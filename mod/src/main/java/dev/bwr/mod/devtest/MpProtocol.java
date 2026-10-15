package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.fcd.FcdSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared vocabulary of the multiplayer check (C3): the one dedicated server is the conductor and the two real clients
 * are its players. They talk through small property files in one directory on this machine ({@code -Dbwr.mp.dir}),
 * never through the game: the server writes {@code step-N.properties} (what each player should do), each client writes
 * {@code ack-N-A.properties} / {@code ack-N-B.properties} when it has done it, with everything it then sees on its own
 * client level. Everything the players DO goes through the real connection (the mod's panel payloads and vanilla
 * packets). Common code: no client or server only types. Part of the stripped devtest package.
 */
final class MpProtocol {
    static final int PORT = 25599;
    /** Client run names. */
    static final String A = "A", B = "B";

    private MpProtocol() {
    }

    static Path dir() {
        String dir = System.getProperty("bwr.mp.dir");
        if (dir == null || dir.isBlank()) throw new IllegalStateException("-Dbwr.mp.dir is not set");
        return Path.of(dir);
    }

    static Path step(int n) {
        return dir().resolve("step-" + n + ".properties");
    }

    static Path ack(int n, String who) {
        return dir().resolve("ack-" + n + "-" + who + ".properties");
    }

    /** Writes through a temporary file and a rename, so a reader never sees half a file. */
    static void write(Path file, Map<String, String> values) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, String> e : values.entrySet()) {
                text.append(e.getKey()).append('=').append(e.getValue().replace("\\", "\\\\").replace("\n", "\n")).append('\n');
            }
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file, e);
        }
    }

    /** The file's keys and values, or null when it does not exist (yet). */
    static Map<String, String> read(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) values.put(line.substring(0, eq), line.substring(eq + 1).replace("\n", "\n").replace("\\\\", "\\"));
            }
            return values;
        } catch (IOException e) {
            return null;
        }
    }

    /** Every rod's notch of a snapshot, comma separated, preceded by the selected rod: what a display shows, in one string. */
    static String digest(FcdSnapshot snapshot) {
        StringBuilder text = new StringBuilder("formed=").append(snapshot.formed()).append(" selected=")
                .append(snapshot.selected()).append(" rods=").append(snapshot.rodCount()).append(" notches=");
        for (int rod = 0; rod < snapshot.rodCount(); rod++) {
            if (rod > 0) text.append(',');
            text.append(snapshot.notch(rod));
        }
        return text.toString();
    }
}
