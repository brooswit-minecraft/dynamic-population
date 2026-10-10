package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.ArrayList;
import java.util.List;

import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;
import io.github.brooswitminecraft.dynamicpopulation.propagation.Propagation;

/**
 * Pure seeding logic (AC4): the King raises the field's target density in cells within a horizontal radius of
 * itself, at a configurable rate, directly against {@link Propagation.Field} -- the exact storage contract
 * {@code Propagation} itself steps against (DPOP-4's cell field storage API, reached through the same
 * {@code LevelField} glue in real use), so this never bypasses {@link Propagation#CAPACITY} or traversability
 * with a second, parallel write path.
 *
 * <p>Vertical: only the King's own layer is seeded (dz/dy = 0) -- it raises population on the surface layer it
 * stands on, not through solid rock above/below it.
 */
public final class KingSeeding {
    private KingSeeding() { }

    /** Every cell within {@code radiusCells} (Chebyshev, horizontal only) of {@code king}, king's own layer included. */
    public static List<CellKey> cellsInRange(CellKey king, int radiusCells) {
        List<CellKey> cells = new ArrayList<>();
        for (int dx = -radiusCells; dx <= radiusCells; dx++) {
            for (int dz = -radiusCells; dz <= radiusCells; dz++) {
                cells.add(king.offset(dx, 0, dz));
            }
        }
        return cells;
    }

    /** Raise every traversable cell within range by {@code ratePpt}, never exceeding {@link Propagation#CAPACITY}. */
    public static void seed(Propagation.Field field, CellKey king, int radiusCells, int ratePpt) {
        for (CellKey cell : cellsInRange(king, radiusCells)) {
            if (!field.traversable(cell)) {
                continue;
            }
            int next = Math.min(Propagation.CAPACITY, field.get(cell) + ratePpt);
            field.set(cell, next);
        }
    }
}
