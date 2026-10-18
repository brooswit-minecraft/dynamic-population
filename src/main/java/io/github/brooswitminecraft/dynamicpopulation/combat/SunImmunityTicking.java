package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.Map;
import java.util.WeakHashMap;

import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.LevelField;
import io.github.brooswitminecraft.dynamicpopulation.propagation.PopulationSimulation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * MINECRAFT-390 (late-added requirement): suppresses sun-exposure burning ONLY for ordinary (filler-tagged,
 * NOT King) Zombie/Skeleton fillers while their OWN faction's local density is &gt; 250 (see
 * {@link EnvironmentalOverride}). {@link SunBurnSuppression#shouldSuppress} and {@link FireIgnitionTracking}
 * are the pure decisions; this class is the Minecraft-wiring half that derives their inputs.
 *
 * <p><b>Review fix (P1-2), FOUR iterations -- each one caught live or by re-review, not by unit tests alone:</b>
 * <ol>
 * <li>Original: cleared fire from ANY on-fire filler under the density override, regardless of cause --
 * also cleared ordinary fire/lava/Flame-enchantment ignition.</li>
 * <li>Round 2: reflectively re-invoked vanilla's {@code Mob#isSunBurnTick()} every tick while on fire, only
 * clearing when that returned true. Compiled and passed unit tests; did NOT work live -- confirmed by
 * booting a real server and watching a filler zombie burn to death with the override confirmed active.
 * Root cause: {@code isSunBurnTick()}'s own conditions are gated behind a per-tick RANDOM ROLL, so a
 * second, independent invocation of it only coincidentally agrees with vanilla's own roll.</li>
 * <li>Round 3: replaced the random-gated re-check with vanilla's {@code isSunBurnTick()} DETERMINISTIC
 * conditions only (daytime/light/dryness/sky-visible), clearing fire whenever on fire AND those conditions
 * held. This WAS reliable (verified live), but re-review correctly identified it was STILL too broad:
 * {@link net.minecraft.world.level.block.BaseFireBlock} ignites an entity via the IDENTICAL
 * {@code igniteForSeconds(8.0F)} call vanilla's sun-burn path uses, and lava/fire contact can obviously
 * co-occur with "daytime, bright, open sky, dry" -- duration/state alone can never disambiguate the two
 * paths.</li>
 * <li>Round 4: only acted on the EXACT TICK a fresh ignition happened (a jump up in remaining fire ticks, or
 * a transition from not-on-fire to on-fire), positively ruling out fire/soul-fire/lava contact and a
 * recently-observed Flame-enchanted projectile hit (a short time-bounded grace window) before treating a
 * fresh ignition as sun-caused. Re-review found this left two gaps open: melee Fire Aspect (a different
 * ignition code path than a Flame-enchanted projectile's) wasn't excluded at all, and the short grace window
 * could expire WHILE an externally-caused burn was still active, so a later redundant re-ignition (vanilla's
 * OWN sun-burn roll firing again on top of an already-burning mob -- see {@link FireIgnitionTracking}'s
 * class doc for why this is common, not a rare edge case) would wrongly clear the whole burn.</li>
 * </ol>
 *
 * <p><b>This version (round 5)</b> adds a melee Fire Aspect detector ({@link #onLivingDamagePost}, mirroring
 * {@link #onProjectileImpact}'s projectile-Flame detector) and, far more importantly, replaces the
 * time-bounded "re-justify every fresh-ignition tick" scheme with {@link FireIgnitionTracking}'s latch: the
 * FIRST tick of a continuous burn is attributed once (cleared as sun-caused, or left alone as externally
 * caused); that decision is then remembered for the mob and never revisited for the rest of that SAME
 * continuous burn, however many further redundant ignition jumps land on top of it, until the fire actually
 * extinguishes and a genuinely new burn can start fresh. See {@link FireIgnitionTracking} for the full
 * reasoning and the pure, directly-unit-tested decision logic.
 *
 * <p><b>Remaining honestly-scoped gap:</b> this positively rules out the ignition paths this mod knows about
 * (fire/soul-fire/lava contact, Flame-enchanted projectiles, melee Fire Aspect); it cannot rule out a MODDED
 * ignition source using some other mechanism entirely, nor a vanilla path this review missed. A true 100%
 * guarantee would still require a Mixin/ASM transformer patching {@code igniteForSeconds} (or
 * {@code Zombie#aiStep}'s sun-burn call site specifically) directly, which this mod does not currently have
 * the tooling for and has not pursued -- the latch above is what keeps that residual gap from compounding
 * into a wrongly-cleared ONGOING burn, even if some future unenumerated cause slips past the positive checks
 * on its very first tick.
 */
public final class SunImmunityTicking {
    private SunImmunityTicking() { }

    /** Previous tick's remaining-fire-ticks snapshot, per mob -- how a "fresh ignition" is detected. */
    private static final Map<Mob, Integer> LAST_FIRE_TICKS = new WeakHashMap<>();

    /**
     * Whether this mob's CURRENT, continuous burn has already been attributed (and latched) as externally
     * caused -- see {@link FireIgnitionTracking}. Cleared once the mob's fire actually goes out.
     */
    private static final Map<Mob, Boolean> PROTECTED_BURN = new WeakHashMap<>();

    /**
     * Mobs a Flame-enchanted (self-on-fire) projectile JUST hit, valid for a small number of ticks --
     * {@code ProjectileImpactEvent} fires before the projectile's own {@code onHitEntity} runs, so this is
     * set slightly ahead of the ignition it is meant to explain; a short grace window covers that ordering.
     */
    private static final Map<Mob, Long> RECENT_FLAME_HIT_EXPIRY_TICK = new WeakHashMap<>();

    /**
     * Mobs a melee attacker wielding a Fire Aspect weapon JUST hit, valid for a small number of ticks --
     * {@code LivingDamageEvent.Post} fires from {@code LivingEntity#actuallyHurt} BEFORE the attacker's
     * {@code doHurtTarget}/{@code attack} calls {@code EnchantmentHelper.doPostAttackEffects} (which is what
     * actually applies Fire Aspect's ignition), so this too is set slightly ahead of the ignition it is
     * meant to explain -- the same ordering {@link #RECENT_FLAME_HIT_EXPIRY_TICK} covers for projectiles.
     */
    private static final Map<Mob, Long> RECENT_MELEE_FIRE_ASPECT_HIT_EXPIRY_TICK = new WeakHashMap<>();

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

    private static boolean recentMeleeFireAspectHit(ServerLevel level, Mob mob) {
        Long expiry = RECENT_MELEE_FIRE_ASPECT_HIT_EXPIRY_TICK.get(mob);
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

    /**
     * Marks a mob as just having been struck by a melee attacker wielding a Fire Aspect weapon -- the
     * melee counterpart of {@link #onProjectileImpact}. Fired from {@code LivingEntity#actuallyHurt} for
     * EVERY damage instance, so this only looks at the attacker's weapon enchantment, not at whether the
     * victim is actually on fire yet (it isn't, at this point in the attack sequence -- see this field's
     * own doc on {@link #RECENT_MELEE_FIRE_ASPECT_HIT_EXPIRY_TICK}).
     */
    @SubscribeEvent
    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker)) {
            return;
        }
        ItemStack weapon = attacker.getWeaponItem();
        if (weapon.isEmpty()) {
            return;
        }
        Holder<Enchantment> fireAspect = level.registryAccess()
            .registryOrThrow(Registries.ENCHANTMENT)
            .getHolderOrThrow(Enchantments.FIRE_ASPECT);
        if (weapon.getEnchantmentLevel(fireAspect) <= 0) {
            return;
        }
        RECENT_MELEE_FIRE_ASPECT_HIT_EXPIRY_TICK.put(mob, level.getGameTime() + FLAME_HIT_GRACE_TICKS);
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
        boolean previouslyProtected = Boolean.TRUE.equals(PROTECTED_BURN.get(mob));
        boolean knownNonSunCause = touchingFireOrLava(level, mob) || recentFlameHit(level, mob) || recentMeleeFireAspectHit(level, mob);

        LevelField field = PopulationSimulation.field(level, faction);
        int density = field.get(field.cellAt(mob.blockPosition()));
        boolean overrideActive = EnvironmentalOverride.active(density);

        FireIgnitionTracking.Decision decision = FireIgnitionTracking.decide(currentFireTicks, previousFireTicks,
            previouslyProtected, knownNonSunCause, sunEligible(level, mob), overrideActive);

        if (decision.nextProtected()) {
            PROTECTED_BURN.put(mob, Boolean.TRUE);
        } else {
            PROTECTED_BURN.remove(mob);
        }
        if (decision.clearFire()) {
            mob.clearFire();
        }
    }

    private static boolean isKing(Entity entity) {
        return entity instanceof ZombieKingEntity || entity instanceof SkeletonKingEntity;
    }
}
