package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import java.util.function.BiConsumer;
import java.util.function.ToIntFunction;

import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;
import io.github.brooswitminecraft.dynamicpopulation.propagation.Propagation;

/** Pure decision logic for sampled Fillager reconciliation. Sampling is read-only by design. */
public final class FillagerReconciliation {
    private FillagerReconciliation() { }

    /** An overlapping entity box is only a candidate: ownership is the entity's block-position cell. */
    public static <T> java.util.List<T> residentsInCell(java.util.List<T> candidates, CellKey cell,
            java.util.function.Function<T, CellKey> owner) {
        return candidates.stream().filter(candidate -> cell.equals(owner.apply(candidate))).toList();
    }

    public record Params(int horizontalRadius, int verticalRadius, int fillagersPerFullCell, int refillLimit,
            int spawnCap, int despawnCap) { }

    public record Decision(int sampledTarget, int desired, int spawn, int despawn) { }

    /**
     * Returns an arithmetic sample of readable cells. This method never receives, and cannot mutate, storage.
     *
     * <p>{@code minLayer}/{@code maxLayer} (inclusive) are the world's own valid layer range, never a config
     * value: a layer outside it does not exist, so it is excluded from both the sum and the denominator
     * rather than contributing a 0 as if it were a real, merely-empty cell. Without this clamp, a window that
     * crosses a world edge would undercount every cell inside the window too (e.g. a uniform field would
     * read as lower density near the top/bottom of the world purely because the window is clipped there).
     */
    public static int sampledTarget(CellKey centre, int horizontalRadius, int verticalRadius, int minLayer,
            int maxLayer, ToIntFunction<CellKey> read) {
        long total = 0;
        int count = 0;
        for (int x = -horizontalRadius; x <= horizontalRadius; x++) {
            for (int z = -horizontalRadius; z <= horizontalRadius; z++) {
                for (int y = -verticalRadius; y <= verticalRadius; y++) {
                    CellKey cell = centre.offset(x, y, z);
                    if (cell.layer() < minLayer || cell.layer() > maxLayer) {
                        continue;
                    }
                    total += Math.max(0, Math.min(Propagation.CAPACITY, read.applyAsInt(cell)));
                    count++;
                }
            }
        }
        return count == 0 ? 0 : (int) (total / count);
    }

    public static Decision decide(CellKey centre, int current, Params params, int minLayer, int maxLayer,
            ToIntFunction<CellKey> read) {
        int target = sampledTarget(centre, params.horizontalRadius(), params.verticalRadius(), minLayer, maxLayer, read);
        int desired = (int) Math.round(target * params.fillagersPerFullCell() / (double) Propagation.CAPACITY);
        int missing = Math.max(0, desired - current);
        int excess = Math.max(0, current - desired);
        int spawn = Math.min(missing, Math.min(params.refillLimit(), params.spawnCap()));
        int despawn = Math.min(excess, params.despawnCap());
        return new Decision(target, desired, spawn, despawn);
    }

    /** The death feedback is deliberately a local scalar operation, never a sampled-window operation. */
    public static int unsafeDeathTargetAfter(int localTarget, int decrement) {
        return Math.max(0, localTarget - decrement);
    }

    /**
     * The full event-to-storage wiring for AC9, as a pure function: given whether a death was classified
     * unsafe, read-then-write exactly the one local cell, never a neighbour. {@code unsafe == false} (an
     * ordinary despawn, promotion, or any non-hazard death) must leave storage completely untouched -- no
     * read, no write -- not just an unchanged value, since this is also what the event handler calls for
     * every Fillager death, unsafe or not.
     */
    public static void applyUnsafeDeathIfUnsafe(boolean unsafe, CellKey localCell, ToIntFunction<CellKey> read,
            BiConsumer<CellKey, Integer> write, int decrement) {
        if (!unsafe) {
            return;
        }
        write.accept(localCell, unsafeDeathTargetAfter(read.applyAsInt(localCell), decrement));
    }
}
