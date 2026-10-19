package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.entity.DynamicPopulationEntities;
import io.github.brooswitminecraft.dynamicpopulation.entity.Fillager;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.reconciliation.FillagerReconciliation;
import io.github.brooswitminecraft.dynamicpopulation.reconciliation.PopulationDeathDecrement;
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
        // MINECRAFT-390 (settled spec; review fix P1-1): day/night spawn-RATE multiplier -- scales ONLY
        // the refill limit (the config's own documented global per-interval rate), via SpawnRateBudget's
        // fractional-carry accumulator so the LONG-RUN rate is correct even when any single interval's
        // whole-number allowance floors to 0 (e.g. the default refill limit of 1 at a 0.25x/0.5x phase).
        // spawnCapPerInterval is deliberately left UNSCALED: it is a per-cell burst cap, not the rate
        // governor. Never touches despawnCapPerInterval or density/target accounting. Computed from game
        // ticks only (never wall-clock). See DayNightSpawnRate's class doc for the Fillager 12h/12h alignment.
        long dayTime = level.getDayTime();
        int scaledRefill = SpawnRateBudget.nextRefillAllowance(level, Faction.VILLAGER, config.reconciliationRefillPerInterval(), dayTime);
        var params = new FillagerReconciliation.Params(config.samplingHorizontalRadius(), config.samplingVerticalRadius(),
            config.fillagersPerFullCell(), scaledRefill, config.spawnCapPerInterval(),
            config.despawnCapPerInterval());
        for (CellKey cell : cells) {
            if (field.blockedReason(cell) != null) {
                continue;
            }
            List<Fillager> residents = residents(level, field, cell);
            var decision = FillagerReconciliation.decide(cell, residents.size(), params, 0, field.layers() - 1,
                field::readable, field::get);
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
        return FillagerReconciliation.residentsInCell(level.getEntitiesOfClass(Fillager.class, bounds), cell,
            fillager -> field.cellAt(fillager.blockPosition()));
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

    /**
     * MINECRAFT-425: capture the Fillager's actual death-position cell now -- before any death-animation
     * movement, and well before the entity's confirmed, irreversible removal -- for
     * {@code PopulationDeathDecrement} to apply (once, only if this death is never cancelled) via its own
     * {@code EntityLeaveLevelEvent} listener. This method itself never touches storage: see
     * {@code PopulationDeathDecrement}'s own class doc for why a cancellable {@code LivingDeathEvent} must
     * never write directly.
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Fillager fillager) || !(fillager.level() instanceof ServerLevel level)) {
            return;
        }
        // Review fix (round 1): discard any stale pending capture from an earlier, cancelled death attempt
        // first -- see PopulationDeathDecrement's own class doc. A Fillager's eligibility never itself
        // flips (unlike a tagged filler's), but every onLivingDeath listener does this uniformly so no
        // future change here can silently reopen that staleness window.
        PopulationDeathDecrement.take(fillager.getUUID());
        LevelField field = PopulationSimulation.field(level);
        CellKey localCell = field.cellAt(fillager.blockPosition());
        PopulationDeathDecrement.capture(fillager.getUUID(), Faction.VILLAGER, localCell,
            DynamicPopulationConfig.get().populationDeathTargetDecrement());
    }
}
