package io.github.brooswitminecraft.dynamicpopulation.client;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudPayload;
import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * MINECRAFT-417: the debug population HUD overlay itself -- three compact rows, one per
 * {@link Faction} (Villager/Fillager, Zombie, Skeleton), each a small recognizable colored face swatch plus
 * that faction's current typed population density for the cell the player stands in, or an explicit
 * "unknown"/"not simulated" label when {@link PopulationHudPayload.Reading#readable()} is false -- NEVER a
 * bare 0 standing in for "we don't know" (ticket AC2).
 *
 * <p>Deliberately placed in the top-right corner, away from the top-left/center areas a typical mission HUD
 * occupies, so the two never physically overlap on screen -- see the PR description for the explicit caveat
 * that this spacing has not been eyeballed against the real mission HUD in a running client, only reasoned
 * about from the coordinates.
 *
 * <p>{@code Dist.CLIENT} only: {@link GuiGraphics}/{@link Minecraft} are both client-only types, so this
 * class, like {@code DynamicPopulationClient}, is never loaded on a dedicated server.
 */
@EventBusSubscriber(modid = DynamicPopulationMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PopulationHudOverlay {
    private static final int MARGIN = 6;
    private static final int ROW_HEIGHT = 12;
    private static final int SWATCH_SIZE = 8;
    private static final int PANEL_WIDTH = 92;

    private PopulationHudOverlay() { }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!PopulationHudClientState.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int screenWidth = graphics.guiWidth();
        int x0 = screenWidth - PANEL_WIDTH - MARGIN;
        int y0 = MARGIN;

        PopulationHudSnapshot snapshot = PopulationHudClientState.cache();
        int panelHeight = ROW_HEIGHT * Faction.values().length + ROW_HEIGHT / 2;
        graphics.fill(x0 - 4, y0 - 4, x0 + PANEL_WIDTH, y0 + panelHeight, 0x90000000);

        if (snapshot == null) {
            graphics.drawString(mc.font, "Population HUD: waiting...", x0, y0, 0xFFFFFF);
            return;
        }

        int y = y0;
        if (!snapshot.simulated()) {
            graphics.drawString(mc.font, "Not simulated here", x0, y, ChatFormatting.GRAY.getColor());
            y += ROW_HEIGHT;
        }
        for (Faction faction : Faction.values()) {
            PopulationHudPayload.Reading reading = snapshot.readings().get(faction);
            graphics.fill(x0, y + 1, x0 + SWATCH_SIZE, y + 1 + SWATCH_SIZE, faceColor(faction));
            String label = faceLabel(faction) + ": " + valueText(reading);
            graphics.drawString(mc.font, label, x0 + SWATCH_SIZE + 4, y, 0xFFFFFF);
            y += ROW_HEIGHT;
        }
        graphics.drawString(mc.font, "cell " + snapshot.cellSize() + "b (" + snapshot.cellX() + ","
            + snapshot.cellLayer() + "," + snapshot.cellZ() + ")", x0, y, ChatFormatting.DARK_GRAY.getColor());
    }

    private static String valueText(PopulationHudPayload.Reading reading) {
        if (reading == null || !reading.readable()) {
            return "unknown";
        }
        return reading.densityPpt() + " ppt";
    }

    /** Recognizable-at-a-glance single letter standing in for a face icon; see the overlay's own class doc. */
    private static String faceLabel(Faction faction) {
        return switch (faction) {
            case VILLAGER -> "Villager";
            case ZOMBIE -> "Zombie";
            case SKELETON -> "Skeleton";
        };
    }

    private static int faceColor(Faction faction) {
        return switch (faction) {
            case VILLAGER -> 0xFFCB8D5B; // Villager robe brown.
            case ZOMBIE -> 0xFF4E8544; // Zombie skin green.
            case SKELETON -> 0xFFC6C6C6; // Skeleton bone white/grey.
        };
    }
}
