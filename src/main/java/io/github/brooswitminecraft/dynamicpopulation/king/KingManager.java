package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.VillagerKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.DynamicPopulationEntities;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * NeoForge-facing glue for the King mechanism (AC4, generalized to all three factions by MINECRAFT-390):
 * bridges {@link KingTerritory} and {@link KingUniqueness} (pure logic) to the real level (sky-light/
 * heightmap reads, live config) and to {@link KingStorage}'s LEVEL attachment registry, and (new in
 * MINECRAFT-390) spawns the real, visible King entity a settle actually creates.
 *
 * <p>Territory eligibility has no biome restriction (MINECRAFT-413): biome identity alone never rejects
 * settlement, for any faction, in any vanilla or modded biome. Only sky light and surface depth gate
 * territory -- see the config comments on {@code king_min_sky_light}/{@code king_max_depth_below_surface_blocks}.
 *
 * <p><b>Territory/uniqueness are reused unchanged across all three factions (MINECRAFT-390 scope item 2):</b>
 * no documented reason exists to prefer different terrain for a Zombie/Skeleton King over a Villager King, so
 * {@link #suitableTerritory} applies the exact same rule to every faction. This is a deliberate choice, not
 * an oversight: it also means a Zombie/Skeleton King settling on the Villager King's own territory
 * is expected, not a bug -- see the next paragraph for why that is fine.
 *
 * <p><b>Cross-faction uniqueness policy (MINECRAFT-390 scope item 2, decided and documented here):</b>
 * {@link #wouldBeUnique} only compares a candidate against EXISTING KINGS OF THE SAME FACTION --
 * {@link #kings(ServerLevel)} is filtered by faction before the pure {@link KingUniqueness#allowed} check
 * ever runs. A Zombie King and a Villager King (or any other cross-faction pair) CAN coexist at or near the
 * same location. Rationale: {@code king_uniqueness_radius_cells} represents "don't oversaturate one
 * faction's own seed network in one area" -- a resource competition that is meaningless across factions,
 * since each faction's density lives in its own independent cell-field attachment (see
 * {@code CellFieldStorage}) and seeding one faction's field never touches another's. Allowing cross-faction
 * coexistence is also exactly what makes "battle lines" (ticket scope item 4) possible in the first place:
 * two different factions' Kings seeding overlapping territory is the intended way a contested front forms.
 */
public final class KingManager {
    private KingManager() { }

    public static List<KingRecord.Entry> kings(ServerLevel level) {
        return level.getData(KingStorage.KING_REGISTRY.get()).kings();
    }

    public static List<KingRecord.Entry> kings(ServerLevel level, Faction faction) {
        return kings(level).stream().filter(entry -> entry.faction() == faction).toList();
    }

    /** Whether {@code pos} is suitable surface territory, per {@link KingTerritory} and live config. */
    public static boolean suitableTerritory(ServerLevel level, BlockPos pos) {
        var config = DynamicPopulationConfig.get();
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        int depthBelowSurface = Math.max(0, surfaceY - pos.getY());
        return KingTerritory.suitable(skyLight, depthBelowSurface, config.kingMinSkyLight(),
            config.kingMaxDepthBelowSurfaceBlocks());
    }

    /** Horizontal cell coordinate of a block position, at the live {@code cell_size}. */
    public static KingUniqueness.CellCoord cellCoord(BlockPos pos, int cellSize) {
        return new KingUniqueness.CellCoord(Math.floorDiv(pos.getX(), cellSize), Math.floorDiv(pos.getZ(), cellSize));
    }

    /** Whether a King of {@code faction} could be unique at {@code pos}, against every EXISTING King of the SAME faction. */
    public static boolean wouldBeUnique(ServerLevel level, BlockPos pos, Faction faction) {
        var config = DynamicPopulationConfig.get();
        KingUniqueness.CellCoord candidate = cellCoord(pos, config.cellSize());
        List<KingUniqueness.CellCoord> existing = new ArrayList<>();
        for (KingRecord.Entry entry : kings(level, faction)) {
            existing.add(cellCoord(new BlockPos(entry.x(), entry.y(), entry.z()), config.cellSize()));
        }
        return KingUniqueness.allowed(candidate, existing, config.kingUniquenessRadiusCells());
    }

    /** Outcome of a settle attempt, in the order they're checked. */
    public enum SettleResult {
        SETTLED, UNSUITABLE_TERRITORY, NOT_UNIQUE
    }

    /**
     * Attempt to found a new King of {@code faction} at {@code pos}: checks territory suitability, then
     * (same-faction) uniqueness, in that order; on success, also spawns the real, visible, persistent King
     * entity -- the record's id IS the spawned entity's UUID (see {@link KingRecord.Entry}'s own doc), so
     * there is exactly one source of truth correlating the two, never two independently-created ids.
     */
    public static SettleResult settle(ServerLevel level, BlockPos pos, Faction faction) {
        if (!suitableTerritory(level, pos)) {
            return SettleResult.UNSUITABLE_TERRITORY;
        }
        if (!wouldBeUnique(level, pos, faction)) {
            return SettleResult.NOT_UNIQUE;
        }
        Mob king = spawnKingEntity(level, pos, faction, UUID.randomUUID());
        level.getData(KingStorage.KING_REGISTRY.get())
            .add(new KingRecord.Entry(king.getUUID(), pos.getX(), pos.getY(), pos.getZ(), faction));
        return SettleResult.SETTLED;
    }

    /**
     * Spawns the real King entity for a record, with the GIVEN uuid (never a freshly-generated one) so the
     * entity and its {@link KingRecord.Entry} always share one identity. Used both by a fresh
     * {@link #settle} and by {@code KingEntitySync}'s migration path for a pre-390 record that has no
     * entity yet.
     */
    static Mob spawnKingEntity(ServerLevel level, BlockPos pos, Faction faction, UUID uuid) {
        Mob king = switch (faction) {
            case VILLAGER -> new VillagerKingEntity(DynamicPopulationEntities.VILLAGER_KING.get(), level);
            case ZOMBIE -> new ZombieKingEntity(DynamicPopulationEntities.ZOMBIE_KING.get(), level);
            case SKELETON -> new SkeletonKingEntity(DynamicPopulationEntities.SKELETON_KING.get(), level);
        };
        king.setUUID(uuid);
        king.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        king.setPersistenceRequired();
        king.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.COMMAND, null);
        level.addFreshEntity(king);
        return king;
    }

    /** Remove the King nearest to {@code pos} in {@code level}, if one is within {@code withinBlocks}; also removes its entity. */
    public static boolean removeNearest(ServerLevel level, BlockPos pos, double withinBlocks) {
        KingRecord record = level.getData(KingStorage.KING_REGISTRY.get());
        KingRecord.Entry nearest = null;
        double nearestDistSq = withinBlocks * withinBlocks;
        for (KingRecord.Entry entry : record.kings()) {
            double distSq = pos.distSqr(new BlockPos(entry.x(), entry.y(), entry.z()));
            if (distSq <= nearestDistSq) {
                nearestDistSq = distSq;
                nearest = entry;
            }
        }
        if (nearest == null) {
            return false;
        }
        record.remove(nearest.id());
        var entity = level.getEntity(nearest.id());
        if (entity != null) {
            entity.discard();
        }
        return true;
    }
}
