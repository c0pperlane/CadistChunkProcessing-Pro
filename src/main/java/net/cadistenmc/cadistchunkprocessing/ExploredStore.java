package net.cadistenmc.cadistchunkprocessing;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * On-disk persistence for fog-of-war explored sets. One file per player per
 * world: {@code plugins/CadistChunkProcessing-Pro/explored/<world-uuid>/<player-uuid>.ccpf}.
 * Pure byte I/O — the (de)serialization is {@link net.cadistenmc.cadistchunkprocessing.engine.ExploredCodec}.
 * Writes are atomic (temp file + replace) so a crash mid-write can never leave a
 * half-written file that would be silently misread.
 */
public final class ExploredStore {

    private final File baseDir;

    public ExploredStore(JavaPlugin plugin) {
        this.baseDir = new File(plugin.getDataFolder(), "explored");
    }

    private File fileFor(UUID world, UUID player) {
        return new File(new File(baseDir, world.toString()), player + ".ccpf");
    }

    /** Raw bytes of a player's saved set for this world, or null if none on disk. */
    public byte[] read(UUID world, UUID player) throws IOException {
        File f = fileFor(world, player);
        if (!f.isFile()) return null;
        return Files.readAllBytes(f.toPath());
    }

    /** Epoch-millis the saved file was last written, or 0 if absent (for expiry). */
    public long lastModified(UUID world, UUID player) {
        File f = fileFor(world, player);
        return f.isFile() ? f.lastModified() : 0L;
    }

    /** Atomically write a player's set for this world. */
    public void write(UUID world, UUID player, byte[] data) throws IOException {
        File dir = new File(baseDir, world.toString());
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("could not create " + dir);
        }
        File f = fileFor(world, player);
        File tmp = new File(dir, player + ".ccpf.tmp");
        Files.write(tmp.toPath(), data);
        try {
            Files.move(tmp.toPath(), f.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
