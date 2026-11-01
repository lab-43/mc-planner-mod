package dev.lab43.mcplanner.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** File helpers: atomic writes (temp file + move) and owner-only permissions. */
final class Files2 {
    private Files2() {}

    static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** Writes text atomically: a temp file next to the target, then a (atomic when possible) move. */
    static void writeAtomic(Path file, String text, boolean ownerOnly) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        // createTempFile makes the file owner-only (rw-------) on POSIX file systems
        Path tmp = Files.createTempFile(dir, "." + file.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            if (!ownerOnly && posix()) {
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
                } catch (IOException | UnsupportedOperationException ignored) {
                    // keep owner-only
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (ownerOnly) restrict(file);
    }

    /** rw------- on POSIX file systems (no-op elsewhere). */
    static void restrict(Path file) {
        if (!posix()) return;
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(Files.isDirectory(file) ? "rwx------" : "rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // best effort
        }
    }

    static String read(Path file) throws IOException {
        String s = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }
}
