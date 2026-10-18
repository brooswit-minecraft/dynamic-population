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
 * attributed to a cause; whatever that attribution decides (sun-caused, or externally caused) is LATCHED
 * for the mob and carried forward for every tick of that SAME continuous burn, however many further
 * redundant re-ignition jumps vanilla fires on top of it, until the fire actually goes out (remaining ticks
 * reach zero) and a genuinely new burn can start. A latched "externally caused" burn can never be clawed
 * back and reclassified later just because the short-lived evidence that justified it has expired -- the
 * caller ({@code SunImmunityTicking}) only needs to remember one boolean per mob, not a timestamp.
 *
 * <p><b>Round 6 re-review fix:</b> the previous version of this latch conflated two DIFFERENT facts into
 * one boolean -- "was this burn caused by something other than the sun" (the burn's actual provenance,
 * decided once, at ignition) and "did {@code overrideActive} happen to be true at that exact moment" (an
 * unrelated fact about whether suppression was ELIGIBLE to apply right then). Keying the latch on the
 * latter meant an ordinary ignition that started while {@code overrideActive} was false -- for ANY reason,
 * including a genuinely sun-caused burn that started a tick before the density override activated -- got
 * permanently mislabeled "externally caused/protected" the instant {@code overrideActive} happened to be
 * false, and the latch then NEVER revisited that misclassification even after the override turned on and
 * the burn was still ongoing. The fix below decides {@code externallyCaused} from direct cause evidence
 * ({@code knownNonSunIgnitionCause}) ONLY, independent of {@code overrideActive}, and latches exactly that
 * fact; whether to actually clear the fire ({@code shouldSuppress}'s job) is then re-derived EVERY tick from
 * the current {@code sunEligible}/{@code overrideActive} inputs against that latched cause -- so a density
 * crossing (or a mob walking into an already-&gt;250 cell) mid-burn can correctly start or stop suppression
 * on the very next tick, without ever retroactively changing what actually caused the fire in the first
 * place. Those are two separate facts and are no longer conflated into one latch state.
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
     *     if never observed before (treated the same as "was not on fire"). The caller MUST ensure this
     *     reflects the mob's ACTUAL remaining-fire-ticks value after the previous tick's own action (0 if
     *     {@code clearFire()} was called that tick) -- see {@link #snapshotFireTicksAfterThisTick}.
     * @param previouslyProtected the externally-caused-burn latch carried over from the previous tick --
     *     {@code true} means THIS continuous burn was already determined, at its first tick, to have a
     *     known non-sun cause, and that fact is never revisited for the rest of this same burn.
     * @param knownNonSunIgnitionCause whether a known non-sun ignition cause (fire/lava/soul-fire contact,
     *     a recent Flame-enchanted projectile hit, or a recent melee Fire Aspect hit) was observed THIS tick.
     * @param sunEligible vanilla's deterministic (non-random) sun-burn gate -- see {@code sunEligible} in
     *     {@code SunImmunityTicking}.
     * @param overrideActive whether the &gt;250-density light/sun override is active for this mob's faction
     *     right now. Deliberately NOT used to decide {@code externallyCaused} below -- see this class's
     *     doc for why conflating the two was the round-5 bug.
     */
    public static Decision decide(int currentFireTicks, Integer previousFireTicks, boolean previouslyProtected,
            boolean knownNonSunIgnitionCause, boolean sunEligible, boolean overrideActive) {
        if (currentFireTicks <= 0) {
            // Fully extinguished: whatever burn was (or wasn't) externally caused is over. The NEXT
            // ignition, of any origin, starts a completely fresh attribution -- this is what lets a later,
            // truly independent sun ignition still be suppressed correctly after an earlier external burn
            // has run its course.
            return new Decision(false, false, false);
        }
        boolean trueFirstIgnition = previousFireTicks == null || previousFireTicks <= 0;
        boolean freshIgnition = trueFirstIgnition || currentFireTicks > previousFireTicks;

        // externallyCaused is the mob's ACTUAL ignition provenance for this continuous burn -- decided from
        // direct cause evidence ONLY, never from overrideActive (a completely separate fact about whether
        // suppression is currently eligible, not about what caused the fire). It is decided once, at the
        // burn's true first tick; a later redundant re-ignition jump on top of an ALREADY ongoing burn
        // (vanilla's own repeated sun-roll landing on top of an externally-caused fire, the round-4 gap)
        // does not get to re-derive the cause from scratch -- it can only LATCH IN new direct evidence
        // (knownNonSunIgnitionCause true right now), never erase cause evidence already latched.
        boolean externallyCaused = trueFirstIgnition
            ? knownNonSunIgnitionCause
            : (previouslyProtected || knownNonSunIgnitionCause);

        // Suppression eligibility is a SEPARATE fact, re-derived EVERY tick (fresh or not) from the latched
        // cause against the CURRENT sun/override conditions: the override turning on or off mid-burn (a
        // density crossing, or the mob walking into/out of a >250 cell) changes whether suppression applies
        // RIGHT NOW, but never retroactively changes what actually caused the fire. shouldSuppress's own
        // "freshIgnitionThisTick" parameter is deliberately passed as a constant `true` here -- NOT this
        // tick's actual freshIgnition -- because gating suppression on freshIgnition was exactly the
        // round-4 mistake this class exists to move past: the CAUSE (externallyCaused) is what is only
        // ever decided on a fresh/first tick, decided above; once that is known, whether to ACT on it this
        // tick depends only on sunEligible/overrideActive right now, on every tick of the burn, fresh or
        // decaying, not on whether this particular tick happens to be a fresh-ignition tick.
        boolean clear = SunBurnSuppression.shouldSuppress(true, sunEligible, externallyCaused, overrideActive);
        return new Decision(freshIgnition, clear, externallyCaused);
    }

    /**
     * The remaining-fire-ticks value the caller must snapshot for comparison on the NEXT tick -- review fix
     * P2, round 6. MUST reflect the mob's state AFTER this tick's own action, never the stale pre-clear
     * value: if {@code decide}'s {@code clearFire} was honoured this tick, the mob's real remaining-fire-
     * ticks is 0 going into the next tick, not whatever it was before the clear. Recording the pre-clear
     * value instead meant an immediate same-duration re-ignition on the very next tick (e.g. cleared from
     * 160, then re-ignited straight back to 160) compared {@code currentFireTicks(160) > previousFireTicks
     * (160)} as false -- wrongly NOT a fresh ignition -- and the second, independent ignition silently
     * inherited whatever latch state the first one left behind instead of ever being re-evaluated.
     */
    public static int snapshotFireTicksAfterThisTick(int currentFireTicksThisTick, boolean clearedThisTick) {
        return clearedThisTick ? 0 : currentFireTicksThisTick;
    }
}
