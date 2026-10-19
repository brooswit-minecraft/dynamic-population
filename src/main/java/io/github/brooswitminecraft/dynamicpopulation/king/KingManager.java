package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.List;
import java.util.UUID;

import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.VillagerKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.DynamicPopulationEntities;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;

/**
 * NeoForge-facing glue for the King mechanism (AC4, generalized to all three factions by MINECRAFT-390):
 * bridges {@link KingStorage}'s LEVEL attachment registry to the real level and (new in MINECRAFT-390)
 * spawns the real, visible King entity a settle actually creates.
 *
 * <p><b>Explicit placement has no eligibility gate (MINECRAFT-424):</b> {@link #settle} no longer checks
 * sky-light/surface-depth territory preference or same-faction spacing -- a player/command King placement
 * at any position, for any faction, including underground, in the dark, in a non-plains biome, or next to
 * another King of the same faction, always proceeds straight to entity insertion. These checks (and the
 * {@code king_min_sky_light}/{@code king_max_depth_below_surface_blocks}/{@code king_uniqueness_radius_cells}
 * config keys and the pure {@code KingTerritory}/{@code KingUniqueness} logic that implemented them) were
 * removed outright, not merely bypassed, since nothing else in this codebase read them once {@code settle}
 * stopped calling them -- natural founding ({@link KingNaturalFounding}) never routed through these checks
 * in the first place and is completely unaffected by this change.
 */
public final class KingManager {
    private KingManager() { }

    public static List<KingRecord.Entry> kings(ServerLevel level) {
        return level.getData(KingStorage.KING_REGISTRY.get()).kings();
    }

    public static List<KingRecord.Entry> kings(ServerLevel level, Faction faction) {
        return kings(level).stream().filter(entry -> entry.faction() == faction).toList();
    }

    /** Outcome of a settle attempt. */
    public enum SettleResult {
        SETTLED, INSERTION_FAILED
    }

    /**
     * Attempt to found a new King of {@code faction} at {@code pos}: spawns the real, visible, persistent
     * King entity unconditionally (MINECRAFT-424 -- no territory/uniqueness eligibility gate) -- the
     * record's id IS the spawned entity's UUID (see {@link KingRecord.Entry}'s own doc), so there is
     * exactly one source of truth correlating the two, never two independently-created ids.
     *
     * <p>[P1 review fix, round 1] {@link #finalizeKingEntity} never checked whether {@code addFreshEntity}
     * actually succeeded, so a registry {@link KingRecord.Entry} with {@code hasEntity} true could
     * previously be committed for an entity that was never really inserted -- a phantom source
     * seeding/counting as a King forever, with no way to retry (the ticket's own original description
     * already required "check entity insertion succeeds before committing a source"). {@code settle} now
     * commits a record only when {@link #spawnKingEntity} reports {@code true}.
     *
     * <p>[P1 review fix, round 2 -- the dedicated-server smoke test's own real, false-NEGATIVE catch]: the
     * FIRST version of this fix checked success via a fresh {@code level.getEntity(king.getUUID()) != null}
     * lookup immediately after spawning, reasoning (by analogy with {@code KingEntitySync}) that this was
     * the same "is it really there" signal. It was NOT equivalent, and produced a confirmed false negative
     * in a real dedicated server (the entity genuinely was added -- other King entities of the identical
     * type summon and persist fine in the same run -- yet that immediate re-lookup returned {@code null}
     * right after a successful insertion in this exact command-handler call path). Root cause: an entity
     * is added to the level's {@code knownUuids}/section storage SYNCHRONOUSLY inside
     * {@code addEntityWithoutEvent} (confirmed by reading the actual mapped
     * {@code PersistentEntitySectionManager} source) regardless of outcome, but {@code ServerLevel#getEntity
     * (UUID)} reads a SEPARATE "visible"/tracked lookup ({@code EntityLookup}, populated only when
     * {@code startTracking} runs for an {@code isAccessible()} chunk visibility) that can momentarily lag
     * that same-tick, in a command-handler call path specifically. {@code Level#addFreshEntity} itself
     * ALREADY returns the correct, authoritative success boolean (it IS {@code addEntityWithoutEvent}'s
     * own return value) -- using THAT directly, instead of re-deriving success from a second, differently
     * timed lookup, is what actually fixes this; see {@link #finalizeKingEntity}.
     */
    public static SettleResult settle(ServerLevel level, BlockPos pos, Faction faction) {
        UUID uuid = UUID.randomUUID();
        if (!spawnKingEntity(level, pos, faction, uuid)) {
            return SettleResult.INSERTION_FAILED;
        }
        level.getData(KingStorage.KING_REGISTRY.get())
            .add(new KingRecord.Entry(uuid, pos.getX(), pos.getY(), pos.getZ(), faction, true));
        return SettleResult.SETTLED;
    }

    /**
     * Constructs a King entity for {@code faction}, WITHOUT any of {@link #finalizeKingEntity}'s
     * positioning/insertion side effects -- just the bare, unattached {@link Mob}. Used by
     * {@link #finalizeKingEntity}/{@link #spawnKingEntity} themselves, and (MINECRAFT-414 review fix) by
     * natural founding to read the real, scale-attribute-inclusive {@code getDimensions(getPose())} off a
     * genuine instance BEFORE committing to a site, then reused as the actual entity inserted if the site
     * validates -- never a second, independently-constructed instance.
     */
    static Mob newKingEntity(ServerLevel level, Faction faction) {
        return switch (faction) {
            case VILLAGER -> new VillagerKingEntity(DynamicPopulationEntities.VILLAGER_KING.get(), level);
            case ZOMBIE -> new ZombieKingEntity(DynamicPopulationEntities.ZOMBIE_KING.get(), level);
            case SKELETON -> new SkeletonKingEntity(DynamicPopulationEntities.SKELETON_KING.get(), level);
        };
    }

    /**
     * Spawns the real King entity for a record, with the GIVEN uuid (never a freshly-generated one) so the
     * entity and its {@link KingRecord.Entry} always share one identity. Used both by a fresh
     * {@link #settle} and by {@code KingEntitySync}'s migration path for a pre-390 record that has no
     * entity yet. Returns whether insertion actually succeeded (see {@link #finalizeKingEntity}).
     */
    static boolean spawnKingEntity(ServerLevel level, BlockPos pos, Faction faction, UUID uuid) {
        return finalizeKingEntity(level, newKingEntity(level, faction), pos, uuid);
    }

    /**
     * The positioning/insertion half of {@link #spawnKingEntity}, factored out so natural founding can
     * reuse an already-constructed probe entity instead of building a second one. Returns
     * {@code level.addFreshEntity}'s OWN boolean result directly -- the authoritative, same-call success
     * signal, confirmed (the hard way, via a real dedicated-server false negative) to be the correct check,
     * unlike re-deriving success from a subsequent {@code level.getEntity(uuid)} lookup (see {@link #settle}'s
     * own doc for why that differently-timed lookup is NOT an equivalent signal in this call path).
     */
    static boolean finalizeKingEntity(ServerLevel level, Mob king, BlockPos pos, UUID uuid) {
        king.setUUID(uuid);
        king.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        king.setPersistenceRequired();
        king.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.COMMAND, null);
        return level.addFreshEntity(king);
    }

    /** Remove the King nearest to {@code pos} in {@code level}, if one is within {@code withinBlocks}; also removes its entity. */
    public static boolean removeNearest(ServerLevel level, BlockPos pos, double withinBlocks) {
        KingRecord record = level.getData(KingStorage.KING_REGISTRY.get());
        KingRecord.Entry nearest = null;
        double nearestDistSq = withinBlocks * withinBlocks;
        for (KingRecord.Entry entry : record.kings()) {
            double distSq = pos.distSqr(new BlockPos(entry.x(), entry.y(), entry.z()));
            if (distSq <= nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entry;
            }
        }
        if (nearest == null) {
            return false;
        }
        record.remove(nearest.id());
        var entity = level.getEntity(nearest.id());
        if (entity != null) {
            entity.discard();
        }
        return true;
    }
}
