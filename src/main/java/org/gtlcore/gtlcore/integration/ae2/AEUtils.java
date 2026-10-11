package org.gtlcore.gtlcore.integration.ae2;

import org.gtlcore.gtlcore.api.recipe.ingredient.CacheHashStrategies;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.config.AE2CalculationMode;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.utils.NumberUtils;

import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.nbt.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.pattern.*;
import it.unimi.dsi.fastutil.objects.*;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static appeng.crafting.execution.CraftingCpuHelper.*;

public class AEUtils {

    private static final int BATCH_SIZE = 64;
    private static final int MAX_FAILED_ATTEMPTS = 5;

    public static <T extends AEKey> boolean reFunds(Object2LongMap<T> buffer, @Nullable IGrid network, IActionSource actionSource) {
        if (buffer.isEmpty()) return false;

        if (network == null) return false;

        final MEStorage networkInv = network.getStorageService().getInventory();
        final var energy = network.getEnergyService();
        int operationsBatched = 0, consecutiveFailures = 0;
        boolean didWork = false;

        for (var it = Object2LongMaps.fastIterator(buffer); it.hasNext() && operationsBatched < BATCH_SIZE;) {
            var entry = it.next();
            long amount = entry.getLongValue();

            if (amount <= 0) {
                it.remove();
                continue;
            }

            long inserted = StorageHelper.poweredInsert(energy, networkInv, entry.getKey(), amount, actionSource);
            operationsBatched++;

            if (inserted > 0) {
                didWork = true;
                consecutiveFailures = 0;
                long left = amount - inserted;
                if (left <= 0) {
                    it.remove();
                } else {
                    entry.setValue(left);
                }
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_FAILED_ATTEMPTS) {
                    break;
                }
            }
        }
        return didWork;
    }

    // ========================================
    // Recipe
    // ========================================

    public static boolean testFluidIngredient(FluidIngredient fluidIngredient, AEFluidKey fluidKey) {
        if (fluidIngredient.isEmpty()) {
            return false;
        } else if (fluidIngredient.getNbt() != null && !fluidIngredient.getNbt().equals(fluidKey.getTag())) {
            return false;
        } else {
            for (FluidStack fluidStack : fluidIngredient.getStacks()) {
                if (fluidStack.getFluid() == fluidKey.getFluid()) {
                    return true;
                }
            }
            return false;
        }
    }

    // ========================================
    // Persist
    // ========================================

    public static <T> ListTag createListTag(Function<T, CompoundTag> keySerializer, Object2LongMap<T> map) {
        ListTag tag = new ListTag();
        for (var it = Object2LongMaps.fastIterator(map); it.hasNext();) {
            var entry = it.next();
            var ct = keySerializer.apply(entry.getKey());
            ct.putLong("real", entry.getLongValue());
            tag.add(ct);
        }
        return tag;
    }

    public static <K> void loadInventory(ListTag tag, Function<CompoundTag, K> keyExtractor, Object2LongMap<K> targetMap) {
        for (Tag t : tag) {
            if (!(t instanceof CompoundTag ct)) continue;
            K key = keyExtractor.apply(ct);
            long value = ct.getLong("real");
            if (key != null && value > 0) {
                targetMap.put(key, value);
            }
        }
    }

    public static <T> ListTag createListTag(Function<T, CompoundTag> keySerializer, ObjectSet<T> map) {
        ListTag tag = new ListTag();
        for (T t : map) {
            var ct = keySerializer.apply(t);
            tag.add(ct);
        }
        return tag;
    }

    public static <K> void loadInventory(ListTag tag, Function<CompoundTag, K> keyExtractor, ObjectSet<K> targetMap) {
        for (Tag t : tag) {
            if (!(t instanceof CompoundTag ct)) continue;
            K key = keyExtractor.apply(ct);
            if (key != null) {
                targetMap.add(key);
            }
        }
    }

    public static <K> void appendPersistedInventory(ListTag tag, Function<CompoundTag, K> keyExtractor,
                                                    Object2LongOpenHashMap<K> targetMap) {
        for (Tag t : tag) {
            if (!(t instanceof CompoundTag ct)) continue;

            K key = keyExtractor.apply(ct);
            long amount = ct.getLong("real");
            if (key != null && amount > 0) {
                targetMap.addTo(key, amount);
            }
        }
    }

    // ========================================
    // ME IO Machine Utils
    // ========================================

    public static Object2LongMap<Ingredient> ingredientsMapWithOutCircuit(List<Ingredient> ingredients, Consumer<Integer> consumer) {
        var result = new Object2LongOpenCustomHashMap<>(CacheHashStrategies.IngredientHashStrategy.INSTANCE);
        for (Ingredient ingredient : ingredients) {
            var items = ingredient.getItems();
            if (items.length == 0 || items[0].isEmpty()) {
                continue;
            }
            if (GTItems.INTEGRATED_CIRCUIT.is(items[0].getItem())) {
                consumer.accept(IntCircuitBehaviour.getCircuitConfiguration(items[0]));
                continue;
            }
            result.addTo(ingredient, ingredient instanceof LongIngredient longIngredient ? longIngredient.getActualAmount() : items[0].getCount());
        }
        return result;
    }

    public static Object2LongMap<Ingredient> ingredientsMap(List<Ingredient> ingredients) {
        var result = new Object2LongOpenCustomHashMap<>(CacheHashStrategies.IngredientHashStrategy.INSTANCE);
        for (Ingredient ingredient : ingredients) {
            var items = ingredient.getItems();
            if (items.length == 0 || items[0].isEmpty()) {
                continue;
            }
            result.addTo(ingredient, ingredient instanceof LongIngredient longIngredient ? longIngredient.getActualAmount() : items[0].getCount());
        }
        return result;
    }

    public static Object2LongMap<FluidIngredient> fluidIngredientsMap(List<FluidIngredient> ingredients) {
        var result = new Object2LongOpenCustomHashMap<>(CacheHashStrategies.FluidIngredientHashStrategy.INSTANCE);
        for (FluidIngredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            result.addTo(ingredient, ingredient.getAmount());
        }
        return result;
    }

    public static Function<ItemStack, Boolean> PROCESS_FILTER = stack -> stack.getItem() instanceof ProcessingPatternItem;

    public static boolean molecularFilter(ItemStack stack, Level level) {
        final var item = stack.getItem();
        if (item instanceof CraftingPatternItem craftingPatternItem) {
            var pattern = craftingPatternItem.decode(stack, level, false);
            if (pattern != null) {
                return !hasContainerItems(pattern);
            }
        } else {
            return item instanceof SmithingTablePatternItem || item instanceof StonecuttingPatternItem;
        }
        return false;
    }

    private static boolean hasContainerItems(AECraftingPattern pattern) {
        IPatternDetails.IInput[] inputs = pattern.getInputs();

        for (IPatternDetails.IInput input : inputs) {
            AEKey key = input.getPossibleInputs()[0].what();
            AEKey remainingKey = input.getRemainingKey(key);
            if (remainingKey != null) {
                if (!(remainingKey instanceof AEItemKey itemKey) || itemKey.toStack().isDamageableItem()) {
                    return true;
                }
            }
        }

        return false;
    }

    public static Pair<IPatternDetails, @Nullable ObjectSet<Item>> createProcessingFromCraftPattern(IMolecularAssemblerSupportedPattern molecularAssemblerSupportedPattern, Level level) {
        IPatternDetails.IInput[] inputs = molecularAssemblerSupportedPattern.getInputs();

        ObjectArrayList<GenericStack> normalInputs = new ObjectArrayList<>();
        ObjectSet<Item> remainingInputs = new ObjectArraySet<>();
        for (IPatternDetails.IInput input : inputs) {
            final var stack = input.getPossibleInputs()[0];
            final var remaining = input.getRemainingKey(stack.what());
            if (remaining != null) {
                assert remaining instanceof AEItemKey;
                if (((AEItemKey) remaining).toStack().isDamageableItem()) {
                    return ImmutablePair.of(null, null);

                } else remainingInputs.add(((AEItemKey) remaining).getItem());
            } else {
                normalInputs.add(new GenericStack(stack.what(), stack.amount() * input.getMultiplier()));
            }
        }

        ItemStack pattern = PatternDetailsHelper.encodeProcessingPattern(normalInputs.toArray(new GenericStack[0]), molecularAssemblerSupportedPattern.getOutputs());

        return ImmutablePair.of(PatternDetailsHelper.decodePattern(pattern, level), remainingInputs);
    }

    // ========================================
    // ME Processing Pattern Multiply
    // ========================================

    public static AE2CalculationMode getCalculationMode() {
        return ConfigHolder.INSTANCE.ae.ae2CalculationMode;
    }

    public static boolean isIntegratedCircuit(AEKey key) {
        return key instanceof AEItemKey itemKey && GTItems.INTEGRATED_CIRCUIT.is(itemKey.getItem());
    }

    public static void pushInputsToMEPatternBufferInventory(KeyCounter[] inputHolder, IPatternDetails.PatternInputSink inputSink) {
        for (var inputList : inputHolder) {
            for (var input : inputList) {
                inputSink.pushInput(input.getKey(), input.getLongValue());
            }
        }
    }

    public static KeyCounter[] extractForProcessingPattern(IPatternDetails originDetail,
                                                           ICraftingInventory sourceInv,
                                                           KeyCounter expectedOutputs) {
        return extractForProcessingPattern(originDetail, sourceInv, expectedOutputs, 1);
    }

    public static KeyCounter[] extractForProcessingPattern(IPatternDetails originDetail,
                                                           ICraftingInventory sourceInv,
                                                           KeyCounter expectedOutputs,
                                                           long multiplier) {
        IPatternDetails.IInput[] inputs = originDetail.getInputs();
        KeyCounter[] inputHolder = new KeyCounter[inputs.length];
        boolean found = true;

        for (int x = 0; x < inputs.length; x++) {
            var list = inputHolder[x] = new KeyCounter();
            AEKey key = inputs[x].getPossibleInputs()[0].what();
            // Programmed circuits only reconfigure the target circuit slot,
            // so always push exactly one operation's worth regardless of the multiplier.
            long effectiveMultiplier = isIntegratedCircuit(key) ? 1 : multiplier;
            long amount = NumberUtils.saturatedMultiply(inputs[x].getMultiplier(), effectiveMultiplier);
            long extracted = AEUtils.extractTemplates(sourceInv, key, amount);
            list.add(key, extracted);
            if (extracted < amount) {
                found = false;
                break;
            }
        }

        if (!found) {
            reinjectPatternInputs(sourceInv, inputHolder);
            return null;
        } else {
            for (GenericStack output : originDetail.getOutputs()) {
                expectedOutputs.add(output.what(), NumberUtils.saturatedMultiply(output.amount(), multiplier));
            }
            return inputHolder;
        }
    }

    private static long extractTemplates(ICraftingInventory inv, AEKey key, long amount) {
        if (amount == 0) return 0;
        long simEx = inv.extract(key, amount, Actionable.SIMULATE);
        if (simEx == 0) return 0;
        long extracted = inv.extract(key, simEx, Actionable.MODULATE);
        if (extracted == 0 || extracted != simEx) {
            throw new IllegalStateException("Failed to correctly extract whole number. Invalid simulation!");
        }
        return extracted;
    }

    public static KeyCounter[] extractForCraftPattern(IPatternDetails details,
                                                      ICraftingInventory sourceInv,
                                                      Level level,
                                                      KeyCounter expectedOutputs,
                                                      KeyCounter expectedContainerItems) {
        var inputs = details.getInputs();
        KeyCounter[] inputHolder = new KeyCounter[inputs.length];
        boolean found = true;

        for (int x = 0; x < inputs.length; x++) {
            var list = inputHolder[x] = new KeyCounter();
            long remainingMultiplier = inputs[x].getMultiplier();
            for (var template : getValidItemTemplates(sourceInv, inputs[x], level)) {
                long extracted = CraftingCpuHelper.extractTemplates(sourceInv, template, remainingMultiplier);
                list.add(template.key(), extracted * template.amount());

                var containerItem = inputs[x].getRemainingKey(template.key());
                if (containerItem != null) {
                    expectedContainerItems.add(containerItem, extracted);
                }

                remainingMultiplier -= extracted;
                if (remainingMultiplier == 0)
                    break;
            }

            if (remainingMultiplier > 0) {
                found = false;
                break;
            }
        }

        if (!found) {
            reinjectPatternInputs(sourceInv, inputHolder);
            return null;
        }

        for (GenericStack output : details.getOutputs()) {
            expectedOutputs.add(output.what(), output.amount());
        }

        return inputHolder;
    }
}
