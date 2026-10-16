/*
 * Derived from More AE2 Additions (mae2a) - com.easterfg.mae2a.common.cells.InfiniteDyesCellInventory.
 * Copyright (C) EasterFG and contributors.
 * Licensed under the GNU General Public License v3.0 (full text: META-INF/licenses/LICENSE-GPL-3.0.txt).
 * Modified by the GTLCore contributors.
 */
package org.gtlcore.gtlcore.integration.ae2.storage;

import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.network.chat.Component;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;

import java.util.LinkedHashSet;
import java.util.Set;

public class CircuitInfinityCellInventory implements StorageCell {

    private static final int CIRCUIT_VARIANTS = 33;
    private static final long AMOUNT = Long.MAX_VALUE;
    private static final Set<AEKey> CONFIGURED = new LinkedHashSet<>();

    static {
        for (int i = 0; i < CIRCUIT_VARIANTS; i++) {
            CONFIGURED.add(AEItemKey.of(IntCircuitBehaviour.stack(i)));
        }
    }

    @Override
    public CellState getStatus() {
        return CellState.TYPES_FULL;
    }

    @Override
    public double getIdleDrain() {
        return 1.0;
    }

    @Override
    public void persist() {}

    @Override
    public Component getDescription() {
        return Component.translatable("item.gtlcore.circuit_infinity_cell");
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (AEKey key : CONFIGURED) {
            out.add(key, AMOUNT);
        }
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        return CONFIGURED.contains(what) ? amount : 0L;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        return CONFIGURED.contains(what) ? amount : 0L;
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        return CONFIGURED.contains(what);
    }
}
