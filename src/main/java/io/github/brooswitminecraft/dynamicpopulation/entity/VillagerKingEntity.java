package io.github.brooswitminecraft.dynamicpopulation.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
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
 * tankier figure rather than a roaming one.
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
        // A King is a stationary, peaceful landmark: it flees if hurt (vanilla PanicGoal, same mechanism
        // Fillager uses) but has NO targetSelector goals at all -- it never participates in population-combat
        // either as attacker or target (see PopulationCombatTargeting.factionOf, which does not recognize
        // this class), and it never acquires any other combat target either.
        this.goalSelector.addGoal(1, new PanicGoal(this, 1.25));
        this.goalSelector.addGoal(2, new FloatGoal(this));
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.4));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, LivingEntity.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
    }
}
