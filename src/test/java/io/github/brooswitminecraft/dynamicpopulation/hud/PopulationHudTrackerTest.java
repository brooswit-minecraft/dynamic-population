package io.github.brooswitminecraft.dynamicpopulation.hud;

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
}
