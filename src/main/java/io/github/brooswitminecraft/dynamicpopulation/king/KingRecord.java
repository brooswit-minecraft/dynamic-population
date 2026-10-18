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
     * One King: its identity, block position, and faction. {@code id} is also the UUID of the real,
     * visible King entity this record corresponds to (see {@code KingEntitySync}) -- this record is not a
     * second, independent "source" of a King, it is the position/faction bookkeeping the pure
     * territory/uniqueness/seeding logic reads, correlated to the one real entity by this shared id.
     *
     * <p>{@code faction} defaults to {@link Faction#VILLAGER} when decoding a pre-MINECRAFT-390 record that
     * has no stored type at all (see {@link KingStorage}) -- every King that existed before this story was,
     * by definition, a Villager King.
     */
    public record Entry(UUID id, int x, int y, int z, Faction faction) { }

    public List<Entry> kings() {
        return kings;
    }

    public void add(Entry entry) {
        kings.add(entry);
    }

    public void remove(UUID id) {
        kings.removeIf(entry -> entry.id().equals(id));
    }
}
