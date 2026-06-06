package net.cadistenmc.cadistchunkprocessing;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Invalidates {@link BorderCache} entries for an edited chunk and its four
 * neighbours, so SHELL seam continuity recomputes from fresh geometry on the
 * next send. Cheap and purely cosmetic — the solidify model can never void, so
 * a momentarily stale seam is harmless.
 */
public final class ChunkDirtyListener implements Listener {

    private final BorderCache borderCache;
    private final ProcessedChunkCache chunkCache;

    public ChunkDirtyListener(BorderCache borderCache, ProcessedChunkCache chunkCache) {
        this.borderCache = borderCache;
        this.chunkCache = chunkCache;
    }

    private void dirty(World world, int cx, int cz) {
        java.util.UUID w = world.getUID();
        borderCache.invalidate(w, cx, cz);
        borderCache.invalidate(w, cx - 1, cz);
        borderCache.invalidate(w, cx + 1, cz);
        borderCache.invalidate(w, cx, cz - 1);
        borderCache.invalidate(w, cx, cz + 1);
        // The processed (DEEP) cache only depends on the chunk's own blocks, so a
        // single-chunk invalidation suffices; neighbours are unaffected.
        chunkCache.invalidate(w, cx, cz);
    }

    private void dirty(Block b) {
        dirty(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { dirty(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { dirty(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        Chunk c = e.getBlock().getChunk();
        dirty(c.getWorld(), c.getX(), c.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        Chunk c = e.getLocation().getChunk();
        dirty(c.getWorld(), c.getX(), c.getZ());
    }
}
