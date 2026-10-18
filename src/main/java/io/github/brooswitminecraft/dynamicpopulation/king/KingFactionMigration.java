package io.github.brooswitminecraft.dynamicpopulation.king;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;

/**
 * Pure migration logic for {@link KingRecord.Entry}'s new {@code type}/faction field (MINECRAFT-390): no
 * NeoForge/registry classes at all, unlike {@link KingStorage} itself (whose mere class-load triggers
 * {@code DeferredRegister} registry bootstrap) -- so this one piece of {@code KingStorage}'s decode logic
 * is directly unit-testable with plain JUnit, the same way {@code ConfigVersionMigration} is pure-testable
 * apart from the FML config machinery around it.
 *
 * <p>A pre-MINECRAFT-390 record has no stored {@code type} at all (the field didn't exist yet); every King
 * that existed before this story IS a Villager King by definition, so defaulting a missing OR unrecognized
 * value to {@link Faction#VILLAGER} is the only correct migration, never a guess.
 */
public final class KingFactionMigration {
    private KingFactionMigration() { }

    public static Faction readFaction(String stored) {
        if (stored == null) {
            return Faction.VILLAGER;
        }
        try {
            return Faction.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return Faction.VILLAGER;
        }
    }
}
