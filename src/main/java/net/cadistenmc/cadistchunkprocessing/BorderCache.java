package net.cadistenmc.cadistchunkprocessing;

import net.cadistenmc.cadistchunkprocessing.engine.BorderSeed;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-chunk seam continuity. After a chunk is processed we record, per face,
 * whether each border block ended up transparent (a revealed cave opening or
 * exterior air). A neighbour reads those faces to seed its own SHELL reveal, so
 * a cave spanning a chunk boundary opens to the same depth on both sides — no
 * flat seam wall. Purely cosmetic: with the solidify model there is never void
 * to leak, so a stale seam is at worst stone-next-to-stone.
 *
 * <p>Fully concurrent and Bukkit-free — safe from the packet thread.
 */
public final class BorderCache {

    /** Per-face processed transparency. Index = y*16 + lateral (z for W/E, x for N/S). */
    public record Faces(int ySize, boolean[] west, boolean[] east, boolean[] north, boolean[] south) {}

    private static final int CAP = 16384;
    /** Partitioned per world — chunk (cx,cz) is shared across overworld/nether/end. */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Faces>> byWorld = new ConcurrentHashMap<>();

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private ConcurrentHashMap<Long, Faces> world(UUID world) {
        return byWorld.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
    }

    public void store(UUID world, int cx, int cz, Faces faces) {
        ConcurrentHashMap<Long, Faces> m = world(world);
        if (m.size() > CAP) m.clear();
        m.put(key(cx, cz), faces);
    }

    public void invalidate(UUID world, int cx, int cz) {
        ConcurrentHashMap<Long, Faces> m = byWorld.get(world);
        if (m != null) m.remove(key(cx, cz));
    }

    public void clear() {
        byWorld.clear();
    }

    /**
     * A {@link BorderSeed} for chunk (cx,cz) that reads the four neighbours'
     * shared faces. Returns false everywhere a neighbour hasn't been processed
     * yet (the seam converges after the next refresh).
     */
    public BorderSeed seedFor(UUID world, int cx, int cz, int ySize) {
        ConcurrentHashMap<Long, Faces> m = byWorld.get(world);
        if (m == null) return null;
        Faces w = match(m.get(key(cx - 1, cz)), ySize);
        Faces e = match(m.get(key(cx + 1, cz)), ySize);
        Faces n = match(m.get(key(cx, cz - 1)), ySize);
        Faces s = match(m.get(key(cx, cz + 1)), ySize);
        if (w == null && e == null && n == null && s == null) return null;
        return new BorderSeed() {
            @Override public boolean openingWest(int y, int z)  { return w != null && w.east[y * 16 + z]; }
            @Override public boolean openingEast(int y, int z)  { return e != null && e.west[y * 16 + z]; }
            @Override public boolean openingNorth(int y, int x) { return n != null && n.south[y * 16 + x]; }
            @Override public boolean openingSouth(int y, int x) { return s != null && s.north[y * 16 + x]; }
        };
    }

    private static Faces match(Faces f, int ySize) {
        return (f != null && f.ySize == ySize) ? f : null;
    }
}
