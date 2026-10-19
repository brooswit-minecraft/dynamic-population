package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure predicate coverage for {@code VillagerSkeletonFear}'s decision core (ticket scope item 3's "actual
 * villager fear wiring to Skeletons"). The Minecraft-wiring half ({@code onTick}, reading/writing a real
 * {@code Brain}'s memories) cannot be unit-tested without a running game -- same convention as
 * {@code FillagerTargetCompat#install}/{@code SkeletonVillagerHostility#install} -- but
 * {@link VillagerSkeletonFear#shouldPreferSkeleton} and {@link VillagerSkeletonFear#shouldEraseOurs} are the
 * exact two decisions {@code onTick} calls into, and both are plain booleans/doubles.
 */
class VillagerSkeletonFearTest {
    // --- shouldPreferSkeleton: "nearest wins".

    @Test
    void noCurrentHostilePrefersSkeletonRegardlessOfDistance() {
        assertTrue(VillagerSkeletonFear.shouldPreferSkeleton(false, 0.0, 999.0));
    }

    @Test
    void closerSkeletonPreemptsAFartherCurrentHostile() {
        assertTrue(VillagerSkeletonFear.shouldPreferSkeleton(true, 100.0, 50.0));
    }

    @Test
    void fartherSkeletonDoesNotPreemptACloserCurrentHostile() {
        assertFalse(VillagerSkeletonFear.shouldPreferSkeleton(true, 50.0, 100.0));
    }

    @Test
    void tiedDistanceDeliberatelyPrefersTheSkeleton() {
        assertTrue(VillagerSkeletonFear.shouldPreferSkeleton(true, 50.0, 50.0));
    }

    // --- shouldEraseOurs: only ever clear OUR OWN previously-written entry.

    @Test
    void erasesOurEntryOnceTheSkeletonNoLongerQualifies() {
        assertTrue(VillagerSkeletonFear.shouldEraseOurs(false, true));
    }

    @Test
    void neverErasesAVanillaWrittenEntryEvenIfTheSkeletonIsGone() {
        assertFalse(VillagerSkeletonFear.shouldEraseOurs(false, false));
    }

    @Test
    void neverErasesAnythingWhileTheSkeletonStillQualifies() {
        assertFalse(VillagerSkeletonFear.shouldEraseOurs(true, true));
        assertFalse(VillagerSkeletonFear.shouldEraseOurs(true, false));
    }

    @Test
    void fearDistanceMatchesVanillasOwnZombieEntry() {
        // VillagerHostilesSensor.ACCEPTABLE_DISTANCE_FROM_HOSTILES.get(EntityType.ZOMBIE) == 8.0F.
        assertEquals(8.0F, VillagerSkeletonFear.SKELETON_FEAR_DISTANCE_BLOCKS);
    }
}
