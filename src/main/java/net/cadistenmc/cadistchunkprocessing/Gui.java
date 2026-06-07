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
 * Catppuccin-Mocha control panel: master toggles, per-mode tuning sliders, a
 * live stats readout, and one-click presets. All changes persist and apply to
 * newly sent chunks immediately (and nearby chunks are re-sent for online
 * players via {@link CadistChunkProcessingPro#applyChanges()}).
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

    private static final int CAVE = 10, ORE = 12, WORLD = 14, STATS = 16;
    private static final int BLOCK_ENT = 11, CACHE = 15, ANTI_BASE = 13;
    private static final int REAL_R = 19, RENDER = 21, SHELL = 23, HOMO = 25;
    private static final int ROCK = 29, MODE = 31, HIDE_ALL = 28, ORE_RADIUS = 33;
    private static final int VCULL = 30, VMARGIN = 32;
    private static final int P_BAL = 38, P_MAX = 40, P_GEN = 42;
    private static final int CLOSE = 49;

    private final CadistChunkProcessingPro plugin;

    public Gui(CadistChunkProcessingPro plugin) {
        this.plugin = plugin;
    }

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    public void open(Player player) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("CadistChunkProcessing Pro", MAUVE, TextDecoration.BOLD));
        holder.inv = inv;
        render(inv, player);
        player.openInventory(inv);
    }

    private void render(Inventory inv, Player player) {
        Config c = plugin.cfg();
        ModeParams p = c.params();
        ItemStack pane = pane();
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);

        inv.setItem(CAVE, toggle(Material.DEEPSLATE, "Cave Hiding", c.caveHiding(),
                "Solidify hidden caves below the surface."));
        inv.setItem(ORE, toggle(Material.DIAMOND_ORE, "Ore Hiding (anti-xray)", c.oreHiding(),
                "Camouflage ores you cannot legitimately see."));
        inv.setItem(BLOCK_ENT, toggle(Material.CHEST, "Hide block entities (anti-xray)", c.hideBlockEntities(),
                "Strip buried chests/spawners from hidden chunks so they can't be packet-xrayed."));
        inv.setItem(ANTI_BASE, item(c.antiBaseFinder() ? Material.SCULK_SENSOR : Material.SCULK_SHRIEKER,
                Component.text("Anti-Base Finder", c.antiBaseFinder() ? GREEN : RED),
                List.of(line(c.antiBaseFinder() ? "Enabled" : "Disabled", c.antiBaseFinder() ? GREEN : RED),
                        line("Aggressively hides buried bases.", SUB),
                        line("Man-made tunnels, ladder/water-lift shafts and", SUB),
                        line("rooms stay solid even at an opening, and base", SUB),
                        line("blocks below ground (ladders, rails, lamps,", SUB),
                        line("doors, building blocks) read as plain rock.", SUB),
                        line("Natural caves still reveal; your base re-appears", SUB),
                        line("up close. Click to toggle.", SUB))));

        boolean cache = c.chunkCache();
        inv.setItem(CACHE, item(cache ? Material.ENDER_CHEST : Material.CLOCK,
                Component.text("Processing: " + (cache ? "CACHED" : "LIVE"), cache ? GREEN : YELLOW),
                List.of(line(cache ? "Far chunks processed once & reused (efficient)"
                                : "Every chunk reprocessed on each send (testing)", SUB),
                        line("Click to switch CACHED <-> LIVE", SUB),
                        line("LIVE = watch processing happen in production", SUB))));

        boolean worldOn = c.worldEnabled(player.getWorld().getName());
        inv.setItem(WORLD, item(Material.GRASS_BLOCK,
                Component.text("World: " + player.getWorld().getName(), BLUE),
                List.of(line(worldOn ? "Enabled" : "Disabled", worldOn ? GREEN : RED),
                        line("Click to toggle this world", SUB),
                        line("(no effect while enabled-worlds is \"*\")", SUB))));

        inv.setItem(STATS, statsItem());

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
        inv.setItem(MODE, item(Material.COMPARATOR,
                Component.text("Mode: " + c.mode().display, MAUVE),
                List.of(line("Click to cycle", SUB))));

        inv.setItem(VCULL, toggle(Material.ELYTRA, "Vertical culling", c.verticalCulling(),
                "Solidify the deep column far below you (in the real bubble); reveals as you descend."));
        inv.setItem(VMARGIN, slider(Material.LADDER, "Vertical margin", c.verticalMargin(), "blocks",
                "Blocks kept real below your feet before culling. Higher = safer for fast drops."));

        inv.setItem(HIDE_ALL, toggle(Material.SCULK, "Paranoid anti-xray", c.hideAllOres(),
                "ON: hide every ore. OFF: show surface veins + ores in the cave you're in."));
        inv.setItem(ORE_RADIUS, slider(Material.IRON_ORE, "Ore reveal radius", c.oreRevealRadius(), "blocks",
                "How close an exposed ore must be to stay visible (off when paranoid)."));

        inv.setItem(P_BAL, preset(Mode.BALANCED, c.mode()));
        inv.setItem(P_MAX, preset(Mode.MAX_SAVINGS, c.mode()));
        inv.setItem(P_GEN, preset(Mode.GENEROUS, c.mode()));

        inv.setItem(CLOSE, item(Material.BARRIER, Component.text("Close", RED), List.of()));
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
        switch (e.getRawSlot()) {
            case CAVE -> c.setCaveHiding(!c.caveHiding());
            case ORE -> c.setOreHiding(!c.oreHiding());
            case BLOCK_ENT -> c.setHideBlockEntities(!c.hideBlockEntities());
            case ANTI_BASE -> c.setAntiBaseFinder(!c.antiBaseFinder());
            case CACHE -> c.setChunkCache(!c.chunkCache());
            case VCULL -> c.setVerticalCulling(!c.verticalCulling());
            case VMARGIN -> c.setVerticalMargin(clamp(c.verticalMargin() + (left ? 8 : -8), 8, 128));
            case HIDE_ALL -> c.setHideAllOres(!c.hideAllOres());
            case ORE_RADIUS -> c.setOreRevealRadius(clamp(c.oreRevealRadius() + (left ? 2 : -2), 0, 64));
            case WORLD -> c.toggleWorld(player.getWorld().getName());
            case ROCK -> c.setCurrentModeBool("rock-collapse", !c.params().rockCollapse());
            case MODE -> c.setMode(c.mode().next());
            case REAL_R -> c.setCurrentModeInt("real-radius", clamp(c.params().realRadius() + (left ? 1 : -1), 2, 16));
            case RENDER -> c.setCurrentModeInt("cave-render-distance", clamp(c.params().caveRenderDistance() + (left ? 1 : -1), 2, 32));
            case SHELL -> c.setCurrentModeInt("entrance-shell-depth", clamp(c.params().entranceShellDepth() + (left ? 1 : -1), 0, 32));
            case HOMO -> c.setCurrentModeInt("homogenize-below", clamp(c.params().homogenizeBelow() + (left ? 4 : -4), 0, 128));
            case P_BAL -> c.setMode(Mode.BALANCED);
            case P_MAX -> c.setMode(Mode.MAX_SAVINGS);
            case P_GEN -> c.setMode(Mode.GENEROUS);
            case CLOSE -> { player.closeInventory(); return; }
            case STATS -> changed = false;
            default -> changed = false;
        }
        if (changed) plugin.applyChanges();
        render(e.getView().getTopInventory(), player);
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
