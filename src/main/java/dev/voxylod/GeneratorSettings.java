package dev.voxylod;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class GeneratorSettings {
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("voxylod.properties");

    public int originX;
    public int originZ;
    public int radiusChunks;
    public int threads;
    public int pendingChunks;
    public int saveQueueLimit;
    public int renderQueueLimit;

    private GeneratorSettings() {
        this.threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        this.originX = 0;
        this.originZ = 0;
        this.radiusChunks = 512;
        this.pendingChunks = defaultPending(this.threads);
        this.saveQueueLimit = 64;
        this.renderQueueLimit = 512;
    }

    public static GeneratorSettings load() {
        GeneratorSettings settings = new GeneratorSettings();
        if (!Files.isRegularFile(FILE)) {
            return settings;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(FILE)) {
            properties.load(input);
        } catch (IOException exception) {
            VoxyLodMod.LOGGER.warn("Unable to read {}; using defaults", FILE, exception);
            return settings;
        }
        settings.originX = readInt(properties, "originX", settings.originX, Integer.MIN_VALUE, Integer.MAX_VALUE);
        settings.originZ = readInt(properties, "originZ", settings.originZ, Integer.MIN_VALUE, Integer.MAX_VALUE);
        settings.radiusChunks = readInt(properties, "radiusChunks", settings.radiusChunks, 0, 4096);
        settings.threads = readInt(properties, "threads", settings.threads, 1, 64);
        settings.pendingChunks = readInt(properties, "pendingChunks", settings.pendingChunks, 1, 64);
        settings.saveQueueLimit = readInt(properties, "saveQueueLimit", settings.saveQueueLimit, 16, 1024);
        settings.renderQueueLimit = readInt(properties, "renderQueueLimit", settings.renderQueueLimit, 64, 4096);
        return settings;
    }

    public void save() {
        Properties properties = new Properties();
        properties.setProperty("originX", Integer.toString(this.originX));
        properties.setProperty("originZ", Integer.toString(this.originZ));
        properties.setProperty("radiusChunks", Integer.toString(this.radiusChunks));
        properties.setProperty("threads", Integer.toString(this.threads));
        properties.setProperty("pendingChunks", Integer.toString(this.pendingChunks));
        properties.setProperty("saveQueueLimit", Integer.toString(this.saveQueueLimit));
        properties.setProperty("renderQueueLimit", Integer.toString(this.renderQueueLimit));
        try {
            Files.createDirectories(FILE.getParent());
            Path temporary = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Voxy LOD Generator: origin in blocks, radius and queues in chunks");
            }
            try {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            VoxyLodMod.LOGGER.warn("Unable to save {}", FILE, exception);
        }
    }

    public void setThreads(int value) {
        this.threads = value;
        this.pendingChunks = defaultPending(value);
    }

    public static int defaultPending(int threads) {
        return Math.min(64, Math.max(8, threads * 2));
    }

    private static int readInt(Properties properties, String key, int fallback, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
            return value < minimum || value > maximum ? fallback : value;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
