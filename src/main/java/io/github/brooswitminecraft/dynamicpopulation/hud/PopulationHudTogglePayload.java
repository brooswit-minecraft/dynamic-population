package io.github.brooswitminecraft.dynamicpopulation.hud;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * MINECRAFT-417: client-to-server. Sent once whenever the player toggles the debug population HUD (keybind
 * or client command), so the server only ever schedules bounded, targeted {@link PopulationHudPayload}
 * updates for players who actually have the HUD open -- never a broadcast, never every render frame.
 */
public record PopulationHudTogglePayload(boolean enabled) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PopulationHudTogglePayload> TYPE = new CustomPacketPayload.Type<>(
        ResourceLocation.fromNamespaceAndPath(DynamicPopulationMod.MODID, "population_hud_toggle"));

    public static final StreamCodec<FriendlyByteBuf, PopulationHudTogglePayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.BOOL, PopulationHudTogglePayload::enabled, PopulationHudTogglePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
