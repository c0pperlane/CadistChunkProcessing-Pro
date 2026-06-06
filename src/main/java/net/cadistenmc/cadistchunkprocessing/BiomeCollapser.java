package net.cadistenmc.cadistchunkprocessing;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;

/**
 * A chunk section also carries a paletted <em>biome</em> container (a 4×4×4 grid
 * of biome ids) alongside its block container. Where we have already collapsed a
 * section's blocks to a single ghost id, that section is fully hidden — so its
 * biome grid is invisible too, and flattening it to one value (the section's own
 * existing biome at 0,0,0) makes the biome storage uniform as well. That costs
 * nothing visually and lets zlib (the server's network compression) crush the
 * biome bytes just like the uniform block bytes.
 *
 * <p>Only touches sections whose blocks are already uniform, and reuses a biome
 * id already present in that section — so it can never introduce an invalid /
 * unregistered biome id, and never alters a biome the player can actually see.
 */
public final class BiomeCollapser {

    private BiomeCollapser() {}

    /**
     * For every section that is uniform in {@code blocks}, flatten its biome
     * grid to that section's existing (0,0,0) biome. {@code blocks} is the
     * flattened/processed block array; {@code ySize} its height.
     *
     * @return number of biome cells rewritten (0 if nothing changed)
     */
    public static int collapse(Column column, int[] blocks, int ySize) {
        BaseChunk[] sections = column.getChunks();
        if (sections == null) return 0;
        int changed = 0;

        for (int sy = 0; sy < sections.length; sy++) {
            if (!(sections[sy] instanceof Chunk_v1_18 c)) continue;
            int base = sy << 12;
            if (!sectionUniform(blocks, base)) continue;

            DataPalette biome = c.getBiomeData();
            if (biome == null) continue;

            // Biome grid is 4×4×4. Pick the existing corner biome and stamp it
            // everywhere; if it's already uniform this is a no-op (no writes).
            int target = biome.get(0, 0, 0);
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        if (biome.get(x, y, z) != target) {
                            biome.set(x, y, z, target);
                            changed++;
                        }
                    }
                }
            }
        }
        return changed;
    }

    /** True if all 4096 block ids in section {@code [base, base+4096)} are equal. */
    private static boolean sectionUniform(int[] blocks, int base) {
        int first = blocks[base];
        for (int i = base + 1, end = base + 4096; i < end; i++) {
            if (blocks[i] != first) return false;
        }
        return true;
    }
}
