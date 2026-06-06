package net.cadistenmc.cadistchunkprocessing;

import net.cadistenmc.cadistchunkprocessing.engine.Tier;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Cumulative, <em>measured-byte</em> statistics (modelled on the real 1.21
 * paletted-section wire format by {@code ChunkProcessor.estimateBytes}) plus an
 * optional per-player BossBar. Unlike v8 this reflects actual packet size, not a
 * block count, so the reported percentage is honest.
 */
public final class BandwidthMonitor {

    // Catppuccin Mocha green / surface.
    private static final TextColor GREEN = TextColor.color(0xA6E3A1);

    private final JavaPlugin plugin;

    private final AtomicLong bytesBefore = new AtomicLong();
    private final AtomicLong bytesAfter = new AtomicLong();
    private final AtomicLong packetsTotal = new AtomicLong();
    private final AtomicLong packetsModified = new AtomicLong();
    private final AtomicLong oresHidden = new AtomicLong();
    private final AtomicLong blocksSolidified = new AtomicLong();

    // Per-tier (REAL/SHELL/DEEP) breakdown so the aggregate % can be explained:
    // REAL is sent 0% by design, so a large near-bubble drags the average down.
    private final AtomicLongArray tierBefore = new AtomicLongArray(3);
    private final AtomicLongArray tierAfter = new AtomicLongArray(3);
    private final AtomicLongArray tierCount = new AtomicLongArray(3);

    private volatile long chunksPerSecond = 0;
    private volatile long lastPacketSample = 0;

    private final Set<Player> barPlayers = ConcurrentHashMap.newKeySet();
    private final Map<Player, BossBar> bars = new ConcurrentHashMap<>();

    public BandwidthMonitor(JavaPlugin plugin) {
        this.plugin = plugin;
        startUpdater();
    }

    public void addPacket(Tier tier, long before, long after, long ores, long solidified, boolean modified) {
        bytesBefore.addAndGet(before);
        bytesAfter.addAndGet(after);
        oresHidden.addAndGet(ores);
        blocksSolidified.addAndGet(solidified);
        packetsTotal.incrementAndGet();
        if (modified) packetsModified.incrementAndGet();

        int t = tier.ordinal();
        tierBefore.addAndGet(t, before);
        tierAfter.addAndGet(t, after);
        tierCount.incrementAndGet(t);
    }

    /** Saving % for one tier, or 0 if nothing seen yet. */
    public double tierSavingsPercent(Tier tier) {
        long b = tierBefore.get(tier.ordinal());
        if (b == 0) return 0.0;
        return Math.max(0.0, (b - tierAfter.get(tier.ordinal())) * 100.0 / b);
    }

    public long tierCount(Tier tier) {
        return tierCount.get(tier.ordinal());
    }

    public double savingsPercent() {
        long b = bytesBefore.get();
        if (b == 0) return 0.0;
        return Math.max(0.0, (b - bytesAfter.get()) * 100.0 / b);
    }

    /** Cumulative block-data bytes saved this session (estimate, light excluded). */
    public long savedBytes() {
        return Math.max(0L, bytesBefore.get() - bytesAfter.get());
    }

    /** Human-readable cumulative saving, e.g. "1.4 GB", "812.0 MB". */
    public String savedHuman() {
        return humanBytes(savedBytes());
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    public long packetsTotal()    { return packetsTotal.get(); }
    public long packetsModified() { return packetsModified.get(); }
    public long oresHidden()      { return oresHidden.get(); }
    public long blocksSolidified(){ return blocksSolidified.get(); }
    public long chunksPerSecond() { return chunksPerSecond; }
    public long bytesBefore()     { return bytesBefore.get(); }
    public long bytesAfter()      { return bytesAfter.get(); }

    public void reset() {
        bytesBefore.set(0); bytesAfter.set(0);
        packetsTotal.set(0); packetsModified.set(0);
        oresHidden.set(0); blocksSolidified.set(0);
        for (int i = 0; i < 3; i++) { tierBefore.set(i, 0); tierAfter.set(i, 0); tierCount.set(i, 0); }
        lastPacketSample = 0; chunksPerSecond = 0;
    }

    public boolean hasBar(Player p) { return barPlayers.contains(p); }

    public void toggleBar(Player p) {
        BossBar existing = bars.remove(p);
        if (existing != null) {
            p.hideBossBar(existing);
            barPlayers.remove(p);
            return;
        }
        BossBar bar = BossBar.bossBar(barName(0.0), 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        bars.put(p, bar);
        barPlayers.add(p);
        p.showBossBar(bar);
    }

    private Component barName(double pct) {
        return Component.text(String.format("Bandwidth saved: %.1f%%  (%s, %d chunks/s)",
                pct, savedHuman(), chunksPerSecond), GREEN);
    }

    private void startUpdater() {
        new BukkitRunnable() {
            @Override public void run() {
                long now = packetsTotal.get();
                chunksPerSecond = Math.max(0, now - lastPacketSample);
                lastPacketSample = now;

                if (barPlayers.isEmpty()) return;
                double pct = savingsPercent();
                float progress = (float) Math.min(1.0, pct / 100.0);
                List<Player> stale = new ArrayList<>();
                for (Player p : barPlayers) {
                    if (!p.isOnline()) { stale.add(p); continue; }
                    BossBar bar = bars.get(p);
                    if (bar != null) {
                        bar.progress(progress);
                        bar.name(barName(pct));
                    }
                }
                // Remove stale players after iteration is complete to avoid
                // mutating the set while iterating (safe for ConcurrentHashMap.newKeySet,
                // but hideBossBar on an offline player risks NPE on some Paper builds).
                for (Player p : stale) {
                    barPlayers.remove(p);
                    bars.remove(p);
                    // Do not call hideBossBar on an offline player; the bar expires naturally.
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }
}
