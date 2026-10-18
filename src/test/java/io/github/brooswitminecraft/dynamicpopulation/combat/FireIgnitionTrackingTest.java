package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void notSunEligibleOrOverrideInactiveIsNeverClearedButIsNotLatchedAsExternallyCaused() {
        // Round-6 re-review fix: NOT sun-eligible, or override NOT active, correctly means the fire is not
        // cleared THIS tick -- but, unlike the pre-fix behaviour, it must NOT latch the burn as
        // "externally caused" going forward. There is no known non-sun cause here (knownNonSunIgnitionCause
        // is false in both cases): the fire's true provenance is simply unresolved by these two facts alone,
        // and overrideActive in particular must never be used as evidence of cause -- see the two new tests
        // below for why conflating the two was the actual round-5 bug.
        var notSunEligible = FireIgnitionTracking.decide(160, 0, false, false, false, true);
        assertFalse(notSunEligible.clearFire());
        assertFalse(notSunEligible.nextProtected(), "overrideActive being true but sunEligible false must "
            + "not fabricate an externally-caused latch");

        var overrideInactive = FireIgnitionTracking.decide(160, 0, false, false, true, false);
        assertFalse(overrideInactive.clearFire());
        assertFalse(overrideInactive.nextProtected(), "overrideActive being false must not itself be "
            + "treated as evidence that the ignition was externally caused -- this was the round-5 P1 bug: "
            + "a genuinely sun-caused burn that started before the override activated would otherwise be "
            + "permanently misclassified as protected");
    }

    @Test
    void sunCausedBurnStartingBeforeOverrideActivatesIsSuppressedAsSoonAsOverrideTurnsOnMidBurn() {
        // Round-6 re-review P1: a genuine sun-caused ignition (no known external cause) starts while the
        // override is NOT YET active -- so it is correctly left alone this tick, same as the test above.
        var ignition = FireIgnitionTracking.decide(160, 0, false, false, true, false);
        assertFalse(ignition.clearFire());
        assertFalse(ignition.nextProtected());

        // The burn continues decaying naturally (not a fresh ignition)...
        var stillInactive = FireIgnitionTracking.decide(150, 160, ignition.nextProtected(), false, true, false);
        assertFalse(stillInactive.freshIgnition());
        assertFalse(stillInactive.clearFire());
        assertFalse(stillInactive.nextProtected());

        // ...and THEN the density override activates mid-burn (a density crossing, or the mob walking into
        // an already->250 cell), with the SAME burn still ongoing (no fresh ignition jump). Because this
        // burn's cause was never latched as external, suppression becomes eligible and must now apply, on
        // this very next tick -- it does not need to wait for another fresh-ignition jump that may never
        // come for a steadily decaying burn.
        var afterCrossing = FireIgnitionTracking.decide(140, 150, stillInactive.nextProtected(), false, true, true);
        assertFalse(afterCrossing.freshIgnition());
        assertTrue(afterCrossing.clearFire(), "a genuinely sun-caused burn that started before the override "
            + "activated must be suppressed as soon as the override becomes eligible mid-burn");
    }

    @Test
    void externallyCausedBurnStartingWhileOverrideInactiveStaysProtectedAfterCrossingIntoOverrideActive() {
        // The mirror-image case the ticket calls out explicitly: a mob is ignited by a KNOWN external cause
        // (melee Fire Aspect / Flame arrow / fire-lava contact) while the override is NOT active. The
        // override later activates (density crosses 250, or the mob walks into a >250 cell) WHILE the mob
        // is still burning from that same original external ignition -- the override turning on must NOT
        // retroactively relabel this burn as sun-caused; it must keep burning normally for its entire
        // natural duration.
        var ignition = FireIgnitionTracking.decide(160, 0, false, true, false, false);
        assertFalse(ignition.clearFire());
        assertTrue(ignition.nextProtected(), "a known external ignition must be latched regardless of "
            + "whether the override happens to be active at that moment");

        // Several ticks of natural decay, grace window for the direct cause evidence now expired
        // (knownNonSunIgnitionCause is false), and the override has now crossed into active territory.
        var afterCrossing = FireIgnitionTracking.decide(130, 150, ignition.nextProtected(), false, true, true);
        assertFalse(afterCrossing.freshIgnition());
        assertFalse(afterCrossing.clearFire(), "external cause persists regardless of override state -- "
            + "the override activating mid-burn must not retroactively clear a burn whose cause was already "
            + "decided, at ignition, to be external");
        assertTrue(afterCrossing.nextProtected());
    }

    @Test
    void snapshotAfterThisTickReflectsTheClearNotThePreClearValue() {
        // Review fix P2, round 6: the pure half of the fix. The caller (SunImmunityTicking) must snapshot
        // 0, not the pre-clear currentFireTicks, for any tick where clearFire() was honoured.
        assertEquals(0, FireIgnitionTracking.snapshotFireTicksAfterThisTick(160, true));
        assertEquals(160, FireIgnitionTracking.snapshotFireTicksAfterThisTick(160, false));
    }

    @Test
    void immediateSameDurationReIgnitionOnTheVeryNextTickAfterAClearIsIndependentlyEvaluated() {
        // Review fix P2, round 6: simulates the EXACT caller sequence the re-review flagged --
        // SunImmunityTicking snapshotting LAST_FIRE_TICKS BEFORE calling clearFire() meant the stale
        // pre-clear value was compared against on the next tick. This test drives decide() +
        // snapshotFireTicksAfterThisTick() together, the same way the fixed caller now does, to prove a
        // same-duration re-ignition on the very next tick is correctly seen as fresh and independently
        // evaluated, never silently skipped because it happens to match a stale snapshot.

        // Tick 1: fresh sun-caused ignition to 160 ticks -- no known cause, sun-eligible, override active:
        // must be cleared.
        var first = FireIgnitionTracking.decide(160, null, false, false, true, true);
        assertTrue(first.clearFire());
        int snapshotAfterTick1 = FireIgnitionTracking.snapshotFireTicksAfterThisTick(160, first.clearFire());
        assertEquals(0, snapshotAfterTick1, "the snapshot carried into tick 2 must reflect the clear, not "
            + "the pre-clear 160");

        // Tick 2 (the VERY NEXT tick): re-ignited straight back to the SAME duration (160), but this time
        // by a KNOWN EXTERNAL cause (e.g. a melee Fire Aspect hit) -- a completely independent ignition
        // event from tick 1's. With the fix, previousFireTicks is 0 (the post-clear snapshot), so
        // 160 > 0 correctly registers as fresh, and this ignition is re-evaluated from scratch on its own
        // merits rather than being compared against -- or confused with -- tick 1's stale 160.
        var second = FireIgnitionTracking.decide(160, snapshotAfterTick1, first.nextProtected(),
            true, true, true);
        assertTrue(second.freshIgnition(), "160 -> 160 across the clear boundary must still register as a "
            + "fresh ignition, not be masked by a stale pre-clear snapshot");
        assertFalse(second.clearFire(), "this second, independent, externally-caused ignition must be "
            + "correctly left alone on its own merits");
        assertTrue(second.nextProtected());
    }
}
