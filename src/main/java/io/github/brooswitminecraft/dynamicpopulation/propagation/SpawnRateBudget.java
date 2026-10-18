package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The Minecraft-wiring half of {@link DayNightSpawnRate#accumulate} (review fix P1-1): holds the ONE
 * fractional carry-over per (level, faction) that {@link DayNightSpawnRate.Accumulation} needs threaded
 * across reconciliation intervals, so the day/night multiplier's long-run rate is correct even though any
 * single interval's emitted whole-number allowance can be (and at low configured caps routinely is) zero.
 *
 * <p>Deliberately NOT per-cell, per-entity, or per-spawn-call: one budget per (level, faction), matching
 * {@code fillager_refill_per_interval}'s own documented meaning as a GLOBAL per-interval cap, not a
 * per-cell one (the separate {@code fillager_spawn_cap_per_interval} stays unscaled -- see the call sites'
 * own comments for why only the refill limit is governed by this rate, not the per-cell spawn cap).
 */
public final class SpawnRateBudget {
    private static final Map<ServerLevel, Map<Faction, Double>> CARRY = new HashMap<>();

    private SpawnRateBudget() { }

    /** This interval's refill allowance for {@code faction} in {@code level}, threading the carry forward. */
    public static int nextRefillAllowance(ServerLevel level, Faction faction, int configuredRefillLimit, long dayTimeTicks) {
        Map<Faction, Double> perLevel = CARRY.computeIfAbsent(level, l -> new EnumMap<>(Faction.class));
        double carryIn = perLevel.getOrDefault(faction, 0.0);
        double multiplier = DayNightSpawnRate.multiplier(dayTimeTicks, faction);
        var result = DayNightSpawnRate.accumulate(carryIn, configuredRefillLimit, multiplier);
        perLevel.put(faction, result.carry());
        return result.spawnAllowance();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CARRY.clear();
    }
}
