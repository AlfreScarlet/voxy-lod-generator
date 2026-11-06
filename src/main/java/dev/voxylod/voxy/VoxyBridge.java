package dev.voxylod.voxy;

import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.service.SectionSavingService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoxyBridge {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE =
            ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private static final Field SAVING_SERVICE;

    static {
        try {
            SAVING_SERVICE = VoxyInstance.class.getDeclaredField("savingService");
            SAVING_SERVICE.setAccessible(true);
        } catch (NoSuchFieldException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private VoxyBridge() {
    }

    public static EngineHandle open(ServerLevel level) {
        VoxyInstance instance = VoxyCommon.getInstance();
        if (instance == null || !instance.isRunning()) {
            throw new IllegalStateException("Voxy 尚未初始化，请进入世界并启用 Voxy 后重试");
        }
        WorldIdentifier identifier = WorldIdentifier.of(level);
        if (identifier == null || !instance.isIngestEnabled(identifier)) {
            throw new IllegalStateException("当前维度未启用 Voxy LOD 写入");
        }
        WorldEngine engine = instance.getOrCreate(identifier);
        if (engine == null || !engine.isLive()) {
            throw new IllegalStateException("无法创建当前维度的 Voxy WorldEngine");
        }
        ClientQueueProbe.resolve();
        engine.acquireRef();
        try {
            QueueStatus queues = queueStatus(instance);
            if (!queues.rendererReady()) {
                throw new IllegalStateException("Voxy 渲染器尚未就绪，请稍后重试");
            }
            return new EngineHandle(instance, engine);
        } catch (RuntimeException exception) {
            engine.releaseRef();
            throw exception;
        }
    }

    public static QueueStatus queueStatus(EngineHandle handle) {
        return queueStatus(handle.instance);
    }

    private static QueueStatus queueStatus(VoxyInstance instance) {
        try {
            int saveTasks = ((SectionSavingService) SAVING_SERVICE.get(instance)).getTaskCount();
            int renderTasks = ClientQueueProbe.renderTasks();
            return new QueueStatus(saveTasks, renderTasks, renderTasks >= 0);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to inspect Voxy saving queue", exception);
        }
    }

    public static ChunkSnapshot capture(LevelChunk chunk, WorldEngine engine) {
        engine.acquireRef();
        try {
            LevelChunkSection[] sourceSections = chunk.getSections();
            ChunkSnapshot.SectionSnapshot[] sections =
                    new ChunkSnapshot.SectionSnapshot[sourceSections.length];
            int minSectionY = chunk.getMinSection();
            var lightEngine = chunk.getLevel().getLightEngine();
            var blockLightEngine = lightEngine.getLayerListener(LightLayer.BLOCK);
            var skyLightEngine = lightEngine.getLayerListener(LightLayer.SKY);
            for (int index = 0; index < sourceSections.length; index++) {
                LevelChunkSection source = sourceSections[index];
                if (source == null) {
                    continue;
                }
                SectionPos position = SectionPos.of(chunk.getPos(), minSectionY + index);
                DataLayer blockLight = blockLightEngine.getDataLayerData(position);
                DataLayer skyLight = skyLightEngine.getDataLayerData(position);
                sections[index] = new ChunkSnapshot.SectionSnapshot(
                        source.getStates().copy(),
                        ((net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>)
                                source.getBiomes()).copy(),
                        blockLight == null ? null : blockLight.copy(),
                        skyLight == null ? null : skyLight.copy(),
                        source.hasOnlyAir());
            }
            return new ChunkSnapshot(engine, chunk.getPos().x, chunk.getPos().z, minSectionY, sections);
        } catch (Throwable throwable) {
            engine.releaseRef();
            throw throwable;
        }
    }

    public static void ingest(ChunkSnapshot snapshot) {
        WorldEngine engine = snapshot.engine;
        for (int index = 0; index < snapshot.sections.length; index++) {
            ChunkSnapshot.SectionSnapshot section = snapshot.sections[index];
            snapshot.sections[index] = null;
            if (section == null) {
                continue;
            }
            VoxelizedSection converted = SECTION_CACHE.get().setPosition(
                    snapshot.chunkX, snapshot.minSectionY + index, snapshot.chunkZ);
            if (section.onlyAir() && section.blockLight() == null && section.skyLight() == null) {
                converted.zero();
            } else {
                WorldConversionFactory.convert(
                        converted,
                        engine.getMapper(),
                        section.states(),
                        section.biomes(),
                        lighting(section.blockLight(), section.skyLight()));
                WorldVoxilizedSectionMipper.mipSection(converted, engine.getMapper());
            }
            WorldUpdater.insertUpdate(engine, converted);
        }
    }

    private static ILightingSupplier lighting(DataLayer block, DataLayer sky) {
        if (block == null && sky == null) {
            return (x, y, z) -> 0;
        }
        return (x, y, z) -> {
            int blockValue = block == null ? 0 : Math.min(15, block.get(x, y, z));
            int skyValue = sky == null ? 0 : Math.min(15, sky.get(x, y, z));
            return (byte) (skyValue | (blockValue << 4));
        };
    }

    public static final class EngineHandle {
        private final VoxyInstance instance;
        final WorldEngine engine;
        private final AtomicBoolean closed = new AtomicBoolean();

        private EngineHandle(VoxyInstance instance, WorldEngine engine) {
            this.instance = instance;
            this.engine = engine;
        }

        public WorldEngine engine() {
            return this.engine;
        }

        public void close() {
            if (this.closed.compareAndSet(false, true) && this.engine.isLive()) {
                this.engine.releaseRef();
            }
        }
    }

    public record QueueStatus(int saveTasks, int renderTasks, boolean rendererReady) {
    }
}
