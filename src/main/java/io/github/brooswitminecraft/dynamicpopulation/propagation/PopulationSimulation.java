package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.brooswitminecraft.dynamicpopulation.cellfield.CellFieldRecord;
import io.github.brooswitminecraft.dynamicpopulation.cellfield.CellFieldStorage;
import io.github.brooswitminecraft.dynamicpopulation.cellfield.CellGrid;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives propagation on the server: tracks which cells hold population (the active set, per level) and steps
 * them every {@code propagation_step_interval_ticks}. Overworld only for V1 (assumption A1).
 */
public final class PopulationSimulation {
    private static final Map<ServerLevel, Set<CellKey>> ACTIVE = new HashMap<>();
    private static final java.util.Random RANDOM = new java.util.Random();

    private PopulationSimulation() { }

    static Set<CellKey> active(ServerLevel level) {
        return ACTIVE.computeIfAbsent(level, l -> new HashSet<>());
    }

    static boolean simulated(ServerLevel level) {
        return level.dimension() == Level.OVERWORLD;
    }

    /** Run {@code steps} propagation steps now, whatever the interval. Returns the cells holding population. */
    static int stepNow(ServerLevel level, int steps) {
        LevelField field = new LevelField(level);
        var config = DynamicPopulationConfig.get();
        var params = new Propagation.Params(config.weightDown(), config.weightSides(), config.weightUp(), config.spreadFraction());
        Set<CellKey> active = active(level);
        for (int i = 0; i < steps; i++) {
            field.clearCache();
            Set<CellKey> next = Propagation.step(field, active, params, RANDOM);
            active.clear();
            active.addAll(next);
        }
        return active.size();
    }

    static LevelField field(ServerLevel level) {
        return new LevelField(level);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int interval = DynamicPopulationConfig.get().stepIntervalTicks();
        if (event.getServer().getTickCount() % interval != 0) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (simulated(level) && !active(level).isEmpty()) {
                stepNow(level, 1);
            }
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !simulated(level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        int cellSize = DynamicPopulationConfig.get().cellSize();
        if (!CellGrid.isAnchorChunk(chunk.getPos().x, chunk.getPos().z, cellSize)) {
            return;
        }
        if (chunk.getData(CellFieldStorage.CELL_FIELD.get()) instanceof CellFieldRecord.Loaded loaded) {
            int footprint = CellGrid.footprintChunks(cellSize);
            for (var entry : loaded.layerDensities().entrySet()) {
                if (entry.getValue() > 0) {
                    active(level).add(new CellKey(chunk.getPos().x / footprint, entry.getKey(), chunk.getPos().z / footprint));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !simulated(level)) {
            return;
        }
        int cellSize = DynamicPopulationConfig.get().cellSize();
        int footprint = CellGrid.footprintChunks(cellSize);
        var pos = event.getChunk().getPos();
        if (CellGrid.isAnchorChunk(pos.x, pos.z, cellSize)) {
            int cx = Math.floorDiv(pos.x, footprint);
            int cz = Math.floorDiv(pos.z, footprint);
            active(level).removeIf(cell -> cell.x() == cx && cell.z() == cz);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        ACTIVE.clear();
    }

}
