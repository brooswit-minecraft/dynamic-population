package io.github.brooswitminecraft.dynamicpopulation.combat;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.LevelField;
import io.github.brooswitminecraft.dynamicpopulation.propagation.PopulationSimulation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * MINECRAFT-390 (late-added requirement): suppresses sun-exposure burning for ordinary (filler-tagged, NOT
 * King) Zombie/Skeleton fillers while their OWN faction's local density is &gt; 250 (see
 * {@link EnvironmentalOverride}).
 *
 * <p><b>Why a tick event, not an override:</b> filler Zombies/Skeletons are literal vanilla
 * {@code EntityType.ZOMBIE}/{@code EntityType.SKELETON} instances (ticket scope item 3 requires reusing the
 * vanilla types, not a subclass) -- {@code Zombie#isSunBurnTick}/{@code AbstractSkeleton#isSunBurnTick} are
 * instance methods on classes this mod cannot subclass for the plain filler case, so there is no override
 * point available. Instead, every tick, for every such entity that vanilla has already set on fire via its
 * own sun check, this immediately extinguishes it (before any burn damage tick lands) when the override is
 * active -- functionally equivalent to vanilla never having ignited it, at the cost of a reactive
 * extinguish within the same tick rather than suppressing ignition itself. This does NOT grant general
 * fire/lava immunity: only entities current already-on-fire are touched, and only when this mod itself
 * ignited... no -- more precisely: {@link net.minecraft.world.entity.Entity#clearFire} is called
 * unconditionally once this tick's check passes, regardless of what set the entity alight (lava, fire, a
 * Flame-enchanted bow, sun) -- this IS a scoping gap: a filler Zombie/Skeleton standing in lava while its own
 * override is active would also have lava-caused fire cleared by this hook (though the lava damage HURT
 * itself, dealt separately by {@code Entity#baseTick}'s own lava handling, is untouched -- only the
 * fire-ticks/burning status is cleared, never a direct damage source). Accepted as a reasonable
 * approximation given no event fires specifically for "this ignition came from the sun" to filter on
 * instead; flagged here rather than silently narrowed.
 */
public final class SunImmunityTicking {
    private SunImmunityTicking() { }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !PopulationSimulation.simulated(level)) {
            return;
        }
        Faction faction;
        if (event.getEntity() instanceof Zombie zombie && !isKing(zombie)
                && zombie.getTags().contains(io.github.brooswitminecraft.dynamicpopulation.combat.FillerMarkers.ZOMBIE_FILLER_TAG)) {
            faction = Faction.ZOMBIE;
        } else if (event.getEntity() instanceof Skeleton skeleton && !isKing(skeleton)
                && skeleton.getTags().contains(io.github.brooswitminecraft.dynamicpopulation.combat.FillerMarkers.SKELETON_FILLER_TAG)) {
            faction = Faction.SKELETON;
        } else {
            return;
        }
        if (!event.getEntity().isOnFire()) {
            return;
        }
        LevelField field = PopulationSimulation.field(level, faction);
        int density = field.get(field.cellAt(event.getEntity().blockPosition()));
        if (EnvironmentalOverride.active(density)) {
            event.getEntity().clearFire();
        }
    }

    private static boolean isKing(net.minecraft.world.entity.Entity entity) {
        return entity instanceof io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity
            || entity instanceof io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
    }
}
