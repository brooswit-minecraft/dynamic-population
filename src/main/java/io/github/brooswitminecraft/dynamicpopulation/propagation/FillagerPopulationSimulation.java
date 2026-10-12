package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.entity.DynamicPopulationEntities;
import io.github.brooswitminecraft.dynamicpopulation.entity.Fillager;
import io.github.brooswitminecraft.dynamicpopulation.reconciliation.FillagerReconciliation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-only reconciliation of temporary Fillagers toward a read-only cell-window sample. */
public final class FillagerPopulationSimulation {
    private FillagerPopulationSimulation() { }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var config = DynamicPopulationConfig.get(); // live volatile snapshot: reloads affect the next decision.
        if (event.getServer().getTickCount() % config.reconciliationIntervalTicks() != 0) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level.dimension() == Level.OVERWORLD) {
                reconcile(level, config);
            }
        }
    }

    static void reconcile(ServerLevel level, DynamicPopulationConfig.Snapshot config) {
        LevelField field = PopulationSimulation.field(level);
        Set<CellKey> cells = new HashSet<>(PopulationSimulation.active(level));
        for (Fillager fillager : level.getEntitiesOfClass(Fillager.class, new AABB(level.getWorldBorder().getMinX(),
                level.getMinBuildHeight(), level.getWorldBorder().getMinZ(), level.getWorldBorder().getMaxX(),
                level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ()))) {
            cells.add(field.cellAt(fillager.blockPosition()));
        }
        var params = new FillagerReconciliation.Params(config.samplingHorizontalRadius(), config.samplingVerticalRadius(),
            config.fillagersPerFullCell(), config.reconciliationRefillPerInterval(), config.spawnCapPerInterval(),
            config.despawnCapPerInterval());
        for (CellKey cell : cells) {
            if (field.blockedReason(cell) != null) {
                continue;
            }
            List<Fillager> residents = residents(level, field, cell);
            var decision = FillagerReconciliation.decide(cell, residents.size(), params, field::get);
            for (int i = 0; i < decision.despawn(); i++) {
                residents.get(i).discard(); // ordinary despawn never changes the target.
            }
            for (int i = 0; i < decision.spawn(); i++) {
                spawn(level, field, cell);
            }
        }
    }

    private static List<Fillager> residents(ServerLevel level, LevelField field, CellKey cell) {
        int size = field.cellSize();
        int minY = level.getMinBuildHeight() + cell.layer() * size;
        AABB bounds = new AABB(cell.x() * size, minY, cell.z() * size, (cell.x() + 1) * size, minY + size, (cell.z() + 1) * size);
        return level.getEntitiesOfClass(Fillager.class, bounds);
    }

    private static void spawn(ServerLevel level, LevelField field, CellKey cell) {
        int size = field.cellSize();
        int x = cell.x() * size + size / 2;
        int z = cell.z() * size + size / 2;
        int y = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1,
            level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)));
        Fillager fillager = new Fillager(DynamicPopulationEntities.FILLAGER.get(), level);
        fillager.moveTo(x + 0.5, y, z + 0.5, level.random.nextFloat() * 360F, 0);
        level.addFreshEntity(fillager);
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Fillager fillager) || !fillager.wasUnsafeLocationDeath()
                || !(fillager.level() instanceof ServerLevel level)) {
            return;
        }
        LevelField field = PopulationSimulation.field(level);
        CellKey localCell = field.cellAt(fillager.blockPosition());
        int decrement = DynamicPopulationConfig.get().unsafeDeathTargetDecrement();
        field.set(localCell, FillagerReconciliation.unsafeDeathTargetAfter(field.get(localCell), decrement));
    }
}
