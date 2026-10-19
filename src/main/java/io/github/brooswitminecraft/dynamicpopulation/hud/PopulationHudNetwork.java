package io.github.brooswitminecraft.dynamicpopulation.hud;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * MINECRAFT-417: registers the ONE direction of this story's networking that is safe to handle from common
 * (server-safe) code -- the client-to-server {@link PopulationHudTogglePayload}, whose handler only ever
 * touches {@link PopulationHudTracker} (plain server bookkeeping, no client classes at all).
 *
 * <p>The other direction -- the server-to-client {@link PopulationHudPayload}, whose handler must call into
 * real client state ({@code Minecraft.getInstance()}) -- is registered separately, in the {@code client}
 * package's {@code PopulationHudClientNetwork}, which is {@code Dist.CLIENT}-scoped exactly like
 * {@code DynamicPopulationClient} already is for renderers. Both registration calls target the SAME
 * {@link RegisterPayloadHandlersEvent} instance (NeoForge fires it once per side; each subscriber just adds
 * its own type to the shared registrar), so splitting the registration across two classes this way is the
 * normal, supported pattern -- not a workaround.
 */
public final class PopulationHudNetwork {
    private static final String PROTOCOL_VERSION = "1";

    private PopulationHudNetwork() { }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION).playToServer(PopulationHudTogglePayload.TYPE,
            PopulationHudTogglePayload.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer serverPlayer) {
                    PopulationHudTracker.setEnabled(serverPlayer, payload.enabled());
                }
            }));
    }
}
