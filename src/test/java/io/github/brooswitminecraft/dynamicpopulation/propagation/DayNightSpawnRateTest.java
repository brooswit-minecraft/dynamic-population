package io.github.brooswitminecraft.dynamicpopulation.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.brooswitminecraft.dynamicpopulation.combat.EnvironmentalOverride;
import io.github.brooswitminecraft.dynamicpopulation.combat.PopulationCombatEligibility;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import org.junit.jupiter.api.Test;

/** MINECRAFT-390 (settled spec, Jira comment id 48143): the day/night spawn-rate multiplier. */
class DayNightSpawnRateTest {
    // --- Fillager: independent 12h/12h split.

    @Test
    void fillagerDaytimeIsFullRate() {
        assertEquals(1.0, DayNightSpawnRate.fillagerMultiplier(0));
    }

    @Test
    void fillagerNighttimeIsQuarterRate() {
        assertEquals(0.25, DayNightSpawnRate.fillagerMultiplier(14000));
    }

    @Test
    void fillagerDayNightCutIsExactlyAtTick12000() {
        assertEquals(1.0, DayNightSpawnRate.fillagerMultiplier(11999));
        assertEquals(0.25, DayNightSpawnRate.fillagerMultiplier(12000));
    }

    @Test
    void fillagerWrapsAcrossTheFullCycle() {
        assertEquals(1.0, DayNightSpawnRate.fillagerMultiplier(24000)); // == tick 0 of the next day
        assertEquals(0.25, DayNightSpawnRate.fillagerMultiplier(23999));
    }

    // --- Zombie/Skeleton: three 8000-tick phases, cut at 4000/12000/20000.

    @Test
    void zombieSkeletonPhaseBoundariesAreContiguousAndNonOverlapping() {
        // Every one of the 24000 ticks in a cycle belongs to EXACTLY one phase (trivially true of any total
        // function), and each phase covers EXACTLY 8000 of them -- this is the actual "tiles with no
        // gaps/overlap" property worth asserting.
        int day = 0;
        int earlyNight = 0;
        int lateNight = 0;
        for (long t = 0; t < DayNightSpawnRate.DAY_LENGTH_TICKS; t++) {
            switch (DayNightSpawnRate.zombieSkeletonPhase(t)) {
                case DAY -> day++;
                case EARLY_NIGHT -> earlyNight++;
                case LATE_NIGHT -> lateNight++;
            }
        }
        assertEquals(8000, day);
        assertEquals(8000, earlyNight);
        assertEquals(8000, lateNight);
    }

    @Test
    void dayPhaseBoundaries() {
        assertEquals(DayNightSpawnRate.Phase.LATE_NIGHT, DayNightSpawnRate.zombieSkeletonPhase(3999));
        assertEquals(DayNightSpawnRate.Phase.DAY, DayNightSpawnRate.zombieSkeletonPhase(4000));
        assertEquals(DayNightSpawnRate.Phase.DAY, DayNightSpawnRate.zombieSkeletonPhase(11999));
        assertEquals(DayNightSpawnRate.Phase.EARLY_NIGHT, DayNightSpawnRate.zombieSkeletonPhase(12000));
    }

    @Test
    void earlyNightPhaseStartsExactlyAtVanillaSunset() {
        // Vanilla's own sunset tick is 12000 -- Early-night (Zombie's own peak) must start exactly there,
        // not drift ahead of real darkness.
        assertEquals(DayNightSpawnRate.Phase.EARLY_NIGHT, DayNightSpawnRate.zombieSkeletonPhase(12000));
        assertEquals(DayNightSpawnRate.Phase.EARLY_NIGHT, DayNightSpawnRate.zombieSkeletonPhase(19999));
        assertEquals(DayNightSpawnRate.Phase.LATE_NIGHT, DayNightSpawnRate.zombieSkeletonPhase(20000));
    }

    @Test
    void lateNightPhaseWrapsThroughTickZero() {
        assertEquals(DayNightSpawnRate.Phase.LATE_NIGHT, DayNightSpawnRate.zombieSkeletonPhase(23999));
        assertEquals(DayNightSpawnRate.Phase.LATE_NIGHT, DayNightSpawnRate.zombieSkeletonPhase(0));
        assertEquals(DayNightSpawnRate.Phase.LATE_NIGHT, DayNightSpawnRate.zombieSkeletonPhase(3999));
    }

    // --- Representative tick inside each of the three phases, per faction -- matrix coverage.

    @Test
    void zombieMultiplierMatrix() {
        assertEquals(0.25, DayNightSpawnRate.zombieMultiplier(8000)); // Day phase
        assertEquals(1.0, DayNightSpawnRate.zombieMultiplier(16000)); // Early-night phase (Zombie's own peak)
        assertEquals(0.5, DayNightSpawnRate.zombieMultiplier(22000)); // Late-night phase
    }

    @Test
    void skeletonMultiplierMatrix() {
        assertEquals(0.25, DayNightSpawnRate.skeletonMultiplier(8000)); // Day phase
        assertEquals(0.5, DayNightSpawnRate.skeletonMultiplier(16000)); // Early-night phase
        assertEquals(1.0, DayNightSpawnRate.skeletonMultiplier(22000)); // Late-night phase (Skeleton's own peak)
    }

    @Test
    void facadeDispatchesToTheRightRulePerFaction() {
        assertEquals(DayNightSpawnRate.fillagerMultiplier(8000), DayNightSpawnRate.multiplier(8000, Faction.VILLAGER));
        assertEquals(DayNightSpawnRate.zombieMultiplier(8000), DayNightSpawnRate.multiplier(8000, Faction.ZOMBIE));
        assertEquals(DayNightSpawnRate.skeletonMultiplier(8000), DayNightSpawnRate.multiplier(8000, Faction.SKELETON));
    }

    // --- accumulate: the fractional-carry fix for review finding P1-1 (a naive per-interval floor
    // silently floors a sub-1.0 rate to a literal ZERO spawn rate at the default refill limit of 1).

    @Test
    void accumulateNeverGoesNegative() {
        assertEquals(0, DayNightSpawnRate.accumulate(0.0, 0, 0.25).spawnAllowance());
    }

    @Test
    void accumulateAtFullRateEmitsTheWholeCapEveryInterval() {
        var result = DayNightSpawnRate.accumulate(0.0, 4, 1.0);
        assertEquals(4, result.spawnAllowance());
        assertEquals(0.0, result.carry(), 1e-9);
    }

    @Test
    void defaultRefillLimitAtQuarterRateSpawnsExactlyOnceEveryFourIntervals() {
        // THE regression test for P1-1: configuredCap=1 (the real default fillager_refill_per_interval),
        // multiplier=0.25 (a non-peak Fillager-night / Zombie-day / Skeleton-day phase). A naive
        // floor(1*0.25) would emit 0 EVERY interval forever -- the actual bug. The accumulator must instead
        // emit exactly 1 spawn allowance somewhere across every 4 intervals, carrying the rest forward.
        double carry = 0.0;
        int totalSpawned = 0;
        int[] perInterval = new int[4];
        for (int i = 0; i < 4; i++) {
            var result = DayNightSpawnRate.accumulate(carry, 1, 0.25);
            perInterval[i] = result.spawnAllowance();
            carry = result.carry();
            totalSpawned += result.spawnAllowance();
        }
        assertEquals(1, totalSpawned, "must spawn at least once across 4 intervals at a 0.25x rate -- never a flat zero");
        assertEquals(0.0, carry, 1e-9, "carry must return to exactly 0 after a full 4-interval cycle at 0.25x");
        // Deterministic (no randomness involved): the spawn must land on the LAST interval, not earlier --
        // confirms the carry genuinely accumulates rather than spawning early by coincidence.
        assertEquals(0, perInterval[0]);
        assertEquals(0, perInterval[1]);
        assertEquals(0, perInterval[2]);
        assertEquals(1, perInterval[3]);
    }

    @Test
    void defaultRefillLimitAtHalfRateSpawnsOnceEveryTwoIntervals() {
        // Zombie/Skeleton's own "opposite peak" phase (0.5x) at the default refill limit of 1.
        double carry = 0.0;
        int totalOverEightIntervals = 0;
        for (int i = 0; i < 8; i++) {
            var result = DayNightSpawnRate.accumulate(carry, 1, 0.5);
            carry = result.carry();
            totalOverEightIntervals += result.spawnAllowance();
        }
        assertEquals(4, totalOverEightIntervals, "8 intervals at 0.5x with cap 1 must total exactly 4, not 0");
    }

    @Test
    void lowConfiguredLimitAtQuarterRateConvergesToTheLongRunRate() {
        // A "low configured limit" other than the bare default: cap=3, multiplier=0.25 -> 0.75/interval.
        // Over a 4-interval cycle (0.75*4 = 3.0 exactly) the fractional remainder returns to 0 and the
        // total spawned must be exactly 3 -- not floor(3*0.25)=0 repeated forever.
        double carry = 0.0;
        int total = 0;
        for (int i = 0; i < 4; i++) {
            var result = DayNightSpawnRate.accumulate(carry, 3, 0.25);
            carry = result.carry();
            total += result.spawnAllowance();
        }
        assertEquals(3, total);
        assertEquals(0.0, carry, 1e-9);
    }

    @Test
    void longRunRateApproximatesTheConfiguredMultiplierOverManyIntervals() {
        // A longer run (100 intervals) at the default refill limit (1) and the 0.25x multiplier: the
        // long-run AVERAGE spawn allowance per interval must be close to 0.25, not 0.
        double carry = 0.0;
        int total = 0;
        int intervals = 100;
        for (int i = 0; i < intervals; i++) {
            var result = DayNightSpawnRate.accumulate(carry, 1, 0.25);
            carry = result.carry();
            total += result.spawnAllowance();
        }
        double observedRate = total / (double) intervals;
        assertEquals(0.25, observedRate, 0.02, "observed long-run rate must approximate the 0.25x multiplier");
        assertTrue(total > 0, "100 intervals at a 0.25x rate must spawn SOMETHING, not a flat zero");
    }

    // --- No interaction with the independent >250 override, or with each other's inputs.

    @Test
    void doesNotInteractWithTheLightSunOverride() {
        // Pick a tick/density combination where the day/night multiplier is at its lowest (0.25, daytime)
        // while the independent >250 override is simultaneously active -- neither value should change
        // because of the other; they are computed from, and gate, completely separate things.
        long daytimeTick = 8000;
        int density = 300;
        assertEquals(0.25, DayNightSpawnRate.multiplier(daytimeTick, Faction.ZOMBIE));
        assertTrue(EnvironmentalOverride.active(density));
        // And the reverse combination: override inactive while the spawn-rate multiplier is at its peak.
        long peakTick = 16000;
        int lowDensity = 100;
        assertEquals(1.0, DayNightSpawnRate.multiplier(peakTick, Faction.ZOMBIE));
        assertFalse(EnvironmentalOverride.active(lowDensity));
    }

    @Test
    void doesNotInteractWithTheCombatThreshold() {
        long tick = 16000;
        int density = 499;
        assertEquals(1.0, DayNightSpawnRate.multiplier(tick, Faction.ZOMBIE));
        assertFalse(PopulationCombatEligibility.active(density));
    }
}
