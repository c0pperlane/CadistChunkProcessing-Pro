package net.cadistenmc.cadistchunkprocessing;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drains per-player chunk re-send queues a few at a time each tick (main thread)
 * via {@link World#refreshChunk}, so revealing/hiding caves as a player moves
 * never bursts the network. {@code refreshChunk} re-triggers the interceptor,
 * which re-classifies the chunk for the player's new distance.
 *
 * <p>The queue is a per-player {@link LinkedHashSet} keyed by chunk: enqueuing the
 * same chunk twice before it drains is a no-op, so a burst of overlapping reveal
 * sources (movement bubble, fog reveal, ore ring) can never pile thousands of
 * duplicate re-sends into the queue. All access is main-thread.
 */
public final class RefreshScheduler {

    private final JavaPlugin plugin;
    private final Config config;
    private final Map<UUID, LinkedHashSet<Long>> pending = new ConcurrentHashMap<>();
    private BukkitTask task;

    public RefreshScheduler(JavaPlugin plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        pending.clear();
    }

    public void enqueue(UUID id, int cx, int cz) {
        pending.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(key(cx, cz));
    }

    public void remove(UUID id) {
        pending.remove(id);
    }

    private void tick() {
        int per = config.refreshPerTick();
        for (Map.Entry<UUID, LinkedHashSet<Long>> e : pending.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            LinkedHashSet<Long> q = e.getValue();
            if (p == null || !p.isOnline()) { q.clear(); continue; }
            World w = p.getWorld();
            int done = 0;
            Iterator<Long> it = q.iterator();
            while (done < per && it.hasNext()) {
                long ck = it.next();
                it.remove();
                int cx = (int) (ck >> 32), cz = (int) ck;
                if (w.isChunkLoaded(cx, cz)) {
                    try {
                        w.refreshChunk(cx, cz);
                    } catch (Exception ignored) {
                    }
                }
                done++;
            }
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
