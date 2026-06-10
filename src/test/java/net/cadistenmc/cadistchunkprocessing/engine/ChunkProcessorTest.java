package net.cadistenmc.cadistchunkprocessing.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-function invariant tests for {@link ChunkProcessor}. No server required.
 * These pin the contract that makes the whole "solidify, never void" model safe.
 */
class ChunkProcessorTest {

    // Synthetic block ids.
    static final int AIR = 0, STONE = 1, DEEPSLATE = 2, ORE = 3, WATER = 4, DIRT = 5, GLASS = 6;
    // Anti-base-finder fixtures: a solid man-made block and a transparent one (a ladder).
    static final int BRICKS = 20, LADDER = 21, GRASS = 7;

    static final BlockClassifier CLF = new BlockClassifier() {
        @Override public boolean isTransparent(int id) { return id == AIR || id == WATER || id == GLASS || id == LADDER; }
        @Override public boolean isOre(int id) { return id == ORE; }
        @Override public boolean isTerrain(int id) {
            return id == STONE || id == DEEPSLATE || id == DIRT || id == 7 || id == 8 || id == 9 || id == 10;
        }
        @Override public boolean isArtificial(int id) { return id == BRICKS || id == LADDER; }
        @Override public boolean isFluid(int id) { return id == WATER; }
    };

    static final ModeParams P = new ModeParams(4, 10, 4, 8, true, 2);

    static int idx(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    /** A column of given height, all STONE up to {@code surface}, AIR above. */
    static int[] flatWorld(int ySize, int surface) {
        int[] b = new int[ySize << 8];
        for (int y = 0; y <= surface; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    b[idx(x, y, z)] = STONE;
        return b;
    }

    /** Build the standard "tunnel with a vertical opening" fixture used below. */
    static int[] tunnelFixture(int ySize) {
        int[] b = flatWorld(ySize, 40);
        // horizontal tunnel at y=20, z=8, x=2..13
        for (int x = 2; x <= 13; x++) b[idx(x, 20, 8)] = AIR;
        // vertical shaft to the sky at x=2 (the opening): y=20.. top
        for (int y = 20; y < ySize; y++) b[idx(2, y, 8)] = AIR;
        return b;
    }

    private static ChunkProcessor proc() { return new ChunkProcessor(CLF); }

    // ---- master invariant: never create transparency (no void) ----

    @Test
    void neverCreatesTransparency_shell_and_deep() {
        for (Tier tier : new Tier[]{Tier.SHELL, Tier.DEEP}) {
            int ySize = 64;
            int[] in = tunnelFixture(ySize);
            // sprinkle ores + a flooded pocket
            in[idx(5, 5, 5)] = ORE;
            in[idx(6, 20, 8)] = WATER; // sits in the tunnel
            in[idx(9, 10, 9)] = ORE;
            int[] out = in.clone();
            proc().process(out, ySize, 0, tier, P, true, STONE, DEEPSLATE, null);
            for (int i = 0; i < in.length; i++) {
                if (CLF.isTransparent(out[i])) {
                    assertTrue(CLF.isTransparent(in[i]),
                            "tier " + tier + " created transparency at idx " + i
                                    + " (in=" + in[i] + " out=" + out[i] + ")");
                }
            }
        }
    }

    // ---- surface and everything above it is untouched ----

    @Test
    void surfaceAndSkyUntouched() {
        int ySize = 64;
        int[] in = tunnelFixture(ySize);
        int[] out = in.clone();
        proc().process(out, ySize, 0, Tier.DEEP, P, true, STONE, DEEPSLATE, null);
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                if (x == 2 && z == 8) continue; // the opening column legitimately differs? no — it's exterior, also untouched
                for (int y = 40; y < ySize; y++) {
                    assertEquals(in[idx(x, y, z)], out[idx(x, y, z)],
                            "surface/sky changed at " + x + "," + y + "," + z);
                }
            }
    }

    // ---- SHELL reveals near openings, solidifies deep; DEEP hides all ----

    @Test
    void shellRevealsNearOpening_solidifiesFar() {
        int ySize = 64;
        int[] out = tunnelFixture(ySize);
        proc().process(out, ySize, 0, Tier.SHELL, P, false, STONE, DEEPSLATE, null);
        // x=3..7 are within shell depth 4 of the opening -> still air
        assertEquals(AIR, out[idx(5, 20, 8)], "near-opening cave should stay revealed");
        assertEquals(AIR, out[idx(7, 20, 8)], "cave at shell-depth edge should stay revealed");
        // x=8..13 are beyond shell depth -> solid
        assertEquals(STONE, out[idx(10, 20, 8)], "far cave should be solidified");
        assertEquals(STONE, out[idx(13, 20, 8)], "far cave should be solidified");
        // the open shaft (exterior, above its own surface) is untouched
        assertEquals(AIR, out[idx(2, 30, 8)], "open shaft must remain air");
    }

    @Test
    void deepHidesAllCaves() {
        int ySize = 64;
        int[] out = tunnelFixture(ySize);
        proc().process(out, ySize, 0, Tier.DEEP, P, false, STONE, DEEPSLATE, null);
        for (int x = 3; x <= 13; x++)
            assertEquals(STONE, out[idx(x, 20, 8)], "DEEP must solidify all cave air at x=" + x);
    }

    // ---- anti-xray ore camouflage ----

    @Test
    void buriedOreHidden_exposedOreKept_realTier() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        out[idx(5, 10, 5)] = ORE;          // fully buried in stone
        out[idx(5, 25, 5)] = AIR;          // a little cave pocket (real tier keeps it)
        out[idx(6, 25, 5)] = ORE;          // exposed to that pocket
        proc().process(out, ySize, 0, Tier.REAL, P, true, STONE, DEEPSLATE, null);
        assertEquals(STONE, out[idx(5, 10, 5)], "buried ore must be camouflaged");
        assertEquals(ORE, out[idx(6, 25, 5)], "ore exposed to real cave air must be kept");
        assertEquals(AIR, out[idx(5, 25, 5)], "REAL tier must not solidify cave air");
    }

    @Test
    void deepslateUsedBelowZero() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        out[idx(5, 10, 5)] = ORE;          // worldY = -64 + 10 = -54 -> deepslate
        proc().process(out, ySize, -64, Tier.REAL, P, true, STONE, DEEPSLATE, null);
        assertEquals(DEEPSLATE, out[idx(5, 10, 5)], "buried ore below y=0 must become deepslate");
    }

    // ---- DEEP + rock-collapse yields single-palette deep sections ----

    @Test
    void homogenizeCollapsesDeepSectionToSinglePalette() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        // varied junk in section 0 (y 0..15), all well below the cutoff (40-8=32)
        out[idx(1, 3, 1)] = DIRT;
        out[idx(2, 4, 2)] = ORE;
        out[idx(3, 5, 3)] = AIR;
        out[idx(4, 6, 4)] = GLASS;
        proc().process(out, ySize, 0, Tier.DEEP, P, true, STONE, DEEPSLATE, null);
        // section 0 must now be a single block-state (all STONE, since worldY>=0)
        for (int i = 0; i < 4096; i++)
            assertEquals(STONE, out[i], "deep homogenised section must be uniform at idx " + i);
    }

    @Test
    void homogenizeLeavesSurfaceSectionAlone() {
        int ySize = 64;
        int[] in = flatWorld(ySize, 40);
        int[] out = in.clone();
        proc().process(out, ySize, 0, Tier.DEEP, P, false, STONE, DEEPSLATE, null);
        // section 2 spans y32..47 and contains the surface (40) -> must not be homogenised
        // the solid stone column at y=40 stays stone, air at y=45 stays air
        assertEquals(STONE, out[idx(0, 40, 0)]);
        assertEquals(AIR, out[idx(0, 45, 0)]);
    }

    // ---- byte model reflects palette collapse ----

    @Test
    void estimateBytes_singlePaletteMuchSmallerThanVaried() {
        int ySize = 16;
        ChunkProcessor p = proc();
        int[] uniform = new int[ySize << 8];        // all AIR -> single palette
        long uniformBytes = p.estimateBytes(uniform, ySize);

        int[] varied = new int[ySize << 8];
        for (int i = 0; i < varied.length; i++) varied[i] = i % 300; // many distinct -> direct palette
        long variedBytes = p.estimateBytes(varied, ySize);

        assertTrue(variedBytes > uniformBytes * 10,
                "varied section (" + variedBytes + ") should dwarf uniform (" + uniformBytes + ")");
    }

    @Test
    void realTierNoOreCamoIsIdentity() {
        int ySize = 64;
        int[] in = tunnelFixture(ySize);
        in[idx(5, 10, 5)] = ORE;
        int[] out = in.clone();
        ChunkProcessor.Result r = proc().process(out, ySize, 0, Tier.REAL, P, false, STONE, DEEPSLATE, null);
        assertArrayEquals(in, out, "REAL tier with no ore-camo must be a no-op");
        assertFalse(r.modified);
    }

    @Test
    void shellRevealsFromNeighbourOpening() {
        int ySize = 64;
        // tunnel along x=0..5 at y=20,z=8, with NO opening of its own.
        int[] base = flatWorld(ySize, 40);
        for (int x = 0; x <= 5; x++) base[idx(x, 20, 8)] = AIR;

        // Without neighbour info, a closed cave is fully solidified.
        int[] closed = base.clone();
        proc().process(closed, ySize, 0, Tier.SHELL, P, false, STONE, DEEPSLATE, null);
        assertEquals(STONE, closed[idx(0, 20, 8)], "closed cave with no opening must be solidified");

        // With a west-neighbour opening at (y=20,z=8), the cave reveals inward.
        BorderSeed seed = new BorderSeed() {
            @Override public boolean openingWest(int y, int z) { return y == 20 && z == 8; }
            @Override public boolean openingEast(int y, int z) { return false; }
            @Override public boolean openingNorth(int y, int x) { return false; }
            @Override public boolean openingSouth(int y, int x) { return false; }
        };
        int[] seeded = base.clone();
        proc().process(seeded, ySize, 0, Tier.SHELL, P, false, STONE, DEEPSLATE, seed);
        assertEquals(AIR, seeded[idx(0, 20, 8)], "cave at neighbour opening must reveal");
        assertEquals(AIR, seeded[idx(4, 20, 8)], "cave within shell depth must reveal");
        assertEquals(STONE, seeded[idx(5, 20, 8)], "cave beyond shell depth must solidify");
    }

    @Test
    void collapseIsPerColumn_ravineDoesNotBlockChunk() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        // A ravine column open to the sky at (0,0): surface drops to ~y5.
        for (int y = 6; y < ySize; y++) out[idx(0, y, 0)] = AIR;
        // A normal column with a buried ore + cave deep down.
        out[idx(8, 10, 8)] = ORE;
        out[idx(8, 12, 8)] = AIR;

        ModeParams mp = new ModeParams(2, 6, 3, 8, true, 2); // margin 8
        proc().process(out, ySize, 0, Tier.DEEP, mp, true, STONE, DEEPSLATE, null);

        assertEquals(STONE, out[idx(8, 10, 8)], "deep block must collapse despite a ravine elsewhere in the chunk");
        assertEquals(STONE, out[idx(8, 12, 8)], "deep cave in a normal column must be solid");
        assertEquals(AIR, out[idx(0, 30, 0)], "an open ravine stays visible (above its own low surface)");
    }

    @Test
    void deepCollapseGivesLargeByteSavings() {
        int ySize = 64;
        int[] b = new int[ySize << 8];
        int[] varied = {STONE, DEEPSLATE, DIRT, ORE, GLASS, WATER, 7, 8, 9, 10};
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = 0; y <= 40; y++)
                    b[idx(x, y, z)] = (y == 40) ? STONE : varied[(x + y + z) % varied.length];

        ChunkProcessor p = proc();
        ModeParams mp = new ModeParams(2, 2, 0, 2, true, 2); // Max-Savings-like
        ChunkProcessor.Result r = p.process(b, ySize, 0, Tier.DEEP, mp, true, STONE, DEEPSLATE, null);

        assertTrue(r.bytesBefore > 0);
        assertTrue(r.bytesAfter * 2 < r.bytesBefore,
                "DEEP collapse should cut wire bytes >50% (before=" + r.bytesBefore + " after=" + r.bytesAfter + ")");
    }

    @Test
    void measureRealisticDeepSavings() {
        int ySize = 384;          // -64..320 overworld
        int minY = -64;
        int[] b = new int[ySize << 8];
        int[] rock = {7, 8, 9, 10};
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = 0; y <= 128; y++) {     // surface at local y=128 (worldY 64)
                    int v = (y < 64) ? DEEPSLATE : STONE;
                    if ((x * 31 + y * 17 + z * 13) % 7 == 0) v = rock[(x + y + z) & 3];
                    if ((x * 7 + y * 5 + z * 3) % 23 == 0) v = ORE;
                    if ((x * 11 + y * 9 + z * 5) % 29 < 3) v = AIR;   // cave pockets
                    if (y >= 125) v = DIRT;                           // surface skin
                    b[idx(x, y, z)] = v;
                }
        ChunkProcessor p = proc();
        ModeParams mp = new ModeParams(2, 2, 0, 2, true, 2);
        ChunkProcessor.Result r = p.process(b, ySize, minY, Tier.DEEP, mp, true, STONE, DEEPSLATE, null);
        double savings = (r.bytesBefore - r.bytesAfter) * 100.0 / r.bytesBefore;
        System.out.printf("[MEASURE] realistic DEEP chunk: before=%d after=%d savings=%.1f%%%n",
                r.bytesBefore, r.bytesAfter, savings);
        assertTrue(savings > 75.0, "expected >75% block savings on a deep chunk, got " + savings);
    }

    @Test
    void hideAllOresCamouflagesExposedToo() {
        int ySize = 64;
        int[] base = flatWorld(ySize, 40);
        base[idx(5, 25, 5)] = AIR;   // a cave pocket
        base[idx(6, 25, 5)] = ORE;   // ore exposed to it

        int[] keep = base.clone();
        proc().process(keep, ySize, 0, Tier.REAL, P, true, STONE, DEEPSLATE, null);
        assertEquals(ORE, keep[idx(6, 25, 5)], "exposed ore kept when hideAll=false");

        int[] hide = base.clone();
        proc().process(hide, ySize, 0, Tier.REAL, P, true, true, STONE, DEEPSLATE, null);
        assertEquals(STONE, hide[idx(6, 25, 5)], "exposed ore hidden when hideAll=true");
    }

    @Test
    void aboveGroundPreserved_buriedCollapsed() {
        int ySize = 64;
        int LOG = 11; // non-terrain in the fake classifier
        int[] out = flatWorld(ySize, 40);
        for (int y = 41; y <= 45; y++) out[idx(8, y, 8)] = LOG; // tree trunk above the surface
        out[idx(3, 20, 3)] = LOG;                               // buried structure block
        proc().process(out, ySize, 0, Tier.DEEP, P, true, STONE, DEEPSLATE, null);
        // Above the terrain surface line -> untouched (trees, villages, builds).
        for (int y = 41; y <= 45; y++)
            assertEquals(LOG, out[idx(8, y, 8)], "above-surface log must be preserved at y" + y);
        // Below the surface -> collapsed like everything else (hidden, reveals on approach).
        assertEquals(STONE, out[idx(3, 20, 3)], "buried structure must collapse (hidden)");
    }

    @Test
    void surfaceAndNear_showsSurfaceAndYourCave_hidesFarAndBuried() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        out[idx(5, 40, 5)] = ORE;          // surface-exposed (air above is open sky)
        out[idx(8, 30, 8)] = AIR;          // the cave the player is in
        out[idx(9, 30, 8)] = ORE;          // ore in that cave, next to the player
        out[idx(1, 10, 1)] = AIR;          // a far cave
        out[idx(2, 10, 1)] = ORE;          // ore in the far cave
        out[idx(12, 15, 12)] = ORE;        // fully buried ore

        // Player standing at (8,30,8); reveal radius 8; chunk origin (0,0).
        OreView view = OreView.surfaceAndNear(8, 30, 8, 8, 0, 0);
        proc().process(out, ySize, 0, Tier.REAL, P, true, view, STONE, DEEPSLATE, null);

        assertEquals(ORE, out[idx(5, 40, 5)], "surface-exposed ore must stay visible");
        assertEquals(ORE, out[idx(9, 30, 8)], "ore in the cave you're standing in must stay visible");
        assertEquals(STONE, out[idx(2, 10, 1)], "far cave ore must be hidden from x-ray");
        assertEquals(STONE, out[idx(12, 15, 12)], "buried ore must be hidden");
    }

    @Test
    void surfaceOnly_keepsSurfaceVein_hidesTheRest() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        out[idx(5, 40, 5)] = ORE;          // surface-exposed
        out[idx(12, 15, 12)] = ORE;        // buried
        proc().process(out, ySize, 0, Tier.SHELL, P, true, OreView.surfaceOnly(), STONE, DEEPSLATE, null);
        assertEquals(ORE, out[idx(5, 40, 5)], "distant surface vein stays visible");
        assertEquals(STONE, out[idx(12, 15, 12)], "buried ore hidden");
    }

    @Test
    void deepSolidifiesSubsurfaceWater() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        out[idx(5, 20, 5)] = WATER;  // flooded cave below the surface
        proc().process(out, ySize, 0, Tier.DEEP, P, false, STONE, DEEPSLATE, null);
        assertEquals(STONE, out[idx(5, 20, 5)], "hidden underwater cave must be solidified too");
    }

    @Test
    void verticalCull_solidifiesBelowCut_keepsAbove_noVoid() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);   // STONE 0..40, AIR above
        out[idx(5, 10, 5)] = AIR;           // cave below the cut -> must be hidden
        out[idx(7, 30, 7)] = AIR;           // cave above the cut -> REAL keeps it
        out[idx(9, 35, 9)] = ORE;           // ore above the cut -> kept (no camo here)
        int cutLocalY = 20;
        int[] in = out.clone();

        proc().process(out, ySize, 0, Tier.REAL, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, cutLocalY);

        assertEquals(STONE, out[idx(5, 10, 5)], "cave below the cut must be solidified");
        for (int y = 0; y < cutLocalY; y++) {
            assertFalse(CLF.isTransparent(out[idx(5, y, 5)]), "no transparency below cut at y=" + y);
        }
        assertEquals(AIR, out[idx(7, 30, 7)], "geometry above the cut stays real");
        assertEquals(ORE, out[idx(9, 35, 9)], "ore above the cut is untouched");

        for (int i = 0; i < in.length; i++) {
            if (CLF.isTransparent(out[i])) {
                assertTrue(CLF.isTransparent(in[i]), "vertical cull created void at idx " + i);
            }
        }
    }

    @Test
    void verticalCull_disabledByNegativeCut() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        out[idx(5, 10, 5)] = AIR;           // cave low down
        proc().process(out, ySize, 0, Tier.REAL, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1);
        assertEquals(AIR, out[idx(5, 10, 5)], "with cut disabled, REAL leaves geometry intact");
    }

    // ---- anti-base-finder ----

    /**
     * A man-made entrance tunnel that reaches a real opening to the sky at x=2,
     * with a ladder inside it and a brick floor block — a textbook base tell.
     */
    private static int[] baseTunnelFixture(int ySize) {
        int[] b = flatWorld(ySize, 40);
        for (int x = 2; x <= 10; x++) b[idx(x, 20, 8)] = AIR;       // horizontal tunnel
        for (int y = 20; y < ySize; y++) b[idx(2, y, 8)] = AIR;     // vertical shaft to the sky (the opening)
        b[idx(5, 19, 8)] = BRICKS;                                  // a built floor block under the tunnel
        b[idx(8, 20, 8)] = LADDER;                                  // a ladder inside the tunnel (below surface)
        return b;
    }

    @Test
    void antiBaseFinder_manMadeTunnelNotRevealed() {
        int ySize = 64;
        // Baseline: with anti-base OFF, the shell reveals the tunnel near the opening.
        int[] off = baseTunnelFixture(ySize);
        proc().process(off, ySize, 0, Tier.SHELL, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, false);
        assertEquals(AIR, off[idx(4, 20, 8)], "control: without anti-base the base tunnel reveals near the opening");

        // With anti-base ON, the whole man-made pocket stays solid despite the opening,
        // and the ladder inside it is scrubbed to rock.
        int[] on = baseTunnelFixture(ySize);
        proc().process(on, ySize, 0, Tier.SHELL, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, true);
        for (int x = 3; x <= 10; x++)
            assertEquals(STONE, on[idx(x, 20, 8)], "anti-base must keep the man-made tunnel solid at x=" + x);
        // The above-surface part of the shaft (open sky) is still untouched.
        assertEquals(AIR, on[idx(2, 45, 8)], "open sky above the surface must remain air");
    }

    @Test
    void antiBaseFinder_naturalCaveStillReveals() {
        int ySize = 64;
        int[] out = tunnelFixture(ySize);   // purely natural: no man-made blocks
        proc().process(out, ySize, 0, Tier.SHELL, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, true);
        assertEquals(AIR, out[idx(5, 20, 8)], "a natural cave near an opening must still reveal under anti-base");
        assertEquals(STONE, out[idx(10, 20, 8)], "natural cave beyond the shell still solidifies");
    }

    @Test
    void antiBaseFinder_scrubsBuriedBaseBlocks() {
        int ySize = 64;
        // A base block sitting in the near-surface margin that rock-collapse preserves.
        int[] base = flatWorld(ySize, 40);
        base[idx(5, 38, 5)] = BRICKS;       // y=38 is above the collapse cut (40 - homogenize 8 = 32)
        base[idx(6, 38, 5)] = LADDER;

        int[] off = base.clone();
        proc().process(off, ySize, 0, Tier.DEEP, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, false);
        assertEquals(BRICKS, off[idx(5, 38, 5)], "control: without anti-base the margin keeps the base block");

        int[] on = base.clone();
        proc().process(on, ySize, 0, Tier.DEEP, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, true);
        assertEquals(STONE, on[idx(5, 38, 5)], "anti-base must scrub the buried building block to rock");
        assertEquals(STONE, on[idx(6, 38, 5)], "anti-base must scrub the buried ladder to rock");
    }

    @Test
    void antiBaseFinder_neverCreatesVoid() {
        for (Tier tier : new Tier[]{Tier.SHELL, Tier.DEEP}) {
            int ySize = 64;
            int[] in = baseTunnelFixture(ySize);
            in[idx(7, 10, 7)] = ORE;
            int[] out = in.clone();
            proc().process(out, ySize, 0, tier, P, true, OreView.keepExposed(),
                    STONE, DEEPSLATE, null, -1, true);
            for (int i = 0; i < in.length; i++) {
                if (CLF.isTransparent(out[i])) {
                    assertTrue(CLF.isTransparent(in[i]),
                            "anti-base (" + tier + ") created transparency at idx " + i);
                }
            }
        }
    }

    @Test
    void antiBaseFinder_realBubbleUntouched() {
        int ySize = 64;
        int[] in = baseTunnelFixture(ySize);
        int[] out = in.clone();
        // REAL tier, no ore camo: anti-base must be a no-op so your own base shows up close.
        ChunkProcessor.Result r = proc().process(out, ySize, 0, Tier.REAL, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, true);
        assertArrayEquals(in, out, "anti-base must not touch the REAL bubble");
        assertFalse(r.modified);
    }

    // ---- reachability ore reveal ----

    @Test
    void reachabilityOre_keepsReachableCave_hidesSealed_keepsSurface() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        out[idx(8, 30, 8)] = AIR;  out[idx(9, 30, 8)] = ORE;   // ore in the cave the player can reach
        out[idx(1, 10, 1)] = AIR;  out[idx(2, 10, 1)] = ORE;   // ore in a sealed (unreachable) cave
        out[idx(5, 40, 5)] = ORE;                              // surface-exposed vein

        boolean[] reach = new boolean[ySize << 8];
        reach[idx(8, 30, 8)] = true;                            // only the player's cave is reachable
        OreView v = OreView.surfaceAndReachable(reach);
        proc().process(out, ySize, 0, Tier.REAL, P, true, v, STONE, DEEPSLATE, null, -1, false);

        assertEquals(ORE, out[idx(9, 30, 8)], "ore in the reachable cave must stay visible");
        assertEquals(STONE, out[idx(2, 10, 1)], "ore in a sealed cave must be hidden (no peeking through walls)");
        assertEquals(ORE, out[idx(5, 40, 5)], "surface-exposed vein must stay visible");
    }

    @Test
    void reachabilityOre_nullMaskHidesAllButSurface() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        out[idx(8, 30, 8)] = AIR;  out[idx(9, 30, 8)] = ORE;
        out[idx(5, 40, 5)] = ORE;                              // surface-exposed
        proc().process(out, ySize, 0, Tier.REAL, P, true, OreView.surfaceAndReachable(null),
                STONE, DEEPSLATE, null, -1, false);
        assertEquals(STONE, out[idx(9, 30, 8)], "with no mask, non-surface ore is hidden");
        assertEquals(ORE, out[idx(5, 40, 5)], "with no mask, surface ore is still kept");
    }

    // ---- reachability cave hiding (REAL tier) ----

    @Test
    void reachabilityCaves_keepsReachable_solidifiesSealed_keepsSurface_noVoid() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        out[idx(8, 30, 8)] = AIR; out[idx(8, 30, 9)] = AIR;   // the cave the player is in
        out[idx(1, 10, 1)] = AIR; out[idx(2, 10, 1)] = AIR;   // a sealed cave elsewhere
        int[] in = out.clone();

        boolean[] reach = new boolean[ySize << 8];
        reach[idx(8, 30, 8)] = true; reach[idx(8, 30, 9)] = true;
        proc().process(out, ySize, 0, Tier.REAL, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, false, reach, true);

        assertEquals(AIR, out[idx(8, 30, 8)], "the reachable cave must stay real");
        assertEquals(AIR, out[idx(8, 30, 9)], "the reachable cave must stay real");
        assertEquals(STONE, out[idx(1, 10, 1)], "a cave you can't reach must be solidified, even in REAL");
        assertEquals(STONE, out[idx(2, 10, 1)], "a cave you can't reach must be solidified, even in REAL");
        // surface/sky untouched
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++)
                for (int y = 40; y < ySize; y++)
                    assertEquals(in[idx(x, y, z)], out[idx(x, y, z)], "surface/sky changed at " + x + "," + y + "," + z);
        // never creates void
        for (int i = 0; i < in.length; i++)
            if (CLF.isTransparent(out[i]))
                assertTrue(CLF.isTransparent(in[i]), "reachability caves created void at idx " + i);
    }

    @Test
    void reachabilityCaves_scrubsUnreachableBaseWalls_keepsWallsByYourCave() {
        int ySize = 64;
        int[] out = flatWorld(ySize, 40);
        out[idx(8, 30, 8)] = AIR;                  // the cave the player is in
        out[idx(7, 30, 8)] = BRICKS;               // a wall touching the reachable cave
        out[idx(3, 12, 3)] = BRICKS;               // an enclosed base wall you can't reach
        boolean[] reach = new boolean[ySize << 8];
        reach[idx(8, 30, 8)] = true;
        // anti-base ON so enclosed man-made blocks are scrubbed
        proc().process(out, ySize, 0, Tier.REAL, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, true, reach, true);
        assertEquals(BRICKS, out[idx(7, 30, 8)], "a wall touching the cave you're in stays real");
        assertEquals(STONE, out[idx(3, 12, 3)], "an enclosed unreachable base wall is scrubbed");
    }

    @Test
    void reachabilityCaves_disabledOrNullMaskLeavesRealIntact() {
        int ySize = 64;
        int[] off = flatWorld(ySize, 40);
        off[idx(1, 10, 1)] = AIR;
        proc().process(off, ySize, 0, Tier.REAL, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, false, null, false);
        assertEquals(AIR, off[idx(1, 10, 1)], "disabled: REAL leaves caves intact");

        int[] warming = flatWorld(ySize, 40);
        warming[idx(1, 10, 1)] = AIR;
        proc().process(warming, ySize, 0, Tier.REAL, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, false, null, true);
        assertEquals(AIR, warming[idx(1, 10, 1)], "null mask (warming up): no cave hiding yet");
    }

    // ---- surface-entrance camouflage ----

    /** Grass surface at {@code surface} over dirt/stone, full 16x16. */
    private static int[] grassWorld(int ySize, int surface) {
        int[] b = new int[ySize << 8];
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < surface; y++) b[idx(x, y, z)] = (y >= surface - 3) ? DIRT : STONE;
                b[idx(x, surface, z)] = GRASS;
            }
        return b;
    }

    @Test
    void surfaceEntrance_capsLadderShaft_blendsToGround_noVoid() {
        int ySize = 64, surface = 40;
        int[] b = grassWorld(ySize, surface);
        for (int y = 20; y <= surface; y++) b[idx(8, y, 8)] = AIR;     // a 1-wide shaft
        for (int y = 21; y < surface; y++) b[idx(8, y, 8)] = LADDER;   // ladder inside
        int[] in = b.clone();
        proc().process(b, ySize, 0, Tier.DEEP, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, false, null, false, true);
        assertEquals(GRASS, b[idx(8, surface, 8)], "entrance capped with grass on top");
        assertEquals(DIRT, b[idx(8, surface - 1, 8)], "below the cap blends to the neighbour's subsurface");
        assertFalse(CLF.isTransparent(b[idx(8, 30, 8)]), "the shaft is no longer open");
        for (int i = 0; i < in.length; i++)
            if (CLF.isTransparent(b[i])) assertTrue(CLF.isTransparent(in[i]), "entrance camo created void at idx " + i);
    }

    @Test
    void surfaceEntrance_capsWaterLift() {
        int ySize = 64, surface = 40;
        int[] b = grassWorld(ySize, surface);
        for (int y = 22; y <= surface; y++) b[idx(3, y, 3)] = WATER;   // a water column to the surface
        proc().process(b, ySize, 0, Tier.DEEP, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, false, null, false, true);
        assertEquals(GRASS, b[idx(3, surface, 3)], "water-lift mouth capped with grass");
        assertFalse(CLF.isTransparent(b[idx(3, 30, 3)]), "the water column is hidden");
    }

    @Test
    void surfaceEntrance_leavesPlainHoleAndRealTierAlone() {
        int ySize = 64, surface = 40;
        // A narrow hole with no man-made block / fluid -> not an entrance, left alone.
        int[] plain = grassWorld(ySize, surface);
        for (int y = 30; y <= surface; y++) plain[idx(5, y, 5)] = AIR;
        proc().process(plain, ySize, 0, Tier.DEEP, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, false, null, false, true);
        assertEquals(AIR, plain[idx(5, 35, 5)], "a plain unmarked hole is left alone");

        // REAL tier: the entrance stays visible (you're standing on it).
        int[] real = grassWorld(ySize, surface);
        for (int y = 20; y <= surface; y++) real[idx(8, y, 8)] = AIR;
        for (int y = 21; y < surface; y++) real[idx(8, y, 8)] = LADDER;
        proc().process(real, ySize, 0, Tier.REAL, P, false, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, false, null, false, true);
        assertEquals(LADDER, real[idx(8, 30, 8)], "REAL tier keeps your own entrance visible");
    }

    @Test
    void antiBaseFinder_offByDefaultOverloadEquivalence() {
        // The 11-arg overload (no anti-base) must equal the 12-arg overload with false.
        int ySize = 64;
        int[] a = baseTunnelFixture(ySize);
        int[] b = baseTunnelFixture(ySize);
        proc().process(a, ySize, 0, Tier.SHELL, P, true, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1);
        proc().process(b, ySize, 0, Tier.SHELL, P, true, OreView.keepExposed(),
                STONE, DEEPSLATE, null, -1, false);
        assertArrayEquals(a, b, "the legacy overload must behave exactly like anti-base=false");
    }
}
