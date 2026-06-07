package net.cadistenmc.cadistchunkprocessing;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes, on the main thread, the set of air cells each player can actually
 * <em>reach</em> — the connected pocket of open space (air / fluids) containing
 * their body — and publishes it as per-chunk boolean masks the packet thread can
 * read race-free. This is the "the cave you're in", reachability primitive the
 * anti-base / anti-xray layers use instead of a radius that bleeds through walls.
 *
 * <p>It only ever <em>reads</em> a bounded bubble around the player (real-radius
 * chunks, a vertical band around their feet) and recomputes lazily (when they
 * move into new space), so cost stays bounded. A snapshot is immutable once
 * published, so {@link #maskFor} is safe off the main thread. Anything that would
 * make it expensive (an enormous connected cave) trips a visit cap and the
 * player's snapshot is dropped, falling the caller back to the radius behaviour.
 */
public final class ReachabilityService {

    /** Vertical half-height (blocks) scanned above/below the player. */
    private static final int BAND = 64;
    /** Recompute only after the player moves at least this many blocks. */
    private static final int MOVE_THRESHOLD = 2;
    /** Abort (and fall back) if a single flood would visit more than this. */
    private static final int MAX_VISITS = 400_000;
    /** Scan cadence in ticks. */
    private static final long PERIOD = 10L;

    private final JavaPlugin plugin;
    private final Config config;
    private final RefreshScheduler scheduler;

    /** Immutable published result for one player. */
    private static final class Snap {
        final UUID world;
        final int ySize;
        final Map<Long, boolean[]> masks;   // chunkKey -> reachable mask (idx=(y<<8)|(z<<4)|x)
        Snap(UUID world, int ySize, Map<Long, boolean[]> masks) {
            this.world = world; this.ySize = ySize; this.masks = masks;
        }
    }

    private final ConcurrentHashMap<UUID, Snap> byPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, int[]> lastPos = new ConcurrentHashMap<>();
    private BukkitTask task;

    // Reused main-thread scratch (the task is single-threaded).
    private boolean[] visited = new boolean[0];
    private int[] queue = new int[0];

    public ReachabilityService(JavaPlugin plugin, Config config, RefreshScheduler scheduler) {
        this.plugin = plugin;
        this.config = config;
        this.scheduler = scheduler;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD, PERIOD);
    }

    public void stop() {
        if (task != null) task.cancel();
        byPlayer.clear();
        lastPos.clear();
    }

    /** Drop everything (e.g. on reload / settings change). */
    public void clear() {
        byPlayer.clear();
        lastPos.clear();
    }

    // ---- packet-thread accessors (race-free: Snap is immutable once published) ----

    /** True if a fresh reachability snapshot exists for this player+world+height. */
    public boolean hasSnapshot(UUID id, UUID world, int ySize) {
        Snap s = byPlayer.get(id);
        return s != null && s.world.equals(world) && s.ySize == ySize;
    }

    /** Reachable mask for one chunk, or null if that chunk holds no reachable air. */
    public boolean[] maskFor(UUID id, int cx, int cz) {
        Snap s = byPlayer.get(id);
        return s == null ? null : s.masks.get(key(cx, cz));
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ---- main-thread scan ----

    private void tick() {
        boolean on = config.reachabilityOres();
        if (!on) {
            if (!byPlayer.isEmpty()) { byPlayer.clear(); lastPos.clear(); }
            return;
        }
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            try {
                compute(p);
            } catch (Exception ignored) {
                byPlayer.remove(p.getUniqueId());   // never let a bad scan break the tick
            }
        }
        // Prune players who left.
        byPlayer.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        lastPos.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void compute(Player p) {
        UUID id = p.getUniqueId();
        if (p.hasPermission("cadistchunkprocessing.bypass")) { byPlayer.remove(id); return; }
        World w = p.getWorld();
        if (!config.worldEnabled(w.getName())) { byPlayer.remove(id); return; }

        int px = p.getLocation().getBlockX();
        int py = p.getLocation().getBlockY();
        int pz = p.getLocation().getBlockZ();

        int[] prev = lastPos.get(id);
        if (prev != null && byPlayer.containsKey(id)
                && Math.abs(prev[0] - px) < MOVE_THRESHOLD
                && Math.abs(prev[1] - py) < MOVE_THRESHOLD
                && Math.abs(prev[2] - pz) < MOVE_THRESHOLD) {
            return;   // hasn't moved enough; keep the existing snapshot
        }

        int r = config.params().realRadius();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        int ySize = maxY - minY;

        int yLo = Math.max(minY, py - BAND);
        int yHi = Math.min(maxY - 1, py + BAND);
        int H = yHi - yLo + 1;
        if (H <= 0) return;

        int pcx = px >> 4, pcz = pz >> 4;
        int worldX0 = (pcx - r) << 4;
        int worldZ0 = (pcz - r) << 4;
        int W = (2 * r + 1) * 16;

        // Snapshot the loaded bubble chunks (main thread).
        Map<Long, ChunkSnapshot> snaps = new HashMap<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (w.isChunkLoaded(cx, cz)) {
                    snaps.put(key(cx, cz), w.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
                }
            }
        }

        int total = H * W * W;
        ensureScratch(total);
        java.util.Arrays.fill(visited, 0, total, false);

        // Seed from the player's feet + head.
        int head = 0, tail = 0;
        for (int yy = py; yy <= py + 1 && yy <= yHi; yy++) {
            int rel = rel(px - worldX0, yy - yLo, pz - worldZ0, W);
            if (rel >= 0 && !visited[rel] && passableAt(snaps, px, yy, pz)) {
                visited[rel] = true;
                queue[tail++] = rel;
            }
        }

        int visits = 0;
        while (head < tail) {
            int rel = queue[head++];
            if (++visits > MAX_VISITS) { byPlayer.remove(id); lastPos.put(id, new int[]{px, py, pz}); return; }
            int rx = rel % W;
            int t = rel / W;
            int rz = t % W;
            int ry = t / W;
            int wx = worldX0 + rx, wy = yLo + ry, wz = worldZ0 + rz;

            if (rx > 0)     tail = relax(snaps, rel - 1,       wx - 1, wy, wz, tail);
            if (rx < W - 1) tail = relax(snaps, rel + 1,       wx + 1, wy, wz, tail);
            if (rz > 0)     tail = relax(snaps, rel - W,       wx, wy, wz - 1, tail);
            if (rz < W - 1) tail = relax(snaps, rel + W,       wx, wy, wz + 1, tail);
            if (ry > 0)     tail = relax(snaps, rel - W * W,   wx, wy - 1, wz, tail);
            if (ry < H - 1) tail = relax(snaps, rel + W * W,   wx, wy + 1, wz, tail);
        }

        // Build per-chunk masks from the visited set.
        Map<Long, boolean[]> masks = new HashMap<>();
        for (int rel = 0; rel < total; rel++) {
            if (!visited[rel]) continue;
            int rx = rel % W;
            int tt = rel / W;
            int rz = tt % W;
            int ry = tt / W;
            int wx = worldX0 + rx, wy = yLo + ry, wz = worldZ0 + rz;
            long ck = key(wx >> 4, wz >> 4);
            boolean[] mask = masks.computeIfAbsent(ck, k -> new boolean[ySize << 8]);
            int localY = wy - minY;
            mask[(localY << 8) | ((wz & 15) << 4) | (wx & 15)] = true;
        }

        Snap fresh = new Snap(w.getUID(), ySize, masks);
        Snap old = byPlayer.put(id, fresh);
        lastPos.put(id, new int[]{px, py, pz});

        // Re-send chunks whose reachability changed so ores update without a chunk cross.
        enqueueChanged(id, old, fresh);
    }

    private int relax(Map<Long, ChunkSnapshot> snaps, int nRel, int wx, int wy, int wz, int tail) {
        if (!visited[nRel] && passableAt(snaps, wx, wy, wz)) {
            visited[nRel] = true;
            queue[tail++] = nRel;
        }
        return tail;
    }

    private static int rel(int rx, int ry, int rz, int W) {
        if (rx < 0 || rx >= W || rz < 0 || rz >= W || ry < 0) return -1;
        return ry * (W * W) + rz * W + rx;
    }

    /** A cell the player's body can occupy: air or a fluid. Unloaded chunks read as solid. */
    private boolean passableAt(Map<Long, ChunkSnapshot> snaps, int wx, int wy, int wz) {
        ChunkSnapshot s = snaps.get(key(wx >> 4, wz >> 4));
        if (s == null) return false;
        Material m = s.getBlockType(wx & 15, wy, wz & 15);
        if (m.isAir()) return true;
        return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN;
    }

    private void enqueueChanged(UUID id, Snap old, Snap fresh) {
        for (Map.Entry<Long, boolean[]> e : fresh.masks.entrySet()) {
            boolean[] before = old == null ? null : old.masks.get(e.getKey());
            if (!java.util.Arrays.equals(before, e.getValue())) {
                long k = e.getKey();
                scheduler.enqueue(id, (int) (k >> 32), (int) (long) k);
            }
        }
        // Chunks that lost all reachable air also need a re-send (now strict-hidden).
        if (old != null) {
            for (Long k : old.masks.keySet()) {
                if (!fresh.masks.containsKey(k)) {
                    scheduler.enqueue(id, (int) (k >> 32), (int) (long) k);
                }
            }
        }
    }

    private void ensureScratch(int total) {
        if (visited.length < total) {
            visited = new boolean[total];
            queue = new int[total];
        }
    }
}
