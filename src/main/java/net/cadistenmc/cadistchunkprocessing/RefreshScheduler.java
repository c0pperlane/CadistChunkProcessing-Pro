package net.cadistenmc.cadistchunkprocessing;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Drains per-player chunk re-send queues a few at a time each tick (main thread)
 * via {@link World#refreshChunk}, so revealing/hiding caves as a player moves
 * never bursts the network. {@code refreshChunk} re-triggers the interceptor,
 * which re-classifies the chunk for the player's new distance.
 */
public final class RefreshScheduler {

    private final JavaPlugin plugin;
    private final Config config;
    private final Map<UUID, Queue<long[]>> pending = new ConcurrentHashMap<>();
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
        pending.computeIfAbsent(id, k -> new ConcurrentLinkedQueue<>()).add(new long[]{cx, cz});
    }

    public void remove(UUID id) {
        pending.remove(id);
    }

    private void tick() {
        int per = config.refreshPerTick();
        for (Map.Entry<UUID, Queue<long[]>> e : pending.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) continue;
            World w = p.getWorld();
            Queue<long[]> q = e.getValue();
            int done = 0;
            while (done < per) {
                long[] pair = q.poll();
                if (pair == null) break;
                int cx = (int) pair[0], cz = (int) pair[1];
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
}
