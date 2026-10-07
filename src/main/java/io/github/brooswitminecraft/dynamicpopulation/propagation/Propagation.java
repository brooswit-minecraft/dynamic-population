package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Population propagation (MINECRAFT-27): conservative transport between the six face neighbours of a cell.
 * Pure Java, no Minecraft classes.
 *
 * <p>Each step, every populated cell sends a fraction of the value it had at the start of the step to its
 * traversable neighbours in ONE weighted pass: each neighbour's share is proportional to
 * {@code directionWeight * freeCapacity}, so down is weighted heaviest, sides less, up least, and every
 * direction with room still receives something. There is no fill-down-first stage. Values only ever move
 * between cells (the total is conserved); a cell never averages with, or aggregates, its neighbours. A cell
 * sends at most what it had at the start of the step, so a unit moves one hop per step.
 *
 * <p>Conservative transport was chosen over locally generated targets: the original worry (a permanent
 * underground sink) came from an amplified underground hostile-spawn mechanism that dynamic-atmosphere no
 * longer has, so a downward gradient is a tuning problem for the direction weights, not a structural drain.
 * See the tripwire test in {@code PropagationTest}.
 */
public final class Propagation {
    /** The most population a cell can hold, in parts per thousand. */
    public static final int CAPACITY = 1000;

    private static final int[][] DOWN = {{0, -1, 0}};
    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] UP = {{0, 1, 0}};

    private Propagation() { }

    /** Relative direction weights (all strictly positive) and the share of a cell's value that leaves per step. */
    public record Params(double down, double side, double up, double fraction) {
        public Params {
            if (!(down > 0) || !(side > 0) || !(up > 0)) {
                throw new IllegalArgumentException("direction weights must be > 0: " + down + ", " + side + ", " + up);
            }
            if (!(fraction > 0) || fraction > 1) {
                throw new IllegalArgumentException("fraction must be in (0, 1]: " + fraction);
            }
        }
    }

    /** The world as propagation sees it. */
    public interface Field {
        /** Current value of a cell, 0..{@link #CAPACITY}. */
        int get(CellKey cell);

        void set(CellKey cell, int value);

        /** Whether population may enter this cell: it exists, is loaded, and has an open or liquid block. */
        boolean traversable(CellKey cell);
    }

    /** A cell is traversable if it holds at least one open or liquid block (liquid counts, unlike gas). */
    public static boolean traversable(boolean hasOpenBlock, boolean hasLiquidBlock) {
        return hasOpenBlock || hasLiquidBlock;
    }

    /**
     * Run one step over the given populated cells.
     *
     * @return every cell that holds population after the step (the new active set)
     */
    public static Set<CellKey> step(Field field, Iterable<CellKey> sources, Params params, RandomGenerator random) {
        Map<CellKey, Integer> start = new HashMap<>();
        for (CellKey cell : sources) {
            int value = field.get(cell);
            if (value > 0) {
                start.put(cell, value);
            }
        }
        List<CellKey> order = new ArrayList<>(start.keySet());
        Collections.shuffle(order, new java.util.Random(random.nextLong()));
        Set<CellKey> active = new HashSet<>(start.keySet());
        for (CellKey cell : order) {
            int out = stochasticRound(start.get(cell) * params.fraction(), random);
            out = Math.min(out, field.get(cell));
            if (out <= 0) {
                continue;
            }
            List<CellKey> targets = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            List<Integer> caps = new ArrayList<>();
            collect(field, cell, DOWN, params.down(), targets, weights, caps);
            collect(field, cell, SIDES, params.side(), targets, weights, caps);
            collect(field, cell, UP, params.up(), targets, weights, caps);
            if (targets.isEmpty()) {
                continue;
            }
            int[] shares = allocate(out, weights, caps, random);
            for (int i = 0; i < shares.length; i++) {
                if (shares[i] > 0) {
                    CellKey target = targets.get(i);
                    field.set(target, field.get(target) + shares[i]);
                    field.set(cell, field.get(cell) - shares[i]);
                    active.add(target);
                }
            }
        }
        active.removeIf(cell -> field.get(cell) <= 0);
        return active;
    }

    private static void collect(Field field, CellKey cell, int[][] directions, double directionWeight,
            List<CellKey> targets, List<Double> weights, List<Integer> caps) {
        for (int[] d : directions) {
            CellKey neighbour = cell.offset(d[0], d[1], d[2]);
            if (!field.traversable(neighbour)) {
                continue;
            }
            int capacity = CAPACITY - field.get(neighbour);
            if (capacity <= 0) {
                continue;
            }
            targets.add(neighbour);
            weights.add(directionWeight * capacity);
            caps.add(capacity);
        }
    }

    /**
     * Split {@code amount} whole units over the targets in proportion to their weights without exceeding any
     * target's capacity. The shares sum to exactly the amount (or to the total capacity, if that is smaller).
     * Leftover units after rounding down go to targets at random, weighted by their fractional remainder, so
     * a small direction weight is never starved by rounding.
     */
    static int[] allocate(int amount, List<Double> weights, List<Integer> caps, RandomGenerator random) {
        int n = weights.size();
        int[] share = new int[n];
        boolean[] open = new boolean[n];
        java.util.Arrays.fill(open, true);
        int remaining = amount;
        double[] fraction = new double[n];
        while (remaining > 0) {
            double totalWeight = 0;
            for (int i = 0; i < n; i++) {
                if (open[i]) {
                    totalWeight += weights.get(i);
                }
            }
            if (totalWeight <= 0) {
                break;
            }
            boolean clamped = false;
            for (int i = 0; i < n; i++) {
                if (open[i] && remaining * weights.get(i) / totalWeight > caps.get(i) - share[i]) {
                    remaining -= caps.get(i) - share[i];
                    share[i] = caps.get(i);
                    open[i] = false;
                    clamped = true;
                }
            }
            if (clamped) {
                continue;
            }
            int assigned = 0;
            for (int i = 0; i < n; i++) {
                if (open[i]) {
                    double quota = remaining * weights.get(i) / totalWeight;
                    int whole = (int) Math.floor(quota);
                    share[i] += whole;
                    fraction[i] = quota - whole;
                    assigned += whole;
                }
            }
            int leftover = remaining - assigned;
            while (leftover > 0) {
                double total = 0;
                for (int i = 0; i < n; i++) {
                    if (open[i] && share[i] < caps.get(i)) {
                        total += fraction[i] + 1e-9;
                    }
                }
                if (total <= 0) {
                    break;
                }
                double pick = random.nextDouble() * total;
                for (int i = 0; i < n; i++) {
                    if (open[i] && share[i] < caps.get(i)) {
                        pick -= fraction[i] + 1e-9;
                        if (pick <= 0) {
                            share[i]++;
                            fraction[i] = 0;
                            leftover--;
                            break;
                        }
                    }
                }
            }
            break;
        }
        return share;
    }

    static int stochasticRound(double value, RandomGenerator random) {
        int whole = (int) Math.floor(value);
        return random.nextDouble() < value - whole ? whole + 1 : whole;
    }
}
