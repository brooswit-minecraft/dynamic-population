package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.github.brooswitminecraft.dynamicpopulation.cellfield.CellFieldRecord;
import io.github.brooswitminecraft.dynamicpopulation.cellfield.CellFieldStorage;
import io.github.brooswitminecraft.dynamicpopulation.cellfield.CellGrid;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The field of one level, backed by one faction's cell-field attachment on the loaded anchor chunks. A cell
 * whose anchor chunk is not loaded, or whose stored data is preserved-as-corrupt, does not take part (it is
 * not traversable).
 *
 * <p>Public (not package-private): the king package reuses this exact glue to read/raise the real cell field
 * near a King, rather than re-deriving a second NeoForge bridge to the same attachment.
 *
 * <p>MINECRAFT-390: parameterized over WHICH attachment to read/write ({@link #LevelField(ServerLevel)}
 * defaults to {@link CellFieldStorage#CELL_FIELD}, the original Villager/Fillager field, for every
 * pre-existing call site) so the exact same traversal/decode/encode glue serves all three factions' fields
 * without duplicating this class.
 */
public final class LevelField implements Propagation.Field {
    private final ServerLevel level;
    private final Supplier<AttachmentType<CellFieldRecord>> attachment;
    private final int cellSize;
    private final int footprint;
    private final int layers;
    private final int minY;
    private final int sampleSpacing;
    private final Map<CellKey, Boolean> traversableCache = new HashMap<>();

    public LevelField(ServerLevel level) {
        this(level, CellFieldStorage.CELL_FIELD);
    }

    public LevelField(ServerLevel level, Supplier<AttachmentType<CellFieldRecord>> attachment) {
        this.level = level;
        this.attachment = attachment;
        var config = DynamicPopulationConfig.get();
        this.cellSize = config.cellSize();
        this.footprint = CellGrid.footprintChunks(cellSize);
        this.minY = level.getMinBuildHeight();
        this.layers = CellGrid.layerCount(minY, level.getMaxBuildHeight(), cellSize);
        this.sampleSpacing = config.traversableSampleSpacing();
    }

    public CellKey cellAt(BlockPos pos) {
        return new CellKey(Math.floorDiv(pos.getX(), cellSize), CellGrid.layerIndex(pos.getY(), minY, cellSize), Math.floorDiv(pos.getZ(), cellSize));
    }

    public int layers() {
        return layers;
    }

    public int cellSize() {
        return cellSize;
    }

    private LevelChunk anchorChunk(CellKey cell) {
        if (cell.layer() < 0 || cell.layer() >= layers) {
            return null;
        }
        return level.getChunkSource().getChunkNow(cell.x() * footprint, cell.z() * footprint);
    }

    private CellFieldRecord.Loaded loaded(CellKey cell) {
        LevelChunk chunk = anchorChunk(cell);
        if (chunk == null) {
            return null;
        }
        return chunk.getData(attachment.get()) instanceof CellFieldRecord.Loaded l ? l : null;
    }

    @Override
    public int get(CellKey cell) {
        CellFieldRecord.Loaded record = loaded(cell);
        return record == null ? 0 : record.layerDensities().getOrDefault(cell.layer(), 0);
    }

    /**
     * Whether this cell's stored density can actually be read right now: its anchor chunk is loaded and its
     * stored data is not preserved-as-corrupt. {@code get} returns 0 for a not-readable cell too, but that 0
     * is not a real density -- it is "we don't know yet", distinct from a loaded cell with no entry for this
     * layer (a genuine zero). Distinct from {@link #traversable}, which also requires an open/liquid block;
     * a cell can be readable (density known) without being traversable, or vice versa is impossible since
     * traversable already implies loaded(cell) != null.
     */
    public boolean readable(CellKey cell) {
        return loaded(cell) != null;
    }

    @Override
    public void set(CellKey cell, int value) {
        CellFieldRecord.Loaded record = loaded(cell);
        if (record == null) {
            return;
        }
        if (value <= 0) {
            record.layerDensities().remove(cell.layer());
        } else {
            record.layerDensities().put(cell.layer(), Math.min(value, Propagation.CAPACITY));
        }
        anchorChunk(cell).setUnsaved(true);
    }

    @Override
    public boolean traversable(CellKey cell) {
        if (loaded(cell) == null) {
            return false;
        }
        return traversableCache.computeIfAbsent(cell, this::sampleTraversable);
    }

    /** Sample a lattice of blocks in the cell for an open or liquid block. Only blocks in loaded chunks count. */
    private boolean sampleTraversable(CellKey cell) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x0 = cell.x() * cellSize;
        int z0 = cell.z() * cellSize;
        int y0 = minY + cell.layer() * cellSize;
        boolean open = false;
        boolean liquid = false;
        for (int dx = sampleSpacing / 2; dx < cellSize && !(open || liquid); dx += sampleSpacing) {
            for (int dz = sampleSpacing / 2; dz < cellSize && !(open || liquid); dz += sampleSpacing) {
                if (!level.hasChunk((x0 + dx) >> 4, (z0 + dz) >> 4)) {
                    continue;
                }
                for (int dy = sampleSpacing / 2; dy < cellSize && !(open || liquid); dy += sampleSpacing) {
                    int y = y0 + dy;
                    if (y >= level.getMaxBuildHeight()) {
                        break;
                    }
                    var state = level.getBlockState(pos.set(x0 + dx, y, z0 + dz));
                    liquid = !state.getFluidState().isEmpty();
                    open = !state.blocksMotion();
                }
            }
        }
        return Propagation.traversable(open, liquid);
    }

    /** Why a cell does not take part, for the debug command; null if it does. */
    public String blockedReason(CellKey cell) {
        if (cell.layer() < 0 || cell.layer() >= layers) {
            return "outside world height";
        }
        if (anchorChunk(cell) == null) {
            return "anchor chunk not loaded";
        }
        if (loaded(cell) == null) {
            return "stored data preserved as corrupt";
        }
        return sampleTraversable(cell) ? null : "no open or liquid block sampled";
    }

    /** Traversability is cached per step; the world changes slowly relative to the step interval. */
    void clearCache() {
        traversableCache.clear();
    }
}
