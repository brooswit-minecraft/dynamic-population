package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.brooswitminecraft.dynamicpopulation.entity.Fillager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * MINECRAFT-426 scope item 2: ORDINARY (non-population-gated) Skeleton hostility toward Fillagers and
 * villagers (ordinary and promoted). Unlike {@link PopulationCombatTargetGoal}, this is NOT a
 * {@code targetSelector} goal gated by {@link PopulationCombatEligibility} or by filler-field/tag
 * membership ({@link FillerMarkers}) -- it is the same kind of "install a target-acquisition goal at
 * join time" compat shim {@link FillagerTargetCompat} already uses for other vanilla attackers, just
 * going the OTHER direction (Skeleton targeting Fillager/villager, rather than a villager attacker
 * targeting Fillager) and covering an entity type {@link FillagerTargetCompat}'s own inventory explicitly
 * found NOT to target {@code AbstractVillager} in vanilla ({@code Skeleton} is listed among the confirmed
 * non-attackers there).
 *
 * <p>Once a target is acquired via these goals, Skeleton's existing vanilla attack-execution goals (the bow
 * goal / melee goal swapped in {@code AbstractSkeleton#populateDefaultEquipmentSlots}/{@code reassessWeaponGoal})
 * already act on whatever {@code Mob#getTarget()} holds -- exactly the same "acquisition is the only new
 * wiring needed" shape {@link FillagerTargetCompat}'s own class doc explains, so no separate attack-goal is
 * added here. Priority 3 (mustSee=true) matches vanilla {@code AbstractSkeleton}'s own Player/IronGolem
 * target goals at that same priority slot (see that class's {@code registerGoals}) -- there is no ticket-
 * mandated number, so this reuses Skeleton's own established shape rather than Zombie's (which uses
 * mustSee=false) since this goal is being added to Skeleton, not Zombie.
 *
 * <p><b>Subclass/King audit (ticket scope item 2's explicit "document which receive this" requirement):</b>
 * <ul>
 * <li>{@code net.minecraft.world.entity.monster.Skeleton} (literal class) -- the only type matched by the
 * {@code instanceof Skeleton} check below, and therefore the only type that receives this goal.</li>
 * <li>{@code SkeletonKingEntity} extends {@code Skeleton} directly, so it DOES match and DOES receive this
 * goal -- deliberately NOT excluded. This differs from {@code FillerTargetingInstaller}'s explicit King
 * exclusion, but that exclusion exists only because {@code PopulationCombatTargetGoal} is gated by a
 * faction's filler-tag/density membership a King never has; this goal has no such gate at all (ticket: "not
 * gated by...target membership in the filler field"), so there is no reason specific to King-ness to
 * exclude it. The King keeps its own additional {@code KingHostilityTargetGoal} (priority 0) unmodified; this
 * is purely additive.</li>
 * <li>{@code Stray}, {@code WitherSkeleton}, {@code Bogged} all extend {@code AbstractSkeleton} directly, NOT
 * {@code Skeleton} -- {@code instanceof Skeleton} does not match them, so they are explicitly OUT of scope
 * and gain no new hostility, consistent with the ticket's "regular vanilla Skeleton" wording and the same
 * class-literal discipline {@code FillagerTargetCompat}/{@code FillerMarkers} already use for Zombie (where
 * {@code ZombifiedPiglin} is excluded the same way).</li>
 * <li>Population-filler-tagged Skeletons ({@code FillerMarkers.SKELETON_FILLER_TAG}) are ordinary
 * {@code Skeleton} instances too, and are NOT excluded here -- this hostility applies regardless of filler
 * tag/faction membership, per ticket scope item 2.</li>
 * </ul>
 */
public final class SkeletonVillagerHostility {
    private SkeletonVillagerHostility() { }

    /** Same double-registration guard {@code FillagerTargetCompat.WIRED} uses, for the same reason. */
    private static final Set<Mob> WIRED = Collections.newSetFromMap(new WeakHashMap<>());

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getEntity() instanceof Skeleton skeleton)) {
            return;
        }
        if (!WIRED.add(skeleton)) {
            return;
        }
        install(skeleton);
    }

    /** The Minecraft-wiring half, impossible to unit test without a running game -- see this class's own doc. */
    private static void install(Skeleton skeleton) {
        skeleton.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(skeleton, Fillager.class, true));
        skeleton.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(skeleton, AbstractVillager.class, true));
    }
}
