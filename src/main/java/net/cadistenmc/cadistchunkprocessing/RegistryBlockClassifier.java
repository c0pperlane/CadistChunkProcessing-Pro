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

    private static boolean computeTerrain(int id) {
        try {
            return TERRAIN.contains(WrappedBlockState.getByGlobalId(id).getType().getName().toLowerCase());
        } catch (Exception e) {
            return false;
        }
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
