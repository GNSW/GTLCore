package org.gtlcore.gtlcore.common.machine.multiblock.part.ae;

import org.gtlcore.gtlcore.api.gui.RecipePatternMachineUIWidget;
import org.gtlcore.gtlcore.api.gui.RecipePatternManagementPage;
import org.gtlcore.gtlcore.api.machine.trait.IMERecipeHandlerTrait;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternTrait;
import org.gtlcore.gtlcore.client.gui.widget.RecipePatternBrowserWidget;
import org.gtlcore.gtlcore.client.gui.widget.RecipePatternBrowserWidget.Page;
import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.handler.MERecipePatternFilter;
import org.gtlcore.gtlcore.integration.ae2.handler.MERecipePatternFilter.Descriptor;
import org.gtlcore.gtlcore.integration.ae2.handler.MERecipePatternFilter.Rule;
import org.gtlcore.gtlcore.integration.ae2.handler.MERecipePatternFilter.Target;
import org.gtlcore.gtlcore.integration.ae2.handler.MERecipePatternHelper;
import org.gtlcore.gtlcore.integration.ae2.handler.MERecipePatternHelper.RecipePattern;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluid;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.*;
import appeng.api.stacks.*;
import appeng.crafting.pattern.AEProcessingPattern;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/** Patternless hatch: the formed controllers' current recipe maps supply its processing patterns. */
public class MERecipePatternBufferPartMachine extends MEPatternBufferPartMachineBase {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            MERecipePatternBufferPartMachine.class, MEPatternBufferPartMachineBase.MANAGED_FIELD_HOLDER);
    private static final String SLOT_DATA_KEY = "recipePatternSlots";

    private final MERecipePatternBufferRecipeHandlerTrait recipeHandler;
    private final List<RecipePattern> recipePatterns = new ObjectArrayList<>();
    private final List<IPatternDetails> availablePatterns = new ObjectArrayList<>();
    private final Map<String, RecipePattern> patternById = new LinkedHashMap<>();
    private final Map<String, IPatternDetails> previewPatterns = new HashMap<>();
    private final Set<String> invalidPatterns = new HashSet<>();
    private Map<AEKey, AEItemKey> virtualSupplies = Map.of();
    private Map<String, List<AEItemKey>> virtualInputKeys = Map.of();
    private final Map<String, Descriptor> searchMetadata = new HashMap<>();
    private final Set<String> blacklist = new TreeSet<>();
    private final List<Rule> filterRules = new ArrayList<>();
    private final Set<String> ruleExcluded = new HashSet<>();
    private final Set<String> whitelist = new TreeSet<>();
    private final List<Rule> whitelistRules = new ArrayList<>();
    private final Set<String> ruleAllowed = new HashSet<>();
    private final Map<String, AEKey> primaryOverrides = new HashMap<>();
    @Getter
    private long configurationRevision;
    @Persisted
    private boolean keepByProduct = false;
    private final Object2IntMap<IPatternDetails> patternToSlot = new Object2IntOpenHashMap<>();
    // Allocate material buffers only for patterns that have actually received a job.
    private final Int2ReferenceMap<InternalSlot> internalSlots = new Int2ReferenceOpenHashMap<>();
    private final Int2ReferenceMap<GTRecipe> recipeCache = new Int2ReferenceOpenHashMap<>();
    private final IntSet activeSlots = new IntOpenHashSet();
    private IntConsumer removeSlotFromMap = slot -> {};
    private ListTag pendingSlotData = new ListTag();
    private Set<GTRecipeType> activeRecipeTypes = Set.of();
    private RecipeManager recipeManager;
    private long reloadGeneration = -1;
    private TickableSubscription modeSubscription;
    private int skippedRecipes;
    private int unrepresentableChancedPrimaryRecipes;

    public record RecipeStatistics(int published, int blacklisted, int ruleFiltered, int whitelistFiltered, int chancedPrimary, int skipped) {

        public int blocked() {
            return blacklisted + ruleFiltered + whitelistFiltered + chancedPrimary;
        }
    }

    public MERecipePatternBufferPartMachine(IMachineBlockEntity holder, IO io) {
        super(holder, io);
        patternToSlot.defaultReturnValue(-1);
        recipeHandler = new MERecipePatternBufferRecipeHandlerTrait(this, io);
        getMainNode().addService(IGridTickable.class, new Ticker());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!isRemote()) {
            modeSubscription = subscribeServerTick(modeSubscription, () -> {
                if (getOffsetTimer() % 20 == 0) {
                    refreshPatterns();
                    refreshVirtualInputs(true);
                }
            });
        }
    }

    @Override
    public void onUnload() {
        if (modeSubscription != null) {
            modeSubscription.unsubscribe();
            modeSubscription = null;
        }
        super.onUnload();
    }

    @Override
    public void addedToController(@NotNull IMultiController controller) {
        super.addedToController(controller);
        // GT finishes forming the controller after attaching its parts; inspect it on the next server tick.
        reloadGeneration = -1;
    }

    @Override
    public void removedFromController(@NotNull IMultiController controller) {
        super.removedFromController(controller);
        if (!isRemote()) refreshPatterns();
    }

    private void refreshPatterns() {
        if (getLevel() == null || isRemote()) return;
        Set<GTRecipeType> types = MERecipePatternHelper.getActiveRecipeTypes(getControllers());
        RecipeManager manager = getLevel().getRecipeManager();
        long generation = MERecipePatternHelper.getReloadGeneration();
        if (types.equals(activeRecipeTypes) && manager == recipeManager && generation == reloadGeneration) return;

        List<RecipePattern> expanded = new ObjectArrayList<>();
        int skipped = 0;
        int chancedPrimary = 0;
        for (GTRecipe recipe : MERecipePatternHelper.getRecipes(manager, types)) {
            RecipePattern pattern = MERecipePatternHelper.createPattern(recipe, getLevel());
            if (pattern != null) expanded.add(pattern);
            else if (MERecipePatternHelper.hasChancedPrimaryOutput(recipe)) chancedPrimary++;
            else skipped++;
        }

        ListTag saved = collectSlotData();
        for (int index : recipeCache.keySet()) {
            removeSlotFromMap.accept(index);
            notifyProxySlotRemoved(index);
        }
        internalSlots.clear();
        activeSlots.clear();
        recipeCache.clear();
        recipePatterns.clear();
        patternById.clear();
        searchMetadata.clear();
        recipePatterns.addAll(expanded);
        for (RecipePattern recipePattern : recipePatterns) {
            patternById.put(recipePattern.id(), recipePattern);
            searchMetadata.put(recipePattern.id(), MERecipePatternFilter.describe(recipePattern.recipe()));
        }
        rebuildRuleMatches();
        refreshVirtualInputs(false);
        refreshCraftingPatterns();
        configurationRevision++;

        activeRecipeTypes = types;
        recipeManager = manager;
        reloadGeneration = generation;
        skippedRecipes = skipped;
        unrepresentableChancedPrimaryRecipes = chancedPrimary;
        // A freshly loaded part may not have rejoined its controller yet. Keep saved jobs until its mode is known.
        if (types.isEmpty() && availablePatterns.isEmpty() && !pendingSlotData.isEmpty() && saved.isEmpty()) {
            needPatternSync = true;
            return;
        }
        saved.addAll(pendingSlotData);
        pendingSlotData = new ListTag();
        restoreSlotData(saved);
        notifyInputsChanged();
        needPatternSync = true;
    }

    private void refreshCraftingPatterns() {
        if (isRemote() || getLevel() == null) return;
        availablePatterns.clear();
        patternToSlot.clear();
        previewPatterns.clear();
        invalidPatterns.clear();
        for (int index = 0; index < recipePatterns.size(); index++) {
            RecipePattern recipePattern = recipePatterns.get(index);
            IPatternDetails preview = buildPreviewPattern(recipePattern);
            if (preview == null) {
                invalidPatterns.add(recipePattern.id());
                continue;
            }
            previewPatterns.put(recipePattern.id(), preview);
            if (getRecipeStatus(recipePattern.id()) != 0) continue;
            ItemStack complete = preview.getDefinition().toStack();
            IPatternDetails processed = realPatternHelper.processPatternWithCircuit(complete, circuit -> {}, getLevel(), keepByProduct);
            if (processed == null) continue;
            ItemStack encoded = processed.getDefinition().toStack();
            // MEBufferPatternHelper rebuilds the ordinary AE2 IO; retain the generated recipe's unique binding.
            encoded.getOrCreateTag().putString("gtlcoreRecipe", complete.getOrCreateTag().getString("gtlcoreRecipe"));
            encoded.getOrCreateTag().putInt("gtlcoreRecipeCircuit", recipePattern.circuit());
            IPatternDetails pattern = PatternDetailsHelper.decodePattern(encoded, getLevel());
            if (pattern != null) {
                availablePatterns.add(pattern);
                patternToSlot.put(pattern, index);
            }
        }
        needPatternSync = true;
        markDirty();
    }

    private void refreshVirtualInputs(boolean republish) {
        if (isRemote() || getLevel() == null) return;
        Map<AEKey, AEItemKey> supplies = new HashMap<>();
        var grid = getMainNode().getGrid();
        if (grid != null && !recipePatterns.isEmpty()) {
            var storage = grid.getStorageService();
            // AE2's fuzzy index visits only virtual wrappers, regardless of the number of ordinary network items.
            var wrappers = storage.getCachedInventory().findFuzzy(Objects.requireNonNull(AEItemKey.of(GTLItems.VIRTUAL_INGREDIENT.asStack())), FuzzyMode.IGNORE_ALL);
            for (var entry : wrappers) {
                if (entry.getLongValue() <= 0 || !(entry.getKey() instanceof AEItemKey wrapper)) continue;
                ItemStack canonical = VirtualIngredientBehavior.canonicalStack(wrapper.getReadOnlyStack());
                if (canonical.isEmpty() || storage.getInventory().extract(wrapper, 1, Actionable.SIMULATE, actionSource) <= 0) continue;
                AEKey payload = VirtualIngredientBehavior.payloadItemKey(canonical);
                if (payload == null) payload = VirtualIngredientBehavior.payloadFluidKey(canonical);
                if (payload == null) continue;
                AEItemKey canonicalKey = AEItemKey.of(canonical);
                AEItemKey current = supplies.get(payload);
                // Prefer the provider's canonical token; otherwise retain an available previously selected token.
                if (current == null || wrapper.equals(canonicalKey) ||
                        (wrapper.equals(virtualSupplies.get(payload)) && !current.equals(canonicalKey)))
                    supplies.put(payload, wrapper);
            }
        }
        virtualSupplies = supplies;
        Map<String, List<AEItemKey>> inputs = new HashMap<>();
        for (RecipePattern recipePattern : recipePatterns) {
            Set<AEItemKey> tokens = new LinkedHashSet<>();
            for (List<AEKey> alternatives : recipePattern.nonConsumedInputs()) {
                for (AEKey material : alternatives) {
                    AEItemKey token = supplies.get(material);
                    if (token != null) {
                        tokens.add(token);
                        break;
                    }
                }
            }
            if (!tokens.isEmpty()) inputs.put(recipePattern.id(), List.copyOf(tokens));
        }
        if (!inputs.equals(virtualInputKeys)) {
            virtualInputKeys = inputs;
            if (republish) configurationChanged();
        }
    }

    private void rebuildRuleMatches() {
        ruleExcluded.clear();
        ruleAllowed.clear();
        searchMetadata.forEach((id, metadata) -> {
            if (filterRules.stream().anyMatch(rule -> rule.matches(metadata))) ruleExcluded.add(id);
            if (whitelistRules.stream().anyMatch(rule -> rule.matches(metadata))) ruleAllowed.add(id);
        });
    }

    public RecipeStatistics getRecipeStatistics() {
        int blacklisted = 0;
        int ruleFiltered = 0;
        int whitelistFiltered = 0;
        int chancedPrimary = 0;
        for (RecipePattern pattern : recipePatterns) {
            switch (getRecipeStatus(pattern.id())) {
                case 1 -> blacklisted++;
                case 2 -> ruleFiltered++;
                case 3 -> chancedPrimary++;
                case 5 -> whitelistFiltered++;
            }
        }
        int skipped = skippedRecipes + recipePatterns.size() - availablePatterns.size() - blacklisted - ruleFiltered - whitelistFiltered - chancedPrimary;
        return new RecipeStatistics(availablePatterns.size(), blacklisted, ruleFiltered, whitelistFiltered,
                chancedPrimary + unrepresentableChancedPrimaryRecipes, skipped);
    }

    public List<RecipePattern> searchRecipes(String query, Set<String> localizedMaterials) {
        return recipePatterns.stream().filter(pattern -> searchMetadata.get(pattern.id()).matchesSearch(query, localizedMaterials)).toList();
    }

    public @Nullable RecipePattern getRecipePattern(String id) {
        return patternById.get(id);
    }

    public List<String> getBlacklistedRecipes() {
        return List.copyOf(blacklist);
    }

    public List<Rule> getFilterRules() {
        return List.copyOf(filterRules);
    }

    public List<String> getWhitelistedRecipes() {
        return List.copyOf(whitelist);
    }

    public boolean isRecipeWhitelisted(String id) {
        return whitelist.contains(id);
    }

    public List<Rule> getWhitelistRules() {
        return List.copyOf(whitelistRules);
    }

    public boolean isWhitelistActive() {
        return !whitelist.isEmpty() || !whitelistRules.isEmpty();
    }

    /**
     * 0 = published, 1 = blacklisted, 2 = blacklist rule, 3 = chanced primary, 4 = AE2 limits, 5 = outside whitelist.
     */
    public int getRecipeStatus(String id) {
        if (blacklist.contains(id)) return 1;
        if (ruleExcluded.contains(id)) return 2;
        // Blacklist entries and rules always win, even when the same recipe matches the whitelist.
        if (isWhitelistActive() && !whitelist.contains(id) && !ruleAllowed.contains(id)) return 5;
        RecipePattern pattern = patternById.get(id);
        if (pattern == null || getPrimaryOutput(pattern) == null) return 3;
        return invalidPatterns.contains(id) ? 4 : 0;
    }

    public @Nullable AEKey getPrimaryOutput(RecipePattern pattern) {
        AEKey override = primaryOverrides.get(pattern.id());
        if (override != null && Arrays.stream(pattern.pattern().getOutputs()).anyMatch(stack -> stack.what().equals(override))) return override;
        return pattern.primaryOutput();
    }

    public IPatternDetails getPreviewPattern(RecipePattern recipePattern) {
        return previewPatterns.getOrDefault(recipePattern.id(), recipePattern.pattern());
    }

    private @Nullable IPatternDetails buildPreviewPattern(RecipePattern recipePattern) {
        IPatternDetails original = recipePattern.pattern();
        AEKey primary = getPrimaryOutput(recipePattern);
        List<AEItemKey> virtualInputs = virtualInputKeys.getOrDefault(recipePattern.id(), List.of());
        if (virtualInputs.isEmpty() && (primary == null || original.getPrimaryOutput().what().equals(primary))) return original;
        if (!(original instanceof AEProcessingPattern processing)) return original;
        List<GenericStack> outputs = new ArrayList<>(Arrays.stream(processing.getSparseOutputs()).filter(Objects::nonNull).toList());
        if (primary != null) outputs.sort(Comparator.comparing(stack -> !stack.what().equals(primary)));
        List<GenericStack> inputs = new ArrayList<>();
        // One token grants unlimited matching supply for its non-consumed payload, independently of recipe quantity.
        for (AEItemKey token : virtualInputs) inputs.add(new GenericStack(token, 1));
        inputs.addAll(Arrays.stream(processing.getSparseInputs()).filter(Objects::nonNull).toList());
        try {
            ItemStack encoded = PatternDetailsHelper.encodeProcessingPattern(inputs.toArray(GenericStack[]::new), outputs.toArray(GenericStack[]::new));
            encoded.getOrCreateTag().putString("gtlcoreRecipe", recipePattern.id());
            encoded.getOrCreateTag().putInt("gtlcoreRecipeCircuit", recipePattern.circuit());
            return PatternDetailsHelper.decodePattern(encoded, getLevel());
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            return null;
        }
    }

    public void setRecipeBlacklisted(String id, boolean excluded) {
        if (isRemote() || (excluded && !patternById.containsKey(id))) return;
        boolean changed = excluded ? blacklist.add(id) : blacklist.remove(id);
        if (changed) configurationChanged();
    }

    public void setRecipeWhitelisted(String id, boolean allowed) {
        if (isRemote() || (allowed && !patternById.containsKey(id))) return;
        boolean changed = allowed ? whitelist.add(id) : whitelist.remove(id);
        if (changed) configurationChanged();
    }

    public void setPrimaryOutput(String id, @Nullable AEKey primary) {
        RecipePattern pattern = patternById.get(id);
        if (isRemote() || pattern == null) return;
        if (primary == null) {
            if (primaryOverrides.remove(id) != null) configurationChanged();
        } else if (Arrays.stream(pattern.pattern().getOutputs()).anyMatch(stack -> stack.what().equals(primary))) {
            if (!primary.equals(primaryOverrides.put(id, primary))) configurationChanged();
        }
    }

    public void addFilterRule(Target target, String expression) {
        addRule(filterRules, target, expression);
    }

    public void addWhitelistRule(Target target, String expression) {
        addRule(whitelistRules, target, expression);
    }

    private void addRule(List<Rule> rules, Target target, String expression) {
        if (isRemote() || expression.isBlank() || expression.length() > MERecipePatternFilter.MAX_EXPRESSION_LENGTH ||
                rules.size() >= MERecipePatternFilter.MAX_RULES)
            return;
        Rule rule = new Rule(target, expression);
        if (!rules.contains(rule)) {
            rules.add(rule);
            rebuildRuleMatches();
            configurationChanged();
        }
    }

    public void removeFilterRule(Rule rule) {
        removeRule(filterRules, rule);
    }

    public void removeWhitelistRule(Rule rule) {
        removeRule(whitelistRules, rule);
    }

    private void removeRule(List<Rule> rules, Rule rule) {
        if (!isRemote() && rules.remove(rule)) {
            rebuildRuleMatches();
            configurationChanged();
        }
    }

    private void configurationChanged() {
        configurationRevision++;
        // Keep already accepted jobs in their original recipe slots; new jobs use the updated published patterns.
        refreshCraftingPatterns();
        markDirty();
    }

    private ListTag collectSlotData() {
        ListTag saved = new ListTag();
        for (var entry : Int2ReferenceMaps.fastIterable(internalSlots)) {
            CompoundTag inventory = entry.getValue().serializeNBT();
            if (inventory.isEmpty()) continue;
            CompoundTag data = new CompoundTag();
            data.put("pattern", recipePatterns.get(entry.getIntKey()).pattern().getDefinition().toTag());
            data.put("slot", inventory);
            saved.add(data);
        }
        return saved;
    }

    private void restoreSlotData(ListTag saved) {
        Map<AEItemKey, Integer> definitions = new HashMap<>();
        for (int index = 0; index < recipePatterns.size(); index++) {
            definitions.put(recipePatterns.get(index).pattern().getDefinition(), index);
        }
        for (int index = 0; index < saved.size(); index++) {
            CompoundTag data = saved.getCompound(index);
            Integer slot = definitions.get(AEItemKey.fromTag(data.getCompound("pattern")));
            if (slot != null && !internalSlots.containsKey(slot)) {
                InternalSlot inventory = getInternalSlot(slot);
                inventory.deserializeNBT(data.getCompound("slot"));
                restoreCircuit(inventory, recipePatterns.get(slot).circuit());
                if (inventory.isActive()) activeSlots.add(slot);
            } else {
                refundPersistedSlot(data.getCompound("slot"));
            }
        }
        AEUtils.reFunds(buffer, getMainNode().getGrid(), actionSource);
    }

    private void refundPersistedSlot(CompoundTag data) {
        Object2LongOpenHashMap<AEItemKey> items = new Object2LongOpenHashMap<>();
        Object2LongOpenHashMap<AEFluidKey> fluids = new Object2LongOpenHashMap<>();
        AEUtils.appendPersistedInventory(data.getList("inventory", Tag.TAG_COMPOUND), AEItemKey::fromTag, items);
        AEUtils.appendPersistedInventory(data.getList("fluidInventory", Tag.TAG_COMPOUND), AEFluidKey::fromTag, fluids);
        refundSlot(items, fluids);
    }

    private void notifyInputsChanged() {
        markDirty();
        recipeHandler.getMeItemHandler().notifyListeners();
        recipeHandler.getMeFluidHandler().notifyListeners();
    }

    @Override
    protected void update() {
        super.update();
        if (!buffer.isEmpty() && AEUtils.reFunds(buffer, getMainNode().getGrid(), actionSource)) markDirty();
    }

    @Override
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputHolder) {
        refreshPatterns();
        return super.pushPattern(pattern, inputHolder);
    }

    @Override
    public boolean gtlcore$canProduceGraphPattern(IPatternDetails pattern) {
        refreshPatterns();
        return super.gtlcore$canProduceGraphPattern(pattern);
    }

    @Override
    protected @Nullable Integer getSlotIndexForPattern(IPatternDetails pattern) {
        int slot = patternToSlot.getInt(pattern);
        return slot < 0 ? null : slot;
    }

    @Override
    protected int getInternalSlotCount() {
        return recipePatterns.size();
    }

    @Override
    protected boolean hasPatternInSlot(int slot) {
        return slot >= 0 && slot < recipePatterns.size();
    }

    @Override
    protected InternalSlot getInternalSlot(int index) {
        return internalSlots.computeIfAbsent(index, slotIndex -> {
            InternalSlot slot = new InternalSlot(slotIndex);
            restoreCircuit(slot, recipePatterns.get(slotIndex).circuit());
            recipeCache.put(slotIndex, recipePatterns.get(slotIndex).recipe());
            slot.setOnContentsChanged(() -> {
                if (slot.isActive()) activeSlots.add(slotIndex);
                else activeSlots.remove(slotIndex);
                notifyInputsChanged();
            });
            return slot;
        });
    }

    private static void restoreCircuit(InternalSlot slot, int circuit) {
        if (circuit >= 0) slot.getCacheManager().setCircuitCache(circuit);
        else slot.getCacheManager().clearCircuitCache();
    }

    public boolean matchesRecipe(int slot, GTRecipe recipe) {
        if (!hasPatternInSlot(slot)) return false;
        GTRecipe expected = recipePatterns.get(slot).recipe();
        return expected.recipeType == recipe.recipeType && Objects.equals(expected.id, recipe.id);
    }

    @Override
    protected @Nullable InternalSlot getInternalSlotOrNull(int index) {
        return internalSlots.get(index);
    }

    @Override
    protected int[] getActiveAndUnCachedSlots() {
        // Generated slots already know their recipe and do not need ingredient-tree discovery.
        return new int[0];
    }

    @Override
    public Pair<Object2LongOpenHashMap<Item>, Object2LongOpenHashMap<Fluid>> getMergedInternalSlot() {
        Object2LongOpenHashMap<Item> items = new Object2LongOpenHashMap<>();
        Object2LongOpenHashMap<Fluid> fluids = new Object2LongOpenHashMap<>();
        for (InternalSlot slot : internalSlots.values()) {
            MEPatternBufferJadeMerger.mergeItemKeys(items, slot.getItemInventory());
            MEPatternBufferJadeMerger.mergeFluidKeys(fluids, slot.getFluidInventory());
        }
        MEPatternBufferJadeMerger.mergeItemStacks(items, sharedCatalystInventory.getContents());
        MEPatternBufferJadeMerger.mergeFluidStacks(fluids, sharedCatalystTank.getContents());
        return Pair.of(items, fluids);
    }

    @Override
    public long gtlcore$graphCapacity(IPatternDetails pattern, Map<AEKey, Long> inputPerRun, long requested) {
        if (!gtlcore$canProduceGraphPattern(pattern)) return 0;
        InternalSlot slot = internalSlots.get(patternToSlot.getInt(pattern));
        if (slot == null) return requested;
        for (var input : inputPerRun.entrySet()) {
            long held;
            if (input.getKey() instanceof AEItemKey item) held = slot.getItemInventory().getLong(item);
            else if (input.getKey() instanceof AEFluidKey fluid) held = slot.getFluidInventory().getLong(fluid);
            else return 0;
            if (held < 0) return 0;
            requested = Math.min(requested, (Long.MAX_VALUE - held) / input.getValue());
        }
        return requested;
    }

    @Override
    protected boolean hasRecipeCacheInSlot(int slot) {
        return recipeCache.containsKey(slot);
    }

    @Override
    protected int[] getActiveSlots() {
        activeSlots.removeIf(index -> !internalSlots.get(index).isActive());
        return activeSlots.toIntArray();
    }

    @Override
    protected Iterable<InternalSlot> getActiveInternalSlots() {
        List<InternalSlot> slots = new ObjectArrayList<>();
        for (int index : getActiveSlots()) slots.add(internalSlots.get(index));
        return slots;
    }

    @Override
    protected void refundAll(ClickData clickData) {
        if (clickData.isRemote) return;
        for (InternalSlot slot : internalSlots.values()) {
            refundSlot(slot.getItemInventory(), slot.getFluidInventory());
            slot.clearVirtualSupply();
        }
        activeSlots.clear();
        for (int index = 0; index < pendingSlotData.size(); index++) {
            refundPersistedSlot(pendingSlotData.getCompound(index).getCompound("slot"));
        }
        pendingSlotData = new ListTag();
        AEUtils.reFunds(buffer, getMainNode().getGrid(), actionSource);
        notifyInputsChanged();
    }

    @Override
    public void saveCustomPersistedData(@NotNull CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        ListTag saved = collectSlotData();
        saved.addAll(pendingSlotData);
        tag.put(SLOT_DATA_KEY, saved);
        tag.put("recipeBlacklist", saveRecipeList(blacklist));
        tag.put("recipeWhitelist", saveRecipeList(whitelist));
        tag.put("recipeFilterRules", saveRules(filterRules));
        tag.put("recipeWhitelistRules", saveRules(whitelistRules));
        ListTag primaries = new ListTag();
        primaryOverrides.forEach((id, primary) -> {
            CompoundTag data = new CompoundTag();
            data.putString("recipe", id);
            data.put("output", primary.toTagGeneric());
            primaries.add(data);
        });
        tag.put("recipePrimaryOutputs", primaries);
    }

    private static ListTag saveRecipeList(Set<String> ids) {
        ListTag saved = new ListTag();
        ids.forEach(id -> saved.add(StringTag.valueOf(id)));
        return saved;
    }

    private static ListTag saveRules(List<Rule> filterRules) {
        ListTag rules = new ListTag();
        for (Rule rule : filterRules) {
            CompoundTag data = new CompoundTag();
            data.putString("target", rule.target().name());
            data.putString("expression", rule.expression());
            rules.add(data);
        }
        return rules;
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        pendingSlotData = tag.getList(SLOT_DATA_KEY, Tag.TAG_COMPOUND).copy();
        loadRecipeList(tag.getList("recipeBlacklist", Tag.TAG_STRING), blacklist);
        loadRecipeList(tag.getList("recipeWhitelist", Tag.TAG_STRING), whitelist);
        loadRules(tag.getList("recipeFilterRules", Tag.TAG_COMPOUND), filterRules);
        loadRules(tag.getList("recipeWhitelistRules", Tag.TAG_COMPOUND), whitelistRules);
        rebuildRuleMatches();
        primaryOverrides.clear();
        ListTag primaries = tag.getList("recipePrimaryOutputs", Tag.TAG_COMPOUND);
        for (int index = 0; index < primaries.size(); index++) {
            CompoundTag data = primaries.getCompound(index);
            AEKey primary = AEKey.fromTagGeneric(data.getCompound("output"));
            if (primary != null) primaryOverrides.put(data.getString("recipe"), primary);
        }
        reloadGeneration = -1;
    }

    private static void loadRecipeList(ListTag saved, Set<String> ids) {
        ids.clear();
        for (int index = 0; index < saved.size(); index++) ids.add(saved.getString(index));
    }

    private static void loadRules(ListTag rules, List<Rule> filterRules) {
        filterRules.clear();
        for (int index = 0; index < rules.size() && filterRules.size() < MERecipePatternFilter.MAX_RULES; index++) {
            CompoundTag data = rules.getCompound(index);
            String expression = data.getString("expression");
            if (expression.isBlank() || expression.length() > MERecipePatternFilter.MAX_EXPRESSION_LENGTH) continue;
            try {
                Rule rule = new Rule(Target.valueOf(data.getString("target")), expression);
                if (!filterRules.contains(rule)) filterRules.add(rule);
            } catch (IllegalArgumentException ignored) {
                // Unknown targets from a newer save do not prevent the hatch from loading.
            }
        }
    }

    @Override
    public @NotNull ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel panel) {
        super.attachConfigurators(panel);
        panel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                org.gtlcore.gtlcore.api.gui.GuiTextures.BUTTON_DISABLE_BYPRODUCT.getSubTexture(0, 0, 1, 0.5),
                org.gtlcore.gtlcore.api.gui.GuiTextures.BUTTON_DISABLE_BYPRODUCT.getSubTexture(0, 0.5, 1, 0.5),
                () -> !keepByProduct, (clickData, pressed) -> {
                    if (clickData.isRemote) return;
                    keepByProduct = !pressed;
                    configurationChanged();
                }).setTooltipsSupplier(pressed -> List.of(Component.translatable("tooltip.gtlcore.disable_by_product")
                        .append(Component.translatable(pressed ? "gtceu.multiblock.universal.distinct.yes" : "gtceu.multiblock.universal.distinct.no")))));
    }

    @Override
    public void attachSideTabs(TabsWidget sideTabs) {
        super.attachSideTabs(sideTabs);
        sideTabs.attachSubTab(new RecipePatternManagementPage(this, Page.SEARCH));
        sideTabs.attachSubTab(new RecipePatternManagementPage(this, Page.FILTERS));
    }

    @Override
    public ModularUI createUI(Player player) {
        return new ModularUI(176, 166, this, player)
                .widget(new RecipePatternMachineUIWidget(this, 176, 166));
    }

    @Override
    public Widget createUIWidget() {
        WidgetGroup group = new WidgetGroup(0, 0, 248, 142);
        group.addWidget(new LabelWidget(8, 4, () -> isOnline ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline")
                .setTextColor(0xFF303541).setDropShadow(false));
        group.addWidget(new AETextInputButtonWidget(180, 4, 60, 10).setText(customName).setOnConfirm(this::setCustomName)
                .setButtonTooltips(Component.translatable("gui.gtceu.rename.desc")));
        group.addWidget(new LabelWidget(8, 23, () -> Component.translatable("gtceu.machine.me_recipe_pattern_buffer.mode",
                activeRecipeTypes.isEmpty() ? Component.translatable("gtceu.machine.me_recipe_pattern_buffer.unformed").getString() :
                        activeRecipeTypes.stream().map(type -> Component.translatable(type.registryName.toLanguageKey()).getString())
                                .collect(Collectors.joining(", ")))
                .getString()).setTextColor(0xFF303541).setDropShadow(false));
        group.addWidget(new RecipePatternBrowserWidget(8, 42, this, Page.OVERVIEW));
        group.addWidget(new LabelWidget(8, 128, () -> Component.translatable("gtceu.machine.me_recipe_pattern_buffer.buffer_counts",
                getActiveSlots().length, buffer.size()).getString()).setTextColor(0xFF616978).setDropShadow(false));
        return group;
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return Collections.unmodifiableList(availablePatterns);
    }

    @Override
    public boolean isVisibleInTerminal() {
        return false;
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return InternalInventory.empty();
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        return new PatternContainerGroup(AEItemKey.of(getDefinition().asStack()),
                customName.isEmpty() ? getDefinition().getItem().getDescription() : Component.literal(customName), List.of());
    }

    @Override
    protected @NotNull RecipeMEPatternTrait createMETrait() {
        return new RecipeMEPatternTrait();
    }

    @Override
    public Pair<IMERecipeHandlerTrait<Ingredient, ItemStack>, IMERecipeHandlerTrait<FluidIngredient, FluidStack>> getMERecipeHandlerTraits() {
        return Pair.of(recipeHandler.getMeItemHandler(), recipeHandler.getMeFluidHandler());
    }

    private class Ticker implements IGridTickable {

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(5, 60, false, true);
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            if (!getMainNode().isActive() || buffer.isEmpty()) return TickRateModulation.SLEEP;
            if (AEUtils.reFunds(buffer, getMainNode().getGrid(), actionSource)) {
                markDirty();
                return TickRateModulation.URGENT;
            }
            return TickRateModulation.SLOWER;
        }
    }

    protected class RecipeMEPatternTrait extends MEIOTrait implements IMEPatternTrait {

        public RecipeMEPatternTrait() {
            super(MERecipePatternBufferPartMachine.this);
        }

        @Override
        public @NotNull ObjectSet<@NotNull GTRecipe> getCachedGTRecipe() {
            ObjectSet<GTRecipe> recipes = new ObjectOpenHashSet<>();
            for (int slot : getActiveSlots()) recipes.add(recipeCache.get(slot));
            return recipes;
        }

        @Override
        public void setSlotCacheRecipe(int index, GTRecipe recipe) {
            // Each generated slot belongs to one known GT recipe; matching must not replace that binding.
            if (hasPatternInSlot(index)) recipeCache.put(index, recipePatterns.get(index).recipe());
        }

        @Override
        public @NotNull Int2ReferenceMap<ObjectSet<@NotNull GTRecipe>> getSlot2RecipesCache() {
            Int2ReferenceMap<ObjectSet<@NotNull GTRecipe>> slots = new Int2ReferenceOpenHashMap<>();
            for (var entry : Int2ReferenceMaps.fastIterable(recipeCache)) {
                slots.put(entry.getIntKey(), new ObjectArraySet<>(new GTRecipe[] { entry.getValue() }));
            }
            return slots;
        }

        @Override
        public void setOnPatternChange(IntConsumer removeMapOnSlot) {
            removeSlotFromMap = removeMapOnSlot;
        }

        @Override
        public boolean hasCacheInSlot(int slot) {
            return recipeCache.containsKey(slot);
        }
    }
}
