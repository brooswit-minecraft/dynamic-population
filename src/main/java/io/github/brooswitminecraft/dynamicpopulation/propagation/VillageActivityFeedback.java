package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.HashSet;
import java.util.Set;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.reconciliation.FillagerReconciliation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
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
        FillagerReconciliation.adjustLocalTarget(cell, increment, field::get, field::set);
    }

    /** Trading (AC6): a player trading with any villager-family entity is positive village activity. */
    @SubscribeEvent
    public static void onTrade(TradeWithVillagerEvent event) {
        if (!(event.getAbstractVillager().level() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        raise(level, event.getAbstractVillager().blockPosition(), DynamicPopulationConfig.get().villageTradeTargetIncrement());
    }

    /** Breeding (AC6): scoped to villagers breeding, since this story's activity list is village-specific. */
    @SubscribeEvent
    public static void onBreeding(BabyEntitySpawnEvent event) {
        AgeableMob child = event.getChild();
        if (!(child instanceof Villager) || !(event.getParentA().level() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        raise(level, child.blockPosition(), DynamicPopulationConfig.get().villageBreedingTargetIncrement());
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
