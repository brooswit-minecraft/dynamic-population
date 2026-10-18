package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.brooswitminecraft.dynamicpopulation.entity.Fillager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Illusioner;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * MINECRAFT-389 (AC1): makes every real vanilla villager attacker also treat a {@link Fillager} as
 * attackable, at the same boundary vanilla itself uses -- each attacker's own {@code targetSelector} -- so
 * that eligibility (range, visibility, difficulty, alive/invulnerability, team) is inherited unmodified
 * rather than re-derived.
 *
 * <p><b>Why not a shared tag/interface/{@code instanceof AbstractVillager}?</b> {@link Fillager} deliberately
 * does NOT extend {@code AbstractVillager} -- that would force it to implement {@code Merchant} and drag in
 * trading machinery this mod explicitly keeps out (see {@link Fillager}'s own javadoc and this ticket's
 * explicit out-of-scope list). Every vanilla villager attacker below scans with a class-generic
 * {@code NearestAttackableTargetGoal<AbstractVillager>} it registers directly on itself in
 * {@code registerGoals()}/{@code addBehaviourGoals()} -- a Java generic-class filter, not a tag or an
 * overridable predicate -- so a {@link Fillager} is structurally invisible to that scan regardless of any
 * event fired AFTER a target is chosen (confirmed: NeoForge's own
 * {@code LivingChangeTargetEvent} fires only once vanilla has already decided on a target; it cannot widen
 * the class-filtered scan that found candidates in the first place). The fix here instead mirrors each
 * attacker's own goal, one priority slot later is not needed -- same priority, same mustSee/memory flags --
 * targeting {@code Fillager.class} specifically, so target ACQUISITION (the scan), not just acceptance of an
 * already-selected target, is covered. Once {@code Mob.setTarget(fillager)} is set this way, every attack
 * goal (melee, crossbow, trident) already present on these mobs is type-agnostic over {@code getTarget()} and
 * needs no further change -- confirmed by inspecting {@code MeleeAttackGoal}, {@code ZombieAttackGoal}, and
 * the raiders' own melee/ranged attack goals in decompiled 1.21.1 sources.
 *
 * <p><b>Inventory (1.21.1 decompiled vanilla sources, every {@code NearestAttackableTargetGoal<AbstractVillager>}
 * registration found repo-wide, confirmed by exhaustive grep over {@code net/minecraft/world/entity/}):</b>
 * <ul>
 * <li>{@code Zombie} -- priority 3, mustSee=false. {@code Husk} and {@code ZombieVillager} both extend
 * {@code Zombie} and never override {@code addBehaviourGoals()}, so they inherit this unchanged.</li>
 * <li>{@code ZombifiedPiglin} extends {@code Zombie} too, but FULLY overrides {@code addBehaviourGoals()} and
 * never targets {@code AbstractVillager} -- explicitly excluded below, never matched by a broad
 * {@code instanceof Zombie} check.</li>
 * <li>{@code Drowned} -- priority 3, mustSee=false (its own override, extends {@code Zombie} but replaces
 * {@code addBehaviourGoals()} entirely).</li>
 * <li>{@code Pillager} -- priority 3, mustSee=false.</li>
 * <li>{@code Vindicator} -- priority 3, mustSee=true.</li>
 * <li>{@code Evoker} -- priority 3, mustSee=false, {@code setUnseenMemoryTicks(300)}.</li>
 * <li>{@code Illusioner} -- priority 3, mustSee=false, {@code setUnseenMemoryTicks(300)}.</li>
 * <li>{@code Ravager} -- priority 4, mustSee=true (vanilla also excludes baby {@code AbstractVillager}
 * targets via a predicate; irrelevant here since {@link Fillager} has no baby variant, so mustSee alone is
 * an exact behavioural match).</li>
 * </ul>
 * Explicitly confirmed NOT to target {@code AbstractVillager} in 1.21.1 (left completely untouched by this
 * class, so they gain no new hostility): {@code Witch}, {@code Vex}, {@code ZombifiedPiglin}, and every other
 * hostile mob -- none reference {@code AbstractVillager} anywhere in their goal registration. No
 * brain/sensor-based (as opposed to goal-based) villager targeting exists for raiders in 1.21.1 vanilla
 * either (searched {@code net/minecraft/world/entity/ai/sensing/} and {@code .../ai/behavior/} for
 * {@code AbstractVillager}: no hits) -- confirmed by source inspection, not assumed.
 *
 * <p><b>Modded compatibility:</b> this project has no other mods in its dependency set (single-mod NeoForge
 * project), so there is nothing modded to test here; only this vanilla inventory is verified. Any
 * third-party mod that ALSO registers its own class-generic {@code NearestAttackableTargetGoal<AbstractVillager>}
 * (rather than going through this same join-time extensibility point) would need the identical treatment --
 * {@link #specFor} is the one place that would need a new case.
 */
public final class FillagerTargetCompat {
    private FillagerTargetCompat() { }

    /** One real vanilla attacker's own goal shape, mirrored onto {@link Fillager}; {@code unseenMemoryTicks
     * <= 0} means "leave the goal's own default alone" (vanilla's {@code TargetGoal} default is 0 ticks, i.e.
     * no override needed). */
    public record TargetGoalSpec(int priority, boolean mustSee, int unseenMemoryTicks) { }

    /**
     * The pure, unit-tested decision: given a {@code Mob} subclass, which (if any) {@link TargetGoalSpec}
     * should be installed. Class LITERALS only -- no entity construction and no Minecraft registry bootstrap
     * is needed to call this, so it is safe to exercise directly with real vanilla {@code Class} tokens in a
     * plain JUnit test. This is also the exact function {@link #onJoin} calls into, never a re-derived copy.
     *
     * <p>Order matters: more specific vanilla subclasses ({@link Drowned}, {@link ZombifiedPiglin}) are
     * checked before the broader {@link Zombie} check they would otherwise also match.
     */
    public static Optional<TargetGoalSpec> specFor(Class<? extends Mob> type) {
        if (Drowned.class.isAssignableFrom(type)) {
            return Optional.of(new TargetGoalSpec(3, false, 0));
        }
        if (ZombifiedPiglin.class.isAssignableFrom(type)) {
            return Optional.empty();
        }
        if (Zombie.class.isAssignableFrom(type)) {
            return Optional.of(new TargetGoalSpec(3, false, 0));
        }
        if (Ravager.class.isAssignableFrom(type)) {
            return Optional.of(new TargetGoalSpec(4, true, 0));
        }
        if (Vindicator.class.isAssignableFrom(type)) {
            return Optional.of(new TargetGoalSpec(3, true, 0));
        }
        if (Evoker.class.isAssignableFrom(type) || Illusioner.class.isAssignableFrom(type)) {
            return Optional.of(new TargetGoalSpec(3, false, 300));
        }
        if (Pillager.class.isAssignableFrom(type)) {
            return Optional.of(new TargetGoalSpec(3, false, 0));
        }
        return Optional.empty();
    }

    /**
     * Guards against double registration: {@link EntityJoinLevelEvent} fires once per fresh spawn, but also
     * whenever an already-constructed {@code Mob} instance re-enters a level (chunk reload of an entity that
     * happened to stay in memory, a leash/teleport re-add, etc.) -- goal selectors are never reset or
     * persisted on their own, so adding blindly on every join would stack duplicate target goals on the same
     * instance over time. Keyed by identity via a {@link WeakHashMap}-backed set, the same pattern
     * {@code VillageActivityFeedback.BREEDING_SPAWNS} already uses for a one-shot, non-leaking marker.
     */
    private static final Set<Mob> WIRED = Collections.newSetFromMap(new WeakHashMap<>());

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getEntity() instanceof Mob mob) || mob instanceof Fillager) {
            return;
        }
        if (!WIRED.add(mob)) {
            return;
        }
        specFor(mob.getClass()).ifPresent(spec -> install(mob, spec));
    }

    /** The Minecraft-wiring half, impossible to unit test without a running game -- see {@link #specFor}. */
    private static void install(Mob mob, TargetGoalSpec spec) {
        var goal = new NearestAttackableTargetGoal<>(mob, Fillager.class, spec.mustSee());
        if (spec.unseenMemoryTicks() > 0) {
            goal.setUnseenMemoryTicks(spec.unseenMemoryTicks());
        }
        mob.targetSelector.addGoal(spec.priority(), goal);
    }
}
