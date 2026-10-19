package io.github.brooswitminecraft.dynamicpopulation.king;

import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.VillagerKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.LevelField;
import io.github.brooswitminecraft.dynamicpopulation.propagation.PopulationSimulation;
import io.github.brooswitminecraft.dynamicpopulation.reconciliation.PopulationDeathDecrement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * MINECRAFT-414: on an ACTUAL, CONFIRMED-IRREVERSIBLE King death (any faction, naturally founded OR
 * command-settled via {@link KingManager#settle}), announce it (type-only, no location, matching
 * {@link NaturalFounding#deathAnnouncementText}) and retire its {@link KingRecord.Entry} so
 * {@link KingEntitySync} can never resurrect it and registry-driven seeding stops reading from a dead
 * source. This retirement/announcement path itself still does NOT touch the faction's population density
 * field (only the King's own source/registry record) -- {@link #onLivingDeath} below is a SEPARATE,
 * independently-dedup'd capture for {@link PopulationDeathDecrement} (MINECRAFT-425), which is what makes a
 * King's death also subtract the flat population_death_target_decrement from its own faction field at its
 * death cell, the same as a Fillager's or filler's. This class does NOT add any King-vs-King hostility/targeting behavior
 * -- that is explicitly a separate follow-up, out of scope here.
 *
 * <p><b>[P1 review fix] Why {@link EntityLeaveLevelEvent}, not {@code LivingDeathEvent}:</b> the earlier
 * version listened on {@code LivingDeathEvent} at {@code EventPriority.LOWEST}, reasoning that every other
 * listener would already have had its chance to cancel by then. That reasoning was correct as far as it
 * went, but incomplete: {@code LOWEST} orders priorities, it does not forbid ANOTHER listener registered
 * at the SAME priority (this mod's own, or any other mod's) from running after this one and still
 * cancelling the death -- {@code event.isCanceled()} at this handler's own call time only reflects the
 * state AT THAT MOMENT, not the event's final outcome. A later cancellation (reviving the King) would
 * leave this handler having already announced "died" and erased its registry entry for a King that is, in
 * truth, still alive -- exactly the violation the review flagged.
 *
 * <p>{@link EntityLeaveLevelEvent} is NOT cancellable (it is a plain notification that an entity has
 * ALREADY left the level) and fires from {@code LevelCallback#onTrackingEnd}, which for a death only ever
 * runs via {@code LivingEntity#tickDeath}'s {@code this.remove(Entity.RemovalReason.KILLED)} call -- made
 * ~1 second (20 ticks) AFTER an uncancelled {@code LivingDeathEvent}, never before, and never at all if
 * that event was cancelled (a cancelled death never reaches {@code tickDeath}'s removal branch, so this
 * listener simply never fires for it). Checking {@code entity.getRemovalReason() == KILLED} -- not just
 * "the entity left the level" -- is what distinguishes a genuine death from the SAME event firing for an
 * ordinary chunk unload ({@code UNLOADED_TO_CHUNK}/{@code UNLOADED_WITH_PLAYER}, which must never retire a
 * King that is merely temporarily out of range) or an administrative {@link KingManager#removeNearest}
 * discard ({@code DISCARDED}, which already removes the registry entry itself before discarding, so even
 * without this filter {@link KingDeathRetirement#isGenuineKingDeath} would already see it as not-genuine).
 *
 * <p>Dedupe is carried by {@link KingDeathRetirement#isGenuineKingDeath}: a record already removed (by an
 * earlier death callback, or by that administrative discard) is not "genuine" a second time.
 */
public final class KingDeathHandling {
    private KingDeathHandling() { }

    /**
     * MINECRAFT-425: capture a King's actual death-position cell now -- before any death-animation
     * movement -- for {@code PopulationDeathDecrement} to apply (once, only if this death is never
     * cancelled) via its own {@code EntityLeaveLevelEvent} listener, independently of this class's own
     * {@link #onEntityLeaveLevel} source-retirement/announcement logic below. Never touches storage
     * directly: see {@code PopulationDeathDecrement}'s own class doc for why a cancellable
     * {@code LivingDeathEvent} must not.
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        Faction faction = factionOfKing(entity);
        if (faction == null || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        // Review fix (round 1): discard any stale pending capture from an earlier, cancelled death attempt
        // first -- see PopulationDeathDecrement's own class doc. A King's eligibility never itself flips,
        // but every onLivingDeath listener does this uniformly so no future change here can silently
        // reopen that staleness window.
        PopulationDeathDecrement.take(entity.getUUID());
        LevelField field = PopulationSimulation.field(level, faction);
        PopulationDeathDecrement.capture(entity.getUUID(), faction, field.cellAt(entity.blockPosition()));
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        String removalReason = entity.getRemovalReason() == null ? null : entity.getRemovalReason().name();
        if (!KingDeathRetirement.isConfirmedDeathRemoval(removalReason)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Faction faction = factionOfKing(entity);
        if (faction == null) {
            return;
        }
        KingRecord record = level.getData(KingStorage.KING_REGISTRY.get());
        if (!KingDeathRetirement.isGenuineKingDeath(record.kings(), entity.getUUID())) {
            return;
        }
        record.remove(entity.getUUID());
        KingNaturalFounding.broadcast(level, NaturalFounding.deathAnnouncementText(faction));
    }

    private static Faction factionOfKing(Entity entity) {
        if (entity instanceof VillagerKingEntity) {
            return Faction.VILLAGER;
        }
        if (entity instanceof ZombieKingEntity) {
            return Faction.ZOMBIE;
        }
        if (entity instanceof SkeletonKingEntity) {
            return Faction.SKELETON;
        }
        return null;
    }
}
