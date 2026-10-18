package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * MINECRAFT-390: installs {@link PopulationCombatTargetGoal} onto filler-tagged vanilla Zombie/Skeleton
 * instances at join time -- the same join-time goal-mirroring pattern
 * {@code io.github.brooswitminecraft.dynamicpopulation.combat.FillagerTargetCompat} already uses, for the
 * same underlying reason: these are literal vanilla {@code Zombie}/{@code Skeleton} instances, not a
 * subclass this mod owns, so there is no {@code registerGoals()} override point to add to directly.
 *
 * <p>{@code ZombieKingEntity}/{@code SkeletonKingEntity} ARE, by inheritance, also {@code Zombie}/
 * {@code Skeleton}, but they are never filler-tagged (see {@code FillerMarkers}' class doc) and are also
 * explicitly excluded here by {@code instanceof}, so a King never receives this goal.
 */
public final class FillerTargetingInstaller {
    private FillerTargetingInstaller() { }

    /** Same double-registration guard {@code FillagerTargetCompat.WIRED} uses, for the same reason. */
    private static final Set<Mob> WIRED = Collections.newSetFromMap(new WeakHashMap<>());

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!WIRED.add(mob)) {
            return;
        }
        if (mob instanceof Zombie zombie && !(mob instanceof ZombieKingEntity)
                && zombie.getTags().contains(FillerMarkers.ZOMBIE_FILLER_TAG)) {
            mob.targetSelector.addGoal(1, new PopulationCombatTargetGoal(mob, Faction.ZOMBIE));
        } else if (mob instanceof Skeleton skeleton && !(mob instanceof SkeletonKingEntity)
                && skeleton.getTags().contains(FillerMarkers.SKELETON_FILLER_TAG)) {
            mob.targetSelector.addGoal(1, new PopulationCombatTargetGoal(mob, Faction.SKELETON));
        }
    }
}
