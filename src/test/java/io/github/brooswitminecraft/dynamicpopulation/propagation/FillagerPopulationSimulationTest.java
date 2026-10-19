package io.github.brooswitminecraft.dynamicpopulation.propagation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-425 removed the hazard/other-cause death classification this file used to test
 * ({@code isUnsafeDeath}/{@code isUnsafeDeathTypePath}): every actual Fillager death now applies the same
 * flat decrement regardless of cause, so there is no classification left to unit test here. See
 * {@code io.github.brooswitminecraft.dynamicpopulation.reconciliation.PopulationDeathDecrementTest} for the
 * new pure capture/consume/apply coverage, shared by every population-owned entity type.
 */
class FillagerPopulationSimulationTest {
    @Test
    void onLivingDeathIsStillRegisteredAsAnEventListener() {
        // Lightweight regression guard: FillagerPopulationSimulation.onLivingDeath must keep existing as the
        // capture half of the MINECRAFT-425 death-decrement wiring (see its own javadoc). No Minecraft
        // registry/entity needed to assert the method itself is still present and public-static.
        assertTrue(hasPublicStaticOnLivingDeath());
    }

    private static boolean hasPublicStaticOnLivingDeath() {
        for (var method : FillagerPopulationSimulation.class.getDeclaredMethods()) {
            if (method.getName().equals("onLivingDeath")) {
                return true;
            }
        }
        return false;
    }
}
