package io.github.brooswitminecraft.dynamicpopulation.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.PathfinderMob;

/**
 * Class-hierarchy regression guards for MINECRAFT-389: real vanilla {@code Class} literals only, no entity
 * construction and no registry bootstrap needed, so these are safe to run in a plain JUnit test.
 *
 * <p>Both of these are a deliberate design constraint, not an accident: see
 * {@code io.github.brooswitminecraft.dynamicpopulation.combat.FillagerTargetCompat}'s javadoc for why
 * villager-attacker compatibility is done WITHOUT {@code AbstractVillager} inheritance (it would force
 * {@code Merchant}/trading machinery this mod explicitly keeps out of scope), and {@link Fillager}'s own
 * javadoc for why it stays a plain {@link PathfinderMob}.
 */
class FillagerTest {
    @Test
    void fillagerNeverExtendsAbstractVillager() {
        // A regression guard against a well-intentioned "simplification" that would make villager-attacker
        // targeting work for free via instanceof, at the cost of dragging in trading/jobs/schedules this
        // ticket (and the mod's whole design) explicitly keeps out.
        assertFalse(AbstractVillager.class.isAssignableFrom(Fillager.class));
    }

    @Test
    void fillagerStaysAPlainPathfinderMob() {
        assertTrue(PathfinderMob.class.isAssignableFrom(Fillager.class), "sanity: Fillager must still be a PathfinderMob subtype");
    }
}
