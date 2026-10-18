package io.github.brooswitminecraft.dynamicpopulation.combat;

/**
 * Pure per-tick decision step for {@code SunImmunityTicking} (review fix P1, round 5). No Minecraft classes,
 * same reasoning as {@link SunBurnSuppression}: this is directly unit-testable.
 *
 * <p><b>The round-4 gap this closes:</b> round 4 ({@link SunBurnSuppression}'s own class doc) only ever
 * excluded a KNOWN non-sun cause (fire/lava contact, a recently-observed Flame-enchanted projectile hit) at
 * the EXACT TICK a fresh ignition happened, using a short time-bounded "just happened" window. Re-review
 * found two more gaps this left open, both really the same root problem -- a time-bounded window is the
 * wrong tool for attributing an ONGOING burn:
 * <ol>
 * <li>Melee Fire Aspect ignites via a different code path than a Flame-enchanted projectile (applied in
 * {@code LivingEntity#doHurtTarget}/{@code Player#attack} via {@code EnchantmentHelper.doPostAttackEffects},
 * not {@code AbstractArrow#onHitEntity}) and simply wasn't excluded at all.</li>
 * <li>Far more importantly: an entity set on fire by ANY known external cause keeps decaying over many
 * ticks. {@code Entity#igniteForTicks} only raises the remaining-fire-tick count when the new duration
 * EXCEEDS the current remaining count -- which is true almost any time vanilla's OWN per-tick random
 * sun-burn roll independently fires on a mob that is already burning from an unrelated external cause
 * (the external burn has usually decayed below sun-burning's fixed 8-second/160-tick duration by then). That
 * redundant vanilla re-ignition reads as a "fresh ignition" by the jump-detection rule -- and by the time it
 * happens, any short grace window used to explain the ORIGINAL external cause has long since expired, so the
 * code has lost track of why the mob is burning and wrongly clears the whole burn, external portion
 * included.</li>
 * </ol>
 *
 * <p><b>The fix:</b> stop re-deriving "why is this mob on fire" from scratch, with a short time window, on
 * every fresh-ignition tick. Instead, the FIRST tick of a continuous burn is the only tick that is ever
 * attributed to a cause; whatever that attribution decides (clear it as sun-caused, or leave it alone as
 * externally caused) is LATCHED for the mob and carried forward for every tick of that SAME continuous burn,
 * however many further redundant re-ignition jumps vanilla fires on top of it, until the fire actually goes
 * out (remaining ticks reach zero) and a genuinely new burn can start. A latched "leave it alone" burn can
 * never be clawed back and cleared later just because the short-lived evidence that justified it has expired
 * -- the caller ({@code SunImmunityTicking}) only needs to remember one boolean per mob, not a timestamp.
 */
public final class FireIgnitionTracking {
    private FireIgnitionTracking() { }

    /**
     * @param freshIgnition whether THIS tick is the start of a new burn (or a redundant re-ignition on top
     *     of a burn already in progress) -- {@code false} means "leave this mob's fire completely alone",
     *     the same as the round-4 behaviour.
     * @param clearFire whether {@code SunImmunityTicking} should call {@code mob.clearFire()} this tick.
     * @param nextProtected the protected-burn latch value to remember for this mob going into the NEXT tick.
     */
    public record Decision(boolean freshIgnition, boolean clearFire, boolean nextProtected) { }

    /**
     * @param currentFireTicks the mob's remaining fire ticks THIS tick ({@code Mob#getRemainingFireTicks()}).
     * @param previousFireTicks the same value from the previous tick this mob was observed, or {@code null}
     *     if never observed before (treated the same as "was not on fire").
     * @param previouslyProtected the protected-burn latch carried over from the previous tick.
     * @param knownNonSunIgnitionCause whether a known non-sun ignition cause (fire/lava/soul-fire contact,
     *     a recent Flame-enchanted projectile hit, or a recent melee Fire Aspect hit) was observed THIS tick.
     * @param sunEligible vanilla's deterministic (non-random) sun-burn gate -- see {@code sunEligible} in
     *     {@code SunImmunityTicking}.
     * @param overrideActive whether the &gt;250-density light/sun override is active for this mob's faction
     *     right now.
     */
    public static Decision decide(int currentFireTicks, Integer previousFireTicks, boolean previouslyProtected,
            boolean knownNonSunIgnitionCause, boolean sunEligible, boolean overrideActive) {
        if (currentFireTicks <= 0) {
            // Fully extinguished: whatever burn was (or wasn't) protected is over. The NEXT ignition, of any
            // origin, starts a completely fresh attribution -- this is what lets a later, truly independent
            // sun ignition still be suppressed correctly after an earlier external burn has run its course.
            return new Decision(false, false, false);
        }
        boolean freshIgnition = previousFireTicks == null || previousFireTicks <= 0 || currentFireTicks > previousFireTicks;
        if (!freshIgnition) {
            // A naturally decaying count (or an in-place no-op re-ignition): never re-evaluated, latch
            // carried forward unchanged -- this mob's fire is simply not looked at again until either it
            // goes out (handled above) or a real jump happens (handled below).
            return new Decision(false, false, previouslyProtected);
        }
        // Once a burn has been latched as protected, no later redundant re-ignition jump on top of it is
        // ever re-attributed from scratch -- it stays protected until the fire actually goes out, regardless
        // of whether the short-lived evidence that originally justified it (a contact check, a grace window)
        // is still true THIS exact tick.
        boolean effectiveKnownCause = knownNonSunIgnitionCause || previouslyProtected;
        boolean clear = SunBurnSuppression.shouldSuppress(true, sunEligible, effectiveKnownCause, overrideActive);
        return new Decision(true, clear, !clear);
    }
}
