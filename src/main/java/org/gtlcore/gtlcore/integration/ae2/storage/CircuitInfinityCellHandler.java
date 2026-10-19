/*
 * Derived from More AE2 Additions (mae2a) - com.easterfg.mae2a.common.cells.InfiniteDyesCellHandler.
 * Copyright (C) EasterFG and contributors.
 * Licensed under the GNU General Public License v3.0 (full text: META-INF/licenses/LICENSE-GPL-3.0.txt).
 * Modified by the GTLCore contributors.
 */
package org.gtlcore.gtlcore.integration.ae2.storage;

import org.gtlcore.gtlcore.integration.ae2.CircuitInfinityCell;

import net.minecraft.world.item.ItemStack;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;

public class CircuitInfinityCellHandler implements ICellHandler {

    public static final CircuitInfinityCellHandler INSTANCE = new CircuitInfinityCellHandler();

    @Override
    public boolean isCell(ItemStack is) {
        return is.getItem() instanceof CircuitInfinityCell;
    }

    @Override
    public StorageCell getCellInventory(ItemStack is, ISaveProvider container) {
        if (!isCell(is)) return null;
        return new CircuitInfinityCellInventory();
    }
}
