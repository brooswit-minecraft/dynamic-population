package io.github.brooswitminecraft.dynamicpopulation.client;

import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudPayload;
import net.minecraft.client.Minecraft;

/**
 * MINECRAFT-417: the ONLY piece of this story's networking that is Dist.CLIENT-scoped -- the actual handler
 * logic for an incoming {@link PopulationHudPayload}, which hands off to {@link PopulationHudClientState}
 * (itself the one place that touches {@link Minecraft#getInstance()}).
 *
 * <p>Payload TYPE/CODEC registration for BOTH directions now lives in the common {@code hud} package's
 * {@code PopulationHudNetwork} (see its class doc for why that is required, and why a dedicated server
 * registering this payload's codec is safe even though it never runs {@link #handle}). This class is
 * referenced from there only as a method reference passed to {@code playToClient} -- the JVM does not
 * resolve/run a method reference's target until it is actually invoked, and a dedicated server never
 * receives a server-to-client payload, so {@link #handle} is only ever reached on a real client.
 */
public final class PopulationHudClientNetwork {
    private PopulationHudClientNetwork() { }

    public static void handle(PopulationHudPayload payload) {
        PopulationHudClientState.onReceived(payload);
    }
}
