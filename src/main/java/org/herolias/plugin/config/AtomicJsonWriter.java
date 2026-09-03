package org.herolias.plugin.config;

import com.google.gson.Gson;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Writes JSON to a temp file in the target directory, forces it to disk, and
 * atomically moves it over the target so a crash mid-write never leaves a
 * truncated config behind.
 */
final class AtomicJsonWriter {

    private AtomicJsonWriter() {
    }

    static void write(File file, Object value, Gson gson) throws IOException {
        Path target = file.toPath().toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        } else {
            parent = Path.of(".");
        }

        Path temp = Files.createTempFile(parent, file.getName(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                    Writer writer = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))) {
                gson.toJson(value, writer);
                writer.flush();
                // Make sure the bytes are on disk before the rename makes them visible
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers Gson's unchecked JsonIOException
            Files.deleteIfExists(temp);
            throw e;
        }
    }
}
