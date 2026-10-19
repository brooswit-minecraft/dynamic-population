package io.github.brooswitminecraft.dynamicpopulation.king;

import java.util.function.BiPredicate;

/**
 * Pure placement-footprint math (MINECRAFT-414 review fix, [P1]): every King entity's REAL physical size is
 * its base {@code EntityType} dimensions (0.9x2.9 for all three factions) further multiplied by the 1.5
 * {@code Attributes.SCALE} attribute every King is registered with (see
 * {@code DynamicPopulationEntities#KING_VISUAL_SCALE}) -- about 1.35 wide, 4.35 tall, NOT the base 0.9x2.9
 * a naive check against only the unscaled {@code EntityType} size would use. {@code Entity#getDimensions}
 * on a real, constructed King instance already folds the scale attribute in (it is a {@code LivingEntity}
 * override), so the glue ({@code KingNaturalFounding}) reads it off one real probe instance and passes the
 * resulting width/height here -- this class never hardcodes either number, so it stays correct even if a
 * future change alters either the base size or the scale factor.
 *
 * <p>No Minecraft classes: this is the footprint arithmetic and the generic "every cell in the footprint
 * passes some caller-supplied check" composition, directly unit-testable; sampling real block/fluid/chunk
 * state at those cells is the Minecraft-facing glue's job.
 */
public final class KingPlacement {
    private KingPlacement() { }

    /** The exact integer block range (inclusive) a King's real AABB, centered on {@code (centerX, centerZ)}, spans. */
    public record Footprint(int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ, int heightBlocks) { }

    /**
     * Compute the footprint for a King of real {@code width}/{@code height} (already scale-inclusive)
     * centered on block column {@code (centerX, centerZ)}. A tiny epsilon is subtracted before flooring
     * the max bound so a body edge landing EXACTLY on a block boundary (e.g. an integer-aligned half-width)
     * does not spuriously claim one extra block it does not actually occupy.
     */
    public static Footprint footprint(int centerX, int centerZ, double width, double height) {
        double halfWidth = width / 2.0;
        double minX = centerX + 0.5 - halfWidth;
        double maxX = centerX + 0.5 + halfWidth;
        double minZ = centerZ + 0.5 - halfWidth;
        double maxZ = centerZ + 0.5 + halfWidth;
        int minBlockX = (int) Math.floor(minX);
        int maxBlockX = (int) Math.floor(maxX - 1e-7);
        int minBlockZ = (int) Math.floor(minZ);
        int maxBlockZ = (int) Math.floor(maxZ - 1e-7);
        int heightBlocks = (int) Math.ceil(height);
        return new Footprint(minBlockX, maxBlockX, minBlockZ, maxBlockZ, heightBlocks);
    }

    /**
     * Whether every (x, z) column the footprint's horizontal extent touches satisfies {@code predicate} --
     * used for both "every column's chunk is loaded" (never load a neighbor to validate: a chunk edge
     * where the scaled body straddles two chunks must see BOTH checked) and "every column at some fixed Y
     * is fluid-free" (an adjacent-water corner the OLD single-center-column check could miss).
     */
    public static boolean everyColumn(Footprint footprint, BiPredicate<Integer, Integer> predicate) {
        for (int x = footprint.minBlockX(); x <= footprint.maxBlockX(); x++) {
            for (int z = footprint.minBlockZ(); z <= footprint.maxBlockZ(); z++) {
                if (!predicate.test(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Every DISTINCT chunk (chunk coordinates, not block coordinates) the footprint's horizontal extent touches. */
    public static boolean everyChunk(Footprint footprint, BiPredicate<Integer, Integer> chunkPredicate) {
        int minChunkX = footprint.minBlockX() >> 4;
        int maxChunkX = footprint.maxBlockX() >> 4;
        int minChunkZ = footprint.minBlockZ() >> 4;
        int maxChunkZ = footprint.maxBlockZ() >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!chunkPredicate.test(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether a King of this {@code heightBlocks} standing with feet at {@code feetY} fits within world
     * build-height bounds: the support block below its feet must itself be loadable/placeable ground
     * ({@code feetY - 1 >= minBuildHeight}), and its full real (scaled) height must not poke through the
     * world ceiling ({@code feetY + heightBlocks - 1 < maxBuildHeight}) -- using the REAL height, not a
     * fixed guess, is what catches a roof that the old hardcoded-height check could miss.
     */
    public static boolean withinBuildHeight(int feetY, int heightBlocks, int minBuildHeight, int maxBuildHeight) {
        return feetY - 1 >= minBuildHeight && feetY + heightBlocks - 1 < maxBuildHeight;
    }
}
