package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.function.LongConsumer;

/** Guarded cross-view clauses retain both their coordinates and independently checked scopes. */
public final class CountAffineConflictProof {

    private CountAffineConflictProof() {}

    public record Certificate(int originalVariables, List<CountProof.Row> originalAxioms,
                              CountProof.Certificate source, List<CountProof.Row> sourceMapping,
                              List<CountProof.Row> originalClause, int targetVariables, List<CountProof.Row> targetAxioms,
                              List<CountProof.Row> targetMapping, List<CountProof.Row> targetClause) {

        public Certificate {
            originalAxioms = List.copyOf(originalAxioms);
            sourceMapping = List.copyOf(sourceMapping);
            originalClause = List.copyOf(originalClause);
            targetAxioms = List.copyOf(targetAxioms);
            targetMapping = List.copyOf(targetMapping);
            targetClause = List.copyOf(targetClause);
        }
    }

    public static CountProof.Verdict verify(Certificate p, long maximumWork) {
        return verify(p, maximumWork, ignored -> {});
    }

    static CountProof.Verdict verify(Certificate p, long maximumWork, LongConsumer charged) {
        if (maximumWork <= 0 || p.originalVariables < 0 || p.originalVariables > 1024 || p.targetVariables < 0 ||
                p.targetVariables > 1024 || p.source.variables() < 0 || p.source.variables() > 1024 ||
                p.source.forbidden().size() != 1 || p.source.forbidden().get(0).size() > 16 || !p.source.derived().isEmpty() || !p.source.farkas().isEmpty() ||
                p.originalClause.size() > 16 || p.targetClause.size() > 16 || p.originalAxioms.size() > 4096 || p.targetAxioms.size() > 4096)
            return CountProof.Verdict.INVALID;
        long[] left = { maximumWork };
        try {
            var source = CountProof.verify(p.source, left[0], spent -> left[0] -= spent);
            if (source != CountProof.Verdict.VERIFIED) return source;
            var from = new ArrayList<CountProof.Row>();
            var to = new ArrayList<CountProof.Row>();
            for (var row : p.originalClause) {
                CountAffineProof.tick(left);
                if (!CountAffineProof.valid(row, p.originalVariables)) return CountProof.Verdict.INVALID;
                var a = CountAffineProof.substitute(row, p.sourceMapping, p.originalVariables, p.source.variables(), left);
                var b = CountAffineProof.substitute(row, p.targetMapping, p.originalVariables, p.targetVariables, left);
                if (a == null || b == null) return CountProof.Verdict.INVALID;
                from.add(a);
                to.add(b);
            }
            var first = conjunction(p.source.forbidden().get(0));
            var last = conjunction(p.targetClause);
            if (first == null || last == null || !first.equals(conjunction(from)) || !last.equals(conjunction(to))) return CountProof.Verdict.INVALID;
            if (left[0] <= 0) return CountProof.Verdict.INCOMPLETE;
            var original = CountProof.verify(clause(p.originalVariables, p.originalAxioms, p.originalClause), left[0], spent -> left[0] -= spent);
            if (original != CountProof.Verdict.VERIFIED) return original;
            if (left[0] <= 0) return CountProof.Verdict.INCOMPLETE;
            return CountProof.verify(clause(p.targetVariables, p.targetAxioms, p.targetClause), left[0], spent -> left[0] -= spent);
        } catch (CountAffineProof.Limit ignored) {
            return CountProof.Verdict.INCOMPLETE;
        } finally {
            charged.accept(maximumWork - left[0]);
        }
    }

    static CountProof.Certificate clause(int variables, List<CountProof.Row> axioms, List<CountProof.Row> clause) {
        return new CountProof.Certificate("affine_conflict:scoped_implication", variables, axioms, List.of(clause), List.of(), false);
    }

    private static Map<Integer, BigInteger> conjunction(List<CountProof.Row> rows) {
        Map<Integer, BigInteger> result = new TreeMap<>();
        for (var raw : rows) {
            if (!CountAffineProof.valid(raw, 1024) || raw.terms().size() != 1) return null;
            var row = CountAffineProof.normalize(raw);
            if (row.terms().size() != 1) return null;
            var term = row.terms().entrySet().iterator().next();
            result.merge(2 * term.getKey() + (term.getValue().signum() > 0 ? 1 : 0), row.upper(), BigInteger::min);
        }
        return result;
    }

    static long bytes(Certificate p) {
        long size = 512;
        for (var rows : List.of(p.originalAxioms, p.source.axioms(), p.sourceMapping, p.originalClause,
                p.targetAxioms, p.targetMapping, p.targetClause))
            for (var row : rows) {
                size += 160L + 96L * row.terms().size() + (row.upper().bitLength() + 7L) / 8;
                for (var coefficient : row.terms().values()) size += (coefficient.bitLength() + 7L) / 8;
            }
        for (var clause : p.source.forbidden()) for (var row : clause) {
            size += 160L + 96L * row.terms().size() + (row.upper().bitLength() + 7L) / 8;
            for (var coefficient : row.terms().values()) size += (coefficient.bitLength() + 7L) / 8;
        }
        return size;
    }

    static void write(DataOutputStream out, Certificate p) throws IOException {
        out.writeInt(p.originalVariables);
        CountProof.rows(out, p.originalAxioms);
        CountProof.certificate(out, p.source, 0x43475045);
        CountProof.rows(out, p.sourceMapping);
        CountProof.rows(out, p.originalClause);
        out.writeInt(p.targetVariables);
        CountProof.rows(out, p.targetAxioms);
        CountProof.rows(out, p.targetMapping);
        CountProof.rows(out, p.targetClause);
    }

    static Certificate read(DataInputStream in) throws IOException {
        int original = CountProof.length(in, 1024);
        var axioms = CountProof.rows(in);
        var source = CountProof.certificate(in, 0x43475045);
        var map = CountProof.rows(in);
        var clause = CountProof.rows(in);
        int target = CountProof.length(in, 1024);
        var rows = CountProof.rows(in);
        return new Certificate(original, axioms, source, map, clause, target, rows, CountProof.rows(in), CountProof.rows(in));
    }
}
