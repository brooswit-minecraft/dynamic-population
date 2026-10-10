package io.github.brooswitminecraft.dynamicpopulation.king;

/**
 * Pure uniqueness logic (AC4): a King's "relevant loaded area" is a horizontal radius, in cells (the same
 * {@code cell_size} the field uses), around each existing King. Vertical layer is deliberately ignored -- a
 * King's territory is a horizontal surface region, not a vertical column -- so uniqueness compares only cell
 * X/Z, via Chebyshev distance (a square radius, matching how {@link KingSeeding}'s range is shaped too).
 */
public final class KingUniqueness {
    private KingUniqueness() { }

    /** A King's horizontal cell coordinate (no layer -- see class doc). */
    public record CellCoord(int x, int z) { }

    /** Whether a candidate King may spawn/persist at {@code candidate} given the existing Kings' cell coordinates. */
    public static boolean allowed(CellCoord candidate, Iterable<CellCoord> existing, int radiusCells) {
        for (CellCoord other : existing) {
            if (Math.max(Math.abs(candidate.x() - other.x()), Math.abs(candidate.z() - other.z())) <= radiusCells) {
                return false;
            }
        }
        return true;
    }
}
