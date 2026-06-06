package net.cadistenmc.cadistchunkprocessing;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-independent cache of processed DEEP chunks. A DEEP-tier transform
 * depends only on the chunk's own blocks, the world (min height + ghost ids)
 * and the active mode — never on player position or neighbour borders — so the
 * result can be computed once and replayed for every player and every re-send,
 * turning the engine's ~8 full-column passes into a single {@code apply}.
 *
 * <p>SHELL (border-dependent) and REAL (player-position-dependent) are never
 * cached. Staleness is harmless by construction: a cached DEEP chunk is fully
 * solidified rock far below the surface and far from any player; the instant a
 * player approaches it re-sends as SHELL/REAL (cache-bypassed) with live
 * geometry, and the solidify model means a stale entry can only ever be
 * solid-where-something-changed, never void.
 *
 * <p>Entries are partitioned <b>per world</b>: chunk (cx,cz) exists in the
 * overworld, nether and end at once, and each has different ghost blocks / min
 * height, so they must never share an entry.
 *
 * <p>Fully concurrent and Bukkit-free — safe to read/write from the packet
 * thread. Invalidated per-chunk on block edits ({@link ChunkDirtyListener}) and
 * cleared wholesale on any mode/param change ({@link CadistChunkProcessingPro#applyChanges()}).
 */
public final class ProcessedChunkCache {

    /** A finished DEEP transform: the processed blocks, the heightmap (for the
     *  block-entity strip) and the byte/count stats to replay into the monitor. */
    public record Entry(int[] blocks, int[] heightMap, int ySize,
                        long bytesBefore, long bytesAfter, long oresHidden, long blocksSolidified) {}

    /** Caps memory: each entry is ~25 KB, so 4096 ≈ 100 MB worst case. */
    private static final int CAP = 4096;

    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Entry>> byWorld = new ConcurrentHashMap<>();

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public Entry get(UUID world, int cx, int cz) {
        ConcurrentHashMap<Long, Entry> m = byWorld.get(world);
        return m == null ? null : m.get(key(cx, cz));
    }

    public void put(UUID world, int cx, int cz, Entry entry) {
        if (totalSize() > CAP) {
            // Evict only the largest single-world sub-map rather than clearing all worlds,
            // to avoid a thundering-herd of cache misses across unrelated worlds.
            byWorld.values().stream()
                    .max(Comparator.comparingInt(Map::size))
                    .ifPresent(ConcurrentHashMap::clear);
        }
        byWorld.computeIfAbsent(world, w -> new ConcurrentHashMap<>()).put(key(cx, cz), entry);
    }

    public void invalidate(UUID world, int cx, int cz) {
        ConcurrentHashMap<Long, Entry> m = byWorld.get(world);
        if (m != null) m.remove(key(cx, cz));
    }

    public void clear() {
        byWorld.clear();
    }

    public int size() {
        return totalSize();
    }

    private int totalSize() {
        int n = 0;
        for (ConcurrentHashMap<Long, Entry> m : byWorld.values()) n += m.size();
        return n;
    }
}
