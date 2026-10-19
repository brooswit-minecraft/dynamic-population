package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.ToIntFunction;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
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
 * MINECRAFT-425: a single flat decrement (config {@code population_death_target_decrement}, default 25),
 * subtracted exactly once from a dying entity's OWN faction field at its DEATH-POSITION cell, for every
 * population-owned entity -- Fillager, a tagged Zombie/Skeleton filler, or any of the three Kings --
 * regardless of death cause. Replaces Fillager's former hazard/other-cause split (MINECRAFT-30, AC9), which
 * is now a distinction without a difference since every actual death applies the same amount.
 *
 * <p>Entity-type-specific code ({@code FillagerPopulationSimulation}, {@code FillerFactionSimulation},
 * {@code king.KingDeathHandling}) each call {@link #capture} from their own {@code LivingDeathEvent}
 * listener -- the only correct place to read the actual death-cell position, since it fires before any
 * death-animation movement, and well before the ~20-tick-later confirmed removal. {@code LivingDeathEvent}
 * is cancellable and NOT proof of an irreversible death (see {@link KingDeathRetirement}'s own doc), so
 * {@link #capture} only ever remembers an in-memory (faction, cell) pending tuple, keyed by the entity's
 * UUID -- never storage. This class alone owns the other half: the single {@link #onEntityLeaveLevel}
 * listener that decides whether to actually apply it, gated on the SAME confirmed-{@code KILLED}-removal
 * signal {@code king.KingDeathHandling} already uses for source retirement. A cancelled death never reaches
 * that removal at all, so its pending entry is simply left to be overwritten by a later capture for the
 * same UUID (or discarded, un-applied, the next time that entity leaves the level for any other reason);
 * a genuine death is consumed -- removed from the map -- exactly once, so neither a duplicate leave-level
 * callback nor a lingering stale entry can ever apply the decrement twice.
 */
public final class PopulationDeathDecrement {
    private PopulationDeathDecrement() { }

    public record Pending(Faction faction, CellKey cell) { }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    /** Called from each entity type's own {@code LivingDeathEvent} listener; never touches storage. */
    public static void capture(UUID id, Faction faction, CellKey cell) {
        PENDING.put(id, new Pending(faction, cell));
    }

    /** Removes and returns the pending death for {@code id}, if any -- never left dangling after this call. */
    public static Optional<Pending> take(UUID id) {
        return Optional.ofNullable(PENDING.remove(id));
    }

    public static void clear() {
        PENDING.clear();
    }

    /**
     * The pure apply step: a flat, single subtraction at the pending death's OWN cell, clamped the same way
     * every other local-target adjustment in this package is (see
     * {@link FillagerReconciliation#adjustLocalTarget}) -- floors at zero, never touches a neighbour.
     */
    public static void apply(Pending pending, int decrement, ToIntFunction<CellKey> read,
            BiConsumer<CellKey, Integer> write) {
        FillagerReconciliation.adjustLocalTarget(pending.cell(), -decrement, read, write);
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        Optional<Pending> pending = take(entity.getUUID());
        if (pending.isEmpty()) {
            return;
        }
        String removalReason = entity.getRemovalReason() == null ? null : entity.getRemovalReason().name();
        if (!KingDeathRetirement.isConfirmedDeathRemoval(removalReason) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LevelField field = PopulationSimulation.field(level, pending.get().faction());
        apply(pending.get(), DynamicPopulationConfig.get().populationDeathTargetDecrement(), field::get, field::set);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
