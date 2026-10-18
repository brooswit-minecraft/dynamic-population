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

    /**
     * Whether THIS side of {@code Fillager.mobInteract} is allowed to evaluate {@link #canPromote} at all,
     * as opposed to always deferring (PASS) immediately. Only the server may: NeoForge's automatic config
     * sync is connection-time only, so a later live reload of the promotion cost can leave an
     * already-connected client holding a stale value in either direction. If the client ever used its own
     * (possibly stale) cost to decide a hand "already succeeded," it could end Minecraft's main-hand/
     * off-hand fallback on the wrong hand -- the one true fix in this method's own history (review on PR
     * #11) -- so this is {@code false} unconditionally for the client, never based on any cost comparison.
     */
    public static boolean shouldEvaluateOnThisSide(boolean isServerSide) {
        return isServerSide;
    }
}
