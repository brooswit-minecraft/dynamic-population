package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * MINECRAFT-390: keeps every {@link KingRecord.Entry} correlated with exactly one real, loaded King entity.
 *
 * <p>Two cases this covers: (1) migration -- a King record that existed before this story (or was loaded
 * while its chunk was unloaded, then that chunk loads later) has no entity yet, so one is spawned, using the
 * record's OWN id as the entity's uuid; (2) the ordinary steady state -- a record whose entity is already
 * loaded (vanilla entity persistence already reloaded it from the chunk's own saved entity data, since every
 * King entity is {@code persistenceRequired}) needs nothing done at all. {@link #missingEntities} is the
 * pure decision (no duplicate spawn is ever possible: a record is only ever "missing" when
 * {@code level.getEntity(id)} genuinely returns null for that id, checked fresh every call, never cached),
 * fully unit-testable without any real level/entity; {@link #sync} is the one Minecraft-wiring call site.
 */
public final class KingEntitySync {
    private KingEntitySync() { }

    /** Pure: which records have no corresponding loaded entity id. No Minecraft classes -- directly unit-testable. */
    public static List<KingRecord.Entry> missingEntities(List<KingRecord.Entry> records, Set<UUID> loadedEntityIds) {
        return records.stream().filter(entry -> !loadedEntityIds.contains(entry.id())).toList();
    }

    /** Overload taking a predicate instead of a materialized id set, for the real-level call site below. */
    public static List<KingRecord.Entry> missingEntities(List<KingRecord.Entry> records, Predicate<UUID> entityLoaded) {
        return records.stream().filter(entry -> !entityLoaded.test(entry.id())).toList();
    }

    /**
     * Pure (MINECRAFT-414 review fix, [P1]): of the records already known to have no currently loaded
     * entity (see {@link #missingEntities}), which ones genuinely need a FIRST-EVER spawn right now.
     *
     * <p>This is what actually ELIMINATES the duplicate-entity bug, not merely narrows it: a record with
     * {@link KingRecord.Entry#hasEntity()} already {@code true} is excluded UNCONDITIONALLY here, before
     * {@code anchorChunkLoaded} is even consulted. The earlier (position-refresh-only) fix only closed the
     * race within a single sync interval -- a King observed at A that wanders to B and has B's chunk
     * unload again, ALL BETWEEN two sync calls (the default interval is {@code king_seed_interval_ticks},
     * 20 ticks, not every tick), would never have had its anchor refreshed to B at all, so a still-loaded,
     * stale anchor A (e.g. inside spawn chunks) would read as "missing, anchor loaded" and wrongly spawn a
     * second entity. Once {@code hasEntity} is {@code true} for an id, THIS function never again returns
     * that id, no matter what any anchor position says -- the only way a {@code true}-{@code hasEntity}
     * King's entity ever reappears is vanilla's own entity persistence reloading it with its REAL current
     * chunk, which this code never races against because it never attempts to spawn a replacement at all.
     * {@code anchorChunkLoaded} only matters for a GENUINE legacy record ({@code hasEntity} still
     * {@code false}), where spawning for the first time ever is correct, not a duplicate.
     */
    public static List<KingRecord.Entry> needingFirstSpawn(List<KingRecord.Entry> missing,
            Predicate<KingRecord.Entry> anchorChunkLoaded) {
        return missing.stream().filter(entry -> !entry.hasEntity() && anchorChunkLoaded.test(entry)).toList();
    }

    /**
     * Refreshes every LOADED King's stored position to where it actually currently is (see
     * {@link KingRecord#updatePosition}'s own doc), then spawns exactly one entity for every record that
     * {@link #needingFirstSpawn} says genuinely needs its first-ever entity -- never a second entity for an
     * id already confirmed real (see that method's own doc for why this, not just the position refresh,
     * is what actually closes the duplicate-entity bug).
     */
    public static void sync(ServerLevel level) {
        KingRecord record = level.getData(KingStorage.KING_REGISTRY.get());
        Set<UUID> loadedIds = new HashSet<>();
        for (KingRecord.Entry entry : List.copyOf(record.kings())) {
            var entity = level.getEntity(entry.id());
            if (entity == null) {
                continue;
            }
            loadedIds.add(entry.id());
            BlockPos current = entity.blockPosition();
            if (current.getX() != entry.x() || current.getY() != entry.y() || current.getZ() != entry.z()) {
                record.updatePosition(entry.id(), current.getX(), current.getY(), current.getZ());
            }
        }
        List<KingRecord.Entry> missing = missingEntities(record.kings(), loadedIds);
        List<KingRecord.Entry> toSpawn = needingFirstSpawn(missing,
            entry -> level.hasChunk(entry.x() >> 4, entry.z() >> 4));
        for (KingRecord.Entry entry : toSpawn) {
            KingManager.spawnKingEntity(level, new BlockPos(entry.x(), entry.y(), entry.z()), entry.faction(), entry.id());
            record.markHasEntity(entry.id());
        }
    }
}
