package io.github.brooswitminecraft.dynamicpopulation.hud;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * MINECRAFT-417: pure measurement logic for the debug HUD's background panel size -- no
 * {@code Minecraft}/{@code GuiGraphics}/{@code Font} types at all, so it is directly unit-testable the same
 * way {@link PopulationHudSnapshot#isStale} and {@code PopulationHudTracker#dueThisTick} are. The actual
 * rendering ({@code client.PopulationHudOverlay}) supplies the real text-width function
 * ({@code Font::width}) and the real row list (which varies: a "waiting..." placeholder row, an optional
 * "Not simulated here" row, one row per {@link io.github.brooswitminecraft.dynamicpopulation.faction.Faction},
 * and a trailing cell-coordinates footer row), then sizes the panel to fit exactly what is about to be
 * drawn -- never a fixed constant that can truncate a long label/status/location string or fail to grow for
 * a conditional row.
 */
public final class PopulationHudPanelLayout {
    private PopulationHudPanelLayout() { }

    /** One row of content: its text, and whether a leading icon (faction face swatch) precedes it. */
    public record Row(String text, boolean hasIcon) {
        public static Row plain(String text) {
            return new Row(text, false);
        }

        public static Row withIcon(String text) {
            return new Row(text, true);
        }
    }

    /** The panel's computed background size, in GUI pixels. */
    public record Metrics(int width, int height) {
    }

    /**
     * Measures the panel needed to fit every row without truncation: width is driven by the single widest
     * row (icon width + gap added only for rows that actually carry an icon), height by the row count --
     * so a conditional footer row (e.g. "Not simulated here") that pushes the row count up automatically
     * grows the panel instead of being clipped by a constant.
     *
     * @param rows the rows about to be rendered, top to bottom
     * @param textWidth the real text-width measurer (the overlay passes {@code Font::width})
     * @param iconWidth width reserved for a row's leading icon, when it has one
     * @param iconGap horizontal gap between an icon and its row's text
     * @param rowHeight vertical space one row occupies
     * @param horizontalPadding extra width added beyond the widest row's content (both sides combined)
     * @param verticalPadding extra height added beyond the rows' total height (both sides combined)
     */
    public static Metrics measure(List<Row> rows, ToIntFunction<String> textWidth, int iconWidth, int iconGap,
            int rowHeight, int horizontalPadding, int verticalPadding) {
        int maxRowWidth = 0;
        for (Row row : rows) {
            int width = textWidth.applyAsInt(row.text());
            if (row.hasIcon()) {
                width += iconWidth + iconGap;
            }
            maxRowWidth = Math.max(maxRowWidth, width);
        }
        int width = maxRowWidth + horizontalPadding;
        int height = rows.size() * rowHeight + verticalPadding;
        return new Metrics(width, height);
    }
}
