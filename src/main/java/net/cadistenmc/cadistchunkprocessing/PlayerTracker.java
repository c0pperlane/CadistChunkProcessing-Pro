package net.cadistenmc.cadistchunkprocessing;

import net.cadistenmc.cadistchunkprocessing.engine.ModeParams;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains, on the main thread, the small per-player facts the packet thread
 * reads (current chunk + world), and drives the "reveal bubble": chunks within
 * {@code real-radius} are kept real (queued for re-send when newly entered) and
 * re-hidden once they drift past {@code real-radius + reveal-hysteresis}.
 */
public final class PlayerTracker implements Listener {

    private final Config config;
    private final WorldMeta worldMeta;
    private final RefreshScheduler scheduler;

    private final ConcurrentHashMap<UUID, Long> chunkOf = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> worldOf = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, int[]> posOf = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<Long>> revealed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> joinMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> lastY = new ConcurrentHashMap<>();

    public PlayerTracker(Config config, WorldMeta worldMeta, RefreshScheduler scheduler) {
        this.config = config;
        this.worldMeta = worldMeta;
        this.scheduler = scheduler;
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ---- packet-thread accessors (all reads of concurrent maps) ----
    public Long chunkKey(UUID id) { return chunkOf.get(id); }
    public UUID worldUID(UUID id) { return worldOf.get(id); }
    /** Last known player block position {x,y,z}, or null. A fresh array is stored each update (race-safe). */
    public int[] pos(UUID id) { return posOf.get(id); }
    public boolean inJoinGrace(UUID id, int rawSeconds) {
        if (rawSeconds <= 0) return false;
        Long j = joinMs.get(id);
        return j != null && (System.currentTimeMillis() - j) < rawSeconds * 1000L;
    }

    // ---- lifecycle ----

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        worldMeta.refresh(p.getWorld());
        worldOf.put(id, p.getWorld().getUID());
        joinMs.put(id, System.currentTimeMillis());
        revealed.put(id, ConcurrentHashMap.newKeySet());
        Location l = p.getLocation();
        int nx = l.getBlockX() >> 4, nz = l.getBlockZ() >> 4;
        chunkOf.put(id, key(nx, nz));
        posOf.put(id, new int[]{l.getBlockX(), l.getBlockY(), l.getBlockZ()});
        lastY.put(id, l.getBlockY());
        // Track the initial bubble for later re-hiding; initial sends are already
        // REAL by distance, so no enqueue needed here.
        seedBubble(id, nx, nz);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Location to = e.getTo();
        posOf.put(id, new int[]{to.getBlockX(), to.getBlockY(), to.getBlockZ()});
        int nx = to.getBlockX() >> 4, nz = to.getBlockZ() >> 4;
        Long prev = chunkOf.put(id, key(nx, nz));
        if (!config.caveHiding()) return;

        boolean crossed = prev == null || prev.longValue() != key(nx, nz);
        if (crossed) {
            recompute(id, nx, nz);
        }
        // Vertical reveal: as the player descends/ascends, re-send the bubble so
        // the vertical cull line follows them (independent of horizontal crossing).
        if (config.verticalCulling()) {
            Integer pY = lastY.get(id);
            int y = to.getBlockY();
            if (pY == null || Math.abs(y - pY) >= config.verticalResendBlocks()) {
                lastY.put(id, y);
                if (!crossed) enqueueBubble(id, nx, nz);   // recompute already re-sent the bubble if we crossed
            }
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        worldMeta.refresh(p.getWorld());
        worldOf.put(id, p.getWorld().getUID());
        scheduler.remove(id);
        Set<Long> set = revealed.get(id);
        if (set != null) set.clear();
        Location l = p.getLocation();
        int nx = l.getBlockX() >> 4, nz = l.getBlockZ() >> 4;
        chunkOf.put(id, key(nx, nz));
        posOf.put(id, new int[]{l.getBlockX(), l.getBlockY(), l.getBlockZ()});
        lastY.put(id, l.getBlockY());
        seedBubble(id, nx, nz);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        chunkOf.remove(id);
        worldOf.remove(id);
        posOf.remove(id);
        revealed.remove(id);
        joinMs.remove(id);
        lastY.remove(id);
        scheduler.remove(id);
    }

    // ---- bubble logic ----

    private void seedBubble(UUID id, int nx, int nz) {
        Set<Long> set = revealed.get(id);
        if (set == null) return;
        int r = config.params().realRadius();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                set.add(key(nx + dx, nz + dz));
    }

    /** Re-send every chunk in the real bubble (used by the vertical reveal). */
    private void enqueueBubble(UUID id, int nx, int nz) {
        int r = config.params().realRadius();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                scheduler.enqueue(id, nx + dx, nz + dz);
    }

    private void recompute(UUID id, int nx, int nz) {
        Set<Long> set = revealed.get(id);
        if (set == null) return;
        ModeParams params = config.params();
        int r = params.realRadius();
        int hyst = r + params.revealHysteresis();

        // Reveal newly-entered chunks (geometry: caves open as you approach).
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = nx + dx, cz = nz + dz;
                if (set.add(key(cx, cz))) {
                    scheduler.enqueue(id, cx, cz);
                }
            }
        }
        // Keep ore reveal fresh: re-send chunks within ore-reveal range each time
        // you cross a chunk, so ores in the cave you walk into actually appear.
        // (Ores only near-reveal inside the REAL bubble, so this stays bounded.)
        int orR = Math.min(r, (config.oreRevealRadius() + 15) / 16);
        for (int dx = -orR; dx <= orR; dx++) {
            for (int dz = -orR; dz <= orR; dz++) {
                scheduler.enqueue(id, nx + dx, nz + dz);
            }
        }
        // Re-hide chunks that have drifted past the hysteresis ring.
        for (Iterator<Long> it = set.iterator(); it.hasNext(); ) {
            long k = it.next();
            int cx = (int) (k >> 32), cz = (int) k;
            if (Math.max(Math.abs(cx - nx), Math.abs(cz - nz)) > hyst) {
                it.remove();
                scheduler.enqueue(id, cx, cz);
            }
        }
    }
}
