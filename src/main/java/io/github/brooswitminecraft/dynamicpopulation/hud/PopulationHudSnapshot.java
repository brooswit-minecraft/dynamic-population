package io.github.brooswitminecraft.dynamicpopulation.hud;

import java.util.Map;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.resources.ResourceLocation;

/**
 * MINECRAFT-417: the client-side cached HUD state, as a plain value -- no Minecraft client classes (no
 * {@code Minecraft}, no {@code GuiGraphics}), so it is directly unit-testable and reusable from either side
 * without pulling in anything {@code Dist.CLIENT}-only. The actual mutable cache holder, and the one call
 * into real client state ({@code Minecraft.getInstance().player}'s current dimension) needed to evaluate
 * {@link #isStale}, live in the {@code client} package's {@code PopulationHudClientState} -- this class is
 * the pure decision, that one is the thin wiring, the same split {@code KingEntitySync} already uses
 * elsewhere in this codebase (pure {@code missingEntities} vs. the one real-level {@code sync} call site).
 */
public record PopulationHudSnapshot(
        ResourceLocation dimension,
        boolean simulated,
        int cellSize,
        int cellX,
        int cellLayer,
        int cellZ,
        Map<Faction, PopulationHudPayload.Reading> readings
) {
    public static PopulationHudSnapshot from(PopulationHudPayload payload) {
        return new PopulationHudSnapshot(payload.dimension(), payload.simulated(), payload.cellSize(),
            payload.cellX(), payload.cellLayer(), payload.cellZ(), payload.readings());
    }

    /**
     * Whether an incoming payload must be rejected as stale: it was computed for a dimension the player has
     * since left. {@code liveDimension} is the player's CURRENT dimension at the moment the packet is
     * handled (read fresh from the real client player, not cached) -- a payload computed for an earlier
     * dimension that arrives after the player has already changed dimension/world/reconnected is exactly
     * the race this guards against (ticket AC3). A null {@code liveDimension} (no player -- e.g. the packet
     * arrives after disconnect, or before the player entity exists) is also treated as stale: there is
     * nothing live to accept it for.
     */
    public static boolean isStale(PopulationHudPayload payload, ResourceLocation liveDimension) {
        return liveDimension == null || !liveDimension.equals(payload.dimension());
    }
}
