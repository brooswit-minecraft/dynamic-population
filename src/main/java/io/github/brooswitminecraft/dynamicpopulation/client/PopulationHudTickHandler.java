package io.github.brooswitminecraft.dynamicpopulation.client;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudTogglePayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * MINECRAFT-417: polls {@code PopulationHudKeyMapping.TOGGLE_HUD.consumeClick()} once per client tick (not
 * once per render frame -- {@link ClientTickEvent.Post}, not a render event) and, on a fresh press, flips
 * the local toggle state and tells the server via one {@link PopulationHudTogglePayload} so
 * {@code PopulationHudTracker} starts/stops scheduling updates for this player. {@code Dist.CLIENT} only.
 */
@EventBusSubscriber(modid = DynamicPopulationMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PopulationHudTickHandler {
    private PopulationHudTickHandler() { }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PopulationHudKeyMapping.TOGGLE_HUD.consumeClick()) {
            boolean enabled = !PopulationHudClientState.isEnabled();
            PopulationHudClientState.setEnabled(enabled);
            PacketDistributor.sendToServer(new PopulationHudTogglePayload(enabled));
        }
    }
}
