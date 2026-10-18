package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import io.github.brooswitminecraft.dynamicpopulation.combat.EnvironmentalOverride;
import io.github.brooswitminecraft.dynamicpopulation.combat.FillerMarkers;
import io.github.brooswitminecraft.dynamicpopulation.combat.FillerSpawnRules;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;
import io.github.brooswitminecraft.dynamicpopulation.propagation.DayNightSpawnRate;
import io.github.brooswitminecraft.dynamicpopulation.propagation.LevelField;
import io.github.brooswitminecraft.dynamicpopulation.propagation.PopulationSimulation;
import io.github.brooswitminecraft.dynamicpopulation.propagation.SpawnRateBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * MINECRAFT-390 scope item 3: ordinary, non-persistent Zombie/Skeleton filler reconciliation, generalized
 * over the two factions (unlike {@code FillagerPopulationSimulation}, which stays Fillager-specific since
 * Fillager also carries bespoke hazard-death/promotion logic these two do not need).
 *
 * <p>Reuses vanilla {@code EntityType.ZOMBIE}/{@code EntityType.SKELETON} directly, per the ticket's explicit
 * instruction -- never a new entity subclass for the ordinary filler case (only the Kings get one). A
 * filler's ONLY distinguishing mark is {@code FillerMarkers}' scoreboard tag, applied before the entity ever
 * joins the level: reconciliation counts, spawns, and despawns ONLY entities carrying that tag, so a
 * naturally-spawned/spawn-egged/named/leashed vanilla Zombie or Skeleton is never touched by this class, is
 * never counted toward the local density target, and is never a population-combat participant (see
 * {@code FillerMarkers}' own doc).
 *
 * <p>No custom hazard-death/lava-fire logic (unlike {@code Fillager}): vanilla {@code LivingEntity}/
 * {@code Mob} already applies ordinary fire/lava/drowning damage and suffocation to any entity, filler or
 * not, so there is nothing to reimplement here. No promotion economy either -- not requested for these two
 * factions ("we don't need persistent variants for the zombie or skeleton (not yet at least)").
 */
public final class FillerFactionSimulation {
    private FillerFactionSimulation() { }

    private record Spec(Faction faction, String tag, Function<Level, Mob> factory) { }

    private static final List<Spec> SPECS = List.of(
        new Spec(Faction.ZOMBIE, FillerMarkers.ZOMBIE_FILLER_TAG, level -> new Zombie(EntityType.ZOMBIE, level)),
        new Spec(Faction.SKELETON, FillerMarkers.SKELETON_FILLER_TAG, level -> new Skeleton(EntityType.SKELETON, level)));

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var config = DynamicPopulationConfig.get();
        if (event.getServer().getTickCount() % config.reconciliationIntervalTicks() != 0) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (level.dimension() == Level.OVERWORLD) {
                for (Spec spec : SPECS) {
                    reconcile(level, spec, config);
                }
            }
        }
    }

    private static void reconcile(ServerLevel level, Spec spec, DynamicPopulationConfig.Snapshot config) {
        LevelField field = PopulationSimulation.field(level, spec.faction());
        Set<CellKey> cells = new HashSet<>(PopulationSimulation.active(level, spec.faction()));
        // Iterate loaded entities directly, never query an AABB spanning the whole world border -- same
        // approach FillagerPopulationSimulation.reconcile uses.
        for (var entity : level.getAllEntities()) {
            if (isFiller(entity, spec)) {
                cells.add(field.cellAt(entity.blockPosition()));
            }
        }
        // MINECRAFT-390 (settled spec; review fix P1-1): day/night spawn-RATE multiplier, scaling ONLY the
        // refill limit via SpawnRateBudget's fractional-carry accumulator -- see
        // FillagerPopulationSimulation.reconcile's own comment for why spawnCapPerInterval stays unscaled
        // and why a naive per-interval floor was wrong. Composed completely independently of the >250
        // light/sun EnvironmentalOverride (different inputs, never gating each other).
        long dayTime = level.getDayTime();
        int scaledRefill = SpawnRateBudget.nextRefillAllowance(level, spec.faction(), config.reconciliationRefillPerInterval(), dayTime);
        var params = new FillagerReconciliation.Params(config.samplingHorizontalRadius(), config.samplingVerticalRadius(),
            config.fillagersPerFullCell(), scaledRefill, config.spawnCapPerInterval(),
            config.despawnCapPerInterval());
        for (CellKey cell : cells) {
            if (field.blockedReason(cell) != null) {
                continue;
            }
            List<Mob> residents = residents(level, field, cell, spec);
            var decision = FillagerReconciliation.decide(cell, residents.size(), params, 0, field.layers() - 1,
                field::readable, field::get);
            for (int i = 0; i < decision.despawn(); i++) {
                residents.get(i).discard();
            }
            for (int i = 0; i < decision.spawn(); i++) {
                spawn(level, field, cell, spec);
            }
        }
    }

    /**
     * Review fix (P1, round 2): a tagged filler that LATER becomes persistent (named with a name tag,
     * leashed, or any other vanilla persistence-granting action) must stop counting toward reconciliation
     * entirely, not merely stop being a valid population-combat TARGET (already fixed separately in
     * {@code PopulationCombatTargeting}). {@code isFiller} is both where residency is counted toward the
     * local density target AND the candidate pool a bounded despawn picks from, so excluding
     * {@code mob.isPersistenceRequired()} here is the single choke point that removes a now-persistent
     * filler from ALL reconciliation accounting at once -- it is never counted as present, never
     * contributes to "desired" capacity being already met, and is never despawned by the bounded
     * excess-removal (since it is no longer a candidate at all, not merely skipped).
     */
    private static boolean isFiller(net.minecraft.world.entity.Entity entity, Spec spec) {
        return entity instanceof Mob mob && mob.getTags().contains(spec.tag()) && !mob.isPersistenceRequired();
    }

    private static List<Mob> residents(ServerLevel level, LevelField field, CellKey cell, Spec spec) {
        int size = field.cellSize();
        int minY = level.getMinBuildHeight() + cell.layer() * size;
        AABB bounds = new AABB(cell.x() * size, minY, cell.z() * size, (cell.x() + 1) * size, minY + size, (cell.z() + 1) * size);
        List<Mob> candidates = level.getEntitiesOfClass(Mob.class, bounds, e -> isFiller(e, spec));
        return FillagerReconciliation.residentsInCell(candidates, cell, mob -> field.cellAt(mob.blockPosition()));
    }

    private static void spawn(ServerLevel level, LevelField field, CellKey cell, Spec spec) {
        int size = field.cellSize();
        int x = cell.x() * size + level.random.nextInt(size);
        int z = cell.z() * size + level.random.nextInt(size);
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return;
        }
        boolean overrideActive = EnvironmentalOverride.active(field.get(cell));
        var y = FillagerSpawnSearch.findY(cell.layer(), size, level.getMinBuildHeight(), level.getMaxBuildHeight(),
            candidate -> safeSpawn(level, new BlockPos(x, candidate, z), overrideActive));
        if (y.isEmpty()) {
            return;
        }
        BlockPos pos = new BlockPos(x, y.getAsInt(), z);
        Mob mob = spec.factory().apply(level);
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360F, 0);
        mob.addTag(spec.tag()); // before addFreshEntity, so FillerTargetingInstaller sees it on join.
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        if (level.getWorldBorder().isWithinBounds(mob.getBoundingBox()) && level.noCollision(mob)) {
            level.addFreshEntity(mob);
        }
    }

    private static boolean safeSpawn(ServerLevel level, BlockPos pos, boolean overrideActive) {
        var feet = level.getBlockState(pos);
        var head = level.getBlockState(pos.above());
        var floor = level.getBlockState(pos.below());
        boolean structurallySafe = floor.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)
            && floor.getFluidState().isEmpty() && feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()
            && level.noCollision(new AABB(pos.getX() + 0.2, pos.getY(), pos.getZ() + 0.2,
                pos.getX() + 0.8, pos.getY() + 1.95, pos.getZ() + 0.8));
        if (!structurallySafe) {
            return false;
        }
        int lightLevel = level.getMaxLocalRawBrightness(pos);
        return FillerSpawnRules.lightLevelAllows(lightLevel, overrideActive);
    }
}
