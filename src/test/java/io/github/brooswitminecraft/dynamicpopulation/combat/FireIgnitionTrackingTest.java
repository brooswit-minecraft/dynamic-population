package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Review fix P1 (round 5): {@link FireIgnitionTracking} is what actually closes the two gaps re-review
 * found in round 4 ({@code SunImmunityTicking}'s class doc) -- melee Fire Aspect wasn't excluded at all,
 * and a short time-bounded grace window could expire while a legitimately externally-caused burn was still
 * going, letting a later redundant re-ignition wrongly clear it. Every test here is pure booleans/ints, the
 * same {@code SunImmunityTicking} is wired to call, with Minecraft entirely out of the picture.
 */
class FireIgnitionTrackingTest {

    @Test
    void freshSunIgnitionWithNoKnownCauseAndNoPriorProtectionIsCleared() {
        // Baseline round-4 behaviour, preserved: 0 -> 160, sun-eligible, override active, nothing else going
        // on -- this is the case that must still be suppressed.
        var decision = FireIgnitionTracking.decide(160, 0, false, false, true, true);
        assertTrue(decision.freshIgnition());
        assertTrue(decision.clearFire());
        assertFalse(decision.nextProtected());
    }

    @Test
    void meleeFireAspectIgnitionWhileSunEligibleIsNeverCleared() {
        // The first gap: a melee Fire Aspect hit lands (detected by SunImmunityTicking's onLivingDamagePost
        // handler and passed in here as knownNonSunIgnitionCause=true), even though sun/override conditions
        // also happen to hold this exact tick -- the entity must keep burning normally.
        var decision = FireIgnitionTracking.decide(160, 0, false, true, true, true);
        assertTrue(decision.freshIgnition());
        assertFalse(decision.clearFire(), "melee Fire Aspect ignition must never be cleared as sun-caused");
        assertTrue(decision.nextProtected(), "the burn it started must be latched as protected");
    }

    @Test
    void latchedProtectionSurvivesGraceWindowExpiryOnALaterReIgnitionTick() {
        // The second, more important gap: an externally-caused burn (melee Fire Aspect or a Flame arrow)
        // ignites and is correctly left alone (latched). Many ticks later, with the grace window long since
        // expired (knownNonSunIgnitionCause is now false -- SunImmunityTicking can no longer attribute the
        // cause from scratch) the mob is STILL burning and vanilla's own sun-burn roll independently fires
        // again on top of it (a jump in remaining ticks). The latch alone -- not the expired window -- must
        // be what keeps this from being wrongly cleared.
        var ignition = FireIgnitionTracking.decide(160, 0, false, true, true, true);
        assertTrue(ignition.nextProtected());

        // Several ticks of natural decay in between: not fresh, latch simply carried forward.
        var decaying = FireIgnitionTracking.decide(140, 150, ignition.nextProtected(), false, true, true);
        assertFalse(decaying.freshIgnition());
        assertFalse(decaying.clearFire());
        assertTrue(decaying.nextProtected());

        // The later re-fire: a jump up in remaining ticks (vanilla's own redundant sun roll), grace window
        // expired (knownNonSunIgnitionCause=false), but the latch from the original ignition is still true.
        var reFire = FireIgnitionTracking.decide(160, 90, decaying.nextProtected(), false, true, true);
        assertTrue(reFire.freshIgnition());
        assertFalse(reFire.clearFire(), "a still-latched external burn must survive a later re-ignition tick "
            + "even after the grace window that originally explained it has expired");
        assertTrue(reFire.nextProtected());
    }

    @Test
    void extinguishingResetsTheLatchSoATrulyIndependentLaterSunIgnitionIsStillSuppressed() {
        // The burn that was latched as protected actually goes out...
        var extinguished = FireIgnitionTracking.decide(0, 20, true, false, true, true);
        assertFalse(extinguished.nextProtected(), "the latch must not outlive the fire it was protecting");

        // ...and a BRAND NEW, genuinely sun-caused ignition afterwards must still be correctly suppressed --
        // the old protection must not leak into an unrelated later burn.
        var freshSunIgnition = FireIgnitionTracking.decide(160, 0, extinguished.nextProtected(), false, true, true);
        assertTrue(freshSunIgnition.clearFire());
        assertFalse(freshSunIgnition.nextProtected());
    }

    @Test
    void twoSequentialKnownCauseIgnitionsOfEqualDurationAreTrackedIndependentlyNotConfusedByStaleSnapshot() {
        // First known-cause ignition: 0 -> 160.
        var first = FireIgnitionTracking.decide(160, 0, false, true, true, true);
        assertTrue(first.freshIgnition());
        assertFalse(first.clearFire());
        assertTrue(first.nextProtected());

        // Decays partway...
        var decaying = FireIgnitionTracking.decide(100, 130, first.nextProtected(), false, true, true);
        assertFalse(decaying.freshIgnition());

        // A SECOND independent known-cause ignition of the exact same duration (160) lands while still
        // burning from the first -- must be recognised as fresh (jump 100 -> 160) and still correctly
        // excluded, not confused with (or short-circuited by) the first ignition's now-stale 160 snapshot.
        var second = FireIgnitionTracking.decide(160, 100, decaying.nextProtected(), true, true, true);
        assertTrue(second.freshIgnition(), "a jump back up to the same duration must still register as fresh");
        assertFalse(second.clearFire());
        assertTrue(second.nextProtected());

        // Burns out completely...
        var out = FireIgnitionTracking.decide(0, 10, second.nextProtected(), false, true, true);
        assertFalse(out.nextProtected());

        // A THIRD ignition, same duration again but this time genuinely sun-caused (no known cause), must
        // be independently and correctly cleared -- proving the two prior same-duration ignitions left no
        // stale state behind.
        var third = FireIgnitionTracking.decide(160, 0, out.nextProtected(), false, true, true);
        assertTrue(third.clearFire());
    }

    @Test
    void notAFreshIgnitionNeverTouchesTheLatchOrClearsFire() {
        var decision = FireIgnitionTracking.decide(140, 150, true, false, true, true);
        assertFalse(decision.freshIgnition());
        assertFalse(decision.clearFire());
        assertTrue(decision.nextProtected(), "an already-decaying burn's latch must be carried forward unchanged");
    }

    @Test
    void firstObservationWithNoPreviousSnapshotIsTreatedAsFreshIgnition() {
        var decision = FireIgnitionTracking.decide(160, null, false, false, true, true);
        assertTrue(decision.freshIgnition());
        assertTrue(decision.clearFire());
    }

    @Test
    void notSunEligibleOrOverrideInactiveIsNeverClearedAndLatchesAsProtected() {
        var notSunEligible = FireIgnitionTracking.decide(160, 0, false, false, false, true);
        assertFalse(notSunEligible.clearFire());
        assertTrue(notSunEligible.nextProtected());

        var overrideInactive = FireIgnitionTracking.decide(160, 0, false, false, true, false);
        assertFalse(overrideInactive.clearFire());
        assertTrue(overrideInactive.nextProtected());
    }
}
