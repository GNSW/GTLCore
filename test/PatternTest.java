import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline sanity check for the pattern-cell walk.  This is not shipped in the mod jar.
 *
 * <p>It builds a small GregTech pattern, calls the same private world-offset method the mod calls
 * through its Mixin invoker, and prints the offsets.  A correct walk produces one unique offset per
 * non-controller cell and a compact 3x3x3 cube.
 */
public final class PatternTest {

    public static void main(String[] args) throws Exception {
        BlockPattern pattern = FactoryBlockPattern.start()
                .aisle("XXX", "XXX", "XXX")
                .aisle("XXX", "X#X", "XXX")
                .aisle("XXX", "XSX", "XXX")
                .where('X', Predicates.any())
                .where('#', Predicates.air())
                .where('S', Predicates.controller(Predicates.any()))
                .build();

        Field matchesField = BlockPattern.class.getDeclaredField("blockMatches");
        matchesField.setAccessible(true);
        TraceabilityPredicate[][][] matches = (TraceabilityPredicate[][][]) matchesField.get(pattern);
        Field centerField = BlockPattern.class.getDeclaredField("centerOffset");
        centerField.setAccessible(true);
        int[] center = (int[]) centerField.get(pattern);
        Method offsetMethod = BlockPattern.class.getDeclaredMethod("setActualRelativeOffset",
                int.class, int.class, int.class, Direction.class, Direction.class, boolean.class);
        offsetMethod.setAccessible(true);

        System.out.println("centerOffset=" + java.util.Arrays.toString(center)
                + " repetitions=" + java.util.Arrays.deepToString(pattern.aisleRepetitions)
                + " size=" + matches.length + "x" + matches[0].length + "x" + matches[0][0].length);

        List<BlockPos> cells = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int fingerLength = matches.length;
        int thumbLength = matches[0].length;
        int palmLength = matches[0][0].length;
        int minZ = -center[4];
        boolean findFirstAisle = false;
        int z = minZ++;
        for (int c = 0; c < fingerLength; c++) {
            int repetition = 0;
            while (findFirstAisle ? repetition < pattern.aisleRepetitions[c][1] : z <= -center[3]) {
                for (int b = 0; b < thumbLength; b++) {
                    int y = -center[1] + b;
                    for (int a = 0; a < palmLength; a++) {
                        int x = -center[0] + a;
                        TraceabilityPredicate predicate = matches[c][b][a];
                        if (predicate == null || predicate.isController) {
                            continue;
                        }
                        BlockPos pos = (BlockPos) offsetMethod.invoke(pattern, x, y, z,
                                Direction.NORTH, Direction.NORTH, false);
                        if (seen.add(pos.m_121878_())) {
                            cells.add(pos);
                        }
                    }
                }
                findFirstAisle = true;
                z++;
                repetition++;
            }
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ2 = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : cells) {
            minX = Math.min(minX, pos.m_123341_());
            minY = Math.min(minY, pos.m_123342_());
            minZ2 = Math.min(minZ2, pos.m_123343_());
            maxX = Math.max(maxX, pos.m_123341_());
            maxY = Math.max(maxY, pos.m_123342_());
            maxZ = Math.max(maxZ, pos.m_123343_());
        }
        System.out.println("cells=" + cells.size() + " bounds="
                + minX + ".." + maxX + ", " + minY + ".." + maxY + ", " + minZ2 + ".." + maxZ);
        System.out.println("sample=" + cells.subList(0, Math.min(8, cells.size())));
        if (cells.size() != 26) {
            throw new AssertionError("expected 26 non-controller cells, got " + cells.size());
        }
        if (maxX - minX != 2 || maxY - minY != 2 || maxZ - minZ2 != 2) {
            throw new AssertionError("expected a 3x3x3 cell cube");
        }
    }
}

