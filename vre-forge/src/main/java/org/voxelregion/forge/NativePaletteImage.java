/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.IdMap;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

/** Detached native index storage. Palette remapping does not re-encode the 4096 voxel indices. */
public final class NativePaletteImage {
    private static final Access ACCESS = access();
    public final BitStorage indices;
    public final List<BlockState> states;
    private final Object configuration;
    private NativePaletteImage(BitStorage indices, List<BlockState> states, Object configuration) {
        this.indices = indices; this.states = states; this.configuration = configuration;
    }

    @SuppressWarnings("unchecked")
    public static NativePaletteImage capture(PalettedContainer<BlockState> container) {
        if (ACCESS == null || container.getClass() != PalettedContainer.class && container.getClass() != RegionBlockStateContainer.class)
            return null;
        container.acquire();
        try {
            Object data = ACCESS.data.invoke(container);
            var storage = (BitStorage) ACCESS.storage.invoke(data);
            if (storage.getSize() != 4096 || storage.getBits() > 8) return null; // Global palettes keep native handling.
            var palette = (Palette<BlockState>) ACCESS.palette.invoke(data);
            var values = new ArrayList<BlockState>(palette.getSize());
            for (int i = 0; i < palette.getSize(); i++) values.add(palette.valueFor(i));
            return new NativePaletteImage(storage.copy(), List.copyOf(values), ACCESS.configuration.invoke(data));
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError error) throw error;
            return null;
        } finally { container.release(); }
    }

    @SuppressWarnings("unchecked")
    public PalettedContainer<BlockState> remap(List<BlockState> replacement) {
        if (replacement.size() != states.size()) throw new IllegalArgumentException("Palette indices must stay stable");
        try {
            return (PalettedContainer<BlockState>) ACCESS.constructor.invoke(Block.BLOCK_STATE_REGISTRY,
                    PalettedContainer.Strategy.SECTION_STATES, configuration, indices.copy(), replacement);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError error) throw error;
            return null;
        }
    }

    /** Resolve by field types, not development/SRG names. Missing or replaced layouts use normal preparation. */
    private static Access access() {
        try {
            var lookup = MethodHandles.lookup();
            for (var dataField : PalettedContainer.class.getDeclaredFields()) {
                if (Modifier.isStatic(dataField.getModifiers())) continue;
                var type = dataField.getType();
                java.lang.reflect.Field storage = null, palette = null, configuration = null;
                for (var field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType() == BitStorage.class) storage = field;
                    else if (field.getType() == Palette.class) palette = field;
                    else if (field.getType().getEnclosingClass() == PalettedContainer.class) configuration = field;
                }
                if (storage == null || palette == null || configuration == null) continue;
                for (var field : new java.lang.reflect.Field[]{dataField, storage, palette, configuration}) field.setAccessible(true);
                var constructor = PalettedContainer.class.getDeclaredConstructor(IdMap.class, PalettedContainer.Strategy.class,
                        configuration.getType(), BitStorage.class, List.class);
                constructor.setAccessible(true);
                return new Access(lookup.unreflectGetter(dataField), lookup.unreflectGetter(storage), lookup.unreflectGetter(palette),
                        lookup.unreflectGetter(configuration), lookup.unreflectConstructor(constructor));
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
        return null;
    }
    private record Access(MethodHandle data, MethodHandle storage, MethodHandle palette, MethodHandle configuration, MethodHandle constructor) { }
}
