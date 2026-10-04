package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.function.LongConsumer;

/** Portable cut transfer with independently checked implications on BOTH sides of a mapping. */
public final class CountAffineProof {

    private CountAffineProof() {}

    /** Mapping rows encode x_i = terms * y + upper (upper is an offset here). */
    public record Certificate(int originalVariables, List<CountProof.Row> originalAxioms,
                              CountProof.Derivation source, List<CountProof.Row> sourceMapping,
                              CountProof.Row originalCut, int targetVariables, List<CountProof.Row> targetAxioms,
                              List<CountProof.Row> targetMapping, CountProof.Row targetCut) {

        public Certificate {
            originalAxioms = List.copyOf(originalAxioms);
            sourceMapping = List.copyOf(sourceMapping);
            targetAxioms = List.copyOf(targetAxioms);
            targetMapping = List.copyOf(targetMapping);
        }
    }

    public static CountProof.Verdict verify(Certificate proof, long maximumWork) {
        return verify(proof, maximumWork, ignored -> {});
    }

    static CountProof.Verdict verify(Certificate p, long maximumWork, LongConsumer charged) {
        if (maximumWork <= 0 || p.originalVariables < 0 || p.originalVariables > 1024 || p.targetVariables < 0 ||
                p.targetVariables > 1024 || p.source.variables() < 0 || p.source.variables() > 1024 ||
                p.source.steps().isEmpty() || p.source.steps().size() > 64 || p.originalAxioms.size() > 4096 ||
                p.targetAxioms.size() > 4096 || !valid(p.originalCut, p.originalVariables) || !valid(p.targetCut, p.targetVariables))
            return CountProof.Verdict.INVALID;
        long[] left = { maximumWork };
        try {
            CountProof.Verdict source = CountProof.verify(p.source, left[0], spent -> left[0] -= spent);
            if (source != CountProof.Verdict.VERIFIED) return source;
            if (left[0] <= 0) return CountProof.Verdict.INCOMPLETE;
            var originalInSource = substitute(p.originalCut, p.sourceMapping, p.originalVariables, p.source.variables(), left);
            var originalInTarget = substitute(p.originalCut, p.targetMapping, p.originalVariables, p.targetVariables, left);
            if (originalInSource == null || originalInTarget == null ||
                    !normalize(originalInSource).equals(normalize(p.source.steps().get(p.source.steps().size() - 1).consequence())) ||
                    !normalize(originalInTarget).equals(normalize(p.targetCut)))
                return CountProof.Verdict.INVALID;
            // A right inverse alone does not establish coverage. Reprove each
            // claimed consequence from the explicit original/target axioms.
            // Unsupported transfers remain optional and are simply declined.
            var original = implication(p.originalVariables, p.originalAxioms, p.originalCut);
            CountProof.Verdict first = CountProof.verify(original, left[0], spent -> left[0] -= spent);
            if (first != CountProof.Verdict.VERIFIED) return first;
            if (left[0] <= 0) return CountProof.Verdict.INCOMPLETE;
            return CountProof.verify(implication(p.targetVariables, p.targetAxioms, p.targetCut), left[0], spent -> left[0] -= spent);
        } catch (Limit ignored) {
            return CountProof.Verdict.INCOMPLETE;
        } finally {
            charged.accept(maximumWork - left[0]);
        }
    }

    static CountProof.Certificate implication(int variables, List<CountProof.Row> axioms, CountProof.Row cut) {
        return new CountProof.Certificate("affine_cut:scoped_implication", variables, axioms,
                List.of(List.of(opposite(cut))), List.of(), false);
    }

    static CountProof.Row opposite(CountProof.Row row) {
        Map<Integer, BigInteger> terms = new TreeMap<>();
        row.terms().forEach((id, coefficient) -> terms.put(id, coefficient.negate()));
        return new CountProof.Row(terms, row.upper().negate().subtract(BigInteger.ONE));
    }

    static List<CountProof.Row> axioms(CountModelViews.View view) {
        var rows = new ArrayList<>(view.rows().stream().map(CountProof::row).toList());
        for (int i = 0; i < view.lower().length; i++) {
            rows.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), view.lower()[i].negate()));
            if (view.upper()[i] != null) rows.add(new CountProof.Row(Map.of(i, BigInteger.ONE), view.upper()[i]));
        }
        return rows;
    }

    static List<CountProof.Row> mapping(CountModelViews.View view) {
        return view.substitution() == null ? List.of() : view.substitution().coordinates().stream()
                .map(e -> new CountProof.Row(e.terms(), e.constant())).toList();
    }

    static CountProof.Row substitute(CountProof.Row row, List<CountProof.Row> mapping, int originals, int variables, long[] left) {
        if (mapping.isEmpty()) return originals == variables ? row : null;
        if (mapping.size() != originals) return null;
        for (var expression : mapping) {
            tick(left);
            if (!valid(expression, variables)) return null;
        }
        Map<Integer, BigInteger> terms = new TreeMap<>();
        BigInteger upper = row.upper();
        for (var coefficient : row.terms().entrySet()) {
            var expression = mapping.get(coefficient.getKey());
            upper = upper.subtract(coefficient.getValue().multiply(expression.upper()));
            for (var term : expression.terms().entrySet()) {
                tick(left);
                terms.merge(term.getKey(), term.getValue().multiply(coefficient.getValue()), BigInteger::add);
            }
        }
        terms.values().removeIf(v -> v.signum() == 0);
        return new CountProof.Row(terms, upper);
    }

    static CountProof.Row normalize(CountProof.Row row) {
        BigInteger gcd = BigInteger.ZERO;
        for (BigInteger coefficient : row.terms().values()) gcd = gcd.gcd(coefficient);
        if (gcd.signum() == 0) return row;
        Map<Integer, BigInteger> terms = new TreeMap<>();
        for (var term : row.terms().entrySet()) if (term.getValue().signum() != 0) terms.put(term.getKey(), term.getValue().divide(gcd));
        var divided = row.upper().divideAndRemainder(gcd);
        return new CountProof.Row(terms, divided[1].signum() < 0 ? divided[0].subtract(BigInteger.ONE) : divided[0]);
    }

    static boolean valid(CountProof.Row row, int variables) {
        return row != null && row.upper() != null && row.upper().bitLength() <= 4096 && row.terms().size() <= 1024 &&
                row.terms().entrySet().stream().allMatch(e -> e.getKey() >= 0 && e.getKey() < variables && e.getValue() != null && e.getValue().bitLength() <= 4096);
    }

    static void tick(long[] left) {
        if (left[0] <= 0) throw new Limit();
        left[0]--;
    }

    static final class Limit extends RuntimeException {

        Limit() {
            super(null, null, false, false);
        }
    }

    static long bytes(Certificate p) {
        long bytes = 512;
        for (var rows : List.of(p.originalAxioms, p.source.axioms(), p.sourceMapping, p.targetAxioms, p.targetMapping,
                List.of(p.originalCut, p.targetCut)))
            for (var row : rows) {
                bytes += 160L + 96L * row.terms().size() + (row.upper().bitLength() + 7L) / 8;
                for (var c : row.terms().values()) bytes += (c.bitLength() + 7L) / 8;
            }
        for (var step : p.source.steps()) {
            bytes += 256L + 192L * step.parents().size() + 192L * step.consequence().terms().size();
            for (var c : step.parents().values()) bytes += (c.bitLength() + 7L) / 8;
            for (var c : step.consequence().terms().values()) bytes += (c.bitLength() + 7L) / 8;
            bytes += (step.divisor().bitLength() + step.consequence().upper().bitLength() + 14L) / 8;
        }
        return bytes;
    }

    static void write(DataOutputStream out, Certificate p) throws IOException {
        out.writeInt(p.originalVariables);
        CountProof.rows(out, p.originalAxioms);
        out.writeUTF(p.source.scope());
        out.writeInt(p.source.variables());
        CountProof.rows(out, p.source.axioms());
        out.writeInt(p.source.steps().size());
        for (var step : p.source.steps()) {
            CountProof.rows(out, List.of(new CountProof.Row(step.parents(), step.divisor()), step.consequence()));
        }
        CountProof.rows(out, p.sourceMapping);
        CountProof.rows(out, List.of(p.originalCut));
        out.writeInt(p.targetVariables);
        CountProof.rows(out, p.targetAxioms);
        CountProof.rows(out, p.targetMapping);
        CountProof.rows(out, List.of(p.targetCut));
    }

    static Certificate read(DataInputStream in) throws IOException {
        int originalVariables = CountProof.length(in, 1024);
        var axioms = CountProof.rows(in);
        String scope = in.readUTF();
        int sourceVariables = CountProof.length(in, 1024);
        var sourceRows = CountProof.rows(in);
        var steps = new ArrayList<CountProof.Combination>();
        for (int n = CountProof.length(in, 64); n > 0; n--) {
            var pair = CountProof.rows(in);
            if (pair.size() != 2) throw new IOException("Invalid affine derivation");
            steps.add(new CountProof.Combination(pair.get(0).terms(), pair.get(0).upper(), pair.get(1)));
        }
        var sourceMap = CountProof.rows(in);
        var originalCut = CountProof.rows(in);
        int targetVariables = CountProof.length(in, 1024);
        var targetRows = CountProof.rows(in);
        var targetMap = CountProof.rows(in);
        var targetCut = CountProof.rows(in);
        if (originalCut.size() != 1 || targetCut.size() != 1) throw new IOException("Invalid affine consequence");
        return new Certificate(originalVariables, axioms, new CountProof.Derivation(scope, sourceVariables, sourceRows, steps),
                sourceMap, originalCut.get(0), targetVariables, targetRows, targetMap, targetCut.get(0));
    }
}
