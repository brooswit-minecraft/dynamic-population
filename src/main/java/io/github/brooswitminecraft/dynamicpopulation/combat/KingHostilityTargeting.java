package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.Optional;

import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.VillagerKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.world.entity.Entity;

/**
 * MINECRAFT-416: the targeting filter for cross-type King-vs-King hostility ONLY. This is deliberately a
 * separate predicate from {@link PopulationCombatTargeting} -- that class's {@code isEligibleOpponent}
 * explicitly excludes every King ({@code isKing} short-circuits), and {@code factionOf} never classifies a
 * King into a {@link Faction} at all, by design (MINECRAFT-390 scope item 5's "never eligible as a
 * population-combat attacker or target"). Reusing or widening that predicate to also cover Kings would risk
 * making a King eligible as an ordinary filler's population-combat target too, which this ticket explicitly
 * forbids ("Do not add same-type King hostility or broaden ordinary filler targeting to include Kings").
 *
 * <p><b>Review fix (P1):</b> this class previously also implemented its own nearest-target search (a raw
 * AABB scan plus a hand-rolled {@code isAlive()} check), which {@link KingHostilityTargetGoal} used to
 * acquire AND continue a target with none of the normal combat conditions vanilla's own
 * {@code TargetGoal}/{@code TargetingConditions} machinery provides -- no alliance/team exclusion, no
 * visibility, and no bounded continuation once the target left {@code FOLLOW_RANGE}. That search method is
 * now gone; {@link KingHostilityTargetGoal} instead uses vanilla's own
 * {@code net.minecraft.world.entity.ai.targeting.TargetingConditions} (team/alliance exclusion, distance,
 * line-of-sight, {@code canAttack}/{@code isAlive} all included) with this class's predicate plugged in as
 * the narrow cross-type {@code selector} -- the same composition {@code NearestAttackableTargetGoal} itself
 * uses. This class now only holds the pure, unit-tested faction predicate.
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

    /**
     * The Minecraft-wiring half of {@link #isEligibleOpponent(Faction, Faction)}, used as a
     * {@code TargetingConditions} selector -- see {@link KingHostilityTargetGoal}. Everything else a
     * population-combat-style target needs (alive, not spectator/invulnerable, not allied/same team,
     * difficulty, distance, line-of-sight) is already enforced by {@code TargetingConditions}/
     * {@code TargetGoal} itself; this predicate only needs to add "is this entity an opposing-faction King".
     */
    public static boolean isEligibleOpponent(Faction ownFaction, Entity candidate) {
        Optional<Faction> candidateFaction = kingFactionOf(candidate);
        return candidateFaction.isPresent() && isEligibleOpponent(ownFaction, candidateFaction.get());
    }
}
