package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * MINECRAFT-426 scope item 3: ordinary villagers (and promoted villagers, which ARE ordinary
 * {@code net.minecraft.world.entity.npc.Villager} instances post-promotion -- see {@code Fillager#promote})
 * must perceive Skeletons as threats and flee, exactly as they already do for Zombies.
 *
 * <p><b>The real villager brain/sensor/threat path (per ticket instruction -- not a target predicate no
 * sensor uses):</b> vanilla wires villager fear entirely through {@code Brain}'s
 * {@code MemoryModuleType.NEAREST_HOSTILE} memory. {@code net.minecraft.world.entity.ai.sensing.
 * VillagerHostilesSensor} (registered on every Villager's brain -- see {@code Villager.SENSOR_TYPES})
 * populates that memory from a hardcoded {@code ImmutableMap<EntityType<?>, Float>} of vanilla hostile
 * types/acceptable distances (Zombie at 8.0 blocks among them); {@code VillagerPanicTrigger}/
 * {@code VillagerCalmDown} (the actual flee behaviour) key ENTIRELY off
 * {@code brain.hasMemoryValue(NEAREST_HOSTILE)} -- they never re-check entity type. So "treat Skeletons like
 * Zombies" is correctly achieved by making {@code NEAREST_HOSTILE} itself include Skeleton, not by adding
 * any new goal/behaviour on the villager.
 *
 * <p><b>Why not a mixin replacing {@code VillagerHostilesSensor}'s hardcoded map:</b> this project has no
 * Mixin framework (confirmed: no {@code *.mixins.json} anywhere in the repo), and {@code Brain}'s private
 * {@code sensors} map has no public mutator or NeoForge extension point. Instead, this re-derives the SAME
 * {@code NEAREST_HOSTILE} value every tick from the SAME underlying data vanilla's own sensor reads --
 * {@code MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES} (populated by a separate, completely unmodified
 * vanilla sensor every Villager already has) -- using the exact same 8-block distance vanilla uses for
 * Zombie ({@link #SKELETON_FEAR_DISTANCE_BLOCKS}, reusing "the existing zombie fear behaviour/appropriate
 * distance" per the ticket's explicit instruction), and only ever WRITES a Skeleton into that memory when it
 * is at least as close as whatever vanilla's own sensor already wrote there (see
 * {@link #shouldPreferSkeleton}) -- it never overrides a real, closer vanilla hostile -- and only ever
 * ERASES a value IT ITSELF wrote (tracked in {@link #OUR_SKELETON}, see {@link #shouldEraseOurs}), never a
 * vanilla-written one. Firing from {@code EntityTickEvent.Post} (not {@code Pre}) guarantees this runs AFTER
 * the entity's own tick -- and therefore after {@code Brain#tick()}'s own sensor pass -- every single game
 * tick, so whichever of the two writes last in a tick simply reflects the nearer threat, which is also the
 * semantically correct "nearest hostile overall" outcome.
 */
public final class VillagerSkeletonFear {
    /** Matches vanilla {@code VillagerHostilesSensor.ACCEPTABLE_DISTANCE_FROM_HOSTILES}'s own Zombie entry. */
    public static final float SKELETON_FEAR_DISTANCE_BLOCKS = 8.0F;

    private VillagerSkeletonFear() { }

    /**
     * Pure decision (unit-testable without any Minecraft type): given whether the villager currently has
     * ANY {@code NEAREST_HOSTILE} memory value and, if so, how far away (squared) it is, should a Skeleton
     * at {@code skeletonDistSq} be written in its place? "Nearest wins" -- the same notion vanilla's own
     * {@code NearestVisibleLivingEntities#findClosest} list-order traversal encodes, evaluated here against
     * a single externally-supplied candidate instead of a whole list.
     */
    public static boolean shouldPreferSkeleton(boolean currentHostilePresent, double currentHostileDistSq, double skeletonDistSq) {
        return !currentHostilePresent || skeletonDistSq <= currentHostileDistSq;
    }

    /**
     * Pure decision: our own previously-written Skeleton entry must be cleared once the Skeleton that
     * earned it no longer qualifies (out of range, dead, or simply not re-found this tick) -- but ONLY if
     * nothing else (a real vanilla hostile) has since taken over that memory slot.
     */
    public static boolean shouldEraseOurs(boolean skeletonStillQualifies, boolean currentMemoryIsStillOurs) {
        return !skeletonStillQualifies && currentMemoryIsStillOurs;
    }

    /** Which Skeleton (if any) WE most recently wrote into a given villager's {@code NEAREST_HOSTILE}. */
    private static final Map<Villager, LivingEntity> OUR_SKELETON = new WeakHashMap<>();

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || !(villager.level() instanceof ServerLevel) || !villager.isAlive()) {
            return;
        }
        Brain<Villager> brain = villager.getBrain();
        Optional<LivingEntity> current = brain.getMemory(MemoryModuleType.NEAREST_HOSTILE);
        Optional<Skeleton> nearestSkeleton = nearestSkeleton(villager);

        if (nearestSkeleton.isPresent()) {
            Skeleton skeleton = nearestSkeleton.get();
            double skeletonDistSq = villager.distanceToSqr(skeleton);
            boolean currentPresent = current.isPresent();
            double currentDistSq = currentPresent ? villager.distanceToSqr(current.get()) : 0.0;
            if (shouldPreferSkeleton(currentPresent, currentDistSq, skeletonDistSq)) {
                brain.setMemory(MemoryModuleType.NEAREST_HOSTILE, skeleton);
                OUR_SKELETON.put(villager, skeleton);
                return;
            }
        } else {
            OUR_SKELETON.remove(villager);
        }

        LivingEntity ours = OUR_SKELETON.get(villager);
        boolean currentMemoryIsStillOurs = ours != null && current.isPresent() && current.get() == ours;
        if (shouldEraseOurs(nearestSkeleton.isPresent(), currentMemoryIsStillOurs)) {
            brain.eraseMemory(MemoryModuleType.NEAREST_HOSTILE);
        }
    }

    /** Reuses the SAME {@code NEAREST_VISIBLE_LIVING_ENTITIES} memory vanilla's own hostility sensor reads. */
    private static Optional<Skeleton> nearestSkeleton(Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES)
            .flatMap(nearby -> nearby.findClosest(candidate -> candidate instanceof Skeleton && candidate.isAlive()
                && villager.distanceToSqr(candidate) <= SKELETON_FEAR_DISTANCE_BLOCKS * SKELETON_FEAR_DISTANCE_BLOCKS))
            .map(candidate -> (Skeleton) candidate);
    }
}
