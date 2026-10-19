package io.github.brooswitminecraft.dynamicpopulation.client;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * MINECRAFT-417: registers the server-to-client {@link PopulationHudPayload} handler. {@code Dist.CLIENT}
 * only, same pattern as {@code DynamicPopulationClient} -- a dedicated server never loads this class, so the
 * handler lambda's reference to {@link PopulationHudClientState} (which itself touches
 * {@code Minecraft.getInstance()}) is never resolved there. See {@code PopulationHudNetwork} (common
 * package) for the other half of this registration -- the client-to-server toggle payload, which IS safe on
 * both sides and lives there instead.
 */
@EventBusSubscriber(modid = DynamicPopulationMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PopulationHudClientNetwork {
    private static final String PROTOCOL_VERSION = "1";

    private PopulationHudClientNetwork() { }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION).playToClient(PopulationHudPayload.TYPE, PopulationHudPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> PopulationHudClientState.onReceived(payload)));
    }
}
