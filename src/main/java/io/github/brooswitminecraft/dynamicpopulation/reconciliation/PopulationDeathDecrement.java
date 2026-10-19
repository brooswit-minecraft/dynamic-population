package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.ToIntFunction;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.king.KingDeathRetirement;
import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;
import io.github.brooswitminecraft.dynamicpopulation.propagation.LevelField;
import io.github.brooswitminecraft.dynamicpopulation.propagation.PopulationSimulation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * MINECRAFT-425: a flat decrement, subtracted exactly once from a dying entity's OWN faction field at its
 * DEATH-POSITION cell, for every population-owned entity -- Fillager, a tagged Zombie/Skeleton filler, or
 * any of the three Kings -- regardless of death cause. The AMOUNT is two-tier, by a later user request: an
 * ordinary Fillager/tagged-filler death uses config {@code population_death_target_decrement} (default 25);
 * a King death -- any of the three types -- uses the separate, larger config
 * {@code king_death_target_decrement} (default 500), since a King is a far more significant population
 * loss. Each entity type's own capture call site picks its own amount (see {@link Pending#decrement()}),
 * never this class. Replaces Fillager's former hazard/other-cause split (MINECRAFT-30, AC9), which is now a
 * distinction without a difference since every ordinary-entity death applies the same amount as every
 * other.
 *
 * <p>Entity-type-specific code ({@code FillagerPopulationSimulation}, {@code FillerFactionSimulation},
 * {@code king.KingDeathHandling}) each call {@link #capture} from their own {@code LivingDeathEvent}
 * listener -- the only correct place to read the actual death-cell position, since it fires before any
 * death-animation movement, and well before the ~20-tick-later confirmed removal. {@code LivingDeathEvent}
 * is cancellable and NOT proof of an irreversible death (see {@link KingDeathRetirement}'s own doc), so
 * {@link #capture} only ever remembers an in-memory (faction, cell) pending tuple, keyed by the entity's
 * UUID -- never storage. This class alone owns the other half: the single {@link #onEntityLeaveLevel}
 * listener that decides whether to actually apply it, gated on the SAME confirmed-{@code KILLED}-removal
 * signal {@code king.KingDeathHandling} already uses for source retirement.
 *
 * <p><b>Review fix (round 1): client-side leave events must never touch {@code PENDING}.</b> In an
 * integrated client+server JVM (singleplayer), the SAME UUID has a server-side {@code Entity} and a
 * separate client-side one, each firing its OWN {@code EntityLeaveLevelEvent} against its own
 * {@code Level}; since {@code PENDING} is static (shared across both), a client-side leave event reaching
 * {@link #take} first -- on a {@code ClientLevel}, for a reason that is never the authoritative one -- would
 * silently consume (and lose) the real, server-captured pending death before the server-side confirmed-
 * {@code KILLED} event ever got a chance to see it. {@link #onEntityLeaveLevel} therefore checks
 * {@code event.getLevel() instanceof ServerLevel} BEFORE calling {@link #take} at all (via
 * {@link #consumeIfConfirmedServerKill}, the pure half of that gate) -- {@code PENDING} is mutated only by a
 * server-side leave event, never a client-side one.
 *
 * <p><b>Review fix (round 1): a stale capture must not outlive the eligibility it was captured under.</b>
 * If a tagged Zombie/Skeleton filler's death is CANCELLED at cell A (capturing (faction, A)), then that same
 * entity becomes persistence-required (named/leashed) -- no longer eligible, so {@code
 * FillerFactionSimulation.isFiller} now excludes it -- and later actually dies for real at cell B, the LATER
 * {@code LivingDeathEvent} finds it ineligible and (before this fix) simply skipped calling {@link #capture}
 * again, leaving the STALE (faction, A) entry from the cancelled attempt to be wrongly consumed and applied
 * by the real death's confirmed removal. Every entity-type {@code onLivingDeath} listener now calls
 * {@link #take} UNCONDITIONALLY as its first action -- discarding whatever was pending, win or lose -- and
 * only calls {@link #capture} afterward if THIS attempt is itself currently eligible. So the real death's own
 * {@code LivingDeathEvent} (which fires before its confirmed removal, same as every death) always clears the
 * stale A-entry first; since the entity is ineligible at that point, nothing new is captured, and the
 * confirmed removal that follows finds nothing pending to apply.
 *
 * <p>Net effect: a cancelled death never reaches confirmed removal at all, so its pending entry is either
 * superseded by a later capture for the same UUID, or discarded (un-applied) the next time that entity's own
 * {@code LivingDeathEvent} fires -- eligible or not -- or by any other server-side leave event; a genuine,
 * still-eligible death is consumed -- removed from the map -- exactly once, so neither a duplicate
 * leave-level callback nor a lingering stale entry can ever apply the decrement twice, or at the wrong cell.
 */
public final class PopulationDeathDecrement {
    private PopulationDeathDecrement() { }

    /** {@code decrement} is picked by the CAPTURING call site (ordinary vs. King amount), not applied here. */
    public record Pending(Faction faction, CellKey cell, int decrement) { }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    /** Called from each entity type's own {@code LivingDeathEvent} listener; never touches storage. */
    public static void capture(UUID id, Faction faction, CellKey cell, int decrement) {
        PENDING.put(id, new Pending(faction, cell, decrement));
    }

    /** Removes and returns the pending death for {@code id}, if any -- never left dangling after this call. */
    public static Optional<Pending> take(UUID id) {
        return Optional.ofNullable(PENDING.remove(id));
    }

    public static void clear() {
        PENDING.clear();
    }

    /**
     * The pure apply step: a flat, single subtraction (the pending's OWN {@link Pending#decrement()},
     * picked by the capturing call site) at the pending death's OWN cell, clamped the same way every other
     * local-target adjustment in this package is (see {@link FillagerReconciliation#adjustLocalTarget}) --
     * floors at zero, never touches a neighbour.
     */
    public static void apply(Pending pending, ToIntFunction<CellKey> read, BiConsumer<CellKey, Integer> write) {
        FillagerReconciliation.adjustLocalTarget(pending.cell(), -pending.decrement(), read, write);
    }

    /**
     * The pure gate behind {@link #onEntityLeaveLevel}: {@code isServerLevel} must be checked and found true
     * BEFORE {@link #take} is ever called -- a {@code false} here must short-circuit without touching
     * {@code PENDING} at all, which is exactly what makes a client-side leave event (same UUID, integrated
     * JVM) harmless. When server-side, {@link #take} always runs (clearing a stale/non-{@code KILLED}
     * entry same as ever), but this returns empty unless the removal is also a confirmed {@code KILLED}.
     */
    static Optional<Pending> consumeIfConfirmedServerKill(boolean isServerLevel, UUID id, String removalReasonName) {
        if (!isServerLevel) {
            return Optional.empty();
        }
        Optional<Pending> pending = take(id);
        if (pending.isEmpty() || !KingDeathRetirement.isConfirmedDeathRemoval(removalReasonName)) {
            return Optional.empty();
        }
        return pending;
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        boolean isServerLevel = event.getLevel() instanceof ServerLevel;
        String removalReason = entity.getRemovalReason() == null ? null : entity.getRemovalReason().name();
        Optional<Pending> pending = consumeIfConfirmedServerKill(isServerLevel, entity.getUUID(), removalReason);
        if (pending.isEmpty()) {
            return;
        }
        LevelField field = PopulationSimulation.field((ServerLevel) event.getLevel(), pending.get().faction());
        apply(pending.get(), field::get, field::set);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
