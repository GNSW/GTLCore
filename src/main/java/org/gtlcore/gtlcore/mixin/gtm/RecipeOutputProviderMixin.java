package org.gtlcore.gtlcore.mixin.gtm;

import org.gtlcore.gtlcore.utils.GTLUtil;
import org.gtlcore.gtlcore.utils.NumberUtils;

import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.integration.jade.provider.CapabilityBlockProvider;
import com.gregtechceu.gtceu.integration.jade.provider.RecipeOutputProvider;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * @author EasterFG on 2025/3/8
 */
@Mixin(RecipeOutputProvider.class)
public abstract class RecipeOutputProviderMixin extends CapabilityBlockProvider<RecipeLogic> {

    @Shadow(remap = false)
    protected abstract void addFluidTooltips(ITooltip iTooltip, List<FluidStack> outputFluids);

    @Shadow(remap = false)
    protected abstract Component getItemName(ItemStack stack);

    protected RecipeOutputProviderMixin(ResourceLocation uid) {
        super(uid);
    }

    @Override
    protected void write(CompoundTag data, RecipeLogic recipeLogic) {
        if (recipeLogic.isWorking()) {
            data.putBoolean("Working", true);
            var recipe = recipeLogic.getLastRecipe();
            if (recipe != null) org.gtlcore.gtlcore.integration.monitor.MachineMonitorEntries.writeJadeOutputs(data, recipe);
        }
    }

    @Override
    protected void addTooltip(CompoundTag capData, ITooltip tooltip, Player player, BlockAccessor block,
                              BlockEntity blockEntity, IPluginConfig config) {
        if (capData.getBoolean("Working")) {
            List<CompoundTag> outputItems = new ArrayList<>();
            if (capData.contains("OutputItems", Tag.TAG_LIST)) {
                ListTag itemTags = capData.getList("OutputItems", Tag.TAG_COMPOUND);
                for (Tag tag : itemTags) {
                    if (tag instanceof CompoundTag tCompoundTag) {
                        outputItems.add(tCompoundTag);
                    }
                }
            }
            List<FluidStack> outputFluids = new ArrayList<>();
            if (capData.contains("OutputFluids", Tag.TAG_LIST)) {
                ListTag fluidTags = capData.getList("OutputFluids", Tag.TAG_COMPOUND);
                for (Tag tag : fluidTags) {
                    if (tag instanceof CompoundTag tCompoundTag) {
                        var stack = FluidStack.loadFromTag(tCompoundTag);
                        if (!stack.isEmpty()) {
                            outputFluids.add(stack);
                        }
                    }
                }
            }
            if (!outputItems.isEmpty() || !outputFluids.isEmpty()) {
                tooltip.add(Component.translatable("gtceu.top.recipe_output"));
            }
            gTLCore$addItemTooltips(tooltip, outputItems);
            addFluidTooltips(tooltip, outputFluids);
        }
    }

    @Unique
    private void gTLCore$addItemTooltips(ITooltip iTooltip, List<CompoundTag> outputItems) {
        IElementHelper helper = iTooltip.getElementHelper();
        for (CompoundTag tag : outputItems) {
            if (tag != null && !tag.isEmpty()) {
                ItemStack stack = GTLUtil.loadItemStack(tag);
                long count = tag.getLong("Count");
                iTooltip.add(helper.smallItem(stack));
                Component text = Component.literal(" ")
                        .append(NumberUtils.formatLong(count))
                        .append("× ")
                        .append(getItemName(stack))
                        .withStyle(ChatFormatting.WHITE);
                iTooltip.append(text);
            }
        }
    }
}
