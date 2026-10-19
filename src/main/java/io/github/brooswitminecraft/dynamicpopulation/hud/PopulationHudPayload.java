package io.github.brooswitminecraft.dynamicpopulation.hud;

import java.util.EnumMap;
import java.util.Map;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * MINECRAFT-417: server-to-client snapshot of the debug population HUD's data for ONE player, for the
 * faction densities the player's current population cell holds right now.
 *
 * <p>Carries everything the client needs to render the HUD AND to detect a stale update on its own
 * ({@link #dimension()}, the exact cell/layer it was computed for, and {@link #computedAtX()}/Y/Z, the block
 * position the player was standing at when the server computed this) -- see
 * {@code PopulationHudSnapshot#isStale}, the pure client-side staleness check that uses these fields.
 *
 * <p>{@link #readings()} carries one {@link Reading} per {@link Faction}, each explicitly tagged by its own
 * faction key (an {@link EnumMap}, not a fixed-order list/array) -- so one faction's entry can never silently
 * overwrite another's by landing in the wrong fixed slot, and a partial update (not all three factions
 * present) is representable without ambiguity, though the current sender ({@code PopulationHudTracker})
 * always sends all three.
 */
public record PopulationHudPayload(
        ResourceLocation dimension,
        boolean simulated,
        int cellSize,
        int cellX,
        int cellLayer,
        int cellZ,
        int computedAtX,
        int computedAtY,
        int computedAtZ,
        Map<Faction, Reading> readings
) implements CustomPacketPayload {

    /**
     * One faction's density reading. {@code readable} distinguishes a genuine, currently-known value (which
     * may legitimately be 0 -- an empty cell is real information) from "unknown/unavailable" (anchor chunk
     * not loaded, data preserved-as-corrupt, or the dimension isn't simulated at all) -- {@code densityPpt}
     * is meaningless and must be ignored by a reader when {@code readable} is false, exactly mirroring
     * {@code LevelField#readable} vs {@code LevelField#get}'s "0 for both reasons" trap.
     */
    public record Reading(boolean readable, int densityPpt) {
    }

    public static final CustomPacketPayload.Type<PopulationHudPayload> TYPE = new CustomPacketPayload.Type<>(
        ResourceLocation.fromNamespaceAndPath(DynamicPopulationMod.MODID, "population_hud"));

    public static final StreamCodec<FriendlyByteBuf, PopulationHudPayload> STREAM_CODEC =
        StreamCodec.of(PopulationHudPayload::write, PopulationHudPayload::read);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buf, PopulationHudPayload payload) {
        buf.writeResourceLocation(payload.dimension());
        buf.writeBoolean(payload.simulated());
        buf.writeVarInt(payload.cellSize());
        buf.writeVarInt(payload.cellX());
        buf.writeVarInt(payload.cellLayer());
        buf.writeVarInt(payload.cellZ());
        buf.writeVarInt(payload.computedAtX());
        buf.writeVarInt(payload.computedAtY());
        buf.writeVarInt(payload.computedAtZ());
        buf.writeVarInt(payload.readings().size());
        for (Map.Entry<Faction, Reading> entry : payload.readings().entrySet()) {
            buf.writeVarInt(entry.getKey().ordinal());
            buf.writeBoolean(entry.getValue().readable());
            buf.writeVarInt(entry.getValue().densityPpt());
        }
    }

    private static PopulationHudPayload read(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        boolean simulated = buf.readBoolean();
        int cellSize = buf.readVarInt();
        int cellX = buf.readVarInt();
        int cellLayer = buf.readVarInt();
        int cellZ = buf.readVarInt();
        int computedAtX = buf.readVarInt();
        int computedAtY = buf.readVarInt();
        int computedAtZ = buf.readVarInt();
        int count = buf.readVarInt();
        Map<Faction, Reading> readings = new EnumMap<>(Faction.class);
        for (int i = 0; i < count; i++) {
            Faction[] values = Faction.values();
            int ordinal = buf.readVarInt();
            boolean readable = buf.readBoolean();
            int densityPpt = buf.readVarInt();
            if (ordinal >= 0 && ordinal < values.length) {
                readings.put(values[ordinal], new Reading(readable, densityPpt));
            }
        }
        return new PopulationHudPayload(dimension, simulated, cellSize, cellX, cellLayer, cellZ,
            computedAtX, computedAtY, computedAtZ, readings);
    }
}
