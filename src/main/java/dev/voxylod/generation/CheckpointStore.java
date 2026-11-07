package dev.voxylod.generation;

import dev.voxylod.GeneratorSettings;
import dev.voxylod.VoxyLodMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class CheckpointStore {
    private CheckpointStore() {
    }

    static CircularCursor load(ServerLevel level, GeneratorSettings settings) {
        Path file = file(level, settings.originX, settings.originZ, settings.radiusChunks);
        if (!Files.isRegularFile(file)) {
            Path legacyFile = legacyFile(level);
            if (!Files.isRegularFile(legacyFile)) {
                return null;
            }
            file = legacyFile;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            if (settings.originX != Integer.parseInt(properties.getProperty("originX"))
                    || settings.originZ != Integer.parseInt(properties.getProperty("originZ"))
                    || settings.radiusChunks != Integer.parseInt(properties.getProperty("radiusChunks"))) {
                return null;
            }
            int centerX = Math.floorDiv(settings.originX, 16);
            int centerZ = Math.floorDiv(settings.originZ, 16);
            int ring = Integer.parseInt(properties.getProperty("ring"));
            int side = Integer.parseInt(properties.getProperty("side"));
            int offset = Integer.parseInt(properties.getProperty("offset"));
            long index = Long.parseLong(properties.getProperty("index"));
            boolean returnedCenter = Boolean.parseBoolean(properties.getProperty("returnedCenter"));
            long total = CircularCursor.countChunks(settings.radiusChunks);
            if (ring < 0 || side < 0 || side > 3 || offset < 0 || index < 0 || index > total) {
                return null;
            }
            return new CircularCursor(centerX, centerZ, settings.radiusChunks,
                    ring, side, offset, index, returnedCenter);
        } catch (IOException | RuntimeException exception) {
            VoxyLodMod.LOGGER.warn("Ignoring invalid Voxy LOD checkpoint {}", file, exception);
            return null;
        }
    }

    static void save(GenerationTask task) {
        CircularCursor cursor = task.checkpointCursor;
        Properties properties = new Properties();
        properties.setProperty("originX", Integer.toString(task.originBlockX));
        properties.setProperty("originZ", Integer.toString(task.originBlockZ));
        properties.setProperty("radiusChunks", Integer.toString(task.radiusChunks));
        properties.setProperty("ring", Integer.toString(cursor.ring()));
        properties.setProperty("side", Integer.toString(cursor.side()));
        properties.setProperty("offset", Integer.toString(cursor.offset()));
        properties.setProperty("index", Long.toString(cursor.index()));
        properties.setProperty("returnedCenter", Boolean.toString(cursor.returnedCenter()));
        Path file = file(task.level, task.originBlockX, task.originBlockZ, task.radiusChunks);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Voxy LOD continuous completion cursor");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            VoxyLodMod.LOGGER.warn("Unable to save Voxy LOD checkpoint {}", file, exception);
        }
    }

    private static Path file(ServerLevel level, int originX, int originZ, int radiusChunks) {
        return checkpointRoot(level)
                .resolve(dimension(level))
                .resolve("x" + originX + "_z" + originZ + "_r" + radiusChunks + ".properties");
    }

    private static Path legacyFile(ServerLevel level) {
        return checkpointRoot(level).resolve(dimension(level) + ".properties");
    }

    private static Path checkpointRoot(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("voxylod");
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().location().toString().replace(':', '_').replace('/', '_');
    }
}
