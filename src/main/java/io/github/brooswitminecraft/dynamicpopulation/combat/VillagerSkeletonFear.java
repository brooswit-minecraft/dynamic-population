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
 * is at least as close as whatever vanilla's own sensor already wrote there -- it never overrides a real,
 * closer vanilla hostile -- and only ever ERASES a value IT ITSELF wrote (tracked in {@link #OUR_SKELETON}),
 * never a vanilla-written one. Firing from {@code EntityTickEvent.Post} (not {@code Pre}) guarantees this
 * runs AFTER the entity's own tick -- and therefore after {@code Brain#tick()}'s own sensor pass -- every
 * single game tick, so whichever of the two writes last in a tick simply reflects the nearer threat, which
 * is also the semantically correct "nearest hostile overall" outcome.
 *
 * <p><b>Review fix (round 1):</b> the first version of {@code onTick} split this into two separate pure
 * predicates ({@code shouldPreferSkeleton}/{@code shouldEraseOurs}) called from two separate branches of
 * the wiring method -- but the "no skeleton found" branch unconditionally removed {@link #OUR_SKELETON}'s
 * entry for this villager BEFORE the erase decision below it ever read that same entry, so
 * {@code currentMemoryIsStillOurs} was always {@code false} by construction and the erase path was
 * unreachable in practice. That was a wiring-level SEQUENCING mistake a predicate split across two
 * separate calls cannot, by itself, make unreachable at the type level. {@link #decide} now folds the
 * ENTIRE ownership/memory state transition -- including what {@link #OUR_SKELETON} should become -- into
 * one pure function callers simply obey by acting on its returned {@link Action}, removing that class of
 * bug and making the transition directly unit-testable.
 */
public final class VillagerSkeletonFear {
    /** Matches vanilla {@code VillagerHostilesSensor.ACCEPTABLE_DISTANCE_FROM_HOSTILES}'s own Zombie entry. */
    public static final float SKELETON_FEAR_DISTANCE_BLOCKS = 8.0F;

    private VillagerSkeletonFear() { }

    /** What {@link #onTick} should do to the villager's {@code NEAREST_HOSTILE} memory and bookkeeping. */
    public enum Action {
        /** Write the nearby Skeleton into {@code NEAREST_HOSTILE} and record it as ours. */
        SET_SKELETON,
        /** Erase {@code NEAREST_HOSTILE} (it holds a Skeleton WE wrote, which no longer qualifies) and drop bookkeeping. */
        ERASE,
        /** Touch nothing -- either a real (non-ours) hostile already occupies the slot, or there is nothing to erase. */
        NOOP
    }

    /**
     * Pure decision (unit-testable without any Minecraft type): the complete state transition for one
     * villager's {@code NEAREST_HOSTILE} memory on one tick.
     *
     * <p>"Nearest wins": a present, qualifying Skeleton is written whenever there is no current hostile at
     * all, or the Skeleton is at least as close as whatever already occupies the memory -- the same notion
     * vanilla's own {@code NearestVisibleLivingEntities#findClosest} list-order traversal encodes, evaluated
     * here against a single externally-supplied candidate instead of a whole list. A FARTHER Skeleton never
     * preempts a real, closer hostile -- the slot is left exactly as-is (a vanilla-owned replacement is
     * preserved untouched).
     *
     * <p>Erasure is the mirror case: only reachable when there is NO qualifying Skeleton AND the memory
     * currently holds the exact Skeleton WE previously wrote there ({@code currentMemoryIsOurs}) -- never a
     * vanilla-written value, and never when nothing is there to begin with.
     */
    public static Action decide(boolean skeletonPresent, boolean currentHostilePresent, double currentHostileDistSq,
            double skeletonDistSq, boolean currentMemoryIsOurs) {
        if (skeletonPresent && (!currentHostilePresent || skeletonDistSq <= currentHostileDistSq)) {
            return Action.SET_SKELETON;
        }
        if (!skeletonPresent && currentMemoryIsOurs) {
            return Action.ERASE;
        }
        return Action.NOOP;
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
        LivingEntity ours = OUR_SKELETON.get(villager);
        boolean currentMemoryIsOurs = ours != null && current.isPresent() && current.get() == ours;
        boolean skeletonPresent = nearestSkeleton.isPresent();
        double currentDistSq = current.isPresent() ? villager.distanceToSqr(current.get()) : 0.0;
        double skeletonDistSq = skeletonPresent ? villager.distanceToSqr(nearestSkeleton.get()) : 0.0;

        switch (decide(skeletonPresent, current.isPresent(), currentDistSq, skeletonDistSq, currentMemoryIsOurs)) {
            case SET_SKELETON -> {
                brain.setMemory(MemoryModuleType.NEAREST_HOSTILE, nearestSkeleton.get());
                OUR_SKELETON.put(villager, nearestSkeleton.get());
            }
            case ERASE -> {
                brain.eraseMemory(MemoryModuleType.NEAREST_HOSTILE);
                OUR_SKELETON.remove(villager);
            }
            case NOOP -> {
                // Only drop now-stale bookkeeping when it is no longer accurate -- never while the slot is
                // still genuinely ours (skeletonPresent is false here, and if currentMemoryIsOurs were true
                // this would have been ERASE, not NOOP, so reaching NOOP with the skeleton gone always means
                // currentMemoryIsOurs is already false) and never touch a vanilla-owned value itself.
                if (!skeletonPresent) {
                    OUR_SKELETON.remove(villager);
                }
            }
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
