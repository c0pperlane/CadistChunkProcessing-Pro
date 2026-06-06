package net.cadistenmc.cadistchunkprocessing.engine;

import java.util.Arrays;

/**
 * The pure culling engine. Operates on a flattened column of block-state global
 * ids and an injected {@link BlockClassifier} — no Bukkit, no PacketEvents, no
 * shared mutable static state — so it is fully deterministic and unit-testable.
 *
 * <h2>The one rule: solidify, never void</h2>
 * Every transform only ever turns transparent space or ore into a homogeneous
 * <em>solid</em> ghost block. It never writes air where a solid existed, so the
 * client can never be shown void by a later block-update, chunk border, or in
 * freecam. Bandwidth is reclaimed because collapsing a section's palette (down
 * to one block-state ≈ a few bytes on the wire) shrinks the packet just as much
 * as deleting blocks would — see {@link #estimateBytes}.
 *
 * <h2>Index convention</h2>
 * {@code idx = (y << 8) | (z << 4) | x}, with {@code x,z in [0,15]} and
 * {@code y in [0, ySize)}. A 16-block section {@code sy} is the contiguous range
 * {@code [sy<<12, (sy+1)<<12)}.
 *
 * <p>One instance is single-threaded; the caller holds a {@code ThreadLocal}.
 */
public final class ChunkProcessor {

    /** Outcome of one column process; the input array is mutated in place. */
    public static final class Result {
        public long bytesBefore;
        public long bytesAfter;
        public long blocksSolidified;   // hidden cave space -> ghost
        public long oresHidden;         // buried ore -> ghost
        public long blocksHomogenized;  // deep section collapsed to ghost
        public boolean modified;
        /** Highest natural-terrain local-Y per column (idx = (z&lt;&lt;4)|x), -1 if none. */
        public int[] heightMap;
    }

    private final BlockClassifier clf;

    // Reusable scratch buffers (grown on demand for the tallest world seen).
    private boolean[] caveAir = new boolean[0];
    private int[] dist = new int[0];
    private int[] queue = new int[0];
    private final int[] sortScratch = new int[4096];

    public ChunkProcessor(BlockClassifier clf) {
        this.clf = clf;
    }

    public BlockClassifier classifier() {
        return clf;
    }

    /**
     * Process one column.
     *
     * @param blocks    flattened global ids (mutated in place)
     * @param ySize     total block height (multiple of 16)
     * @param minY      world min height (e.g. -64) — selects deepslate vs stone
     * @param tier      REAL / SHELL / DEEP
     * @param params    active mode knobs
     * @param oreCamo   apply anti-xray ore camouflage (all tiers)
     * @param ghostHigh ghost block id for worldY >= 0 (e.g. stone / netherrack)
     * @param ghostLow  ghost block id for worldY < 0  (e.g. deepslate)
     * @param border    cross-chunk opening info, or null
     */
    /** Overload: keep legitimately exposed ores visible (no player view). */
    public Result process(int[] blocks, int ySize, int minY,
                          Tier tier, ModeParams params, boolean oreCamo,
                          int ghostHigh, int ghostLow, BorderSeed border) {
        return process(blocks, ySize, minY, tier, params, oreCamo, OreView.keepExposed(),
                ghostHigh, ghostLow, border);
    }

    /** Overload: simple hide-all toggle (true = camouflage every ore). */
    public Result process(int[] blocks, int ySize, int minY,
                          Tier tier, ModeParams params, boolean oreCamo, boolean hideAllOres,
                          int ghostHigh, int ghostLow, BorderSeed border) {
        return process(blocks, ySize, minY, tier, params, oreCamo,
                hideAllOres ? OreView.hideAll() : OreView.keepExposed(), ghostHigh, ghostLow, border);
    }

    /**
     * Full process. {@code oreView} decides which ores remain visible — the
     * anti-xray policy (hide-all / keep-exposed / surface-only / surface+near).
     */
    public Result process(int[] blocks, int ySize, int minY,
                          Tier tier, ModeParams params, boolean oreCamo, OreView oreView,
                          int ghostHigh, int ghostLow, BorderSeed border) {
        return process(blocks, ySize, minY, tier, params, oreCamo, oreView,
                ghostHigh, ghostLow, border, -1);
    }

    /**
     * As above, plus {@code verticalCutLocalY}: when {@code > 0}, every block
     * below that local-Y is solidified to the ghost block regardless of tier —
     * "vertical culling". The caller sets it to (playerY − margin) so the deep
     * column far below a player (who is standing on the surface or in a shallow
     * cave) isn't sent in full detail; it re-reveals as they descend. {@code <= 0}
     * disables it (keeps the result player-independent, so DEEP stays cacheable).
     */
    public Result process(int[] blocks, int ySize, int minY,
                          Tier tier, ModeParams params, boolean oreCamo, OreView oreView,
                          int ghostHigh, int ghostLow, BorderSeed border, int verticalCutLocalY) {
        Result r = new Result();
        final int total = ySize << 8;
        ensureScratch(total);

        r.bytesBefore = estimateBytes(blocks, ySize);

        int[] heightMap = new int[256];
        computeHeightmap(blocks, heightMap, ySize);
        r.heightMap = heightMap;

        if (tier != Tier.REAL) {
            classifyCaveAir(blocks, heightMap, ySize, total);
            if (tier == Tier.SHELL) {
                markRevealedShell(blocks, heightMap, ySize, total,
                        params.entranceShellDepth(), border);
            }
            // DEEP: dist stays -1 everywhere -> nothing revealed -> all hidden.
            solidifyHidden(blocks, ySize, minY, ghostHigh, ghostLow, total, r);
        }

        if (oreCamo) {
            oreCamouflage(blocks, ySize, minY, heightMap, oreView, ghostHigh, ghostLow, border, r);
        }

        // The main bandwidth lever: recolour sub-surface rock to the ghost block
        // so sections become single-palette. Runs in SHELL *and* DEEP — it skips
        // revealed shell cave air and exposed ores, so reveals and legit ores are
        // preserved while everything genuinely hidden collapses.
        if (tier != Tier.REAL && params.rockCollapse()) {
            collapseSubSurface(blocks, heightMap, minY, ghostHigh, ghostLow, params.homogenizeBelow(), r);
        }

        // Vertical culling: solidify everything below the player's depth margin.
        // Runs last so it overrides any revealed cave air / exposed ore below the
        // cut too (extra anti-xray). Never writes air, so the no-void invariant
        // holds; the region re-reveals on descent via the vertical resend.
        if (verticalCutLocalY > 0) {
            verticalCollapse(blocks, ySize, minY, ghostHigh, ghostLow, verticalCutLocalY, r);
        }

        r.bytesAfter = estimateBytes(blocks, ySize);
        r.modified = r.blocksSolidified > 0 || r.oresHidden > 0 || r.blocksHomogenized > 0;
        return r;
    }

    // ---------------------------------------------------------------- heightmap

    /**
     * Highest natural-terrain local-Y per column, or -1 if none. Using terrain
     * (not merely "solid") means trees, village houses and other above-ground
     * structures sit ABOVE the surface line, so their wood/air is never treated
     * as cave space and never solidified or collapsed.
     */
    private void computeHeightmap(int[] blocks, int[] heightMap, int ySize) {
        Arrays.fill(heightMap, -1);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = (z << 4) | x;
                for (int y = ySize - 1; y >= 0; y--) {
                    if (clf.isTerrain(blocks[(y << 8) | (z << 4) | x])) {
                        heightMap[col] = y;
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- cave-air mask

    /** cave air = transparent block strictly below its column's surface. */
    private void classifyCaveAir(int[] blocks, int[] heightMap, int ySize, int total) {
        Arrays.fill(caveAir, 0, total, false);
        Arrays.fill(dist, 0, total, -1);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int s = heightMap[(z << 4) | x];
                if (s <= 0) continue;
                for (int y = 0; y < s; y++) {
                    int idx = (y << 8) | (z << 4) | x;
                    if (clf.isTransparent(blocks[idx])) caveAir[idx] = true;
                }
            }
        }
    }

    // --------------------------------------------------------- SHELL reveal BFS

    /**
     * Geodesic BFS through cave air, seeded from genuine openings (this chunk's
     * exterior air + neighbour openings). Cave air within {@code shellDepth} of
     * an opening keeps {@code dist >= 0} (stays revealed); the rest is solidified.
     */
    private void markRevealedShell(int[] blocks, int[] heightMap, int ySize,
                                   int total, int shellDepth, BorderSeed border) {
        int tail = 0;
        for (int idx = 0; idx < total; idx++) {
            if (!caveAir[idx]) continue;
            int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;
            if (touchesOpening(blocks, idx, x, y, z, ySize, border)) {
                dist[idx] = 0;
                queue[tail++] = idx;
            }
        }
        int head = 0;
        while (head < tail) {
            int idx = queue[head++];
            int d = dist[idx];
            if (d >= shellDepth) continue;
            int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;
            int nd = d + 1;
            if (x > 0)          tail = relax(idx - 1,   nd, tail);
            if (x < 15)         tail = relax(idx + 1,   nd, tail);
            if (z > 0)          tail = relax(idx - 16,  nd, tail);
            if (z < 15)         tail = relax(idx + 16,  nd, tail);
            if (y > 0)          tail = relax(idx - 256, nd, tail);
            if (y < ySize - 1)  tail = relax(idx + 256, nd, tail);
        }
    }

    /** Relax a neighbour during the BFS; returns the (possibly grown) queue tail. */
    private int relax(int n, int nd, int tail) {
        if (caveAir[n] && dist[n] < 0) {
            dist[n] = nd;
            queue[tail++] = n;
        }
        return tail;
    }

    private boolean touchesOpening(int[] blocks, int idx, int x, int y, int z,
                                   int ySize, BorderSeed border) {
        if (x > 0          && isExterior(blocks, idx - 1))   return true;
        if (x < 15         && isExterior(blocks, idx + 1))   return true;
        if (z > 0          && isExterior(blocks, idx - 16))  return true;
        if (z < 15         && isExterior(blocks, idx + 16))  return true;
        if (y > 0          && isExterior(blocks, idx - 256)) return true;
        if (y < ySize - 1  && isExterior(blocks, idx + 256)) return true;
        if (border != null) {
            if (x == 0  && border.openingWest(y, z))  return true;
            if (x == 15 && border.openingEast(y, z))  return true;
            if (z == 0  && border.openingNorth(y, x)) return true;
            if (z == 15 && border.openingSouth(y, x)) return true;
        }
        return false;
    }

    /** exterior = transparent but not cave air (i.e. open sky / above surface). */
    private boolean isExterior(int[] blocks, int nIdx) {
        return !caveAir[nIdx] && clf.isTransparent(blocks[nIdx]);
    }

    // ------------------------------------------------------------- solidify

    private void solidifyHidden(int[] blocks, int ySize, int minY,
                                int ghostHigh, int ghostLow, int total, Result r) {
        for (int idx = 0; idx < total; idx++) {
            if (caveAir[idx] && dist[idx] < 0) {
                int worldY = minY + (idx >> 8);
                blocks[idx] = worldY < 0 ? ghostLow : ghostHigh;
                r.blocksSolidified++;
            }
        }
    }

    // ------------------------------------------------------------- homogenize

    /**
     * DEEP + rock-collapse: recolour every block more than {@code margin} blocks
     * below its own column's surface to the ghost block. Per-column, so a deep
     * ravine or cave-mouth in one column no longer blocks the rest of the chunk
     * (the bug that capped savings). Fully sub-surface sections become single-
     * palette — the bulk of the bandwidth win. A small real margin under the
     * surface preserves near-surface rock and cliff-face colour.
     */
    private void collapseSubSurface(int[] blocks, int[] heightMap, int minY,
                                    int ghostHigh, int ghostLow, int margin, Result r) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int cut = heightMap[(z << 4) | x] - margin;
                for (int y = 0; y < cut; y++) {
                    int idx = (y << 8) | (z << 4) | x;
                    if (caveAir[idx] && dist[idx] >= 0) continue;   // keep revealed shell cave air
                    // Everything else below the surface margin -> ghost. This
                    // includes caves, sculk, amethyst, ancient cities, trial
                    // chambers and buried builds: all hidden, so a section ends
                    // single-state (stone, or deepslate below y=0) and collapses
                    // to a few wire bytes. Above-ground trees/villages are safe —
                    // they sit above the terrain surface line, never in range.
                    int g = (minY + y) < 0 ? ghostLow : ghostHigh;
                    if (blocks[idx] != g) {
                        blocks[idx] = g;
                        r.blocksHomogenized++;
                    }
                }
            }
        }
    }

    /**
     * Solidify every block below {@code cutLocalY} to the world-correct ghost
     * block — a flat horizontal cut (not surface-relative). Used for vertical
     * culling in the REAL bubble so the deep column under a surface player isn't
     * sent in full. Pure solidify: only ever overwrites with a solid block.
     */
    private void verticalCollapse(int[] blocks, int ySize, int minY,
                                  int ghostHigh, int ghostLow, int cutLocalY, Result r) {
        int cut = Math.min(cutLocalY, ySize);
        for (int y = 0; y < cut; y++) {
            int g = (minY + y) < 0 ? ghostLow : ghostHigh;
            int base = y << 8;
            for (int i = 0; i < 256; i++) {
                int idx = base | i;
                if (blocks[idx] != g) {
                    blocks[idx] = g;
                    r.blocksHomogenized++;
                }
            }
        }
    }

    // ------------------------------------------------------------- ore camo

    private void oreCamouflage(int[] blocks, int ySize, int minY, int[] heightMap, OreView view,
                               int ghostHigh, int ghostLow, BorderSeed border, Result r) {
        final int total = ySize << 8;
        for (int idx = 0; idx < total; idx++) {
            if (!clf.isOre(blocks[idx])) continue;
            int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;

            boolean keep = switch (view.mode) {
                case HIDE_ALL -> false;
                case KEEP_EXPOSED -> oreExposed(blocks, idx, x, y, z, ySize, border);
                case SURFACE_ONLY -> surfaceExposed(blocks, heightMap, idx, x, y, z, ySize);
                case SURFACE_AND_NEAR -> surfaceExposed(blocks, heightMap, idx, x, y, z, ySize)
                        || (oreExposed(blocks, idx, x, y, z, ySize, border) && nearPlayer(view, x, y, z, minY));
            };
            if (keep) continue;

            int worldY = minY + y;
            blocks[idx] = worldY < 0 ? ghostLow : ghostHigh;
            r.oresHidden++;
        }
    }

    /** Ore is exposed to exterior (open-sky) air — visible from outside the terrain. */
    private boolean surfaceExposed(int[] blocks, int[] heightMap, int idx, int x, int y, int z, int ySize) {
        if (x > 0          && isExteriorAir(blocks, heightMap, idx - 1))   return true;
        if (x < 15         && isExteriorAir(blocks, heightMap, idx + 1))   return true;
        if (z > 0          && isExteriorAir(blocks, heightMap, idx - 16))  return true;
        if (z < 15         && isExteriorAir(blocks, heightMap, idx + 16))  return true;
        if (y > 0          && isExteriorAir(blocks, heightMap, idx - 256)) return true;
        if (y < ySize - 1  && isExteriorAir(blocks, heightMap, idx + 256)) return true;
        return false;
    }

    private boolean isExteriorAir(int[] blocks, int[] heightMap, int nIdx) {
        if (!clf.isTransparent(blocks[nIdx])) return false;
        int ny = nIdx >> 8, nx = nIdx & 15, nz = (nIdx >> 4) & 15;
        return ny >= heightMap[(nz << 4) | nx];   // at/above its column's surface = open sky
    }

    /** Ore is within the reveal radius of the player (the cave they're standing in). */
    private boolean nearPlayer(OreView v, int x, int y, int z, int minY) {
        long dx = (long) (v.worldX + x) - v.px;
        long dy = (long) (minY + y) - v.py;
        long dz = (long) (v.worldZ + z) - v.pz;
        return dx * dx + dy * dy + dz * dz <= v.radiusSq;
    }

    private boolean oreExposed(int[] blocks, int idx, int x, int y, int z,
                               int ySize, BorderSeed border) {
        if (x > 0          && clf.isTransparent(blocks[idx - 1]))   return true;
        if (x < 15         && clf.isTransparent(blocks[idx + 1]))   return true;
        if (z > 0          && clf.isTransparent(blocks[idx - 16]))  return true;
        if (z < 15         && clf.isTransparent(blocks[idx + 16]))  return true;
        if (y > 0          && clf.isTransparent(blocks[idx - 256])) return true;
        if (y < ySize - 1  && clf.isTransparent(blocks[idx + 256])) return true;
        if (border != null) {
            if (x == 0  && border.openingWest(y, z))  return true;
            if (x == 15 && border.openingEast(y, z))  return true;
            if (z == 0  && border.openingNorth(y, x)) return true;
            if (z == 15 && border.openingSouth(y, x)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- byte model

    /**
     * Estimate the column's block-state wire size, modelling Minecraft 1.21's
     * paletted-container encoding: a section's cost is driven by its palette
     * size (distinct block-states), not by how many blocks are "solid".
     */
    public long estimateBytes(int[] blocks, int ySize) {
        long total = 0;
        int sections = ySize >> 4;
        for (int sy = 0; sy < sections; sy++) {
            System.arraycopy(blocks, sy << 12, sortScratch, 0, 4096);
            Arrays.sort(sortScratch);
            int palette = 1;
            for (int i = 1; i < 4096; i++) {
                if (sortScratch[i] != sortScratch[i - 1]) palette++;
            }
            int bits;
            long paletteBytes;
            if (palette <= 1) {
                bits = 0;
                paletteBytes = 1;                       // single-value: one id varint
            } else {
                int b = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette - 1));
                if (b > 8) {                            // direct (global) palette
                    bits = 15;
                    paletteBytes = 0;
                } else {
                    bits = b;
                    paletteBytes = 1 + palette * 2L;    // palette length + ids
                }
            }
            long dataBytes = 512L * bits;               // 4096 * bits / 8
            total += 3 + paletteBytes + dataBytes;      // block count + bitsPerEntry
        }
        return total;
    }

    // ------------------------------------------------------------- scratch

    private void ensureScratch(int total) {
        if (caveAir.length < total) {
            caveAir = new boolean[total];
            dist = new int[total];
            queue = new int[total];
        }
    }
}
