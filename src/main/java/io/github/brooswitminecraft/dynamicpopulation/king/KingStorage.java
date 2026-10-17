package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.UUID;
import java.util.function.Supplier;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * NeoForge-facing storage: registers the {@code king_registry} LEVEL data attachment holding every Villager
 * King in that level. A LEVEL attachment (unlike the {@code cell_field} CHUNK attachment) because a King's
 * registry is level-wide, not tied to any one chunk's footprint; NeoForge persists level attachments itself
 * (via its own {@code LevelAttachmentsSavedData}), and -- unlike a chunk attachment -- a level attachment needs
 * no {@code setUnsaved}-equivalent call after mutating it in place: NeoForge's javadoc on
 * {@link AttachmentType} lists no such requirement for {@code Level}-held attachments, only for
 * {@code ChunkAccess} ones.
 */
public final class KingStorage {
    private static final DeferredRegister<AttachmentType<?>> TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, DynamicPopulationMod.MODID);

    private static final String KEY_KINGS = "kings";
    private static final String KEY_ID = "id";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";

    public static final Supplier<AttachmentType<KingRecord>> KING_REGISTRY = TYPES.register("king_registry", () ->
        AttachmentType.builder((IAttachmentHolder holder) -> new KingRecord())
            .serialize(new IAttachmentSerializer<CompoundTag, KingRecord>() {
                @Override
                public KingRecord read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
                    KingRecord record = new KingRecord();
                    ListTag list = tag.getList(KEY_KINGS, Tag.TAG_COMPOUND);
                    for (int i = 0; i < list.size(); i++) {
                        CompoundTag entry = list.getCompound(i);
                        record.add(new KingRecord.Entry(entry.getUUID(KEY_ID), entry.getInt(KEY_X), entry.getInt(KEY_Y),
                            entry.getInt(KEY_Z)));
                    }
                    return record;
                }

                @Override
                public CompoundTag write(KingRecord record, HolderLookup.Provider provider) {
                    CompoundTag tag = new CompoundTag();
                    ListTag list = new ListTag();
                    for (KingRecord.Entry entry : record.kings()) {
                        CompoundTag entryTag = new CompoundTag();
                        entryTag.putUUID(KEY_ID, entry.id());
                        entryTag.putInt(KEY_X, entry.x());
                        entryTag.putInt(KEY_Y, entry.y());
                        entryTag.putInt(KEY_Z, entry.z());
                        list.add(entryTag);
                    }
                    tag.put(KEY_KINGS, list);
                    return tag;
                }
            }).build());

    private KingStorage() { }

    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }
}
