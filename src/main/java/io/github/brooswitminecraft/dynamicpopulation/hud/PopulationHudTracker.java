package io.github.brooswitminecraft.dynamicpopulation.hud;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;
import io.github.brooswitminecraft.dynamicpopulation.propagation.LevelField;
import io.github.brooswitminecraft.dynamicpopulation.propagation.PopulationSimulation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * MINECRAFT-417 server side: tracks which players currently have the debug population HUD toggled on (never
 * more than that set -- see {@link #setEnabled}, driven only by {@link PopulationHudTogglePayload}), and
 * sends each of them a bounded, targeted {@link PopulationHudPayload} only when something they'd see has
 * actually changed: a cell/layer crossing, a dimension change, or a density/readability change while
 * standing still. Never broadcasts, never queries/sends every tick or every render frame -- checked at most
 * once every {@code hud_update_interval_ticks} server ticks, and only for enabled players.
 *
 * <p>Reads the EXACT SAME typed per-faction field {@code PopulationCombatTargetGoal.localDensity} reads --
 * {@code PopulationSimulation.field(level, faction)} then {@code field.get(field.cellAt(pos))} -- so this
 * HUD can never show a number that disagrees with what actually drives spawn/combat decisions. Distinct
 * from {@code LevelField#get} returning 0 for an unreadable cell, {@link LevelField#readable} is checked
 * first so "unknown" is never faked as a real zero (ticket AC2).
 */
public final class PopulationHudTracker {
    private static final Set<UUID> ENABLED = new HashSet<>();
    private static final Map<UUID, PopulationHudSnapshot> LAST_SENT = new HashMap<>();

    private PopulationHudTracker() { }

    /**
     * Driven only by an explicit client toggle -- never inferred, never defaulted on for anyone.
     *
     * <p>Only a genuine OFF-&gt;ON transition forces an immediate first sample (clearing
     * {@link #LAST_SENT} so the next poll is guaranteed to send). A redundant ON-&gt;ON toggle (a client
     * re-sending {@code enabled=true} while already tracked -- a buggy/spammy client, or a keybind double
     * fire) is a no-op for timing purposes: it must NOT reset {@link #LAST_SENT} or force another
     * immediate send, or it would bypass {@code hud_update_interval_ticks} and let a repeated toggle force
     * unbounded update frequency. See {@link #isGenuineOffToOnTransition}, the pure decision this defers
     * to.
     */
    public static void setEnabled(ServerPlayer player, boolean enabled) {
        UUID id = player.getUUID();
        boolean wasEnabled = ENABLED.contains(id);
        if (enabled) {
            ENABLED.add(id);
            if (isGenuineOffToOnTransition(wasEnabled, true)) {
                LAST_SENT.remove(id);
                sendIfChanged(player);
            }
        } else {
            ENABLED.remove(id);
            LAST_SENT.remove(id);
        }
    }

    /**
     * Pure gating check, directly unit-testable: only a transition from NOT-enabled to enabled counts as
     * "genuine" and warrants forcing an immediate sample bypassing the update-interval gate. Already-enabled
     * staying enabled (a redundant ON-&gt;ON toggle) must return {@code false}.
     */
    static boolean isGenuineOffToOnTransition(boolean wasEnabled, boolean enabled) {
        return enabled && !wasEnabled;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ENABLED.isEmpty()) {
            return;
        }
        if (!dueThisTick(event.getServer().getTickCount(), DynamicPopulationConfig.get().hudUpdateIntervalTicks())) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (ENABLED.contains(player.getUUID())) {
                sendIfChanged(player);
            }
        }
    }

    /** Dimension change is independently responsive -- not left to wait out the polling interval. */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && ENABLED.contains(player.getUUID())) {
            sendIfChanged(player);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ENABLED.remove(player.getUUID());
            LAST_SENT.remove(player.getUUID());
        }
    }

    /**
     * Pure gating check, directly unit-testable without a real server: this (never "every tick") is what
     * keeps {@link #onServerTick} from even looking at the enabled set, let alone computing/sending
     * anything, on 19 out of every 20 ticks at the default {@code hud_update_interval_ticks} of 10 (itself
     * already a tenth of the server's 20 ticks/second).
     */
    static boolean dueThisTick(long tickCount, int intervalTicks) {
        return tickCount % intervalTicks == 0;
    }

    private static void sendIfChanged(ServerPlayer player) {
        PopulationHudPayload payload = compute(player);
        PopulationHudSnapshot snapshot = PopulationHudSnapshot.from(payload);
        PopulationHudSnapshot previous = LAST_SENT.get(player.getUUID());
        if (snapshot.equals(previous)) {
            return;
        }
        LAST_SENT.put(player.getUUID(), snapshot);
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static PopulationHudPayload compute(ServerPlayer player) {
        var level = player.serverLevel();
        boolean simulated = PopulationSimulation.simulated(level);
        BlockPos pos = player.blockPosition();
        int cellSize = DynamicPopulationConfig.get().cellSize();

        Map<Faction, PopulationHudPayload.Reading> readings = new EnumMap<>(Faction.class);
        CellKey cell = null;
        for (Faction faction : Faction.values()) {
            LevelField field = PopulationSimulation.field(level, faction);
            CellKey here = field.cellAt(pos);
            cell = here;
            // Outside the simulated dimension, the field is not meaningful at all -- never read it, even
            // if the attachment happens to exist and would otherwise report readable.
            boolean readable = simulated && field.readable(here);
            int densityPpt = readable ? field.get(here) : 0;
            readings.put(faction, new PopulationHudPayload.Reading(readable, densityPpt));
        }

        return new PopulationHudPayload(level.dimension().location(), simulated, cellSize,
            cell.x(), cell.layer(), cell.z(), pos.getX(), pos.getY(), pos.getZ(), readings);
    }
}
