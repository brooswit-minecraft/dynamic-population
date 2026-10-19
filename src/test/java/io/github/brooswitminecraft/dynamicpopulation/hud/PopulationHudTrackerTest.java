package io.github.brooswitminecraft.dynamicpopulation.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-417: {@link PopulationHudTracker#dueThisTick} is the pure gating check that keeps server-side
 * HUD polling bounded -- checked only once every {@code hud_update_interval_ticks} server ticks, never
 * every tick (let alone every render frame), and ONLY for players in the (also bounded, toggle-driven)
 * enabled set that {@link PopulationHudTracker#onServerTick} guards on before this check even runs. The
 * real server/player glue around it ({@code ServerPlayer}, {@code PacketDistributor}) needs a running game
 * to exercise and is covered by the server-boot smoke test instead, matching this repo's existing
 * convention for Minecraft-object glue (e.g. {@code PopulationSimulation}'s own real-level iteration is
 * likewise untested at the unit level -- only its pure core, {@code Propagation}/{@code CellGrid}, is).
 */
class PopulationHudTrackerTest {
    @Test
    void notDueOnMostTicksAtTheDefaultInterval() {
        for (long tick = 1; tick < 10; tick++) {
            assertFalse(PopulationHudTracker.dueThisTick(tick, 10), "tick " + tick + " should not be due yet");
        }
    }

    @Test
    void dueExactlyOnEveryIntervalBoundary() {
        assertTrue(PopulationHudTracker.dueThisTick(0, 10));
        assertTrue(PopulationHudTracker.dueThisTick(10, 10));
        assertTrue(PopulationHudTracker.dueThisTick(20, 10));
    }

    @Test
    void anIntervalOfOneIsDueEveryTick() {
        for (long tick = 0; tick < 5; tick++) {
            assertTrue(PopulationHudTracker.dueThisTick(tick, 1));
        }
    }

    /**
     * MINECRAFT-417 review fix: only a genuine OFF-&gt;ON transition may force an immediate first sample
     * (bypassing {@code hud_update_interval_ticks}) -- a redundant ON-&gt;ON toggle (a spammy/buggy client
     * re-sending {@code enabled=true}, or a keybind double-fire) must be a no-op for timing purposes, or it
     * could force unbounded update frequency.
     */
    @Test
    void offToOnIsAGenuineTransition() {
        assertTrue(PopulationHudTracker.isGenuineOffToOnTransition(false, true));
    }

    @Test
    void redundantOnToOnIsNotAGenuineTransition() {
        assertFalse(PopulationHudTracker.isGenuineOffToOnTransition(true, true));
    }

    @Test
    void onToOffIsNotAGenuineOffToOnTransition() {
        assertFalse(PopulationHudTracker.isGenuineOffToOnTransition(true, false));
    }

    @Test
    void offToOffIsNotAGenuineOffToOnTransition() {
        assertFalse(PopulationHudTracker.isGenuineOffToOnTransition(false, false));
    }

    /**
     * MINECRAFT-417 round-2 review fix: an ALTERNATING off/on/off/on... sequence must not force an
     * immediate out-of-band sample on every single off-&gt;on leg -- each one is individually a "genuine"
     * transition by {@link PopulationHudTracker#isGenuineOffToOnTransition}, but only a true first-ever
     * enable (no prior {@code LAST_SENT} entry at all) may bypass {@code hud_update_interval_ticks}.
     * {@link PopulationHudTracker#shouldForceImmediateSample} is the pure gate that enforces this.
     */
    @Test
    void firstEverEnableWithNoPriorLastSentForcesImmediateSample() {
        assertTrue(PopulationHudTracker.shouldForceImmediateSample(false, true, false));
    }

    @Test
    void reEnablingAfterAPriorLastSentDoesNotForceImmediateSample() {
        // Genuine off->on transition, but LAST_SENT memory for this player survived the toggle-off -- this
        // is exactly the alternating-toggle case the review called out, and must not force a send.
        assertFalse(PopulationHudTracker.shouldForceImmediateSample(false, true, true));
    }

    @Test
    void redundantOnToOnNeverForcesImmediateSampleRegardlessOfLastSent() {
        assertFalse(PopulationHudTracker.shouldForceImmediateSample(true, true, false));
        assertFalse(PopulationHudTracker.shouldForceImmediateSample(true, true, true));
    }

    @Test
    void disablingNeverForcesImmediateSampleRegardlessOfLastSent() {
        assertFalse(PopulationHudTracker.shouldForceImmediateSample(true, false, false));
        assertFalse(PopulationHudTracker.shouldForceImmediateSample(true, false, true));
        assertFalse(PopulationHudTracker.shouldForceImmediateSample(false, false, false));
        assertFalse(PopulationHudTracker.shouldForceImmediateSample(false, false, true));
    }

    /**
     * End-to-end (still pure/unit, no real server) regression for the alternating-toggle scenario itself:
     * simulate the decision sequence an off/on/off/on... spam would drive through
     * {@link PopulationHudTracker#shouldForceImmediateSample}, tracking "has a LAST_SENT entry" as the real
     * {@code setEnabled} would (set on the first forced send, left alone by every later disable). Only the
     * very first leg may force a send -- every later off->on leg within the same tracked lifetime must not,
     * keeping the number of forced out-of-band sends bounded to at most one per player, not one per
     * toggle-pair.
     */
    @Test
    void alternatingToggleSequenceForcesAtMostOneImmediateSample() {
        boolean wasEnabled = false;
        boolean hasPriorLastSent = false;
        int forcedSends = 0;

        boolean[] sequence = {true, false, true, false, true, false, true};
        for (boolean enabled : sequence) {
            if (enabled && PopulationHudTracker.shouldForceImmediateSample(wasEnabled, enabled, hasPriorLastSent)) {
                forcedSends++;
                hasPriorLastSent = true;
            }
            wasEnabled = enabled;
        }

        assertEquals(1, forcedSends, "rapid off/on/off/on... spam must force at most one out-of-band sample");
    }

    /**
     * MINECRAFT-417 round-3 review fix: {@link PopulationHudTracker#shouldMarkNeedsResend} is the pure
     * complement of {@link PopulationHudTracker#shouldForceImmediateSample} -- a genuine off-&gt;on
     * transition with prior {@code LAST_SENT} history must mark a resend (so the client isn't stuck with a
     * blank HUD after the server's "nothing changed" comparison stays silent), while every other transition
     * (redundant on-&gt;on, any disable, or a true first-ever enable already covered by the immediate-sample
     * path) must not.
     */
    @Test
    void reEnablingAfterAPriorLastSentMarksNeedsResend() {
        assertTrue(PopulationHudTracker.shouldMarkNeedsResend(false, true, true));
    }

    @Test
    void firstEverEnableDoesNotMarkNeedsResend() {
        // Already handled by the immediate-sample bypass instead -- marking a resend on top would be
        // redundant, not wrong, but the pure gate should still clearly exclude this case.
        assertFalse(PopulationHudTracker.shouldMarkNeedsResend(false, true, false));
    }

    @Test
    void redundantOnToOnNeverMarksNeedsResendRegardlessOfLastSent() {
        assertFalse(PopulationHudTracker.shouldMarkNeedsResend(true, true, false));
        assertFalse(PopulationHudTracker.shouldMarkNeedsResend(true, true, true));
    }

    @Test
    void disablingNeverMarksNeedsResendRegardlessOfLastSent() {
        assertFalse(PopulationHudTracker.shouldMarkNeedsResend(true, false, false));
        assertFalse(PopulationHudTracker.shouldMarkNeedsResend(true, false, true));
        assertFalse(PopulationHudTracker.shouldMarkNeedsResend(false, false, false));
        assertFalse(PopulationHudTracker.shouldMarkNeedsResend(false, false, true));
    }

    /**
     * MINECRAFT-417 round-3 review fix: the exact full lifecycle the regression was found in --
     * enable -&gt; receive a value -&gt; disable (client clears its cache) -&gt; re-enable -&gt; some unchanged
     * scheduled polls go by -&gt; a resend must still eventually reach the player, and it must do so without
     * re-introducing round 2's "immediate send on every toggle" bug.
     *
     * <p>This is a pure simulation of the same state machine {@link PopulationHudTracker#setEnabled} and
     * {@link PopulationHudTracker#sendIfChanged} drive (no real {@code ServerPlayer}/{@code PacketDistributor}
     * -- those need a running game and are covered by the server-boot smoke test per this repo's existing
     * convention, see the class javadoc above), tracking the same three pieces of state: whether a
     * {@code LAST_SENT} entry exists, whether a {@code NEEDS_RESEND} flag is pending, and how many sends
     * happen in total. "Content changed" is modeled directly as a boolean per poll, exactly like the content
     * equality check {@code sendIfChanged} performs against {@code LAST_SENT}.
     */
    @Test
    void genuineReEnableEventuallyResendsEvenWithNoUnderlyingChange() {
        boolean wasEnabled = false;
        boolean hasPriorLastSent = false;
        boolean needsResendPending = false;
        int totalSends = 0;

        // 1. Enable (first ever) -> forces an immediate out-of-band sample.
        boolean enabled = true;
        if (PopulationHudTracker.shouldForceImmediateSample(wasEnabled, enabled, hasPriorLastSent)) {
            totalSends++;
            hasPriorLastSent = true;
        }
        wasEnabled = enabled;
        assertEquals(1, totalSends, "first-ever enable must force exactly one immediate sample");

        // 2. Disable -> LAST_SENT memory survives (per round-2 fix); no send is triggered by disabling.
        enabled = false;
        wasEnabled = enabled; // setEnabled only removes from ENABLED; LAST_SENT/hasPriorLastSent is untouched.
        assertEquals(1, totalSends, "disabling must never trigger a send");

        // 3. Re-enable (genuine off->on, with prior LAST_SENT history) -> must NOT force an immediate
        //    sample (that would reintroduce round 2's alternating-toggle bug), but MUST mark a pending
        //    resend so the next eligible poll catches the client up.
        enabled = true;
        boolean forcedImmediate = PopulationHudTracker.shouldForceImmediateSample(wasEnabled, enabled, hasPriorLastSent);
        assertFalse(forcedImmediate, "re-enable with prior history must not bypass the interval (round-2 regression)");
        if (PopulationHudTracker.shouldMarkNeedsResend(wasEnabled, enabled, hasPriorLastSent)) {
            needsResendPending = true;
        }
        wasEnabled = enabled;
        assertEquals(1, totalSends, "re-enabling itself must not have sent anything yet");
        assertTrue(needsResendPending, "a genuine re-enable with prior history must queue a pending resend");

        // 4. Several scheduled polls go by with NO underlying content change. Model each poll the way
        //    sendIfChanged does: a pending resend forces a send even though content-equality alone would
        //    have stayed silent; the flag is consumed (one-shot) on the poll that uses it.
        boolean[] contentChangedPerPoll = {false, false, false};
        int pollsThatSent = 0;
        for (boolean contentChanged : contentChangedPerPoll) {
            boolean forcedResend = needsResendPending;
            needsResendPending = false; // consumed, same as NEEDS_RESEND.remove(id) in sendIfChanged.
            if (forcedResend || contentChanged) {
                totalSends++;
                pollsThatSent++;
            }
        }

        assertEquals(1, pollsThatSent,
            "exactly one of the unchanged scheduled polls must resend -- the one that consumes the pending flag");
        assertEquals(2, totalSends,
            "bounded total: one immediate first-ever sample, one catch-up resend on re-enable -- never more");
        assertFalse(needsResendPending, "the resend flag must be consumed, not left pending indefinitely");
    }
}
