package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.entity.monster.Bogged;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.entity.monster.WitherSkeleton;

/**
 * MINECRAFT-426 scope item 2 audit, machine-checked: which {@code Skeleton}-family subclasses are matched by
 * {@code SkeletonVillagerHostility}'s join-time {@code instanceof Skeleton} check. The production class has
 * no Minecraft-free pure predicate to call directly (unlike {@code FillagerTargetCompat#specFor}, the actual
 * decision here is a one-line {@code instanceof}), so this pins the exact class-hierarchy facts the
 * production check depends on, using real vanilla {@code Class} tokens -- no entity construction, no level,
 * no registry bootstrap needed for {@code isAssignableFrom}.
 */
class SkeletonVillagerHostilityTest {
    @Test
    void literalSkeletonIsAssignableToItself() {
        assertTrue(Skeleton.class.isAssignableFrom(Skeleton.class),
            "the literal Skeleton class is the one type SkeletonVillagerHostility's instanceof check matches");
    }

    @Test
    void strayDoesNotExtendSkeleton() {
        assertTrue(!Skeleton.class.isAssignableFrom(Stray.class),
            "Stray extends AbstractSkeleton directly, not Skeleton -- out of scope, confirmed by class hierarchy");
    }

    @Test
    void witherSkeletonDoesNotExtendSkeleton() {
        assertTrue(!Skeleton.class.isAssignableFrom(WitherSkeleton.class),
            "WitherSkeleton extends AbstractSkeleton directly, not Skeleton -- out of scope, confirmed by class hierarchy");
    }

    @Test
    void boggedDoesNotExtendSkeleton() {
        assertTrue(!Skeleton.class.isAssignableFrom(Bogged.class),
            "Bogged extends AbstractSkeleton directly, not Skeleton -- out of scope, confirmed by class hierarchy");
    }
}
