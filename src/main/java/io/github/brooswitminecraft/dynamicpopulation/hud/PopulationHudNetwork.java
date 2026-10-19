package io.github.brooswitminecraft.dynamicpopulation.hud;

import io.github.brooswitminecraft.dynamicpopulation.client.PopulationHudClientNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * MINECRAFT-417: registers BOTH payload types/codecs for this story's networking from common (server-safe)
 * code -- the client-to-server {@link PopulationHudTogglePayload} AND the server-to-client
 * {@link PopulationHudPayload}.
 *
 * <p>This is deliberate, not a sidedness bug: {@link RegisterPayloadHandlersEvent} fires on BOTH physical
 * sides, and NeoForge's channel negotiation requires a payload's type/codec to be registered on both the
 * client and the dedicated server even though only the client ever actually RECEIVES/handles the
 * server-to-client one -- a dedicated server that never registers {@link PopulationHudPayload} at all (the
 * bug this class used to have) breaks negotiation for everyone, since the channel's declared payload set
 * must match on both ends. Registering a {@code CustomPacketPayload.Type}/{@code StreamCodec} pair here does
 * NOT touch any client-only class (no {@code Minecraft}, no {@code GuiGraphics}) -- sidedness rules apply to
 * object instantiation / class loading, not to registering a codec descriptor, so this is safe to run on a
 * dedicated server.
 *
 * <p>The handler passed to {@code playToClient} below is a method reference to
 * {@link PopulationHudClientNetwork#handle}, a {@code client}-package static method -- but referencing that
 * method here does not load/initialize {@code PopulationHudClientNetwork} eagerly, let alone run its body:
 * the JVM resolves a method reference's target lazily, only when the generated handler is actually invoked,
 * and a dedicated server never receives a server-to-client payload, so it never invokes this handler at all.
 * {@link PopulationHudClientNetwork#handle} itself is the ONLY place that touches real client state
 * ({@code Minecraft.getInstance()} via {@link PopulationHudClientState}) -- everything in THIS class stays
 * free of client-only types, which is what makes registering both payloads from one common path safe.
 */
public final class PopulationHudNetwork {
    private static final String PROTOCOL_VERSION = "1";

    private PopulationHudNetwork() { }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(PopulationHudTogglePayload.TYPE, PopulationHudTogglePayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer serverPlayer) {
                    PopulationHudTracker.setEnabled(serverPlayer, payload.enabled());
                }
            }));
        registrar.playToClient(PopulationHudPayload.TYPE, PopulationHudPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> PopulationHudClientNetwork.handle(payload)));
    }
}
