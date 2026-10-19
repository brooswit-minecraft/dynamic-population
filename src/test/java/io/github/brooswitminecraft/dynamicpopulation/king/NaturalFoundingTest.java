package io.github.brooswitminecraft.dynamicpopulation.king;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import org.junit.jupiter.api.Test;

/** Pure natural-founding decision logic coverage (MINECRAFT-414's own required test list), no Minecraft classes. */
class NaturalFoundingTest {

    // --- eligibleTick: the 200-tick gate ---

    @Test
    void tickZeroIsEligible() {
        assertTrue(NaturalFounding.eligibleTick(0, 200));
    }

    @Test
    void exactMultipleOfIntervalIsEligible() {
        assertTrue(NaturalFounding.eligibleTick(400, 200));
        assertTrue(NaturalFounding.eligibleTick(1000, 200));
    }

    @Test
    void nonMultipleIsNotEligible() {
        assertFalse(NaturalFounding.eligibleTick(199, 200));
        assertFalse(NaturalFounding.eligibleTick(201, 200));
    }

    @Test
    void aSkippedBoundaryIsNeverRetried() {
        // There is no queued/catch-up state at all: a tick just one past a missed boundary is simply not
        // eligible, with no notion of "owing" an attempt from the boundary that was skipped.
        assertFalse(NaturalFounding.eligibleTick(601, 200));
        assertTrue(NaturalFounding.eligibleTick(600, 200));
    }

    // --- trial: exactly one 1-in-N coin flip, independent of chunk count ---

    @Test
    void winningTrialRollsZero() {
        Random fixed = new Random() {
            @Override
            public int nextInt(int bound) {
                assertEquals(10000, bound);
                return 0;
            }
        };
        assertTrue(NaturalFounding.trial(fixed, 10000));
    }

    @Test
    void losingTrialRollsNonzero() {
        Random fixed = new Random() {
            @Override
            public int nextInt(int bound) {
                return 1;
            }
        };
        assertFalse(NaturalFounding.trial(fixed, 10000));
    }

    @Test
    void onlyOneRandomDrawIsConsumedPerTrialRegardlessOfCallerLoadedChunkCount() {
        // trial() itself has no notion of "how many chunks are loaded" -- it draws exactly once. The
        // glue layer is what guarantees one call per faction per boundary; this test pins that trial()
        // never internally loops or re-draws.
        int[] calls = {0};
        Random counting = new Random() {
            @Override
            public int nextInt(int bound) {
                calls[0]++;
                return 5;
            }
        };
        NaturalFounding.trial(counting, 10000);
        assertEquals(1, calls[0]);
    }

    // --- pickCandidate: one random loaded chunk, one random column within it ---

    @Test
    void emptyLoadedChunkListYieldsNoCandidate() {
        assertTrue(NaturalFounding.pickCandidate(List.of(), new Random()).isEmpty());
    }

    @Test
    void candidateColumnFallsWithinThePickedChunk() {
        var chunk = new NaturalFounding.ChunkCoord(3, -2);
        Optional<NaturalFounding.Candidate> candidate = NaturalFounding.pickCandidate(List.of(chunk), new Random(42));
        assertTrue(candidate.isPresent());
        int blockX = candidate.get().blockX();
        int blockZ = candidate.get().blockZ();
        assertTrue(blockX >= chunk.x() * 16 && blockX < chunk.x() * 16 + 16);
        assertTrue(blockZ >= chunk.z() * 16 && blockZ < chunk.z() * 16 + 16);
    }

    @Test
    void moreLoadedChunksOnlyWidensChoiceNeverForcesASpecificOne() {
        var a = new NaturalFounding.ChunkCoord(0, 0);
        var b = new NaturalFounding.ChunkCoord(100, 100);
        // A deterministic random that always picks index 0 must always return chunk "a"'s column.
        Random alwaysFirst = new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        var candidate = NaturalFounding.pickCandidate(List.of(a, b), alwaysFirst);
        assertEquals(0, candidate.get().blockX());
        assertEquals(0, candidate.get().blockZ());
    }

    // --- densityPermits: zero succeeds, positive rejects, unreadable rejects ---

    @Test
    void zeroReadableDensityPermits() {
        assertTrue(NaturalFounding.densityPermits(true, 0));
    }

    @Test
    void positiveReadableDensityRejects() {
        assertFalse(NaturalFounding.densityPermits(true, 1));
    }

    @Test
    void unreadableDensityRejectsEvenIfTheRawValueIsZero() {
        // get() returning 0 for an unloaded anchor/corrupt cell is NOT a verified zero.
        assertFalse(NaturalFounding.densityPermits(false, 0));
    }

    // --- siteValid: all four placement conditions required ---

    @Test
    void allFourConditionsTruePermitsPlacement() {
        assertTrue(NaturalFounding.siteValid(true, true, true, true));
    }

    @Test
    void outOfBuildHeightRejects() {
        assertFalse(NaturalFounding.siteValid(false, true, true, true));
    }

    @Test
    void wetOrUnsturdySupportRejects() {
        assertFalse(NaturalFounding.siteValid(true, false, true, true));
    }

    @Test
    void wetBodyRejects() {
        assertFalse(NaturalFounding.siteValid(true, true, false, true));
    }

    @Test
    void obstructedBodyRejects() {
        assertFalse(NaturalFounding.siteValid(true, true, true, false));
    }

    // --- eligibleFaction: loaded-same-faction suppression, nothing else ---

    @Test
    void noLoadedSameFactionKingIsEligible() {
        assertTrue(NaturalFounding.eligibleFaction(false));
    }

    @Test
    void aLoadedSameFactionKingSuppresses() {
        assertFalse(NaturalFounding.eligibleFaction(true));
    }

    // --- announcement text: location-free, correctly typed per faction ---

    @Test
    void villagerSpawnAnnouncementNamesVillagerKingOnly() {
        assertEquals("A Villager King has spawned.", NaturalFounding.spawnAnnouncementText(Faction.VILLAGER));
    }

    @Test
    void zombieSpawnAnnouncementNamesZombieKingOnly() {
        assertEquals("A Zombie King has spawned.", NaturalFounding.spawnAnnouncementText(Faction.ZOMBIE));
    }

    @Test
    void skeletonSpawnAnnouncementNamesSkeletonKingOnly() {
        assertEquals("A Skeleton King has spawned.", NaturalFounding.spawnAnnouncementText(Faction.SKELETON));
    }

    @Test
    void villagerDeathAnnouncementNamesVillagerKingOnly() {
        assertEquals("A Villager King has died.", NaturalFounding.deathAnnouncementText(Faction.VILLAGER));
    }

    @Test
    void zombieDeathAnnouncementNamesZombieKingOnly() {
        assertEquals("A Zombie King has died.", NaturalFounding.deathAnnouncementText(Faction.ZOMBIE));
    }

    @Test
    void skeletonDeathAnnouncementNamesSkeletonKingOnly() {
        assertEquals("A Skeleton King has died.", NaturalFounding.deathAnnouncementText(Faction.SKELETON));
    }

    @Test
    void announcementTextNeverContainsDigits() {
        // A cheap but effective guard against accidentally interpolating coordinates into the message.
        for (Faction faction : Faction.values()) {
            String spawn = NaturalFounding.spawnAnnouncementText(faction);
            String death = NaturalFounding.deathAnnouncementText(faction);
            assertFalse(spawn.chars().anyMatch(Character::isDigit), "spawn announcement must carry no location: " + spawn);
            assertFalse(death.chars().anyMatch(Character::isDigit), "death announcement must carry no location: " + death);
        }
    }
}
