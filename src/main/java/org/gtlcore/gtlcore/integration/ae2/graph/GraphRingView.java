package org.gtlcore.gtlcore.integration.ae2.graph;

import net.minecraft.network.FriendlyByteBuf;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import io.netty.buffer.Unpooled;
import org.cgse.core.*;
import org.cgse.core.PlanRingView;

import java.math.BigInteger;
import java.util.*;

/** Immutable, paged UI data. No providers, world references or mutable task ledger. */
public final class GraphRingView {

    public static final int PAGE_SIZE = 256;
    public static final int MAX_ROWS = PlanRingView.MAX_ROWS;
    public static final int MAX_PAGE_BYTES = 1_048_576;
    private static final int PAGE_BYTES = 65_536;

    public enum Kind {
        RESOURCE,
        RECIPE,
        BATCH,
        SEQUENCE,
        REPEAT,
        REFERENCE
    }

    public record Row(Kind kind, String id, GenericStack icon, BigInteger count, long seed, BigInteger missing,
                      List<GenericStack> inputs, List<GenericStack> outputs, int parent) {

        public Row {
            if (count.signum() < 0 || missing.signum() < 0 || seed < 0) throw new IllegalArgumentException("Negative display amount");
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    public record Page(UUID id, GenericStack target, boolean preserve, int offset, int total, int graphRows, List<Row> rows) {

        public Page {
            if (offset < 0 || total < 0 || total > MAX_ROWS || graphRows < 0 || graphRows > total || offset > total || rows.size() > PAGE_SIZE || rows.size() > total - offset)
                throw new IllegalArgumentException("Invalid graph display page");
            rows = List.copyOf(rows);
        }
    }

    private final UUID id;
    private final GenericStack target;
    private final boolean preserve;
    private final List<Row> rows;
    private final int graphRows;
    private final int[] weights;
    private final int headerWeight;

    public GraphRingView(UUID id, GraphPlan<AEKey> plan, Map<String, Long> selected) {
        this(new Builder(id, plan, selected).finish());
    }

    private GraphRingView(Builder builder) {
        id = builder.id;
        target = new GenericStack(builder.plan.target(), builder.plan.amount());
        preserve = builder.plan.preserveSeeds();
        rows = List.copyOf(builder.all);
        graphRows = builder.cursor.graphRows();
        weights = builder.weights.stream().mapToInt(Integer::intValue).toArray();
        headerWeight = builder.headerWeight;
    }

    /** One bounded row per step; a large view cannot stall the server tick. */
    static final class Builder implements PlanningScheduler.Work<GraphRingView> {

        private final UUID id;
        private final GraphPlan<AEKey> plan;
        private final PlanRingView.Cursor<AEKey> cursor;
        private final List<Row> all = new ArrayList<>();
        private final List<Integer> weights = new ArrayList<>();
        private final Map<AEKey, Integer> keySizes = new HashMap<>();
        private final int headerWeight;

        Builder(UUID id, GraphPlan<AEKey> plan, Map<String, Long> selected) {
            this.id = id;
            this.plan = plan;
            headerWeight = 128 + stackSize(new GenericStack(plan.target(), plan.amount()));
            if (headerWeight > MAX_PAGE_BYTES / 2) throw new IllegalArgumentException("Graph display target exceeds packet limit");
            cursor = new PlanRingView.Cursor<>(plan, selected.keySet());
        }

        private boolean next() {
            if (cursor.advance()) return true;
            var portable = cursor.row();
            if (portable == null) return false;
            var inputs = stacks(portable.inputs());
            var outputs = stacks(portable.outputs());
            GenericStack icon = switch (portable.kind()) {
                case RESOURCE -> new GenericStack(portable.resource(), ExactAmounts.capped(portable.count()));
                case RECIPE -> outputs.get(0);
                default -> new GenericStack(plan.target(), plan.amount());
            };
            var row = new Row(Kind.valueOf(portable.kind().name()), portable.id(), icon, portable.count(),
                    portable.seed().longValueExact(), portable.missing(), inputs, outputs, portable.parent());
            all.add(row);
            long weight = 128L + 3L * row.id().length() + stackSize(row.icon()) + row.count().bitLength() / 8 + row.missing().bitLength() / 8;
            for (var stack : row.inputs()) weight += stackSize(stack);
            for (var stack : row.outputs()) weight += stackSize(stack);
            if (weight > MAX_PAGE_BYTES - headerWeight) throw new IllegalArgumentException("Graph display row exceeds packet limit");
            weights.add((int) weight);
            return false;
        }

        private int stackSize(GenericStack stack) {
            return 16 + keySizes.computeIfAbsent(stack.what(), key -> {
                var buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    GenericStack.writeBuffer(new GenericStack(key, 1), buffer);
                    return buffer.readableBytes();
                } finally {
                    buffer.release();
                }
            });
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
                    slice.budget().reserve(Math.max(weights.get(before),
                            256L + 2L * row.id().length() + 32L * (row.inputs().size() + row.outputs().size())));
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
        int end = offset, bytes = headerWeight;
        while (end < rows.size() && end - offset < PAGE_SIZE) {
            int weight = weights[end];
            if (end > offset && bytes + weight > PAGE_BYTES) break;
            bytes += weight;
            end++;
        }
        return new Page(id, target, preserve, offset, rows.size(), graphRows, rows.subList(offset, end));
    }

    private static List<GenericStack> stacks(List<PlanRingView.Amount<AEKey>> values) {
        return values.stream().map(value -> new GenericStack(value.key(), value.amount().longValueExact())).toList();
    }

    public static void write(Page page, FriendlyByteBuf buffer) {
        int start = buffer.writerIndex();
        buffer.writeUUID(page.id());
        GenericStack.writeBuffer(page.target(), buffer);
        buffer.writeBoolean(page.preserve());
        buffer.writeVarInt(page.offset());
        buffer.writeVarInt(page.total());
        buffer.writeVarInt(page.graphRows());
        // NBT-heavy item keys and repeated program icons are sent once per page.
        Map<AEKey, Integer> keys = new LinkedHashMap<>();
        for (var row : page.rows()) {
            keys.computeIfAbsent(row.icon().what(), ignored -> keys.size());
            for (var stack : row.inputs()) keys.computeIfAbsent(stack.what(), ignored -> keys.size());
            for (var stack : row.outputs()) keys.computeIfAbsent(stack.what(), ignored -> keys.size());
        }
        buffer.writeVarInt(keys.size());
        for (var key : keys.keySet()) GenericStack.writeBuffer(new GenericStack(key, 1), buffer);
        buffer.writeVarInt(page.rows().size());
        for (var row : page.rows()) {
            buffer.writeEnum(row.kind());
            buffer.writeUtf(row.id(), 512);
            writeStack(buffer, row.icon(), keys);
            buffer.writeByteArray(row.count().toByteArray());
            buffer.writeVarLong(row.seed());
            buffer.writeByteArray(row.missing().toByteArray());
            buffer.writeInt(row.parent());
            writeStacks(buffer, row.inputs(), keys);
            writeStacks(buffer, row.outputs(), keys);
        }
        if (buffer.writerIndex() - start > MAX_PAGE_BYTES) throw new IllegalArgumentException("Graph display packet too large");
    }

    public static Page read(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MAX_PAGE_BYTES) throw new IllegalArgumentException("Graph display packet too large");
        UUID id = buffer.readUUID();
        GenericStack target = Objects.requireNonNull(GenericStack.readBuffer(buffer));
        boolean preserve = buffer.readBoolean();
        int offset = buffer.readVarInt(), total = buffer.readVarInt(), graphRows = buffer.readVarInt();
        int keyCount = buffer.readVarInt();
        if (keyCount < 0 || keyCount > PAGE_SIZE * 1025 || keyCount > buffer.readableBytes())
            throw new IllegalArgumentException("Invalid graph key table");
        List<AEKey> keys = new ArrayList<>(keyCount);
        for (int i = 0; i < keyCount; i++) keys.add(Objects.requireNonNull(GenericStack.readBuffer(buffer)).what());
        int count = buffer.readVarInt();
        if (count < 0 || count > PAGE_SIZE) throw new IllegalArgumentException("Invalid graph page size");
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Kind kind = buffer.readEnum(Kind.class);
            String recipe = buffer.readUtf(512);
            GenericStack icon = readStack(buffer, keys);
            BigInteger times = readAmount(buffer);
            long seed = CheckedAmounts.nonNegative(buffer.readVarLong());
            BigInteger missing = readAmount(buffer);
            int parent = buffer.readInt();
            if (parent < -1 || parent >= offset + i) throw new IllegalArgumentException("Invalid graph display parent");
            if (kind == Kind.REFERENCE && (Integer.parseInt(recipe) < graphRows || Integer.parseInt(recipe) >= offset + i))
                throw new IllegalArgumentException("Invalid graph program reference");
            rows.add(new Row(kind, recipe, icon, times, seed, missing, readStacks(buffer, keys), readStacks(buffer, keys), parent));
        }
        return new Page(id, target, preserve, offset, total, graphRows, rows);
    }

    private static void writeStack(FriendlyByteBuf buffer, GenericStack stack, Map<AEKey, Integer> keys) {
        buffer.writeVarInt(keys.get(stack.what()));
        buffer.writeVarLong(stack.amount());
    }

    private static BigInteger readAmount(FriendlyByteBuf buffer) {
        byte[] bytes = buffer.readByteArray(MAX_PAGE_BYTES);
        if (bytes.length == 0) throw new IllegalArgumentException("Empty graph display amount");
        BigInteger value = new BigInteger(bytes);
        if (value.signum() < 0) throw new IllegalArgumentException("Negative graph display amount");
        return value;
    }

    private static GenericStack readStack(FriendlyByteBuf buffer, List<AEKey> keys) {
        int key = buffer.readVarInt();
        if (key < 0 || key >= keys.size()) throw new IllegalArgumentException("Invalid graph key reference");
        return new GenericStack(keys.get(key), CheckedAmounts.nonNegative(buffer.readVarLong()));
    }

    private static void writeStacks(FriendlyByteBuf buffer, List<GenericStack> values, Map<AEKey, Integer> keys) {
        buffer.writeVarInt(values.size());
        for (var stack : values) writeStack(buffer, stack, keys);
    }

    private static List<GenericStack> readStacks(FriendlyByteBuf buffer, List<AEKey> keys) {
        int count = buffer.readVarInt();
        if (count < 0 || count > 512) throw new IllegalArgumentException("Too many graph display slots");
        List<GenericStack> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(readStack(buffer, keys));
        }
        return values;
    }
}
