package net.cadistenmc.cadistchunkprocessing;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * CadistChunkProcessing Pro — "solidify, never void" bandwidth + anti-xray
 * chunk processing for Paper 1.21.11. Uses the installed PacketEvents plugin's
 * shared API (declared in plugin.yml as a dependency); it does not own the
 * PacketEvents lifecycle, avoiding the double-init fragility of v8.
 */
public final class CadistChunkProcessingPro extends JavaPlugin {

    private static final TextColor MAUVE = TextColor.color(0xCBA6F7);
    private static final TextColor GREEN = TextColor.color(0xA6E3A1);
    private static final TextColor RED = TextColor.color(0xF38BA8);
    private static final TextColor SUB = TextColor.color(0xA6ADC8);

    private Config config;
    private WorldMeta worldMeta;
    private BorderCache borderCache;
    private ProcessedChunkCache chunkCache;
    private RegistryBlockClassifier classifier;
    private BandwidthMonitor monitor;
    private PlayerTracker tracker;
    private RefreshScheduler scheduler;
    private Gui gui;
    private PacketListenerCommon registered;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = new Config(this);

        WorldMeta.initIds();
        worldMeta = new WorldMeta();
        for (World w : getServer().getWorlds()) worldMeta.refresh(w);

        classifier = new RegistryBlockClassifier();
        classifier.setExtraOres(config.extraOres());
        borderCache = new BorderCache();
        chunkCache = new ProcessedChunkCache();
        monitor = new BandwidthMonitor(this);
        scheduler = new RefreshScheduler(this, config);
        scheduler.start();
        tracker = new PlayerTracker(config, worldMeta, scheduler);
        gui = new Gui(this);

        ChunkPacketInterceptor interceptor = new ChunkPacketInterceptor(
                this, config, worldMeta, tracker, borderCache, chunkCache, monitor, classifier);

        var pm = getServer().getPluginManager();
        pm.registerEvents(tracker, this);
        pm.registerEvents(gui, this);
        pm.registerEvents(new ChunkDirtyListener(borderCache, chunkCache), this);

        registered = PacketEvents.getAPI().getEventManager().registerListener(interceptor);

        getLogger().info("Enabled — mode=" + config.mode()
                + " cave-hiding=" + config.caveHiding()
                + " ore-hiding=" + config.oreHiding());
    }

    @Override
    public void onDisable() {
        if (registered != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(registered);
        }
        if (scheduler != null) scheduler.stop();
        if (borderCache != null) borderCache.clear();
    }

    // ---- shared by GUI / commands ----

    public Config cfg() { return config; }
    public BandwidthMonitor getMonitor() { return monitor; }

    /** Clear seam + processed caches and gradually re-send nearby chunks so changes apply live. */
    public void applyChanges() {
        borderCache.clear();
        chunkCache.clear();
        if (classifier != null) classifier.setExtraOres(config.extraOres());
        int r = config.params().caveRenderDistance();
        for (Player p : getServer().getOnlinePlayers()) {
            int pcx = p.getLocation().getBlockX() >> 4;
            int pcz = p.getLocation().getBlockZ() >> 4;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    scheduler.enqueue(p.getUniqueId(), pcx + dx, pcz + dz);
        }
    }

    // ---- command ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, "/cadistchunk gui | stats | bar | mode <name> | cave | ore | antibase | reload", SUB);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "gui" -> {
                if (sender instanceof Player p) gui.open(p);
                else send(sender, "Players only.", RED);
            }
            case "stats" -> {
                send(sender, "CadistChunkProcessing Pro", MAUVE);
                send(sender, "  mode: " + config.mode().display
                        + "  cave=" + config.caveHiding() + "  ore=" + config.oreHiding(), SUB);
                send(sender, String.format("  bandwidth saved: %.1f%%  (%s this session)",
                        monitor.savingsPercent(), monitor.savedHuman()), GREEN);
                send(sender, "  chunks/sec: " + monitor.chunksPerSecond()
                        + "  processed: " + monitor.packetsTotal()
                        + "  modified: " + monitor.packetsModified(), SUB);
                send(sender, "  chunk-cache: " + (config.chunkCache() ? "on" : "off (live)")
                        + "  cached: " + chunkCache.size(), SUB);
                send(sender, String.format("  by tier — REAL %.0f%% (%d), SHELL %.0f%% (%d), DEEP %.0f%% (%d)",
                        monitor.tierSavingsPercent(net.cadistenmc.cadistchunkprocessing.engine.Tier.REAL),
                        monitor.tierCount(net.cadistenmc.cadistchunkprocessing.engine.Tier.REAL),
                        monitor.tierSavingsPercent(net.cadistenmc.cadistchunkprocessing.engine.Tier.SHELL),
                        monitor.tierCount(net.cadistenmc.cadistchunkprocessing.engine.Tier.SHELL),
                        monitor.tierSavingsPercent(net.cadistenmc.cadistchunkprocessing.engine.Tier.DEEP),
                        monitor.tierCount(net.cadistenmc.cadistchunkprocessing.engine.Tier.DEEP)), SUB);
                send(sender, "  (REAL is sent 0% on purpose for digging safety — lower real-radius to raise the average)", SUB);
                send(sender, "  ores hidden: " + monitor.oresHidden()
                        + "  cave blocks solidified: " + monitor.blocksSolidified(), SUB);
            }
            case "bar" -> {
                if (sender instanceof Player p) {
                    monitor.toggleBar(p);
                    send(sender, "Bandwidth bar " + (monitor.hasBar(p) ? "enabled." : "disabled."), GREEN);
                } else send(sender, "Players only.", RED);
            }
            case "mode" -> {
                if (args.length < 2) { send(sender, "Usage: /cadistchunk mode <BALANCED|MAX_SAVINGS|GENEROUS>", SUB); break; }
                Mode m = Mode.fromString(args[1], config.mode());
                config.setMode(m);
                applyChanges();
                send(sender, "Mode set to " + m.display + ".", GREEN);
            }
            case "cave" -> {
                config.setCaveHiding(!config.caveHiding());
                applyChanges();
                send(sender, "Cave hiding " + (config.caveHiding() ? "enabled." : "disabled."), GREEN);
            }
            case "ore" -> {
                config.setOreHiding(!config.oreHiding());
                applyChanges();
                send(sender, "Ore hiding " + (config.oreHiding() ? "enabled." : "disabled."), GREEN);
            }
            case "antibase" -> {
                config.setAntiBaseFinder(!config.antiBaseFinder());
                applyChanges();
                send(sender, "Anti-Base Finder " + (config.antiBaseFinder() ? "enabled." : "disabled."), GREEN);
            }
            case "reload" -> {
                config.reload();
                applyChanges();
                send(sender, "Reloaded.", GREEN);
            }
            default -> send(sender, "Unknown subcommand.", RED);
        }
        return true;
    }

    private static void send(CommandSender s, String msg, TextColor color) {
        s.sendMessage(Component.text(msg, color));
    }
}
