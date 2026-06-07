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

    /**
     * True for a <em>player-signature</em> block — one that essentially never
     * generates in undisturbed ground and so betrays a base: building materials
     * (planks, cobblestone, bricks, glass, wool, concrete, metal blocks …) and
     * functional blocks (ladders, rails, redstone, doors, signs, lamps, chests,
     * furnaces, …). Used only by the anti-base-finder layer. MUST be false for
     * natural terrain, ores and natural cave decoration. Defaults to {@code false}
     * so classifiers that don't care about anti-base need not implement it.
     */
    default boolean isArtificial(int blockId) { return false; }
}
