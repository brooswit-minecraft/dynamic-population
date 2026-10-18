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

    // --- scaleCap: the actual call-site transformation.

    @Test
    void scaleCapNeverGoesNegativeOrBelowZero() {
        assertEquals(0, DayNightSpawnRate.scaleCap(0, 8000, Faction.ZOMBIE));
        assertTrue(DayNightSpawnRate.scaleCap(1, 8000, Faction.ZOMBIE) >= 0);
    }

    @Test
    void scaleCapAtFullRateIsUnchanged() {
        assertEquals(4, DayNightSpawnRate.scaleCap(4, 16000, Faction.ZOMBIE)); // Zombie's own peak, 1.0x
    }

    @Test
    void scaleCapAtQuarterRateFloors() {
        assertEquals(0, DayNightSpawnRate.scaleCap(1, 8000, Faction.ZOMBIE)); // 1 * 0.25 = 0.25 -> floor 0
        assertEquals(1, DayNightSpawnRate.scaleCap(5, 8000, Faction.ZOMBIE)); // 5 * 0.25 = 1.25 -> floor 1
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
