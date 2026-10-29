/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;
import org.voxelregion.search.ColumnNearestIndex;

/** Reuses fixed climate distances across vertical biome samples without reducing sampling resolution. */
final class CompiledBiomeColumns {
    private final MultiNoiseBiomeSource source;
    private final ColumnNearestIndex<Holder<Biome>> index;
    private CompiledBiomeColumns(MultiNoiseBiomeSource source, ColumnNearestIndex<Holder<Biome>> index) {
        this.source = source; this.index = index;
    }
    static CompiledBiomeColumns create(BiomeSource source) {
        if (source.getClass() != MultiNoiseBiomeSource.class || !(source instanceof NativeAccess.ClimateParameters access)) return null;
        var entries = new ArrayList<ColumnNearestIndex.Entry<Holder<Biome>>>();
        for (var value : access.vre$climateParameters().values()) {
            var p = value.getFirst();
            var axes = List.of(p.temperature(), p.humidity(), p.continentalness(), p.erosion(), p.weirdness(), p.depth());
            long[] lo = new long[6], hi = new long[6];
            for (int d = 0; d < 6; d++) { lo[d] = axes.get(d).min(); hi[d] = axes.get(d).max(); }
            entries.add(new ColumnNearestIndex.Entry<>(lo, hi, p.offset(), value.getSecond()));
        }
        return new CompiledBiomeColumns((MultiNoiseBiomeSource)source, new ColumnNearestIndex<>(entries));
    }
    BiomeResolver resolver() {
        return new BiomeResolver() {
            private final Fixed[] keys = new Fixed[16];
            private final ColumnNearestIndex.Query<Holder<Biome>>[] queries = new ColumnNearestIndex.Query[16];
            private Climate.TargetPoint previous;
            private boolean nativeCurrent;
            @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
                var point = sampler.sample(x, y, z);
                var fixed = new Fixed(point.temperature(), point.humidity(), point.continentalness(), point.erosion(), point.weirdness());
                int slot = (x & 3) | ((z & 3) << 2);
                Holder<Biome> result;
                try {
                    if (!fixed.equals(keys[slot])) {
                        queries[slot] = index.column(fixed.temperature, fixed.humidity, fixed.continentalness, fixed.erosion, fixed.weirdness);
                        keys[slot] = fixed;
                    }
                    result = queries[slot].unique(point.depth());
                } catch (ArithmeticException extremeCustomClimate) { result = null; }
                if (result == null) {
                    // Native search owns ambiguous minima. Refresh its last-result hint with the
                    // preceding sample rather than leaving a stale hint from another image/column.
                    if (!nativeCurrent && previous != null) source.getNoiseBiome(previous);
                    result = source.getNoiseBiome(point); nativeCurrent = true;
                } else nativeCurrent = false;
                previous = point;
                return result;
            }
        };
    }
    private record Fixed(long temperature, long humidity, long continentalness, long erosion, long weirdness) { }
}
