package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.List;
import java.util.UUID;

/**
 * Pure genuine-King-death decision (MINECRAFT-414): whether a dying entity's id is still a real,
 * currently-registered King. No Minecraft classes -- directly unit-testable.
 *
 * <p>This single check is what makes {@code KingDeathHandling} safe to dedupe and scope-correct without any
 * extra bookkeeping: a SECOND confirmed-death callback for the same entity (should never happen -- an
 * entity is only removed once -- but defensive regardless) finds the id already removed by the first
 * callback and is a no-op; an administrative discard via {@link KingManager#removeNearest} already removes
 * the record BEFORE discarding the entity, so this check would already see the id as not-registered too.
 */
public final class KingDeathRetirement {
    private KingDeathRetirement() { }

    /** Whether {@code id} is still present in {@code kings} -- a genuine, not-yet-retired King death. */
    public static boolean isGenuineKingDeath(List<KingRecord.Entry> kings, UUID id) {
        return kings.stream().anyMatch(entry -> entry.id().equals(id));
    }

    /**
     * [P1 review fix]: whether an {@code net.minecraft.world.entity.Entity.RemovalReason} name represents
     * a CONFIRMED, IRREVERSIBLE death removal -- {@code "KILLED"}, and nothing else. Takes the enum's
     * {@code name()} as a plain String (same convention {@code FillagerPopulationSimulation} already uses
     * for a damage-type registry key) so this stays Minecraft-class-free and directly unit-testable.
     *
     * <p>{@code KILLED} is only ever reached via vanilla's own {@code LivingEntity#tickDeath}, ~1 second
     * after an UNCANCELLED {@code LivingDeathEvent} -- a cancelled death never reaches it at all, so
     * {@code KingDeathHandling} never sees a false "died" for a death that was actually prevented.
     * {@code DISCARDED} (administrative {@code KingManager#removeNearest}), {@code UNLOADED_TO_CHUNK}/
     * {@code UNLOADED_WITH_PLAYER} (an ordinary chunk unload -- must NEVER retire a King merely out of
     * range), and {@code CHANGED_DIMENSION} are all deliberately excluded.
     */
    public static boolean isConfirmedDeathRemoval(String removalReasonName) {
        return "KILLED".equals(removalReasonName);
    }
}
