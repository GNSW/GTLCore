package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.graph.core.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.api.stacks.AEKey;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Versioned logical state. No provider object, world reference, or floating material amount is saved. */
public final class GraphJobCodec {

    public static final String NBT_KEY = "gtlcoreGraphJob";
    private static final int SCHEMA = 6;
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
        tag.put("seeds", amounts(plan.seeds()));
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
                amounts(tag, "initial"), amounts(tag, "seeds"), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
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
                GraphJobRuntime.State.valueOf(tag.getString("state")), tag.getBoolean("suspended"), tag.getString("reason"), obligations, recovery, committed);
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
        CompoundTag tag = new CompoundTag();
        if (step instanceof PlanStep.Batch batch) {
            tag.putString("kind", "batch");
            tag.putString("recipe", batch.recipe());
            tag.putLong("count", batch.runs());
        } else if (step instanceof PlanStep.Repeat repeat) {
            tag.putString("kind", "repeat");
            tag.putLong("count", repeat.times());
            tag.put("body", step(repeat.body()));
        } else {
            tag.putString("kind", "sequence");
            ListTag children = new ListTag();
            for (PlanStep child : ((PlanStep.Sequence) step).children()) children.add(step(child));
            tag.put("children", children);
        }
        return tag;
    }

    private static PlanStep step(CompoundTag tag, int depth) {
        if (depth > 128) throw new IllegalArgumentException("Graph plan nesting too deep");
        return switch (tag.getString("kind")) {
            case "batch" -> new PlanStep.Batch(tag.getString("recipe"), amount(tag, "count"));
            case "repeat" -> new PlanStep.Repeat(step(tag.getCompound("body"), depth + 1), amount(tag, "count"));
            case "sequence" -> {
                List<PlanStep> children = new ArrayList<>();
                for (Tag entry : list(tag, "children")) children.add(step((CompoundTag) entry, depth + 1));
                yield new PlanStep.Sequence(children);
            }
            default -> throw new IllegalArgumentException("Unknown graph step");
        };
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
