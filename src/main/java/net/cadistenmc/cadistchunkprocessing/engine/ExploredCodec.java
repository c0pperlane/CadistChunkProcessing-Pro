package net.cadistenmc.cadistchunkprocessing.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Pure (de)serialization of one player+world fog-of-war explored set — a
 * {@code Map<chunkKey, long[] bitset>} plus the per-chunk {@code bitsLen}. No
 * Bukkit, so it is fully unit-testable and round-trip verifiable standalone.
 *
 * <p>Format v1: a Deflate-compressed stream of {@code magic("CCPF") · u8 version ·
 * int bitsLen · int chunkCount · (long chunkKey · bitsLen×long)*}. Cave-network
 * bitsets are mostly zero, so they compress heavily. The decode path is
 * <em>defensive</em>: a wrong magic, an unknown version, an out-of-range header,
 * or a truncated/corrupt stream all throw {@link IOException} so the caller can
 * discard the file and start empty — always the safe direction (more hiding,
 * never less).
 */
public final class ExploredCodec {

    private static final byte[] MAGIC = {'C', 'C', 'P', 'F'};
    private static final int VERSION = 1;

    // Sanity bounds so a corrupt header can never trigger a huge allocation.
    private static final int MAX_BITS_LEN = 1 << 20;       // 64M cells per chunk — absurdly generous
    private static final int MAX_CHUNKS = 10_000_000;

    /** A decoded explored set: the per-chunk bitset length and the chunk bitsets. */
    public static final class Decoded {
        public final int bitsLen;
        public final Map<Long, long[]> bits;
        Decoded(int bitsLen, Map<Long, long[]> bits) {
            this.bitsLen = bitsLen;
            this.bits = bits;
        }
    }

    private ExploredCodec() {}

    public static byte[] encode(Map<Long, long[]> bits, int bitsLen) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        try (DataOutputStream d = new DataOutputStream(new DeflaterOutputStream(bos))) {
            d.write(MAGIC);
            d.writeByte(VERSION);
            d.writeInt(bitsLen);
            d.writeInt(bits.size());
            for (Map.Entry<Long, long[]> e : bits.entrySet()) {
                d.writeLong(e.getKey());
                long[] arr = e.getValue();
                for (int i = 0; i < bitsLen; i++) {
                    d.writeLong(i < arr.length ? arr[i] : 0L);
                }
            }
        }
        return bos.toByteArray();
    }

    public static Decoded decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            byte[] m = new byte[4];
            in.readFully(m);
            if (!Arrays.equals(m, MAGIC)) throw new IOException("bad magic");
            int v = in.readUnsignedByte();
            if (v != VERSION) throw new IOException("unsupported version " + v);
            int bitsLen = in.readInt();
            int count = in.readInt();
            if (bitsLen <= 0 || bitsLen > MAX_BITS_LEN || count < 0 || count > MAX_CHUNKS) {
                throw new IOException("corrupt header (bitsLen=" + bitsLen + " count=" + count + ")");
            }
            Map<Long, long[]> bits = new HashMap<>(Math.max(16, count * 2));
            for (int c = 0; c < count; c++) {
                long key = in.readLong();
                long[] arr = new long[bitsLen];
                for (int i = 0; i < bitsLen; i++) arr[i] = in.readLong();
                bits.put(key, arr);
            }
            return new Decoded(bitsLen, bits);
        }
    }
}
