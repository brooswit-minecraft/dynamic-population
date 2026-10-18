package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.Optional;

import io.github.brooswitminecraft.dynamicpopulation.entity.Fillager;
import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.VillagerKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * MINECRAFT-390 scope item 5: the targeting filter for population-combat ONLY. Target opposing-faction
 * NON-PERSISTENT members only -- excludes same-faction members, players, persistent/promoted villagers, all
 * three Kings, and any other persistent entity. This exclusion is scoped to this one goal
 * ({@code PopulationCombatTargetGoal}) alone: it must never be consulted by, or suppress, any other
 * targeting/hostility path (e.g. {@code FillagerTargetCompat}'s vanilla-attacker-vs-Fillager mirroring, or a
 * Zombie/Skeleton's own vanilla player-targeting) -- additive, not a universal immunity flag.
 */
public final class PopulationCombatTargeting {
    private PopulationCombatTargeting() { }

    /**
     * The pure, unit-tested core: given only plain facts about a candidate (never a real Minecraft type),
     * whether it may be selected as a population-combat target by a member of {@code ownFaction}. No density
     * check here at all -- the OPPONENT's own density is irrelevant (see {@code PopulationCombatEligibility}'s
     * class doc); only the attacker's own threshold gates whether this predicate is even consulted.
     */
    public static boolean isEligibleOpponent(Faction ownFaction, Faction candidateFaction, boolean isPlayer,
            boolean isKing, boolean isPersistentOrPromoted) {
        if (isPlayer || isKing || isPersistentOrPromoted) {
            return false;
        }
        return candidateFaction != ownFaction;
    }

    /**
     * Which faction (if any) a real entity belongs to, FOR POPULATION-COMBAT PURPOSES ONLY -- i.e. "is this a
     * non-persistent filler of a faction". A King (any of the three) is deliberately never classified into a
     * faction here, even though a {@code ZombieKingEntity}/{@code SkeletonKingEntity} IS, by Java
     * inheritance, also a {@code Zombie}/{@code Skeleton} -- the King check runs first and short-circuits.
     * A promoted/permanent {@code net.minecraft.world.entity.npc.Villager} is likewise never classified (no
     * case matches it), and neither is an untagged vanilla Zombie/Skeleton (see {@code FillerMarkers}).
     */
    public static Optional<Faction> factionOf(Entity entity) {
        if (entity instanceof VillagerKingEntity || entity instanceof ZombieKingEntity
                || entity instanceof SkeletonKingEntity) {
            return Optional.empty();
        }
        if (entity instanceof Fillager) {
            return Optional.of(Faction.VILLAGER);
        }
        if (entity instanceof Zombie && entity.getTags().contains(FillerMarkers.ZOMBIE_FILLER_TAG)) {
            return Optional.of(Faction.ZOMBIE);
        }
        if (entity instanceof Skeleton && entity.getTags().contains(FillerMarkers.SKELETON_FILLER_TAG)) {
            return Optional.of(Faction.SKELETON);
        }
        return Optional.empty();
    }

    private static boolean isKing(Entity entity) {
        return entity instanceof VillagerKingEntity || entity instanceof ZombieKingEntity
            || entity instanceof SkeletonKingEntity;
    }

    /**
     * The Minecraft-wiring half of {@link #isEligibleOpponent}: derives the plain facts from a real entity.
     *
     * <p><b>Review fix (P1-3):</b> {@code persistentOrPromoted} previously ALSO required
     * {@code factionOf(candidate).isEmpty()} -- but every candidate that reaches the final
     * {@link #isEligibleOpponent} call below is GUARANTEED to have a non-empty faction (the early return
     * right above it rejects every empty-faction candidate first), so that extra clause made
     * {@code persistentOrPromoted} dead code: it could never be {@code true} at the point it mattered. The
     * practical consequence was that a TAGGED filler that later became persistent (named with a name tag,
     * leashed, picked up and re-placed, or any other vanilla persistence-granting action) was NEVER actually
     * excluded from being a population-combat target, contradicting the ticket's explicit "non-persistent
     * members only" requirement. Fixed by checking the entity's REAL, LIVE persistence state alone -- no
     * dependency on whether it also happens to carry a faction tag. Kings are still excluded earlier (via
     * {@code isKing}), so this check does not need to special-case them again, but does so anyway (defensive,
     * matches the documented defense-in-depth elsewhere in this class).
     *
     * <p>This also means {@link PopulationCombatTargetGoal#canContinueToUse}, which calls this exact method
     * every evaluation cycle, now revalidates persistence LIVE, not just at the next reconciliation pass: a
     * filler that transitions to persistent mid-chase has its target cleared the very next check.
     */
    public static boolean isEligibleOpponent(Faction ownFaction, Entity candidate) {
        if (!(candidate instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        boolean isPlayer = candidate instanceof Player;
        boolean isKing = isKing(candidate);
        boolean persistentOrPromoted = !isKing && candidate instanceof Mob mob && mob.isPersistenceRequired();
        Optional<Faction> candidateFaction = factionOf(candidate);
        if (candidateFaction.isEmpty()) {
            return false;
        }
        return isEligibleOpponent(ownFaction, candidateFaction.get(), isPlayer, isKing, persistentOrPromoted);
    }

    /** Nearest eligible opposing-faction filler within {@code range} blocks of {@code self}, or null. */
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
