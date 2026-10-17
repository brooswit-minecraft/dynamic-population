package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

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

    /** Returns an arithmetic sample of readable cells. This method never receives, and cannot mutate, storage. */
    public static int sampledTarget(CellKey centre, int horizontalRadius, int verticalRadius, ToIntFunction<CellKey> read) {
        long total = 0;
        int count = 0;
        for (int x = -horizontalRadius; x <= horizontalRadius; x++) {
            for (int z = -horizontalRadius; z <= horizontalRadius; z++) {
                for (int y = -verticalRadius; y <= verticalRadius; y++) {
                    total += Math.max(0, Math.min(Propagation.CAPACITY, read.applyAsInt(centre.offset(x, y, z))));
                    count++;
                }
            }
        }
        return count == 0 ? 0 : (int) (total / count);
    }

    public static Decision decide(CellKey centre, int current, Params params, ToIntFunction<CellKey> read) {
        int target = sampledTarget(centre, params.horizontalRadius(), params.verticalRadius(), read);
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
}
