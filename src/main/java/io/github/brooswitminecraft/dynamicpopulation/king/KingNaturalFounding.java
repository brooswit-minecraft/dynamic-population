package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.entity.SkeletonKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.VillagerKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;
import io.github.brooswitminecraft.dynamicpopulation.propagation.LevelField;
import io.github.brooswitminecraft.dynamicpopulation.propagation.PopulationSimulation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * MINECRAFT-414: rare natural King founding, independent of the command-driven {@link KingManager#settle}
 * path -- deliberately does NOT call {@link KingManager#suitableTerritory}/{@link KingManager#wouldBeUnique}
 * (those carry the MINECRAFT-390 biome/light/depth/registry-distance gates that MINECRAFT-413 is separately
 * revising for the command path; natural founding never inherits them, by this ticket's own requirement).
 * It DOES reuse {@link KingManager#spawnKingEntity}, so a naturally founded King is constructed exactly the
 * same way a command-settled one is.
 *
 * <p>Cadence is deliberately its OWN tick listener, not folded into {@link KingSimulation}'s: seeding/sync
 * run at {@code king_seed_interval_ticks} (default 20) for responsive density growth, while natural founding
 * is a much rarer, independent check at {@code king_natural_founding_interval_ticks} (default 200, per the
 * ticket) -- the two cadences are unrelated numbers and mixing them into one listener would make changing
 * either accidentally change both.
 *
 * <p>Implementation assumption (ticket requires this be stated, not silent): "a currently loaded chunk" is
 * tracked by this class itself from {@link ChunkEvent.Load}/{@link ChunkEvent.Unload} (the same public
 * NeoForge event pair {@code PopulationSimulation} already uses for its own active-cell bookkeeping), NOT
 * read off {@code ChunkMap} directly -- {@code ChunkMap#getChunks()} is {@code protected} in this NeoForge
 * version (verified against the actual mapped jar; no access-widening transformer makes it public), so this
 * mod's own load/unload-tracked set is the correct public-API substitute, not a workaround of convenience.
 * "A random column within it" is then a uniform random block X/Z within that chunk's 16x16 footprint. The
 * per-ServerLevel/per-faction split matches every other King mechanism in this codebase (seeding,
 * uniqueness, density) being faction-scoped.
 *
 * <p>No interaction with {@link KingEntitySync}: {@code sync} only ever recreates the entity for an EXISTING
 * registry record (by the record's own stored position), it never creates a new record -- so it cannot race
 * with, double-create against, or bypass the probability rule of natural founding, which only ever inserts a
 * brand-new record with a freshly generated id. Verified by reading both call paths; no reconciliation
 * change was needed here.
 */
public final class KingNaturalFounding {
    private static final Random RANDOM = new Random();

    private static final Map<ServerLevel, Set<ChunkPos>> LOADED_CHUNKS = new HashMap<>();

    private KingNaturalFounding() { }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && PopulationSimulation.simulated(level)) {
            LOADED_CHUNKS.computeIfAbsent(level, l -> new HashSet<>()).add(event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var chunks = LOADED_CHUNKS.get(level);
            if (chunks != null) {
                chunks.remove(event.getChunk().getPos());
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LOADED_CHUNKS.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var config = DynamicPopulationConfig.get();
        if (!NaturalFounding.eligibleTick(event.getServer().getTickCount(), config.kingNaturalFoundingIntervalTicks())) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (PopulationSimulation.simulated(level)) {
                attempt(level, config);
            }
        }
    }

    /**
     * Performance note (flagged in review): a naive per-faction scan would call {@code level.getAllEntities()}
     * three times on every eligible boundary. Scanning once and classifying every loaded entity's faction
     * in that single pass avoids tripling the cost of what is already the most expensive step here, with
     * no change in behavior -- {@link #loadedKingFactions} returns exactly the same information
     * {@code anyLoadedKingOfFaction} would have, for every faction, from one pass instead of three.
     */
    private static void attempt(ServerLevel level, DynamicPopulationConfig.Snapshot config) {
        Set<Faction> loadedKingFactions = loadedKingFactions(level);
        for (Faction faction : Faction.values()) {
            attemptFaction(level, faction, config, loadedKingFactions.contains(faction));
        }
    }

    private static void attemptFaction(ServerLevel level, Faction faction, DynamicPopulationConfig.Snapshot config,
            boolean anyLoadedKingOfFaction) {
        if (!NaturalFounding.eligibleFaction(anyLoadedKingOfFaction)) {
            return;
        }
        if (!NaturalFounding.trial(RANDOM, config.kingNaturalFoundingChanceDenominator())) {
            return;
        }
        Optional<NaturalFounding.Candidate> candidate = NaturalFounding.pickCandidate(loadedChunkCoords(level), RANDOM);
        if (candidate.isEmpty()) {
            return;
        }
        attemptSite(level, faction, candidate.get());
    }

    /**
     * Every faction with at least one LIVE King entity loaded in {@code level} -- one entity scan, not one
     * per faction. [P2 review fix]: requires {@code entity.isAlive()}, not just type match -- a King mid
     * death-animation (dead, but not yet removed: vanilla delays the actual {@code remove} call ~1 second
     * after death, see {@code LivingEntity#tickDeath}) is still returned by {@code getAllEntities()} but is
     * NOT a live King by the ticket's own rule ("no live King entity of that type"); counting it would
     * wrongly suppress the very next boundary's trial for a faction that, in truth, currently has none.
     */
    private static Set<Faction> loadedKingFactions(ServerLevel level) {
        Set<Faction> present = new HashSet<>();
        for (Entity entity : level.getAllEntities()) {
            if (present.size() == Faction.values().length) {
                break; // every faction already accounted for; nothing left to learn from the rest of the scan.
            }
            if (!entity.isAlive()) {
                continue;
            }
            for (Faction faction : Faction.values()) {
                if (!present.contains(faction) && isKingOfFaction(entity, faction)) {
                    present.add(faction);
                    break;
                }
            }
        }
        return present;
    }

    static boolean isKingOfFaction(Entity entity, Faction faction) {
        return switch (faction) {
            case VILLAGER -> entity instanceof VillagerKingEntity;
            case ZOMBIE -> entity instanceof ZombieKingEntity;
            case SKELETON -> entity instanceof SkeletonKingEntity;
        };
    }

    /** Every chunk this class has observed loaded (and not yet unloaded) in {@code level}; see class doc. */
    private static List<NaturalFounding.ChunkCoord> loadedChunkCoords(ServerLevel level) {
        Set<ChunkPos> chunks = LOADED_CHUNKS.get(level);
        if (chunks == null) {
            return List.of();
        }
        List<NaturalFounding.ChunkCoord> coords = new ArrayList<>(chunks.size());
        for (ChunkPos pos : chunks) {
            coords.add(new NaturalFounding.ChunkCoord(pos.x, pos.z));
        }
        return coords;
    }

    /**
     * [P1 review fix]: validates against the King's REAL physical footprint -- base dimensions scaled by
     * its {@code Attributes.SCALE} (about 1.35x4.35, not the base 0.9x2.9 the old hardcoded constants
     * used) -- read off a genuine, freshly-constructed probe instance of this exact faction's entity class
     * (never a guessed/hardcoded number), and checks EVERY block the real footprint would occupy, not just
     * the single center column. The probe is reused as the actual inserted entity on success (see below),
     * never a second, independently-constructed instance.
     */
    private static void attemptSite(ServerLevel level, Faction faction, NaturalFounding.Candidate candidate) {
        int x = candidate.blockX();
        int z = candidate.blockZ();
        // Re-verify loaded: picked from a snapshot taken a moment ago.
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return;
        }
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos feet = new BlockPos(x, surfaceY, z);

        Mob probe = KingManager.newKingEntity(level, faction);
        var dims = probe.getDimensions(probe.getPose());
        KingPlacement.Footprint footprint = KingPlacement.footprint(x, z, dims.width(), dims.height());

        // Never require loading a neighbor chunk to validate a footprint straddling a chunk edge: bail
        // (skip this boundary's trial, no retry) the instant any touched chunk is not already loaded.
        if (!KingPlacement.everyChunk(footprint, (cx, cz) -> level.hasChunk(cx, cz))) {
            return;
        }

        boolean withinBuildHeight = KingPlacement.withinBuildHeight(feet.getY(), footprint.heightBlocks(),
            level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (!withinBuildHeight) {
            return;
        }

        boolean supportSturdyAndDry = KingPlacement.everyColumn(footprint,
            (bx, bz) -> supportOk(level, bx, feet.getY() - 1, bz));
        boolean bodyDry = bodyDry(level, footprint, feet.getY());
        AABB bodyAabb = bodyAabb(x, z, feet.getY(), dims.width(), dims.height());
        boolean bodyCollisionFree = level.noCollision(bodyAabb);

        if (!NaturalFounding.siteValid(withinBuildHeight, supportSturdyAndDry, bodyDry, bodyCollisionFree)) {
            return;
        }

        LevelField field = PopulationSimulation.field(level, faction);
        CellKey cell = field.cellAt(feet);
        if (!NaturalFounding.densityPermits(field.readable(cell), field.get(cell))) {
            return;
        }

        // Recheck still loaded immediately before insertion -- loaded state could have changed since the
        // candidate was picked and validated above. Checks the SAME full footprint, not just the center.
        if (!KingPlacement.everyChunk(footprint, (cx, cz) -> level.hasChunk(cx, cz))) {
            return;
        }

        Mob king = KingManager.finalizeKingEntity(level, probe, feet, UUID.randomUUID());
        if (level.getEntity(king.getUUID()) == null) {
            return; // insertion did not actually succeed; never commit a registry entry without a real entity.
        }
        level.getData(KingStorage.KING_REGISTRY.get())
            .add(new KingRecord.Entry(king.getUUID(), feet.getX(), feet.getY(), feet.getZ(), faction, true));
        announceSpawn(level, faction);
    }

    private static boolean supportOk(ServerLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        var state = level.getBlockState(pos);
        return state.isFaceSturdy(level, pos, Direction.UP) && state.getFluidState().isEmpty();
    }

    /** Every block the real (scaled) footprint occupies, feet layer through its full real height -- not just the center column. */
    private static boolean bodyDry(ServerLevel level, KingPlacement.Footprint footprint, int feetY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < footprint.heightBlocks(); dy++) {
            int y = feetY + dy;
            boolean layerDry = KingPlacement.everyColumn(footprint, (bx, bz) ->
                level.getBlockState(cursor.set(bx, y, bz)).getFluidState().isEmpty());
            if (!layerDry) {
                return false;
            }
        }
        return true;
    }

    private static AABB bodyAabb(int centerX, int centerZ, int feetY, double width, double height) {
        double halfWidth = width / 2.0;
        double x = centerX + 0.5;
        double z = centerZ + 0.5;
        return new AABB(x - halfWidth, feetY, z - halfWidth, x + halfWidth, feetY + height, z + halfWidth);
    }

    /**
     * Exactly-once, location-free success announcement: called from exactly one call site, immediately
     * after the entity+registry commit above and nowhere else -- never on a losing trial, a rejected site,
     * an insertion failure, ordinary reload, {@link KingEntitySync} migration, or any reconciliation path.
     */
    private static void announceSpawn(ServerLevel level, Faction faction) {
        broadcast(level, NaturalFounding.spawnAnnouncementText(faction));
    }

    static void broadcast(ServerLevel level, String text) {
        level.getServer().getPlayerList().broadcastSystemMessage(Component.literal(text), false);
    }
}
