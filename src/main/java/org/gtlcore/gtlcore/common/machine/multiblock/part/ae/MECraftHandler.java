package org.gtlcore.gtlcore.common.machine.multiblock.part.ae;

import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;

import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;

import net.minecraft.world.item.crafting.Ingredient;

import appeng.api.stacks.AEItemKey;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Collections;
import java.util.List;

public class MECraftHandler extends NotifiableMAHandlerTrait {

    public MECraftHandler(MEMolecularAssemblerIOPartMachine machine) {
        super(machine);
    }

    public MEMolecularAssemblerIOPartMachine getMachine() {
        return (MEMolecularAssemblerIOPartMachine) this.machine;
    }

    @Override
    public void handleRecipeOutput(GTRecipe recipe) {
        final var buffer = getMachine().getBuffer();
        for (Content content : recipe.outputs.getOrDefault(ItemRecipeCapability.CAP, Collections.emptyList())) {
            if (content.content instanceof LongIngredient longIngredient) {
                buffer.addTo(AEItemKey.of(longIngredient.getItems()[0]), longIngredient.getActualAmount());
            }
        }
        getMachine().getMETrait().notifySelfIO();
    }

    @Override
    public GTRecipe extractGTRecipe(long parallelAmount, int tickDuration) {
        GTRecipe output = GTRecipeBuilder.ofRaw().buildRawRecipe();
        List<Content> outputList = output.outputs.computeIfAbsent(ItemRecipeCapability.CAP, cap -> new ObjectArrayList<>());
        long remain = parallelAmount;
        for (var it = Object2LongMaps.fastIterator(getMachine().getOutputItems()); it.hasNext() && remain > 0;) {
            var entry = it.next();
            var key = entry.getKey();
            if (!(key.what() instanceof AEItemKey aeItemKey)) {
                it.remove();
                continue;
            }
            long multiply = entry.getLongValue();

            long extract = Math.min(multiply, remain);

            var cont = new Content(LongIngredient.create(Ingredient.of(aeItemKey.toStack()), extract * key.amount()), ChanceLogic.getMaxChancedValue(), ChanceLogic.getMaxChancedValue(), 0, null, null);
            outputList.add(cont);

            remain -= extract;
            multiply -= extract;
            if (multiply == 0) it.remove();
            else entry.setValue(multiply);
        }
        if (outputList.isEmpty()) return null;
        else {
            output.duration = tickDuration;
            return output;
        }
    }
}
