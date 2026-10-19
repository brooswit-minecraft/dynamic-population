package io.github.brooswitminecraft.dynamicpopulation.client;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudPayload;
import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * MINECRAFT-417: the one piece of real client state this story adds -- a single cached
 * {@link PopulationHudSnapshot} (null when nothing valid is known yet) plus whether the player has the HUD
 * toggled on. {@code Dist.CLIENT}-scoped, same pattern as {@code DynamicPopulationClient}: a dedicated
 * server never loads this class at all, so it never touches {@link Minecraft#getInstance()}.
 *
 * <p>Deliberately reset on every lifecycle transition the ticket calls out (AC: "never carry stale values
 * across those transitions") -- disconnect, reconnect, and dimension change all clear {@link #cache} (and
 * disconnect/reconnect also turn the toggle back off, since the server-side {@code PopulationHudTracker}
 * forgets the player's enabled state on logout too -- re-enabling would otherwise leave the HUD showing
 * stale numbers forever with no further update ever coming).
 */
@EventBusSubscriber(modid = DynamicPopulationMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PopulationHudClientState {
    private static volatile PopulationHudSnapshot cache;
    private static volatile boolean enabled;

    private PopulationHudClientState() { }

    public static boolean isEnabled() {
        return enabled;
    }

    public static PopulationHudSnapshot cache() {
        return cache;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            cache = null;
        }
    }

    /** Called from the network handler ({@code PopulationHudClientNetwork}) whenever a payload arrives. */
    public static void onReceived(PopulationHudPayload payload) {
        if (PopulationHudSnapshot.isStale(payload, currentDimension())) {
            return;
        }
        cache = PopulationHudSnapshot.from(payload);
    }

    private static ResourceLocation currentDimension() {
        var player = Minecraft.getInstance().player;
        return player == null ? null : player.level().dimension().location();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        cache = null;
        enabled = false;
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        cache = null;
        enabled = false;
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() == Minecraft.getInstance().player) {
            cache = null;
        }
    }
}
