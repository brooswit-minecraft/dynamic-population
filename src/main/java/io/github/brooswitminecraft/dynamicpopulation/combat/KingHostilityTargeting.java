package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.Optional;

import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.VillagerKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * MINECRAFT-416: the targeting filter for cross-type King-vs-King hostility ONLY. This is deliberately a
 * separate predicate from {@link PopulationCombatTargeting} -- that class's {@code isEligibleOpponent}
 * explicitly excludes every King ({@code isKing} short-circuits), and {@code factionOf} never classifies a
 * King into a {@link Faction} at all, by design (MINECRAFT-390 scope item 5's "never eligible as a
 * population-combat attacker or target"). Reusing or widening that predicate to also cover Kings would risk
 * making a King eligible as an ordinary filler's population-combat target too, which this ticket explicitly
 * forbids ("Do not add same-type King hostility or broaden ordinary filler targeting to include Kings").
 *
 * <p>Unlike {@link PopulationCombatTargeting}, this predicate has no density/threshold gate at all (ticket:
 * "has no population-density prerequisite... Do not reuse the ordinary filler population-combat eligibility
 * gate") and no player/persistence exclusion -- the only two facts that matter are "is this a King" and
 * "which faction's King is it", since the only entities this predicate is ever consulted about are the three
 * King types themselves (installed only on {@code VillagerKingEntity}/{@code ZombieKingEntity}/
 * {@code SkeletonKingEntity} by {@link KingHostilityTargetGoal}).
 */
public final class KingHostilityTargeting {
    private KingHostilityTargeting() { }

    /** Which King faction (if any) a real entity is. Empty for every non-King entity, including players and fillers. */
    public static Optional<Faction> kingFactionOf(Entity entity) {
        if (entity instanceof VillagerKingEntity) {
            return Optional.of(Faction.VILLAGER);
        }
        if (entity instanceof ZombieKingEntity) {
            return Optional.of(Faction.ZOMBIE);
        }
        if (entity instanceof SkeletonKingEntity) {
            return Optional.of(Faction.SKELETON);
        }
        return Optional.empty();
    }

    /** The pure, unit-tested core: two different King factions are always mutually eligible; same faction never is. */
    public static boolean isEligibleOpponent(Faction ownFaction, Faction candidateFaction) {
        return ownFaction != candidateFaction;
    }

    /** The Minecraft-wiring half of {@link #isEligibleOpponent(Faction, Faction)}: derives the facts from a real entity. */
    public static boolean isEligibleOpponent(Faction ownFaction, Entity candidate) {
        if (!(candidate instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        Optional<Faction> candidateFaction = kingFactionOf(candidate);
        return candidateFaction.isPresent() && isEligibleOpponent(ownFaction, candidateFaction.get());
    }

    /** Nearest eligible opposing-faction King within {@code range} blocks of {@code self}, or null. */
    public static LivingEntity findNearestTarget(ServerLevel level, Mob self, Faction ownFaction, double range) {
        AABB box = self.getBoundingBox().inflate(range);
        LivingEntity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Entity candidate : level.getEntities(self, box, e -> isEligibleOpponent(ownFaction, e))) {
            double distSq = self.distanceToSqr(candidate);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = (LivingEntity) candidate;
            }
        }
        return nearest;
    }
}
