package io.github.brooswitminecraft.dynamicpopulation.cellfield;

import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * NeoForge-facing storage: registers the {@code cell_field} chunk data
 * attachment (NeoForge's chunk-owned attachment mechanism — the attachment's
 * NBT round-trips through the per-chunk save data written by
 * ChunkSerializer, so it dies with a deleted region, unlike a level-wide
 * SavedData) and bridges CompoundTag &lt;-&gt; the plain values
 * {@link CellFieldCodec} classifies/decodes/encodes. Attached only to a
 * cell's anchor chunk — every other chunk in the footprint never gets this
 * attachment at all.
 *
 * <p>This story only stores and loads a per-layer target density; nothing
 * here ticks, propagates, or spawns anything.
 */
public final class CellFieldStorage {
    private static final DeferredRegister<AttachmentType<?>> TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, DynamicPopulationMod.MODID);

    private static final String KEY_VERSION = "version";
    private static final String KEY_CELL_SIZE = "cellSize";
    private static final String KEY_ANCHOR_X = "anchorX";
    private static final String KEY_ANCHOR_Z = "anchorZ";
    private static final String KEY_ENTRIES = "entries";

    public static final Supplier<AttachmentType<CellFieldRecord>> CELL_FIELD = TYPES.register("cell_field", () ->
        AttachmentType.builder(CellFieldStorage::defaultValue)
            .serialize(new IAttachmentSerializer<CompoundTag, CellFieldRecord>() {
                @Override
                public CellFieldRecord read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
                    return CellFieldStorage.read(holder, tag);
                }

                @Override
                public CompoundTag write(CellFieldRecord record, HolderLookup.Provider provider) {
                    return CellFieldStorage.write(record);
                }
            }).build());

    private CellFieldStorage() { }

    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }

    private static CellFieldRecord defaultValue(IAttachmentHolder holder) {
        ChunkAccess chunk = requireChunk(holder);
        int cellSize = DynamicPopulationConfig.get().cellSize();
        int anchorX = CellGrid.anchorChunkCoordinate(chunk.getPos().x, cellSize);
        int anchorZ = CellGrid.anchorChunkCoordinate(chunk.getPos().z, cellSize);
        return new CellFieldRecord.Loaded(cellSize, anchorX, anchorZ, new TreeMap<>());
    }

    private static CellFieldRecord read(IAttachmentHolder holder, CompoundTag tag) {
        ChunkAccess chunk = requireChunk(holder);
        int cellSize = DynamicPopulationConfig.get().cellSize();
        int anchorX = CellGrid.anchorChunkCoordinate(chunk.getPos().x, cellSize);
        int anchorZ = CellGrid.anchorChunkCoordinate(chunk.getPos().z, cellSize);
        int layerCount = CellGrid.layerCount(chunk.getMinBuildHeight(), chunk.getMaxBuildHeight(), cellSize);

        CellFieldCodec.DecodeResult result = CellFieldCodec.decode(
            getInt(tag, KEY_VERSION),
            getInt(tag, KEY_CELL_SIZE),
            getInt(tag, KEY_ANCHOR_X),
            getInt(tag, KEY_ANCHOR_Z),
            getIntArray(tag, KEY_ENTRIES),
            cellSize, anchorX, anchorZ, layerCount);

        if (result instanceof CellFieldCodec.DecodeResult.Loaded loaded) {
            return new CellFieldRecord.Loaded(cellSize, anchorX, anchorZ, loaded.layerDensities());
        }
        if (result instanceof CellFieldCodec.DecodeResult.Stale stale) {
            DynamicPopulationMod.LOGGER.warn(
                "Discarding stale cell field data at anchor chunk ({}, {}): found version {}, expected {} "
                    + "-- chunk treated as empty and will regrow from the King",
                anchorX, anchorZ, stale.foundVersion(), stale.expectedVersion());
            return new CellFieldRecord.Loaded(cellSize, anchorX, anchorZ, new TreeMap<>());
        }
        if (result instanceof CellFieldCodec.DecodeResult.SizeMismatch mismatch) {
            DynamicPopulationMod.LOGGER.warn(
                "Discarding cell field data at anchor chunk ({}, {}): stored cell_size {} does not match "
                    + "configured {} -- chunk treated as empty and will regrow from the King",
                anchorX, anchorZ, mismatch.foundCellSize(), mismatch.expectedCellSize());
            return new CellFieldRecord.Loaded(cellSize, anchorX, anchorZ, new TreeMap<>());
        }
        CellFieldCodec.DecodeResult.Corrupt corrupt = (CellFieldCodec.DecodeResult.Corrupt) result;
        DynamicPopulationMod.LOGGER.warn(
            "Preserving corrupt cell field data at anchor chunk ({}, {}), skipping it for ticking: {}",
            anchorX, anchorZ, corrupt.reason());
        return new CellFieldRecord.Preserved(tag.copy());
    }

    private static CompoundTag write(CellFieldRecord record) {
        return switch (record) {
            case CellFieldRecord.Preserved preserved -> preserved.rawTag().copy();
            case CellFieldRecord.Loaded loaded -> {
                CompoundTag tag = new CompoundTag();
                tag.putInt(KEY_VERSION, CellFieldCodec.CURRENT_VERSION);
                tag.putInt(KEY_CELL_SIZE, loaded.cellSize());
                tag.putInt(KEY_ANCHOR_X, loaded.anchorChunkX());
                tag.putInt(KEY_ANCHOR_Z, loaded.anchorChunkZ());
                tag.putIntArray(KEY_ENTRIES, CellFieldCodec.encodeEntries(loaded.layerDensities()));
                yield tag;
            }
        };
    }

    private static ChunkAccess requireChunk(IAttachmentHolder holder) {
        if (holder instanceof ChunkAccess chunk) {
            return chunk;
        }
        throw new IllegalArgumentException("cell_field storage requires a chunk holder, got " + holder);
    }

    private static Optional<Integer> getInt(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_INT) ? Optional.of(tag.getInt(key)) : Optional.empty();
    }

    private static Optional<int[]> getIntArray(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_INT_ARRAY) ? Optional.of(tag.getIntArray(key)) : Optional.empty();
    }
}
