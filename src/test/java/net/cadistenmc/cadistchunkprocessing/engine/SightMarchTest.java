package net.cadistenmc.cadistchunkprocessing.engine;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-function tests for the {@link SightMarch} voxel ray traversal. */
class SightMarchTest {

    /** A finite occupancy grid; everything outside it (or marked solid) is opaque. */
    static final class Grid implements SightMarch.BlockAccess {
        final boolean[][][] solid;
        final int sx, sy, sz;
        Grid(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; solid = new boolean[sx][sy][sz]; }
        void wall(int x, int y, int z) { solid[x][y][z] = true; }
        @Override public boolean transparent(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return false; // edge = opaque
            return !solid[x][y][z];
        }
    }

    static String k(int x, int y, int z) { return x + "," + y + "," + z; }

    static Set<String> collect(Grid g, double ox, double oy, double oz,
                               double dx, double dy, double dz, double maxDist, boolean dilate) {
        Set<String> hits = new HashSet<>();
        SightMarch.cast(g, (x, y, z) -> hits.add(k(x, y, z)), ox, oy, oz, dx, dy, dz, maxDist, dilate);
        return hits;
    }

    @Test
    void axisRay_marksUpToWall_stopsAtIt() {
        Grid g = new Grid(20, 4, 4);
        g.wall(5, 0, 0);                                   // wall at x=5
        Set<String> hits = collect(g, 0.5, 0.5, 0.5, 1, 0, 0, 16, false);
        for (int x = 0; x <= 4; x++) assertTrue(hits.contains(k(x, 0, 0)), "should see x=" + x);
        assertFalse(hits.contains(k(5, 0, 0)), "must not mark the occluding wall");
        assertFalse(hits.contains(k(6, 0, 0)), "must not see past the wall");
    }

    @Test
    void distanceCap_limitsTraversal() {
        Grid g = new Grid(40, 4, 4);                       // no walls
        Set<String> hits = collect(g, 0.5, 0.5, 0.5, 1, 0, 0, 6, false);
        assertTrue(hits.contains(k(6, 0, 0)), "within the cap is seen");
        assertFalse(hits.contains(k(8, 0, 0)), "beyond the distance cap is not seen");
    }

    @Test
    void edgeOfWorld_actsAsOpaqueStop() {
        Grid g = new Grid(8, 4, 4);
        Set<String> hits = collect(g, 0.5, 0.5, 0.5, 1, 0, 0, 64, false);
        assertTrue(hits.contains(k(7, 0, 0)), "last in-bounds cell is seen");
        assertFalse(hits.contains(k(8, 0, 0)), "out-of-world is opaque, never marked");
    }

    @Test
    void eyeInSolid_marksNothing() {
        Grid g = new Grid(8, 4, 4);
        g.wall(0, 0, 0);
        Set<String> hits = collect(g, 0.5, 0.5, 0.5, 1, 0, 0, 16, false);
        assertTrue(hits.isEmpty(), "an eye embedded in solid sees nothing");
    }

    @Test
    void diagonalRay_advancesOnAllAxes() {
        Grid g = new Grid(20, 20, 20);
        Set<String> hits = collect(g, 0.5, 0.5, 0.5, 1, 1, 1, 20, false);
        assertTrue(hits.contains(k(0, 0, 0)), "origin marked");
        assertTrue(hits.contains(k(3, 3, 3)), "diagonal progression marked");
    }

    @Test
    void dilation_marksTransparentNeighbours() {
        Grid g = new Grid(20, 6, 6);
        Set<String> plain = collect(g, 0.5, 2.5, 2.5, 1, 0, 0, 8, false);
        Set<String> dilated = collect(g, 0.5, 2.5, 2.5, 1, 0, 0, 8, true);
        assertTrue(dilated.size() > plain.size(), "dilation should add neighbour cells");
        assertTrue(dilated.contains(k(3, 3, 2)), "a transparent vertical neighbour is marked under dilation");
    }
}
