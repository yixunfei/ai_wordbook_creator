package com.wordbookgen.core.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordbookgen.core.model.WordbookCheckpoint;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;

/**
 * checkpoint 文件读写。
 */
public class CheckpointStore {

    private final ObjectMapper mapper;

    public CheckpointStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<WordbookCheckpoint> load(Path path) throws IOException {
        if (path == null || !Files.exists(path) || Files.isDirectory(path)) {
            return Optional.empty();
        }
        return Optional.of(mapper.readValue(path.toFile(), WordbookCheckpoint.class));
    }

    public void save(Path path, WordbookCheckpoint checkpoint) throws IOException {
        if (path == null || checkpoint == null) {
            return;
        }

        Path target = path.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        checkpoint.setUpdatedAt(Instant.now().toString());

        Path temp = parent == null
                ? Files.createTempFile(target.getFileName().toString(), ".tmp")
                : Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), checkpoint);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void deleteIfExists(Path path) throws IOException {
        if (path != null && Files.exists(path)) {
            Files.delete(path);
        }
    }
}
