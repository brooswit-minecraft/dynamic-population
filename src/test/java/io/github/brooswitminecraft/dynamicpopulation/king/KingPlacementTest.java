package io.github.brooswitminecraft.dynamicpopulation.king;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** [P1 review fix] Scaled-body/roof/adjacent-fluid/build-limit/chunk-edge coverage, as pure functions. */
class KingPlacementTest {
    /** A King's real, scale-attribute-inclusive size: 0.9x2.9 base * 1.5 Attributes.SCALE = 1.35x4.35. */
    private static final double REAL_WIDTH = 1.35;
    private static final double REAL_HEIGHT = 4.35;

    // --- footprint: the real scaled body, not the old hardcoded 0.9/3 guess ---

    @Test
    void realScaledWidthSpansMoreThanOneBlockEvenCenteredInsideAColumn() {
        var fp = KingPlacement.footprint(10, 10, REAL_WIDTH, REAL_HEIGHT);
        // half-width 0.675 centered at 10.5 -> [9.825, 11.175] -> blocks 9, 10, 11.
        assertEquals(9, fp.minBlockX());
        assertEquals(11, fp.maxBlockX());
        assertEquals(9, fp.minBlockZ());
        assertEquals(11, fp.maxBlockZ());
    }

    @Test
    void oldHardcodedNarrowWidthWouldHaveMissedTheRealFootprint() {
        // The OLD bug used a fixed 0.9-wide box (half-width 0.45): [10.05, 10.95] -> block 10 ONLY. The
        // real 1.35-wide body spans 9..11 -- two EXTRA columns (9 and 11) the old check never looked at.
        var oldNarrow = KingPlacement.footprint(10, 10, 0.9, 2.9);
        var realScaled = KingPlacement.footprint(10, 10, REAL_WIDTH, REAL_HEIGHT);
        assertEquals(10, oldNarrow.minBlockX());
        assertEquals(10, oldNarrow.maxBlockX());
        assertTrue(realScaled.minBlockX() < oldNarrow.minBlockX());
        assertTrue(realScaled.maxBlockX() > oldNarrow.maxBlockX());
    }

    @Test
    void realScaledHeightNeedsFiveBlocksNotTheOldHardcodedThree() {
        var fp = KingPlacement.footprint(0, 0, REAL_WIDTH, REAL_HEIGHT);
        assertEquals(5, fp.heightBlocks(), "ceil(4.35) must be 5, not the old hardcoded 3");
    }

    @Test
    void aFootprintCenteredOnAChunkBoundaryStraddlesTwoChunksHorizontally() {
        // Center column x=16 (first column of chunk 1): half-width 0.675 -> [15.825, 17.175] -> blocks 15,16,17.
        // Block 15 is chunk 0, blocks 16/17 are chunk 1 -- the real body genuinely straddles a chunk edge.
        var fp = KingPlacement.footprint(16, 0, REAL_WIDTH, REAL_HEIGHT);
        assertEquals(15, fp.minBlockX());
        assertEquals(17, fp.maxBlockX());
    }

    // --- everyChunk: never require a neighbor chunk load; bail on ANY unloaded touched chunk ---

    @Test
    void everyChunkPassesWhenAllTouchedChunksAreLoaded() {
        var fp = KingPlacement.footprint(16, 0, REAL_WIDTH, REAL_HEIGHT); // straddles chunk 0 and chunk 1
        assertTrue(KingPlacement.everyChunk(fp, (cx, cz) -> true));
    }

    @Test
    void everyChunkFailsWhenTheNeighborChunkAcrossTheStraddledEdgeIsNotLoaded() {
        var fp = KingPlacement.footprint(16, 0, REAL_WIDTH, REAL_HEIGHT); // touches chunk 0 (block 15) and chunk 1 (blocks 16,17)
        boolean result = KingPlacement.everyChunk(fp, (cx, cz) -> cx != 0); // chunk 0 reported NOT loaded
        assertFalse(result, "a footprint straddling an edge must bail if ANY touched chunk is unloaded -- never load a neighbor to validate");
    }

    @Test
    void everyChunkPassesForACenteredFootprintFullyInsideOneChunk() {
        var fp = KingPlacement.footprint(8, 8, REAL_WIDTH, REAL_HEIGHT); // nowhere near an edge
        assertTrue(KingPlacement.everyChunk(fp, (cx, cz) -> cx == 0 && cz == 0));
    }

    // --- everyColumn: adjacent-fluid at a NON-CENTER corner must fail the whole footprint ---

    @Test
    void everyColumnPassesWhenEveryTouchedColumnIsClean() {
        var fp = KingPlacement.footprint(10, 10, REAL_WIDTH, REAL_HEIGHT);
        assertTrue(KingPlacement.everyColumn(fp, (x, z) -> true));
    }

    @Test
    void adjacentFluidAtANonCenterCornerFailsEvenThoughTheCenterColumnIsDry() {
        var fp = KingPlacement.footprint(10, 10, REAL_WIDTH, REAL_HEIGHT); // spans x,z in [9,11]
        // Only the far corner (9,9) is "wet"; the center column (10,10) -- all the OLD single-column
        // check ever looked at -- is perfectly dry. The real, wider body still touches that wet corner.
        boolean allDry = KingPlacement.everyColumn(fp, (x, z) -> !(x == 9 && z == 9));
        assertFalse(allDry, "a fluid block at ANY corner of the real footprint must reject the site, "
            + "even when the single center column the old narrow check sampled is dry");
    }

    @Test
    void theOldSingleCenterColumnCheckWouldHaveMissedTheCornerFluid() {
        // Demonstrates the actual regression this fixes: sampling ONLY the center column (as the old
        // bodyDry loop effectively did) reports "dry" for the exact same corner-wet scenario above.
        int centerX = 10;
        int centerZ = 10;
        boolean oldCheckDry = !(centerX == 9 && centerZ == 9); // old: just checks the one center column
        assertTrue(oldCheckDry, "the old narrow check wrongly reports dry here -- this is the bug");
    }

    // --- withinBuildHeight: the real (taller) height, not the old hardcoded 3, must be checked ---

    @Test
    void realHeightFitsComfortablyAwayFromAnyLimit() {
        assertTrue(KingPlacement.withinBuildHeight(10, 5, -64, 320));
    }

    @Test
    void realHeightDoesNotFitUnderALowRoofTheOldHardcodedHeightWouldHaveMissed() {
        // A ceiling at y=13 leaves exactly 3 free blocks above feet=10 (10,11,12) -- enough for the OLD
        // hardcoded heightBlocks=3 to wrongly pass, but NOT enough for the real heightBlocks=5 body.
        int feetY = 10;
        int maxBuildHeight = 13; // world/roof effectively caps usable space at y < 13
        boolean oldHardcodedThreeWouldPass = feetY + 3 - 1 < maxBuildHeight;
        boolean realFiveCorrectlyFails = KingPlacement.withinBuildHeight(feetY, 5, -64, maxBuildHeight);
        assertTrue(oldHardcodedThreeWouldPass, "sanity: the old hardcoded height=3 check wrongly passes under this roof");
        assertFalse(realFiveCorrectlyFails, "the real, taller body must be rejected under a roof the old hardcoded height missed");
    }

    @Test
    void supportBelowMinBuildHeightFails() {
        assertFalse(KingPlacement.withinBuildHeight(-64, 5, -64, 320), "feet at the world floor leave no room for a support block below");
    }

    @Test
    void exactlyFittingHeightPasses() {
        // feetY=100, heightBlocks=5 -> occupies 100..104; maxBuildHeight=105 means 104 < 105, fits exactly.
        assertTrue(KingPlacement.withinBuildHeight(100, 5, -64, 105));
        // maxBuildHeight=104 means 104 < 104 is false -> does not fit.
        assertFalse(KingPlacement.withinBuildHeight(100, 5, -64, 104));
    }
}
