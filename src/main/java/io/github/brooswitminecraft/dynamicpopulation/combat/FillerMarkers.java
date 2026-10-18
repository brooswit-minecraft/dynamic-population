package io.github.brooswitminecraft.dynamicpopulation.combat;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;

/**
 * Scoreboard-tag markers (vanilla {@code Entity#addTag}/{@code getTags()}, persisted automatically in the
 * entity's own save NBT -- no custom NBT needed) identifying a vanilla {@code Zombie}/{@code Skeleton}
 * instance as ONE THIS MOD SPAWNED as a non-persistent filler, as opposed to any other Zombie/Skeleton in the
 * world (natural spawn, spawn egg, another mod, a named/leashed one, etc).
 *
 * <p>This tag is the entire exclusion mechanism for "do not reclassify unrelated persistent/vanilla entities
 * as fillers just because they share a class" (ticket scope item 3): only a tagged instance is ever counted
 * as a resident by reconciliation, ever despawned by reconciliation's own bounded excess-removal, or ever
 * eligible as a population-combat target/attacker. A {@code ZombieKingEntity}/{@code SkeletonKingEntity}
 * (which extend vanilla {@code Zombie}/{@code Skeleton} to inherit their combat AI) are never given this
 * tag, which is also what keeps Kings invisible to population-combat targeting without any separate
 * {@code instanceof} check at every call site -- though {@code PopulationCombatTargeting} still carries an
 * explicit {@code instanceof} guard too, for defense-in-depth and because the ticket asks this exclusion to
 * be documented, not merely implied by an absent tag.
 */
public final class FillerMarkers {
    public static final String ZOMBIE_FILLER_TAG = "dpop_zombie_filler";
    public static final String SKELETON_FILLER_TAG = "dpop_skeleton_filler";

    private FillerMarkers() { }

    public static String tagFor(Faction faction) {
        return switch (faction) {
            case ZOMBIE -> ZOMBIE_FILLER_TAG;
            case SKELETON -> SKELETON_FILLER_TAG;
            case VILLAGER -> throw new IllegalArgumentException(
                "Villager fillers are identified by the Fillager class itself, not a tag");
        };
    }
}
