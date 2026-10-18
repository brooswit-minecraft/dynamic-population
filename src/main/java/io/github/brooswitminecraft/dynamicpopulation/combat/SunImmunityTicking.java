package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.Map;
import java.util.WeakHashMap;

import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.LevelField;
import io.github.brooswitminecraft.dynamicpopulation.propagation.PopulationSimulation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * MINECRAFT-390 (late-added requirement): suppresses sun-exposure burning ONLY for ordinary (filler-tagged,
 * NOT King) Zombie/Skeleton fillers while their OWN faction's local density is &gt; 250 (see
 * {@link EnvironmentalOverride}). {@link SunBurnSuppression#shouldSuppress} is the pure decision; this
 * class is the Minecraft-wiring half that derives its four inputs.
 *
 * <p><b>Review fix (P1-2), THREE iterations -- each one caught live or by re-review, not by unit tests alone:</b>
 * <ol>
 * <li>Original: cleared fire from ANY on-fire filler under the density override, regardless of cause --
 * also cleared ordinary fire/lava/Flame-enchantment ignition.</li>
 * <li>Round 2: reflectively re-invoked vanilla's {@code Mob#isSunBurnTick()} every tick while on fire, only
 * clearing when that returned true. Compiled and passed unit tests; did NOT work live -- confirmed by
 * booting a real server and watching a filler zombie burn to death with the override confirmed active.
 * Root cause: {@code isSunBurnTick()}'s own conditions are gated behind a per-tick RANDOM ROLL, so a
 * second, independent invocation of it only coincidentally agrees with vanilla's own roll.</li>
 * <li>Round 3 (the fix immediately before this one): replaced the random-gated re-check with vanilla's
 * {@code isSunBurnTick()} DETERMINISTIC conditions only (daytime/light/dryness/sky-visible), clearing fire
 * whenever on fire AND those conditions held. This WAS reliable (verified live), but re-review correctly
 * identified it was STILL too broad: {@link net.minecraft.world.level.block.BaseFireBlock} ignites an
 * entity via the IDENTICAL {@code igniteForSeconds(8.0F)} call vanilla's sun-burn path uses, and lava/fire
 * contact can obviously co-occur with "daytime, bright, open sky, dry" (a fire block burning in an open
 * sunlit field) -- duration/state alone can never disambiguate the two paths.</li>
 * </ol>
 *
 * <p><b>This version (round 4) only acts on the EXACT TICK a FRESH ignition happens</b> (tracked via
 * {@link #LAST_FIRE_TICKS}, a per-mob previous-tick snapshot: a fresh ignition is a jump up in remaining
 * fire ticks, or a transition from not-on-fire to on-fire -- a naturally DECAYING count, whatever its true
 * origin, is left completely alone), <b>and, at that exact moment, positively rules out every KNOWN
 * non-sun ignition cause this mod can detect</b> before treating it as sun-caused:
 * <ul>
 * <li>Standing in/touching {@code Blocks.FIRE}, {@code Blocks.SOUL_FIRE}, or lava THIS tick
 * ({@link #touchingFireOrLava}) -- covers ordinary fire/lava contact ignition.</li>
 * <li>Having just been struck by a projectile that was itself on fire ({@link #onProjectileImpact}, a
 * {@code Flame}-enchanted arrow sets ITSELF alight at shoot time, which is exactly how
 * {@code AbstractArrow#onHitEntity} itself decides whether to ignite its target -- see
 * {@link #RECENT_FLAME_HIT}) -- covers Flame-enchantment ignition.</li>
 * </ul>
 * Only when NEITHER known cause applies, the fresh ignition coincides with vanilla's OWN deterministic
 * sun-tick conditions ({@link #sunEligible}), and the density override is active, is the fire cleared.
 *
 * <p><b>Remaining honestly-scoped gap:</b> this positively rules out the three ignition paths this mod
 * knows about (fire/soul-fire/lava contact, Flame-enchanted arrows); it cannot rule out a MODDED ignition
 * source using some other mechanism entirely, nor a vanilla path this review missed. A true 100% guarantee
 * would still require a Mixin/ASM transformer patching {@code igniteForSeconds} at vanilla's own sun-check
 * call site, which this mod does not currently have the tooling for and has not pursued.
 */
public final class SunImmunityTicking {
    private SunImmunityTicking() { }

    /** Previous tick's remaining-fire-ticks snapshot, per mob -- how a "fresh ignition" is detected. */
    private static final Map<Mob, Integer> LAST_FIRE_TICKS = new WeakHashMap<>();

    /**
     * Mobs a Flame-enchanted (self-on-fire) projectile JUST hit, valid for a small number of ticks --
     * {@code ProjectileImpactEvent} fires before the projectile's own {@code onHitEntity} runs, so this is
     * set slightly ahead of the ignition it is meant to explain; a short grace window covers that ordering.
     */
    private static final Map<Mob, Long> RECENT_FLAME_HIT_EXPIRY_TICK = new WeakHashMap<>();
    private static final long FLAME_HIT_GRACE_TICKS = 3L;

    /** Replicates {@code Mob#isSunBurnTick()}'s deterministic gate only (every check except the final per-tick random roll), using exclusively public API. */
    private static boolean sunEligible(ServerLevel level, Mob mob) {
        if (!level.isDay()) {
            return false;
        }
        if (mob.getLightLevelDependentMagicValue() <= 0.5F) {
            return false;
        }
        if (mob.isInWaterRainOrBubble() || mob.isInPowderSnow || mob.wasInPowderSnow) {
            return false;
        }
        BlockPos eyePos = BlockPos.containing(mob.getX(), mob.getEyeY(), mob.getZ());
        return level.canSeeSky(eyePos);
    }

    /** Standing in/touching an ordinary contact ignition source THIS tick -- fire, soul fire, or lava. */
    private static boolean touchingFireOrLava(ServerLevel level, Mob mob) {
        BlockPos feet = mob.blockPosition();
        var feetState = level.getBlockState(feet);
        if (feetState.is(Blocks.FIRE) || feetState.is(Blocks.SOUL_FIRE) || feetState.getFluidState().is(FluidTags.LAVA)) {
            return true;
        }
        // Also check one block below: a mob standing ON TOP of a fire/soul-fire block has its own feet
        // position one block above the fire block itself in vanilla's own block-position convention.
        var belowState = level.getBlockState(feet.below());
        return belowState.is(Blocks.FIRE) || belowState.is(Blocks.SOUL_FIRE);
    }

    private static boolean recentFlameHit(ServerLevel level, Mob mob) {
        Long expiry = RECENT_FLAME_HIT_EXPIRY_TICK.get(mob);
        return expiry != null && level.getGameTime() <= expiry;
    }

    /** Marks a mob as just having been struck by a self-on-fire (Flame-enchanted) projectile. */
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (!projectile.isOnFire() || !(event.getRayTraceResult() instanceof EntityHitResult hit)) {
            return;
        }
        if (!(projectile.level() instanceof ServerLevel level)) {
            return;
        }
        if (hit.getEntity() instanceof Mob mob) {
            RECENT_FLAME_HIT_EXPIRY_TICK.put(mob, level.getGameTime() + FLAME_HIT_GRACE_TICKS);
        }
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !PopulationSimulation.simulated(level)) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        Faction faction;
        if (mob instanceof Zombie zombie && !isKing(zombie) && zombie.getTags().contains(FillerMarkers.ZOMBIE_FILLER_TAG)) {
            faction = Faction.ZOMBIE;
        } else if (mob instanceof Skeleton skeleton && !isKing(skeleton) && skeleton.getTags().contains(FillerMarkers.SKELETON_FILLER_TAG)) {
            faction = Faction.SKELETON;
        } else {
            return;
        }
        int currentFireTicks = mob.getRemainingFireTicks();
        Integer previousFireTicks = LAST_FIRE_TICKS.put(mob, currentFireTicks);
        boolean freshIgnition = currentFireTicks > 0
            && (previousFireTicks == null || previousFireTicks <= 0 || currentFireTicks > previousFireTicks);
        if (!freshIgnition) {
            return;
        }
        LevelField field = PopulationSimulation.field(level, faction);
        int density = field.get(field.cellAt(mob.blockPosition()));
        boolean overrideActive = EnvironmentalOverride.active(density);
        boolean knownNonSunCause = touchingFireOrLava(level, mob) || recentFlameHit(level, mob);
        if (SunBurnSuppression.shouldSuppress(freshIgnition, sunEligible(level, mob), knownNonSunCause, overrideActive)) {
            mob.clearFire();
        }
    }

    private static boolean isKing(Entity entity) {
        return entity instanceof ZombieKingEntity || entity instanceof SkeletonKingEntity;
    }
}
