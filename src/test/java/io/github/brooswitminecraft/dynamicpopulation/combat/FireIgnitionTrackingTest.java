package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link FireIgnitionTracking} is what actually closes the gaps re-review has found in this latch across
 * several rounds -- melee Fire Aspect wasn't excluded at all (round 5), a short time-bounded grace window
 * could expire while a legitimately externally-caused burn was still going (round 5), and (round 7, the
 * current version) a latched "externally caused" burn was never suppressed again for its ENTIRE remaining
 * duration, letting vanilla's own repeated sun-roll top-ups on top of an external fire burn indefinitely.
 * Every test here is pure booleans/ints, the same {@code SunImmunityTicking} is wired to call, with
 * Minecraft entirely out of the picture.
 */
class FireIgnitionTrackingTest {

    @Test
    void freshSunIgnitionWithNoKnownCauseAndNoPriorProtectionIsCleared() {
        // Baseline round-4 behaviour, preserved: 0 -> 160, sun-eligible, override active, nothing else going
        // on -- this is the case that must still be suppressed.
        var decision = FireIgnitionTracking.decide(160, 0, false, 0, 1L, false, true, true);
        assertTrue(decision.freshIgnition());
        assertTrue(decision.clearFire());
        assertFalse(decision.clampFireTicks());
        assertEquals(0, decision.fireTicksAfterAction());
        assertFalse(decision.nextProtected());
    }

    @Test
    void meleeFireAspectIgnitionWhileSunEligibleIsNeverCleared() {
        // The first gap: a melee Fire Aspect hit lands (detected by SunImmunityTicking's onLivingDamagePost
        // handler and passed in here as knownNonSunIgnitionCause=true), even though sun/override conditions
        // also happen to hold this exact tick -- the entity must keep burning normally.
        var decision = FireIgnitionTracking.decide(160, 0, false, 0, 1L, true, true, true);
        assertTrue(decision.freshIgnition());
        assertFalse(decision.clearFire(), "melee Fire Aspect ignition must never be cleared as sun-caused");
        assertFalse(decision.clampFireTicks());
        assertEquals(160, decision.fireTicksAfterAction());
        assertTrue(decision.nextProtected(), "the burn it started must be latched as protected");
        assertEquals(160, decision.nextExternalBudgetTicks());
    }

    @Test
    void latchedProtectionSurvivesGraceWindowExpiryOnADecayingBurn() {
        // The second gap: an externally-caused burn (melee Fire Aspect or a Flame arrow) ignites and is
        // correctly left alone (latched). Many ticks later, with the grace window long since expired
        // (knownNonSunIgnitionCause is now false), the burn is simply decaying naturally (no jump) -- the
        // latch alone -- not the expired window -- must be what keeps this from being wrongly cleared.
        var ignition = FireIgnitionTracking.decide(160, 0, false, 0, 1L, true, true, true);
        assertTrue(ignition.nextProtected());
        assertEquals(160, ignition.nextExternalBudgetTicks());

        // 10 real ticks of natural decay in between: not fresh, latch simply carried forward, and the
        // external budget decays by exactly the elapsed real ticks (10), matching the actual observed
        // fire-tick decay (160 -> 150) since nothing topped it up. previousFireTicks is ignition's own
        // fireTicksAfterAction, exactly what the real caller would have snapshotted.
        var decaying = FireIgnitionTracking.decide(150, ignition.fireTicksAfterAction(), ignition.nextProtected(),
            ignition.nextExternalBudgetTicks(), 10L, false, true, true);
        assertFalse(decaying.freshIgnition());
        assertFalse(decaying.clearFire());
        assertFalse(decaying.clampFireTicks(), "actual ticks (150) matching the decayed budget (160-10=150) "
            + "means no sun top-up occurred -- nothing to counter");
        assertTrue(decaying.nextProtected());
        assertEquals(150, decaying.nextExternalBudgetTicks());
    }

    @Test
    void sunRollTopUpOnTopOfAnExternallyCausedBurnIsClampedBackToTheOriginalExternalTimelineNotLeftAlone() {
        // THE round-7 bug, reproduced directly: an externally-caused burn ignites and is latched...
        var ignition = FireIgnitionTracking.decide(160, 0, false, 0, 1L, true, true, true);
        assertTrue(ignition.nextProtected());
        assertEquals(160, ignition.nextExternalBudgetTicks());
        assertEquals(160, ignition.fireTicksAfterAction());

        // ...decays naturally for a while (10 real ticks, matching the observed fire-tick decay 160 -> 150,
        // no top-up -- previousFireTicks here is ignition's own fireTicksAfterAction, exactly what the real
        // caller would snapshot and feed back in)...
        var decaying = FireIgnitionTracking.decide(150, ignition.fireTicksAfterAction(), ignition.nextProtected(),
            ignition.nextExternalBudgetTicks(), 10L, false, true, true);
        assertFalse(decaying.clampFireTicks());
        assertEquals(150, decaying.nextExternalBudgetTicks());
        assertEquals(150, decaying.fireTicksAfterAction());

        // ...and THEN vanilla's own sun-burn roll independently re-ignites it on top of the still-burning
        // external fire (another 10 real ticks pass, so the natural external budget would now be 140, but
        // the mob's ACTUAL remaining fire ticks jumped back up to 160). The old (round-6) behaviour: the
        // latch alone decided "externally caused, never touch it again", and the fire burns forever at the
        // sun-stoked 160 value. The fix: this must be recognised as a sun top-up and ACTIVELY CLAMPED back
        // down to what the original external cause's own natural decay would have produced (140), not left
        // at the sun-inflated 160 and not fully cleared either (that would also erase the legitimate
        // external portion still owed).
        var sunToppedUp = FireIgnitionTracking.decide(160, decaying.fireTicksAfterAction(), decaying.nextProtected(),
            decaying.nextExternalBudgetTicks(), 10L, false, true, true);
        assertTrue(sunToppedUp.freshIgnition(), "the jump (150 -> 160) is still detected as a fresh-ignition "
            + "event at the raw fire-tick level");
        assertFalse(sunToppedUp.clearFire(), "must not be treated as a fully sun-caused burn and cleared "
            + "outright -- that would also erase the still-owed external portion");
        assertTrue(sunToppedUp.clampFireTicks(), "a sun-roll top-up on top of a latched external burn must "
            + "be actively countered, not silently left in place");
        assertEquals(140, sunToppedUp.fireTicksAfterAction(), "remaining fire ticks must be clamped back to "
            + "the original external cause's own natural decay (150 - 10 elapsed = 140), undoing exactly "
            + "the sun mechanic's 20-tick top-up and nothing more");
        assertTrue(sunToppedUp.nextProtected(), "the burn is still externally caused -- only the top-up was "
            + "countered, the underlying external attribution is untouched");
        assertEquals(140, sunToppedUp.nextExternalBudgetTicks());

        // The budget keeps decaying naturally (40 more real ticks, no further top-up) and the mob's actual
        // remaining fire ticks (now also clamped to 140) track it down toward zero on the ORIGINAL external
        // timeline, even though the sun rolled again in between.
        var furtherDecay = FireIgnitionTracking.decide(100, sunToppedUp.fireTicksAfterAction(),
            sunToppedUp.nextProtected(), sunToppedUp.nextExternalBudgetTicks(), 40L, false, true, true);
        assertFalse(furtherDecay.freshIgnition());
        assertFalse(furtherDecay.clampFireTicks());
        assertEquals(100, furtherDecay.nextExternalBudgetTicks());
        assertEquals(100, furtherDecay.fireTicksAfterAction());

        // And it does eventually actually extinguish -- once remaining fire ticks genuinely reach zero
        // (vanilla's own natural tick-down, now tracking the original external timeline rather than being
        // perpetually re-inflated), the latch resets and nothing burns forever.
        var extinguished = FireIgnitionTracking.decide(0, furtherDecay.fireTicksAfterAction(),
            furtherDecay.nextProtected(), furtherDecay.nextExternalBudgetTicks(), 100L, false, true, true);
        assertFalse(extinguished.nextProtected());
        assertEquals(0, extinguished.nextExternalBudgetTicks());
    }

    @Test
    void sunTopUpIsNotClampedWhenOverrideIsNotActive() {
        // Outside the >250-density override's scope, vanilla's own sun-burn behaviour must be left
        // completely alone, even for an otherwise-latched external burn -- the mod has no business touching
        // fire ticks at all when the override isn't eligible to suppress anything right now. The external
        // budget has already decayed to 130 (consistent with the last-observed actual value, no prior
        // top-up), and a sun roll now jumps the actual value back up to 160.
        var sunToppedUpOverrideInactive = FireIgnitionTracking.decide(160, 130, true, 130, 20L, false, true, false);
        assertTrue(sunToppedUpOverrideInactive.freshIgnition());
        assertFalse(sunToppedUpOverrideInactive.clampFireTicks(), "outside the override's scope, a sun "
            + "top-up must not be clamped -- vanilla behaviour is left alone");
        assertEquals(160, sunToppedUpOverrideInactive.fireTicksAfterAction());
        assertTrue(sunToppedUpOverrideInactive.nextProtected());
    }

    @Test
    void extinguishingResetsTheLatchSoATrulyIndependentLaterSunIgnitionIsStillSuppressed() {
        // The burn that was latched as protected actually goes out...
        var extinguished = FireIgnitionTracking.decide(0, 20, true, 10, 1L, false, true, true);
        assertFalse(extinguished.nextProtected(), "the latch must not outlive the fire it was protecting");
        assertEquals(0, extinguished.nextExternalBudgetTicks());

        // ...and a BRAND NEW, genuinely sun-caused ignition afterwards must still be correctly suppressed --
        // the old protection must not leak into an unrelated later burn.
        var freshSunIgnition = FireIgnitionTracking.decide(160, 0, extinguished.nextProtected(),
            extinguished.nextExternalBudgetTicks(), 1L, false, true, true);
        assertTrue(freshSunIgnition.clearFire());
        assertFalse(freshSunIgnition.nextProtected());
    }

    @Test
    void twoSequentialKnownCauseIgnitionsOfEqualDurationAreTrackedIndependentlyNotConfusedByStaleSnapshot() {
        // First known-cause ignition: 0 -> 160.
        var first = FireIgnitionTracking.decide(160, 0, false, 0, 1L, true, true, true);
        assertTrue(first.freshIgnition());
        assertFalse(first.clearFire());
        assertTrue(first.nextProtected());

        // Decays partway (30 real ticks, matching the observed 160 -> 130 decay -- no top-up)...
        var decaying = FireIgnitionTracking.decide(130, 160, first.nextProtected(),
            first.nextExternalBudgetTicks(), 30L, false, true, true);
        assertFalse(decaying.freshIgnition());
        assertFalse(decaying.clampFireTicks());

        // A SECOND independent known-cause ignition of the exact same duration (160) lands while still
        // burning from the first -- must be recognised as fresh (jump 130 -> 160) and still correctly
        // excluded, not confused with (or short-circuited by) the first ignition's now-stale 160 snapshot.
        var second = FireIgnitionTracking.decide(160, 130, decaying.nextProtected(),
            decaying.nextExternalBudgetTicks(), 1L, true, true, true);
        assertTrue(second.freshIgnition(), "a jump back up to the same duration must still register as fresh");
        assertFalse(second.clearFire());
        assertFalse(second.clampFireTicks(), "a GENUINE known-cause re-ignition must adopt the new value, "
            + "not be mistaken for (and clamped as) a sun top-up");
        assertTrue(second.nextProtected());
        assertEquals(160, second.nextExternalBudgetTicks());

        // Burns out completely...
        var out = FireIgnitionTracking.decide(0, 10, second.nextProtected(), second.nextExternalBudgetTicks(),
            1L, false, true, true);
        assertFalse(out.nextProtected());

        // A THIRD ignition, same duration again but this time genuinely sun-caused (no known cause), must
        // be independently and correctly cleared -- proving the two prior same-duration ignitions left no
        // stale state behind.
        var third = FireIgnitionTracking.decide(160, 0, out.nextProtected(), out.nextExternalBudgetTicks(),
            1L, false, true, true);
        assertTrue(third.clearFire());
    }

    @Test
    void notAFreshIgnitionNeverTouchesTheLatchOrClearsFire() {
        var decision = FireIgnitionTracking.decide(140, 150, true, 150, 10L, false, true, true);
        assertFalse(decision.freshIgnition());
        assertFalse(decision.clearFire());
        assertTrue(decision.nextProtected(), "an already-decaying burn's latch must be carried forward unchanged");
    }

    @Test
    void firstObservationWithNoPreviousSnapshotIsTreatedAsFreshIgnition() {
        var decision = FireIgnitionTracking.decide(160, null, false, 0, 1L, false, true, true);
        assertTrue(decision.freshIgnition());
        assertTrue(decision.clearFire());
    }

    @Test
    void notSunEligibleOrOverrideInactiveIsNeverClearedButIsNotLatchedAsExternallyCaused() {
        // Round-6 re-review fix: NOT sun-eligible, or override NOT active, correctly means the fire is not
        // cleared THIS tick -- but, unlike the pre-fix behaviour, it must NOT latch the burn as
        // "externally caused" going forward. There is no known non-sun cause here (knownNonSunIgnitionCause
        // is false in both cases): the fire's true provenance is simply unresolved by these two facts alone,
        // and overrideActive in particular must never be used as evidence of cause.
        var notSunEligible = FireIgnitionTracking.decide(160, 0, false, 0, 1L, false, false, true);
        assertFalse(notSunEligible.clearFire());
        assertFalse(notSunEligible.nextProtected(), "overrideActive being true but sunEligible false must "
            + "not fabricate an externally-caused latch");

        var overrideInactive = FireIgnitionTracking.decide(160, 0, false, 0, 1L, false, true, false);
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
        var ignition = FireIgnitionTracking.decide(160, 0, false, 0, 1L, false, true, false);
        assertFalse(ignition.clearFire());
        assertFalse(ignition.nextProtected());

        // The burn continues decaying naturally (not a fresh ignition)...
        var stillInactive = FireIgnitionTracking.decide(150, 160, ignition.nextProtected(),
            ignition.nextExternalBudgetTicks(), 10L, false, true, false);
        assertFalse(stillInactive.freshIgnition());
        assertFalse(stillInactive.clearFire());
        assertFalse(stillInactive.nextProtected());

        // ...and THEN the density override activates mid-burn (a density crossing, or the mob walking into
        // an already->250 cell), with the SAME burn still ongoing (no fresh ignition jump). Because this
        // burn's cause was never latched as external, suppression becomes eligible and must now apply, on
        // this very next tick -- it does not need to wait for another fresh-ignition jump that may never
        // come for a steadily decaying burn.
        var afterCrossing = FireIgnitionTracking.decide(140, 150, stillInactive.nextProtected(),
            stillInactive.nextExternalBudgetTicks(), 10L, false, true, true);
        assertFalse(afterCrossing.freshIgnition());
        assertTrue(afterCrossing.clearFire(), "a genuinely sun-caused burn that started before the override "
            + "activated must be suppressed as soon as the override becomes eligible mid-burn");
    }

    @Test
    void externallyCausedBurnStartingWhileOverrideInactiveStaysProtectedAfterCrossingIntoOverrideActive() {
        // The mirror-image case the ticket calls out explicitly: a mob is ignited by a KNOWN external cause
        // (melee Fire Aspect / Flame arrow / fire-lava contact) while the override is NOT active. The
        // override later activates (density crosses 250, or the mob walks into a >250 cell) WHILE the mob
        // is still burning from that same original external ignition, with NO sun top-up having occurred --
        // the override turning on must NOT retroactively relabel this burn as sun-caused; it must keep
        // burning normally for its entire natural duration.
        var ignition = FireIgnitionTracking.decide(160, 0, false, 0, 1L, true, false, false);
        assertFalse(ignition.clearFire());
        assertTrue(ignition.nextProtected(), "a known external ignition must be latched regardless of "
            + "whether the override happens to be active at that moment");

        // 20 real ticks of natural decay, grace window for the direct cause evidence now expired
        // (knownNonSunIgnitionCause is false), matching observed decay (160 -> 140, no top-up), and the
        // override has now crossed into active territory.
        var afterCrossing = FireIgnitionTracking.decide(140, 160, ignition.nextProtected(),
            ignition.nextExternalBudgetTicks(), 20L, false, true, true);
        assertFalse(afterCrossing.freshIgnition());
        assertFalse(afterCrossing.clearFire(), "external cause persists regardless of override state -- "
            + "the override activating mid-burn must not retroactively clear a burn whose cause was already "
            + "decided, at ignition, to be external");
        assertFalse(afterCrossing.clampFireTicks(), "no sun top-up occurred here (actual decay matches the "
            + "external budget's own natural decay) -- nothing to counter");
        assertTrue(afterCrossing.nextProtected());
    }

    @Test
    void immediateSameDurationReIgnitionOnTheVeryNextTickAfterAClearIsIndependentlyEvaluated() {
        // Review fix P2, round 6, still valid under the new signature: SunImmunityTicking snapshotting
        // LAST_FIRE_TICKS BEFORE calling clearFire() meant the stale pre-clear value was compared against
        // on the next tick. Decision#fireTicksAfterAction is now exactly the value the caller must
        // snapshot, proving a same-duration re-ignition on the very next tick is correctly seen as fresh
        // and independently evaluated.

        // Tick 1: fresh sun-caused ignition to 160 ticks -- no known cause, sun-eligible, override active:
        // must be cleared.
        var first = FireIgnitionTracking.decide(160, null, false, 0, 1L, false, true, true);
        assertTrue(first.clearFire());
        assertEquals(0, first.fireTicksAfterAction(), "the snapshot carried into tick 2 must reflect the "
            + "clear, not the pre-clear 160");

        // Tick 2 (the VERY NEXT tick): re-ignited straight back to the SAME duration (160), but this time
        // by a KNOWN EXTERNAL cause (e.g. a melee Fire Aspect hit) -- a completely independent ignition
        // event from tick 1's. With the fix, previousFireTicks is 0 (the post-clear snapshot), so
        // 160 > 0 correctly registers as fresh, and this ignition is re-evaluated from scratch on its own
        // merits rather than being compared against -- or confused with -- tick 1's stale 160.
        var second = FireIgnitionTracking.decide(160, first.fireTicksAfterAction(), first.nextProtected(),
            first.nextExternalBudgetTicks(), 1L, true, true, true);
        assertTrue(second.freshIgnition(), "160 -> 160 across the clear boundary must still register as a "
            + "fresh ignition, not be masked by a stale pre-clear snapshot");
        assertFalse(second.clearFire(), "this second, independent, externally-caused ignition must be "
            + "correctly left alone on its own merits");
        assertTrue(second.nextProtected());
    }
}
