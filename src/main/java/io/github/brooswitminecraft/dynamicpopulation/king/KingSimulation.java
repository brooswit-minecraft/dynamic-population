package io.github.brooswitminecraft.dynamicpopulation.king;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;
import io.github.brooswitminecraft.dynamicpopulation.propagation.LevelField;
import io.github.brooswitminecraft.dynamicpopulation.propagation.PopulationSimulation;

/**
 * Drives King seeding (AC4) on the server: every {@code king_seed_interval_ticks}, every King in every
 * simulated (Overworld, V1) level raises the real cell field near itself via {@link KingSeeding}, against the
 * exact {@link LevelField} glue {@code Propagation} itself steps against -- so seeding and propagation share
 * one storage path, never two. Seeded cells are marked active so propagation's own tick picks them up from
 * there; the King does not step propagation itself.
 *
 * <p>MINECRAFT-390: each King seeds ONLY its own faction's field -- {@link #seedAll} looks up
 * {@code PopulationSimulation.field(level, entry.faction())}, so a Zombie King raises only the zombie
 * attachment, never the Villager or Skeleton one, and likewise for every other faction. This also now
 * drives {@code KingEntitySync.sync}, so every loaded level self-heals any King record missing its entity
 * (a pre-390 legacy record, or any other drift) on the same cadence, without a separate tick listener.
 */
public final class KingSimulation {
    private KingSimulation() { }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int interval = DynamicPopulationConfig.get().kingSeedIntervalTicks();
        if (event.getServer().getTickCount() % interval != 0) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (PopulationSimulation.simulated(level)) {
                KingEntitySync.sync(level);
                seedAll(level);
            }
        }
    }

    private static void seedAll(ServerLevel level) {
        var config = DynamicPopulationConfig.get();
        for (Faction faction : Faction.values()) {
            LevelField field = PopulationSimulation.field(level, faction);
            for (KingRecord.Entry entry : KingManager.kings(level, faction)) {
                CellKey cell = field.cellAt(new BlockPos(entry.x(), entry.y(), entry.z()));
                KingSeeding.seed(field, cell, config.kingSeedRadiusCells(), config.kingSeedRatePpt());
                for (CellKey seeded : KingSeeding.cellsInRange(cell, config.kingSeedRadiusCells())) {
                    if (field.get(seeded) > 0) {
                        PopulationSimulation.active(level, faction).add(seeded);
                    }
                }
            }
        }
    }
}
