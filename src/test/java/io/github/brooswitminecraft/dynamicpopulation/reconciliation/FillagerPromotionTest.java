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
