package net.cadistenmc.cadistchunkprocessing.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Round-trip and corruption tests for {@link ExploredCodec}. */
class ExploredCodecTest {

    private static Map<Long, long[]> sample(int bitsLen) {
        Map<Long, long[]> m = new HashMap<>();
        long[] a = new long[bitsLen]; a[0] = 0xDEADBEEFL; a[bitsLen - 1] = 1L;
        long[] b = new long[bitsLen]; b[3] = -1L;
        m.put(((long) 5 << 32) | (7 & 0xFFFFFFFFL), a);
        m.put(((long) -2 << 32) | (-9 & 0xFFFFFFFFL), b);
        return m;
    }

    @Test
    void roundTrip_preservesEverything() throws IOException {
        int bitsLen = 1536;                       // ySize=384 -> (384<<8)/64
        Map<Long, long[]> in = sample(bitsLen);
        byte[] enc = ExploredCodec.encode(in, bitsLen);
        ExploredCodec.Decoded out = ExploredCodec.decode(enc);
        assertEquals(bitsLen, out.bitsLen);
        assertEquals(in.size(), out.bits.size());
        for (Map.Entry<Long, long[]> e : in.entrySet()) {
            assertArrayEquals(e.getValue(), out.bits.get(e.getKey()), "chunk " + e.getKey());
        }
    }

    @Test
    void empty_roundTrips() throws IOException {
        byte[] enc = ExploredCodec.encode(new HashMap<>(), 16);
        ExploredCodec.Decoded out = ExploredCodec.decode(enc);
        assertEquals(16, out.bitsLen);
        assertTrue(out.bits.isEmpty());
    }

    @Test
    void deflateActuallyCompressesSparseBitsets() throws IOException {
        int bitsLen = 1536;
        Map<Long, long[]> m = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            long[] arr = new long[bitsLen]; arr[i % bitsLen] = 1L;   // one bit each
            m.put((long) i << 32, arr);
        }
        byte[] enc = ExploredCodec.encode(m, bitsLen);
        long uncompressed = 13L + (long) m.size() * (8 + 8L * bitsLen);
        assertTrue(enc.length * 5L < uncompressed,
                "sparse bitsets should compress hard (enc=" + enc.length + " raw=" + uncompressed + ")");
    }

    @Test
    void badMagic_throws() {
        byte[] junk = "not a ccpf file at all".getBytes();
        assertThrows(IOException.class, () -> ExploredCodec.decode(junk));
    }

    @Test
    void truncated_throws() throws IOException {
        byte[] enc = ExploredCodec.encode(sample(1536), 1536);
        byte[] cut = Arrays.copyOf(enc, enc.length / 2);
        assertThrows(IOException.class, () -> ExploredCodec.decode(cut));
    }
}
