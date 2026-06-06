package net.cadistenmc.cadistchunkprocessing.engine;

/**
 * Classifies block-state global ids into the few categories the culling engine
 * cares about. Kept as an interface so {@link ChunkProcessor} has <em>zero</em>
 * dependency on PacketEvents or Bukkit and can be unit-tested with synthetic
 * ids. The production implementation wraps PacketEvents' block registry.
 */
public interface BlockClassifier {

    /**
     * True for air and anything that does not visually occlude a full block:
     * fluids (water/lava), glass, ice, leaves, plants, slabs, stairs, fences,
     * torches, rails, etc. These are the blocks that form "cave space" below
     * the surface and "open sky" above it.
     */
    boolean isTransparent(int blockId);

    /**
     * True for any ore the anti-xray layer should camouflage when it is not
     * legitimately exposed: coal/iron/copper/gold/redstone/lapis/diamond/
     * emerald ores (incl. deepslate variants), nether gold/quartz, ancient
     * debris, and raw-metal blocks.
     */
    boolean isOre(int blockId);

    /**
     * True for natural ground material that is safe to homogenise into the ghost
     * block (stone/deepslate/dirt/sand/gravel/the rock family, etc.). MUST be
     * false for anything player-built or notable — logs, planks, cobblestone,
     * bricks, glass, wool, slabs, doors — so trees, villages and structures are
     * never collapsed. Conservative by design: when unsure, return false.
     */
    boolean isTerrain(int blockId);
}
