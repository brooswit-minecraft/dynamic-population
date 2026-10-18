package io.github.brooswitminecraft.dynamicpopulation.faction;

/**
 * MINECRAFT-390: the three population factions. Villager (the original Fillager/Villager-King population)
 * plus the two new non-persistent filler factions, Zombie and Skeleton, each with its own King.
 *
 * <p>Each faction has its own independent cell-field density storage (see {@code CellFieldStorage}'s
 * per-faction attachments) and its own King (see the {@code king} package) -- the three factions never share
 * a density value or a King registry entry, so seeding/propagation/reconciliation for one faction can never
 * leak into another's numbers.
 */
public enum Faction {
    VILLAGER, ZOMBIE, SKELETON
}
