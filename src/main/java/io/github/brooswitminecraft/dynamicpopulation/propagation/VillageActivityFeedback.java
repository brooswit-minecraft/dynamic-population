package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.HashSet;
import java.util.Set;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.reconciliation.FillagerReconciliation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
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
        CellKey cell = field.cellAt(pos);
        FillagerReconciliation.raiseLocalTargetAndActivate(cell, increment, field::get, field::set,
            PopulationSimulation.active(level)::add);
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
     * Breeding (AC6): a freshly-created baby Villager. {@code BabyEntitySpawnEvent} is deliberately NOT
     * used here -- per NeoForge's own Javadoc it is fired from {@code Animal.spawnChildFromBreeding} and
     * {@code Fox.spawnChildFromBreeding} only; the vanilla {@code VillagerMakeLove} behavior normal villager
     * breeding actually runs through has no such hook in NeoForge's 1.21.1 patch set, so filtering that
     * event to a {@code Villager} child would never fire for ordinary village breeding. A baby villager
     * still reaches the level via the same {@code Level#addFreshEntity} path every other fresh entity does,
     * so {@link EntityJoinLevelEvent} (already used below for golem creation) is the actually-accessible
     * hook -- {@code loadedFromDisk() == false} excludes a baby merely loaded back in with its chunk, and
     * {@code isBaby()} excludes an adult villager joining the level by any other means (a cured zombie
     * villager, a /summon, a boat/portal transfer).
     */
    @SubscribeEvent
    public static void onBreeding(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager)
                || !isOrdinaryVillagerBirth(event.loadedFromDisk(), villager.isBaby())
                || !(event.getLevel() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        raise(level, villager.blockPosition(), DynamicPopulationConfig.get().villageBreedingTargetIncrement());
    }

    /**
     * The pure, unit-tested half of {@link #onBreeding}: a baby Villager that was NOT loaded from disk is a
     * real birth (a villager merely loaded back in with its chunk, or an adult villager joining the level by
     * any other means -- a cured zombie villager, a {@code /summon}, a boat/portal transfer -- must not
     * count).
     */
    static boolean isOrdinaryVillagerBirth(boolean loadedFromDisk, boolean isBaby) {
        return !loadedFromDisk && isBaby;
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
