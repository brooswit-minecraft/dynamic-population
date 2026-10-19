package io.github.brooswitminecraft.dynamicpopulation.entity;

import io.github.brooswitminecraft.dynamicpopulation.combat.KingHostilityTargetGoal;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.level.Level;

/**
 * MINECRAFT-390: the Villager King, now a real, visible, larger villager/Fillager-looking ENTITY (replacing
 * the pre-390 LEVEL-attachment-only mechanism docs/villager-king.md previously described). Deliberately a
 * sibling of {@link Fillager}, not a subclass: a King must never be promotable (it has no
 * {@code mobInteract} emerald-promotion override at all) and must never be eligible as a population-combat
 * attacker or target (see {@code PopulationCombatTargeting#factionOf}, which only recognizes the plain
 * {@link Fillager} class, not this one) -- inheriting {@link Fillager} would require re-overriding both of
 * those back out, which is more error-prone than simply not inheriting them in the first place.
 *
 * <p>Size/stats are deliberately not specified by the ticket ("no specific size/health/damage/lifespan
 * numbers were requested -- pick reasonable values and document them"): 1.5x Fillager's own
 * width/height (0.6x1.95 -&gt; 0.9x2.9) for "bigger... like the Fillager" visually, 60 max health (3x
 * Fillager's 20) and a slightly slower 0.35 movement speed (vs Fillager's 0.5) to read as a stationary,
 * tankier figure rather than a roaming one. MINECRAFT-416 adds a 6.0 {@code ATTACK_DAMAGE} attribute (see
 * {@code DynamicPopulationEntities#createAttributes}) -- above Fillager's unarmed-equivalent 2.0, below
 * {@code ZombieKingEntity}'s 7.0, reasoning the Villager King as a capable but not the hardest-hitting King;
 * no specific number was mandated.
 *
 * <p><b>MINECRAFT-416 (cross-type King hostility):</b> this King now has a real {@code targetSelector} goal
 * ({@link KingHostilityTargetGoal}) and attack-execution goal ({@link MeleeAttackGoal}) so it can acquire and
 * actually fight an opposing-faction King (Zombie/Skeleton), while remaining peaceful toward everything else
 * -- see {@link #registerGoals} for the goal-priority reasoning and {@code PopulationCombatTargeting}'s class
 * doc, which still deliberately never classifies this class into a population-combat faction at all. It
 * still has NO goal that lets it initiate against a player or an ordinary mob/filler.
 *
 * <p>Persistence (ticket scope item 1: "persist across save/load, do not naturally despawn, survive chunk
 * unload/reload and server restart"): {@link #isPersistenceRequired()} is unconditionally {@code true},
 * never dependent on the mutable {@code persistenceRequired} flag Mob's own save/load round-trips (that flag
 * IS also set on spawn, in {@code KingManager}/{@code KingEntitySync}, as defense-in-depth, but this
 * override is the actual guarantee). No new despawn-prevention mechanism beyond that single override is
 * needed -- vanilla's own {@code Mob#checkDespawn} already treats persistence-required as "never despawn".
 */
public final class VillagerKingEntity extends PathfinderMob {
    public VillagerKingEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.xpReward = 0;
        this.setPersistenceRequired();
    }

    public static EntityType.Builder<VillagerKingEntity> typeBuilder() {
        return EntityType.Builder.of(VillagerKingEntity::new, MobCategory.MISC).sized(0.9F, 2.9F).clientTrackingRange(10);
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    @Override
    protected void registerGoals() {
        // A King is a stationary landmark: it flees if hurt (vanilla PanicGoal, same mechanism Fillager
        // uses) and still has NO goal that lets it initiate against a player or ordinary mob/filler -- it
        // never participates in population-combat either as attacker or target (see
        // PopulationCombatTargeting.factionOf, which does not recognize this class).
        //
        // MINECRAFT-416: it DOES now acquire and fight an opposing-faction King, via a targetSelector goal
        // (KingHostilityTargetGoal, below) plus the MeleeAttackGoal this adds at goalSelector priority 2 --
        // mirroring PopulationCombatTargetGoal's own documented goalSelector-priority writeup for Fillager:
        // PanicGoal (priority 1) and MeleeAttackGoal (priority 2) share the MOVE flag, so they never run
        // concurrently, and priority 1 is checked first -- a recently-hurt King's flee always preempts
        // attacking an opposing King for as long as the flee window lasts, then it reverts to acting on
        // whatever target KingHostilityTargetGoal has already set (that goal never stopped targeting it).
        this.goalSelector.addGoal(1, new PanicGoal(this, 1.25));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, true));
        this.goalSelector.addGoal(3, new FloatGoal(this));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.4));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, LivingEntity.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new KingHostilityTargetGoal(this, Faction.VILLAGER));
    }
}
