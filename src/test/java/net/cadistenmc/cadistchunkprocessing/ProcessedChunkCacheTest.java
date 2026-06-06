package net.cadistenmc.cadistchunkprocessing;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Guards the multi-world (overworld / nether / end) correctness of the DEEP
 * cache: the same chunk coordinates exist in every world, so entries must never
 * leak across worlds.
 */
class ProcessedChunkCacheTest {

    private static ProcessedChunkCache.Entry entry(int marker) {
        return new ProcessedChunkCache.Entry(new int[]{marker}, new int[]{0}, 256, 100, 10, 1, 2);
    }

    @Test
    void sameCoordsInDifferentWorldsDoNotCollide() {
        ProcessedChunkCache cache = new ProcessedChunkCache();
        UUID overworld = UUID.randomUUID();
        UUID nether = UUID.randomUUID();

        ProcessedChunkCache.Entry ow = entry(1);   // e.g. stone-filled
        ProcessedChunkCache.Entry ne = entry(2);   // e.g. netherrack-filled
        cache.put(overworld, 100, 50, ow);
        cache.put(nether, 100, 50, ne);

        assertSame(ow, cache.get(overworld, 100, 50), "overworld chunk must return its own entry");
        assertSame(ne, cache.get(nether, 100, 50), "nether chunk at same coords must return its own entry");
        assertEquals(2, cache.size());
    }

    @Test
    void invalidateIsWorldScoped() {
        ProcessedChunkCache cache = new ProcessedChunkCache();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        cache.put(a, 0, 0, entry(1));
        cache.put(b, 0, 0, entry(2));

        cache.invalidate(a, 0, 0);
        assertNull(cache.get(a, 0, 0), "invalidated world-A entry is gone");
        assertNotNull(cache.get(b, 0, 0), "world-B entry at same coords is untouched");
    }

    @Test
    void missesReturnNull() {
        ProcessedChunkCache cache = new ProcessedChunkCache();
        assertNull(cache.get(UUID.randomUUID(), 0, 0));
        cache.put(UUID.randomUUID(), 5, 5, entry(1));
        cache.clear();
        assertEquals(0, cache.size());
    }
}
