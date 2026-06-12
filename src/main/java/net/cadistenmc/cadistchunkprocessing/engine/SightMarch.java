package net.cadistenmc.cadistchunkprocessing.engine;

/**
 * Pure voxel ray traversal (Amanatides &amp; Woo DDA) used to mark the cells a
 * player has actually <em>seen</em> for the fog-of-war layer. No Bukkit, no
 * PacketEvents — it walks an abstract {@link BlockAccess} and reports each
 * transparent cell a ray passes through to a {@link Visitor}, stopping at the
 * first occluding block, the distance cap, or the edge of the known world.
 *
 * <p>Deterministic and fully unit-testable. The Bukkit-side
 * {@code ExploredSetService} supplies a {@link BlockAccess} backed by chunk
 * snapshots and accumulates the visited cells into the explored bitset.
 */
public final class SightMarch {

    /** Abstract read of world geometry in absolute block coordinates. */
    public interface BlockAccess {
        /**
         * True if sight passes through this cell (air, glass, water, leaves, …) —
         * i.e. it does not occlude a full block. Cells outside the loaded/known
         * world must read as occluding (return false) so a ray never marks past
         * the edge of what the server actually knows.
         */
        boolean transparent(int x, int y, int z);
    }

    /** Receives every transparent cell a ray traverses (absolute coords). */
    public interface Visitor {
        void mark(int x, int y, int z);
    }

    private SightMarch() {}

    /**
     * March one ray from {@code (ox,oy,oz)} (the eye) along the unit-ish direction
     * {@code (dx,dy,dz)} for up to {@code maxDist} blocks. Every transparent cell
     * the ray enters is reported to {@code visitor}; traversal stops at the first
     * occluding cell (that cell is not marked — you can't see through it, only up
     * to it) or once {@code maxDist} is exceeded. The origin cell is marked if it
     * is itself transparent.
     *
     * @param dilate also mark the 6 face-neighbours of each visited cell when they
     *               are transparent (fills single-cell slivers between rays); set
     *               false for exact traversal (tests).
     */
    public static void cast(BlockAccess world, Visitor visitor,
                            double ox, double oy, double oz,
                            double dx, double dy, double dz,
                            double maxDist, boolean dilate) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9 || maxDist <= 0) return;
        dx /= len; dy /= len; dz /= len;

        int x = floor(ox), y = floor(oy), z = floor(oz);

        int stepX = sign(dx), stepY = sign(dy), stepZ = sign(dz);
        double tMaxX = boundary(ox, dx, stepX);
        double tMaxY = boundary(oy, dy, stepY);
        double tMaxZ = boundary(oz, dz, stepZ);
        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

        // The eye cell itself.
        if (world.transparent(x, y, z)) {
            visitor.mark(x, y, z);
        } else {
            return; // eye embedded in solid — nothing to see
        }

        double travelled = 0.0;
        // Bound the loop hard against pathological inputs.
        int guard = (int) (maxDist * 3) + 8;
        while (guard-- > 0) {
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX; travelled = tMaxX; tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY; travelled = tMaxY; tMaxY += tDeltaY;
            } else {
                z += stepZ; travelled = tMaxZ; tMaxZ += tDeltaZ;
            }
            if (travelled > maxDist) return;
            if (!world.transparent(x, y, z)) return;   // hit a wall — stop at it, don't mark it
            visitor.mark(x, y, z);
            if (dilate) {
                markIfClear(world, visitor, x - 1, y, z);
                markIfClear(world, visitor, x + 1, y, z);
                markIfClear(world, visitor, x, y - 1, z);
                markIfClear(world, visitor, x, y + 1, z);
                markIfClear(world, visitor, x, y, z - 1);
                markIfClear(world, visitor, x, y, z + 1);
            }
        }
    }

    private static void markIfClear(BlockAccess world, Visitor v, int x, int y, int z) {
        if (world.transparent(x, y, z)) v.mark(x, y, z);
    }

    private static int floor(double v) {
        int i = (int) v;
        return (v < i) ? i - 1 : i;
    }

    private static int sign(double v) {
        return v > 0 ? 1 : (v < 0 ? -1 : 0);
    }

    /** Distance (in units of t) from the origin to the first voxel boundary along this axis. */
    private static double boundary(double origin, double dir, int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double cell = floor(origin);
        double next = step > 0 ? cell + 1.0 : cell;
        return (next - origin) / dir;
    }
}
