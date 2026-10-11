// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;

/**
 * Portable candidate-domain coverage and serial execution certificates. Checking
 * does not invoke the compiler, candidate expander, planner or count search.
 * Coverage is NOT an infeasibility or optimality certificate. In particular, a
 * closed count proof from an earlier restricted view cannot be promoted by it.
 */
public final class CapturedProof {

    private CapturedProof() {}

    public record Pattern(String id, String binding, List<CapturedRecipe.Input<Integer>> inputs,
                          List<CapturedRecipe.Amount<Integer>> outputs, boolean external, boolean complete) {
        public Pattern { inputs = List.copyOf(inputs); outputs = List.copyOf(outputs); }
    }

    /** Resource identity is an integer, never a display name or toString(). */
    public record Snapshot(int resources, List<Pattern> patterns, boolean complete) {
        public Snapshot { patterns = List.copyOf(patterns); }
    }

    public record Request(int target, long amount, Map<Integer, Long> stock, Set<Integer> external,
                          Map<Integer, Long> requiredSeeds, boolean preserve, boolean forceCraft) {
        public Request { stock = Map.copyOf(stock); external = Set.copyOf(external); requiredSeeds = Map.copyOf(requiredSeeds); }
    }

    public enum Kind { BATCH, SEQUENCE, REPEAT }

    /** Children refer strictly backwards. Repetitions are never expanded. */
    public record Instruction(Kind kind, String recipe, List<Integer> children, long times) {
        public Instruction { children = List.copyOf(children); }
    }

    public record Witness(Map<String, GraphRecipe<Integer>> recipes, List<Instruction> program,
                          Map<Integer, BigInteger> initial, Map<Integer, Long> seeds) {
        public Witness {
            recipes = Collections.unmodifiableMap(new LinkedHashMap<>(recipes));
            program = List.copyOf(program);
            initial = Map.copyOf(initial);
            seeds = Map.copyOf(seeds);
        }
    }

    /** A null witness asserts coverage only; it asserts nothing about satisfiability. */
    public record Certificate(Snapshot snapshot, Request request, CapturedCatalog.Coverage coverage, Witness witness) {}

    public record Replay(CountProof.Verdict coverage, CountProof.Verdict execution, boolean witnessPresent) {}

    /** Bounded export; an export limit never changes the already computed plan. */
    static <K> Certificate capture(List<CapturedCatalog.Entry<K>> entries, boolean complete, K target, long amount,
                                   Map<K, Long> stock, Set<K> external, Map<K, Long> requiredSeeds,
                                   boolean preserve, boolean forceCraft, CapturedCatalog.Result<K> result,
                                   long maximumWork, long maximumBytes) {
        Guard guard = new Guard(maximumWork, maximumBytes);
        Map<K, Integer> keys = new LinkedHashMap<>();
        java.util.function.Function<K, Integer> key = value -> {
            guard.work();
            Integer id = keys.get(value);
            if (id == null) { guard.keep(128); id = keys.size(); keys.put(value, id); }
            return id;
        };
        var patterns = new ArrayList<Pattern>();
        for (var entry : entries) {
            guard.work(); guard.keep(256); guard.text(entry.id()); guard.text(entry.binding());
            var inputs = new ArrayList<CapturedRecipe.Input<Integer>>();
            for (var input : entry.recipe().inputs()) {
                guard.work(); guard.keep(128);
                var candidates = new ArrayList<CapturedRecipe.Candidate<Integer>>();
                for (var candidate : input.candidates()) {
                    guard.work(); guard.keep(192);
                    candidates.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key.apply(candidate.stack().what()),
                            candidate.stack().amount()), candidate.remaining() == null ? null : key.apply(candidate.remaining()),
                            candidate.configuration(), candidate.reusable()));
                }
                inputs.add(new CapturedRecipe.Input<>(input.multiplier(), candidates));
            }
            var outputs = new ArrayList<CapturedRecipe.Amount<Integer>>();
            for (var output : entry.recipe().outputs()) {
                guard.work(); guard.keep(64);
                outputs.add(new CapturedRecipe.Amount<>(key.apply(output.what()), output.amount()));
            }
            patterns.add(new Pattern(entry.id(), entry.binding(), inputs, outputs, entry.recipe().external(), !entry.recipe().captureBounded()));
        }
        Map<Integer, Long> capturedStock = map(stock, key, guard), seeds = map(requiredSeeds, key, guard);
        Set<Integer> capturedExternal = new LinkedHashSet<>();
        for (K value : external) { guard.work(); guard.keep(64); capturedExternal.add(key.apply(value)); }
        var request = new Request(key.apply(target), amount, capturedStock, capturedExternal, seeds, preserve, forceCraft);
        var coverage = result.coverage();
        for (var domain : coverage.domains()) {
            guard.work(); guard.keep(192); guard.text(domain.id()); guard.integer(domain.expanded()); guard.integer(domain.total());
            for (var ordinal : domain.additional()) { guard.work(); guard.integer(ordinal); }
        }
        Witness witness = null;
        if (result.plan().feasible()) {
            var recipes = new LinkedHashMap<String, GraphRecipe<Integer>>();
            for (var recipe : result.plan().recipes().values()) {
                guard.work(); guard.keep(512); guard.text(recipe.id()); guard.text(recipe.binding());
                var slots = new ArrayList<GraphRecipe.Slot<Integer>>();
                for (var slot : recipe.slots()) {
                    guard.work(); guard.keep(192);
                    slots.add(new GraphRecipe.Slot<>(key.apply(slot.key()), slot.amount(), slot.inputSlot(), slot.configuration(), slot.reusable()));
                }
                recipes.put(recipe.id(), new GraphRecipe<>(recipe.id(), recipe.binding(), slots, map(recipe.outputs(), key, guard)));
            }
            Map<Integer, BigInteger> initial = new LinkedHashMap<>();
            for (var value : result.plan().initialExact().entrySet()) {
                guard.work(); guard.keep(96); guard.integer(value.getValue());
                initial.put(key.apply(value.getKey()), value.getValue());
            }
            witness = new Witness(recipes, program(result.plan().steps(), guard), initial, map(result.plan().seeds(), key, guard));
        }
        return new Certificate(new Snapshot(keys.size(), patterns, complete), request, coverage, witness);
    }

    private static <K> Map<Integer, Long> map(Map<K, Long> values, java.util.function.Function<K, Integer> key, Guard guard) {
        Map<Integer, Long> result = new LinkedHashMap<>();
        for (var e : values.entrySet()) { guard.work(); guard.keep(96); result.put(key.apply(e.getKey()), e.getValue()); }
        return result;
    }

    private static List<Instruction> program(PlanStep root, Guard guard) {
        Map<PlanStep, Integer> ids = new IdentityHashMap<>();
        record Frame(PlanStep step, boolean expanded) {}
        var pending = new ArrayDeque<Frame>();
        var result = new ArrayList<Instruction>();
        pending.push(new Frame(root, false));
        while (!pending.isEmpty()) {
            guard.work();
            var frame = pending.pop();
            if (ids.containsKey(frame.step())) continue;
            if (!frame.expanded()) {
                guard.keep(192);
                pending.push(new Frame(frame.step(), true));
                if (frame.step() instanceof PlanStep.Sequence sequence) {
                    for (int i = sequence.children().size() - 1; i >= 0; i--) {
                        guard.work(); guard.keep(48); pending.push(new Frame(sequence.children().get(i), false));
                    }
                } else if (frame.step() instanceof PlanStep.Repeat repeat) pending.push(new Frame(repeat.body(), false));
            } else {
                Instruction op;
                if (frame.step() instanceof PlanStep.Batch batch) {
                    guard.text(batch.recipe()); op = new Instruction(Kind.BATCH, batch.recipe(), List.of(), batch.runs());
                } else if (frame.step() instanceof PlanStep.Repeat repeat) {
                    op = new Instruction(Kind.REPEAT, null, List.of(ids.get(repeat.body())), repeat.times());
                } else {
                    var children = new ArrayList<Integer>();
                    for (var child : ((PlanStep.Sequence) frame.step()).children()) { guard.work(); children.add(ids.get(child)); }
                    op = new Instruction(Kind.SEQUENCE, null, children, 1);
                }
                ids.put(frame.step(), result.size()); result.add(op);
            }
        }
        return result;
    }

    public static Replay verify(Certificate proof, long maximumWork, long maximumBytes) {
        Guard guard = new Guard(maximumWork, maximumBytes);
        boolean covered = false;
        try {
            Checker checker = new Checker(proof, guard);
            checker.coverage(); covered = true;
            if (proof.witness() != null) checker.execution();
            return new Replay(CountProof.Verdict.VERIFIED,
                    proof.witness() == null ? CountProof.Verdict.INCOMPLETE : CountProof.Verdict.VERIFIED, proof.witness() != null);
        } catch (Limit exhausted) {
            return new Replay(covered ? CountProof.Verdict.VERIFIED : CountProof.Verdict.INCOMPLETE,
                    CountProof.Verdict.INCOMPLETE, proof.witness() != null);
        } catch (IllegalArgumentException | ArithmeticException | IndexOutOfBoundsException | NullPointerException invalid) {
            return new Replay(covered ? CountProof.Verdict.VERIFIED : CountProof.Verdict.INVALID,
                    CountProof.Verdict.INVALID, proof != null && proof.witness() != null);
        }
    }

    private static final class Checker {
        final Certificate proof;
        final Guard guard;
        final Map<String, Pattern> patterns = new LinkedHashMap<>();
        final Map<String, BigInteger> sizes = new HashMap<>();
        final Map<String, CapturedCatalog.DomainCoverage> domains = new HashMap<>();
        final Map<Integer, Set<String>> producers = new HashMap<>();

        Checker(Certificate proof, Guard guard) { this.proof = proof; this.guard = guard; }

        void key(int key) { require(key >= 0 && key < proof.snapshot().resources()); }

        void amounts(Map<Integer, Long> values) {
            for (var e : values.entrySet()) { guard.work(); key(e.getKey()); require(e.getValue() > 0); }
        }

        void coverage() {
            require(proof.snapshot().resources() > 0 && proof.request().amount() > 0);
            key(proof.request().target()); amounts(proof.request().stock()); amounts(proof.request().requiredSeeds());
            for (int key : proof.request().external()) { guard.work(); key(key); }
            boolean complete = proof.snapshot().complete();
            for (var pattern : proof.snapshot().patterns()) {
                guard.work(); guard.keep(256); guard.text(pattern.id()); guard.text(pattern.binding());
                require(patterns.put(pattern.id(), pattern) == null);
                BigInteger total = BigInteger.ONE;
                for (var input : pattern.inputs()) {
                    guard.work(); require(input.multiplier() > 0);
                    for (var candidate : input.candidates()) {
                        guard.work(); key(candidate.stack().what()); require(candidate.stack().amount() > 0);
                        require(!candidate.reusable() || candidate.configuration());
                        if (candidate.remaining() != null) key(candidate.remaining());
                    }
                    total = total.multiply(choices(pattern, input)); guard.integer(total);
                }
                for (var output : pattern.outputs()) { guard.work(); key(output.what()); require(output.amount() >= 0); }
                sizes.put(pattern.id(), total);
                complete &= pattern.complete();
                if (total.signum() > 0) {
                    for (var output : pattern.outputs()) if (output.amount() > 0) producer(output.what(), pattern.id());
                    for (var input : pattern.inputs()) for (var candidate : input.candidates())
                        if (candidate.remaining() != null) producer(candidate.remaining(), pattern.id());
                }
            }
            require(proof.coverage().revision() >= 0);
            for (var domain : proof.coverage().domains()) {
                guard.work(); guard.keep(256); guard.integer(domain.total()); guard.integer(domain.expanded());
                require(patterns.containsKey(domain.id()) && domains.put(domain.id(), domain) == null);
                require(domain.total().equals(sizes.get(domain.id())) && domain.expanded().signum() >= 0 &&
                        domain.expanded().compareTo(domain.total()) <= 0 && domain.captureComplete() == patterns.get(domain.id()).complete());
                Set<BigInteger> extra = new HashSet<>();
                for (var ordinal : domain.additional()) {
                    guard.work(); guard.integer(ordinal); guard.keep(64);
                    require(ordinal.compareTo(domain.expanded()) >= 0 && ordinal.compareTo(domain.total()) < 0 && extra.add(ordinal));
                }
                complete &= domain.expanded().equals(domain.total());
            }
            // A complete claim must include providers reached through ALL input
            // alternatives and physical container returns, not just the first window.
            if (proof.coverage().complete()) {
                require(complete && proof.coverage().revision() > 0);
                Set<Integer> visited = new HashSet<>();
                Set<String> reached = new HashSet<>();
                var pending = new ArrayDeque<Integer>();
                pending.add(proof.request().target()); pending.addAll(proof.request().requiredSeeds().keySet());
                while (!pending.isEmpty()) {
                    guard.work(); int key = pending.removeFirst();
                    if (!visited.add(key)) continue;
                    guard.keep(96);
                    for (String id : producers.getOrDefault(key, Set.of())) {
                        guard.work(); require(domains.containsKey(id));
                        if (reached.add(id)) for (var input : patterns.get(id).inputs()) for (var candidate : input.candidates()) {
                            guard.work(); guard.keep(32); pending.add(candidate.stack().what());
                        }
                    }
                }
            }
        }

        void producer(int key, String id) {
            guard.work(); guard.keep(128);
            producers.computeIfAbsent(key, unused -> new HashSet<>()).add(id);
        }

        BigInteger choices(Pattern pattern, CapturedRecipe.Input<Integer> input) {
            return !pattern.external() && input.multiplier() <= 9 ?
                    compositions(input.candidates().size(), (int) input.multiplier()) : BigInteger.valueOf(input.candidates().size());
        }

        BigInteger compositions(int choices, int copies) {
            if (copies == 0) return BigInteger.ONE;
            if (choices == 0) return BigInteger.ZERO;
            BigInteger count = BigInteger.ONE;
            for (int i = 1; i <= copies; i++) { guard.work(); count = count.multiply(BigInteger.valueOf((long) choices + i - 1)).divide(BigInteger.valueOf(i)); }
            guard.integer(count); return count;
        }

        GraphRecipe<Integer> restore(String id) {
            guard.work(); guard.text(id);
            int split = id.lastIndexOf('#'); require(split >= 0);
            String source = id.substring(0, split);
            Pattern pattern = patterns.get(source); require(pattern != null);
            BigInteger ordinal = new BigInteger(id.substring(split + 1)); guard.integer(ordinal);
            require(id.equals(source + "#" + ordinal) && ordinal.signum() >= 0 && ordinal.compareTo(sizes.get(source)) < 0);
            var domain = domains.get(source); require(domain != null);
            boolean included = ordinal.compareTo(domain.expanded()) < 0;
            if (!included) for (var extra : domain.additional()) { guard.work(); included |= ordinal.equals(extra); }
            require(included);
            guard.keep(64L * pattern.inputs().size() + 512);
            BigInteger[] ranks = new BigInteger[pattern.inputs().size()];
            for (int slot = ranks.length - 1; slot >= 0; slot--) {
                guard.work(); var qr = ordinal.divideAndRemainder(choices(pattern, pattern.inputs().get(slot)));
                ranks[slot] = qr[1]; ordinal = qr[0];
            }
            var inputs = new ArrayList<GraphRecipe.Slot<Integer>>();
            Map<Integer, Long> outputs = new LinkedHashMap<>();
            for (var output : pattern.outputs()) { guard.work(); guard.keep(96); outputs.merge(output.what(), output.amount(), Math::addExact); }
            for (int slot = 0; slot < ranks.length; slot++) {
                var input = pattern.inputs().get(slot); int n = input.candidates().size();
                BigInteger rank = ranks[slot];
                if (rank.compareTo(BigInteger.valueOf(n)) < 0) add(inputs, outputs, slot, input.candidates().get(rank.intValueExact()), input.multiplier());
                else {
                    require(!pattern.external() && input.multiplier() <= 9);
                    // Independently unrank full weak-composition vectors, removing
                    // the singletons that occupy the first n canonical positions.
                    rank = rank.subtract(BigInteger.valueOf(n));
                    int left = (int) input.multiplier(); boolean selected = false;
                    for (int at = 0; at < n; at++) {
                        boolean found = false;
                        for (int count = left; count >= 0; count--) {
                            guard.work();
                            BigInteger block = compositions(n - at - 1, left - count);
                            if (!selected) {
                                if (count == left) block = BigInteger.ZERO;
                                else if (count == 0) block = block.subtract(BigInteger.valueOf(n - at - 1));
                            }
                            if (rank.compareTo(block) >= 0) rank = rank.subtract(block);
                            else {
                                if (count > 0) { add(inputs, outputs, slot, input.candidates().get(at), count); selected = true; }
                                left -= count; found = true; break;
                            }
                        }
                        require(found);
                    }
                    require(left == 0 && rank.signum() == 0);
                }
            }
            return new GraphRecipe<>(id, pattern.binding(), inputs, outputs);
        }

        void add(List<GraphRecipe.Slot<Integer>> slots, Map<Integer, Long> outputs, int slot,
                 CapturedRecipe.Candidate<Integer> candidate, long copies) {
            guard.work(); guard.keep(384);
            long amount = Math.multiplyExact(candidate.stack().amount(), copies);
            slots.add(new GraphRecipe.Slot<>(candidate.stack().what(), amount, slot, candidate.configuration(), candidate.reusable()));
            if (candidate.reusable()) outputs.merge(candidate.stack().what(), amount, Math::addExact);
            if (candidate.remaining() != null) outputs.merge(candidate.remaining(), copies, Math::addExact);
        }

        void execution() {
            var witness = proof.witness();
            amounts(witness.seeds());
            for (var e : witness.initial().entrySet()) {
                guard.work(); key(e.getKey()); guard.integer(e.getValue()); require(e.getValue().signum() > 0);
                require(proof.request().external().contains(e.getKey()) || e.getValue().compareTo(BigInteger.valueOf(proof.request().stock().getOrDefault(e.getKey(), 0L))) <= 0);
            }
            Map<String, Summary> recipes = new HashMap<>();
            for (var e : witness.recipes().entrySet()) {
                guard.work(); GraphRecipe<Integer> restored = restore(e.getKey());
                require(restored.equals(e.getValue()));
                var summary = new Summary();
                for (var slot : restored.slots()) {
                    guard.work(); BigInteger amount = BigInteger.valueOf(slot.amount());
                    summary.add(slot.key(), amount, amount.negate(), BigInteger.ZERO);
                }
                for (var output : restored.outputs().entrySet()) {
                    guard.work(); summary.output(output.getKey(), BigInteger.valueOf(output.getValue()));
                }
                for (var slot : restored.slots()) if (slot.reusable()) {
                    guard.work(); summary.produced.merge(slot.key(), BigInteger.valueOf(slot.amount()).negate(), BigInteger::add);
                }
                recipes.put(e.getKey(), summary);
            }
            require(!witness.program().isEmpty());
            List<Summary> summaries = new ArrayList<>();
            for (var op : witness.program()) {
                guard.work(); guard.keep(256); require(op.times() >= 0);
                for (int child : op.children()) { guard.work(); require(child >= 0 && child < summaries.size()); }
                Summary next;
                switch (op.kind()) {
                    case BATCH -> {
                        require(op.recipe() != null && op.children().isEmpty());
                        Summary recipe = recipes.get(op.recipe()); require(recipe != null);
                        next = recipe.repeat(op.times());
                    }
                    case REPEAT -> {
                        require(op.recipe() == null && op.children().size() == 1);
                        next = summaries.get(op.children().get(0)).repeat(op.times());
                    }
                    case SEQUENCE -> {
                        require(op.recipe() == null && op.times() == 1);
                        next = new Summary();
                        for (int child : op.children()) next.then(summaries.get(child));
                    }
                    default -> throw new IllegalArgumentException("Unknown opcode");
                }
                summaries.add(next);
            }
            Summary total = summaries.get(summaries.size() - 1);
            for (var e : total.need.entrySet()) { guard.work(); require(witness.initial().getOrDefault(e.getKey(), BigInteger.ZERO).compareTo(e.getValue()) >= 0); }
            Map<Integer, BigInteger> goals = new HashMap<>();
            for (var e : witness.seeds().entrySet()) { guard.work(); guard.keep(96); goals.put(e.getKey(), BigInteger.valueOf(e.getValue())); }
            for (var e : proof.request().requiredSeeds().entrySet()) { guard.work(); guard.keep(96); goals.merge(e.getKey(), BigInteger.valueOf(e.getValue()), BigInteger::max); }
            goals.merge(proof.request().target(), BigInteger.valueOf(proof.request().amount()), BigInteger::add);
            for (var e : goals.entrySet()) {
                guard.work(); require(witness.initial().getOrDefault(e.getKey(), BigInteger.ZERO)
                        .add(total.delta.getOrDefault(e.getKey(), BigInteger.ZERO)).compareTo(e.getValue()) >= 0);
            }
            if (proof.request().forceCraft() && !proof.request().external().contains(proof.request().target())) {
                BigInteger net = total.delta.getOrDefault(proof.request().target(), BigInteger.ZERO);
                BigInteger amount = BigInteger.valueOf(proof.request().amount());
                require(net.signum() > 0 && total.produced.getOrDefault(proof.request().target(), BigInteger.ZERO).compareTo(amount) >= 0);
                // The stronger no-redundant-turnover certificate used by the
                // planner for seed-consuming orders is a separate proof obligation.
                // Do not silently approve such a case just from gross production.
                if (net.compareTo(amount) < 0) throw new Limit();
            }
        }

        private final class Summary {
            final Map<Integer, BigInteger> need = new HashMap<>(), delta = new HashMap<>(), produced = new HashMap<>();
            void add(int key, BigInteger input, BigInteger change, BigInteger output) {
                guard.keep(384); need.merge(key, input, BigInteger::add); delta.merge(key, change, BigInteger::add); produced.merge(key, output, BigInteger::add);
            }
            void output(int key, BigInteger output) { add(key, BigInteger.ZERO, output, output); }
            void then(Summary child) {
                for (int key : child.need.keySet()) {
                    guard.work(); guard.keep(384);
                    BigInteger previous = delta.getOrDefault(key, BigInteger.ZERO);
                    BigInteger required = need.getOrDefault(key, BigInteger.ZERO).max(child.need.get(key).subtract(previous));
                    BigInteger change = previous.add(child.delta.get(key));
                    BigInteger output = produced.getOrDefault(key, BigInteger.ZERO).add(child.produced.get(key));
                    guard.integer(required); guard.integer(change); guard.integer(output);
                    need.put(key, required); delta.put(key, change); produced.put(key, output);
                }
            }
            Summary repeat(long times) {
                var result = new Summary();
                if (times == 0) return result;
                BigInteger count = BigInteger.valueOf(times);
                for (int key : need.keySet()) {
                    guard.work();
                    BigInteger required = need.get(key).add(delta.get(key).negate().max(BigInteger.ZERO).multiply(count.subtract(BigInteger.ONE)));
                    BigInteger change = delta.get(key).multiply(count), output = produced.get(key).multiply(count);
                    guard.integer(required); guard.integer(change); guard.integer(output);
                    result.add(key, required, change, output);
                }
                return result;
            }
        }
    }

    static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid captured certificate"); }

    /** Conservative cumulative allocations, including discarded intermediate integers. */
    static final class Guard {
        long work, bytes;
        Guard(long work, long bytes) { if (work <= 0 || bytes <= 0) throw new IllegalArgumentException("Invalid proof limits"); this.work = work; this.bytes = bytes; }
        void work() { if (--work < 0) throw new Limit(); }
        void keep(long size) { if (size < 0 || size > bytes) throw new Limit(); bytes -= size; }
        void integer(BigInteger value) { keep(80L + ((long) value.bitLength() + 32) / 8); }
        void text(String value) { keep(64L + 2L * value.length()); }
    }

    public static final class Limit extends RuntimeException {
        private Limit() { super("Candidate proof export/replay limit", null, false, false); }
    }

    public static void write(Path path, Certificate proof) throws IOException { CapturedProofCodec.write(path, proof); }
    public static Certificate read(Path path) throws IOException { return CapturedProofCodec.read(path); }

    public static void main(String[] args) throws IOException {
        var proof = read(Path.of(args[0]));
        var replay = verify(proof, 20_000_000, 128L << 20);
        System.out.println("candidate_coverage=" + replay.coverage() + "; complete=" + proof.coverage().complete() +
                "; execution=" + replay.execution() + "; witness=" + replay.witnessPresent() + "; no_infeasibility_or_optimality_claim");
        if (replay.coverage() != CountProof.Verdict.VERIFIED || replay.witnessPresent() && replay.execution() != CountProof.Verdict.VERIFIED)
            throw new IllegalStateException("Incomplete or invalid captured certificate");
    }
}
