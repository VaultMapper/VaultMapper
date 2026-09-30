package com.nodiumhosting.vaultmapper.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

// Files.exists silently treats access/I/O errors as absence. Recovery must only
// treat an actually missing file as absent, or it could overwrite an unreadable save.
final class VaultSaveFiles {
    private VaultSaveFiles() {
    }

    static boolean exists(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    // Bytes read successfully, but not valid in a supported save format. Only
    // this kind of error can be salvaged; filesystem errors must block recovery.
    static final class CorruptDataException extends IOException {
        CorruptDataException(String message) {
            super(message);
        }

        CorruptDataException(String message, IOException cause) {
            super(message, cause);
        }
    }
}
