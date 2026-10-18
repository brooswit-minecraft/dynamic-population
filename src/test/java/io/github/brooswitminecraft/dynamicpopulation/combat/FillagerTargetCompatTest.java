package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Illusioner;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Spider;

/**
 * {@link FillagerTargetCompat#specFor} is the exact production decision {@code onJoin} calls into, driven
 * here with real vanilla {@code Class} LITERALS -- no entity construction, no level, no registry bootstrap --
 * which is enough to exercise it without a running game. Each case below is sourced from decompiled 1.21.1
 * vanilla sources (see {@link FillagerTargetCompat}'s own javadoc for the exact lines); this is the coverage
 * matrix's machine-checked half.
 */
class FillagerTargetCompatTest {
    // --- Real vanilla villager attackers: each must get a spec matching vanilla's own AbstractVillager goal.

    @Test
    void zombieMirrorsItsOwnVillagerTargetGoal() {
        var spec = FillagerTargetCompat.specFor(Zombie.class).orElseThrow();
        assertEquals(3, spec.priority());
        assertFalse(spec.mustSee(), "vanilla Zombie's AbstractVillager goal does not require line of sight");
        assertEquals(0, spec.unseenMemoryTicks());
    }

    @Test
    void huskInheritsZombiesUnmodifiedBehaviourGoals() {
        assertTrue(FillagerTargetCompat.specFor(Husk.class).isPresent(),
            "Husk never overrides addBehaviourGoals, so it inherits Zombie's villager targeting");
        assertEquals(FillagerTargetCompat.specFor(Zombie.class), FillagerTargetCompat.specFor(Husk.class));
    }

    @Test
    void zombieVillagerInheritsZombiesUnmodifiedBehaviourGoals() {
        assertTrue(FillagerTargetCompat.specFor(ZombieVillager.class).isPresent());
        assertEquals(FillagerTargetCompat.specFor(Zombie.class), FillagerTargetCompat.specFor(ZombieVillager.class));
    }

    @Test
    void zombifiedPiglinExtendsZombieButIsExplicitlyExcluded() {
        // The one case where a broad "instanceof Zombie" would be WRONG: ZombifiedPiglin fully overrides
        // addBehaviourGoals() and never targets AbstractVillager in vanilla.
        assertTrue(FillagerTargetCompat.specFor(ZombifiedPiglin.class).isEmpty(),
            "ZombifiedPiglin must gain no new hostility toward Fillagers");
    }

    @Test
    void drownedMirrorsItsOwnVillagerTargetGoal() {
        var spec = FillagerTargetCompat.specFor(Drowned.class).orElseThrow();
        assertEquals(3, spec.priority());
        assertFalse(spec.mustSee());
        assertEquals(0, spec.unseenMemoryTicks());
    }

    @Test
    void pillagerMirrorsItsOwnVillagerTargetGoal() {
        var spec = FillagerTargetCompat.specFor(Pillager.class).orElseThrow();
        assertEquals(3, spec.priority());
        assertFalse(spec.mustSee());
    }

    @Test
    void vindicatorMirrorsItsOwnVillagerTargetGoalIncludingMustSee() {
        var spec = FillagerTargetCompat.specFor(Vindicator.class).orElseThrow();
        assertEquals(3, spec.priority());
        assertTrue(spec.mustSee(), "vanilla Vindicator's AbstractVillager goal requires line of sight");
    }

    @Test
    void evokerMirrorsItsOwnVillagerTargetGoalIncludingExtendedMemory() {
        var spec = FillagerTargetCompat.specFor(Evoker.class).orElseThrow();
        assertEquals(3, spec.priority());
        assertFalse(spec.mustSee());
        assertEquals(300, spec.unseenMemoryTicks(), "vanilla Evoker extends unseen-target memory to 300 ticks");
    }

    @Test
    void illusionerMirrorsItsOwnVillagerTargetGoalIncludingExtendedMemory() {
        var spec = FillagerTargetCompat.specFor(Illusioner.class).orElseThrow();
        assertEquals(3, spec.priority());
        assertFalse(spec.mustSee());
        assertEquals(300, spec.unseenMemoryTicks());
    }

    @Test
    void ravagerMirrorsItsOwnVillagerTargetGoalAtItsOwnHigherPriority() {
        var spec = FillagerTargetCompat.specFor(Ravager.class).orElseThrow();
        assertEquals(4, spec.priority(), "vanilla Ravager registers its AbstractVillager goal at priority 4, not 3");
        assertTrue(spec.mustSee());
    }

    // --- Confirmed non-attackers: must gain no new hostility at all.

    @Test
    void zombifiedPiglinWitchVexAndOrdinaryHostilesGainNoNewHostility() {
        assertEveryNonAttackerIsUntouched();
    }

    private void assertEveryNonAttackerIsUntouched() {
        for (Class<? extends Mob> nonAttacker : java.util.List.of(
                Witch.class, Vex.class, ZombifiedPiglin.class, Creeper.class, Skeleton.class, Spider.class)) {
            assertTrue(FillagerTargetCompat.specFor(nonAttacker).isEmpty(),
                nonAttacker.getSimpleName() + " does not target AbstractVillager in vanilla and must not target Fillager either");
        }
    }

    @Test
    void specIsOrderedSoMoreSpecificSubclassesWinOverTheirBroaderSuperclass() {
        // Drowned extends Zombie but must get Drowned's OWN spec shape, not accidentally fall through to a
        // hypothetically-different Zombie branch (today they're numerically identical, which is exactly why
        // this test pins both independently rather than only comparing them to each other).
        assertEquals(new FillagerTargetCompat.TargetGoalSpec(3, false, 0), FillagerTargetCompat.specFor(Drowned.class).orElseThrow());
        assertEquals(new FillagerTargetCompat.TargetGoalSpec(3, false, 0), FillagerTargetCompat.specFor(Zombie.class).orElseThrow());
    }
}
