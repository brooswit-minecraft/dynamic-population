package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;

/**
 * Pure natural-founding decision logic (MINECRAFT-414): the 200-tick gate, the one 1-in-10000 trial per
 * faction per boundary, random loaded-chunk/column candidate selection, the minimal placement/density
 * validity composition, and the success-announcement text. No Minecraft classes -- directly unit-testable
 * the same way {@link KingPlacement} is; {@code KingNaturalFounding} is the one Minecraft/NeoForge-facing
 * glue that drives this against a real {@code ServerLevel}.
 */
public final class NaturalFounding {
    private NaturalFounding() { }

    /** One already-loaded chunk's CHUNK coordinates (not block coordinates). */
    public record ChunkCoord(int x, int z) { }

    /** A candidate block column: world X/Z, picked within one loaded chunk. */
    public record Candidate(int blockX, int blockZ) { }

    /**
     * Whether {@code tick} is an eligible natural-founding boundary. A pure modulus test on the server's own
     * tick COUNT, never wall-clock time: it fires on exactly the ticks that are multiples of
     * {@code intervalTicks}, with no catch-up burst for a boundary that was skipped -- there is no queued
     * state here at all, a missed boundary is simply never retried.
     */
    public static boolean eligibleTick(long tick, int intervalTicks) {
        return intervalTicks > 0 && tick % intervalTicks == 0;
    }

    /**
     * One independent 1-in-{@code chanceDenominator} random trial. The caller invokes this AT MOST ONCE per
     * faction per eligible boundary -- this function itself has no notion of "per chunk" at all, it is just
     * the coin flip, so more loaded chunks can never raise the odds.
     */
    public static boolean trial(RandomGenerator random, int chanceDenominator) {
        return random.nextInt(chanceDenominator) == 0;
    }

    /**
     * Pick one random currently-loaded chunk, then a random X/Z column within it. Empty when no chunk is
     * loaded at all -- the caller treats that exactly like any other invalid site: skip until the next
     * scheduled trial, never retry within this same boundary.
     */
    public static Optional<Candidate> pickCandidate(List<ChunkCoord> loadedChunks, RandomGenerator random) {
        if (loadedChunks.isEmpty()) {
            return Optional.empty();
        }
        ChunkCoord chunk = loadedChunks.get(random.nextInt(loadedChunks.size()));
        int localX = random.nextInt(16);
        int localZ = random.nextInt(16);
        return Optional.of(new Candidate(chunk.x() * 16 + localX, chunk.z() * 16 + localZ));
    }

    /**
     * Whether the candidate site's target population density for this faction permits natural founding: the
     * field must be genuinely READABLE (not an unloaded anchor or corrupt data masquerading as a zero -- see
     * {@code LevelField#readable}) AND its value must be exactly 0. Other factions' density is irrelevant;
     * the caller passes only this faction's own field reading.
     */
    public static boolean densityPermits(boolean readable, int density) {
        return readable && density == 0;
    }

    /**
     * Whether the candidate placement site itself is valid: within world build-height bounds, a dry
     * (non-fluid, non-waterlogged) supporting block sturdy enough to stand on, and the King's own spawn body
     * both dry and collision-free. All four are required; any single failure means skip this boundary's
     * trial entirely -- no retry with a different site.
     */
    public static boolean siteValid(boolean withinBuildHeight, boolean supportSturdyAndDry, boolean bodyDry,
            boolean bodyCollisionFree) {
        return withinBuildHeight && supportSturdyAndDry && bodyDry && bodyCollisionFree;
    }

    /**
     * Whether natural founding may even attempt a trial for this faction right now: only when NO live King
     * entity of the SAME faction exists in any currently loaded chunk. A loaded King of a DIFFERENT faction
     * never blocks (see {@code KingManager}'s cross-faction coexistence policy, unchanged here), and neither
     * does an unloaded King of the same faction -- this is loaded-only eligibility, not global uniqueness: an
     * old King can still legitimately return when its own previously-unloaded chunk loads back in.
     */
    public static boolean eligibleFaction(boolean anyLoadedKingOfSameFactionPresent) {
        return !anyLoadedKingOfSameFactionPresent;
    }

    /** Capitalized faction name used as the King's "kind" in the success announcement, e.g. "Villager". */
    public static String kindName(Faction faction) {
        String name = faction.name();
        return name.substring(0, 1) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The exactly-once success announcement text for a natural founding of {@code faction}. Deliberately
     * carries NO location information (no coordinates/chunk/biome/direction/distance) -- the ticket requires
     * naming only the King's kind.
     */
    public static String spawnAnnouncementText(Faction faction) {
        return "A " + kindName(faction) + " King has spawned.";
    }

    /**
     * The exactly-once death announcement text for a King of {@code faction}. Same no-location constraint as
     * {@link #spawnAnnouncementText}: no coordinates/chunk/biome/direction/distance, and no killer either.
     */
    public static String deathAnnouncementText(Faction faction) {
        return "A " + kindName(faction) + " King has died.";
    }
}
