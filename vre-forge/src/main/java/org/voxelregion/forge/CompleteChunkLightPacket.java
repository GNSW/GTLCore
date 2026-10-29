/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;

/** A replacement of an already tracked chunk must also replace light layers native storage dropped. */
public final class CompleteChunkLightPacket {
    public static void fillMissing(ClientboundLightUpdatePacketData packet, LevelLightEngine engine,
            ChunkPos position, boolean hasSky) {
        var all = new java.util.BitSet(); all.set(0, engine.getLightSectionCount());
        fillMissing(packet, engine, position, hasSky, all, all);
    }
    public static void fillMissing(ClientboundLightUpdatePacketData packet, LevelLightEngine engine,
            ChunkPos position, boolean hasSky, java.util.BitSet requiredSky, java.util.BitSet requiredBlock) {
        int count = engine.getLightSectionCount();
        for (int i = 0; i < count; i++)
            if (requiredBlock.get(i) && !packet.getBlockYMask().get(i) && !packet.getEmptyBlockYMask().get(i))
                packet.getEmptyBlockYMask().set(i); // An absent native block-light layer resolves to zero.

        var present = packet.getSkyYMask(); var empty = packet.getEmptySkyYMask();
        var old = packet.getSkyUpdates().iterator();
        var complete = new ArrayList<byte[]>();
        DataLayer inherited = null;
        for (int i = 0; i < count; i++) {
            if (present.get(i)) { complete.add(old.next()); inherited = null; }
            else if (empty.get(i)) inherited = null;
            else if (!requiredSky.get(i)) inherited = null;
            else {
                // A native missing sky layer repeats the first row of the next
                // stored layer, or the dimension's upper default. Consecutive
                // missing layers share that row; inspect 256 columns once, not
                // every voxel of every empty section. Never assume skylight 15.
                if (inherited == null) {
                    inherited = new DataLayer();
                    if (hasSky) {
                        var cursor = new BlockPos.MutableBlockPos();
                        int y = (engine.getMinLightSection() + i) * 16;
                        var sky = engine.getLayerListener(LightLayer.SKY);
                        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            int light = sky.getLightValue(cursor.set(position.getMinBlockX() + x, y, position.getMinBlockZ() + z));
                            if (light != 0) for (int dy = 0; dy < 16; dy++) inherited.set(x, dy, z, light);
                        }
                    }
                }
                if (inherited.isEmpty()) empty.set(i);
                else { present.set(i); complete.add(inherited.getData()); }
            }
        }
        if (old.hasNext()) throw new IllegalStateException("Native sky mask and payload disagree");
        packet.getSkyUpdates().clear(); packet.getSkyUpdates().addAll(complete);
    }
    private CompleteChunkLightPacket() { }
}
