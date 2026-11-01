package org.gtlcore.gtlcore.integration.terminal.host;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HatchDetector {

    private static final Map<Block, Boolean> CACHE = new ConcurrentHashMap<>();

    private HatchDetector() {}

    public static boolean containsHatch(BlockInfo[] infos) {
        if (infos == null) {
            return false;
        }
        for (BlockInfo info : infos) {
            if (info != null && isHatch(info.getBlockState().getBlock())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isHatch(Block block) {
        return CACHE.computeIfAbsent(block, HatchDetector::computeIsHatch);
    }

    private static boolean computeIsHatch(Block block) {
        if (!(block instanceof MetaMachineBlock machineBlock)) {
            return false;
        }

        MachineDefinition definition = machineBlock.getDefinition();
        if (definition.getRecipeTypes() != null || definition instanceof MultiblockMachineDefinition) {
            return false;
        }

        try {
            var blockEntity = definition.getBlockEntityType()
                    .create(BlockPos.ZERO, machineBlock.defaultBlockState());
            if (blockEntity instanceof IMachineBlockEntity machineBlockEntity) {
                MetaMachine machine = definition.createMetaMachine(machineBlockEntity);
                if (machine instanceof MultiblockPartMachine) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to the registry id below for machines that cannot be instantiated headlessly.
        }

        String path = BuiltInRegistries.BLOCK.getKey(block).getPath().toLowerCase(Locale.ROOT);
        return path.contains("hatch") || path.contains("maintenance") || path.endsWith("_bus") || path.contains("pattern_buffer") || path.contains("input_bus") || path.contains("output_bus");
    }
}
