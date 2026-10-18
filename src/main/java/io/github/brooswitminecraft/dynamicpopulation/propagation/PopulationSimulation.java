package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import io.github.brooswitminecraft.dynamicpopulation.cellfield.CellFieldRecord;
import io.github.brooswitminecraft.dynamicpopulation.cellfield.CellFieldStorage;
import io.github.brooswitminecraft.dynamicpopulation.cellfield.CellGrid;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives propagation on the server: tracks which cells hold population (the active set, per level PER
 * FACTION as of MINECRAFT-390) and steps them every {@code propagation_step_interval_ticks}. Overworld only
 * for V1 (assumption A1).
 *
 * <p>MINECRAFT-390: generalized from a single implicit (Villager/Fillager) field to one independent active
 * set and {@link LevelField} per {@link Faction}, backed by that faction's own attachment (see
 * {@code CellFieldStorage}). Every pre-existing no-faction-argument method ({@link #active(ServerLevel)},
 * {@link #field(ServerLevel)}) is kept, unchanged in behavior, as a fixed alias for {@link Faction#VILLAGER}
 * -- every call site that existed before this story keeps working exactly as before, reading/writing only
 * the original {@code cell_field} attachment.
 */
public final class PopulationSimulation {
    private static final Map<ServerLevel, Map<Faction, Set<CellKey>>> ACTIVE = new HashMap<>();
    private static final java.util.Random RANDOM = new java.util.Random();

    private PopulationSimulation() { }

    /** Public: the king package marks cells it seeds as active so propagation picks them up from there. */
    public static Set<CellKey> active(ServerLevel level) {
        return active(level, Faction.VILLAGER);
    }

    public static Set<CellKey> active(ServerLevel level, Faction faction) {
        return ACTIVE.computeIfAbsent(level, l -> new EnumMap<>(Faction.class))
            .computeIfAbsent(faction, f -> new HashSet<>());
    }

    public static boolean simulated(ServerLevel level) {
        return level.dimension() == Level.OVERWORLD;
    }

    /** Which attachment backs a faction's field -- the one place this mapping is made. */
    private static Supplier<AttachmentType<CellFieldRecord>> attachmentFor(Faction faction) {
        return switch (faction) {
            case VILLAGER -> CellFieldStorage.CELL_FIELD;
            case ZOMBIE -> CellFieldStorage.ZOMBIE_FIELD;
            case SKELETON -> CellFieldStorage.SKELETON_FIELD;
        };
    }

    /** Run {@code steps} propagation steps now, whatever the interval. Returns the cells holding population. */
    static int stepNow(ServerLevel level, int steps) {
        return stepNow(level, Faction.VILLAGER, steps);
    }

    static int stepNow(ServerLevel level, Faction faction, int steps) {
        LevelField field = field(level, faction);
        var config = DynamicPopulationConfig.get();
        var params = new Propagation.Params(config.weightDown(), config.weightSides(), config.weightUp(), config.spreadFraction());
        Set<CellKey> active = active(level, faction);
        for (int i = 0; i < steps; i++) {
            field.clearCache();
            Set<CellKey> next = Propagation.step(field, active, params, RANDOM);
            active.clear();
            active.addAll(next);
        }
        return active.size();
    }

    /** Public: the king package builds the same real field glue to read/raise cell values near a King. */
    public static LevelField field(ServerLevel level) {
        return field(level, Faction.VILLAGER);
    }

    public static LevelField field(ServerLevel level, Faction faction) {
        return new LevelField(level, attachmentFor(faction));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int interval = DynamicPopulationConfig.get().stepIntervalTicks();
        if (event.getServer().getTickCount() % interval != 0) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (!simulated(level)) {
                continue;
            }
            for (Faction faction : Faction.values()) {
                if (!active(level, faction).isEmpty()) {
                    stepNow(level, faction, 1);
                }
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
        int footprint = CellGrid.footprintChunks(cellSize);
        for (Faction faction : Faction.values()) {
            if (chunk.getData(attachmentFor(faction).get()) instanceof CellFieldRecord.Loaded loaded) {
                for (var entry : loaded.layerDensities().entrySet()) {
                    if (entry.getValue() > 0) {
                        active(level, faction).add(new CellKey(chunk.getPos().x / footprint, entry.getKey(), chunk.getPos().z / footprint));
                    }
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
            for (Faction faction : Faction.values()) {
                active(level, faction).removeIf(cell -> cell.x() == cx && cell.z() == cz);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        ACTIVE.clear();
    }

}
