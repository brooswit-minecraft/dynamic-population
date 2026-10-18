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
 * <p><b>Round 5/6 fix (latch):</b> stop re-deriving "why is this mob on fire" from scratch, with a short time
 * window, on every fresh-ignition tick. Instead, the FIRST tick of a continuous burn is the only tick that
 * is ever attributed to a cause; whatever that attribution decides (sun-caused, or externally caused) is
 * LATCHED for the mob and carried forward for every tick of that SAME continuous burn, until the fire
 * actually goes out (remaining ticks reach zero) and a genuinely new burn can start. Round 6 also fixed the
 * latch keying on {@code overrideActive} (a suppression-eligibility fact) instead of direct cause evidence
 * -- see the history preserved in {@code SunImmunityTicking}'s own class doc.
 *
 * <p><b>Round 7 re-review fix (THIS version) -- the P1 finding the latch itself was still too blunt:</b>
 * a latched "externally caused" burn was previously never suppressed again for its ENTIRE remaining
 * duration, however long that turned out to be -- including every further tick where vanilla's OWN sun-burn
 * mechanism kept independently re-rolling and topping the fire back up on top of the already-burning
 * external fire (the very redundant-re-ignition behaviour the round-4/5 fix above was written to describe).
 * A mob hit once by a torch and then left standing in direct sun in a &gt;250-density cell would therefore
 * burn forever at full sun-stoked duration: the latch correctly remembered "this burn started externally"
 * but then used that one bit to excuse EVERY subsequent tick's fire-tick value, including portions that
 * were never part of the original external ignition at all.
 *
 * <p>The real fix: a boolean latch is the wrong shape for this problem. What must be tracked, for the
 * entire life of an externally-caused burn, is a running estimate of how many fire ticks the ORIGINAL
 * external cause would still have left if the sun mechanic were not repeatedly topping it up -- an
 * "external budget" that decays by exactly as many ticks pass between observations (via {@code
 * ticksElapsedSinceLastObservation}, supplied by the caller from real game time, not inferred from the fire
 * -tick value itself, which is exactly the value the sun mechanic is corrupting) and is only ever RAISED by
 * a genuine new direct-cause ignition event ({@code knownNonSunIgnitionCause} true on that tick). Whenever
 * the mob's actual remaining fire ticks sit ABOVE that budget while no direct cause justifies it, the excess
 * is attributed to vanilla's sun-roll topping the fire back up, and {@code SunImmunityTicking} is told to
 * actively clamp the mob's remaining fire ticks back down to the budget -- countering specifically that
 * top-up, tick by tick, rather than either leaving it in place (the round-6 bug) or clearing the fire
 * entirely (which would also wrongly erase the legitimate external portion). The fire still burns out on
 * its own natural external timeline; only the sun mechanic's repeated extensions on top of it are undone.
 */
public final class FireIgnitionTracking {
    private FireIgnitionTracking() { }

    /**
     * @param freshIgnition whether THIS tick is the start of a new burn (or a redundant re-ignition on top
     *     of a burn already in progress).
     * @param clearFire whether {@code SunImmunityTicking} should call {@code mob.clearFire()} this tick --
     *     ONLY for a burn whose cause is not (and never was) externally latched, i.e. a genuinely sun-caused
     *     burn.
     * @param clampFireTicks whether {@code SunImmunityTicking} should instead call {@code
     *     mob.setRemainingFireTicks(fireTicksAfterAction)} this tick -- countering a sun-roll top-up landing
     *     on top of an externally-caused burn without erasing the external portion. Mutually exclusive with
     *     {@code clearFire}.
     * @param fireTicksAfterAction the mob's remaining fire ticks once this tick's action (if any) is
     *     applied -- 0 if {@code clearFire}, the external budget if {@code clampFireTicks}, otherwise
     *     unchanged from {@code currentFireTicks}. This is exactly the value the caller must snapshot as
     *     {@code previousFireTicks} for the next tick.
     * @param nextProtected the externally-caused-burn latch value to remember for this mob going into the
     *     NEXT tick.
     * @param nextExternalBudgetTicks the external fire-tick budget to remember for this mob going into the
     *     NEXT tick -- see this class's doc. Meaningless (and always 0) when {@code nextProtected} is false.
     */
    public record Decision(boolean freshIgnition, boolean clearFire, boolean clampFireTicks,
            int fireTicksAfterAction, boolean nextProtected, int nextExternalBudgetTicks) { }

    /**
     * @param currentFireTicks the mob's remaining fire ticks THIS tick ({@code Mob#getRemainingFireTicks()}).
     * @param previousFireTicks the same value from the previous tick this mob was observed, or {@code null}
     *     if never observed before (treated the same as "was not on fire"). The caller MUST ensure this
     *     reflects the mob's ACTUAL remaining-fire-ticks value after the previous tick's own action (the
     *     previous {@code Decision#fireTicksAfterAction}), never the stale pre-action value.
     * @param previouslyProtected the externally-caused-burn latch carried over from the previous tick --
     *     {@code true} means THIS continuous burn was already determined, at its first tick, to have a
     *     known non-sun cause, and that fact is never revisited for the rest of this same burn.
     * @param previousExternalBudgetTicks the external fire-tick budget carried over from the previous tick
     *     (see this class's doc); ignored (treated as 0) when {@code previouslyProtected} is false.
     * @param ticksElapsedSinceLastObservation real game ticks elapsed since this mob was last observed
     *     (normally 1, every-tick observation; the caller derives it from actual game time, e.g. {@code
     *     ServerLevel#getGameTime()}, NOT from any fire-tick delta, since the fire-tick value itself is what
     *     the sun mechanic is busy corrupting). Clamped to a minimum of 0.
     * @param knownNonSunIgnitionCause whether a known non-sun ignition cause (fire/lava/soul-fire contact,
     *     a recent Flame-enchanted projectile hit, or a recent melee Fire Aspect hit) was observed THIS tick.
     * @param sunEligible vanilla's deterministic (non-random) sun-burn gate -- see {@code sunEligible} in
     *     {@code SunImmunityTicking}.
     * @param overrideActive whether the &gt;250-density light/sun override is active for this mob's faction
     *     right now. Deliberately NOT used to decide {@code externallyCaused} below -- see this class's
     *     doc for why conflating the two was the round-5/6 bug.
     */
    public static Decision decide(int currentFireTicks, Integer previousFireTicks, boolean previouslyProtected,
            int previousExternalBudgetTicks, long ticksElapsedSinceLastObservation, boolean knownNonSunIgnitionCause,
            boolean sunEligible, boolean overrideActive) {
        if (currentFireTicks <= 0) {
            // Fully extinguished: whatever burn was (or wasn't) externally caused is over, and so is
            // whatever was left of its external budget. The NEXT ignition, of any origin, starts a
            // completely fresh attribution -- this is what lets a later, truly independent sun ignition
            // still be suppressed correctly after an earlier external burn has run its course.
            return new Decision(false, false, false, 0, false, 0);
        }
        boolean trueFirstIgnition = previousFireTicks == null || previousFireTicks <= 0;
        boolean freshIgnition = trueFirstIgnition || currentFireTicks > previousFireTicks;

        // externallyCaused is the mob's ACTUAL ignition provenance for this continuous burn -- decided from
        // direct cause evidence ONLY, never from overrideActive. It is decided once, at the burn's true
        // first tick; a later redundant re-ignition jump on top of an ALREADY ongoing burn (vanilla's own
        // repeated sun-roll landing on top of an externally-caused fire) does not get to re-derive the
        // cause from scratch -- it can only LATCH IN new direct evidence, never erase cause evidence already
        // latched.
        boolean externallyCaused = trueFirstIgnition
            ? knownNonSunIgnitionCause
            : (previouslyProtected || knownNonSunIgnitionCause);

        // The external fire-tick BUDGET (round-7 fix): how many fire ticks the original external cause
        // would still have left if sun top-ups never happened. Not tracked at all for a burn that isn't
        // (and never was) externally caused. Raised to the actual current value only on a tick where a
        // genuine direct cause is observed (a real extension, not a sun top-up); otherwise it decays by
        // exactly the real elapsed ticks since it was last computed -- never by the mob's own (possibly
        // sun-corrupted) remaining-fire-ticks delta.
        int externalBudgetTicks;
        if (!externallyCaused) {
            externalBudgetTicks = 0;
        } else if (knownNonSunIgnitionCause) {
            externalBudgetTicks = currentFireTicks;
        } else {
            long elapsed = Math.max(0, ticksElapsedSinceLastObservation);
            externalBudgetTicks = (int) Math.max(0, previousExternalBudgetTicks - elapsed);
        }

        // A sun top-up on top of an externally-caused burn: the mob is still latched as externally caused,
        // THIS tick is not itself a new direct-cause event, yet the actual fire ticks sit above what the
        // external budget alone would produce -- the only thing that can have added the difference is
        // vanilla's own sun-burn mechanic re-igniting on top of the external fire.
        boolean sunToppedUpExternalBurn = externallyCaused && !knownNonSunIgnitionCause
            && currentFireTicks > externalBudgetTicks;

        // Only actively counter that top-up while suppression is actually eligible right now (sunEligible/
        // overrideActive) -- outside the override's scope, vanilla's own sun-burn behaviour is simply
        // allowed to proceed unmodified, same as it always has been for a mob this mod isn't suppressing.
        boolean clampFireTicks = sunToppedUpExternalBurn && overrideActive;

        // A genuinely sun-caused burn (never externally latched) is still fully suppressed exactly as
        // before -- shouldSuppress's own freshIgnitionThisTick parameter is deliberately passed as a
        // constant `true`: the CAUSE is what is only ever decided on a fresh/first tick (above); once known,
        // whether to ACT on it depends only on sunEligible/overrideActive right now, on every tick of the
        // burn, not on whether this particular tick happens to be a fresh-ignition tick.
        boolean clearFire = SunBurnSuppression.shouldSuppress(true, sunEligible, externallyCaused, overrideActive);

        int fireTicksAfterAction = clearFire ? 0 : clampFireTicks ? externalBudgetTicks : currentFireTicks;

        return new Decision(freshIgnition, clearFire, clampFireTicks, fireTicksAfterAction, externallyCaused,
            externalBudgetTicks);
    }
}
