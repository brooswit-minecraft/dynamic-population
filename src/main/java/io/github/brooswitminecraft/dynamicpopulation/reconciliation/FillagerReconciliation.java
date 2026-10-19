package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import java.util.function.BiConsumer;
import java.util.function.Predicate;
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
     *
     * <p>{@code readable} is the same exclusion for a different reason: a cell inside the world whose anchor
     * chunk is not loaded (or whose stored data is preserved-as-corrupt) is not a real zero either -- it is
     * "unknown right now", not "known to be empty". {@code read} is never even called for a cell this
     * predicate rejects, matching the layer clamp, which also skips the read entirely rather than reading
     * and discarding.
     */
    public static int sampledTarget(CellKey centre, int horizontalRadius, int verticalRadius, int minLayer,
            int maxLayer, Predicate<CellKey> readable, ToIntFunction<CellKey> read) {
        long total = 0;
        int count = 0;
        for (int x = -horizontalRadius; x <= horizontalRadius; x++) {
            for (int z = -horizontalRadius; z <= horizontalRadius; z++) {
                for (int y = -verticalRadius; y <= verticalRadius; y++) {
                    CellKey cell = centre.offset(x, y, z);
                    if (cell.layer() < minLayer || cell.layer() > maxLayer || !readable.test(cell)) {
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
            Predicate<CellKey> readable, ToIntFunction<CellKey> read) {
        int target = sampledTarget(centre, params.horizontalRadius(), params.verticalRadius(), minLayer, maxLayer,
            readable, read);
        int desired = (int) Math.round(target * params.fillagersPerFullCell() / (double) Propagation.CAPACITY);
        int missing = Math.max(0, desired - current);
        int excess = Math.max(0, current - desired);
        int spawn = Math.min(missing, Math.min(params.refillLimit(), params.spawnCap()));
        int despawn = Math.min(excess, params.despawnCap());
        return new Decision(target, desired, spawn, despawn);
    }

    /**
     * AC6 (MINECRAFT-30, village activity feedback): a local target adjustment, clamped to the field's own
     * {@code 0..CAPACITY} range the same way {@code LevelField.set} clamps it, so a test driven purely
     * through maps (no real {@code LevelField}) still sees the same bound a real server would. A positive
     * {@code delta} raises the target (trading, breeding, building, farming, golem creation); a negative one
     * lowers it (a non-unsafe-location Fillager death, or destruction).
     */
    public static int targetAfterAdjustment(int localTarget, int delta) {
        return Math.max(0, Math.min(Propagation.CAPACITY, localTarget + delta));
    }

    /**
     * The shared event-to-storage wiring behind every AC6 village-activity hook, and (MINECRAFT-425) behind
     * {@code PopulationDeathDecrement#apply} too: read-then-write exactly the one local cell, never a
     * neighbour.
     */
    public static void adjustLocalTarget(CellKey localCell, int delta, ToIntFunction<CellKey> read,
            BiConsumer<CellKey, Integer> write) {
        write.accept(localCell, targetAfterAdjustment(read.applyAsInt(localCell), delta));
    }

    /**
     * Destruction (AC6): every distinct cell an explosion touched is decremented independently -- never
     * double-counted, never spread to a cell the explosion did not actually touch.
     */
    public static void applyDestruction(java.util.Set<CellKey> touchedCells, int decrement,
            ToIntFunction<CellKey> read, BiConsumer<CellKey, Integer> write) {
        for (CellKey cell : touchedCells) {
            adjustLocalTarget(cell, -decrement, read, write);
        }
    }

    /**
     * AC6 raise, PLUS the post-write registration a raise needs that a lower never does: a cell with no
     * existing Fillagers and outside King-seeding range is otherwise invisible to both propagation (which
     * only steps the active set) and reconciliation (active set plus cells containing existing Fillagers),
     * so a raise that actually makes the stored target positive must also enroll the cell in the active
     * set -- exactly as {@code KingSimulation.seedAll} already does after King seeding. {@code markActive}
     * is called only when the write resulted in a positive value, never for a raise that a later lower
     * (same event, or a config of 0) leaves at/below zero.
     */
    public static void raiseLocalTargetAndActivate(CellKey localCell, int increment, ToIntFunction<CellKey> read,
            BiConsumer<CellKey, Integer> write, java.util.function.Consumer<CellKey> markActive) {
        adjustLocalTarget(localCell, increment, read, write);
        if (read.applyAsInt(localCell) > 0) {
            markActive.accept(localCell);
        }
    }
}
