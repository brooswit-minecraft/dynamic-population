package io.github.brooswitminecraft.dynamicpopulation.combat;

/**
 * Pure decision for {@code SunImmunityTicking} (review fix P1-2): whether to clear fire from a filler
 * Zombie/Skeleton THIS tick. No Minecraft classes -- the three inputs are already-derived plain booleans,
 * so this is directly unit-testable the same way {@code FillagerReconciliation}'s pure functions are.
 *
 * <p>{@code sunConditionPresent} MUST be vanilla's own {@code Mob#isSunBurnTick()} result for this exact
 * entity this exact tick (obtained via reflection at the one real call site, since filler Zombies/Skeletons
 * are literal vanilla instances with no override point -- see {@code SunImmunityTicking}'s own javadoc for
 * the full rationale and the one remaining edge case this cannot fully close without a Mixin/ASM
 * transformer). Requiring it explicitly here (rather than letting the caller pass {@code true}
 * unconditionally whenever the entity is on fire, which was the actual P1-2 bug) is what scopes this to
 * sunlight specifically: ordinary fire/lava/Flame-enchantment ignition does NOT make
 * {@code isSunBurnTick()} true (that check has nothing to do with contact fire sources at all -- it is
 * purely a function of daytime/sky-visibility/light-level/dryness), so {@code shouldSuppress} returns
 * {@code false} for it regardless of density, and the fire is left completely untouched.
 */
public final class SunBurnSuppression {
    private SunBurnSuppression() { }

    public static boolean shouldSuppress(boolean isOnFire, boolean sunConditionPresent, boolean overrideActive) {
        return isOnFire && sunConditionPresent && overrideActive;
    }
}
