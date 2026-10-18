package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfigTestSupport;

class FillagerPromotionTest {
    @AfterEach
    void restoreLiveConfigDefault() {
        DynamicPopulationConfigTestSupport.reload("fillager_promotion_emerald_cost", 1);
    }

    @Test
    void anItemThatIsNotAnEmeraldNeverPromotesRegardlessOfCount() {
        assertFalse(FillagerPromotion.canPromote(false, 64, 1), "a non-emerald stack must never promote");
    }

    @Test
    void fewerEmeraldsThanTheConfiguredCostDoesNotPromote() {
        assertFalse(FillagerPromotion.canPromote(true, 2, 3));
    }

    @Test
    void exactlyTheConfiguredCostPromotes() {
        assertTrue(FillagerPromotion.canPromote(true, 3, 3));
    }

    @Test
    void moreThanTheConfiguredCostStillPromotes() {
        assertTrue(FillagerPromotion.canPromote(true, 5, 3));
    }

    @Test
    void emptyMainHandMustNotBlockAValidOffhandAttempt() {
        // The exact review-flagged regression (Fillager.mobInteract previously hardcoded a definitive
        // SUCCESS on the client regardless of what was held): Entity.interact's pipeline treats ANY
        // non-PASS mobInteract result as definitive and never then tries the other hand, on either side.
        // So the SAME eligibility gate that decides "defer, don't consume this hand's result" must say so
        // for an empty main hand, on both sides, before a same-tick offhand attempt is even considered.
        assertFalse(FillagerPromotion.canPromote(false, 0, 1), "an empty main hand must defer, never succeed");
        assertTrue(FillagerPromotion.canPromote(true, 1, 1), "the offhand attempt that follows must still promote");
    }

    @Test
    void belowCostMainHandMustNotBlockAValidOffhandAttempt() {
        assertFalse(FillagerPromotion.canPromote(true, 2, 3), "an under-cost main hand must defer, never succeed");
        assertTrue(FillagerPromotion.canPromote(true, 3, 3), "the offhand attempt that follows must still promote");
    }

    @Test
    void onlyTheServerEverEvaluatesEligibility() {
        // The review-flagged live-cost-divergence scenario: NeoForge's config sync is connection-time only,
        // so a server-side live reload of the promotion cost after a client has joined can leave that
        // client's cached cost stale. The client must therefore NEVER evaluate canPromote itself -- not
        // "evaluate it using some safer value," but skip the evaluation entirely -- so every hand attempt
        // always reaches the server, which alone holds the authoritative cost.
        assertFalse(FillagerPromotion.shouldEvaluateOnThisSide(false), "the client must always defer");
        assertTrue(FillagerPromotion.shouldEvaluateOnThisSide(true), "only the server evaluates eligibility");
    }

    @Test
    void defaultCostIsOneEmerald() {
        DynamicPopulationConfigTestSupport.load();
        assertTrue(1 == DynamicPopulationConfig.get().promotionEmeraldCost(),
            "the documented default promotion cost is 1 emerald");
    }

    @Test
    void liveConfigReloadChangesTheRequiredCountWithoutRestart() {
        DynamicPopulationConfigTestSupport.load();

        DynamicPopulationConfigTestSupport.reload("fillager_promotion_emerald_cost", 1);
        int before = DynamicPopulationConfig.get().promotionEmeraldCost();
        assertTrue(FillagerPromotion.canPromote(true, 1, before));

        DynamicPopulationConfigTestSupport.reload("fillager_promotion_emerald_cost", 4);
        int after = DynamicPopulationConfig.get().promotionEmeraldCost();
        assertTrue(after == 4, "the live snapshot must observe the reload, not a frozen value");
        assertFalse(FillagerPromotion.canPromote(true, 1, after),
            "a single emerald must no longer be enough once the live-reloaded cost is 4");
        assertTrue(FillagerPromotion.canPromote(true, 4, after),
            "the very next promotion check must use the reloaded cost, with no restart");
    }
}
