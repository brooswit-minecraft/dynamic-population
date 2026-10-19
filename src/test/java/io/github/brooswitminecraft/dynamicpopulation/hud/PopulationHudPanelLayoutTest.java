package io.github.brooswitminecraft.dynamicpopulation.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudPanelLayout.Metrics;
import io.github.brooswitminecraft.dynamicpopulation.hud.PopulationHudPanelLayout.Row;
import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-417 review fix: {@link PopulationHudPanelLayout#measure} must size the panel to the ACTUAL
 * rendered content (measured text width, actual row count) instead of a fixed constant that can truncate a
 * long label/status/location string or fail to grow for a conditional footer row -- exercised here with a
 * fake width function (no real {@code Font}/{@code Minecraft} needed) that stands in for
 * {@code Font::width}.
 */
class PopulationHudPanelLayoutTest {
    private static final int ICON_WIDTH = 8;
    private static final int ICON_GAP = 4;
    private static final int ROW_HEIGHT = 12;
    private static final int H_PAD = 8;
    private static final int V_PAD = 8;

    @Test
    void panelGrowsToFitALongLineThatWouldOverflowAFixedWidth() {
        List<Row> shortRows = List.of(Row.plain("short"));
        List<Row> longRows = List.of(Row.plain("a much, much longer status/location line than before"));

        Metrics shortMetrics = measure(shortRows);
        Metrics longMetrics = measure(longRows);

        assertTrue(longMetrics.width() > shortMetrics.width(),
            "a longer line must widen the panel instead of being truncated by a fixed constant");
    }

    @Test
    void panelGrowsTallerForAConditionalFooterRow() {
        List<Row> withoutFooter = List.of(Row.withIcon("Villager: 10 ppt"), Row.withIcon("Zombie: 20 ppt"));
        List<Row> withFooter = List.of(Row.withIcon("Villager: 10 ppt"), Row.withIcon("Zombie: 20 ppt"),
            Row.plain("Not simulated here"));

        Metrics without = measure(withoutFooter);
        Metrics with = measure(withFooter);

        assertEquals(without.height() + ROW_HEIGHT, with.height(),
            "an extra conditional row must grow the panel by exactly one row's height, never clip it");
    }

    @Test
    void anIconRowReservesIconWidthPlusGapBeyondTheTextItself() {
        // Same text, with vs. without a leading icon -- the icon row must measure wider by icon+gap.
        Row plainRow = Row.plain("Villager: 10 ppt");
        Row iconRow = Row.withIcon("Villager: 10 ppt");

        Metrics plainMetrics = measure(List.of(plainRow));
        Metrics iconMetrics = measure(List.of(iconRow));

        assertEquals(plainMetrics.width() + ICON_WIDTH + ICON_GAP, iconMetrics.width());
    }

    @Test
    void widthIsDrivenByTheSingleWidestRowNotTheSum() {
        List<Row> rows = List.of(Row.plain("x"), Row.plain("xx"), Row.plain("xxx"));

        Metrics metrics = measure(rows);

        // fakeWidth below is text.length() * 6 -- widest row is "xxx" (length 3).
        assertEquals(3 * 6 + H_PAD, metrics.width());
    }

    private static Metrics measure(List<Row> rows) {
        return PopulationHudPanelLayout.measure(rows, PopulationHudPanelLayoutTest::fakeWidth,
            ICON_WIDTH, ICON_GAP, ROW_HEIGHT, H_PAD, V_PAD);
    }

    /** Stand-in for {@code Font::width}: deterministic, no real font/Minecraft needed. */
    private static int fakeWidth(String text) {
        return text.length() * 6;
    }
}
