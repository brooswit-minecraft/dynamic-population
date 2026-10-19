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
import net.neoforged.neoforge.client.event.ClientTickEvent;

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
 *
 * <p>Dimension-change detection is done by comparing the client level's CURRENT dimension key against the
 * last one seen, once per client tick ({@link #onClientTick}) -- NOT via
 * {@code PlayerEvent.PlayerChangedDimensionEvent}, which is a server-side event carrying a
 * {@code ServerPlayer} that is never identity-equal (nor otherwise usable as "the local player") to the
 * client's own {@code LocalPlayer}; a listener keyed on that event silently never fires for the client
 * actually viewing this HUD, leaving {@link #cache} showing stale cross-dimension data forever. Polling once
 * per client tick is cheap (one field compare) and catches every dimension change, including ones NeoForge
 * has no dedicated CLIENT-side event for.
 */
@EventBusSubscriber(modid = DynamicPopulationMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PopulationHudClientState {
    private static volatile PopulationHudSnapshot cache;
    private static volatile boolean enabled;
    private static volatile ResourceLocation lastSeenDimension;

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
        lastSeenDimension = null;
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        cache = null;
        enabled = false;
        lastSeenDimension = null;
    }

    /**
     * Runs every client tick (cheap: one field read, one equality check) and clears {@link #cache} the
     * moment the client's own dimension no longer matches the last one observed -- see the class doc for
     * why this replaces the old (broken) server-event-based listener.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var player = Minecraft.getInstance().player;
        ResourceLocation current = player == null ? null : player.level().dimension().location();
        if (shouldClearForDimensionChange(lastSeenDimension, current)) {
            cache = null;
        }
        lastSeenDimension = current;
    }

    /**
     * Pure decision, directly unit-testable without a real client/level: clear the cache only when there
     * WAS a previously-known dimension and the current one genuinely differs from it. A {@code null}
     * {@code lastSeen} (nothing observed yet) or a {@code null} {@code current} (no player right now --
     * disconnect/login already clear separately) must never trigger a clear here, only a real dimension
     * change while both are known.
     */
    static boolean shouldClearForDimensionChange(ResourceLocation lastSeen, ResourceLocation current) {
        return lastSeen != null && current != null && !lastSeen.equals(current);
    }
}
