package io.github.brooswitminecraft.dynamicpopulation.combat;

/**
 * Pure decision for {@code SunImmunityTicking} (review fix P1-2, second round): whether to clear fire from
 * a filler Zombie/Skeleton THIS tick. No Minecraft classes -- the inputs are already-derived plain booleans,
 * so this is directly unit-testable the same way {@code FillagerReconciliation}'s pure functions are.
 *
 * <p><b>Why "fresh ignition" and "known non-sun cause", not just "is currently on fire":</b> the first
 * round's fix (re-checking {@code isSunBurnTick()} every tick while on fire) turned out to be unreliable
 * (see {@code SunImmunityTicking}'s class doc for the live-testing failure), and a second round's naive
 * "on fire + deterministic daytime/light/sky conditions -&gt; clear" is ALSO wrong: standing in a vanilla
 * {@code Blocks.FIRE} block uses the IDENTICAL {@code igniteForSeconds(8.0F)} call vanilla's own sun-burn
 * path uses ({@code BaseFireBlock} vs {@code Mob#isSunBurnTick()}'s own call site), so duration/state alone
 * can never disambiguate the two -- a fire block burning in an open, sunlit field would satisfy "daytime +
 * bright + open sky" just as much as genuine sun exposure.
 *
 * <p>The fix: only ever act on the EXACT TICK a fresh ignition happens (a jump in remaining fire ticks, or
 * a transition from not-on-fire to on-fire -- {@code SunImmunityTicking} tracks this), and at that exact
 * moment, positively rule out every KNOWN non-sun cause this mod can detect (standing in/touching fire,
 * soul fire, or lava THIS tick; or having just been struck by a Flame-enchanted projectile) before treating
 * it as sun-caused. A continuing, already-decaying fire (whatever its true origin) is left completely
 * alone by this check, since {@code freshIgnitionThisTick} is false for it -- this is what keeps the
 * "lingering embers from an earlier contact" case from being misclassified days after the fact.
 */
public final class SunBurnSuppression {
    private SunBurnSuppression() { }

    public static boolean shouldSuppress(boolean freshIgnitionThisTick, boolean sunEligible,
            boolean knownNonSunIgnitionCause, boolean overrideActive) {
        return freshIgnitionThisTick && sunEligible && !knownNonSunIgnitionCause && overrideActive;
    }
}
