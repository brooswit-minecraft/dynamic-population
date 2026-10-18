package io.github.brooswitminecraft.dynamicpopulation.propagation;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
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

    /**
     * Per-activity raise adapters are the actual production join point every raise event hook calls into
     * (not a re-derived copy of their logic) -- each selects ITS OWN config getter internally, rather than
     * the caller supplying an already-resolved {@code increment}, so a production bug that reads the wrong
     * weight (or an omitted call entirely) is something a test calling the adapter can actually catch;
     * passing increment/cell in from outside (the previous round's shape) could not. Decoupled from
     * {@link ServerLevel}/{@link LevelField} so a test can drive each one with a plain map and set, the
     * same way {@link FillagerReconciliation}'s own pure functions are tested.
     */
    static void raiseForTrade(CellKey cell, ToIntFunction<CellKey> read, BiConsumer<CellKey, Integer> write,
            Consumer<CellKey> markActive) {
        FillagerReconciliation.raiseLocalTargetAndActivate(cell, DynamicPopulationConfig.get().villageTradeTargetIncrement(),
            read, write, markActive);
    }

    static void raiseForBuilding(CellKey cell, ToIntFunction<CellKey> read, BiConsumer<CellKey, Integer> write,
            Consumer<CellKey> markActive) {
        FillagerReconciliation.raiseLocalTargetAndActivate(cell, DynamicPopulationConfig.get().villageBuildingTargetIncrement(),
            read, write, markActive);
    }

    static void raiseForFarming(CellKey cell, ToIntFunction<CellKey> read, BiConsumer<CellKey, Integer> write,
            Consumer<CellKey> markActive) {
        FillagerReconciliation.raiseLocalTargetAndActivate(cell, DynamicPopulationConfig.get().villageFarmingTargetIncrement(),
            read, write, markActive);
    }

    static void raiseForGolemCreation(CellKey cell, ToIntFunction<CellKey> read, BiConsumer<CellKey, Integer> write,
            Consumer<CellKey> markActive) {
        FillagerReconciliation.raiseLocalTargetAndActivate(cell,
            DynamicPopulationConfig.get().villageGolemCreationTargetIncrement(), read, write, markActive);
    }

    /** Trading (AC6): a player trading with any villager-family entity is positive village activity. */
    @SubscribeEvent
    public static void onTrade(TradeWithVillagerEvent event) {
        if (!(event.getAbstractVillager().level() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        LevelField field = PopulationSimulation.field(level);
        raiseForTrade(field.cellAt(event.getAbstractVillager().blockPosition()), field::get, field::set,
            PopulationSimulation.active(level)::add);
    }

    /**
     * Breeding (AC6): a Villager produced via {@code Villager.getBreedOffspring} -- the shared factory both
     * {@code VillagerMakeLove} (two parent villagers breeding) and {@code SpawnEggItem.spawnOffspringFromSpawnEgg}
     * (a player using a villager spawn egg on an existing villager) call, both tagging the child
     * {@code MobSpawnType.BREEDING} via {@code finalizeSpawn}. This IS the detection signal below, and
     * also its one known, deliberate imprecision -- see {@link #BREEDING_SPAWNS}'s javadoc.
     *
     * <p>Rejected approaches, in order, and why:
     * <ul>
     * <li>{@code BabyEntitySpawnEvent} -- per NeoForge's own Javadoc it is fired only from
     * {@code Animal.spawnChildFromBreeding}/{@code Fox.spawnChildFromBreeding}; {@code VillagerMakeLove}
     * has no such hook in NeoForge's 1.21.1 patch set, so it never fires for village breeding at all.</li>
     * <li>{@code EntityJoinLevelEvent} filtered to a baby, non-disk-loaded {@code Villager} -- that flag
     * only distinguishes a disk load from {@code PersistentEntitySectionManager.addNewEntity}, which also
     * runs for the spawn-egg case above, so it is no narrower a signal than {@code MobSpawnType.BREEDING}
     * itself, while ALSO discarding the one thing {@link FinalizeSpawnEvent} gets right: timing.</li>
     * <li>Raising directly inside {@link #onFinalizeSpawn} (the first version of this fix) -- confirmed
     * wrong by static inspection of vanilla bytecode/sources: {@code getBreedOffspring} calls
     * {@code finalizeSpawn} on the child at its just-constructed position (the world origin), and only
     * the CALLER ({@code VillagerMakeLove.breed}/{@code SpawnEggItem.spawnOffspringFromSpawnEgg}) calls
     * {@code moveTo} the real birth location afterward, before {@code addFreshEntityWithPassengers}. So
     * {@link FinalizeSpawnEvent}'s own coordinates are never the birth location -- only
     * {@link EntityJoinLevelEvent}'s position (read after that {@code moveTo}) is.</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (event.getEntity() instanceof Villager villager && event.getSpawnType() == MobSpawnType.BREEDING) {
            BREEDING_SPAWNS.add(villager);
        }
    }

    /**
     * No accessible NeoForge/vanilla event distinguishes two villagers actually breeding from a player using
     * a spawn egg on an existing villager -- Mojang's own vanilla code (see {@link #onFinalizeSpawn}'s
     * javadoc) deliberately routes both through the identical {@code getBreedOffspring} factory with the
     * identical {@code MobSpawnType.BREEDING} tag, and that choice is made purely by the CALLER with no
     * observable difference left on the resulting event/entity. Per this ticket's own instruction to wire
     * what has an accessible event and document what can't be disambiguated further: this hook therefore
     * also raises the target for the spawn-egg case, the same way {@link #onGolemCreation} already counts
     * a player-built golem the same as a villager-built one -- "a villager was added to the population via
     * the breeding mechanism" is treated as the positive signal, not "two villagers independently decided
     * to mate."
     *
     * <p>{@link #BREEDING_SPAWNS} is a {@code Set} backed by a {@link WeakHashMap} (no public weak-hash-set
     * exists), keyed by entity identity: an entry a cancelled/never-joining spawn leaves behind is simply
     * never removed by {@link #remove}, but becomes collectible the moment nothing else holds the entity,
     * so it never leaks memory -- it is a one-shot marker, not a registry.
     */
    private static final Set<Villager> BREEDING_SPAWNS = Collections.newSetFromMap(new WeakHashMap<>());

    @SubscribeEvent
    public static void onBreeding(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager) || !(event.getLevel() instanceof ServerLevel level)
                || !overworld(level)) {
            return;
        }
        LevelField field = PopulationSimulation.field(level);
        confirmBreedingBirth(BREEDING_SPAWNS, villager, field.cellAt(villager.blockPosition()), field::get,
            field::set, PopulationSimulation.active(level)::add);
    }

    /**
     * The pure, unit-tested half of the two-event breeding-birth tracking AND the activity-specific adapter
     * for breeding: marking happens in {@link #onFinalizeSpawn} ({@code pending.add}, not shown here since
     * {@link Set#add} needs no wrapper); this is the confirm-and-consume-then-raise step, generic over the
     * entity type so it is testable without any Minecraft type, but using the exact same
     * {@code villageBreedingTargetIncrement} getter {@link #onBreeding} would otherwise have called inline.
     * A join NOT preceded by a matching {@link #onFinalizeSpawn} mark (an ordinary villager joining the
     * level any other way) must never read, write, or activate the cell -- not just leave it unchanged.
     */
    static <T> void confirmBreedingBirth(Set<T> pendingBreedingSpawns, T entity, CellKey cell,
            ToIntFunction<CellKey> read, BiConsumer<CellKey, Integer> write, Consumer<CellKey> markActive) {
        if (pendingBreedingSpawns.remove(entity)) {
            FillagerReconciliation.raiseLocalTargetAndActivate(cell,
                DynamicPopulationConfig.get().villageBreedingTargetIncrement(), read, write, markActive);
        }
    }

    /** Building (AC6): any block a player places. */
    @SubscribeEvent
    public static void onBuilding(BlockEvent.EntityPlaceEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player) || !(event.getLevel() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        LevelField field = PopulationSimulation.field(level);
        raiseForBuilding(field.cellAt(event.getPos()), field::get, field::set, PopulationSimulation.active(level)::add);
    }

    /** Farming (AC6): any crop-family block finishing a growth step. */
    @SubscribeEvent
    public static void onFarming(CropGrowEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        LevelField field = PopulationSimulation.field(level);
        raiseForFarming(field.cellAt(event.getPos()), field::get, field::set, PopulationSimulation.active(level)::add);
    }

    /** Golem creation (AC6): a freshly-created Iron Golem, never one merely loaded back in from disk. */
    @SubscribeEvent
    public static void onGolemCreation(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getEntity() instanceof IronGolem golem)
                || !(event.getLevel() instanceof ServerLevel level) || !overworld(level)) {
            return;
        }
        LevelField field = PopulationSimulation.field(level);
        raiseForGolemCreation(field.cellAt(golem.blockPosition()), field::get, field::set,
            PopulationSimulation.active(level)::add);
    }

    /**
     * Destruction (AC6): every distinct cell an explosion actually touched, never a neighbour it didn't --
     * the activity-specific adapter selects its own {@code villageDestructionTargetDecrement} getter
     * internally, same as every raise adapter above.
     */
    static void lowerForDestruction(Set<CellKey> touchedCells, ToIntFunction<CellKey> read,
            BiConsumer<CellKey, Integer> write) {
        FillagerReconciliation.applyDestruction(touchedCells, DynamicPopulationConfig.get().villageDestructionTargetDecrement(),
            read, write);
    }

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
        lowerForDestruction(touchedCells, field::get, field::set);
    }
}
