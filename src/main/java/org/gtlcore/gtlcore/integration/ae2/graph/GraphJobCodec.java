package org.gtlcore.gtlcore.integration.ae2.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.api.stacks.AEKey;
import org.cgse.core.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned logical state. No provider object, world reference, or floating material amount is saved. */
public final class GraphJobCodec {

    public static final String NBT_KEY = "gtlcoreGraphJob";
    private static final int SCHEMA = 10;
    private static final int MAX_ENTRIES = 100_000;

    private GraphJobCodec() {}

    public static CompoundTag write(GraphJobRuntime.Snapshot<AEKey> state) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schemaVersion", SCHEMA);
        tag.putString("engineKind", "graph");
        GraphPlan<AEKey> plan = state.plan();
        tag.put("target", plan.target().toTagGeneric());
        tag.putLong("amount", plan.amount());
        tag.putBoolean("preserve", plan.preserveSeeds());
        tag.put("initial", amounts(plan.initial()));
        tag.put("initialExact", exactAmounts(plan.initialExact()));
        tag.put("deferredExternal", exactAmounts(state.deferredExternal()));
        tag.put("seeds", amounts(plan.seeds()));
        if (plan.seedOptimality() != null) {
            var proof = plan.seedOptimality();
            CompoundTag saved = new CompoundTag();
            saved.putInt("lowerTypes", proof.lowerTypeBound());
            saved.putInt("types", proof.types());
            saved.putBoolean("cardinalityProven", proof.cardinalityProven());
            saved.putBoolean("quantitiesProven", proof.quantitiesParetoProven());
            saved.putBoolean("fundedPreview", proof.fundedPreview());
            saved.putBoolean("baseMaterialTradeoff", proof.baseMaterialTradeoff());
            tag.put("seedOptimality", saved);
        }
        tag.put("steps", step(plan.steps()));
        ListTag recipes = new ListTag();
        plan.recipes().values().forEach(recipe -> {
            CompoundTag row = new CompoundTag();
            row.putString("id", recipe.id());
            row.putString("binding", recipe.binding());
            ListTag slots = new ListTag();
            recipe.slots().forEach(slot -> {
                CompoundTag input = new CompoundTag();
                input.put("key", slot.key().toTagGeneric());
                input.putLong("amount", slot.amount());
                input.putInt("inputSlot", slot.inputSlot());
                input.putBoolean("configuration", slot.configuration());
                input.putBoolean("reusable", slot.reusable());
                slots.add(input);
            });
            row.put("slots", slots);
            row.put("outputs", amounts(recipe.outputs()));
            recipes.add(row);
        });
        tag.put("recipes", recipes);
        tag.put("owned", amounts(state.owned()));
        tag.put("expected", amounts(state.expected()));
        tag.put("uncertainInputs", amounts(state.uncertainInputs()));
        CompoundTag accepted = new CompoundTag();
        state.acceptedRuns().forEach((id, count) -> exact(accepted, id, count));
        tag.put("acceptedRuns", accepted);
        CompoundTag committed = new CompoundTag();
        state.committedHistory().forEach((id, count) -> exact(committed, id, count));
        tag.put("committedHistory", committed);
        ListTag cursor = new ListTag();
        state.cursor().forEach(position -> {
            CompoundTag row = new CompoundTag();
            row.putInt("node", position.node());
            row.putLong("remaining", position.remaining());
            cursor.add(row);
        });
        tag.put("cursor", cursor);
        ListTag pipeline = new ListTag();
        state.pipeline().forEach(batch -> {
            CompoundTag row = new CompoundTag();
            row.putString("recipe", batch.recipe());
            row.putLong("runs", batch.runs());
            pipeline.add(row);
        });
        tag.put("pipeline", pipeline);
        if (state.pendingSteps() != null) tag.put("pendingSteps", step(state.pendingSteps()));
        tag.putLong("remainingDelivery", state.remainingDelivery());
        tag.putString("state", state.state().name());
        tag.putString("reason", state.reason());
        tag.putBoolean("suspended", state.suspended());
        tag.put("externalWaiting", amounts(state.obligations().external()));
        tag.putLong("nextOutputOwner", state.obligations().nextId());
        ListTag flights = new ListTag();
        for (var flight : state.obligations().flights()) {
            CompoundTag row = new CompoundTag();
            row.putLong("id", flight.id());
            row.putString("recipe", flight.recipe());
            row.putLong("runs", flight.runs());
            row.putBoolean("ambiguous", flight.ambiguous());
            row.put("remaining", amounts(flight.remaining()));
            flights.add(row);
        }
        tag.put("inFlight", flights);
        CompoundTag recovery = new CompoundTag();
        recovery.putString("owner", state.recovery().owner());
        recovery.putString("scope", state.recovery().scope());
        recovery.putString("status", state.recovery().status().name());
        recovery.putLong("stage", state.recovery().stage());
        recovery.put("seeds", amounts(state.recovery().seeds()));
        tag.put("recovery", recovery);
        return tag;
    }

    public static GraphJobRuntime.Snapshot<AEKey> read(CompoundTag tag) {
        int schema = tag.getInt("schemaVersion");
        if ((schema < 1 || schema > SCHEMA) || !tag.getString("engineKind").equals("graph"))
            throw new IllegalArgumentException("Unsupported graph task schema");
        Map<String, GraphRecipe<AEKey>> recipes = new LinkedHashMap<>();
        for (Tag entry : list(tag, "recipes")) {
            CompoundTag row = (CompoundTag) entry;
            List<GraphRecipe.Slot<AEKey>> slots = new ArrayList<>();
            for (Tag input : list(row, "slots")) {
                CompoundTag value = (CompoundTag) input;
                slots.add(new GraphRecipe.Slot<>(key(value.getCompound("key")), amount(value, "amount"), value.getInt("inputSlot"), value.getBoolean("configuration"), schema >= 6 && value.getBoolean("reusable")));
            }
            String id = row.getString("id");
            if (id.isEmpty() || recipes.put(id, new GraphRecipe<>(id, row.getString("binding"), slots,
                    amounts(row, "outputs"))) != null)
                throw new IllegalArgumentException("Duplicate recipe");
        }
        GraphPlan<AEKey> plan = new GraphPlan<>(key(tag.getCompound("target")), amount(tag, "amount"),
                tag.getBoolean("preserve"), step(tag.getCompound("steps"), 0), recipes,
                schema >= 7 ? exactAmounts(tag, "initialExact") : amounts(tag, "initial"), amounts(tag, "seeds"), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        if (tag.contains("seedOptimality", Tag.TAG_COMPOUND)) {
            CompoundTag proof = tag.getCompound("seedOptimality");
            if (proof.getInt("types") != plan.seeds().size()) throw new IllegalArgumentException("Seed proof differs from saved plan");
            plan = plan.withSeedOptimality(new GraphPlan.SeedOptimality(proof.getInt("lowerTypes"), proof.getInt("types"),
                    proof.getBoolean("cardinalityProven"), proof.getBoolean("quantitiesProven"), proof.getBoolean("fundedPreview"), proof.getBoolean("baseMaterialTradeoff")));
        }
        PlanVerifier.verify(plan);
        Map<String, BigInteger> accepted = new LinkedHashMap<>();
        CompoundTag counts = tag.getCompound("acceptedRuns");
        if (counts.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many accepted entries");
        counts.getAllKeys().forEach(id -> accepted.put(id, exact(counts, id)));
        Map<String, BigInteger> committed = new LinkedHashMap<>();
        CompoundTag history = tag.getCompound("committedHistory");
        if (history.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too much committed history");
        history.getAllKeys().forEach(id -> committed.put(id, exact(history, id)));
        List<PlanCursor.Position> cursor = new ArrayList<>();
        for (Tag entry : list(tag, "cursor")) {
            CompoundTag row = (CompoundTag) entry;
            cursor.add(new PlanCursor.Position(row.getInt("node"), amount(row, "remaining")));
        }
        List<PlanStep.Batch> pipeline = new ArrayList<>();
        if (schema >= 4) for (Tag entry : list(tag, "pipeline")) {
            CompoundTag row = (CompoundTag) entry;
            pipeline.add(new PlanStep.Batch(row.getString("recipe"), amount(row, "runs")));
        }
        OutputObligations.Snapshot<AEKey> obligations;
        RecoveryObligation<AEKey> recovery;
        if (schema == 1) {
            // The old format mixed external and machine returns. Preserve their
            // exact amounts as an unknown owner; never infer another dispatch.
            Map<AEKey, Long> expected = amounts(tag, "expected");
            obligations = new OutputObligations.Snapshot<>(Map.of(), expected.isEmpty() ? List.of() : List.of(
                    new OutputObligations.Flight<>(1, "legacy_unknown", 1, expected, true)), 2);
            recovery = new RecoveryObligation<>(java.util.UUID.randomUUID().toString(), "order", plan.seeds(), 0,
                    RecoveryObligation.Status.INTERMEDIATE);
        } else {
            List<OutputObligations.Flight<AEKey>> flights = new ArrayList<>();
            for (Tag entry : list(tag, "inFlight")) {
                CompoundTag row = (CompoundTag) entry;
                flights.add(new OutputObligations.Flight<>(amount(row, "id"), row.getString("recipe"), amount(row, "runs"),
                        amounts(row, "remaining"), row.getBoolean("ambiguous")));
            }
            obligations = new OutputObligations.Snapshot<>(amounts(tag, "externalWaiting"), flights, amount(tag, "nextOutputOwner"));
            CompoundTag row = tag.getCompound("recovery");
            recovery = new RecoveryObligation<>(row.getString("owner"), row.getString("scope"), amounts(row, "seeds"),
                    amount(row, "stage"), RecoveryObligation.Status.valueOf(row.getString("status")));
        }
        return new GraphJobRuntime.Snapshot<>(plan, amounts(tag, "owned"), amounts(tag, "expected"),
                amounts(tag, "uncertainInputs"), accepted, cursor, pipeline, amount(tag, "remainingDelivery"),
                GraphJobRuntime.State.valueOf(tag.getString("state")), tag.getBoolean("suspended"), tag.getString("reason"), obligations, recovery, committed,
                schema >= 7 ? exactAmounts(tag, "deferredExternal") : Map.of(),
                schema >= 10 && tag.contains("pendingSteps", Tag.TAG_COMPOUND) ? step(tag.getCompound("pendingSteps"), 0) : null);
    }

    private static ListTag exactAmounts(Map<AEKey, BigInteger> amounts) {
        ListTag entries = new ListTag();
        amounts.forEach((key, count) -> {
            CompoundTag entry = new CompoundTag();
            entry.put("key", key.toTagGeneric());
            exact(entry, "amount", count);
            entries.add(entry);
        });
        return entries;
    }

    private static Map<AEKey, BigInteger> exactAmounts(CompoundTag tag, String name) {
        Map<AEKey, BigInteger> result = new LinkedHashMap<>();
        for (Tag entry : list(tag, name)) {
            CompoundTag row = (CompoundTag) entry;
            if (result.putIfAbsent(key(row.getCompound("key")), exact(row, "amount")) != null)
                throw new IllegalArgumentException("Duplicate resource");
        }
        return ExactAmounts.copy(result);
    }

    public static ListTag amounts(Map<AEKey, Long> amounts) {
        ListTag entries = new ListTag();
        amounts.forEach((key, count) -> {
            CompoundTag entry = new CompoundTag();
            entry.put("key", key.toTagGeneric());
            entry.putLong("amount", CheckedAmounts.nonNegative(count));
            entries.add(entry);
        });
        return entries;
    }

    public static Map<AEKey, Long> amounts(CompoundTag tag, String name) {
        Map<AEKey, Long> result = new LinkedHashMap<>();
        for (Tag entry : list(tag, name)) {
            CompoundTag row = (CompoundTag) entry;
            AEKey key = key(row.getCompound("key"));
            long count = amount(row, "amount");
            if (result.putIfAbsent(key, count) != null) throw new IllegalArgumentException("Duplicate resource");
        }
        return GraphRecipe.amounts(result);
    }

    private static CompoundTag step(PlanStep step) {
        // A flat, post-order table retains shared calls without coupling program
        // depth to either the Java stack or Minecraft's NBT nesting limit.
        Map<PlanStep, Integer> nodes = new IdentityHashMap<>();
        ListTag rows = new ListTag();
        var pending = new ArrayList<StepFrame>();
        pending.add(new StepFrame(step));
        int edges = 0;
        while (!pending.isEmpty()) {
            if (nodes.size() + pending.size() > MAX_ENTRIES) throw new IllegalArgumentException("Graph task too large");
            StepFrame frame = pending.get(pending.size() - 1);
            PlanStep child = null;
            if (frame.step instanceof PlanStep.Sequence sequence && frame.child < sequence.children().size())
                child = sequence.children().get(frame.child++);
            else if (frame.step instanceof PlanStep.Repeat repeat && frame.child++ == 0) child = repeat.body();
            if (child != null) {
                if (++edges > MAX_ENTRIES) throw new IllegalArgumentException("Too many graph program references");
                if (!nodes.containsKey(child)) pending.add(new StepFrame(child));
                continue;
            }
            CompoundTag row = new CompoundTag();
            if (frame.step instanceof PlanStep.Batch batch) {
                row.putString("kind", "batch");
                row.putString("recipe", batch.recipe());
                row.putLong("count", batch.runs());
            } else if (frame.step instanceof PlanStep.Repeat repeat) {
                row.putString("kind", "repeat");
                row.putLong("count", repeat.times());
                row.putInt("body", nodes.get(repeat.body()));
            } else {
                row.putString("kind", "sequence");
                var children = ((PlanStep.Sequence) frame.step).children();
                int[] references = new int[children.size()];
                for (int i = 0; i < references.length; i++) references[i] = nodes.get(children.get(i));
                row.putIntArray("children", references);
            }
            nodes.put(frame.step, rows.size());
            rows.add(row);
            pending.remove(pending.size() - 1);
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "program");
        tag.put("nodes", rows);
        return tag;
    }

    private static PlanStep step(CompoundTag tag, int depth) {
        if (tag.getString("kind").equals("program")) return program(tag);
        return step(tag, depth, new HashMap<>(), new HashSet<>());
    }

    private static PlanStep program(CompoundTag tag) {
        ListTag rows = list(tag, "nodes");
        if (rows.isEmpty()) throw new IllegalArgumentException("Empty graph program table");
        var nodes = new ArrayList<PlanStep>(rows.size());
        boolean[] referenced = new boolean[rows.size()];
        int edges = 0;
        for (Tag entry : rows) {
            CompoundTag row = (CompoundTag) entry;
            PlanStep step;
            switch (row.getString("kind")) {
                case "batch" -> step = new PlanStep.Batch(row.getString("recipe"), amount(row, "count"));
                case "repeat" -> {
                    if (!row.contains("body", Tag.TAG_INT)) throw new IllegalArgumentException("Missing graph program reference");
                    step = new PlanStep.Repeat(reference(nodes, referenced, row.getInt("body")), amount(row, "count"));
                    edges++;
                }
                case "sequence" -> {
                    if (!row.contains("children", Tag.TAG_INT_ARRAY)) throw new IllegalArgumentException("Missing graph program children");
                    int[] children = row.getIntArray("children");
                    edges = Math.addExact(edges, children.length);
                    if (edges > MAX_ENTRIES) throw new IllegalArgumentException("Too many graph program references");
                    var parts = new ArrayList<PlanStep>(children.length);
                    for (int child : children) parts.add(reference(nodes, referenced, child));
                    step = new PlanStep.Sequence(parts);
                }
                default -> throw new IllegalArgumentException("Unknown graph program node");
            }
            if (edges > MAX_ENTRIES) throw new IllegalArgumentException("Too many graph program references");
            nodes.add(step);
        }
        for (int i = 0; i < referenced.length - 1; i++)
            if (!referenced[i]) throw new IllegalArgumentException("Unreachable graph program node");
        return nodes.get(nodes.size() - 1);
    }

    private static PlanStep reference(List<PlanStep> nodes, boolean[] referenced, int id) {
        // Children must already be defined. Forward, cyclic and out-of-range
        // references are rejected before a PlanStep can be constructed.
        if (id < 0 || id >= nodes.size()) throw new IllegalArgumentException("Invalid or cyclic graph program reference");
        referenced[id] = true;
        return nodes.get(id);
    }

    private static final class StepFrame {

        final PlanStep step;
        int child;

        StepFrame(PlanStep step) {
            this.step = step;
        }
    }

    private static PlanStep step(CompoundTag tag, int depth, Map<Integer, PlanStep> nodes, Set<Integer> defined) {
        // Legacy files were written without the old 128-level read cap. Accept
        // the nesting Minecraft itself can store; new programs use a flat table.
        if (depth > 512) throw new IllegalArgumentException("Graph plan nesting too deep");
        if (tag.getString("kind").equals("ref")) {
            if (!tag.contains("node", Tag.TAG_INT) || !nodes.containsKey(tag.getInt("node")))
                throw new IllegalArgumentException("Invalid or cyclic graph program reference");
            return nodes.get(tag.getInt("node"));
        }
        Integer id = tag.contains("node", Tag.TAG_INT) ? tag.getInt("node") : null;
        if (id != null && (id < 0 || id >= MAX_ENTRIES || !defined.add(id)))
            throw new IllegalArgumentException("Duplicate graph program definition");
        PlanStep result = switch (tag.getString("kind")) {
            case "batch" -> new PlanStep.Batch(tag.getString("recipe"), amount(tag, "count"));
            case "repeat" -> new PlanStep.Repeat(step(tag.getCompound("body"), depth + 1, nodes, defined), amount(tag, "count"));
            case "sequence" -> {
                List<PlanStep> children = new ArrayList<>();
                for (Tag entry : list(tag, "children")) children.add(step((CompoundTag) entry, depth + 1, nodes, defined));
                yield new PlanStep.Sequence(children);
            }
            default -> throw new IllegalArgumentException("Unknown graph step");
        };
        if (id != null) nodes.put(id, result);
        return result;
    }

    private static ListTag list(CompoundTag tag, String name) {
        ListTag result = tag.getList(name, Tag.TAG_COMPOUND);
        if (result.size() > MAX_ENTRIES) throw new IllegalArgumentException("Graph task too large");
        return result;
    }

    private static void exact(CompoundTag tag, String name, BigInteger count) {
        ExactAmounts.of(count);
        if (count.compareTo(ExactAmounts.LONG_MAX) <= 0) tag.putLong(name, count.longValueExact());
        else tag.putByteArray(name, count.toByteArray());
    }

    private static BigInteger exact(CompoundTag tag, String name) {
        if (tag.contains(name, Tag.TAG_LONG)) return BigInteger.valueOf(amount(tag, name));
        if (!tag.contains(name, Tag.TAG_BYTE_ARRAY)) throw new IllegalArgumentException("Expected exact integer: " + name);
        byte[] bytes = tag.getByteArray(name);
        if (bytes.length == 0 || bytes.length > 4096) throw new IllegalArgumentException("Invalid integer size");
        return ExactAmounts.of(new BigInteger(bytes));
    }

    private static long amount(CompoundTag tag, String name) {
        if (!tag.contains(name, Tag.TAG_LONG)) throw new IllegalArgumentException("Expected exact long: " + name);
        return CheckedAmounts.nonNegative(tag.getLong(name));
    }

    private static AEKey key(CompoundTag tag) {
        return Objects.requireNonNull(AEKey.fromTagGeneric(tag), "Unknown AE key");
    }
}
