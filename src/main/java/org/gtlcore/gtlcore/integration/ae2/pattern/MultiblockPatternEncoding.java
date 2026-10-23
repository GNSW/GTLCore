package org.gtlcore.gtlcore.integration.ae2.pattern;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

import appeng.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Arrays;
import java.util.List;

import static org.gtlcore.gtlcore.config.ConfigHolder.INSTANCE;

/** The inputs and output shared by structure pattern encoding and its material preview. */
public final class MultiblockPatternEncoding {

    private MultiblockPatternEncoding() {}

    public static List<List<GenericStack>> inputs(List<List<GenericStack>> ingredients) {
        if (INSTANCE.machines.filterHatch.length == 0) return ingredients;
        var filtered = new ObjectArrayList<List<GenericStack>>();
        for (var options : ingredients) {
            var included = options.stream()
                    .filter(stack -> Arrays.stream(INSTANCE.machines.filterHatch)
                            .noneMatch(filter -> stack.what().getId().toString().contains(filter)))
                    .toList();
            if (!included.isEmpty()) filtered.add(included);
        }
        return filtered;
    }

    public static List<GenericStack> outputs(MultiblockMachineDefinition definition) {
        var book = GenericStack.fromItemStack(Items.WRITTEN_BOOK.getDefaultInstance()
                .kjs$withName(Component.translatable(definition.getId().toLanguageKey("block"))
                        .withStyle(style -> style.withColor(16536828))));
        return book == null ? List.of() : List.of(book);
    }
}
