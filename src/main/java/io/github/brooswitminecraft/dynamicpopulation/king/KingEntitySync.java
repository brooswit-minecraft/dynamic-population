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
     * Spawn exactly one entity for every King record currently missing its entity in {@code level} --
     * AFTER first refreshing every LOADED King's stored position to where it actually currently is (see
     * {@link KingRecord#updatePosition}'s own doc for the duplicate-entity bug this refresh fixes: a King
     * that wandered away from its frozen founding/last-synced position could otherwise be wrongly
     * "respawned" at that stale spot once its real, current chunk unloads, if the stale spot's chunk
     * happens to still be loaded).
     *
     * <p>Only respawns a record whose (now-current) CHUNK IS CURRENTLY LOADED: {@code level.getEntity(id)}
     * only ever finds entities belonging to loaded chunks, so an unloaded-chunk record would otherwise look
     * "missing" purely because nothing has loaded it back in yet -- spawning a second entity for it would
     * be exactly the duplicate-entity bug this ticket forbids. Vanilla's own entity persistence always
     * reloads a {@code persistenceRequired} entity together with its chunk, with no lazy delay, so once a
     * King's chunk IS loaded, "no entity found" really does mean "never spawned yet" (a legacy record, or
     * one whose stale anchor was never refreshed because it was never observed loaded since).
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
        for (KingRecord.Entry entry : missingEntities(record.kings(), loadedIds)) {
            if (level.hasChunk(entry.x() >> 4, entry.z() >> 4)) {
                KingManager.spawnKingEntity(level, new BlockPos(entry.x(), entry.y(), entry.z()), entry.faction(), entry.id());
            }
        }
    }
}
