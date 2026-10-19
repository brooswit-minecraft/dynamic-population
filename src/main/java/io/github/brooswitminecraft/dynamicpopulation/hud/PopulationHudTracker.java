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
    private static final Set<UUID> NEEDS_RESEND = new HashSet<>();

    private PopulationHudTracker() { }

    /**
     * Driven only by an explicit client toggle -- never inferred, never defaulted on for anyone.
     *
     * <p>Only a genuinely FIRST-EVER enable (no prior {@link #LAST_SENT} entry for this player at all)
     * forces an immediate out-of-band sample. {@link #LAST_SENT} is deliberately NOT cleared on disable
     * (round-1 fix for a different bug, below, only cleared it on genuine redundant-toggle handling -- this
     * round-2 fix goes further and stops clearing it on OFF transitions at all): a player's "when were they
     * last sent an update" memory must survive a toggle-off/toggle-on cycle, or an ALTERNATING
     * off/on/off/on... sequence -- each individual off-&gt;on in it being a textbook genuine transition by
     * {@link #isGenuineOffToOnTransition} -- would force a fresh immediate send on every single re-enable,
     * defeating {@code hud_update_interval_ticks} just as much as round 1's "redundant ON-&gt;ON" bug did,
     * through a different path. Keeping {@link #LAST_SENT} intact means a re-enable soon after a disable
     * just resumes being polled on the normal {@link #onServerTick} cadence like any other already-tracked
     * player -- {@link #sendIfChanged} naturally finds nothing changed yet and stays silent until the
     * interval elapses (or something the player would actually see changes). Only when there has NEVER been
     * a {@link #LAST_SENT} entry for this player (brand new to tracking, or cleared by an actual
     * logout/login via {@link #onLoggedOut}) does enabling force that first sample, so the HUD doesn't
     * otherwise sit blank until the next polling boundary. See {@link #isGenuineOffToOnTransition} and
     * {@link #shouldForceImmediateSample}, the pure decisions this defers to.
     *
     * <p>MINECRAFT-417 round-3 review fix: {@link #LAST_SENT} surviving a disable is correct for RATE-LIMITING
     * purposes (above), but it also doubles as the CONTENT the next {@link #sendIfChanged} call compares
     * against to decide "did anything change". Those are two different questions. The client clears its own
     * cached snapshot on every disable (correct since round 1 -- the HUD should visually go blank). So on a
     * genuine re-enable where the underlying density genuinely hasn't moved since the disable, the old
     * single-flag design saw "no content change" and stayed silent forever, even though the client has
     * nothing cached any more and is waiting for a value it would recognize as new. A re-enable with prior
     * {@link #LAST_SENT} history (so it does NOT qualify for the first-ever-enable immediate bypass above)
     * instead marks {@link #NEEDS_RESEND}: a one-shot flag consumed by the very next interval-eligible
     * {@link #sendIfChanged} call (normal polling cadence, or the independently-responsive dimension-change
     * hook), which forces exactly one resend of the current value even if it compares equal to
     * {@link #LAST_SENT}. This still respects {@code hud_update_interval_ticks} -- it does not bypass the
     * tick-gated poll the way the first-ever-enable case does -- while guaranteeing the player is not left
     * stuck with a blank HUD indefinitely just because nothing changed server-side.
     */
    public static void setEnabled(ServerPlayer player, boolean enabled) {
        UUID id = player.getUUID();
        boolean wasEnabled = ENABLED.contains(id);
        if (enabled) {
            ENABLED.add(id);
            boolean hasPriorLastSent = LAST_SENT.containsKey(id);
            if (shouldForceImmediateSample(wasEnabled, true, hasPriorLastSent)) {
                sendIfChanged(player);
            } else if (shouldMarkNeedsResend(wasEnabled, true, hasPriorLastSent)) {
                NEEDS_RESEND.add(id);
            }
        } else {
            ENABLED.remove(id);
        }
    }

    /**
     * Pure gating check, directly unit-testable: only a transition from NOT-enabled to enabled counts as
     * "genuine" and is even eligible to force an immediate sample bypassing the update-interval gate.
     * Already-enabled staying enabled (a redundant ON-&gt;ON toggle) must return {@code false}.
     */
    static boolean isGenuineOffToOnTransition(boolean wasEnabled, boolean enabled) {
        return enabled && !wasEnabled;
    }

    /**
     * Pure gating check, directly unit-testable: a genuine OFF-&gt;ON transition forces an immediate sample
     * ONLY when there is no prior {@link #LAST_SENT} entry for this player at all (a true first-ever
     * enable). A genuine OFF-&gt;ON transition that DOES have a prior entry -- i.e. the player was enabled
     * before, toggled off, and is now toggling back on, with that memory intact -- must return
     * {@code false} and let the normal interval-gated polling resume, or a rapid off/on/off/on... sequence
     * would force one immediate out-of-band send per re-enable and bypass
     * {@code hud_update_interval_ticks} just like the round-1 "redundant ON-&gt;ON" bug did.
     */
    static boolean shouldForceImmediateSample(boolean wasEnabled, boolean enabled, boolean hasPriorLastSent) {
        return isGenuineOffToOnTransition(wasEnabled, enabled) && !hasPriorLastSent;
    }

    /**
     * Pure gating check, directly unit-testable: the MINECRAFT-417 round-3 complement of
     * {@link #shouldForceImmediateSample}. A genuine OFF-&gt;ON transition that DOES have a prior
     * {@link #LAST_SENT} entry (so it does NOT qualify for the immediate out-of-band bypass above) must
     * instead mark {@link #NEEDS_RESEND}, so the client -- which cleared its own cache on the disable that
     * preceded this re-enable -- is guaranteed exactly one fresh value on the next interval-eligible poll,
     * even if the server's view of the content hasn't changed at all. A redundant ON-&gt;ON toggle, a
     * disable, or a true first-ever enable must all return {@code false} here: the first two have nothing
     * to catch up on, and the third is already handled by the immediate-sample path instead.
     */
    static boolean shouldMarkNeedsResend(boolean wasEnabled, boolean enabled, boolean hasPriorLastSent) {
        return isGenuineOffToOnTransition(wasEnabled, enabled) && hasPriorLastSent;
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
            NEEDS_RESEND.remove(player.getUUID());
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
        UUID id = player.getUUID();
        PopulationHudPayload payload = compute(player);
        PopulationHudSnapshot snapshot = PopulationHudSnapshot.from(payload);
        PopulationHudSnapshot previous = LAST_SENT.get(id);
        // MINECRAFT-417 round-3 review fix: a pending NEEDS_RESEND flag (set by setEnabled on a genuine
        // re-enable with prior LAST_SENT history, see there) forces exactly one resend here even when the
        // freshly computed snapshot compares equal to what was last sent -- the client's own cache was
        // wiped by the disable, so "no content change" from the server's point of view must not be read as
        // "nothing to do". Consuming the flag (remove, not just a contains check) makes this a one-shot: a
        // single bounded catch-up send, not a standing bypass of the equality check on every later poll.
        boolean forcedResend = NEEDS_RESEND.remove(id);
        if (!forcedResend && snapshot.equals(previous)) {
            return;
        }
        LAST_SENT.put(id, snapshot);
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
