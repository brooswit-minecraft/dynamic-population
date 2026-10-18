package io.github.brooswitminecraft.dynamicpopulation.config;

import java.io.IOException;
import java.nio.file.Path;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config scaffold (AC8). Values are read live via {@link #get()},
 * never captured in a {@code static final} at class load — that pattern is
 * exactly dynamic-atmosphere's {@code smokeOpticalDensity} bug, since it
 * freezes whatever the value was at class-init time and never sees a
 * reload. {@link #snapshot} is rebuilt on every Loading/Reloading/Unloading
 * event and every read goes through the volatile field.
 *
 * <p>How to add a later {@code RestartType.NONE} key: (1) add a
 * {@code BUILDER.comment(...).define(...)} (or {@code defineInRange})
 * call below — omit {@code .worldRestart()}/{@code .gameRestart()} for a
 * NONE-restart value — producing a new {@code ConfigValue} field; (2) add a
 * field for it to {@link Snapshot} and read it in {@link #readSnapshot()}.
 * No other restructuring needed.
 */
public final class DynamicPopulationConfig {
    private DynamicPopulationConfig() { }

    /**
     * OBSERVED live via a real server boot (scripts/smoke-server.py's boot
     * sequence, run manually against a non-deleted server root): this
     * SERVER-type config is written to and read from the per-instance
     * {@code <instance>/config/} directory, the same directory a client/
     * common config would use -- NOT {@code <world>/serverconfig/} as an
     * earlier version of this comment claimed. {@code <world>/serverconfig/}
     * is a separate, optional override mechanism (see its own generated
     * {@code readme.txt}): a file placed there by hand overrides the
     * corresponding {@code config/} file for that world, but nothing is
     * written there automatically. Worth knowing because it is the opposite
     * of what's commonly assumed for NeoForge SERVER configs -- flagged
     * here since DPOP-4's own steering asserted the {@code serverconfig/}
     * location, and that assertion did not hold up under an actual boot on
     * NeoForge 21.1.251 / FML 4.0.44.
     */
    public static final String FILE_NAME = "dynamicpopulation-server.toml";

    /**
     * Bump ONLY when an existing key's shipped default actually moves — not
     * every release, and not for a new key (nightconfig already fills new
     * keys in from their defaults and leaves existing keys alone; see
     * {@link ConfigVersionMigration} for the rename-aside this triggers).
     */
    public static final int CURRENT_CONFIG_VERSION = 1;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.ConfigValue<Integer> CELL_SIZE;
    private static final ModConfigSpec.ConfigValue<Integer> CONFIG_VERSION;
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.DoubleValue WEIGHT_DOWN;
    private static final ModConfigSpec.DoubleValue WEIGHT_SIDES;
    private static final ModConfigSpec.DoubleValue WEIGHT_UP;
    private static final ModConfigSpec.DoubleValue SPREAD_FRACTION;
    private static final ModConfigSpec.IntValue STEP_INTERVAL_TICKS;
    private static final ModConfigSpec.IntValue TRAVERSABLE_SAMPLE_SPACING;

    private static final ModConfigSpec.IntValue KING_UNIQUENESS_RADIUS_CELLS;
    private static final ModConfigSpec.IntValue KING_MIN_SKY_LIGHT;
    private static final ModConfigSpec.IntValue KING_MAX_DEPTH_BELOW_SURFACE_BLOCKS;
    private static final ModConfigSpec.IntValue KING_SEED_RADIUS_CELLS;
    private static final ModConfigSpec.IntValue KING_SEED_RATE_PPT;
    private static final ModConfigSpec.IntValue KING_SEED_INTERVAL_TICKS;

    private static final ModConfigSpec.IntValue SAMPLING_HORIZONTAL_RADIUS;
    private static final ModConfigSpec.IntValue SAMPLING_VERTICAL_RADIUS;
    private static final ModConfigSpec.IntValue FILLAGERS_PER_FULL_CELL;
    private static final ModConfigSpec.IntValue RECONCILIATION_REFILL_PER_INTERVAL;
    private static final ModConfigSpec.IntValue SPAWN_CAP_PER_INTERVAL;
    private static final ModConfigSpec.IntValue DESPAWN_CAP_PER_INTERVAL;
    private static final ModConfigSpec.IntValue RECONCILIATION_INTERVAL_TICKS;
    private static final ModConfigSpec.IntValue UNSAFE_DEATH_TARGET_DECREMENT;

    // AC6 (MINECRAFT-30): village activity feedback. Each event type is its own independently-configurable
    // weight -- "raise" and "lower" magnitudes are never collapsed into a shared value, since e.g. trading
    // happens far more often than a village-destroying explosion and needs a much smaller per-event weight
    // to avoid saturating the target.
    private static final ModConfigSpec.IntValue VILLAGE_TRADE_TARGET_INCREMENT;
    private static final ModConfigSpec.IntValue VILLAGE_BREEDING_TARGET_INCREMENT;
    private static final ModConfigSpec.IntValue VILLAGE_BUILDING_TARGET_INCREMENT;
    private static final ModConfigSpec.IntValue VILLAGE_FARMING_TARGET_INCREMENT;
    private static final ModConfigSpec.IntValue VILLAGE_GOLEM_CREATION_TARGET_INCREMENT;
    private static final ModConfigSpec.IntValue FILLAGER_OTHER_DEATH_TARGET_DECREMENT;
    private static final ModConfigSpec.IntValue VILLAGE_DESTRUCTION_TARGET_DECREMENT;

    /** Smallest direction weight the config accepts: a zero weight would turn the weighted rule back into strict priority. */
    public static final double MIN_WEIGHT = 0.01;

    public record Snapshot(int cellSize, int configVersion, double weightDown, double weightSides, double weightUp,
            double spreadFraction, int stepIntervalTicks, int traversableSampleSpacing,
            int kingUniquenessRadiusCells, int kingMinSkyLight, int kingMaxDepthBelowSurfaceBlocks,
            int kingSeedRadiusCells, int kingSeedRatePpt, int kingSeedIntervalTicks, int samplingHorizontalRadius,
            int samplingVerticalRadius, int fillagersPerFullCell, int reconciliationRefillPerInterval,
            int spawnCapPerInterval, int despawnCapPerInterval, int reconciliationIntervalTicks,
            int unsafeDeathTargetDecrement, int villageTradeTargetIncrement, int villageBreedingTargetIncrement,
            int villageBuildingTargetIncrement, int villageFarmingTargetIncrement,
            int villageGolemCreationTargetIncrement, int fillagerOtherDeathTargetDecrement,
            int villageDestructionTargetDecrement) {
        public Snapshot {
            if (!(weightDown >= MIN_WEIGHT) || !(weightSides >= MIN_WEIGHT) || !(weightUp >= MIN_WEIGHT)) {
                throw new IllegalArgumentException("propagation direction weights must be > 0");
            }
        }
    }

    static {
        BUILDER.comment("Dynamic Population server config.");

        CELL_SIZE = BUILDER
            .comment(
                "Edge length, in blocks, of a population cell (must be a multiple of 16 -- "
                    + "16..1024 -- so a cell footprint is always a whole number of chunks). "
                    + "Changing this RESETS the population field: previously saved cell data "
                    + "whose header no longer matches this value is discarded on load and "
                    + "regrows from the Villager King. Baked into world-save data, so it needs "
                    + "a restart to take effect.")
            .worldRestart()
            .define("cell_size", 64, DynamicPopulationConfig::isValidCellSize);

        CONFIG_VERSION = BUILDER
            .comment(
                "Internal bookkeeping -- do not hand-edit. Bumped by this mod only when an "
                    + "existing key's shipped default has changed. On a mismatch at load, the "
                    + "previous file is renamed aside to <name>.toml.bak-<oldVersion> and a "
                    + "fresh file is regenerated from current defaults; this is a diagnosis aid, "
                    + "not a safety net -- any externally synced keys (e.g. via Sickos) may need "
                    + "to be re-applied after such a bump.")
            .define("configVersion", CURRENT_CONFIG_VERSION, value -> value instanceof Integer);

        WEIGHT_DOWN = BUILDER
            .comment(
                "Propagation: relative share of what spreads out of a cell that goes DOWN each step. These three "
                    + "weights are relative shares, not absolute rates (doubling all three changes nothing), and they "
                    + "do not set how much population exists, only where it goes. Each must stay above zero: a zero "
                    + "weight would turn the weighted rule into strict priority. Down should be heaviest.")
            .defineInRange("propagation_weight_down", 3.0, MIN_WEIGHT, 100.0);
        WEIGHT_SIDES = BUILDER
            .comment("Propagation: relative share that goes to each of the four SIDES (see propagation_weight_down).")
            .defineInRange("propagation_weight_sides", 2.0, MIN_WEIGHT, 100.0);
        WEIGHT_UP = BUILDER
            .comment("Propagation: relative share that goes UP (see propagation_weight_down). Up is never starved.")
            .defineInRange("propagation_weight_up", 1.0, MIN_WEIGHT, 100.0);
        SPREAD_FRACTION = BUILDER
            .comment("Propagation: the share of a cell's population that leaves it per step (0.01..0.5). Higher "
                + "spreads faster; it does not change how much population exists.")
            .defineInRange("propagation_spread_fraction", 0.1, 0.01, 0.5);
        STEP_INTERVAL_TICKS = BUILDER
            .comment("Propagation: server ticks between propagation steps (1..1200). Lower is a faster simulation.")
            .defineInRange("propagation_step_interval_ticks", 20, 1, 1200);
        TRAVERSABLE_SAMPLE_SPACING = BUILDER
            .comment("Propagation: a cell is traversable if it has at least one open or liquid block; blocks are "
                + "sampled on a lattice this many blocks apart (4..32). Smaller is more accurate and slower.")
            .defineInRange("traversable_sample_spacing", 8, 4, 32);

        KING_UNIQUENESS_RADIUS_CELLS = BUILDER
            .comment(
                "Villager King (AC4): a second King may not spawn/persist within this many cells (horizontally, "
                    + "Chebyshev distance, same cell_size the field uses) of an existing King -- this IS the "
                    + "ticket's 'relevant loaded area'. 1..64.")
            .defineInRange("king_uniqueness_radius_cells", 8, 1, 64);
        KING_MIN_SKY_LIGHT = BUILDER
            .comment(
                "Villager King (AC4) territory preference: minimum sky light (0 = none, 15 = fully open to sky) "
                    + "a location needs to count as suitable surface territory. 0..15.")
            .defineInRange("king_min_sky_light", 9, 0, 15);
        KING_MAX_DEPTH_BELOW_SURFACE_BLOCKS = BUILDER
            .comment(
                "Villager King (AC4) territory preference: a location must be no more than this many blocks "
                    + "below the heightmap surface (MOTION_BLOCKING) to count as suitable surface territory. "
                    + "0..64. Biome preference itself (plains-family) is a fixed tag check, not a numeric "
                    + "tunable -- there is no meaningful range for 'how plains-y'.")
            .defineInRange("king_max_depth_below_surface_blocks", 4, 0, 64);
        KING_SEED_RADIUS_CELLS = BUILDER
            .comment(
                "Villager King (AC4) seeding: horizontal radius, in cells (Chebyshev, King's own layer only), "
                    + "within which the King raises the cell field's target density each seeding step. 1..64.")
            .defineInRange("king_seed_radius_cells", 3, 1, 64);
        KING_SEED_RATE_PPT = BUILDER
            .comment(
                "Villager King (AC4) seeding: parts-per-thousand added to each traversable cell in range per "
                    + "seeding step (capped at the field's own CAPACITY, never exceeds it). 1..1000.")
            .defineInRange("king_seed_rate_ppt", 5, 1, 1000);
        KING_SEED_INTERVAL_TICKS = BUILDER
            .comment(
                "Villager King (AC4) seeding: server ticks between seeding steps. Separate from "
                    + "propagation_step_interval_ticks -- seeding and propagation are independent rates. 1..1200. "
                    + "Lifespan: fixed-decisions deliberately leaves the King unbounded (never despawns on its "
                    + "own) -- there is no lifespan key because there is no numeric tunable to attach to that "
                    + "choice; see docs/villager-king.md.")
            .defineInRange("king_seed_interval_ticks", 20, 1, 1200);

        SAMPLING_HORIZONTAL_RADIUS = BUILDER.comment("Fillager reconciliation: horizontal radius, in cells, sampled around a decision (0..16). A larger window makes the decision smoother and less local; it does NOT raise population density.").defineInRange("fillager_sampling_horizontal_radius", 5, 0, 16);
        SAMPLING_VERTICAL_RADIUS = BUILDER.comment("Fillager reconciliation: vertical radius, in cells, sampled around a decision (0..6). A larger window makes the decision smoother and less local; it does NOT raise population density.").defineInRange("fillager_sampling_vertical_radius", 2, 0, 6);
        FILLAGERS_PER_FULL_CELL = BUILDER.comment("Fillager reconciliation: temporary Fillagers represented by a cell at 1000 ppt target density (1..64).")
            .defineInRange("fillagers_per_full_cell", 8, 1, 64);
        RECONCILIATION_REFILL_PER_INTERVAL = BUILDER.comment("Fillager reconciliation: maximum missing Fillagers refilled in one interval (1..64); this bounded rate makes promotion leave a temporary gap.")
            .defineInRange("fillager_refill_per_interval", 1, 1, 64);
        SPAWN_CAP_PER_INTERVAL = BUILDER.comment("Fillager reconciliation: maximum Fillagers spawned by one sampled-cell decision per interval (1..64).")
            .defineInRange("fillager_spawn_cap_per_interval", 2, 1, 64);
        DESPAWN_CAP_PER_INTERVAL = BUILDER.comment("Fillager reconciliation: maximum excess Fillagers removed by one sampled-cell decision per interval (1..64).")
            .defineInRange("fillager_despawn_cap_per_interval", 2, 1, 64);
        RECONCILIATION_INTERVAL_TICKS = BUILDER.comment("Fillager reconciliation: server ticks between sampled spawn/despawn decisions (1..1200).")
            .defineInRange("fillager_reconciliation_interval_ticks", 40, 1, 1200);
        UNSAFE_DEATH_TARGET_DECREMENT = BUILDER.comment("Fillager reconciliation: ppt removed from only the local cell when a Fillager dies at an unsafe location (1..1000).")
            .defineInRange("fillager_unsafe_death_target_decrement", 25, 1, 1000);

        VILLAGE_TRADE_TARGET_INCREMENT = BUILDER.comment("Village activity (AC6): ppt added to only the local cell when a player trades with a villager (1..1000). Trading is frequent, so this is deliberately small.")
            .defineInRange("village_trade_target_increment", 2, 1, 1000);
        VILLAGE_BREEDING_TARGET_INCREMENT = BUILDER.comment("Village activity (AC6): ppt added to only the local cell when a villager breeding event produces a child (1..1000).")
            .defineInRange("village_breeding_target_increment", 10, 1, 1000);
        VILLAGE_BUILDING_TARGET_INCREMENT = BUILDER.comment("Village activity (AC6): ppt added to only the local cell when a player places a block (1..1000). Building is very frequent, so this is deliberately small.")
            .defineInRange("village_building_target_increment", 1, 1, 1000);
        VILLAGE_FARMING_TARGET_INCREMENT = BUILDER.comment("Village activity (AC6): ppt added to only the local cell when a crop finishes growing (1..1000).")
            .defineInRange("village_farming_target_increment", 3, 1, 1000);
        VILLAGE_GOLEM_CREATION_TARGET_INCREMENT = BUILDER.comment("Village activity (AC6): ppt added to only the local cell when an Iron Golem is freshly created (built, not loaded from disk) (1..1000). Golem creation is rare, so this is deliberately large.")
            .defineInRange("village_golem_creation_target_increment", 50, 1, 1000);
        FILLAGER_OTHER_DEATH_TARGET_DECREMENT = BUILDER.comment("Village activity (AC6): ppt removed from only the local cell when a Fillager dies for a reason OTHER than the unsafe-location case already covered by fillager_unsafe_death_target_decrement (e.g. killed by a player or mob). Independently configurable since an unsafe-location death is a stronger, more specific local-danger signal (1..1000).")
            .defineInRange("fillager_other_death_target_decrement", 10, 1, 1000);
        VILLAGE_DESTRUCTION_TARGET_DECREMENT = BUILDER.comment("Village activity (AC6): ppt removed from each cell touched by an explosion's destroyed blocks (1..1000).")
            .defineInRange("village_destruction_target_decrement", 15, 1, 1000);

        SPEC = BUILDER.build();
    }

    // Never a `static final` seeded once: rebuilt on every Loading/Reloading/
    // Unloading event via readSnapshot(), and every consumer calls get() fresh
    // rather than holding on to a Snapshot across a reload. Seeded here (after
    // the static block above, so CELL_SIZE/CONFIG_VERSION already exist) via
    // getDefault(), NOT get() -- ConfigValue.get() throws IllegalStateException
    // ("Cannot get config value before config is loaded") until FML has
    // actually loaded a backing file, which class-init happens well before;
    // observed directly via scripts/smoke-server.py during this change.
    private static volatile Snapshot snapshot = defaults();

    private static boolean isValidCellSize(Object value) {
        return value instanceof Integer i && i >= 16 && i <= 1024 && i % 16 == 0;
    }

    public static Snapshot get() {
        return snapshot;
    }

    private static Snapshot readSnapshot() {
        return new Snapshot(CELL_SIZE.get(), CONFIG_VERSION.get(), WEIGHT_DOWN.get(), WEIGHT_SIDES.get(), WEIGHT_UP.get(),
            SPREAD_FRACTION.get(), STEP_INTERVAL_TICKS.get(), TRAVERSABLE_SAMPLE_SPACING.get(),
            KING_UNIQUENESS_RADIUS_CELLS.get(), KING_MIN_SKY_LIGHT.get(), KING_MAX_DEPTH_BELOW_SURFACE_BLOCKS.get(),
            KING_SEED_RADIUS_CELLS.get(), KING_SEED_RATE_PPT.get(), KING_SEED_INTERVAL_TICKS.get(),
            SAMPLING_HORIZONTAL_RADIUS.get(), SAMPLING_VERTICAL_RADIUS.get(), FILLAGERS_PER_FULL_CELL.get(), RECONCILIATION_REFILL_PER_INTERVAL.get(), SPAWN_CAP_PER_INTERVAL.get(), DESPAWN_CAP_PER_INTERVAL.get(), RECONCILIATION_INTERVAL_TICKS.get(), UNSAFE_DEATH_TARGET_DECREMENT.get(),
            VILLAGE_TRADE_TARGET_INCREMENT.get(), VILLAGE_BREEDING_TARGET_INCREMENT.get(), VILLAGE_BUILDING_TARGET_INCREMENT.get(),
            VILLAGE_FARMING_TARGET_INCREMENT.get(), VILLAGE_GOLEM_CREATION_TARGET_INCREMENT.get(),
            FILLAGER_OTHER_DEATH_TARGET_DECREMENT.get(), VILLAGE_DESTRUCTION_TARGET_DECREMENT.get());
    }

    private static Snapshot defaults() {
        return new Snapshot(CELL_SIZE.getDefault(), CONFIG_VERSION.getDefault(), WEIGHT_DOWN.getDefault(),
            WEIGHT_SIDES.getDefault(), WEIGHT_UP.getDefault(), SPREAD_FRACTION.getDefault(),
            STEP_INTERVAL_TICKS.getDefault(), TRAVERSABLE_SAMPLE_SPACING.getDefault(),
            KING_UNIQUENESS_RADIUS_CELLS.getDefault(), KING_MIN_SKY_LIGHT.getDefault(),
            KING_MAX_DEPTH_BELOW_SURFACE_BLOCKS.getDefault(), KING_SEED_RADIUS_CELLS.getDefault(),
            KING_SEED_RATE_PPT.getDefault(), KING_SEED_INTERVAL_TICKS.getDefault(),
            SAMPLING_HORIZONTAL_RADIUS.getDefault(), SAMPLING_VERTICAL_RADIUS.getDefault(), FILLAGERS_PER_FULL_CELL.getDefault(), RECONCILIATION_REFILL_PER_INTERVAL.getDefault(), SPAWN_CAP_PER_INTERVAL.getDefault(), DESPAWN_CAP_PER_INTERVAL.getDefault(), RECONCILIATION_INTERVAL_TICKS.getDefault(), UNSAFE_DEATH_TARGET_DECREMENT.getDefault(),
            VILLAGE_TRADE_TARGET_INCREMENT.getDefault(), VILLAGE_BREEDING_TARGET_INCREMENT.getDefault(), VILLAGE_BUILDING_TARGET_INCREMENT.getDefault(),
            VILLAGE_FARMING_TARGET_INCREMENT.getDefault(), VILLAGE_GOLEM_CREATION_TARGET_INCREMENT.getDefault(),
            FILLAGER_OTHER_DEATH_TARGET_DECREMENT.getDefault(), VILLAGE_DESTRUCTION_TARGET_DECREMENT.getDefault());
    }

    /**
     * Ordering, verified by disassembling {@code ServerLifecycleHooks} in the
     * resolved neoforge-21.1.251 jar (not assumed): on server start, NeoForge
     * calls {@code ConfigTracker.INSTANCE.loadConfigs(Type.SERVER, ...)} --
     * which reads the on-disk file and fires this very
     * {@code ModConfigEvent.Loading} synchronously -- BEFORE it posts
     * {@code ServerAboutToStartEvent}. There is no earlier, mod-accessible
     * hook: by the time any NeoForge game event fires, FML has already
     * parsed the (possibly stale) file and populated {@code CELL_SIZE}/
     * {@code CONFIG_VERSION} from it. So a configVersion mismatch cannot be
     * caught before FML's own parse -- instead, this handler detects it
     * immediately after that parse, renames the just-read file aside, and
     * forces the in-memory values back to defaults (via set + save, which
     * regenerates the file on disk) before returning -- i.e. before this
     * mod's constructor finishes registering anything else, and long before
     * any chunk/world code can observe a value. That is what makes "the
     * regenerated file is what the game runs on" true for this session, not
     * just for the next restart. NOT OBSERVED end-to-end in a running
     * server as part of this change; verified only by unit test
     * (ConfigVersionMigration, plain Paths) plus this bytecode read.
     */
    public static void onLoading(ModConfigEvent.Loading event) {
        handleConfigVersionMismatch(event);
        refresh(event);
    }

    private static void handleConfigVersionMismatch(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        int stored = CONFIG_VERSION.get();
        if (stored == CURRENT_CONFIG_VERSION) {
            return;
        }
        Path path = event.getConfig().getFullPath();
        ConfigVersionMigration.Result result;
        try {
            result = ConfigVersionMigration.renameAsideIfMismatched(path, stored, CURRENT_CONFIG_VERSION);
        } catch (IOException e) {
            DynamicPopulationMod.LOGGER.error(
                "dynamicpopulation configVersion mismatch (found {}, expected {}) but failed to rename {} aside "
                    + "-- leaving the file as-is, config will NOT be regenerated this load", stored,
                CURRENT_CONFIG_VERSION, path, e);
            return;
        }
        if (!result.renamed()) {
            return;
        }
        CELL_SIZE.set(CELL_SIZE.getDefault());
        CONFIG_VERSION.set(CURRENT_CONFIG_VERSION);
        SPEC.save();
        DynamicPopulationMod.LOGGER.warn(
            "dynamicpopulation configVersion mismatch (found {}, expected {}): renamed {} aside to {} and "
                + "regenerated it from current defaults -- any externally synced keys may need to be re-applied.",
            stored, CURRENT_CONFIG_VERSION, path, result.backupPath());
    }

    public static void onReloading(ModConfigEvent.Reloading event) {
        refresh(event);
    }

    /**
     * Deliberately NOT {@code refresh(event)}: observed live via
     * scripts/smoke-server.py that by the time this event fires (server
     * shutdown, via ConfigTracker.unloadConfig), the backing config is
     * already considered unloaded -- CELL_SIZE.get()/CONFIG_VERSION.get()
     * both throw the same "Cannot get config value before config is loaded"
     * IllegalStateException here as they do pre-load. getDefault() never
     * makes that state check, so falling back to defaults on unload is both
     * the only safe option and the more correct one: no world-specific
     * server config is backing reads once it has unloaded.
     */
    public static void onUnloading(ModConfigEvent.Unloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            snapshot = defaults();
        }
    }

    private static void refresh(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            snapshot = readSnapshot();
        }
    }

    /**
     * Registers the SERVER config and its live-reload listeners. FML's own
     * file watcher (via nightconfig) reloads on disk edits automatically --
     * this deliberately does not add a custom reload command or hook
     * `/reload` (that reloads datapacks, not config). NOT OBSERVED: the
     * live-reload watcher is confirmed present in the FML binary, but firing
     * has not been observed in a running server as part of this change.
     */
    public static void register(ModContainer modContainer, IEventBus modEventBus) {
        modContainer.registerConfig(ModConfig.Type.SERVER, SPEC, FILE_NAME);
        modEventBus.addListener(DynamicPopulationConfig::onLoading);
        modEventBus.addListener(DynamicPopulationConfig::onReloading);
        modEventBus.addListener(DynamicPopulationConfig::onUnloading);
    }
}
