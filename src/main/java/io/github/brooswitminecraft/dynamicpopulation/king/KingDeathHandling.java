package io.github.brooswitminecraft.dynamicpopulation.king;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.VillagerKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * MINECRAFT-414: on an ACTUAL King death (any faction, naturally founded OR command-settled via
 * {@link KingManager#settle} -- deaths are not scoped to natural founding, unlike the spawn announcement),
 * announce it (type-only, no location, matching {@link NaturalFounding#deathAnnouncementText}) and retire
 * its {@link KingRecord.Entry} so {@link KingEntitySync} can never resurrect it and registry-driven seeding
 * stops reading from a dead source. Deliberately narrow: this does NOT touch the faction's population
 * density field at all (only the King's own source/registry record), and does NOT add any King-vs-King
 * hostility/targeting behavior -- that is explicitly a separate follow-up, out of scope here.
 *
 * <p>Registered at {@link EventPriority#LOWEST} and gated on {@code !event.isCanceled()}: by the time the
 * lowest-priority listener runs, every other mod (e.g. a totem of undying handler) has already had its
 * chance to cancel the death, so this only ever reacts to a death that is actually going to happen -- a
 * genuinely completed death, never a preventable pre-death event.
 *
 * <p>Dedupe and scope are both carried by the single {@link KingDeathRetirement#isGenuineKingDeath} check:
 * a record already removed (by an earlier death callback, or by an administrative
 * {@link KingManager#removeNearest} discard that ran first) is not "genuine" a second time, so unload,
 * despawn/removal, reload, {@link KingEntitySync} migration, and registry reconciliation -- none of which
 * ever fire {@code LivingDeathEvent} in the first place -- have nothing to dedupe against even in the
 * hypothetical case they did.
 */
public final class KingDeathHandling {
    private KingDeathHandling() { }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
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
