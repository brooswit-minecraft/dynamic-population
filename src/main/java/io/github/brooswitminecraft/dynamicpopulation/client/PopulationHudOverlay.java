package io.github.brooswitminecraft.dynamicpopulation.client;

import java.util.ArrayList;
import java.util.List;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudPanelLayout;
import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudPanelLayout.Row;
import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudPayload;
import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * MINECRAFT-417: the debug population HUD overlay itself -- compact rows, one per {@link Faction}
 * (Villager/Fillager, Zombie, Skeleton), each a real recognizable mob face icon (blitted directly from the
 * vanilla entity texture already shipped with the game -- the same "blit a known texture region" approach
 * {@code DynamicPopulationClient}'s renderers use to reference entity textures, just sampling one small UV
 * region instead of rendering the whole model) plus that faction's current typed population density for the
 * cell the player stands in, or an explicit "unknown"/"not simulated" label when
 * {@link PopulationHudPayload.Reading#readable()} is false -- NEVER a bare 0 standing in for "we don't know"
 * (ticket AC2).
 *
 * <p>The background panel is sized every frame to fit whatever is about to be drawn -- actual measured text
 * width (via {@link net.minecraft.client.gui.Font#width}) and actual row count, including the conditional
 * "Not simulated here" row -- never a fixed pixel constant that could truncate a long label/status/location
 * string or clip a row the fixed height didn't account for. See {@code PopulationHudPanelLayout}, the pure
 * measurement this defers to.
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
    private static final int ICON_SIZE = 8;
    private static final int ICON_GAP = 4;
    private static final int HORIZONTAL_PADDING = 8;
    private static final int VERTICAL_PADDING = 8;

    /** Vanilla mob textures, reused as-is -- same entity-texture-reference approach as the client renderers. */
    private static final ResourceLocation VILLAGER_TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png");
    private static final ResourceLocation ZOMBIE_TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");
    private static final ResourceLocation SKELETON_TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/entity/skeleton/skeleton.png");

    /** The front-head UV region every one of these vanilla skins shares: an 8x8 box at (8, 8). */
    private static final float FACE_U = 8.0F;
    private static final float FACE_V = 8.0F;

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
        PopulationHudSnapshot snapshot = PopulationHudClientState.cache();

        List<DisplayRow> displayRows = buildDisplayRows(snapshot);
        List<Row> measuredRows = displayRows.stream()
            .map(row -> row.faction() == null ? Row.plain(row.text()) : Row.withIcon(row.text()))
            .toList();
        PopulationHudPanelLayout.Metrics metrics = PopulationHudPanelLayout.measure(measuredRows, mc.font::width,
            ICON_SIZE, ICON_GAP, ROW_HEIGHT, HORIZONTAL_PADDING, VERTICAL_PADDING);

        int screenWidth = graphics.guiWidth();
        int x0 = screenWidth - metrics.width() - MARGIN;
        int y0 = MARGIN;
        graphics.fill(x0 - HORIZONTAL_PADDING / 2, y0 - VERTICAL_PADDING / 2,
            x0 + metrics.width() - HORIZONTAL_PADDING / 2, y0 + metrics.height() - VERTICAL_PADDING / 2, 0x90000000);

        int y = y0;
        for (DisplayRow row : displayRows) {
            if (row.faction() != null) {
                blitFace(graphics, row.faction(), x0, y + (ROW_HEIGHT - ICON_SIZE) / 2);
                graphics.drawString(mc.font, row.text(), x0 + ICON_SIZE + ICON_GAP, y, 0xFFFFFF);
            } else {
                graphics.drawString(mc.font, row.text(), x0, y, row.color());
            }
            y += ROW_HEIGHT;
        }
    }

    /** One row as actually drawn: its text, the color for a plain (non-icon) row, and its faction when iconed. */
    private record DisplayRow(String text, int color, Faction faction) {
        static DisplayRow plain(String text, int color) {
            return new DisplayRow(text, color, null);
        }

        static DisplayRow withIcon(String text, Faction faction) {
            return new DisplayRow(text, 0xFFFFFF, faction);
        }
    }

    /** Builds the exact rows about to be rendered -- the SAME list drives both measurement and drawing. */
    private static List<DisplayRow> buildDisplayRows(PopulationHudSnapshot snapshot) {
        List<DisplayRow> rows = new ArrayList<>();
        if (snapshot == null) {
            rows.add(DisplayRow.plain("Population HUD: waiting...", 0xFFFFFF));
            return rows;
        }
        if (!snapshot.simulated()) {
            rows.add(DisplayRow.plain("Not simulated here", ChatFormatting.GRAY.getColor()));
        }
        for (Faction faction : Faction.values()) {
            PopulationHudPayload.Reading reading = snapshot.readings().get(faction);
            rows.add(DisplayRow.withIcon(faceLabel(faction) + ": " + valueText(reading), faction));
        }
        rows.add(DisplayRow.plain("cell " + snapshot.cellSize() + "b (" + snapshot.cellX() + ","
            + snapshot.cellLayer() + "," + snapshot.cellZ() + ")", ChatFormatting.DARK_GRAY.getColor()));
        return rows;
    }

    private static void blitFace(GuiGraphics graphics, Faction faction, int x, int y) {
        ResourceLocation texture = faceTexture(faction);
        // Vanilla skin width is 64 for every one of these textures; skeleton still ships a legacy 64x32
        // skin, villager/zombie use the modern 64x64 layout -- only the height differs.
        int textureHeight = faction == Faction.SKELETON ? 32 : 64;
        graphics.blit(texture, x, y, FACE_U, FACE_V, ICON_SIZE, ICON_SIZE, 64, textureHeight);
    }

    private static ResourceLocation faceTexture(Faction faction) {
        return switch (faction) {
            case VILLAGER -> VILLAGER_TEXTURE;
            case ZOMBIE -> ZOMBIE_TEXTURE;
            case SKELETON -> SKELETON_TEXTURE;
        };
    }

    private static String valueText(PopulationHudPayload.Reading reading) {
        if (reading == null || !reading.readable()) {
            return "unknown";
        }
        return reading.densityPpt() + " ppt";
    }

    private static String faceLabel(Faction faction) {
        return switch (faction) {
            case VILLAGER -> "Villager";
            case ZOMBIE -> "Zombie";
            case SKELETON -> "Skeleton";
        };
    }
}
