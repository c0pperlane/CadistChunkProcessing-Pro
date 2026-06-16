package net.cadistenmc.cadistchunkprocessing;

import net.cadistenmc.cadistchunkprocessing.engine.ModeParams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Catppuccin-Mocha control panel. The main page shows the modern, recommended kit
 * (fog of war + anti-base + ore hiding + the bandwidth tiers); the legacy hiders
 * that fog of war supersedes live on an "Outdated" sub-page reached from the
 * bottom-right corner. All changes persist and apply to newly sent chunks
 * immediately (nearby chunks are re-sent via {@link CadistChunkProcessingPro#applyChanges()}).
 */
public final class Gui implements Listener {

    // Catppuccin Mocha palette.
    private static final TextColor MAUVE = TextColor.color(0xCBA6F7);
    private static final TextColor GREEN = TextColor.color(0xA6E3A1);
    private static final TextColor RED = TextColor.color(0xF38BA8);
    private static final TextColor YELLOW = TextColor.color(0xF9E2AF);
    private static final TextColor BLUE = TextColor.color(0x89B4FA);
    private static final TextColor TEXT = TextColor.color(0xCDD6F4);
    private static final TextColor SUB = TextColor.color(0xA6ADC8);
    private static final TextColor OVERLAY = TextColor.color(0x6C7086);

    // ---- main page (modern kit) ----
    private static final int FOG = 10, FOG_DIST = 11, FOG_BODY = 12, FOG_SIGHT = 13;
    private static final int ANTI_BASE = 14, ENTRANCES = 16;
    private static final int ORE = 19, HIDE_ALL = 20, BLOCK_ENT = 21, ORE_RADIUS = 22;
    private static final int CAVE = 24, VCULL = 25, VMARGIN = 26;
    private static final int REAL_R = 28, RENDER = 29, SHELL = 30, HOMO = 31, ROCK = 32, MODE = 33, CACHE = 34;
    private static final int P_BAL = 38, P_MAX = 40, P_GEN = 42, WORLD = 44;
    private static final int STATS = 45, CLOSE = 49, OUTDATED = 53;

    // ---- outdated page (superseded by fog of war) ----
    private static final int O_REACH = 20, O_REACH_CAVES = 22, O_SEALED = 24;
    private static final int O_REVEAL_DIST = 30, O_INFO = 4;
    private static final int O_BACK = 45, O_CLOSE = 49;

    private final CadistChunkProcessingPro plugin;

    public Gui(CadistChunkProcessingPro plugin) {
        this.plugin = plugin;
    }

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        boolean outdated;
        @Override public Inventory getInventory() { return inv; }
    }

    public void open(Player player) {
        openPage(player, false);
    }

    private void openPage(Player player, boolean outdated) {
        Holder holder = new Holder();
        holder.outdated = outdated;
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("CadistChunkProcessing Pro" + (outdated ? " — Outdated" : ""),
                        MAUVE, TextDecoration.BOLD));
        holder.inv = inv;
        if (outdated) renderOutdated(inv); else renderMain(inv, player);
        player.openInventory(inv);
    }

    // ---- main page ----

    private void renderMain(Inventory inv, Player player) {
        Config c = plugin.cfg();
        ModeParams p = c.params();
        ItemStack pane = pane();
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);

        // Headline: fog of war + its two ranges.
        inv.setItem(FOG, item(c.fogOfWar() ? Material.SCULK_CATALYST : Material.LIGHT_GRAY_STAINED_GLASS,
                Component.text("Fog of war", c.fogOfWar() ? GREEN : RED),
                List.of(line(c.fogOfWar() ? "Enabled" : "Disabled", c.fogOfWar() ? GREEN : RED),
                        line("Only what you've actually SEEN or been near is", SUB),
                        line("ever sent. Freecam learns nothing you haven't", SUB),
                        line("seen - the strongest anti-xray. Cave mouths and", SUB),
                        line("the live bubble around you stay real, so nothing", SUB),
                        line("visible is false-culled. Subsumes the Outdated", SUB),
                        line("hiders. Click to toggle.", SUB))));
        inv.setItem(FOG_BODY, slider(Material.HEART_OF_THE_SEA, "Fog live radius", c.fogBodyRadius(), "blocks",
                "The bubble around you that's ALWAYS real, even unseen (digging safety). 2-64. Left +2 / Right -2."));
        inv.setItem(FOG_SIGHT, item(c.fogSightRays() ? Material.ENDER_EYE : Material.ENDER_PEARL,
                Component.text("Look-reveal (sight rays)", c.fogSightRays() ? GREEN : RED),
                List.of(line(c.fogSightRays() ? "Enabled" : "Disabled", c.fogSightRays() ? GREEN : RED),
                        line("OFF (default): reveal only caves you WALK into;", SUB),
                        line("far caves you merely glanced at stay hidden -", SUB),
                        line("aggressive and cheapest. ON: also reveal what", SUB),
                        line("you LOOK down (max anti-freecam, costs rays;", SUB),
                        line("uses the ray distance below). Click to toggle.", SUB))));
        inv.setItem(FOG_DIST, slider(Material.SPYGLASS, "Look-reveal distance", c.fogRayDistance(), "blocks",
                "How far look-reveal rays reach (only when look-reveal is ON). Higher = farther, more cost. Left +8 / Right -8."));

        inv.setItem(ANTI_BASE, item(c.antiBaseFinder() ? Material.SCULK_SENSOR : Material.SCULK_SHRIEKER,
                Component.text("Anti-Base Finder", c.antiBaseFinder() ? GREEN : RED),
                List.of(line(c.antiBaseFinder() ? "Enabled" : "Disabled", c.antiBaseFinder() ? GREEN : RED),
                        line("Scrubs a base's solid man-made BLOCKS (walls,", SUB),
                        line("floors, ladders, lamps, doors) to plain rock -", SUB),
                        line("fog of war hides cave AIR, but not solid build", SUB),
                        line("blocks, so the two together fully hide a base", SUB),
                        line("you haven't entered. Your own (explored) base", SUB),
                        line("stays real. Click to toggle.", SUB))));
        inv.setItem(ENTRANCES, item(c.surfaceEntrances() ? Material.GRASS_BLOCK : Material.IRON_TRAPDOOR,
                Component.text("Surface-entrance camouflage", c.surfaceEntrances() ? GREEN : RED),
                List.of(line(c.surfaceEntrances() ? "Enabled" : "Disabled", c.surfaceEntrances() ? GREEN : RED),
                        line("Hide small base entrances at the surface from", SUB),
                        line("afar: a trapdoor/ladder shaft or water-lift in", SUB),
                        line("a narrow pit is capped and blended into the", SUB),
                        line("ground. Shows again up close. Click to toggle.", SUB))));

        // Anti-xray ores.
        inv.setItem(ORE, toggle(Material.DIAMOND_ORE, "Ore Hiding (anti-xray)", c.oreHiding(),
                "Camouflage ores you cannot legitimately see."));
        inv.setItem(HIDE_ALL, toggle(Material.SCULK, "Paranoid anti-xray", c.hideAllOres(),
                "ON: hide every ore. OFF: show surface veins + ores in the cave you're in."));
        inv.setItem(BLOCK_ENT, toggle(Material.CHEST, "Hide block entities (anti-xray)", c.hideBlockEntities(),
                "Strip buried chests/spawners from hidden chunks so they can't be packet-xrayed."));
        inv.setItem(ORE_RADIUS, slider(Material.IRON_ORE, "Ore reveal radius", c.oreRevealRadius(), "blocks",
                "How close an exposed ore must be to stay visible (off when paranoid)."));

        // Bandwidth tiers + vertical cull.
        inv.setItem(CAVE, toggle(Material.DEEPSLATE, "Cave Hiding", c.caveHiding(),
                "Solidify hidden caves below the surface (the distance bandwidth saving)."));
        inv.setItem(VCULL, toggle(Material.ELYTRA, "Vertical culling", c.verticalCulling(),
                "Solidify the deep column far below you; reveals as you descend (fog keeps what you've seen)."));
        inv.setItem(VMARGIN, slider(Material.LADDER, "Vertical margin", c.verticalMargin(), "blocks",
                "Blocks kept real below your feet before culling. Higher = safer for fast drops."));

        // Mode tuning.
        inv.setItem(REAL_R, slider(Material.SPYGLASS, "Real radius", p.realRadius(), "chunks",
                "Chunks around you kept fully real (digging always correct)."));
        inv.setItem(RENDER, slider(Material.ENDER_EYE, "Cave render distance", p.caveRenderDistance(), "chunks",
                "Within this, cave mouths still reveal inward; beyond it, fully hidden."));
        inv.setItem(SHELL, slider(Material.TORCH, "Entrance shell depth", p.entranceShellDepth(), "blocks",
                "How far a visible cave opening reveals inward."));
        inv.setItem(HOMO, slider(Material.STONE, "Homogenize below", p.homogenizeBelow(), "blocks",
                "Deep sections this far under the surface collapse to one block."));
        inv.setItem(ROCK, toggle(Material.TUFF, "Rock collapse (max savings)", p.rockCollapse(),
                "Deep tier merges all rock into the ghost block."));
        inv.setItem(MODE, item(Material.COMPARATOR, Component.text("Mode: " + c.mode().display, MAUVE),
                List.of(line("Click to cycle", SUB))));

        boolean cache = c.chunkCache();
        inv.setItem(CACHE, item(cache ? Material.ENDER_CHEST : Material.CLOCK,
                Component.text("Processing: " + (cache ? "CACHED" : "LIVE"), cache ? GREEN : YELLOW),
                List.of(line(cache ? "Far chunks processed once & reused (efficient)"
                                : "Every chunk reprocessed on each send (testing)", SUB),
                        line("Click to switch CACHED <-> LIVE", SUB))));

        inv.setItem(P_BAL, preset(Mode.BALANCED, c.mode()));
        inv.setItem(P_MAX, preset(Mode.MAX_SAVINGS, c.mode()));
        inv.setItem(P_GEN, preset(Mode.GENEROUS, c.mode()));

        boolean worldOn = c.worldEnabled(player.getWorld().getName());
        inv.setItem(WORLD, item(Material.GRASS_BLOCK,
                Component.text("World: " + player.getWorld().getName(), BLUE),
                List.of(line(worldOn ? "Enabled" : "Disabled", worldOn ? GREEN : RED),
                        line("Click to toggle this world", SUB),
                        line("(no effect while enabled-worlds is \"*\")", SUB))));

        inv.setItem(STATS, statsItem());
        inv.setItem(OUTDATED, item(Material.DEAD_BUSH, Component.text("Outdated features", OVERLAY),
                List.of(line("Legacy hiders that fog of war supersedes:", SUB),
                        line("reachability ore/cave reveal, sealed-cave", SUB),
                        line("hiding, reveal-distance leash. Kept for tuning", SUB),
                        line("if you run without fog. Click to open.", SUB))));
        inv.setItem(CLOSE, item(Material.BARRIER, Component.text("Close", RED), List.of()));
    }

    // ---- outdated page ----

    private void renderOutdated(Inventory inv) {
        Config c = plugin.cfg();
        ItemStack pane = pane();
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);

        inv.setItem(O_INFO, item(Material.DEAD_BUSH, Component.text("Outdated features", OVERLAY),
                List.of(line("Fog of war (on the main page) does all of this", SUB),
                        line("better and more aggressively. These remain only", SUB),
                        line("for servers that run WITHOUT fog of war.", SUB))));

        inv.setItem(O_REACH, item(c.reachabilityOres() ? Material.RECOVERY_COMPASS : Material.COMPASS,
                Component.text("Reachability ore reveal", c.reachabilityOres() ? GREEN : RED),
                List.of(line(c.reachabilityOres() ? "Enabled" : "Disabled", c.reachabilityOres() ? GREEN : RED),
                        line("Show ore only where it touches air you can", SUB),
                        line("actually reach. Superseded by fog of war.", SUB),
                        line("Click to toggle.", SUB))));
        inv.setItem(O_REACH_CAVES, item(c.reachabilityCaves() ? Material.STONE : Material.GLASS,
                Component.text("Reachability cave hiding", c.reachabilityCaves() ? GREEN : RED),
                List.of(line(c.reachabilityCaves() ? "Enabled" : "Disabled", c.reachabilityCaves() ? GREEN : RED),
                        line("Solidify any cave you can't reach. Fog of war", SUB),
                        line("subsumes this (hides what you haven't SEEN,", SUB),
                        line("not merely can't reach). Click to toggle.", SUB))));
        inv.setItem(O_SEALED, item(c.hideSealedCaves() ? Material.STONE_BRICKS : Material.GLOW_BERRIES,
                Component.text("Sealed-cave hiding", c.hideSealedCaves() ? GREEN : RED),
                List.of(line(c.hideSealedCaves() ? "Enabled" : "Disabled", c.hideSealedCaves() ? GREEN : RED),
                        line("Solidify caves with no entrance to the sky.", SUB),
                        line("The gentle sibling of reachability hiding;", SUB),
                        line("superseded by fog of war. Click to toggle.", SUB))));
        inv.setItem(O_REVEAL_DIST, item(Material.LEAD,
                Component.text("Reveal distance: " + (c.revealDistance() == 0 ? "unlimited" : c.revealDistance() + " blocks"), TEXT),
                List.of(line("Leash on how far reachability reveals around", SUB),
                        line("you. Only shapes the reachability scanner above;", SUB),
                        line("fog of war has its own ray distance. 0 = off.", SUB),
                        line("Left +8 / Right -8", SUB))));

        inv.setItem(O_BACK, item(Material.ARROW, Component.text("Back", GREEN),
                List.of(line("Return to the main panel", SUB))));
        inv.setItem(O_CLOSE, item(Material.BARRIER, Component.text("Close", RED), List.of()));
    }

    private ItemStack statsItem() {
        BandwidthMonitor m = plugin.getMonitor();
        List<Component> lore = new ArrayList<>();
        lore.add(line(String.format("Bandwidth saved: %.1f%%", m.savingsPercent()), GREEN));
        lore.add(line("Data saved (session): " + m.savedHuman(), GREEN));
        lore.add(line(String.format("  REAL %.0f%% (%d) - sent real for digging",
                m.tierSavingsPercent(net.cadistenmc.cadistchunkprocessing.engine.Tier.REAL),
                m.tierCount(net.cadistenmc.cadistchunkprocessing.engine.Tier.REAL)), SUB));
        lore.add(line(String.format("  SHELL %.0f%% (%d)   DEEP %.0f%% (%d)",
                m.tierSavingsPercent(net.cadistenmc.cadistchunkprocessing.engine.Tier.SHELL),
                m.tierCount(net.cadistenmc.cadistchunkprocessing.engine.Tier.SHELL),
                m.tierSavingsPercent(net.cadistenmc.cadistchunkprocessing.engine.Tier.DEEP),
                m.tierCount(net.cadistenmc.cadistchunkprocessing.engine.Tier.DEEP)), SUB));
        lore.add(line("Chunks/sec: " + m.chunksPerSecond(), TEXT));
        lore.add(line("Chunks processed: " + m.packetsTotal(), SUB));
        lore.add(line("Chunks modified: " + m.packetsModified(), SUB));
        lore.add(line("Ores hidden: " + m.oresHidden(), SUB));
        lore.add(line("Cave blocks solidified: " + m.blocksSolidified(), SUB));
        Config c = plugin.cfg();
        lore.add(line("Fog of war: " + (c.fogOfWar()
                ? "on (live " + c.fogBodyRadius() + "b, look-reveal "
                  + (c.fogSightRays() ? "on " + c.fogRayDistance() + "b" : "off") + ")" : "off")
                + "   Anti-base: " + (c.antiBaseFinder() ? "on" : "off"), SUB));
        return item(Material.BOOK, Component.text("Live statistics", YELLOW), lore);
    }

    // ---- click handling ----

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof Holder)) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;

        Config c = plugin.cfg();
        boolean left = e.isLeftClick();
        boolean changed = true;

        if (holder.outdated) {
            switch (e.getRawSlot()) {
                case O_REACH -> c.setReachabilityOres(!c.reachabilityOres());
                case O_REACH_CAVES -> c.setReachabilityCaves(!c.reachabilityCaves());
                case O_SEALED -> c.setHideSealedCaves(!c.hideSealedCaves());
                case O_REVEAL_DIST -> c.setRevealDistance(clamp(c.revealDistance() + (left ? 8 : -8), 0, 256));
                case O_BACK -> { openPage(player, false); return; }
                case O_CLOSE -> { player.closeInventory(); return; }
                default -> changed = false;
            }
            if (changed) plugin.applyChanges();
            renderOutdated(e.getView().getTopInventory());
            return;
        }

        switch (e.getRawSlot()) {
            case FOG -> c.setFogOfWar(!c.fogOfWar());
            case FOG_SIGHT -> c.setFogSightRays(!c.fogSightRays());
            case FOG_DIST -> c.setFogRayDistance(clamp(c.fogRayDistance() + (left ? 8 : -8), 8, 256));
            case FOG_BODY -> c.setFogBodyRadius(clamp(c.fogBodyRadius() + (left ? 2 : -2), 2, 64));
            case ANTI_BASE -> c.setAntiBaseFinder(!c.antiBaseFinder());
            case ENTRANCES -> c.setSurfaceEntrances(!c.surfaceEntrances());
            case ORE -> c.setOreHiding(!c.oreHiding());
            case HIDE_ALL -> c.setHideAllOres(!c.hideAllOres());
            case BLOCK_ENT -> c.setHideBlockEntities(!c.hideBlockEntities());
            case ORE_RADIUS -> c.setOreRevealRadius(clamp(c.oreRevealRadius() + (left ? 2 : -2), 0, 64));
            case CAVE -> c.setCaveHiding(!c.caveHiding());
            case VCULL -> c.setVerticalCulling(!c.verticalCulling());
            case VMARGIN -> c.setVerticalMargin(clamp(c.verticalMargin() + (left ? 8 : -8), 8, 128));
            case REAL_R -> c.setCurrentModeInt("real-radius", clamp(c.params().realRadius() + (left ? 1 : -1), 2, 16));
            case RENDER -> c.setCurrentModeInt("cave-render-distance", clamp(c.params().caveRenderDistance() + (left ? 1 : -1), 2, 32));
            case SHELL -> c.setCurrentModeInt("entrance-shell-depth", clamp(c.params().entranceShellDepth() + (left ? 1 : -1), 0, 32));
            case HOMO -> c.setCurrentModeInt("homogenize-below", clamp(c.params().homogenizeBelow() + (left ? 4 : -4), 0, 128));
            case ROCK -> c.setCurrentModeBool("rock-collapse", !c.params().rockCollapse());
            case MODE -> c.setMode(c.mode().next());
            case CACHE -> c.setChunkCache(!c.chunkCache());
            case P_BAL -> c.setMode(Mode.BALANCED);
            case P_MAX -> c.setMode(Mode.MAX_SAVINGS);
            case P_GEN -> c.setMode(Mode.GENEROUS);
            case WORLD -> c.toggleWorld(player.getWorld().getName());
            case OUTDATED -> { openPage(player, true); return; }
            case CLOSE -> { player.closeInventory(); return; }
            case STATS -> changed = false;
            default -> changed = false;
        }
        if (changed) plugin.applyChanges();
        renderMain(e.getView().getTopInventory(), player);
    }

    // ---- item helpers ----

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private ItemStack pane() {
        ItemStack i = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = i.getItemMeta();
        m.displayName(Component.text(" "));
        i.setItemMeta(m);
        return i;
    }

    private ItemStack toggle(Material mat, String name, boolean on, String desc) {
        return item(mat, Component.text(name, on ? GREEN : RED),
                List.of(line(on ? "Enabled" : "Disabled", on ? GREEN : RED),
                        line(desc, SUB),
                        line("Click to toggle", SUB)));
    }

    private ItemStack slider(Material mat, String name, int value, String unit, String desc) {
        return item(mat, Component.text(name + ": " + value + " " + unit, TEXT),
                List.of(line(desc, SUB),
                        line("Left-click +  /  Right-click -", SUB)));
    }

    private ItemStack preset(Mode m, Mode current) {
        boolean active = m == current;
        return item(active ? Material.LIME_DYE : Material.GRAY_DYE,
                Component.text("Preset: " + m.display, active ? GREEN : TEXT),
                List.of(line(active ? "Active" : "Click to apply", active ? GREEN : SUB)));
    }

    private ItemStack item(Material mat, Component name, List<Component> lore) {
        ItemStack i = new ItemStack(mat);
        ItemMeta m = i.getItemMeta();
        m.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) m.lore(lore);
        i.setItemMeta(m);
        return i;
    }

    private static Component line(String s, TextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }
}
