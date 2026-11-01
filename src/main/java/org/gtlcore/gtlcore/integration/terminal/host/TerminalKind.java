package org.gtlcore.gtlcore.integration.terminal.host;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public enum TerminalKind {

    NONE(0, ""),
    BASIC(1, "gtceu:terminal"),
    ADVANCED(2, "gtmthings:advanced_terminal"),
    ULTIMATE(3, "gtlcore:ultimate_terminal");

    private final int rank;
    private final String itemId;

    TerminalKind(int rank, String itemId) {
        this.rank = rank;
        this.itemId = itemId;
    }

    public int rank() {
        return rank;
    }

    public boolean canBuild() {
        return this != NONE;
    }

    public boolean canDismantle() {
        return this == ULTIMATE;
    }

    public boolean hasBuildOptions() {
        return this == ADVANCED || this == ULTIMATE;
    }

    public static TerminalKind from(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return NONE;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        for (TerminalKind kind : values()) {
            if (kind != NONE && kind.itemId.equals(id.toString())) {
                return kind;
            }
        }
        return NONE;
    }
}
