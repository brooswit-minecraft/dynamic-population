package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One level's King registry: every Villager King currently settled in that level, persisted via
 * {@link KingStorage}, a NeoForge LEVEL data attachment (mutable in place, like {@code CellFieldRecord.Loaded},
 * so a held reference stays valid across reads without re-fetching the attachment).
 */
public final class KingRecord {
    private final List<Entry> kings = new ArrayList<>();

    /** A single King: its identity and block position within the level this record belongs to. */
    public record Entry(UUID id, int x, int y, int z) { }

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
