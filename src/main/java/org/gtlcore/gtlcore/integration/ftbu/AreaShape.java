package org.gtlcore.gtlcore.integration.ftbu;

import org.gtlcore.gtlcore.config.ConfigHolder;

import net.minecraft.core.BlockPos;

import dev.ftb.mods.ftbultimine.EntityDistanceComparator;
import dev.ftb.mods.ftbultimine.shape.BlockMatcher;
import dev.ftb.mods.ftbultimine.shape.Shape;
import dev.ftb.mods.ftbultimine.shape.ShapeContext;
import dev.ftb.mods.ftbultimine.shape.ShapeRegistry;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.antlr.v4.runtime.misc.OrderedHashSet;

import java.util.*;

public class AreaShape implements Shape {

    public static void register() {
        ShapeRegistry.register(new AreaShape());
    }

    private static final class Neighbor {

        private final ArrayList<BlockPos> neighbors = new ArrayList<>();
        private int range;

        private Neighbor(int r) {
            update(r);
        }

        private void update(int r) {
            range = r;
            neighbors.clear();
            final var length = (range << 1) + 1;
            neighbors.ensureCapacity(length * length * length - 1);
            for (int x = -range; x <= range; ++x) {
                for (int y = -range; y <= range; ++y) {
                    for (int z = -range; z <= range; ++z) {
                        if (x != 0 || y != 0 || z != 0) {
                            neighbors.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
        }
    }

    private static final AreaShape.Neighbor NEIGHBOR_POSITIONS = new AreaShape.Neighbor(0);

    @Override
    public String getName() {
        return "area";
    }

    @Override
    public List<BlockPos> getBlocks(ShapeContext context) {
        if (NEIGHBOR_POSITIONS.range != ConfigHolder.INSTANCE.gameplay.ftbUltimineRange) {
            NEIGHBOR_POSITIONS.update(ConfigHolder.INSTANCE.gameplay.ftbUltimineRange);
        }

        Set<BlockPos> known = new OrderedHashSet<>();
        walk(context, known);

        List<BlockPos> list = new ArrayList<>(known);
        list.sort(new EntityDistanceComparator(context.pos()));

        if (list.size() > context.maxBlocks()) {
            list.subList(context.maxBlocks(), list.size()).clear();
        }

        return list;
    }

    private static void walk(ShapeContext context, Set<BlockPos> known) {
        LongOpenHashSet traversed = new LongOpenHashSet();
        Deque<BlockPos> openSet = new ArrayDeque<>();
        openSet.add(context.pos());
        traversed.add(context.pos().asLong());

        while (!openSet.isEmpty()) {
            BlockPos ptr = openSet.pop();

            if (context.check(ptr) && known.add(ptr)) {
                if (known.size() >= context.maxBlocks()) {
                    return;
                }

                for (BlockPos side : NEIGHBOR_POSITIONS.neighbors) {
                    BlockPos offset = ptr.offset(side);

                    if (traversed.add(offset.asLong())) {
                        openSet.add(offset);
                    }
                }
            }
        }
    }

    @Override
    public BlockMatcher getTagMatcher() {
        return BlockMatcher.TAGS_MATCH_SHAPELESS;
    }
}
