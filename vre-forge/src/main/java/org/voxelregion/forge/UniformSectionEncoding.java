/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.PalettedContainer;

/** Cache actual native codec results for proven uniform palettes; every caller owns a detached NBT copy. */
public final class UniformSectionEncoding {
    private static final Map<Object, Tag> templates = new IdentityHashMap<>();
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static DataResult encode(Codec codec, DynamicOps ops, Object value) {
        if (!NativeSaveTracker.capturing() || ops != NbtOps.INSTANCE || !(value instanceof PalettedContainer<?> container))
            return codec.encodeStart(ops, value);
        if (container.getClass() != PalettedContainer.class && container.getClass() != RegionBlockStateContainer.class)
            return codec.encodeStart(ops, value);
        container.acquire();
        Object state;
        boolean uniform;
        try {
            state = container.get(0, 0, 0);
            uniform = !container.maybeHas(other -> other != state);
        } finally { container.release(); }
        if (!uniform) return codec.encodeStart(ops, value);
        Tag template;
        synchronized (templates) { template = templates.get(state); }
        if (template != null) return DataResult.success(template.copy());
        DataResult result = codec.encodeStart(ops, value);
        var encoded = result.result();
        if (encoded.isPresent() && encoded.get() instanceof Tag tag) synchronized (templates) {
            if (templates.size() >= 4096) templates.clear();
            templates.put(state, tag.copy());
        }
        return result;
    }
    public static void clear() { synchronized (templates) { templates.clear(); } }
    private UniformSectionEncoding() { }
}
