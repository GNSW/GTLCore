package org.gtlcore.gtlcore.integration.ae2.patternrelay;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftIOPart;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.crafting.IPatternProviderAutoExpand;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphDispatchContext;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphDispatchPreflight;
import org.gtlcore.gtlcore.utils.NumberUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.parts.PartModels;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.util.AECableType;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.parts.AEBasePart;
import appeng.parts.PartModel;
import appeng.util.Platform;
import appeng.util.SettingsFrom;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PatternRelayPart extends AEBasePart implements ICraftingProvider, IGridTickable, IPatternProviderAutoExpand,
                                    GraphDispatchPreflight {

    private static final String TAG_MODE = "mode";
    private static final String TAG_PENDING_OUTPUTS = "pendingOutputs";
    private static final String TAG_OBSERVED_SUPPLIER_STOCK = "observedSupplierStock";
    private static final int MIN_REFRESH_TICKS = 5;
    private static final int MAX_REFRESH_TICKS = 20;
    private static final float CABLE_CONNECTION_LENGTH = 4.0F;
    private static final double IDLE_POWER_USAGE = 1.0D;
    private static final TagKey<Item> WRENCH_TAG = ItemTags.create(new ResourceLocation("forge", "tools/wrench"));

    private static final ResourceLocation MODEL_SUPPLIER = GTLCore.id("part/me_pattern_relay_supplier");
    private static final ResourceLocation MODEL_ACCESS = GTLCore.id("part/me_pattern_relay_access");
    private static final ResourceLocation MODEL_STATUS_OFF = new ResourceLocation("ae2", "part/interface_off");
    private static final ResourceLocation MODEL_STATUS_ON = new ResourceLocation("ae2", "part/interface_on");
    private static final ResourceLocation MODEL_STATUS_HAS_CHANNEL = new ResourceLocation("ae2", "part/interface_has_channel");

    private static final IPartModel SUPPLIER_OFF = new PartModel(MODEL_SUPPLIER, MODEL_STATUS_OFF);
    private static final IPartModel SUPPLIER_ON = new PartModel(MODEL_SUPPLIER, MODEL_STATUS_ON);
    private static final IPartModel SUPPLIER_HAS_CHANNEL = new PartModel(MODEL_SUPPLIER, MODEL_STATUS_HAS_CHANNEL);
    private static final IPartModel ACCESS_OFF = new PartModel(MODEL_ACCESS, MODEL_STATUS_OFF);
    private static final IPartModel ACCESS_ON = new PartModel(MODEL_ACCESS, MODEL_STATUS_ON);
    private static final IPartModel ACCESS_HAS_CHANNEL = new PartModel(MODEL_ACCESS, MODEL_STATUS_HAS_CHANNEL);

    private final IActionSource actionSource = IActionSource.ofMachine(this);
    private final KeyCounter pendingOutputs = new KeyCounter();
    private final KeyCounter observedSupplierStock = new KeyCounter();
    private volatile RouteSnapshot routeSnapshot = RouteSnapshot.EMPTY;
    private Mode mode = Mode.SUPPLIER;
    private boolean routeRefreshRequested = true;

    public PatternRelayPart(IPartItem<?> partItem) {
        super(partItem);
        getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(IDLE_POWER_USAGE)
                .addService(ICraftingProvider.class, this)
                .addService(IGridTickable.class, this);
    }

    public static void registerModels() {
        PartModels.registerModels(MODEL_SUPPLIER, MODEL_ACCESS);
    }

    @Override
    public void addToWorld() {
        super.addToWorld();
        routeRefreshRequested = true;
    }

    @Override
    public void removeFromWorld() {
        clearRoutes();
        super.removeFromWorld();
    }

    @Override
    protected void onMainNodeStateChanged(IGridNodeListener.State reason) {
        routeRefreshRequested = true;
        if (!isClientSide() && !getMainNode().isActive()) {
            clearRoutes();
        }
        super.onMainNodeStateChanged(reason);
    }

    @Override
    public void readFromNBT(CompoundTag data) {
        super.readFromNBT(data);
        mode = getMode(data);
        readCounter(data, TAG_PENDING_OUTPUTS, pendingOutputs);
        readCounter(data, TAG_OBSERVED_SUPPLIER_STOCK, observedSupplierStock);
    }

    @Override
    public void writeToNBT(CompoundTag data) {
        super.writeToNBT(data);
        writeMode(data, mode);
        data.put(TAG_PENDING_OUTPUTS, writeCounter(pendingOutputs));
        data.put(TAG_OBSERVED_SUPPLIER_STOCK, writeCounter(observedSupplierStock));
    }

    @Override
    public void exportSettings(SettingsFrom mode, CompoundTag output) {
        super.exportSettings(mode, output);
        writeMode(output, this.mode);
    }

    @Override
    public void importSettings(SettingsFrom mode, CompoundTag input, @Nullable Player player) {
        super.importSettings(mode, input, player);
        Mode importedMode = getMode(input);
        if (this.mode != importedMode) {
            this.mode = importedMode;
            routeRefreshRequested = true;
            clearRoutes();
            getHost().markForSave();
            getHost().markForUpdate();
        }
    }

    @Override
    public void writeToStream(FriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeEnum(mode);
    }

    @Override
    public boolean readFromStream(FriendlyByteBuf data) {
        boolean redraw = super.readFromStream(data);
        Mode previousMode = mode;
        mode = data.readEnum(Mode.class);
        return redraw || mode != previousMode;
    }

    @Override
    public void writeVisualStateToNBT(CompoundTag data) {
        super.writeVisualStateToNBT(data);
        data.putInt(TAG_MODE, mode.ordinal());
    }

    @Override
    public void readVisualStateFromNBT(CompoundTag data) {
        super.readVisualStateFromNBT(data);
        mode = Mode.fromOrdinal(data.getInt(TAG_MODE));
    }

    @Override
    public boolean onPartActivate(Player player, InteractionHand hand, Vec3 pos) {
        return useWrench(player, hand);
    }

    @Override
    public boolean onPartShiftActivate(Player player, InteractionHand hand, Vec3 pos) {
        return useWrench(player, hand);
    }

    private boolean useWrench(Player player, InteractionHand hand) {
        ItemStack heldItem = player.getItemInHand(hand);
        if (!heldItem.is(WRENCH_TAG)) {
            return false;
        }
        if (isClientSide()) {
            return true;
        }
        if (!Platform.hasPermissions(getHost().getLocation(), player)) {
            return false;
        }

        mode = mode.next();
        routeRefreshRequested = true;
        clearRoutes();
        ICraftingProvider.requestUpdate(getMainNode());
        getHost().markForSave();
        getHost().markForUpdate();
        player.displayClientMessage(Component.translatable(mode.translationKey), true);
        return true;
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return mode == Mode.ACCESS ? routeSnapshot.patterns() : List.of();
    }

    public String getModeNameTranslationKey() {
        return mode.nameTranslationKey;
    }

    public boolean isAccessMode() {
        return mode == Mode.ACCESS;
    }

    static String getModeNameTranslationKey(ItemStack stack) {
        return getMode(stack.getTag()).nameTranslationKey;
    }

    static boolean isAccessMode(ItemStack stack) {
        return getMode(stack.getTag()) == Mode.ACCESS;
    }

    @Override
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputHolder) {
        if (mode != Mode.ACCESS) {
            return false;
        }
        Set<ICraftingProvider> providers = routeSnapshot.routes().get(pattern);
        if (providers == null) {
            return false;
        }
        Map<AEKey, Long> outputBaselines = captureOutputBaselines(pattern);
        // Prefer providers that can absorb a full (possibly expanded) batch directly:
        // ME buffers first, then auto-expand capable providers, plain ones last.
        List<ICraftingProvider> ordered = new ArrayList<>(providers);
        ordered.sort((a, b) -> Integer.compare(pushPriority(b), pushPriority(a)));
        for (ICraftingProvider provider : ordered) {
            if (!provider.isBusy() && GraphDispatchContext.allowed(provider, pattern) && GraphDispatchContext.capacity(provider, pattern,
                    GraphDispatchContext.operations()) >=
                    GraphDispatchContext.operations() && provider.pushPattern(pattern, inputHolder)) {
                trackOutputs(pattern, outputBaselines, GraphDispatchContext.active() ?
                        GraphDispatchContext.operations() : deriveMultiplier(pattern, inputHolder));
                return true;
            }
        }
        return false;
    }

    /**
     * The relay never evaluates P2P output targets; expansion is negotiated via
     * {@link #gtlcore$getMaxPatternOperations} instead.
     */
    @Override
    public long gtlcore$findMaxOperationsForTarget(PatternProviderTarget target, BlockEntity targetBE, Direction side,
                                                   KeyCounter baseInputs, long requestedOperations) {
        return 1;
    }

    private static int pushPriority(ICraftingProvider provider) {
        if (provider instanceof IMEPatternPartMachine || provider instanceof IMECraftIOPart) {
            return 2;
        }
        return provider instanceof IPatternProviderAutoExpand ? 1 : 0;
    }

    /**
     * Smart-doubling support: the relay reports the best operation count any downstream
     * provider can absorb. ME buffers accept whole batches by design, auto-expand capable
     * providers report their own capacity (respecting their per-provider switch), and plain
     * providers stay at 1x. The push itself is safe for every type: full-capacity providers
     * validate themselves, others fall back to vanilla sendList overflow handling.
     */
    @Override
    public long gtlcore$getMaxPatternOperations(IPatternDetails pattern, long requestedOperations) {
        if (mode != Mode.ACCESS || requestedOperations <= 1 || !pattern.supportsPushInputsToExternalInventory()) {
            return 1;
        }
        Set<ICraftingProvider> providers = routeSnapshot.routes().get(pattern);
        if (providers == null || providers.isEmpty()) {
            return 1;
        }
        long max = 1;
        for (ICraftingProvider provider : providers) {
            if (provider.isBusy() || !GraphDispatchContext.allowed(provider, pattern)) {
                continue;
            }
            if (provider instanceof IMEPatternPartMachine || provider instanceof IMECraftIOPart) {
                long capacity = GraphDispatchContext.capacity(provider, pattern, requestedOperations);
                if (capacity == requestedOperations) return capacity;
                max = Math.max(max, capacity);
                continue;
            }
            if (provider instanceof IPatternProviderAutoExpand expandable) {
                max = Math.max(max, expandable.gtlcore$getMaxPatternOperations(pattern, requestedOperations));
            }
        }
        return Math.max(1, max);
    }

    @Override
    public boolean gtlcore$canProduceGraphPattern(IPatternDetails pattern) {
        Set<ICraftingProvider> providers = routeSnapshot.routes().get(pattern);
        return mode == Mode.ACCESS && getMainNode().isActive() && providers != null && !providers.isEmpty();
    }

    @Override
    public long gtlcore$graphCapacity(IPatternDetails pattern, Map<AEKey, Long> inputPerRun, long requested) {
        if (!gtlcore$canProduceGraphPattern(pattern)) return 0;
        for (GenericStack output : pattern.getOutputs()) {
            long pending = pendingOutputs.get(output.what());
            if (pending < 0 || output.amount() <= 0) return 0;
            requested = Math.min(requested, (Long.MAX_VALUE - pending) / output.amount());
        }
        return requested;
    }

    /**
     * Derives the applied expansion multiplier from the extracted inputs, so output tracking
     * moves back the correct amount. Programmed circuits are always extracted 1x and must be
     * ignored here.
     */
    private static long deriveMultiplier(IPatternDetails pattern, KeyCounter[] inputHolder) {
        var inputs = pattern.getInputs();
        long multiplier = 1;
        for (int i = 0; i < inputs.length && i < inputHolder.length; i++) {
            var possibleInputs = inputs[i].getPossibleInputs();
            if (possibleInputs.length == 0) {
                continue;
            }
            AEKey key = possibleInputs[0].what();
            if (AEUtils.isIntegratedCircuit(key)) {
                continue;
            }
            long base = inputs[i].getMultiplier();
            if (base <= 0) {
                continue;
            }
            for (var entry : inputHolder[i]) {
                if (entry.getKey().equals(key) && entry.getLongValue() > 0) {
                    multiplier = Math.max(multiplier, entry.getLongValue() / base);
                }
            }
        }
        return Math.max(1, multiplier);
    }

    @Override
    public boolean isBusy() {
        if (mode != Mode.ACCESS) {
            return true;
        }
        for (Set<ICraftingProvider> providers : routeSnapshot.routes().values()) {
            for (ICraftingProvider provider : providers) {
                if (!provider.isBusy()) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(MIN_REFRESH_TICKS, MAX_REFRESH_TICKS, false, false);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        boolean routesChanged = refreshRoutes();
        boolean outputsMoved = movePendingOutputs();
        if (routeRefreshRequested || routesChanged || outputsMoved) {
            routeRefreshRequested = false;
            return TickRateModulation.URGENT;
        }
        return TickRateModulation.SLOWER;
    }

    @Override
    public IPartModel getStaticModels() {
        if (mode == Mode.SUPPLIER) {
            return selectModel(SUPPLIER_OFF, SUPPLIER_ON, SUPPLIER_HAS_CHANNEL);
        }
        return selectModel(ACCESS_OFF, ACCESS_ON, ACCESS_HAS_CHANNEL);
    }

    private IPartModel selectModel(IPartModel off, IPartModel on, IPartModel hasChannel) {
        if (isActive() && isPowered()) {
            return hasChannel;
        }
        return isPowered() ? on : off;
    }

    @Override
    public void getBoxes(IPartCollisionHelper helper) {
        helper.addBox(2, 2, 14, 14, 14, 16);
        helper.addBox(5, 5, 12, 11, 11, 14);
    }

    @Override
    public float getCableConnectionLength(AECableType cable) {
        return CABLE_CONNECTION_LENGTH;
    }

    private boolean refreshRoutes() {
        if (mode != Mode.ACCESS || !getMainNode().isActive()) {
            return updateSnapshot(RouteSnapshot.EMPTY);
        }
        PatternRelayPart supplierPart = findPeer(Mode.SUPPLIER);
        IGrid accessGrid = getMainNode().getGrid();
        IGrid supplierGrid = supplierPart == null ? null : supplierPart.getMainNode().getGrid();
        if (supplierGrid == null || supplierGrid == accessGrid || !supplierPart.getMainNode().isActive()) {
            return updateSnapshot(RouteSnapshot.EMPTY);
        }

        Map<IPatternDetails, Set<ICraftingProvider>> routes = new HashMap<>();
        for (IGridNode sourceNode : supplierGrid.getNodes()) {
            if (!sourceNode.isActive()) {
                continue;
            }
            ICraftingProvider provider = sourceNode.getService(ICraftingProvider.class);
            if (provider == null || provider instanceof PatternRelayPart) {
                continue;
            }
            for (IPatternDetails pattern : provider.getAvailablePatterns()) {
                routes.computeIfAbsent(pattern, ignored -> new HashSet<>()).add(provider);
            }
        }
        return updateSnapshot(RouteSnapshot.copyOf(routes));
    }

    private boolean movePendingOutputs() {
        if (mode != Mode.ACCESS || pendingOutputs.isEmpty() || !getMainNode().isActive()) {
            return false;
        }
        PatternRelayPart supplierPart = findPeer(Mode.SUPPLIER);
        IGrid accessGrid = getMainNode().getGrid();
        IGrid supplierGrid = supplierPart == null ? null : supplierPart.getMainNode().getGrid();
        if (supplierGrid == null || supplierGrid == accessGrid || !supplierPart.getMainNode().isActive()) {
            return false;
        }

        MEStorage source = supplierGrid.getStorageService().getInventory();
        MEStorage destination = accessGrid.getStorageService().getInventory();
        IActionSource supplierActionSource = IActionSource.ofMachine(supplierPart);
        List<GenericStack> pending = new ArrayList<>();
        for (var entry : pendingOutputs) {
            if (entry.getLongValue() > 0) {
                pending.add(new GenericStack(entry.getKey(), entry.getLongValue()));
            }
        }

        boolean stateChanged = false;
        for (GenericStack stack : pending) {
            long available = source.extract(stack.what(), Long.MAX_VALUE, Actionable.SIMULATE, supplierActionSource);
            long observed = observedSupplierStock.get(stack.what());
            if (available <= observed) {
                stateChanged |= available < observed;
                observedSupplierStock.set(stack.what(), available);
                continue;
            }

            long produced = available - observed;
            long accepted = destination.insert(stack.what(), produced, Actionable.SIMULATE, actionSource);
            long toMove = Math.min(stack.amount(), accepted);
            if (toMove <= 0) {
                continue;
            }

            long extracted = source.extract(stack.what(), toMove, Actionable.MODULATE, supplierActionSource);
            long inserted = destination.insert(stack.what(), extracted, Actionable.MODULATE, actionSource);
            if (inserted < extracted) {
                source.insert(stack.what(), extracted - inserted, Actionable.MODULATE, supplierActionSource);
            }
            if (inserted > 0) {
                pendingOutputs.remove(stack.what(), inserted);
                observedSupplierStock.set(stack.what(), available - inserted);
                stateChanged = true;
            }
        }
        if (stateChanged) {
            pendingOutputs.removeZeros();
            pendingOutputs.removeEmptySubmaps();
            removeCompletedObservations();
            getHost().markForSave();
        }
        return stateChanged;
    }

    private Map<AEKey, Long> captureOutputBaselines(IPatternDetails pattern) {
        PatternRelayPart supplierPart = findPeer(Mode.SUPPLIER);
        IGrid supplierGrid = supplierPart == null ? null : supplierPart.getMainNode().getGrid();
        if (supplierGrid == null) {
            return Map.of();
        }

        MEStorage source = supplierGrid.getStorageService().getInventory();
        IActionSource supplierActionSource = IActionSource.ofMachine(supplierPart);
        Map<AEKey, Long> baselines = new HashMap<>();
        for (GenericStack output : pattern.getOutputs()) {
            if (output != null && output.amount() > 0 && pendingOutputs.get(output.what()) <= 0) {
                long available = source.extract(output.what(), Long.MAX_VALUE, Actionable.SIMULATE, supplierActionSource);
                baselines.put(output.what(), available);
            }
        }
        return baselines;
    }

    private void trackOutputs(IPatternDetails pattern, Map<AEKey, Long> outputBaselines, long multiplier) {
        for (GenericStack output : pattern.getOutputs()) {
            if (output != null && output.amount() > 0) {
                Long baseline = outputBaselines.get(output.what());
                if (baseline != null && pendingOutputs.get(output.what()) <= 0) {
                    observedSupplierStock.set(output.what(), baseline);
                }
                if (GraphDispatchContext.active()) {
                    long amount = Math.multiplyExact(output.amount(), multiplier);
                    pendingOutputs.set(output.what(), Math.addExact(pendingOutputs.get(output.what()), amount));
                } else pendingOutputs.add(output.what(), NumberUtils.saturatedMultiply(output.amount(), multiplier));
            }
        }
        getHost().markForSave();
    }

    private void removeCompletedObservations() {
        List<AEKey> completed = new ArrayList<>();
        for (var entry : observedSupplierStock) {
            if (pendingOutputs.get(entry.getKey()) <= 0) {
                completed.add(entry.getKey());
            }
        }
        for (AEKey key : completed) {
            observedSupplierStock.set(key, 0);
        }
        observedSupplierStock.removeZeros();
        observedSupplierStock.removeEmptySubmaps();
    }

    private static void readCounter(CompoundTag data, String key, KeyCounter counter) {
        counter.clear();
        ListTag entries = data.getList(key, Tag.TAG_COMPOUND);
        for (Tag entry : entries) {
            GenericStack stack = GenericStack.readTag((CompoundTag) entry);
            if (stack != null && stack.amount() > 0) {
                counter.add(stack.what(), stack.amount());
            }
        }
    }

    private static ListTag writeCounter(KeyCounter counter) {
        ListTag entries = new ListTag();
        for (var entry : counter) {
            if (entry.getLongValue() > 0) {
                entries.add(GenericStack.writeTag(new GenericStack(entry.getKey(), entry.getLongValue())));
            }
        }
        return entries;
    }

    private static Mode getMode(@Nullable CompoundTag data) {
        return data != null && data.contains(TAG_MODE, Tag.TAG_INT) ?
                Mode.fromOrdinal(data.getInt(TAG_MODE)) : Mode.SUPPLIER;
    }

    private static void writeMode(CompoundTag data, Mode mode) {
        data.putInt(TAG_MODE, mode.ordinal());
    }

    private @Nullable PatternRelayPart findPeer(Mode expectedMode) {
        BlockEntity blockEntity = getBlockEntity();
        Direction side = getSide();
        if (blockEntity == null || side == null || blockEntity.getLevel() == null) {
            return null;
        }
        BlockPos peerPos = blockEntity.getBlockPos().relative(side);
        BlockEntity peerBlockEntity = blockEntity.getLevel().getBlockEntity(peerPos);
        if (!(peerBlockEntity instanceof IPartHost peerHost)) {
            return null;
        }
        if (peerHost.getPart(side.getOpposite()) instanceof PatternRelayPart peer && peer.mode == expectedMode) {
            return peer;
        }
        return null;
    }

    private boolean updateSnapshot(RouteSnapshot newSnapshot) {
        if (routeSnapshot.equals(newSnapshot)) {
            return false;
        }
        routeSnapshot = newSnapshot;
        if (getMainNode().isReady()) {
            ICraftingProvider.requestUpdate(getMainNode());
        }
        return true;
    }

    private void clearRoutes() {
        updateSnapshot(RouteSnapshot.EMPTY);
    }

    private enum Mode {

        SUPPLIER(
                "message.gtlcore.me_pattern_relay.mode_supplier",
                "tooltip.gtlcore.me_pattern_relay.mode_supplier"),
        ACCESS(
                "message.gtlcore.me_pattern_relay.mode_access",
                "tooltip.gtlcore.me_pattern_relay.mode_access");

        private static final Mode[] VALUES = values();
        private final String translationKey;
        private final String nameTranslationKey;

        Mode(String translationKey, String nameTranslationKey) {
            this.translationKey = translationKey;
            this.nameTranslationKey = nameTranslationKey;
        }

        private Mode next() {
            return VALUES[(ordinal() + 1) % VALUES.length];
        }

        private static Mode fromOrdinal(int ordinal) {
            return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : SUPPLIER;
        }
    }

    private record RouteSnapshot(
                                 List<IPatternDetails> patterns,
                                 Map<IPatternDetails, Set<ICraftingProvider>> routes) {

        private static final RouteSnapshot EMPTY = new RouteSnapshot(List.of(), Map.of());

        private static RouteSnapshot copyOf(Map<IPatternDetails, Set<ICraftingProvider>> routes) {
            if (routes.isEmpty()) {
                return EMPTY;
            }
            Map<IPatternDetails, Set<ICraftingProvider>> immutableRoutes = new HashMap<>();
            routes.forEach((pattern, providers) -> immutableRoutes.put(pattern, Set.copyOf(providers)));
            return new RouteSnapshot(List.copyOf(immutableRoutes.keySet()), Map.copyOf(immutableRoutes));
        }
    }
}
