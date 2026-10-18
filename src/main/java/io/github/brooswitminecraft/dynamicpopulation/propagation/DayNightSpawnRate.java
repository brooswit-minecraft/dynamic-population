package io.github.brooswitminecraft.dynamicpopulation.propagation;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;

/**
 * MINECRAFT-390 (settled spec, per Jira comment id 48143): a per-faction spawn-RATE multiplier tied to the
 * 24000-tick Minecraft day, read from world/game ticks ({@code ServerLevel#getDayTime() % 24000}) only --
 * NEVER real wall-clock time. Affects reconciliation's spawn rate ONLY: never existing population/density
 * counts, never despawn, never Kings, and never the independent {@code EnvironmentalOverride} (>250
 * light/sun threshold) -- both apply simultaneously and neither gates the other; see
 * {@code FillagerPopulationSimulation}/{@code FillerFactionSimulation} for the two call sites, which
 * compute this multiplier and the light-level override from entirely separate inputs.
 *
 * <p><b>Fillager: independent 12h/12h day/night split, no 8-hour phases at all</b> -- 100% of the normal
 * configured rate from tick 0 (dawn) through tick 12000 (sunset) exclusive, 25% from tick 12000 through the
 * next tick-0 (dawn) exclusive.
 *
 * <p><b>Zombie/Skeleton: three successive, equal, non-overlapping 8000-tick phases tiling the full 24000-tick
 * cycle</b>, cut at ticks 4000, 12000, and 20000 (mod 24000), in the fixed order Day / Early-night / Late-night:
 * <ul>
 * <li>Day: [4000, 12000) -- 10:00-18:00 clock-equivalent, solidly daytime, ending exactly at vanilla's own
 * sunset tick (12000).</li>
 * <li>Early-night (Zombie's own peak): [12000, 20000) -- 18:00-2:00, STARTING EXACTLY at vanilla's sunset
 * tick (12000), through the first stretch of real darkness.</li>
 * <li>Late-night (Skeleton's own peak): [20000, 24000) &#8746; [0, 4000) -- 2:00-10:00, covering deep night
 * through vanilla's own sunrise ticks (23000-24000) and just past dawn.</li>
 * </ul>
 * This is the same alignment reasoning as {@code docs/villager-king.md} repeats for readers who never open
 * this file: cutting at 4000/12000/20000 (rather than naively at 0/8000/16000) is what makes Early-night
 * start AT, not drift 4000 ticks ahead of, vanilla's actual sunset -- a naive 0-start tiling would put a full
 * third of "Early-night" still in genuine daylight.
 *
 * <p>Multiplier matrix (relative to each faction's OWN normal configured rate), per phase:
 * <table>
 * <caption>multipliers</caption>
 * <tr><th>Faction</th><th>Day</th><th>Early-night</th><th>Late-night</th></tr>
 * <tr><td>Zombie</td><td>0.25</td><td>1.00 (own peak)</td><td>0.50</td></tr>
 * <tr><td>Skeleton</td><td>0.25</td><td>0.50</td><td>1.00 (own peak)</td></tr>
 * </table>
 */
public final class DayNightSpawnRate {
    public static final long DAY_LENGTH_TICKS = 24000L;

    /** Fillager's own day/night cut: day is [0, FILLAGER_NIGHT_START), night is the rest. */
    private static final long FILLAGER_NIGHT_START = 12000L;
    public static final double FILLAGER_DAY_MULTIPLIER = 1.0;
    public static final double FILLAGER_NIGHT_MULTIPLIER = 0.25;

    /** Zombie/Skeleton phase cut offset -- see class doc for why 4000, not 0. */
    private static final long PHASE_CUT_OFFSET = 4000L;
    private static final long PHASE_LENGTH_TICKS = 8000L;

    public enum Phase { DAY, EARLY_NIGHT, LATE_NIGHT }

    private DayNightSpawnRate() { }

    public static double fillagerMultiplier(long dayTimeTicks) {
        long t = Math.floorMod(dayTimeTicks, DAY_LENGTH_TICKS);
        return t < FILLAGER_NIGHT_START ? FILLAGER_DAY_MULTIPLIER : FILLAGER_NIGHT_MULTIPLIER;
    }

    public static Phase zombieSkeletonPhase(long dayTimeTicks) {
        long t = Math.floorMod(dayTimeTicks - PHASE_CUT_OFFSET, DAY_LENGTH_TICKS);
        return switch ((int) (t / PHASE_LENGTH_TICKS)) {
            case 0 -> Phase.DAY;
            case 1 -> Phase.EARLY_NIGHT;
            default -> Phase.LATE_NIGHT;
        };
    }

    public static double zombieMultiplier(long dayTimeTicks) {
        return switch (zombieSkeletonPhase(dayTimeTicks)) {
            case DAY -> 0.25;
            case EARLY_NIGHT -> 1.0;
            case LATE_NIGHT -> 0.5;
        };
    }

    public static double skeletonMultiplier(long dayTimeTicks) {
        return switch (zombieSkeletonPhase(dayTimeTicks)) {
            case DAY -> 0.25;
            case EARLY_NIGHT -> 0.5;
            case LATE_NIGHT -> 1.0;
        };
    }

    /** The one call site every spawn-rate consumer should use -- dispatches to the right rule per faction. */
    public static double multiplier(long dayTimeTicks, Faction faction) {
        return switch (faction) {
            case VILLAGER -> fillagerMultiplier(dayTimeTicks);
            case ZOMBIE -> zombieMultiplier(dayTimeTicks);
            case SKELETON -> skeletonMultiplier(dayTimeTicks);
        };
    }

    /**
     * One interval's accumulate-then-emit result: {@code spawnAllowance} is the whole-number cap to use
     * THIS interval; {@code carry} is the leftover fraction to feed into the NEXT call.
     *
     * <p><b>Review fix (P1-1):</b> a naive {@code floor(configuredCap * multiplier)} per interval silently
     * floors every sub-1.0 result to ZERO whenever {@code configuredCap} is small relative to
     * {@code 1/multiplier} -- at the DEFAULT {@code fillager_refill_per_interval = 1}, {@code floor(1 *
     * 0.25) == 0} and {@code floor(1 * 0.5) == 0}, so Fillagers would spawn at a literal zero rate all
     * night, and Zombie/Skeleton fillers at zero rate in both their non-peak phases -- not the requested
     * 25%/50%. This record instead carries the undistributed fraction across intervals (see
     * {@link #accumulate}), so the LONG-RUN rate over many intervals converges to the configured multiplier
     * even when any single interval's emitted whole number is 0.
     */
    public record Accumulation(int spawnAllowance, double carry) { }

    /**
     * Pure accumulator step: adds this interval's fractional budget ({@code configuredCap * multiplier}) to
     * the carried-over fraction from the previous call, emits the whole part as this interval's spawn
     * allowance, and keeps the remaining fraction for the next call. Over N intervals the sum of every
     * emitted {@code spawnAllowance} converges to within 1 of {@code N * configuredCap * multiplier} --
     * never systematically zero merely because one interval's product happens to round down.
     */
    public static Accumulation accumulate(double carryIn, int configuredCap, double multiplier) {
        double total = carryIn + configuredCap * multiplier;
        int whole = (int) Math.floor(total);
        return new Accumulation(whole, total - whole);
    }
}
