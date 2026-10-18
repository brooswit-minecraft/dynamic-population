package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.reconciliation.FillagerReconciliation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

/**
 * AC6 (MINECRAFT-30): positive village activity raises the local target; deaths/destruction lower it. This
 * class only moves the target in response to events -- it never spawns/despawns an entity directly (that
 * stays FillagerPopulationSimulation's/the reconciliation loop's job, per this story's fixed decisions).
 *
 * <p>Restricted to the Overworld, the same assumption (A1) the rest of the mod's simulation already makes
 * (see {@link PopulationSimulation#simulated}).
 *
 * <p>Named "unwired" in the PR description for this story (job-site work): there is no NeoForge/vanilla
 * event fired when a villager works a job-site block (composting, smithing, etc.) -- that logic lives
 * entirely inside {@code Villager}'s internal brain/behavior tree with no public hook as of NeoForge
 * 21.1.250/251. Wiring it would require either reflection into brain internals or a tick-polling
 * reimplementation of the vanilla job-site-use heuristic, both far more fragile than an accessible game
 * event; left unwired rather than built on either.
 */
public final class VillageActivityFeedback {
    private VillageActivityFeedback() { }

    private static boolean overworld(Level level) {
        return level instanceof ServerLevel serverLevel && PopulationSimulation.simulated(serverLevel);
    }

    private static void raise(ServerLevel level, BlockPos pos, int increment) {
        LevelField field = PopulationSimulation.field(level);
        raise(field.cellAt(pos), increment, field::get, field::set, PopulationSimulation.active(level)::add);
    }

    /**
     * The real adapter every raise event hook calls into -- decoupled from {@link ServerLevel}/
     * {@link LevelField} so a test can drive it with a plain map and set, the same way
     * {@link FillagerReconciliation}'s own pure functions are tested, while still exercising the exact
     * method the event handlers below call (not a re-derived copy of their logic).
     */
    static void raise(CellKey cell, int increment, ToIntFunction<CellKey> read, BiConsumer<CellKey, Integer> write,
            Consumer<CellKey> markActive) {
        FillagerReconciliation.raiseLocalTargetAndActivate(cell, increment, read, write, markActive);
    }

    /** Trading (AC6): a player trading with any villager-family entity is positive village activity. */
    @SubscribeEvent
    public static void onTrade(TradeWithVillagerEvent event) {
        if (!(event.getAbstractVillager().level() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        raise(level, event.getAbstractVillager().blockPosition(), DynamicPopulationConfig.get().villageTradeTargetIncrement());
    }

    /**
     * Breeding (AC6): a Villager being spawned with {@link MobSpawnType#BREEDING}.
     *
     * <p>Two rejected approaches, in order, and why:
     * <ul>
     * <li>{@code BabyEntitySpawnEvent} -- per NeoForge's own Javadoc it is fired only from
     * {@code Animal.spawnChildFromBreeding}/{@code Fox.spawnChildFromBreeding}; the vanilla
     * {@code VillagerMakeLove} behavior normal villagers breed through has no such hook in NeoForge's
     * 1.21.1 patch set, so it never fires for ordinary village breeding at all.</li>
     * <li>{@code EntityJoinLevelEvent} filtered to a baby, non-disk-loaded {@code Villager} -- that flag
     * only distinguishes a disk load from {@code PersistentEntitySectionManager.addNewEntity}, which also
     * runs for a baby created any other way (a spawn egg used on an adult villager produces exactly the
     * same {@code (loadedFromDisk=false, isBaby=true)} shape with no breeding at all).</li>
     * </ul>
     *
     * <p>{@code VillagerMakeLove.breed} calls {@code Villager.getBreedOffspring}, which calls
     * {@code finalizeSpawn(level, difficulty, MobSpawnType.BREEDING, null)} on the child before it ever
     * joins the level -- NeoForge fires {@link FinalizeSpawnEvent} immediately before that vanilla call,
     * exposing the exact {@link MobSpawnType} that caused the spawn as a genuine, one-time, per-entity
     * signal (never reused for a spawn egg, which uses {@code MobSpawnType.SPAWN_EGG}, or a cured zombie
     * villager, which uses {@code MobSpawnType.CONVERSION}).
     */
    @SubscribeEvent
    public static void onBreeding(FinalizeSpawnEvent event) {
        if (!(event.getEntity() instanceof Villager) || event.getSpawnType() != MobSpawnType.BREEDING
                || !(event.getLevel() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        raise(level, BlockPos.containing(event.getX(), event.getY(), event.getZ()),
            DynamicPopulationConfig.get().villageBreedingTargetIncrement());
    }

    /** Building (AC6): any block a player places. */
    @SubscribeEvent
    public static void onBuilding(BlockEvent.EntityPlaceEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player) || !(event.getLevel() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        raise(level, event.getPos(), DynamicPopulationConfig.get().villageBuildingTargetIncrement());
    }

    /** Farming (AC6): any crop-family block finishing a growth step. */
    @SubscribeEvent
    public static void onFarming(CropGrowEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        raise(level, event.getPos(), DynamicPopulationConfig.get().villageFarmingTargetIncrement());
    }

    /** Golem creation (AC6): a freshly-created Iron Golem, never one merely loaded back in from disk. */
    @SubscribeEvent
    public static void onGolemCreation(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getEntity() instanceof IronGolem golem)
                || !(event.getLevel() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        raise(level, golem.blockPosition(), DynamicPopulationConfig.get().villageGolemCreationTargetIncrement());
    }

    /** Destruction (AC6): every distinct cell an explosion actually touched, never a neighbour it didn't. */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        LevelField field = PopulationSimulation.field(level);
        Set<CellKey> touchedCells = new HashSet<>();
        for (BlockPos pos : event.getAffectedBlocks()) {
            touchedCells.add(field.cellAt(pos));
        }
        FillagerReconciliation.applyDestruction(touchedCells, DynamicPopulationConfig.get().villageDestructionTargetDecrement(),
            field::get, field::set);
    }
}
