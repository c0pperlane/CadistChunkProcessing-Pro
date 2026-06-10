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
    private boolean[] tainted = new boolean[0];   // cave air belonging to a man-made (base) space
    private boolean[] seen = new boolean[0];       // visited flag for the taint flood-fill
    private int[] dist = new int[0];
    private int[] queue = new int[0];
    private final int[] sortScratch = new int[4096];

    /** True for the current column iff the anti-base-finder taint mask is live. */
    private boolean useTaint;

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
        return process(blocks, ySize, minY, tier, params, oreCamo, oreView,
                ghostHigh, ghostLow, border, verticalCutLocalY, false);
    }

    /**
     * As above, plus {@code antiBaseFinder}: the aggressive anti-base layer. When
     * enabled, in the hidden tiers (SHELL/DEEP) the engine additionally
     * <ol>
     *   <li><b>never reveals man-made space.</b> A connected pocket of cave air
     *       that touches any player-signature block (a dug tunnel, ladder shaft,
     *       water-lift, hollowed room) is treated as a base and is solidified
     *       even if it reaches a genuine opening — so the shell reveal can no
     *       longer punch a base entrance open. Natural caves still reveal.</li>
     *   <li><b>camouflages the signature blocks themselves</b> below the surface
     *       (building blocks, ladders, rails, redstone, lamps, doors, signs …) to
     *       the ghost rock, so nothing leaks through a thin wall or window.</li>
     * </ol>
     * Strictly sub-surface and "solidify, never void" like the rest of the
     * engine: surfaces stay vanilla and the REAL bubble is untouched, so your own
     * base re-appears in full as you walk up to it.
     */
    public Result process(int[] blocks, int ySize, int minY,
                          Tier tier, ModeParams params, boolean oreCamo, OreView oreView,
                          int ghostHigh, int ghostLow, BorderSeed border, int verticalCutLocalY,
                          boolean antiBaseFinder) {
        return process(blocks, ySize, minY, tier, params, oreCamo, oreView, ghostHigh, ghostLow,
                border, verticalCutLocalY, antiBaseFinder, null, false);
    }

    /**
     * As above, plus reachability cave hiding. In the REAL tier, when
     * {@code reachabilityCaves} is on and a {@code reachable} mask (chunk-local,
     * idx = (y&lt;&lt;8)|(z&lt;&lt;4)|x) is supplied, every cave-air cell the
     * player can NOT reach is solidified — so a cave/base you aren't standing in
     * reads as solid rock even up close, and freecam can't see it (it's never
     * sent). The cave you're in (reachable) stays real, so digging there is
     * correct; mining into a hidden pocket reveals it on the next scan. With
     * {@code antiBaseFinder} also on, enclosed man-made blocks you can't reach are
     * scrubbed too, so a base's walls don't outline it on x-ray. Pure solidify.
     */
    public Result process(int[] blocks, int ySize, int minY,
                          Tier tier, ModeParams params, boolean oreCamo, OreView oreView,
                          int ghostHigh, int ghostLow, BorderSeed border, int verticalCutLocalY,
                          boolean antiBaseFinder, boolean[] reachable, boolean reachabilityCaves) {
        return process(blocks, ySize, minY, tier, params, oreCamo, oreView, ghostHigh, ghostLow,
                border, verticalCutLocalY, antiBaseFinder, reachable, reachabilityCaves, false);
    }

    /**
     * As above, plus {@code surfaceEntrances}: in the hidden tiers (SHELL/DEEP),
     * camouflage small artificial/water surface entrances — a trapdoor, ladder
     * shaft, hatch or water-lift dropping into a base. A column that is a narrow
     * pit (every neighbour's surface is higher) and contains a man-made block or
     * fluid is capped up to the surrounding ground with the neighbour's own
     * surface blocks, so from a distance the ground reads as untouched. It
     * reappears in the REAL bubble so you can use your own door. The single
     * deliberate exception to "never touch the surface" — kept narrow and
     * artificial-gated so natural ravines / cave mouths are left alone. Pure
     * solidify (only writes solid over transparent), so the no-void rule holds.
     */
    public Result process(int[] blocks, int ySize, int minY,
                          Tier tier, ModeParams params, boolean oreCamo, OreView oreView,
                          int ghostHigh, int ghostLow, BorderSeed border, int verticalCutLocalY,
                          boolean antiBaseFinder, boolean[] reachable, boolean reachabilityCaves,
                          boolean surfaceEntrances) {
        return process(blocks, ySize, minY, tier, params, oreCamo, oreView, ghostHigh, ghostLow,
                border, verticalCutLocalY, antiBaseFinder, reachable, reachabilityCaves,
                surfaceEntrances, false);
    }

    /**
     * As above, plus {@code hideSealedCaves}: REAL-tier sealed-cave hiding. With it
     * on, every cave-air cell that has no air path to the open sky — an
     * <em>entrance-less</em> pocket (a fully walled-off cavity or a sealed room) —
     * is solidified, while caves that DO reach the surface (real, visible cave
     * mouths) are left intact, so nothing is false-culled and no void is created.
     * The cave/room the player is actually in is kept via the {@code reachable}
     * mask, so a sealed base you're standing in (closed door) never solidifies
     * around you. Seam-continuous across chunks via {@code border}. The gentle
     * sibling of reachability cave hiding: it removes only what genuinely has no
     * entrance, never open caves you merely aren't standing in.
     */
    public Result process(int[] blocks, int ySize, int minY,
                          Tier tier, ModeParams params, boolean oreCamo, OreView oreView,
                          int ghostHigh, int ghostLow, BorderSeed border, int verticalCutLocalY,
                          boolean antiBaseFinder, boolean[] reachable, boolean reachabilityCaves,
                          boolean surfaceEntrances, boolean hideSealedCaves) {
        Result r = new Result();
        final int total = ySize << 8;
        ensureScratch(total);
        useTaint = false;

        r.bytesBefore = estimateBytes(blocks, ySize);

        int[] heightMap = new int[256];
        computeHeightmap(blocks, heightMap, ySize);
        r.heightMap = heightMap;

        if (tier != Tier.REAL) {
            classifyCaveAir(blocks, heightMap, ySize, total);
            if (tier == Tier.SHELL) {
                // Anti-base only matters for the SHELL reveal — DEEP already hides
                // every cave, base or not. Build the man-made-space mask so the
                // reveal BFS skips it (the base entrance never opens).
                if (antiBaseFinder) {
                    markArtificialTaint(blocks, total, ySize);
                    useTaint = true;
                }
                markRevealedShell(blocks, heightMap, ySize, total,
                        params.entranceShellDepth(), border);
            }
            // DEEP: dist stays -1 everywhere -> nothing revealed -> all hidden.
            solidifyHidden(blocks, ySize, minY, ghostHigh, ghostLow, total, r);
        } else {
            // REAL tier cave hiding. Two complementary policies, either/both:
            //   reachabilityCaves — hide every cave the player can't reach (sealed
            //       bases AND open caves you aren't standing in); aggressive.
            //   hideSealedCaves   — the gentle option: hide only caves with no
            //       entrance to the open sky, keeping open caves and the room you
            //       are in (reachable). Removes only what genuinely has no way in.
            boolean[] reach = (reachable != null && reachable.length >= total) ? reachable : null;
            boolean reachOn = reachabilityCaves && reach != null;
            if (reachOn || hideSealedCaves) {
                classifyCaveAir(blocks, heightMap, ySize, total);
                if (hideSealedCaves) {
                    markSurfaceConnected(blocks, ySize, total, border);
                    solidifySealed(blocks, minY, ghostHigh, ghostLow, total, reach, r);
                }
                if (reachOn) {
                    solidifyUnreachable(blocks, ySize, minY, ghostHigh, ghostLow, total, reach, r);
                }
            }
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

        // Anti-base finder: scrub player-signature blocks that survive below the
        // surface (e.g. inside the near-surface margin rock-collapse preserves, or
        // when rock-collapse is off) so a base built just under the ground reads
        // as plain rock from above or through a wall. Pure solidify.
        if (antiBaseFinder && tier != Tier.REAL) {
            solidifyArtificial(blocks, heightMap, minY, ghostHigh, ghostLow, r);
        }

        // REAL tier + reachability + anti-base: scrub enclosed man-made blocks the
        // player can't reach, so a hidden base's walls don't outline it on x-ray.
        if (antiBaseFinder && tier == Tier.REAL && reachabilityCaves
                && reachable != null && reachable.length >= total) {
            solidifyArtificialUnreachable(blocks, heightMap, ySize, minY, ghostHigh, ghostLow, reachable, r);
        }

        // Surface-entrance camouflage: cap small artificial/water entrance shafts
        // in the hidden tiers so a distant viewer sees untouched ground. Only away
        // from the player (SHELL/DEEP); the entrance shows in the REAL bubble.
        if (surfaceEntrances && tier != Tier.REAL) {
            camouflageSurfaceEntrances(blocks, heightMap, ySize, ghostHigh, r);
        }

        // Vertical culling: solidify everything below the player's depth margin.
        // Runs last so it overrides any revealed cave air / exposed ore below the
        // cut too (extra anti-xray). Never writes air, so the no-void invariant
        // holds; the region re-reveals on descent via the vertical resend.
        if (verticalCutLocalY > 0) {
            verticalCollapse(blocks, heightMap, ySize, minY, ghostHigh, ghostLow, verticalCutLocalY, r);
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
            if (useTaint && tainted[idx]) continue;   // man-made space never seeds a reveal
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
        if (caveAir[n] && dist[n] < 0 && !(useTaint && tainted[n])) {
            dist[n] = nd;
            queue[tail++] = n;
        }
        return tail;
    }

    // ------------------------------------------------------ anti-base-finder

    /**
     * Mark every cave-air cell that belongs to a <em>man-made</em> space. A space
     * is man-made if its connected (6-neighbour) pocket of cave air touches any
     * player-signature block — a dug tunnel, a ladder/water-lift shaft, a hollowed
     * room or a walled-off cellar. Marking the whole connected component (not just
     * cells next to a block) means even the open centre of a large room is caught,
     * so the shell reveal can never crack a base open from a corner.
     *
     * <p>Natural caves (bordered only by terrain) are never marked and reveal as
     * usual. Pure read of {@code caveAir} + the classifier; mutates only the
     * {@code tainted}/{@code seen}/{@code queue} scratch.
     */
    private void markArtificialTaint(int[] blocks, int total, int ySize) {
        Arrays.fill(tainted, 0, total, false);
        Arrays.fill(seen, 0, total, false);
        for (int start = 0; start < total; start++) {
            if (!caveAir[start] || seen[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            boolean artificial = false;
            while (head < tail) {
                int idx = queue[head++];
                int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;
                if (!artificial && touchesArtificial(blocks, idx, x, y, z, ySize)) {
                    artificial = true;
                }
                if (x > 0)         tail = visit(idx - 1,   tail);
                if (x < 15)        tail = visit(idx + 1,   tail);
                if (z > 0)         tail = visit(idx - 16,  tail);
                if (z < 15)        tail = visit(idx + 16,  tail);
                if (y > 0)         tail = visit(idx - 256, tail);
                if (y < ySize - 1) tail = visit(idx + 256, tail);
            }
            if (artificial) {
                for (int i = 0; i < tail; i++) tainted[queue[i]] = true;
            }
        }
    }

    /** Enqueue an unvisited cave-air neighbour for the taint flood-fill. */
    private int visit(int n, int tail) {
        if (caveAir[n] && !seen[n]) {
            seen[n] = true;
            queue[tail++] = n;
        }
        return tail;
    }

    /** True if any of the six face neighbours is a player-signature block. */
    private boolean touchesArtificial(int[] blocks, int idx, int x, int y, int z, int ySize) {
        if (x > 0          && clf.isArtificial(blocks[idx - 1]))   return true;
        if (x < 15         && clf.isArtificial(blocks[idx + 1]))   return true;
        if (z > 0          && clf.isArtificial(blocks[idx - 16]))  return true;
        if (z < 15         && clf.isArtificial(blocks[idx + 16]))  return true;
        if (y > 0          && clf.isArtificial(blocks[idx - 256])) return true;
        if (y < ySize - 1  && clf.isArtificial(blocks[idx + 256])) return true;
        return false;
    }

    /**
     * Solidify every player-signature block strictly below its column's surface to
     * the world-correct ghost rock. Catches the base's actual materials (ladders,
     * rails, redstone, lamps, doors, signs, building blocks) so none leak through a
     * thin wall or a window. Above-ground builds sit at/above the surface line and
     * are never in range. Pure solidify — only ever overwrites with solid rock.
     */
    private void solidifyArtificial(int[] blocks, int[] heightMap, int minY,
                                    int ghostHigh, int ghostLow, Result r) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int s = heightMap[(z << 4) | x];
                if (s < 0) continue;                 // no terrain in this column -> leave it
                for (int y = 0; y < s; y++) {
                    int idx = (y << 8) | (z << 4) | x;
                    if (clf.isArtificial(blocks[idx])) {
                        int g = (minY + y) < 0 ? ghostLow : ghostHigh;
                        if (blocks[idx] != g) {
                            blocks[idx] = g;
                            r.blocksSolidified++;
                        }
                    }
                }
            }
        }
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

    /**
     * REAL-tier reachability: solidify every cave-air cell the player can't reach.
     * {@code caveAir} already marks only transparent space below the surface, so
     * surfaces/sky are never touched; a reachable cell (the cave you're in) is
     * kept. Pure solidify — only ever writes a solid block.
     */
    private void solidifyUnreachable(int[] blocks, int ySize, int minY,
                                     int ghostHigh, int ghostLow, int total, boolean[] reachable, Result r) {
        for (int idx = 0; idx < total; idx++) {
            if (caveAir[idx] && !reachable[idx]) {
                int worldY = minY + (idx >> 8);
                blocks[idx] = worldY < 0 ? ghostLow : ghostHigh;
                r.blocksSolidified++;
            }
        }
    }

    /**
     * Flood cave air from genuine surface openings (this chunk's exterior/open-sky
     * air plus neighbour openings via {@code border}) with no depth limit. After it
     * runs, {@code dist[idx] >= 0} marks every cave-air cell that has an air path to
     * the open sky; {@code dist[idx] < 0} marks an <em>entrance-less</em> (sealed)
     * pocket. The mirror of {@link #markRevealedShell} but uncapped — used by REAL-
     * tier sealed-cave hiding. Assumes {@code dist} is pre-filled to -1
     * (classifyCaveAir does this) and ignores the anti-base taint mask.
     */
    private void markSurfaceConnected(int[] blocks, int ySize, int total, BorderSeed border) {
        int tail = 0;
        for (int idx = 0; idx < total; idx++) {
            if (!caveAir[idx] || dist[idx] >= 0) continue;
            int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;
            if (touchesOpening(blocks, idx, x, y, z, ySize, border)) {
                dist[idx] = 0;
                queue[tail++] = idx;
            }
        }
        int head = 0;
        while (head < tail) {
            int idx = queue[head++];
            int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;
            if (x > 0)         tail = relaxOpen(idx - 1,   tail);
            if (x < 15)        tail = relaxOpen(idx + 1,   tail);
            if (z > 0)         tail = relaxOpen(idx - 16,  tail);
            if (z < 15)        tail = relaxOpen(idx + 16,  tail);
            if (y > 0)         tail = relaxOpen(idx - 256, tail);
            if (y < ySize - 1) tail = relaxOpen(idx + 256, tail);
        }
    }

    /** Mark a cave-air neighbour surface-connected; returns the (grown) queue tail. */
    private int relaxOpen(int n, int tail) {
        if (caveAir[n] && dist[n] < 0) {
            dist[n] = 0;
            queue[tail++] = n;
        }
        return tail;
    }

    /**
     * REAL-tier sealed-cave hiding: solidify every cave-air cell with no surface
     * connection ({@code dist < 0}) that the player also can't reach. Keeps open
     * caves (surface-connected) and the cave/room you're standing in (reachable),
     * so it never false-culls a visible cave nor buries the player. Clears the
     * solidified cells from {@code caveAir} so a following reachability pass doesn't
     * double-count them. Pure solidify — only ever writes a solid block.
     */
    private void solidifySealed(int[] blocks, int minY, int ghostHigh, int ghostLow,
                                int total, boolean[] reachable, Result r) {
        for (int idx = 0; idx < total; idx++) {
            if (!caveAir[idx] || dist[idx] >= 0) continue;       // surface-connected -> keep
            if (reachable != null && reachable[idx]) continue;   // the cave you're in -> keep
            int worldY = minY + (idx >> 8);
            blocks[idx] = worldY < 0 ? ghostLow : ghostHigh;
            caveAir[idx] = false;
            r.blocksSolidified++;
        }
    }

    /**
     * REAL-tier reachability + anti-base: solidify man-made blocks below the
     * surface that touch no reachable air — i.e. the walls/floor of a base you
     * aren't inside. When you're in the base the interior air is reachable, so the
     * adjacent blocks are kept and it stays real.
     */
    private void solidifyArtificialUnreachable(int[] blocks, int[] heightMap, int ySize, int minY,
                                               int ghostHigh, int ghostLow, boolean[] reachable, Result r) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int s = heightMap[(z << 4) | x];
                if (s < 0) continue;
                for (int y = 0; y < s; y++) {
                    int idx = (y << 8) | (z << 4) | x;
                    if (!clf.isArtificial(blocks[idx])) continue;
                    if (adjacentReachable(reachable, idx, x, y, z, ySize)) continue;
                    int g = (minY + y) < 0 ? ghostLow : ghostHigh;
                    if (blocks[idx] != g) {
                        blocks[idx] = g;
                        r.blocksSolidified++;
                    }
                }
            }
        }
    }

    /** Deepest narrow entrance shaft we'll cap; beyond this it's likely a natural chasm. */
    private static final int ENTRANCE_MAX_DEPTH = 96;

    /**
     * Cap small artificial/water surface entrances. A column qualifies when it is
     * a <em>narrow pit</em> — every in-chunk orthogonal neighbour's surface is
     * strictly higher (so it's a 1–2 wide shaft, not a slope or a wide open
     * cavern) — and the pit holds a man-made block or fluid (so a natural hole is
     * left alone). It's then filled from its own surface up to the lowest
     * surrounding rim with that neighbour's surface blocks, blending the hole into
     * the ground. Only ever writes solid over transparent, so no void is created;
     * it re-reveals as REAL on approach.
     */
    private void camouflageSurfaceEntrances(int[] blocks, int[] heightMap, int ySize, int ghostHigh, Result r) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = (z << 4) | x;
                int ownS = heightMap[col];
                if (ownS < 0) continue;

                // Every in-chunk orthogonal neighbour must be strictly higher.
                int minN = Integer.MAX_VALUE, refCol = -1, present = 0;
                boolean pit = true;
                int[][] nb = {{x - 1, z}, {x + 1, z}, {x, z - 1}, {x, z + 1}};
                for (int[] n : nb) {
                    int nx = n[0], nz = n[1];
                    if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                    present++;
                    int nh = heightMap[(nz << 4) | nx];
                    if (nh <= ownS) { pit = false; break; }
                    if (nh < minN) { minN = nh; refCol = (nz << 4) | nx; }
                }
                if (!pit || present < 2 || refCol < 0) continue;
                int nS = minN;
                if (nS - ownS > ENTRANCE_MAX_DEPTH) continue;

                // The pit must contain a man-made block or fluid (an entrance, not a natural hole).
                boolean entrance = false;
                int top = Math.min(nS + 1, ySize - 1);
                for (int y = ownS + 1; y <= top; y++) {
                    int v = blocks[(y << 8) | col];
                    if (clf.isArtificial(v) || clf.isFluid(v)) { entrance = true; break; }
                }
                if (!entrance) continue;

                // Blend with the lowest-rim neighbour's own surface blocks.
                int topBlock = blocks[(nS << 8) | refCol];
                int belowBlock = (nS - 1 >= 0) ? blocks[((nS - 1) << 8) | refCol] : topBlock;
                if (!clf.isTerrain(topBlock)) topBlock = ghostHigh;
                if (!clf.isTerrain(belowBlock)) belowBlock = ghostHigh;

                for (int y = ownS + 1; y <= nS; y++) {
                    int idx = (y << 8) | col;
                    int g = (y == nS) ? topBlock : belowBlock;
                    if (blocks[idx] != g) {
                        blocks[idx] = g;
                        r.blocksSolidified++;
                    }
                }
            }
        }
    }

    /** True if any of the six neighbours is a cell the player can reach. */
    private boolean adjacentReachable(boolean[] m, int idx, int x, int y, int z, int ySize) {
        if (x > 0          && m[idx - 1])   return true;
        if (x < 15         && m[idx + 1])   return true;
        if (z > 0          && m[idx - 16])  return true;
        if (z < 15         && m[idx + 16])  return true;
        if (y > 0          && m[idx - 256]) return true;
        if (y < ySize - 1  && m[idx + 256]) return true;
        return false;
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
     * Vertical culling, per column. Solidify each column from the bottom up to
     * {@code min(playerCutLocalY, columnSurface)} — i.e. the flat player cut
     * (playerY − margin) <em>clamped to that column's own surface</em>. Clamping is
     * what stops a flat cliff: when the player stands higher than nearby terrain,
     * the raw player cut sits above that terrain's surface, and an un-clamped cut
     * would fill the open air with a floating stone slab. Clamped, a column is never
     * solidified above its own surface (open air and the surface skin stay vanilla),
     * so far/lower terrain in the bubble simply has its deep caves hidden instead of
     * being sliced by a plane. Columns with no terrain are skipped entirely (never
     * slab over air or ocean). As the player descends, the player cut drops below
     * the surface and reveals the column locally. Pure solidify — never void.
     */
    private void verticalCollapse(int[] blocks, int[] heightMap, int ySize, int minY,
                                  int ghostHigh, int ghostLow, int playerCutLocalY, Result r) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = (z << 4) | x;
                int surface = heightMap[col];
                if (surface < 0) continue;                 // no terrain -> never solidify open air
                int cut = Math.min(playerCutLocalY, surface);
                if (cut > ySize) cut = ySize;
                for (int y = 0; y < cut; y++) {
                    int idx = (y << 8) | col;
                    int g = (minY + y) < 0 ? ghostLow : ghostHigh;
                    if (blocks[idx] != g) {
                        blocks[idx] = g;
                        r.blocksHomogenized++;
                    }
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
                case SURFACE_AND_REACHABLE -> surfaceExposed(blocks, heightMap, idx, x, y, z, ySize)
                        || exposedToReachable(view, idx, x, y, z, ySize);
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

    /**
     * Ore touches air the player can actually reach (a cell flagged in the
     * reachability mask). The mask only marks reachable transparent cells, so a
     * flagged neighbour is genuine open space connected to the player — no radius,
     * no peeking through walls. Safe if the mask is missing/short.
     */
    private boolean exposedToReachable(OreView v, int idx, int x, int y, int z, int ySize) {
        boolean[] m = v.reachable;
        if (m == null || m.length < (ySize << 8)) return false;
        if (x > 0          && m[idx - 1])   return true;
        if (x < 15         && m[idx + 1])   return true;
        if (z > 0          && m[idx - 16])  return true;
        if (z < 15         && m[idx + 16])  return true;
        if (y > 0          && m[idx - 256]) return true;
        if (y < ySize - 1  && m[idx + 256]) return true;
        return false;
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
            tainted = new boolean[total];
            seen = new boolean[total];
            dist = new int[total];
            queue = new int[total];
        }
    }
}
