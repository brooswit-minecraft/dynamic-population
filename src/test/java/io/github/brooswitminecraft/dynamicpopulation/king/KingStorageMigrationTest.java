package io.github.brooswitminecraft.dynamicpopulation.king;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import org.junit.jupiter.api.Test;

/**
 * Migration coverage for {@code KingRecord}'s schema gaining a {@code type}/faction field, analogous to
 * {@code ConfigVersionMigrationTest} -- here the "migration" is a pure default-on-absence rule
 * ({@link KingFactionMigration}) rather than a file rename, since the stored shape only ever gained an
 * optional field (additive, not a version bump). Deliberately exercises {@link KingFactionMigration} and
 * NOT {@link KingStorage} directly -- merely loading {@code KingStorage}'s class triggers NeoForge
 * {@code DeferredRegister} attachment-type registration, which needs a bootstrapped game registry
 * unavailable in a plain JUnit run; {@link KingFactionMigration} has no such dependency.
 */
class KingStorageMigrationTest {
    @Test
    void preMinecraft390RecordWithNoTypeTagDefaultsToVillager() {
        // A pre-390 entry had no "type" key at all (the format before this story) -- modeled here as null.
        assertEquals(Faction.VILLAGER, KingFactionMigration.readFaction(null));
    }

    @Test
    void explicitVillagerTypeRoundTrips() {
        assertEquals(Faction.VILLAGER, KingFactionMigration.readFaction("VILLAGER"));
    }

    @Test
    void explicitZombieTypeRoundTrips() {
        assertEquals(Faction.ZOMBIE, KingFactionMigration.readFaction("ZOMBIE"));
    }

    @Test
    void explicitSkeletonTypeRoundTrips() {
        assertEquals(Faction.SKELETON, KingFactionMigration.readFaction("SKELETON"));
    }

    @Test
    void unrecognizedTypeValueDefaultsToVillagerRatherThanThrowing() {
        assertEquals(Faction.VILLAGER, KingFactionMigration.readFaction("NOT_A_REAL_FACTION"));
    }
}
