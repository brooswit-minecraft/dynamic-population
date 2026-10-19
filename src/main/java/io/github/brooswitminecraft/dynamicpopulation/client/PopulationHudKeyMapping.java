package io.github.brooswitminecraft.dynamicpopulation.client;

import com.mojang.blaze3d.platform.InputConstants;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * MINECRAFT-417: the toggle for the debug population HUD (ticket AC: "Provide a toggle (e.g. a keybind or
 * client command)"). A keybind, remappable like any other ({@code key.dynamicpopulation.toggle_hud} /
 * {@code key.categories.misc}, defaulting to G -- chosen only because it is unbound by vanilla; a resource
 * pack/user can remap it like any other keybind if that ever collides). The actual per-tick
 * {@code consumeClick()} polling lives in {@link PopulationHudTickHandler} (a game-bus event, unlike this
 * mod-bus one-time registration).
 *
 * <p>{@code Dist.CLIENT} only, same pattern as {@code DynamicPopulationClient}: {@link KeyMapping} is a
 * client-only concept, never relevant on a dedicated server.
 */
@EventBusSubscriber(modid = DynamicPopulationMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PopulationHudKeyMapping {
    static final KeyMapping TOGGLE_HUD = new KeyMapping("key.dynamicpopulation.toggle_hud",
        InputConstants.Type.KEYSYM, InputConstants.KEY_G, KeyMapping.CATEGORY_MISC);

    private PopulationHudKeyMapping() { }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_HUD);
    }
}
