package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Review fix P1-2 (round 2): {@code SunBurnSuppression} must suppress ONLY a fresh ignition that is both
 * sun-eligible AND has no known non-sun cause -- never fire/lava/Flame ignition, even when it happens to
 * coincide with daytime/bright/open-sky conditions (the exact scenario the previous "round 3" fix got
 * wrong, since a vanilla fire block uses the identical ignite-duration call sun-burning does).
 */
class SunBurnSuppressionTest {
    @Test
    void suppressesFreshSunIgnitionAtDensity251() {
        boolean overrideActive = EnvironmentalOverride.active(251);
        assertTrue(SunBurnSuppression.shouldSuppress(true, true, false, overrideActive));
    }

    @Test
    void doesNotSuppressAtDensity250() {
        boolean overrideActive = EnvironmentalOverride.active(250);
        assertFalse(SunBurnSuppression.shouldSuppress(true, true, false, overrideActive),
            "250 is NOT above 250 -- the override is inactive, so sun-burning must proceed normally");
    }

    @Test
    void doesNotSuppressAtDensity249() {
        boolean overrideActive = EnvironmentalOverride.active(249);
        assertFalse(SunBurnSuppression.shouldSuppress(true, true, false, overrideActive));
    }

    /**
     * THE exact scenario the reviewer asked be tested explicitly: an entity ignited by fire/lava/Flame
     * WHILE ALSO meeting the deterministic daytime/light/open-sky conditions (sunEligible=true) must still
     * burn normally -- knownNonSunIgnitionCause=true must win regardless of sunEligible or density.
     */
    @Test
    void fireLavaOrFlameIgnitionIsNeverSuppressedEvenWhenSunConditionsAlsoHold() {
        boolean overrideActive = EnvironmentalOverride.active(251);
        assertFalse(SunBurnSuppression.shouldSuppress(true, true, true, overrideActive),
            "a known non-sun ignition cause (fire/lava/Flame) must never be suppressed, "
                + "even when daytime/light/open-sky conditions also happen to hold");
    }

    @Test
    void notAFreshIgnitionIsNeverSuppressed() {
        // A continuing, already-decaying fire (whatever its true origin) must never be re-evaluated and
        // cleared on a later tick -- only the exact tick of a fresh ignition is ever acted on.
        assertFalse(SunBurnSuppression.shouldSuppress(false, true, false, true));
    }

    @Test
    void notSunEligibleIsNeverSuppressed() {
        assertFalse(SunBurnSuppression.shouldSuppress(true, false, false, true));
    }

    @Test
    void sunIgnitionBelowThresholdIsNotSuppressed() {
        boolean overrideActive = EnvironmentalOverride.active(100);
        assertFalse(SunBurnSuppression.shouldSuppress(true, true, false, overrideActive));
    }

    @Test
    void allFourConditionsMustHoldSimultaneously() {
        assertTrue(SunBurnSuppression.shouldSuppress(true, true, false, true));
        assertFalse(SunBurnSuppression.shouldSuppress(false, true, false, true));
        assertFalse(SunBurnSuppression.shouldSuppress(true, false, false, true));
        assertFalse(SunBurnSuppression.shouldSuppress(true, true, true, true));
        assertFalse(SunBurnSuppression.shouldSuppress(true, true, false, false));
    }
}
