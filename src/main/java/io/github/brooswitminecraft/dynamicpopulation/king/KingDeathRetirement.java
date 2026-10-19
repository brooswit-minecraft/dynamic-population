package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.List;
import java.util.UUID;

/**
 * Pure genuine-King-death decision (MINECRAFT-414): whether a dying entity's id is still a real,
 * currently-registered King. No Minecraft classes -- directly unit-testable.
 *
 * <p>This single check is what makes {@code KingDeathHandling} safe to dedupe and scope-correct without any
 * extra bookkeeping: a SECOND {@code LivingDeathEvent} callback for the same entity (vanilla should never
 * fire one, but a modded damage path might try) finds the id already removed by the first callback and is
 * a no-op; an administrative discard via {@link KingManager#removeNearest} already removes the record
 * BEFORE discarding the entity, so if that discard ever still routed through a death event, this check
 * would already see the id as not-registered and skip it too.
 */
public final class KingDeathRetirement {
    private KingDeathRetirement() { }

    /** Whether {@code id} is still present in {@code kings} -- a genuine, not-yet-retired King death. */
    public static boolean isGenuineKingDeath(List<KingRecord.Entry> kings, UUID id) {
        return kings.stream().anyMatch(entry -> entry.id().equals(id));
    }
}
