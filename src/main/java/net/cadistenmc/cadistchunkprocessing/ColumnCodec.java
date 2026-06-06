package net.cadistenmc.cadistchunkprocessing;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;

import java.util.Arrays;

/**
 * Bridges a PacketEvents {@link Column} and the engine's flat {@code int[]}.
 * Index convention matches {@code ChunkProcessor}: idx = (y&lt;&lt;8)|(z&lt;&lt;4)|x.
 */
public final class ColumnCodec {

    private ColumnCodec() {}

    /**
     * Read all block-state ids into {@code dst} (which must hold at least
     * {@code sections*16*256} ints). Returns the column height, or 0 if empty.
     */
    public static int flatten(Column column, int[] dst) {
        BaseChunk[] sections = column.getChunks();
        if (sections == null || sections.length == 0) return 0;
        int ySize = sections.length * 16;

        for (int sy = 0; sy < sections.length; sy++) {
            int base = sy << 12;
            BaseChunk sec = sections[sy];
            if (sec == null) {
                Arrays.fill(dst, base, base + 4096, 0);
                continue;
            }
            DataPalette pal = (sec instanceof Chunk_v1_18 c) ? c.getChunkData() : null;
            for (int ly = 0; ly < 16; ly++) {
                int yShift = (sy * 16 + ly) << 8;
                for (int z = 0; z < 16; z++) {
                    int zShift = z << 4;
                    for (int x = 0; x < 16; x++) {
                        int idx = yShift | zShift | x;
                        dst[idx] = (pal != null) ? pal.get(x, ly, z) : sec.getBlockId(x, ly, z);
                    }
                }
            }
        }
        return ySize;
    }

    /**
     * Write {@code desired} back into the column, touching only blocks that
     * differ, and refresh each changed section's non-air count. Sections that
     * are {@code null} (truly absent) are skipped — our transforms only ever
     * solidify existing space, never populate an empty section.
     *
     * @return number of block-state changes written
     */
    public static long apply(Column column, int[] desired) {
        BaseChunk[] sections = column.getChunks();
        if (sections == null || sections.length == 0) return 0;
        long changes = 0;

        for (int sy = 0; sy < sections.length; sy++) {
            BaseChunk sec = sections[sy];
            if (sec == null) continue;
            DataPalette pal = (sec instanceof Chunk_v1_18 c) ? c.getChunkData() : null;
            Chunk_v1_18 c18 = (sec instanceof Chunk_v1_18 c) ? c : null;

            boolean changed = false;
            int nonAir = 0;
            for (int ly = 0; ly < 16; ly++) {
                int yShift = (sy * 16 + ly) << 8;
                for (int z = 0; z < 16; z++) {
                    int zShift = z << 4;
                    for (int x = 0; x < 16; x++) {
                        int idx = yShift | zShift | x;
                        int cur = (pal != null) ? pal.get(x, ly, z) : sec.getBlockId(x, ly, z);
                        int des = desired[idx];
                        if (des != cur) {
                            if (pal != null) pal.set(x, ly, z, des); else sec.set(x, ly, z, des);
                            changed = true;
                            changes++;
                            cur = des;
                        }
                        if (cur != 0) nonAir++;
                    }
                }
            }
            if (changed && c18 != null) c18.setBlockCount(nonAir);
        }
        return changes;
    }
}
