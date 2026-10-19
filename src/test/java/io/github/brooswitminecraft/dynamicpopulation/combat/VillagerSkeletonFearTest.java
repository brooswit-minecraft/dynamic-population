package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicpopulation.combat.VillagerSkeletonFear.Action;

/**
 * Pure predicate coverage for {@code VillagerSkeletonFear#decide} (ticket scope item 3's "actual villager
 * fear wiring to Skeletons"). The Minecraft-wiring half ({@code onTick}, reading/writing a real
 * {@code Brain}'s memories) cannot be unit-tested without a running game -- same convention as
 * {@code FillagerTargetCompat#install}/{@code SkeletonVillagerHostility#install} -- but {@link #decide} is
 * the exact, complete state-transition decision {@code onTick} calls into and mechanically obeys, covering
 * both the memory value AND the ownership bookkeeping in one place.
 *
 * <p><b>Review fix (round 1):</b> the previous version split this into two separate predicates
 * ({@code shouldPreferSkeleton}/{@code shouldEraseOurs}) that {@code onTick} combined incorrectly --
 * {@code OUR_SKELETON}'s entry was cleared before the erase decision ever read it, making the erase branch
 * unreachable in practice even though both predicates individually tested green. The tests below exercise
 * {@link #decide} itself end-to-end for exactly the two sequences that bug would have gotten wrong:
 * disappearance/cleanup ({@link #erasesOnlyOurOwnEntryOnceTheSkeletonDisappears}) and preserving a
 * vanilla-owned replacement ({@link #neverPreemptsACloserVanillaOwnedHostile}).
 */
class VillagerSkeletonFearTest {
    // --- SET_SKELETON: "nearest wins".

    @Test
    void noCurrentHostilePrefersSkeletonRegardlessOfDistance() {
        assertEquals(Action.SET_SKELETON, VillagerSkeletonFear.decide(true, false, 0.0, 999.0, false));
    }

    @Test
    void closerSkeletonPreemptsAFartherCurrentHostile() {
        assertEquals(Action.SET_SKELETON, VillagerSkeletonFear.decide(true, true, 100.0, 50.0, false));
    }

    @Test
    void tiedDistanceDeliberatelyPrefersTheSkeleton() {
        assertEquals(Action.SET_SKELETON, VillagerSkeletonFear.decide(true, true, 50.0, 50.0, false));
    }

    @Test
    void closerSkeletonStillWinsEvenIfTheCurrentEntryWasAlreadyOurs() {
        // Re-asserting our own already-written Skeleton (or a closer new one) is still SET_SKELETON, not a
        // no-op -- the memory value itself must stay current every tick, not just the bookkeeping.
        assertEquals(Action.SET_SKELETON, VillagerSkeletonFear.decide(true, true, 50.0, 10.0, true));
    }

    // --- NOOP: never preempt a closer, non-ours (i.e. vanilla-owned) hostile.

    @Test
    void neverPreemptsACloserVanillaOwnedHostile() {
        assertEquals(Action.NOOP, VillagerSkeletonFear.decide(true, true, 50.0, 100.0, false));
    }

    // --- ERASE: only ever our own entry, only once the Skeleton is gone.

    @Test
    void erasesOnlyOurOwnEntryOnceTheSkeletonDisappears() {
        assertEquals(Action.ERASE, VillagerSkeletonFear.decide(false, true, 0.0, 0.0, true));
    }

    @Test
    void neverErasesAVanillaWrittenEntryEvenIfTheSkeletonIsGone() {
        assertEquals(Action.NOOP, VillagerSkeletonFear.decide(false, true, 0.0, 0.0, false));
    }

    @Test
    void noopWhenNothingIsPresentAndNothingIsOurs() {
        assertEquals(Action.NOOP, VillagerSkeletonFear.decide(false, false, 0.0, 0.0, false));
    }

    @Test
    void fearDistanceMatchesVanillasOwnZombieEntry() {
        // VillagerHostilesSensor.ACCEPTABLE_DISTANCE_FROM_HOSTILES.get(EntityType.ZOMBIE) == 8.0F.
        assertEquals(8.0F, VillagerSkeletonFear.SKELETON_FEAR_DISTANCE_BLOCKS);
    }
}
