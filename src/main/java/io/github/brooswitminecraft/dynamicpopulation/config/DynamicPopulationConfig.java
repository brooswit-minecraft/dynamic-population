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

    public record Snapshot(int cellSize, int configVersion) { }

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
    private static volatile Snapshot snapshot = new Snapshot(CELL_SIZE.getDefault(), CONFIG_VERSION.getDefault());

    private static boolean isValidCellSize(Object value) {
        return value instanceof Integer i && i >= 16 && i <= 1024 && i % 16 == 0;
    }

    public static Snapshot get() {
        return snapshot;
    }

    private static Snapshot readSnapshot() {
        return new Snapshot(CELL_SIZE.get(), CONFIG_VERSION.get());
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
            snapshot = new Snapshot(CELL_SIZE.getDefault(), CONFIG_VERSION.getDefault());
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
