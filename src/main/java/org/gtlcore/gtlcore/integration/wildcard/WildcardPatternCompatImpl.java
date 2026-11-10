package org.gtlcore.gtlcore.integration.wildcard;

import org.gtlcore.gtlcore.common.data.GTLMachines;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import org.leodreamer.wildcard_pattern.WildcardItems;
import org.leodreamer.wildcard_pattern.wildcard.WildcardPatternLogic;

import java.util.ArrayList;
import java.util.List;

import static com.gregtechceu.gtceu.common.registry.GTRegistration.REGISTRATE;

/**
 * Actual implementation of wildcard pattern integration.
 * This class will only be loaded when wildcard_pattern mod is present.
 */
public class WildcardPatternCompatImpl {

    public static MachineDefinition ME_WILDCARD_PATTERN_BUFFER;
    public static MachineDefinition ME_STOCKING_WILDCARD_PATTERN_BUFFER;

    static void init() {
        // Initialization logic if needed
    }

    static void registerMachines() {
        ME_WILDCARD_PATTERN_BUFFER = REGISTRATE
                .machine("me_wildcard_pattern_buffer", holder -> new MEWildcardPatternBufferPartMachine(holder, IO.BOTH))
                .langValue("ME Wildcard Pattern Buffer")
                .tier(GTValues.UHV)
                .rotationState(RotationState.ALL)
                .abilities(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS, PartAbility.EXPORT_ITEMS,
                        PartAbility.EXPORT_FLUIDS)
                .overlayTieredHullRenderer("me_pattern_buffer")
                .tooltips(
                        Component.translatable("block.gtceu.pattern_buffer.desc.0"),
                        Component.translatable("gtceu.machine.me_wildcard_pattern_buffer.desc.0"),
                        Component.translatable("gtceu.machine.me_pattern_buffer.desc.3"),
                        Component.translatable("gtceu.machine.me_pattern_buffer.desc.5"),
                        Component.translatable("block.gtceu.pattern_buffer.desc.2"),
                        Component.translatable("gtceu.universal.enabled"))
                .tooltipBuilder(GTLMachines.GTL_ADD)
                .register();

        ME_STOCKING_WILDCARD_PATTERN_BUFFER = REGISTRATE
                .machine("me_stocking_wildcard_pattern_buffer", holder -> new MEStockingWildcardPatternBufferPartMachine(holder, IO.BOTH))
                .langValue("ME Stocking Wildcard Pattern Buffer")
                .tier(GTValues.UIV)
                .rotationState(RotationState.ALL)
                .abilities(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS, PartAbility.EXPORT_ITEMS, PartAbility.EXPORT_FLUIDS)
                .overlayTieredHullRenderer("me_pattern_buffer")
                .tooltips(
                        Component.translatable("block.gtceu.pattern_buffer.desc.0"),
                        Component.translatable("gtceu.machine.me_wildcard_pattern_buffer.desc.0"),
                        Component.translatable("gtlcore.machine.me_stocking_pattern_buffer.desc.0"),
                        Component.translatable("gtlcore.machine.me_stocking_pattern_buffer.desc.1"),
                        Component.translatable("gtceu.machine.me_pattern_buffer.desc.3"),
                        Component.translatable("gtceu.machine.me_pattern_buffer.desc.5"),
                        Component.translatable("block.gtceu.pattern_buffer.desc.2"),
                        Component.translatable("gtceu.universal.enabled"))
                .tooltipBuilder(GTLMachines.GTL_ADD)
                .register();
    }

    public static MachineDefinition getWildcardPatternBufferDefinition() {
        return ME_WILDCARD_PATTERN_BUFFER;
    }

    public static MachineDefinition getStockingWildcardPatternBufferDefinition() {
        return ME_STOCKING_WILDCARD_PATTERN_BUFFER;
    }

    /**
     * Check if the given ItemStack is a wildcard pattern.
     *
     * @param stack the ItemStack to check
     * @return true if the stack is a wildcard pattern
     */
    static boolean isWildcardPattern(ItemStack stack) {
        return !stack.isEmpty() && stack.is(WildcardItems.WILDCARD_PATTERN.asItem());
    }

    /**
     * Expand a wildcard pattern into all its possible pattern details.
     *
     * @param wildcardStack the wildcard pattern ItemStack
     * @param level         the level for pattern decoding
     * @return list of expanded pattern details, or empty list if not a valid wildcard pattern
     */
    static List<IPatternDetails> expandPatterns(ItemStack wildcardStack, Level level) {
        if (!isWildcardPattern(wildcardStack) || level == null) {
            return List.of();
        }

        List<IPatternDetails> result = new ArrayList<>();
        WildcardPatternLogic.decodePatterns(wildcardStack, level).forEach(result::add);
        return result;
    }
}
