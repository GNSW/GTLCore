package org.gtlcore.gtlcore.mixin.gtm.api.capability;

import org.gtlcore.gtlcore.api.recipe.IParallelLogic;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.integration.jei.RecipeChanceTooltip;

import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.ResearchData;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.*;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntProviderIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.recipe.ui.GTRecipeTypeUI;
import com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition;
import com.gregtechceu.gtceu.common.valueprovider.*;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.gregtechceu.gtceu.utils.ResearchManager;

import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.utils.CycleItemStackHandler;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import com.google.common.primitives.Ints;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.ArrayList;
import java.util.List;

import static com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability.CAP;

@Mixin(ItemRecipeCapability.class)
public abstract class ItemRecipeCapabilityMixin extends RecipeCapability<Ingredient> {

    protected ItemRecipeCapabilityMixin(String name, int color, boolean doRenderSlot, int sortIndex, IContentSerializer<Ingredient> serializer) {
        super(name, color, doRenderSlot, sortIndex, serializer);
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public int limitParallel(GTRecipe recipe, IRecipeCapabilityHolder holder, int multiplier) {
        return Ints.saturatedCast(IParallelLogic.getOutputItemParallel(holder, recipe, recipe.getOutputContents(CAP), multiplier));
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public int getMaxParallelRatio(IRecipeCapabilityHolder holder, GTRecipe recipe, int parallelAmount) {
        return Ints.saturatedCast(IParallelLogic.getInputItemParallel(holder, recipe, parallelAmount));
    }

    /**
     * @author Dragons
     * @reason 提供Long级别物品处理
     */
    @Overwrite(remap = false)
    public Ingredient copyWithModifier(Ingredient content, ContentModifier modifier) {
        if (content instanceof LongIngredient longIngredient) {
            return LongIngredient.create(longIngredient.getInner(), modifier.apply(longIngredient.getActualAmount()).longValue());
        } else if (content instanceof SizedIngredient sizedIngredient) {
            return LongIngredient.create(sizedIngredient.getInner(), modifier.apply((long) sizedIngredient.getAmount()).longValue());
        } else if (content instanceof IntProviderIngredient intProviderIngredient) {
            return new IntProviderIngredient(intProviderIngredient.getInner(), new FlooredInt(new AddedFloat(new MultipliedFloat(new CastedFloat(intProviderIngredient.getCountProvider()), ConstantFloat.of((float) modifier.getMultiplier())), ConstantFloat.of((float) modifier.getAddition()))));
        } else {
            return LongIngredient.create(content, modifier.apply(1L).longValue());
        }
    }

    /**
     * @author Dragons
     * @reason 提供Long级别物品处理
     */
    @Overwrite(remap = false)
    public Ingredient copyInner(Ingredient content) {
        return LongIngredient.copy(content);
    }

    @Override
    public void applyWidgetInfo(@NotNull Widget widget,
                                int index,
                                boolean isXEI,
                                IO io,
                                GTRecipeTypeUI.@UnknownNullability("null when storage == null") RecipeHolder recipeHolder,
                                @NotNull GTRecipeType recipeType,
                                @UnknownNullability("null when content == null") GTRecipe recipe,
                                @Nullable Content content,
                                @Nullable Object storage) {
        if (widget instanceof SlotWidget slot) {
            if (storage instanceof IItemTransfer items) {
                if (index >= 0 && index < items.getSlots()) {
                    slot.setHandlerSlot(items, index);
                    slot.setIngredientIO(io == IO.IN ? IngredientIO.INPUT : IngredientIO.OUTPUT);
                    slot.setCanTakeItems(!isXEI);
                    slot.setCanPutItems(!isXEI && io.support(IO.IN));
                }
                // 1 over container size.
                // If in a recipe viewer and a research slot can be added, add it.
                if (isXEI && recipeType.isHasResearchSlot() && index == items.getSlots()) {
                    if (ConfigHolder.INSTANCE.machines.enableResearch) {
                        ResearchCondition condition = recipeHolder.conditions().stream()
                                .filter(ResearchCondition.class::isInstance).findAny()
                                .map(ResearchCondition.class::cast).orElse(null);
                        if (condition != null) {
                            List<ItemStack> dataItems = new ArrayList<>();
                            for (ResearchData.ResearchEntry entry : condition.data) {
                                ItemStack dataStick = entry.getDataItem().copy();
                                ResearchManager.writeResearchToNBT(dataStick.getOrCreateTag(), entry.getResearchId(),
                                        recipeType);
                                dataItems.add(dataStick);
                            }
                            CycleItemStackHandler handler = new CycleItemStackHandler(List.of(dataItems));
                            slot.setHandlerSlot(handler, 0);
                            slot.setIngredientIO(IngredientIO.INPUT);
                            slot.setCanTakeItems(false);
                            slot.setCanPutItems(false);
                        }
                    }
                }
            }
            if (content != null) {
                slot.setXEIChance((float) content.chance / content.maxChance);
                slot.setOnAddedTooltips((w, tooltips) -> {
                    var ingredient = CAP.of(content.content);
                    long amount = 1;
                    if (ingredient instanceof LongIngredient li) amount = li.getActualAmount();
                    else if (ingredient instanceof SizedIngredient si) amount = si.getAmount();
                    tooltips.add(Component.translatable("gtceu.machine.quantum_chest.items_stored")
                            .withStyle(ChatFormatting.DARK_AQUA)
                            .append(Component.literal(String.valueOf(amount))));
                    RecipeChanceTooltip.add(content, ChanceLogic.OR, tooltips, io);
                    if (this.isTickSlot(index, io, recipe)) {
                        tooltips.add(Component.translatable("gtceu.gui.content.per_tick"));
                    }
                });
            }
        }
    }
}
