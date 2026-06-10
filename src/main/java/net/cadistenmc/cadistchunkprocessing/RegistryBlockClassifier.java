package net.cadistenmc.cadistchunkprocessing;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import net.cadistenmc.cadistchunkprocessing.engine.BlockClassifier;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production {@link BlockClassifier} backed by PacketEvents' block registry.
 * Results are memoised by global id (the registry is immutable at runtime), so
 * the hot path is a single concurrent-map lookup — safe to call off-thread.
 */
public final class RegistryBlockClassifier implements BlockClassifier {

    /** Exact names of natural ground material (no "contains" — keeps cobblestone/bricks out). */
    private static final Set<String> TERRAIN = Set.of(
            "stone", "granite", "diorite", "andesite", "deepslate", "tuff", "calcite",
            "dirt", "grass_block", "podzol", "mycelium", "coarse_dirt", "rooted_dirt",
            "mud", "clay", "sand", "red_sand", "gravel", "sandstone", "red_sandstone",
            "netherrack", "basalt", "blackstone", "soul_sand", "soul_soil", "magma_block",
            "end_stone", "snow_block", "dripstone_block", "moss_block", "dirt_path");

    private final ConcurrentHashMap<Integer, Boolean> transparent = new ConcurrentHashMap<>(2048);
    private final ConcurrentHashMap<Integer, Boolean> ore = new ConcurrentHashMap<>(128);
    private final ConcurrentHashMap<Integer, Boolean> terrain = new ConcurrentHashMap<>(256);
    private final ConcurrentHashMap<Integer, Boolean> artificial = new ConcurrentHashMap<>(512);
    private final ConcurrentHashMap<Integer, Boolean> fluid = new ConcurrentHashMap<>(64);

    /** Extra ore name-substrings from config (e.g. modded ores), lower-cased. */
    private volatile String[] extraOres = new String[0];

    /** Set the configured extra-ore substrings and clear the ore memo so they take effect. */
    public void setExtraOres(List<String> matches) {
        this.extraOres = matches.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toArray(String[]::new);
        ore.clear();
    }

    @Override
    public boolean isTransparent(int id) {
        if (id == 0) return true;
        return transparent.computeIfAbsent(id, RegistryBlockClassifier::computeTransparent);
    }

    @Override
    public boolean isOre(int id) {
        if (id == 0) return false;
        return ore.computeIfAbsent(id, this::computeOre);
    }

    @Override
    public boolean isTerrain(int id) {
        if (id == 0) return false;
        return terrain.computeIfAbsent(id, RegistryBlockClassifier::computeTerrain);
    }

    @Override
    public boolean isArtificial(int id) {
        if (id == 0) return false;   // air is never a base signature
        return artificial.computeIfAbsent(id, RegistryBlockClassifier::computeArtificial);
    }

    @Override
    public boolean isFluid(int id) {
        if (id == 0) return false;
        return fluid.computeIfAbsent(id, RegistryBlockClassifier::computeFluid);
    }

    private static boolean computeFluid(int id) {
        try {
            String name = WrappedBlockState.getByGlobalId(id).getType().getName().toLowerCase();
            return name.contains("water") || name.contains("lava") || name.contains("bubble_column");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean computeTerrain(int id) {
        try {
            return TERRAIN.contains(WrappedBlockState.getByGlobalId(id).getType().getName().toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Player-signature blocks for the anti-base-finder. Conservative at the edges:
     * we first rule out exact natural terrain and ores (so stone/deepslate/sand,
     * ore veins and raw-metal blocks are never treated as a base), then match the
     * man-made / structure families by name. Errs toward flagging structure blocks
     * (mineshaft planks/rails, stronghold bricks) — hiding those underground is in
     * keeping with the plugin's anti-xray intent.
     */
    private static boolean computeArtificial(int id) {
        WrappedBlockState state;
        try {
            state = WrappedBlockState.getByGlobalId(id);
        } catch (Exception e) {
            return false;
        }
        var type = state.getType();
        if (type.isAir()) return false;
        String name = type.getName().toLowerCase();
        if (TERRAIN.contains(name)) return false;            // natural ground -> never a base block
        // Natural ores / raw-metal blocks are handled by the ore layer, not here.
        if (name.contains("_ore") || name.startsWith("raw_")) return false;

        // Building & decoration.
        if (name.contains("planks") || name.contains("_log") || name.contains("_wood")
                || name.contains("stripped_") || name.contains("bookshelf")
                || name.contains("stairs") || name.contains("slab") || name.contains("_wall")
                || name.contains("fence") || name.contains("door") || name.contains("_pane")
                || name.contains("glass") || name.contains("wool") || name.contains("carpet")
                || name.contains("concrete") || name.contains("terracotta") || name.contains("glazed")
                || name.contains("brick") || name.contains("cobblestone") || name.contains("cobbled_")
                || name.contains("mossy_") || name.contains("polished_") || name.contains("chiseled_")
                || name.contains("cut_") || name.contains("smooth_") || name.contains("tiles")
                || name.contains("quartz_block") || name.contains("purpur") || name.contains("prismarine")) {
            return true;
        }
        // Functional / redstone / utility / storage.
        if (name.contains("ladder") || name.contains("scaffolding") || name.contains("rail")
                || name.contains("redstone") || name.contains("repeater") || name.contains("comparator")
                || name.contains("piston") || name.contains("observer") || name.contains("dispenser")
                || name.contains("dropper") || name.contains("hopper") || name.contains("lever")
                || name.contains("button") || name.contains("pressure_plate") || name.contains("tripwire")
                || name.contains("note_block") || name.contains("jukebox") || name.contains("lamp")
                || name.contains("lantern") || name.contains("torch") || name.contains("candle")
                || name.contains("sea_lantern") || name.contains("target") || name.contains("lectern")
                || name.contains("composter") || name.contains("cauldron") || name.contains("campfire")
                || name.contains("beacon") || name.contains("conduit") || name.contains("lodestone")
                || name.contains("bell") || name.contains("grindstone") || name.contains("stonecutter")
                || name.contains("loom") || name.contains("_table") || name.contains("brewing_stand")
                || name.contains("anvil") || name.contains("furnace") || name.contains("smoker")
                || name.contains("barrel") || name.contains("chest") || name.contains("shulker_box")
                || name.contains("iron_bars") || name.contains("chain") || name.contains("frame")
                || name.contains("flower_pot") || name.contains("decorated_pot") || name.contains("respawn_anchor")
                || name.contains("end_rod") || name.contains("_sign") || name.contains("banner")
                || name.contains("_bed") || name.contains("tnt") || name.contains("crafter")) {
            return true;
        }
        // Player mineral-storage blocks.
        return name.contains("iron_block") || name.contains("gold_block") || name.contains("diamond_block")
                || name.contains("emerald_block") || name.contains("netherite_block") || name.contains("lapis_block")
                || name.contains("redstone_block") || name.contains("coal_block") || name.contains("copper_block")
                || name.contains("slime_block") || name.contains("honey_block");
    }

    private static boolean computeTransparent(int id) {
        WrappedBlockState state;
        try {
            state = WrappedBlockState.getByGlobalId(id);
        } catch (Exception e) {
            return false;
        }
        var type = state.getType();
        String name = type.getName().toLowerCase();
        if (type.isAir()) return true;
        if (name.contains("water") || name.contains("lava") || name.contains("bubble_column")) return true;
        if (name.contains("glass") || name.contains("ice")) return true;
        if (name.contains("leaves")) return true;
        if (name.contains("slab") || name.contains("stairs") || name.contains("door")
                || name.contains("trapdoor") || name.contains("fence") || name.contains("wall")
                || name.contains("button") || name.contains("pressure_plate") || name.contains("sign")
                || name.contains("banner") || name.contains("carpet") || name.contains("rail")
                || name.contains("torch") || name.contains("lantern") || name.contains("candle")
                || name.contains("chain") || name.contains("rod") || name.contains("head")
                || name.contains("skull") || name.contains("coral") || name.contains("fan")
                || name.contains("mushroom") || name.contains("roots") || name.contains("vines")
                || name.contains("dripstone") || name.contains("lichen") || name.contains("sculk_vein")
                || name.contains("cave_vines") || name.contains("spore_blossom")) {
            return true;
        }
        if (name.contains("grass") || name.contains("fern") || name.contains("flower")
                || name.contains("bush") || name.contains("sapling") || name.contains("moss")
                || name.contains("crop") || name.contains("stem") || name.contains("nether_wart")
                || name.contains("berry") || name.contains("kelp") || name.contains("seagrass")
                || name.contains("lily") || name.contains("dead_bush") || name.contains("dripleaf")
                || name.contains("spore") || name.contains("amethyst") || name.contains("bud")
                || name.contains("cluster")) {
            return true;
        }
        if (name.equals("snow") || name.contains("snow_layer")) return true;
        return !type.isBlocking();
    }

    private boolean computeOre(int id) {
        WrappedBlockState state;
        try {
            state = WrappedBlockState.getByGlobalId(id);
        } catch (Exception e) {
            return false;
        }
        String name = state.getType().getName().toLowerCase();
        boolean vanilla = name.contains("coal_ore") || name.contains("iron_ore") || name.contains("copper_ore")
                || name.contains("gold_ore") || name.contains("redstone_ore") || name.contains("lapis_ore")
                || name.contains("diamond_ore") || name.contains("emerald_ore") || name.contains("ancient_debris")
                || name.contains("nether_gold_ore") || name.contains("nether_quartz_ore")
                || (name.contains("deepslate") && name.contains("ore"))
                || (name.startsWith("raw_") && name.contains("block"));
        if (vanilla) return true;
        for (String m : extraOres) {
            if (name.contains(m)) return true;
        }
        return false;
    }
}
