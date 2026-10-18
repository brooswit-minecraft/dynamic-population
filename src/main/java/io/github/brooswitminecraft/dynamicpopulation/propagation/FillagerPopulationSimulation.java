package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.entity.DynamicPopulationEntities;
import io.github.brooswitminecraft.dynamicpopulation.entity.Fillager;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
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

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Fillager fillager) || !(fillager.level() instanceof ServerLevel level)) {
            return;
        }
        LevelField field = PopulationSimulation.field(level);
        CellKey localCell = field.cellAt(fillager.blockPosition());
        var config = DynamicPopulationConfig.get();
        boolean unsafe = isUnsafeDeath(fillager, event.getSource());
        if (unsafe) {
            FillagerReconciliation.applyUnsafeDeathIfUnsafe(true, localCell, field::get, field::set,
                config.unsafeDeathTargetDecrement());
        } else {
            // AC6 (MINECRAFT-30): every Fillager death DPOP-7's unsafe-location case doesn't already cover
            // (killed by a player or mob, for example) is still a negative village-activity signal, just a
            // weaker/independently-configurable one than a genuine local hazard.
            FillagerReconciliation.adjustLocalTarget(localCell, -config.fillagerOtherDeathTargetDecrement(),
                field::get, field::set);
        }
    }

    /**
     * AC9 unsafe-death classification. {@code Fillager.wasUnsafeLocationDeath()} covers every hazard that is
     * identified by WHERE the Fillager is standing (lava/fire/soul-fire, and wither rose, all checked by
     * location in {@code Fillager.aiStep}) -- that is the only correct way to classify wither rose, since
     * the actual kill comes from a delayed Wither status-effect tick whose {@code DamageType} ({@code
     * DamageTypes.WITHER}) is indistinguishable from the same effect applied by a wither skeleton or the
     * Wither boss; classifying by damage type there would misattribute an ordinary mob kill as a
     * local-cell hazard death. Drowning and in-wall suffocation have no feet-block equivalent at all (no
     * single block identifies them), so those two are classified by their real {@code DamageType} registry
     * key instead (stable; {@code msgId} is not -- e.g. {@code DamageTypes.CAMPFIRE} and {@code
     * DamageTypes.IN_FIRE} share the msgId "inFire", which is why registry key and not msgId is used here).
     * {@link #isUnsafeDeathTypePath} is the pure, unit-tested half of this; it never receives storage.
     */
    private static boolean isUnsafeDeath(Fillager fillager, net.minecraft.world.damagesource.DamageSource source) {
        boolean hasAttacker = source.getEntity() != null;
        String damageTypePath = source.typeHolder().unwrapKey().map(key -> key.location().getPath()).orElse("");
        return isUnsafeDeath(fillager.wasUnsafeLocationDeath(), hasAttacker, damageTypePath);
    }

    /**
     * The pure, unit-tested half of {@link #isUnsafeDeath}: no Minecraft registry or entity needed to test
     * this. {@code locationUnsafe && !hasAttacker} is required, not {@code locationUnsafe} alone: wither
     * rose is a hazard the Fillager can survive, so a player/mob kill landing while it happens to be
     * standing on one must not count -- genuine location hazards never have an attacking entity (lava/fire's
     * self-kill uses {@code damageSources().generic()}; the wither-rose kill arrives as the Wither effect's
     * own source), while a player, mob, or projectile kill always does.
     */
    static boolean isUnsafeDeath(boolean locationUnsafe, boolean hasAttacker, String damageTypePath) {
        if (locationUnsafe && !hasAttacker) {
            return true;
        }
        return isUnsafeDeathTypePath(damageTypePath);
    }

    /** The registry-key path half of {@link #isUnsafeDeath}: no Minecraft registry needed to unit test this. */
    static boolean isUnsafeDeathTypePath(String damageTypePath) {
        return UNSAFE_DEATH_TYPE_PATHS.contains(damageTypePath);
    }

    // Wither rose and on-fire are deliberately NOT here. Wither rose is classified by location in
    // Fillager.aiStep (see wasUnsafeLocationDeath) instead, because DamageTypes.WITHER cannot distinguish
    // "stood on a wither rose" from "a wither skeleton or the Wither boss killed this mob". "on_fire" is the
    // burning status damage-over-time tick, dealt regardless of what ignited the Fillager (a Flame bow,
    // Fire Aspect, a blaze/ghast fireball, lightning) -- not a location at all; the location causes of
    // burning (lava, fire, soul fire) are already covered by wasUnsafeLocationDeath, which kills the
    // Fillager the same tick via Fillager.isLethalHazard before any on_fire damage-over-time tick can fire.
    private static final Set<String> UNSAFE_DEATH_TYPE_PATHS = Set.of(
        "lava", "in_fire", "hot_floor", "campfire", "cactus", "sweet_berry_bush", "drown", "in_wall");
}
