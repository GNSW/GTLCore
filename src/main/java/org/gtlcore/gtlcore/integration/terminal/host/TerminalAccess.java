package org.gtlcore.gtlcore.integration.terminal.host;

import org.gtlcore.gtlcore.integration.ae2.wireless.CuriosCompat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class TerminalAccess {

    private TerminalAccess() {}

    public record EquippedTerminal(ItemStack stack, TerminalKind kind) {

        public static final EquippedTerminal NONE = new EquippedTerminal(ItemStack.EMPTY, TerminalKind.NONE);

        public boolean usable() {
            return kind != TerminalKind.NONE && !stack.isEmpty();
        }
    }

    public static EquippedTerminal find(Player player) {
        EquippedTerminal found = EquippedTerminal.NONE;
        for (CuriosCompat.CurioSlot slot : CuriosCompat.getCurioSlots(player)) {
            ItemStack stack = slot.stack();
            TerminalKind kind = TerminalKind.from(stack);
            if (kind.rank() > found.kind().rank()) {
                found = new EquippedTerminal(stack, kind);
            }
        }
        return found;
    }
}
