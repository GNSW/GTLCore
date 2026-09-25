package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.graph.core.*;

import net.minecraft.network.FriendlyByteBuf;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.util.*;

/** Immutable, paged UI data. No providers, world references or mutable task ledger. */
public final class GraphRingView {

    public static final int PAGE_SIZE = 32;
    public static final int MAX_ROWS = 400_000;

    public enum Kind {
        RESOURCE,
        RECIPE,
        BATCH,
        SEQUENCE,
        REPEAT
    }

    public record Row(Kind kind, String id, GenericStack icon, long count, long seed, long missing,
                      List<GenericStack> inputs, List<GenericStack> outputs, int parent) {

        public Row {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    public record Page(UUID id, GenericStack target, boolean preserve, int offset, int total, List<Row> rows) {

        public Page {
            if (offset < 0 || total < 0 || total > MAX_ROWS || offset > total || rows.size() > PAGE_SIZE || rows.size() > total - offset)
                throw new IllegalArgumentException("Invalid graph display page");
            rows = List.copyOf(rows);
        }
    }

    private final UUID id;
    private final GenericStack target;
    private final boolean preserve;
    private final List<Row> rows;

    public GraphRingView(UUID id, GraphPlan<AEKey> plan, Map<String, Long> selected) {
        this(new Builder(id, plan, selected).finish());
    }

    private GraphRingView(Builder builder) {
        id = builder.id;
        target = new GenericStack(builder.plan.target(), builder.plan.amount());
        preserve = builder.plan.preserveSeeds();
        rows = List.copyOf(builder.all);
    }

    /** One bounded row per step; a large view cannot stall the server tick. */
    static final class Builder implements PlanningScheduler.Work<GraphRingView> {

        private final UUID id;
        private final GraphPlan<AEKey> plan;
        private final Deque<Iterator<AEKey>> resources = new ArrayDeque<>();
        private final Set<AEKey> seenResources = new HashSet<>();
        private final Iterator<Map.Entry<String, Long>> selected;
        private final List<Row> all = new ArrayList<>();

        private record Pending(Iterator<PlanStep> children, int parent) {}

        private final Deque<Pending> pending = new ArrayDeque<>();

        Builder(UUID id, GraphPlan<AEKey> plan, Map<String, Long> selected) {
            this.id = id;
            this.plan = plan;
            resources.add(plan.initial().keySet().iterator());
            resources.add(plan.seeds().keySet().iterator());
            resources.add(plan.missing().keySet().iterator());
            this.selected = selected.entrySet().iterator();
            pending.push(new Pending(List.of(plan.steps()).iterator(), -1));
        }

        private boolean next() {
            if (!resources.isEmpty()) {
                if (!resources.peek().hasNext()) {
                    resources.pop();
                    return false;
                }
                var key = resources.peek().next();
                if (!seenResources.add(key)) return false;
                all.add(new Row(Kind.RESOURCE, "", new GenericStack(key, plan.initial().getOrDefault(key, 0L)),
                        0, plan.seeds().getOrDefault(key, 0L), plan.missing().getOrDefault(key, 0L), List.of(), List.of(), -1));
            } else if (selected.hasNext()) {
                var entry = selected.next();
                var recipe = plan.recipes().get(entry.getKey());
                if (recipe.inputs().size() > 512 || recipe.outputs().size() > 512)
                    throw new IllegalArgumentException("Too many graph display slots");
                all.add(new Row(Kind.RECIPE, entry.getKey(), stacks(recipe.executionOutputs()).get(0), entry.getValue(), 0, 0,
                        stacks(recipe.inputs()), stacks(recipe.executionOutputs()), -1));
            } else if (!pending.isEmpty()) {
                var target = new GenericStack(plan.target(), plan.amount());
                var next = pending.peek();
                if (!next.children().hasNext()) {
                    pending.pop();
                    return false;
                }
                var step = next.children().next();
                int index = all.size();
                if (step instanceof PlanStep.Batch batch)
                    all.add(new Row(Kind.BATCH, batch.recipe(), target, batch.runs(), 0, 0, List.of(), List.of(), next.parent()));
                else if (step instanceof PlanStep.Repeat repeat) {
                    all.add(new Row(Kind.REPEAT, "", target, repeat.times(), 0, 0, List.of(), List.of(), next.parent()));
                    pending.push(new Pending(List.of(repeat.body()).iterator(), index));
                } else {
                    all.add(new Row(Kind.SEQUENCE, "", target, 1, 0, 0, List.of(), List.of(), next.parent()));
                    pending.push(new Pending(((PlanStep.Sequence) step).children().iterator(), index));
                }
            } else return true;
            if (all.size() > MAX_ROWS) throw new IllegalArgumentException("Graph display exceeds row limit");
            return false;
        }

        private Builder finish() {
            while (!next()) {}
            return this;
        }

        @Override
        public boolean advance(PlanningScheduler.Slice slice) {
            while (slice.next()) {
                int before = all.size();
                if (next()) return true;
                if (all.size() > before) {
                    var row = all.get(before);
                    slice.budget().reserve(256L + 2L * row.id().length() + 32L * (row.inputs().size() + row.outputs().size()));
                }
            }
            return false;
        }

        @Override
        public GraphRingView result() {
            return new GraphRingView(this);
        }
    }

    public Page page(int offset) {
        if (offset < 0 || offset > rows.size()) throw new IllegalArgumentException("Invalid graph display offset");
        return new Page(id, target, preserve, offset, rows.size(), rows.subList(offset, Math.min(rows.size(), offset + PAGE_SIZE)));
    }

    private static List<GenericStack> stacks(Map<AEKey, Long> values) {
        return values.entrySet().stream().map(entry -> new GenericStack(entry.getKey(), entry.getValue())).toList();
    }

    public static void write(Page page, FriendlyByteBuf buffer) {
        buffer.writeUUID(page.id());
        GenericStack.writeBuffer(page.target(), buffer);
        buffer.writeBoolean(page.preserve());
        buffer.writeVarInt(page.offset());
        buffer.writeVarInt(page.total());
        buffer.writeVarInt(page.rows().size());
        for (var row : page.rows()) {
            buffer.writeEnum(row.kind());
            buffer.writeUtf(row.id(), 512);
            GenericStack.writeBuffer(row.icon(), buffer);
            buffer.writeLong(row.count());
            buffer.writeLong(row.seed());
            buffer.writeLong(row.missing());
            buffer.writeInt(row.parent());
            writeStacks(buffer, row.inputs());
            writeStacks(buffer, row.outputs());
        }
    }

    public static Page read(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        GenericStack target = Objects.requireNonNull(GenericStack.readBuffer(buffer));
        boolean preserve = buffer.readBoolean();
        int offset = buffer.readVarInt(), total = buffer.readVarInt(), count = buffer.readVarInt();
        if (count < 0 || count > PAGE_SIZE) throw new IllegalArgumentException("Invalid graph page size");
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Kind kind = buffer.readEnum(Kind.class);
            String recipe = buffer.readUtf(512);
            GenericStack icon = Objects.requireNonNull(GenericStack.readBuffer(buffer));
            long times = CheckedAmounts.nonNegative(buffer.readLong()), seed = CheckedAmounts.nonNegative(buffer.readLong()),
                    missing = CheckedAmounts.nonNegative(buffer.readLong());
            int parent = buffer.readInt();
            if (parent < -1 || parent >= offset + i) throw new IllegalArgumentException("Invalid graph display parent");
            rows.add(new Row(kind, recipe, icon, times, seed, missing, readStacks(buffer), readStacks(buffer), parent));
        }
        return new Page(id, target, preserve, offset, total, rows);
    }

    private static void writeStacks(FriendlyByteBuf buffer, List<GenericStack> values) {
        buffer.writeVarInt(values.size());
        for (var stack : values) GenericStack.writeBuffer(stack, buffer);
    }

    private static List<GenericStack> readStacks(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > 512) throw new IllegalArgumentException("Too many graph display slots");
        List<GenericStack> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            GenericStack stack = Objects.requireNonNull(GenericStack.readBuffer(buffer));
            CheckedAmounts.nonNegative(stack.amount());
            values.add(stack);
        }
        return values;
    }
}
