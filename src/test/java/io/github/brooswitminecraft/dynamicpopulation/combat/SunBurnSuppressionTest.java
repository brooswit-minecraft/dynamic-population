package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Review fix P1-2: SunImmunityTicking must suppress ONLY sunlight ignition, never fire/lava/Flame. */
class SunBurnSuppressionTest {
    @Test
    void suppressesWhenOnFireFromSunAtDensity251() {
        boolean overrideActive = EnvironmentalOverride.active(251);
        assertTrue(SunBurnSuppression.shouldSuppress(true, true, overrideActive));
    }

    @Test
    void doesNotSuppressAtDensity250() {
        boolean overrideActive = EnvironmentalOverride.active(250);
        assertFalse(SunBurnSuppression.shouldSuppress(true, true, overrideActive),
            "250 is NOT above 250 -- the override is inactive, so sun-burning must proceed normally");
    }

    @Test
    void doesNotSuppressAtDensity249() {
        boolean overrideActive = EnvironmentalOverride.active(249);
        assertFalse(SunBurnSuppression.shouldSuppress(true, true, overrideActive));
    }

    @Test
    void fireIgnitionIsNeverSuppressedEvenAboveTheThreshold() {
        // Fire/lava/Flame-enchantment ignition does NOT make isSunBurnTick() true -- sunConditionPresent
        // must be false for every one of these, regardless of density, so the fire is left untouched.
        boolean overrideActive = EnvironmentalOverride.active(251);
        assertFalse(SunBurnSuppression.shouldSuppress(true, false, overrideActive),
            "ordinary fire ignition (sunConditionPresent=false) must never be suppressed");
    }

    @Test
    void lavaIgnitionIsNeverSuppressedEvenAboveTheThreshold() {
        boolean overrideActive = EnvironmentalOverride.active(251);
        assertFalse(SunBurnSuppression.shouldSuppress(true, false, overrideActive),
            "lava ignition (sunConditionPresent=false) must never be suppressed");
    }

    @Test
    void flameEnchantmentIgnitionIsNeverSuppressedEvenAboveTheThreshold() {
        boolean overrideActive = EnvironmentalOverride.active(251);
        assertFalse(SunBurnSuppression.shouldSuppress(true, false, overrideActive),
            "Flame-enchantment arrow ignition (sunConditionPresent=false) must never be suppressed");
    }

    @Test
    void notOnFireIsNeverSuppressedRegardlessOfInputs() {
        assertFalse(SunBurnSuppression.shouldSuppress(false, true, true));
    }

    @Test
    void sunIgnitionBelowThresholdIsNotSuppressed() {
        boolean overrideActive = EnvironmentalOverride.active(100);
        assertFalse(SunBurnSuppression.shouldSuppress(true, true, overrideActive));
    }

    @Test
    void allThreeConditionsMustHoldSimultaneously() {
        assertTrue(SunBurnSuppression.shouldSuppress(true, true, true));
        assertFalse(SunBurnSuppression.shouldSuppress(true, true, false));
        assertFalse(SunBurnSuppression.shouldSuppress(true, false, true));
        assertFalse(SunBurnSuppression.shouldSuppress(false, true, true));
    }
}
