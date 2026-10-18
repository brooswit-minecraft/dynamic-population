package io.github.brooswitminecraft.dynamicpopulation.config;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

import com.electronwill.nightconfig.core.CommentedConfig;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * Drives the live {@link DynamicPopulationConfig#SPEC} through a real FML load/reload cycle in tests,
 * without booting a server: an in-memory backing file is bound to the spec exactly as
 * {@code ConfigTracker} binds an on-disk one, then {@link DynamicPopulationConfig#onLoading}/
 * {@link DynamicPopulationConfig#onReloading} fire against it. This is what makes a "config change is
 * observed by {@link DynamicPopulationConfig#get()} live, without a restart" test exercise the actual
 * reload path instead of a hand-rolled {@code Snapshot} or {@code Params} record.
 *
 * <p>{@code ModConfig} and {@code LoadedConfig} (the only permitted implementation of the sealed
 * {@code IConfigSpec.ILoadedConfig}) are both package-private to FML; reflection is the only way to
 * obtain instances from a test. Both constructors only assign their fields (verified by disassembly),
 * so a null {@link ModContainer} and a non-existent dummy {@link Path} are safe here: nothing on this
 * path calls {@code getModId()} or touches the path on disk.
 */
public final class DynamicPopulationConfigTestSupport {
    private static CommentedConfig backing;

    private DynamicPopulationConfigTestSupport() { }

    /** Binds the live spec to an in-memory config, as {@code ConfigTracker} does on server start. */
    public static synchronized void load() {
        if (backing != null) {
            return;
        }
        backing = CommentedConfig.inMemory();
        DynamicPopulationConfig.SPEC.correct(backing);
        DynamicPopulationConfig.SPEC.acceptConfig(fakeLoadedConfig(backing));
        DynamicPopulationConfig.onLoading(new ModConfigEvent.Loading(fakeModConfig()));
    }

    /** Sets one key in the live backing config and fires Reloading, exactly like an on-disk edit. */
    public static synchronized void reload(String key, Object value) {
        load();
        backing.set(java.util.List.of(key), value);
        // ConfigTracker's file watcher clears each NONE-restart ConfigValue's own cache before firing
        // Reloading; without this, ConfigValue.get() below (via readSnapshot()) would keep returning the
        // value it cached on the very first get(), masking a real "still stale after reload" regression.
        DynamicPopulationConfig.SPEC.afterReload();
        DynamicPopulationConfig.onReloading(new ModConfigEvent.Reloading(fakeModConfig()));
    }

    private static ModConfig fakeModConfig() {
        try {
            Constructor<ModConfig> ctor = ModConfig.class.getDeclaredConstructor(ModConfig.Type.class,
                IConfigSpec.class, ModContainer.class, String.class, ReentrantLock.class);
            ctor.setAccessible(true);
            return ctor.newInstance(ModConfig.Type.SERVER, DynamicPopulationConfig.SPEC, null,
                DynamicPopulationConfig.FILE_NAME, new ReentrantLock());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not build a test ModConfig bound to the live SPEC", e);
        }
    }

    private static IConfigSpec.ILoadedConfig fakeLoadedConfig(CommentedConfig config) {
        try {
            Class<?> loadedConfigClass = Class.forName("net.neoforged.fml.config.LoadedConfig");
            Constructor<?> ctor = loadedConfigClass.getDeclaredConstructor(CommentedConfig.class, Path.class,
                ModConfig.class);
            ctor.setAccessible(true);
            return (IConfigSpec.ILoadedConfig) ctor.newInstance(config,
                Path.of("DynamicPopulationConfigTestSupport-in-memory.toml"), fakeModConfig());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not build a test LoadedConfig bound to the live SPEC", e);
        }
    }
}
