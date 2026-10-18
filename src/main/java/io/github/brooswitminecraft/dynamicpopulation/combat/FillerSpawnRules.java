package io.github.brooswitminecraft.dynamicpopulation.combat;

/**
 * Pure logic for the Zombie/Skeleton filler spawn-light-level restriction and its &gt;250-density override
 * (see {@link EnvironmentalOverride}). This mod spawns filler Zombies/Skeletons itself (via its own
 * reconciliation loop, never vanilla's natural mob spawner), so "light level spawning restriction" here
 * means a restriction THIS mod's own spawn-safety check applies, mirroring vanilla's own overworld hostile
 * spawn rule (max local raw brightness &lt;= 7) rather than reusing vanilla's spawner at all.
 */
public final class FillerSpawnRules {
    /** Vanilla's own overworld hostile-mob spawn light cap (Monster.getMaxSpawnedInLevel/CommonMonsterCheck). */
    public static final int MAX_SPAWN_LIGHT_LEVEL = 7;

    private FillerSpawnRules() { }

    /**
     * Whether a candidate spawn location's light level is acceptable for a Zombie/Skeleton filler. Normally
     * (override inactive) this is the same &lt;= 7 cap vanilla hostile mobs use; when the faction's own local
     * density at the candidate location is &gt; 250, the light-level check is skipped entirely (spawn is
     * allowed regardless of light).
     */
    public static boolean lightLevelAllows(int lightLevel, boolean overrideActive) {
        return overrideActive || lightLevel <= MAX_SPAWN_LIGHT_LEVEL;
    }
}
