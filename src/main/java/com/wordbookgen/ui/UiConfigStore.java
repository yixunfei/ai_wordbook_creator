package com.wordbookgen.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

/**
 * UI 配置持久化存储。
 */
public class UiConfigStore {

    private static final String SETTINGS_FILE_NAME = "ui-settings.json";

    private final ObjectMapper mapper;
    private final Path defaultSettingsDirectory;

    public UiConfigStore() {
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.defaultSettingsDirectory = Path.of(System.getProperty("user.home"), ".wordbookgen");
    }

    public Optional<UiSettings> load() {
        return load(defaultSettingsDirectory);
    }

    public Optional<UiSettings> load(Path configDirectory) {
        Path settingsPath = settingsPathForDirectory(configDirectory);
        try {
            if (!Files.exists(settingsPath) || Files.isDirectory(settingsPath)) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(settingsPath.toFile(), UiSettings.class));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public void save(UiSettings settings) throws IOException {
        save(settings, defaultSettingsDirectory);
    }

    public void save(UiSettings settings, Path configDirectory) throws IOException {
        Path target = settingsPathForDirectory(configDirectory);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = parent == null
                ? Files.createTempFile(target.getFileName().toString(), ".tmp")
                : Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), settings);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            tightenPermissions(target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public Path settingsPath() {
        return settingsPathForDirectory(defaultSettingsDirectory);
    }

    public Path settingsPathForDirectory(Path configDirectory) {
        Path directory = configDirectory == null ? defaultSettingsDirectory : configDirectory;
        return directory.toAbsolutePath().normalize().resolve(SETTINGS_FILE_NAME);
    }

    public Path defaultSettingsDirectory() {
        return defaultSettingsDirectory.toAbsolutePath().normalize();
    }

    private void tightenPermissions(Path target) {
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(target, ownerOnly);
        } catch (Exception ignored) {
            // Unsupported on non-POSIX file systems (e.g., Windows).
        }
    }
}
