package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

/** Searches one loaded column strictly within the requested population layer. */
public final class FillagerSpawnSearch {
    private FillagerSpawnSearch() { }

    public static OptionalInt findY(int layer, int cellSize, int minBuildHeight, int maxBuildHeight,
            IntPredicate safeAtY) {
        if (layer < 0 || cellSize <= 0) {
            return OptionalInt.empty();
        }
        long lower = (long) minBuildHeight + (long) layer * cellSize;
        long upper = Math.min((long) maxBuildHeight - 2, lower + cellSize - 2);
        if (lower < minBuildHeight || lower > upper) {
            return OptionalInt.empty();
        }
        for (int y = (int) upper; y >= lower; y--) {
            if (safeAtY.test(y)) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }
}
