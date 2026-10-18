package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

/**
 * Pure decision logic for the emerald promotion economy (promotion itself -- removing the Fillager and
 * spawning a permanent villager -- happens in {@code Fillager.mobInteract}, which is not unit-testable
 * without a running game; this is the threshold check that drives it, extracted so it is).
 *
 * <p>Promotion never touches any cell's target density: the felt population cost comes entirely from the
 * bounded, non-instant {@code fillager_refill_per_interval} reconciliation rate (see
 * {@link FillagerReconciliation#decide}), never a direct write here.
 */
public final class FillagerPromotion {
    private FillagerPromotion() { }

    /**
     * Whether a held stack of {@code heldCount} emeralds (an item that is not an emerald must pass
     * {@code isEmerald = false}) is enough to promote at the given live-configured {@code cost}.
     */
    public static boolean canPromote(boolean isEmerald, int heldCount, int cost) {
        return isEmerald && heldCount >= cost;
    }
}
