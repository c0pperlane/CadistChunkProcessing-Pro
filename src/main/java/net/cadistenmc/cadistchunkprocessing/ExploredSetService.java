package net.cadistenmc.cadistchunkprocessing;

import net.cadistenmc.cadistchunkprocessing.engine.ExploredCodec;
import net.cadistenmc.cadistchunkprocessing.engine.SightMarch;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fog of war: maintains, per player, the set of cells they have actually
 * <em>seen</em> (eye raycasts) or <em>been near</em> (an 8-block body bubble) —
 * the strongest correct anti-freecam predicate. Mirrors
 * {@link ReachabilityService}: a bounded, throttled main-thread scan that
 * publishes immutable per-chunk bitsets the packet thread reads race-free.
 *
 * <p>Unlike reachability (which is recomputed fresh each scan), the explored set
 * is <em>cumulative within a session</em> — never cleared as you move — so
 * reveals are monotone (no flicker). Per-chunk bitsets are merged copy-on-write:
 * a scan that adds cells to a chunk publishes a brand-new array, so a reader on
 * the packet thread always sees a complete, never-mutated-in-place snapshot.
 *
 * <p>Two mark sources per scan:
 * <ol>
 *   <li><b>Sight</b> — N rays DDA-marched from the eye through server-truth
 *       geometry (chunk snapshots), stopping at the first occluder, the distance
 *       cap, or the edge of loaded chunks. ~75% jittered over the look frustum,
 *       ~25% over the sphere (so the F5 back-camera never stares at solid fog).</li>
 *   <li><b>Body</b> — a small air/fluid flood within an 8-block leash of the
 *       player, so you are never buried in fog and digging stays correct even
 *       before a ray reaches the new space.</li>
 * </ol>
 */
public final class ExploredSetService implements Listener {

    /** Recompute only after the player moves at least this many blocks (or turns; see {@link #compute}). */
    private static final int MOVE_THRESHOLD = 1;
    /** Scan cadence in ticks. */
    private static final long PERIOD = 4L;
    /** Hard cap on total sight rays cast per tick across all players (the governor). */
    private static final int MAX_RAYS_PER_TICK = 4096;
    /** Fraction of rays aimed within the look frustum (the rest sample the sphere). */
    private static final double FRUSTUM_FRACTION = 0.75;
    /** Half-angle (degrees) of the look-frustum cone the frustum rays sample. */
    private static final double FRUSTUM_HALF_ANGLE = 55.0;
    /** Flush all players' sets to disk at least this often (ms). */
    private static final long FLUSH_INTERVAL_MS = 60_000L;
    /** Ignore look changes smaller than this (degrees) so fine mouse jitter doesn't re-scan. */
    private static final int LOOK_THRESHOLD_DEG = 5;
    /** Above this many newly-revealed cells in one chunk, a full chunk re-send is cheaper than per-block updates. */
    private static final int PER_CHUNK_BLOCK_CAP = 256;
    /** Hard cap on per-block reveal updates sent to one player per scan (overflow falls back to chunk re-send). */
    private static final int MAX_BLOCK_UPDATES_PER_SCAN = 8192;

    private final JavaPlugin plugin;
    private final Config config;
    private final RefreshScheduler scheduler;
    private final ExploredStore store;
    private long lastFlush = System.currentTimeMillis();

    /** Cumulative explored bitsets for one player in one world. */
    private static final class Fog {
        final UUID world;
        final int ySize;
        final int minY;
        final int bitsLen;                              // longs per chunk = ceil((ySize<<8)/64)
        final ConcurrentHashMap<Long, long[]> bits = new ConcurrentHashMap<>();
        Fog(UUID world, int ySize, int minY) {
            this.world = world; this.ySize = ySize; this.minY = minY;
            this.bitsLen = ((ySize << 8) + 63) >> 6;
        }
    }

    private final ConcurrentHashMap<UUID, Fog> byPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, int[]> lastScan = new ConcurrentHashMap<>(); // {x,y,z,yawDeg,pitchDeg}
    private BukkitTask task;
    private final Random rng = new Random();

    public ExploredSetService(JavaPlugin plugin, Config config, RefreshScheduler scheduler, ExploredStore store) {
        this.plugin = plugin;
        this.config = config;
        this.scheduler = scheduler;
        this.store = store;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD, PERIOD);
    }

    public void stop() {
        if (task != null) task.cancel();
        byPlayer.clear();
        lastScan.clear();
    }

    public void clear() {
        byPlayer.clear();
        lastScan.clear();
    }

    /** Force the next scan to run (e.g. the player mined into new space). */
    public void invalidate(UUID id) {
        lastScan.remove(id);
    }

    // ---- packet-thread accessors (race-free: a published long[] is never mutated in place) ----

    public boolean hasSnapshot(UUID id, UUID world, int ySize) {
        Fog f = byPlayer.get(id);
        return f != null && f.world.equals(world) && f.ySize == ySize;
    }

    /** Cumulative explored bitset for one chunk (idx = (y&lt;&lt;8)|(z&lt;&lt;4)|x), or null. */
    public long[] bitsFor(UUID id, int cx, int cz) {
        Fog f = byPlayer.get(id);
        return f == null ? null : f.bits.get(key(cx, cz));
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Smallest absolute angular difference (degrees, 0..180) between two yaw values. */
    static int yawDiff(int a, int b) {
        int d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    // ---- main-thread scan ----

    private void tick() {
        if (!config.fogActive()) {
            if (!byPlayer.isEmpty()) { byPlayer.clear(); lastScan.clear(); }
            return;
        }
        int rayBudget = MAX_RAYS_PER_TICK;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            try {
                rayBudget -= compute(p, rayBudget);
            } catch (Exception ignored) {
                byPlayer.remove(p.getUniqueId());
            }
            if (rayBudget <= 0) break;
        }
        byPlayer.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        lastScan.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);

        // Periodic flush so a crash loses at most ~one interval of exploration.
        if (persistOn() && System.currentTimeMillis() - lastFlush >= FLUSH_INTERVAL_MS) {
            lastFlush = System.currentTimeMillis();
            for (Player p : plugin.getServer().getOnlinePlayers()) saveAsync(p.getUniqueId());
        }
    }

    // ---- persistence ----

    private boolean persistOn() {
        return store != null && config.fogPersist() && config.fogOfWar();
    }

    /** Load this player's saved set for their current world (async read + decode, main-thread merge). */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        loadAsync(e.getPlayer().getUniqueId(), e.getPlayer().getWorld().getUID());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        saveAsync(id);
        byPlayer.remove(id);
        lastScan.remove(id);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        saveAsync(id);                                  // save the world we just left (fog still holds it)
        byPlayer.remove(id);                            // compute() would reset anyway; drop now so load can seed
        lastScan.remove(id);
        loadAsync(id, e.getPlayer().getWorld().getUID());
    }

    private void loadAsync(UUID id, UUID world) {
        if (!persistOn()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                byte[] data = store.read(world, id);
                if (data == null) return;
                if (config.fogExpireDays() > 0) {
                    long ageMs = System.currentTimeMillis() - store.lastModified(world, id);
                    if (ageMs > config.fogExpireDays() * 86_400_000L) return;   // expired -> start empty
                }
                ExploredCodec.Decoded dec = ExploredCodec.decode(data);
                Bukkit.getScheduler().runTask(plugin, () -> seedFog(id, world, dec));
            } catch (Exception ignored) {
                // Corrupt / unreadable -> discard and start empty (the safe direction).
            }
        });
    }

    /** Merge a loaded set into the player's live fog (main thread). */
    private void seedFog(UUID id, UUID world, ExploredCodec.Decoded dec) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.getWorld().getUID().equals(world)) return;   // moved on while loading
        World w = p.getWorld();
        int ySize = w.getMaxHeight() - w.getMinHeight();
        Fog fog = byPlayer.get(id);
        if (fog == null || !fog.world.equals(world) || fog.ySize != ySize) {
            fog = new Fog(world, ySize, w.getMinHeight());
            byPlayer.put(id, fog);
        }
        if (dec.bitsLen != fog.bitsLen) return;         // world geometry changed -> can't reuse
        boolean any = false;
        for (Map.Entry<Long, long[]> e : dec.bits.entrySet()) {
            long ck = e.getKey();
            long[] add = e.getValue();
            long[] old = fog.bits.get(ck);
            if (old == null) {
                fog.bits.put(ck, add);
            } else {
                long[] merged = old.clone();
                for (int i = 0; i < fog.bitsLen; i++) merged[i] |= add[i];
                fog.bits.put(ck, merged);
            }
            scheduler.enqueue(id, (int) (ck >> 32), (int) ck);
            any = true;
        }
        if (any) lastScan.remove(id);                   // force a fresh scan so it re-sends/extends
    }

    private void saveAsync(UUID id) {
        if (!persistOn()) return;
        Fog fog = byPlayer.get(id);
        if (fog == null || fog.bits.isEmpty()) return;
        UUID world = fog.world;
        int bitsLen = fog.bitsLen;
        Map<Long, long[]> snapshot = new HashMap<>(fog.bits);   // immutable arrays -> safe off-thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                store.write(world, id, ExploredCodec.encode(snapshot, bitsLen));
            } catch (Exception ignored) {
            }
        });
    }

    /** Synchronous save of every online player's set — for plugin shutdown, when async tasks won't run. */
    public void saveAllSync() {
        if (!persistOn()) return;
        for (Map.Entry<UUID, Fog> e : byPlayer.entrySet()) {
            Fog fog = e.getValue();
            if (fog.bits.isEmpty()) continue;
            try {
                store.write(fog.world, e.getKey(), ExploredCodec.encode(new HashMap<>(fog.bits), fog.bitsLen));
            } catch (Exception ignored) {
            }
        }
    }

    /** Returns the number of sight rays cast for this player (0 if skipped). */
    private int compute(Player p, int rayBudget) {
        UUID id = p.getUniqueId();
        if (p.hasPermission("cadistchunkprocessing.bypass")) { byPlayer.remove(id); return 0; }
        World w = p.getWorld();
        if (!config.worldEnabled(w.getName())) { byPlayer.remove(id); return 0; }

        Location eye = p.getEyeLocation();
        int px = eye.getBlockX(), py = eye.getBlockY(), pz = eye.getBlockZ();
        int yawDeg = Math.floorMod((int) eye.getYaw(), 360);
        int pitchDeg = (int) eye.getPitch();
        boolean sightRays = config.fogSightRays();

        int[] prev = lastScan.get(id);
        boolean moved = prev == null
                || Math.abs(prev[0] - px) >= MOVE_THRESHOLD
                || Math.abs(prev[1] - py) >= MOVE_THRESHOLD
                || Math.abs(prev[2] - pz) >= MOVE_THRESHOLD;
        // A look change only matters when sight rays are on, and only past a small
        // threshold so fine mouse jitter doesn't trigger a full re-scan.
        boolean looked = sightRays && prev != null
                && (yawDiff(prev[3], yawDeg) >= LOOK_THRESHOLD_DEG
                    || Math.abs(prev[4] - pitchDeg) >= LOOK_THRESHOLD_DEG);
        if (prev != null && byPlayer.containsKey(id) && !moved && !looked) {
            return 0;   // hasn't moved enough or turned enough — set unchanged
        }

        int minY = w.getMinHeight(), maxY = w.getMaxHeight();
        int ySize = maxY - minY;

        Fog fog = byPlayer.get(id);
        if (fog == null || !fog.world.equals(w.getUID()) || fog.ySize != ySize) {
            fog = new Fog(w.getUID(), ySize, minY);     // fresh world (or first scan) -> reset exploration
            byPlayer.put(id, fog);
        }

        // Snapshot the loaded bubble around the player (main thread).
        int r = config.params().realRadius();
        int pcx = px >> 4, pcz = pz >> 4;
        Map<Long, ChunkSnapshot> snaps = new HashMap<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (w.isChunkLoaded(cx, cz)) {
                    snaps.put(key(cx, cz), w.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
                }
            }
        }

        // Cells newly marked this scan, per chunk. The marker skips cells at/above the
        // column's surface: only sub-surface cave air is ever hidden by the engine, so
        // marking open sky changes nothing visible yet would needlessly re-send chunks
        // (the main cause of FPS drops when looking around outdoors).
        final int bitsLen = fog.bitsLen;
        Map<Long, long[]> scan = new HashMap<>();
        final int fMinY = minY, fYSize = ySize;
        final Map<Long, ChunkSnapshot> fSnaps = snaps;
        SightMarch.Visitor marker = (x, y, z) -> {
            ChunkSnapshot s = fSnaps.get(key(x >> 4, z >> 4));
            if (s == null || y >= s.getHighestBlockYAt(x & 15, z & 15)) return;
            mark(scan, bitsLen, x, y, z, fMinY, fYSize);
        };

        // --- Body source: connected air within the live radius (always explored). ---
        // The bubble depends only on POSITION, not look direction, and the explored set
        // is cumulative — so when the player only turned we skip the flood entirely.
        if (moved) {
            bodyFlood(snaps, px, p.getLocation().getBlockY(), pz, minY, maxY, marker);
        }

        // --- Sight source: rays from the eye (only when sight rays are enabled). ---
        int rays = 0;
        if (sightRays) {
            rays = Math.min(config.fogRaysPerScan(), Math.max(0, rayBudget));
            double dist = config.fogRayDistance();
            SightMarch.BlockAccess access = (x, y, z) -> sightTransparent(fSnaps, x, y, z, fMinY, maxY);
            castRays(access, marker, eye, rays, dist);
        }

        // --- Merge the scan into the cumulative set and reveal ONLY the changed blocks. ---
        // The right fix (vs. re-sending whole chunks): for each cell that flips
        // hidden -> revealed, push its real block state to the player with a single
        // block-change. The client updates a handful of blocks instead of re-meshing
        // the entire 24-section column, so looking/walking around a frontier no longer
        // tanks FPS. A chunk with a huge first reveal (a whole cavern at once) falls
        // back to one full chunk re-send, which is cheaper than thousands of updates.
        boolean blockUpdates = config.fogBlockUpdates();
        int budget = MAX_BLOCK_UPDATES_PER_SCAN;
        for (Map.Entry<Long, long[]> e : scan.entrySet()) {
            long ck = e.getKey();
            long[] add = e.getValue();
            long[] old = fog.bits.get(ck);

            // Count the cells that actually flip to revealed this scan.
            int revealed = 0;
            for (int i = 0; i < bitsLen; i++) {
                revealed += Long.bitCount(add[i] & ~(old == null ? 0L : old[i]));
            }
            if (revealed == 0) continue;                 // nothing new in this chunk

            long[] merged;
            if (old == null) {
                merged = add;
            } else {
                merged = old.clone();
                for (int i = 0; i < bitsLen; i++) merged[i] |= add[i];
            }
            fog.bits.put(ck, merged);

            if (blockUpdates && revealed <= PER_CHUNK_BLOCK_CAP && revealed <= budget) {
                budget -= sendRevealedBlocks(p, w, fSnaps, ck, add, old, bitsLen, minY);
            } else {
                scheduler.enqueue(id, (int) (ck >> 32), (int) ck);   // big/disabled -> one full re-send
            }
        }

        evictIfOverCap(fog, pcx, pcz);
        lastScan.put(id, new int[]{px, py, pz, yawDeg, pitchDeg});
        return rays;
    }

    /**
     * Push the real block state of each newly-revealed cell ({@code add & ~old}) to the
     * player as a single block-change — the client updates a few blocks instead of
     * re-meshing the whole chunk column. Returns the number of updates sent.
     */
    private int sendRevealedBlocks(Player p, World w, Map<Long, ChunkSnapshot> snaps,
                                   long ck, long[] add, long[] old, int bitsLen, int minY) {
        ChunkSnapshot snap = snaps.get(ck);
        if (snap == null) {                              // no snapshot -> fall back to a full re-send
            scheduler.enqueue(p.getUniqueId(), (int) (ck >> 32), (int) ck);
            return 0;
        }
        int baseX = (int) (ck >> 32) << 4, baseZ = (int) ck << 4;
        int sent = 0;
        for (int wi = 0; wi < bitsLen; wi++) {
            long bitsNew = add[wi] & ~(old == null ? 0L : old[wi]);
            while (bitsNew != 0L) {
                int idx = (wi << 6) | Long.numberOfTrailingZeros(bitsNew);
                bitsNew &= bitsNew - 1;
                int lx = idx & 15, lz = (idx >> 4) & 15, wy = (idx >> 8) + minY;
                org.bukkit.block.data.BlockData data = snap.getBlockData(lx, wy, lz);
                p.sendBlockChange(new Location(w, baseX + lx, wy, baseZ + lz), data);
                sent++;
            }
        }
        return sent;
    }

    /** Mark one absolute cell explored in the per-scan bitset. */
    private static void mark(Map<Long, long[]> scan, int bitsLen, int wx, int wy, int wz, int minY, int ySize) {
        int localY = wy - minY;
        if (localY < 0 || localY >= ySize) return;
        long ck = key(wx >> 4, wz >> 4);
        long[] arr = scan.get(ck);
        if (arr == null) { arr = new long[bitsLen]; scan.put(ck, arr); }
        int idx = (localY << 8) | ((wz & 15) << 4) | (wx & 15);
        arr[idx >> 6] |= 1L << (idx & 63);
    }

    // Reused body-flood scratch (the scan task is single-threaded). Reset after each
    // flood by clearing only the cells actually visited, so cost stays proportional to
    // the air around the player, not to the configured radius cubed.
    private boolean[] bodySeen = new boolean[0];
    private int[] bodyQueue = new int[0];

    /**
     * Flood the air/fluid the player's body can reach within the configured "live
     * radius" ({@code fog-body-radius}); every cell is marked explored, so air within
     * that radius is always real. A 3-D sphere leash, bounded by its own bounding box.
     */
    private void bodyFlood(Map<Long, ChunkSnapshot> snaps, int px, int feetY, int pz,
                           int minY, int maxY, SightMarch.Visitor marker) {
        int leash = config.fogBodyRadius();
        int lo = Math.max(minY, feetY - leash), hi = Math.min(maxY - 1, feetY + leash + 1);
        int x0 = px - leash, z0 = pz - leash;
        int span = leash * 2 + 1;
        int h = hi - lo + 1;
        if (h <= 0) return;
        int total = span * span * h;
        if (bodySeen.length < total) { bodySeen = new boolean[total]; bodyQueue = new int[total]; }
        boolean[] seen = bodySeen;
        int[] queue = bodyQueue;
        long leashSq = (long) leash * leash;
        int head = 0, tail = 0;
        for (int yy = feetY; yy <= feetY + 1 && yy <= hi; yy++) {
            int rel = rel(px - x0, yy - lo, pz - z0, span);
            if (rel >= 0 && !seen[rel] && passable(snaps, px, yy, pz)) {
                seen[rel] = true; queue[tail++] = rel;
            }
        }
        while (head < tail) {
            int rel = queue[head++];
            int rx = rel % span, t = rel / span, rz = t % span, ry = t / span;
            int wx = x0 + rx, wy = lo + ry, wz = z0 + rz;
            marker.mark(wx, wy, wz);
            if (rx > 0)        tail = relax(snaps, seen, queue, rel - 1,           wx - 1, wy, wz, px, feetY, pz, leashSq, tail);
            if (rx < span - 1) tail = relax(snaps, seen, queue, rel + 1,           wx + 1, wy, wz, px, feetY, pz, leashSq, tail);
            if (rz > 0)        tail = relax(snaps, seen, queue, rel - span,        wx, wy, wz - 1, px, feetY, pz, leashSq, tail);
            if (rz < span - 1) tail = relax(snaps, seen, queue, rel + span,        wx, wy, wz + 1, px, feetY, pz, leashSq, tail);
            if (ry > 0)        tail = relax(snaps, seen, queue, rel - span * span, wx, wy - 1, wz, px, feetY, pz, leashSq, tail);
            if (ry < h - 1)    tail = relax(snaps, seen, queue, rel + span * span, wx, wy + 1, wz, px, feetY, pz, leashSq, tail);
        }
        for (int i = 0; i < tail; i++) seen[queue[i]] = false;   // reset scratch for the next player/scan
    }

    private int relax(Map<Long, ChunkSnapshot> snaps, boolean[] seen, int[] queue, int nRel,
                      int wx, int wy, int wz, int px, int py, int pz, long leashSq, int tail) {
        if (seen[nRel]) return tail;
        long dx = wx - px, dy = wy - py, dz = wz - pz;
        if (dx * dx + dy * dy + dz * dz > leashSq) return tail;
        if (passable(snaps, wx, wy, wz)) {
            seen[nRel] = true;
            queue[tail++] = nRel;
        }
        return tail;
    }

    private static int rel(int rx, int ry, int rz, int span) {
        if (rx < 0 || rx >= span || rz < 0 || rz >= span || ry < 0) return -1;
        return ry * (span * span) + rz * span + rx;
    }

    /** Cast the sight rays: most jittered over the look frustum, the rest over the sphere. */
    private void castRays(SightMarch.BlockAccess access, SightMarch.Visitor marker,
                          Location eye, int rays, double dist) {
        if (rays <= 0) return;
        double ox = eye.getX(), oy = eye.getY(), oz = eye.getZ();
        Vector f = eye.getDirection();
        if (f.lengthSquared() < 1e-9) f = new Vector(0, 0, 1);
        f.normalize();
        // Orthonormal basis around the look vector.
        Vector up = Math.abs(f.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector right = f.clone().crossProduct(up).normalize();
        Vector u = right.clone().crossProduct(f).normalize();
        double cosMax = Math.cos(Math.toRadians(FRUSTUM_HALF_ANGLE));
        int frustum = (int) (rays * FRUSTUM_FRACTION);

        for (int i = 0; i < rays; i++) {
            double dx, dy, dz;
            if (i < frustum) {
                double c = cosMax + rng.nextDouble() * (1.0 - cosMax);   // cos of polar angle from look
                double s = Math.sqrt(Math.max(0.0, 1.0 - c * c));
                double phi = rng.nextDouble() * 2.0 * Math.PI;
                double rc = s * Math.cos(phi), uc = s * Math.sin(phi);
                dx = f.getX() * c + right.getX() * rc + u.getX() * uc;
                dy = f.getY() * c + right.getY() * rc + u.getY() * uc;
                dz = f.getZ() * c + right.getZ() * rc + u.getZ() * uc;
            } else {
                double c = rng.nextDouble() * 2.0 - 1.0;
                double s = Math.sqrt(Math.max(0.0, 1.0 - c * c));
                double phi = rng.nextDouble() * 2.0 * Math.PI;
                dx = s * Math.cos(phi); dy = c; dz = s * Math.sin(phi);
            }
            SightMarch.cast(access, marker, ox, oy, oz, dx, dy, dz, dist, true);
        }
    }

    /** Sight passes through this cell (air / fluid / glass / leaves / non-occluding); edge = opaque stop. */
    private boolean sightTransparent(Map<Long, ChunkSnapshot> snaps, int wx, int wy, int wz, int minY, int maxY) {
        if (wy < minY || wy >= maxY) return false;
        ChunkSnapshot s = snaps.get(key(wx >> 4, wz >> 4));
        if (s == null) return false;
        Material m = s.getBlockType(wx & 15, wy, wz & 15);
        return !m.isOccluding();
    }

    /** A cell the player's body can occupy: air or a fluid. Unloaded chunks read as solid. */
    private boolean passable(Map<Long, ChunkSnapshot> snaps, int wx, int wy, int wz) {
        ChunkSnapshot s = snaps.get(key(wx >> 4, wz >> 4));
        if (s == null) return false;
        Material m = s.getBlockType(wx & 15, wy, wz & 15);
        if (m.isAir()) return true;
        return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN;
    }

    /** Keep memory bounded: when over the per-player cap, drop the chunks farthest from the player. */
    private void evictIfOverCap(Fog fog, int pcx, int pcz) {
        int cap = config.fogMaxChunks();
        int over = fog.bits.size() - cap;
        if (over <= 0) return;
        long farKey = 0;
        // Cheap repeated-max eviction: remove the single farthest chunk per overflow.
        for (int n = 0; n < over; n++) {
            long worst = Long.MIN_VALUE;
            boolean found = false;
            for (Long k : fog.bits.keySet()) {
                int cx = (int) (k >> 32), cz = (int) (long) k;
                long d = (long) (cx - pcx) * (cx - pcx) + (long) (cz - pcz) * (cz - pcz);
                if (d > worst) { worst = d; farKey = k; found = true; }
            }
            if (found) fog.bits.remove(farKey);
        }
    }
}
