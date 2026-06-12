package net.cadistenmc.cadistchunkprocessing;

import net.cadistenmc.cadistchunkprocessing.engine.ModeParams;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Typed view over config.yml, reloaded atomically. All fields are read on the
 * packet thread, so values are stored in plain finals snapshotted at reload.
 */
public final class Config {

    private final JavaPlugin plugin;

    private volatile Mode mode = Mode.BALANCED;
    private volatile boolean caveHiding = true;
    private volatile boolean oreHiding = true;
    private volatile boolean hideAllOres = false;
    private volatile boolean hideBlockEntities = true;
    private volatile boolean antiBaseFinder = false;
    private volatile boolean reachabilityOres = false;
    private volatile boolean reachabilityCaves = false;
    private volatile boolean hideSealedCaves = false;
    private volatile boolean surfaceEntrances = false;
    private volatile boolean fogOfWar = false;
    private volatile int fogRayDistance = 64;
    private volatile int fogRaysPerScan = 96;
    private volatile int fogMaxChunks = 50000;
    private volatile boolean chunkCache = true;
    private volatile boolean collapseBiomes = true;
    private volatile boolean verticalCulling = true;
    private volatile int verticalMargin = 48;
    private volatile int verticalResendBlocks = 16;
    private volatile int oreRevealRadius = 16;
    private volatile int revealDistance = 0;
    private volatile boolean debug = false;
    private volatile List<String> extraOres = List.of();
    private volatile int refreshPerTick = 6;
    private volatile int joinRawSeconds = 0;
    private volatile List<String> enabledWorlds = List.of("*");
    private volatile Map<Mode, ModeParams> modeParams = defaults();

    public Config(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        var c = plugin.getConfig();

        this.mode = Mode.fromString(c.getString("mode", "BALANCED"), Mode.BALANCED);
        this.caveHiding = c.getBoolean("cave-hiding", true);
        this.oreHiding = c.getBoolean("ore-hiding", true);
        this.hideAllOres = c.getBoolean("hide-all-ores", false);
        this.hideBlockEntities = c.getBoolean("hide-block-entities", true);
        this.antiBaseFinder = c.getBoolean("anti-base-finder", false);
        this.reachabilityOres = c.getBoolean("reachability-ores", false);
        this.reachabilityCaves = c.getBoolean("reachability-caves", false);
        this.hideSealedCaves = c.getBoolean("hide-sealed-caves", false);
        this.surfaceEntrances = c.getBoolean("surface-entrances", false);
        this.fogOfWar = c.getBoolean("fog-of-war", false);
        this.fogRayDistance = Math.max(8, c.getInt("fog-ray-distance", 64));
        this.fogRaysPerScan = Math.max(8, c.getInt("fog-rays-per-scan", 96));
        this.fogMaxChunks = Math.max(256, c.getInt("fog-max-chunks", 50000));
        this.chunkCache = c.getBoolean("chunk-cache", true);
        this.collapseBiomes = c.getBoolean("collapse-biomes", true);
        this.verticalCulling = c.getBoolean("vertical-culling", true);
        this.verticalMargin = Math.max(8, c.getInt("vertical-margin", 48));
        this.verticalResendBlocks = Math.max(4, c.getInt("vertical-resend-blocks", 16));
        this.oreRevealRadius = Math.max(0, c.getInt("ore-reveal-radius", 16));
        this.revealDistance = Math.max(0, c.getInt("reveal-distance", 0));
        this.debug = c.getBoolean("debug", false);

        List<String> extra = c.getStringList("extra-ores");
        this.extraOres = (extra == null) ? List.of() : List.copyOf(extra);
        this.refreshPerTick = Math.max(1, c.getInt("refresh-per-tick", 6));
        this.joinRawSeconds = Math.max(0, c.getInt("join-raw-seconds", 0));

        List<String> worlds = c.getStringList("enabled-worlds");
        this.enabledWorlds = (worlds == null || worlds.isEmpty()) ? List.of("*") : List.copyOf(worlds);

        EnumMap<Mode, ModeParams> map = new EnumMap<>(Mode.class);
        Map<Mode, ModeParams> def = defaults();
        for (Mode m : Mode.values()) {
            ConfigurationSection s = c.getConfigurationSection("modes." + m.name());
            ModeParams d = def.get(m);
            if (s == null) {
                map.put(m, d);
            } else {
                map.put(m, new ModeParams(
                        s.getInt("real-radius", d.realRadius()),
                        s.getInt("cave-render-distance", d.caveRenderDistance()),
                        s.getInt("entrance-shell-depth", d.entranceShellDepth()),
                        s.getInt("homogenize-below", d.homogenizeBelow()),
                        s.getBoolean("rock-collapse", d.rockCollapse()),
                        s.getInt("reveal-hysteresis", d.revealHysteresis())
                ));
            }
        }
        this.modeParams = map;
    }

    private static Map<Mode, ModeParams> defaults() {
        EnumMap<Mode, ModeParams> m = new EnumMap<>(Mode.class);
        // rock-collapse only homogenises sections fully below the lowest surface
        // point, which are never visible until revealed real on approach — so it
        // has no visual cost and is on for the two savings-oriented modes.
        m.put(Mode.BALANCED,    new ModeParams(4, 10, 6, 8, true, 2));
        m.put(Mode.MAX_SAVINGS, new ModeParams(2, 2, 0, 2, true, 2));   // no shell ring: collapse everything past the real bubble
        m.put(Mode.GENEROUS,    new ModeParams(6, 14, 10, 24, false, 2));
        return m;
    }

    // ---- getters ----
    public Mode mode() { return mode; }
    public ModeParams params() { return modeParams.get(mode); }
    public ModeParams params(Mode m) { return modeParams.get(m); }
    public boolean caveHiding() { return caveHiding; }
    public boolean oreHiding() { return oreHiding; }
    public boolean hideAllOres() { return hideAllOres; }
    public boolean hideBlockEntities() { return hideBlockEntities; }
    public boolean antiBaseFinder() { return antiBaseFinder; }
    public boolean reachabilityOres() { return reachabilityOres; }
    public boolean reachabilityCaves() { return reachabilityCaves; }
    public boolean hideSealedCaves() { return hideSealedCaves; }
    public boolean surfaceEntrances() { return surfaceEntrances; }
    public boolean fogOfWar() { return fogOfWar; }
    public int fogRayDistance() { return fogRayDistance; }
    public int fogRaysPerScan() { return fogRaysPerScan; }
    public int fogMaxChunks() { return fogMaxChunks; }
    /** Any reachability feature active -> the scanner needs to run. */
    public boolean reachabilityActive() { return reachabilityOres || reachabilityCaves || hideSealedCaves; }
    /** Fog of war active -> the explored-set scanner needs to run. */
    public boolean fogActive() { return fogOfWar; }
    public boolean chunkCache() { return chunkCache; }
    public boolean collapseBiomes() { return collapseBiomes; }
    public boolean verticalCulling() { return verticalCulling; }
    public int verticalMargin() { return verticalMargin; }
    public int verticalResendBlocks() { return verticalResendBlocks; }
    public List<String> extraOres() { return extraOres; }
    public int oreRevealRadius() { return oreRevealRadius; }
    /** Cap (blocks, 3D) on how far reachability reveals around the player; 0 = unlimited. */
    public int revealDistance() { return revealDistance; }
    public boolean debug() { return debug; }
    public int refreshPerTick() { return refreshPerTick; }
    public int joinRawSeconds() { return joinRawSeconds; }

    public boolean worldEnabled(String name) {
        return enabledWorlds.contains("*") || enabledWorlds.contains(name);
    }

    // ---- live setters (persist + reload) ----
    public void setMode(Mode m) { set("mode", m.name()); }
    public void setCaveHiding(boolean v) { set("cave-hiding", v); }
    public void setOreHiding(boolean v) { set("ore-hiding", v); }
    public void setHideAllOres(boolean v) { set("hide-all-ores", v); }
    public void setOreRevealRadius(int v) { set("ore-reveal-radius", v); }
    public void setRevealDistance(int v) { set("reveal-distance", v); }
    public void setHideBlockEntities(boolean v) { set("hide-block-entities", v); }
    public void setAntiBaseFinder(boolean v) { set("anti-base-finder", v); }
    public void setReachabilityOres(boolean v) { set("reachability-ores", v); }
    public void setReachabilityCaves(boolean v) { set("reachability-caves", v); }
    public void setHideSealedCaves(boolean v) { set("hide-sealed-caves", v); }
    public void setSurfaceEntrances(boolean v) { set("surface-entrances", v); }
    public void setFogOfWar(boolean v) { set("fog-of-war", v); }
    public void setFogRayDistance(int v) { set("fog-ray-distance", v); }
    public void setChunkCache(boolean v) { set("chunk-cache", v); }
    public void setVerticalCulling(boolean v) { set("vertical-culling", v); }
    public void setVerticalMargin(int v) { set("vertical-margin", v); }

    /** Adjust one knob of the current mode, clamped to a sane range. */
    public void setCurrentModeInt(String key, int value) {
        set("modes." + mode.name() + "." + key, value);
    }

    public void setCurrentModeBool(String key, boolean value) {
        set("modes." + mode.name() + "." + key, value);
    }

    /** Add or remove a world from enabled-worlds (no-op while the list is "*"). */
    public void toggleWorld(String name) {
        var list = new java.util.ArrayList<>(enabledWorlds);
        if (list.contains("*")) return;
        if (!list.remove(name)) list.add(name);
        plugin.getConfig().set("enabled-worlds", list);
        plugin.saveConfig();
        reload();
    }

    private void set(String path, Object value) {
        plugin.getConfig().set(path, value);
        plugin.saveConfig();
        reload();
    }
}
