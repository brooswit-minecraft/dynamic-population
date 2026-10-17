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
import io.github.brooswitminecraft.dynamicpopulation.reconciliation.FillagerSpawnSearch;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.Direction;
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
        // Iterate loaded entities directly, never query an AABB spanning the whole world border.
        for (var entity : level.getAllEntities()) {
            if (entity instanceof Fillager fillager) {
                cells.add(field.cellAt(fillager.blockPosition()));
            }
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
        return level.getEntitiesOfClass(Fillager.class, bounds,
            fillager -> field.cellAt(fillager.blockPosition()).equals(cell));
    }

    private static void spawn(ServerLevel level, LevelField field, CellKey cell) {
        int size = field.cellSize();
        int x = cell.x() * size + level.random.nextInt(size);
        int z = cell.z() * size + level.random.nextInt(size);
        // Never load a chunk merely to populate it.
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return;
        }
        var y = FillagerSpawnSearch.findY(cell.layer(), size, level.getMinBuildHeight(), level.getMaxBuildHeight(),
            candidate -> safeSpawn(level, new BlockPos(x, candidate, z)));
        if (y.isEmpty()) {
            return;
        }
        Fillager fillager = new Fillager(DynamicPopulationEntities.FILLAGER.get(), level);
        fillager.moveTo(x + 0.5, y.getAsInt(), z + 0.5, level.random.nextFloat() * 360F, 0);
        if (level.getWorldBorder().isWithinBounds(fillager.getBoundingBox()) && level.noCollision(fillager)) {
            level.addFreshEntity(fillager);
        }
    }

    private static boolean safeSpawn(ServerLevel level, BlockPos pos) {
        var feet = level.getBlockState(pos);
        var head = level.getBlockState(pos.above());
        var floor = level.getBlockState(pos.below());
        return floor.isFaceSturdy(level, pos.below(), Direction.UP)
            && floor.getFluidState().isEmpty() && feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()
            && !hazard(floor) && !hazard(feet) && !hazard(head)
            && level.noCollision(new AABB(pos.getX() + 0.2, pos.getY(), pos.getZ() + 0.2,
                pos.getX() + 0.8, pos.getY() + 1.95, pos.getZ() + 0.8));
    }

    private static boolean hazard(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
            || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE);
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
