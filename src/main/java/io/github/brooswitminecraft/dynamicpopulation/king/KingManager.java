package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * NeoForge-facing glue for the Villager King mechanism (AC4): bridges {@link KingTerritory} and
 * {@link KingUniqueness} (pure logic) to the real level (biome/sky-light/heightmap reads, live config) and to
 * {@link KingStorage}'s LEVEL attachment registry.
 *
 * <p>"Plains territory" is {@code minecraft:plains} or {@code minecraft:sunflower_plains} specifically -- NOT
 * {@code minecraft:snowy_plains}, deliberately: vanilla ships no {@code BiomeTags.IS_PLAINS}-style tag to check
 * against (verified against the real {@code BiomeTags} class), and a snowy biome does not match this ticket's
 * "surface/plains-preferring" framing. Flagged as a concrete, overridable choice, not a numeric tunable --
 * see the config comment on {@code king_max_depth_below_surface_blocks}.
 */
public final class KingManager {
    private KingManager() { }

    public static List<KingRecord.Entry> kings(ServerLevel level) {
        return level.getData(KingStorage.KING_REGISTRY.get()).kings();
    }

    /** Whether {@code pos} is suitable surface/plains territory, per {@link KingTerritory} and live config. */
    public static boolean suitableTerritory(ServerLevel level, BlockPos pos) {
        var config = DynamicPopulationConfig.get();
        boolean plains = level.getBiome(pos).is(Biomes.PLAINS) || level.getBiome(pos).is(Biomes.SUNFLOWER_PLAINS);
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        int depthBelowSurface = Math.max(0, surfaceY - pos.getY());
        return KingTerritory.suitable(plains, skyLight, depthBelowSurface, config.kingMinSkyLight(),
            config.kingMaxDepthBelowSurfaceBlocks());
    }

    /** Horizontal cell coordinate of a block position, at the live {@code cell_size}. */
    public static KingUniqueness.CellCoord cellCoord(BlockPos pos, int cellSize) {
        return new KingUniqueness.CellCoord(Math.floorDiv(pos.getX(), cellSize), Math.floorDiv(pos.getZ(), cellSize));
    }

    /** Whether a King could be unique at {@code pos}, against every existing King already in {@code level}. */
    public static boolean wouldBeUnique(ServerLevel level, BlockPos pos) {
        var config = DynamicPopulationConfig.get();
        KingUniqueness.CellCoord candidate = cellCoord(pos, config.cellSize());
        List<KingUniqueness.CellCoord> existing = new ArrayList<>();
        for (KingRecord.Entry entry : kings(level)) {
            existing.add(cellCoord(new BlockPos(entry.x(), entry.y(), entry.z()), config.cellSize()));
        }
        return KingUniqueness.allowed(candidate, existing, config.kingUniquenessRadiusCells());
    }

    /** Outcome of a settle attempt, in the order they're checked. */
    public enum SettleResult {
        SETTLED, UNSUITABLE_TERRITORY, NOT_UNIQUE
    }

    /** Attempt to found a new King at {@code pos}: checks territory suitability, then uniqueness, in that order. */
    public static SettleResult settle(ServerLevel level, BlockPos pos) {
        if (!suitableTerritory(level, pos)) {
            return SettleResult.UNSUITABLE_TERRITORY;
        }
        if (!wouldBeUnique(level, pos)) {
            return SettleResult.NOT_UNIQUE;
        }
        level.getData(KingStorage.KING_REGISTRY.get())
            .add(new KingRecord.Entry(UUID.randomUUID(), pos.getX(), pos.getY(), pos.getZ()));
        return SettleResult.SETTLED;
    }

    /** Remove the King nearest to {@code pos} in {@code level}, if one is within {@code withinBlocks}. */
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
        return true;
    }
}
