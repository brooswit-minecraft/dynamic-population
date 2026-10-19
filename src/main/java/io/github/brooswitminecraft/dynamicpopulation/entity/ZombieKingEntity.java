package io.github.brooswitminecraft.dynamicpopulation.entity;

import io.github.brooswitminecraft.dynamicpopulation.combat.KingHostilityTargetGoal;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/**
 * MINECRAFT-390: the Zombie King -- a bigger, recognizable, PERSISTENT Zombie, extending vanilla
 * {@link Zombie} directly (rather than sibling-style like {@link VillagerKingEntity}/{@link Fillager}) so it
 * inherits vanilla Zombie combat AI (melee attack, villager/golem targeting, reinforcement calls, etc) for
 * free -- there is nothing villager-shaped to avoid inheriting here, unlike {@code Fillager} avoiding
 * {@code AbstractVillager}'s trading machinery.
 *
 * <p><b>Sun immunity is unconditional, not threshold-gated</b> -- a deliberate, documented departure from
 * the ordinary Zombie FILLER's threshold-gated sun override (see {@code EnvironmentalOverride}, which only
 * applies above 250 local density): a King must NEVER die to environmental sun exposure, full stop, since
 * persistence ("does not naturally despawn... survive chunk unload/reload and server restart") is a hard
 * requirement of this ticket, and a King can settle in direct sky light (explicit placement no longer has
 * any sky-light/depth preference at all as of MINECRAFT-424, and even before that removal the preference
 * only ever favored open sky, never avoided it) would otherwise put it directly in harm's way the moment it
 * settles. {@link #isSunBurnTick} is overridden to always return {@code false}; no other
 * fire/lava immunity is granted (ordinary fire/lava damage from any other source still applies normally,
 * same scoping rule {@code EnvironmentalOverride}'s own doc states for the filler case).
 *
 * <p>Size/stats, like {@link VillagerKingEntity}'s, are not ticket-mandated numbers: 1.5x vanilla Zombie's
 * 0.6x1.95 (-&gt; 0.9x2.9, matching {@link VillagerKingEntity}'s own scale for visual consistency across
 * Kings), 100 max health (5x vanilla Zombie's 20) and +4 attack damage over vanilla Zombie's own 3, to read
 * as a clearly tougher boss-like figure.
 */
public final class ZombieKingEntity extends Zombie {
    public ZombieKingEntity(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
        this.xpReward = 0;
        this.setPersistenceRequired();
    }

    public static EntityType.Builder<ZombieKingEntity> typeBuilder() {
        return EntityType.Builder.of(ZombieKingEntity::new, net.minecraft.world.entity.MobCategory.MISC)
            .sized(0.9F, 2.9F).clientTrackingRange(10);
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    @Override
    protected boolean isSunBurnTick() {
        return false;
    }

    /**
     * MINECRAFT-416: adds cross-type King-vs-King hostility on top of unmodified vanilla Zombie AI --
     * {@code super.registerGoals()} keeps every vanilla goalSelector/targetSelector goal (melee attack,
     * reinforcement calls, player/villager/golem targeting, {@code HurtByTargetGoal}, etc.) intact; this
     * only ADDS {@link KingHostilityTargetGoal} at targetSelector priority 0. See that goal's own class doc
     * for why priority 0 is safe here without needing to know vanilla Zombie's own priority numbers.
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.targetSelector.addGoal(0, new KingHostilityTargetGoal(this, Faction.ZOMBIE));
    }
}
