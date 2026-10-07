package org.gtlcore.gtlcore.common.machine.multiblock.part.ae;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;

import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.world.item.crafting.Ingredient;

import appeng.api.stacks.AEFluidKey;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.List;

public class MERecipePatternBufferRecipeHandlerTrait extends MEPatternBufferRecipeHandlerTraitBase {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(MERecipePatternBufferRecipeHandlerTrait.class);

    public MERecipePatternBufferRecipeHandlerTrait(MERecipePatternBufferPartMachine machine, IO io) {
        super(machine, io);
    }

    @Override
    protected MEItemInputHandlerBase createMEItemHandler(IO io) {
        return new RecipeItemHandler(getMachine(), io);
    }

    @Override
    protected MEFluidHandlerBase createMEFluidHandler(IO io) {
        return new RecipeFluidHandler(getMachine(), io);
    }

    @Override
    public MERecipePatternBufferPartMachine getMachine() {
        return (MERecipePatternBufferPartMachine) super.getMachine();
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    private static class RecipeItemHandler extends MEItemInputHandlerBase {

        RecipeItemHandler(MERecipePatternBufferPartMachine machine, IO io) {
            super(machine, io);
        }

        @Override
        public MERecipePatternBufferPartMachine getMachine() {
            return (MERecipePatternBufferPartMachine) super.getMachine();
        }

        @Override
        public boolean meHandleRecipeInner(GTRecipe recipe, Object2LongMap<Ingredient> left, boolean simulate, int slot) {
            if (!getMachine().matchesRecipe(slot, recipe)) return false;
            boolean handled = super.meHandleRecipeInner(recipe, left, simulate, slot);
            if (!simulate) getMachine().markDirty();
            return handled;
        }

        @Override
        public List<Ingredient> meHandleRecipeOutputInner(List<Ingredient> left, boolean simulate) {
            var remaining = super.meHandleRecipeOutputInner(left, simulate);
            if (!simulate) getMachine().markDirty();
            return remaining;
        }
    }

    private static class RecipeFluidHandler extends MEFluidHandlerBase {

        RecipeFluidHandler(MERecipePatternBufferPartMachine machine, IO io) {
            super(machine, io);
        }

        @Override
        public MERecipePatternBufferPartMachine getMachine() {
            return (MERecipePatternBufferPartMachine) super.getMachine();
        }

        @Override
        public boolean meHandleRecipeInner(GTRecipe recipe, Object2LongMap<FluidIngredient> left, boolean simulate, int slot) {
            if (!getMachine().matchesRecipe(slot, recipe)) return false;
            boolean handled = super.meHandleRecipeInner(recipe, left, simulate, slot);
            if (!simulate) getMachine().markDirty();
            return handled;
        }

        @Override
        public List<FluidIngredient> meHandleRecipeOutputInner(List<FluidIngredient> left, boolean simulate) {
            if (simulate) return List.of();
            for (FluidIngredient ingredient : left) {
                var fluids = ingredient.getStacks();
                if (fluids.length > 0 && !fluids[0].isEmpty()) {
                    var stack = fluids[0];
                    getMachine().buffer.addTo(AEFluidKey.of(stack.getFluid(), stack.getTag()), ingredient.getAmount());
                }
            }
            getMachine().markDirty();
            return List.of();
        }
    }
}
