package io.github.brooswitminecraft.dynamicpopulation.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-417 review fix: {@link PopulationHudClientState#shouldClearForDimensionChange}, the pure
 * decision that replaced the broken {@code PlayerEvent.PlayerChangedDimensionEvent}-based listener (a
 * SERVER-side event carrying a {@code ServerPlayer} that is never identity-equal to the client's own
 * {@code LocalPlayer}, so the old listener silently never fired for the client actually viewing the HUD).
 * This pure method needs no {@code Minecraft}/level bootstrap to exercise -- it is a plain
 * {@link ResourceLocation} comparison.
 */
class PopulationHudClientStateTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation NETHER = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    @Test
    void genuineDimensionChangeMustClear() {
        assertTrue(PopulationHudClientState.shouldClearForDimensionChange(OVERWORLD, NETHER));
    }

    @Test
    void stayingInTheSameDimensionMustNotClear() {
        assertFalse(PopulationHudClientState.shouldClearForDimensionChange(OVERWORLD, OVERWORLD));
    }

    @Test
    void noPreviouslyKnownDimensionMustNotClear() {
        // Nothing observed yet (e.g. the very first tick after logging in) -- login/logout already clear
        // the cache separately; this check must not also fire here.
        assertFalse(PopulationHudClientState.shouldClearForDimensionChange(null, OVERWORLD));
    }

    @Test
    void noCurrentPlayerMustNotClearHere() {
        // No live player right now (e.g. mid-disconnect) -- disconnect handling clears the cache on its
        // own; this check must not also fire for a merely-absent current dimension.
        assertFalse(PopulationHudClientState.shouldClearForDimensionChange(OVERWORLD, null));
    }
}
