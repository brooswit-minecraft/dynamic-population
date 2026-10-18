package io.github.brooswitminecraft.dynamicpopulation.combat;

import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.LevelField;
import io.github.brooswitminecraft.dynamicpopulation.propagation.PopulationSimulation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * MINECRAFT-390 (late-added requirement): suppresses sun-exposure burning ONLY for ordinary (filler-tagged,
 * NOT King) Zombie/Skeleton fillers while their OWN faction's local density is &gt; 250 (see
 * {@link EnvironmentalOverride}). {@link SunBurnSuppression#shouldSuppress} is the pure decision; this
 * class is the Minecraft-wiring half that derives its three inputs.
 *
 * <p><b>Review fix (P1-2), two iterations:</b>
 * <ol>
 * <li>The ORIGINAL version of this class cleared fire from ANY on-fire filler under the density override,
 * regardless of what actually ignited it -- also clearing ordinary fire/lava/Flame-enchantment ignition,
 * violating the ticket's explicit scoping.</li>
 * <li>The FIRST attempted fix reflectively re-invoked vanilla's {@code Mob#isSunBurnTick()} every tick and
 * only cleared fire when that returned true. This compiled, passed unit tests, and still did not actually
 * suppress sun-burning on a real server: confirmed by live testing (a filler zombie burned to death with
 * the override density confirmed active). Root cause: {@code isSunBurnTick()}'s own deterministic
 * conditions are gated behind a PER-TICK RANDOM ROLL (roughly a few percent chance per tick even in full
 * daylight), so a SECOND, INDEPENDENT invocation of it (ours, moments after vanilla's own internal one
 * already ignited the entity) only coincidentally agrees with whatever vanilla's roll actually did maybe
 * one tick in several dozen -- nowhere near reliable enough to catch an already-burning fire before it
 * does real damage over its ~160-tick duration.</li>
 * </ol>
 *
 * <p><b>This version instead replicates {@code isSunBurnTick()}'s DETERMINISTIC gate only (every check
 * except the final random roll) using entity/level state that is PUBLIC API</b> -- no reflection needed at
 * all: {@code Level#isDay()}, {@code Entity#getLightLevelDependentMagicValue()},
 * {@code Entity#isInWaterRainOrBubble()}, the public {@code isInPowderSnow}/{@code wasInPowderSnow} fields,
 * and {@code BlockAndTintGetter#canSeeSky}. Unlike the random-gated check, this deterministic signal stays
 * TRUE continuously for the whole daylight/sky-exposed/dry stretch an entity is actually in -- so every
 * tick during that window (not a rare coincidental one) it is checked, reliably catching and clearing any
 * sun-caused fire almost immediately after vanilla's own (randomly-timed) ignition happens.
 *
 * <p><b>Why {@code EntityTickEvent.Post}, not {@code Pre}:</b> vanilla's sun-ignition call
 * ({@code igniteForSeconds}, gated by {@code isSunBurnTick()}) happens INSIDE {@code Mob#aiStep()}, which
 * runs as part of the entity's own {@code tick()} -- so checking at {@code Post} observes the fire state
 * AFTER that already happened this tick, letting us react to it the same tick it occurs.
 *
 * <p><b>One remaining, honestly-scoped edge case</b> (unchanged from the original design, now actually
 * reliable rather than broken): a filler standing in LAVA/FIRE while ALSO meeting every one of the
 * deterministic sun conditions (full daylight, open sky overhead, not raining/wet, not in powder snow)
 * would have that genuinely non-sun fire cleared too, since this check cannot distinguish "on fire from
 * lava AND currently also sun-eligible" from "on fire purely from the sun" without a Mixin/ASM transformer
 * patching {@code igniteForSeconds} at its vanilla call site (not pursued here). This is a narrow
 * intersection (lava/fire source + full daylight + open sky + dry simultaneously) and is a far smaller gap
 * than the original bug (which touched ALL fire regardless of light/time/sky/weather) -- flagged here
 * rather than silently left, per the reviewer's own request.
 */
public final class SunImmunityTicking {
    private SunImmunityTicking() { }

    /**
     * Replicates {@code Mob#isSunBurnTick()}'s deterministic gate ONLY (every check except the final
     * per-tick random roll), using exclusively public API -- see this class's own javadoc for why the
     * random roll is deliberately excluded (re-rolling it independently is what made the first fix
     * attempt unreliable).
     */
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
        if (!mob.isOnFire()) {
            return;
        }
        LevelField field = PopulationSimulation.field(level, faction);
        int density = field.get(field.cellAt(mob.blockPosition()));
        boolean overrideActive = EnvironmentalOverride.active(density);
        if (SunBurnSuppression.shouldSuppress(mob.isOnFire(), sunEligible(level, mob), overrideActive)) {
            mob.clearFire();
        }
    }

    private static boolean isKing(Entity entity) {
        return entity instanceof ZombieKingEntity || entity instanceof SkeletonKingEntity;
    }
}
