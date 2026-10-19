package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;

/**
 * One level's King registry: every King (of any faction, as of MINECRAFT-390) currently settled in that
 * level, persisted via {@link KingStorage}, a NeoForge LEVEL data attachment (mutable in place, like
 * {@code CellFieldRecord.Loaded}, so a held reference stays valid across reads without re-fetching the
 * attachment).
 */
public final class KingRecord {
    private final List<Entry> kings = new ArrayList<>();

    /**
     * One King: its identity, block position, faction, and (MINECRAFT-414 review fix) whether a real
     * entity has EVER been confirmed to exist for this id. {@code id} is also the UUID of the real,
     * visible King entity this record corresponds to (see {@code KingEntitySync}) -- this record is not a
     * second, independent "source" of a King, it is the position/faction bookkeeping the pure
     * territory/uniqueness/seeding logic reads, correlated to the one real entity by this shared id.
     *
     * <p>{@code faction} defaults to {@link Faction#VILLAGER} when decoding a pre-MINECRAFT-390 record that
     * has no stored type at all (see {@link KingStorage}) -- every King that existed before this story was,
     * by definition, a Villager King.
     *
     * <p>{@code hasEntity}: {@code false} ONLY for a genuine pre-MINECRAFT-390 legacy record, which really
     * never had a real entity constructed for it (the original King mechanism was a level-attachment-only
     * marker, no entity at all) -- every OTHER way an {@code Entry} is ever created (a fresh
     * {@code KingManager.settle}, a fresh natural founding, or {@code KingEntitySync}'s own one-time legacy
     * migration spawn) only ever happens AFTER that call's own entity insertion is confirmed, so
     * {@code hasEntity} is {@code true} from the moment such an entry first exists. Once {@code true}, it
     * is NEVER set back to {@code false} by anything in this codebase -- that permanence is exactly what
     * lets {@code KingEntitySync#sync} refuse to ever spawn a second entity for an id it has already
     * confirmed real, no matter how stale {@code x/y/z} or how coincidentally loaded some unrelated chunk
     * is. See {@code KingEntitySync#needsFirstSpawn}'s own doc for the duplicate-entity bug this closes.
     */
    public record Entry(UUID id, int x, int y, int z, Faction faction, boolean hasEntity) { }

    public List<Entry> kings() {
        return kings;
    }

    public void add(Entry entry) {
        kings.add(entry);
    }

    public void remove(UUID id) {
        kings.removeIf(entry -> entry.id().equals(id));
    }

    /**
     * MINECRAFT-414 bug fix: replace-in-place the stored position of the King {@code id}, keeping its id
     * and faction unchanged. A no-op if {@code id} is not present (e.g. it was retired between the caller
     * reading its entry and calling this).
     *
     * <p>Why this exists: {@link KingEntitySync#sync} previously left an entry's position frozen at
     * whatever it was when the entry was created (founding, or an earlier migration respawn), and used that
     * stale position's chunk-loaded state to decide whether a missing entity should be respawned. A King
     * that actually WANDERS (vanilla wander AI, inherited by {@code ZombieKingEntity}/
     * {@code SkeletonKingEntity}) can move far from that frozen anchor; if its CURRENT chunk later unloads
     * while the stale founding chunk happens to still be loaded (e.g. inside spawn chunks), the old code
     * read "entity missing, anchor chunk loaded" and spawned a SECOND entity with the same uuid at the
     * stale position -- a real duplicate/resurrection bug, not a false positive. Calling this every sync
     * tick a King's entity IS observed loaded keeps the stored position current, so a later tick's
     * loaded-chunk check reflects where the King actually now is, not a stale founding spot. This narrows
     * (does not eliminate -- a King could still move again in the single tick between the last update and
     * its new chunk unloading) the race to one sync interval.
     */
    public void updatePosition(UUID id, int x, int y, int z) {
        for (int i = 0; i < kings.size(); i++) {
            Entry entry = kings.get(i);
            if (entry.id().equals(id)) {
                // Called only when the entity IS currently observed loaded, so hasEntity is forced true
                // here too (defensive: by invariant it already is, for every entry this is ever called on).
                kings.set(i, new Entry(id, x, y, z, entry.faction(), true));
                return;
            }
        }
    }

    /**
     * Mark {@code id} as having a confirmed real entity, without changing its stored position -- used by
     * {@link KingEntitySync}'s one-time legacy migration spawn, immediately after that spawn is confirmed to
     * have actually inserted an entity. A no-op if {@code id} is not present.
     */
    public void markHasEntity(UUID id) {
        for (int i = 0; i < kings.size(); i++) {
            Entry entry = kings.get(i);
            if (entry.id().equals(id)) {
                kings.set(i, new Entry(id, entry.x(), entry.y(), entry.z(), entry.faction(), true));
                return;
            }
        }
    }
}
