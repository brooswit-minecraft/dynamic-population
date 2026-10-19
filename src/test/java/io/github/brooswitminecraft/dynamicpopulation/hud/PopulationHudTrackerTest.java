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
}
