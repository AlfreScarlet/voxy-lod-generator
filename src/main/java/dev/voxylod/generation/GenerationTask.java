package dev.voxylod.generation;

import dev.voxylod.GeneratorSettings;
import dev.voxylod.c2me.C2MEQueueProbe;
import dev.voxylod.voxy.VoxyBridge;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class GenerationTask {
    final ServerLevel level;
    final VoxyBridge.EngineHandle engine;
    final int originBlockX;
    final int originBlockZ;
    final int radiusChunks;
    final int threads;
    final int pendingChunks;
    final int saveQueueLimit;
    final int renderQueueLimit;
    final long totalChunks;
    final CircularCursor dispatchCursor;
    final CircularCursor checkpointCursor;
    final Map<Long, ChunkWork> inFlight = new HashMap<>();
    final Set<Long> settledOutOfOrder = new HashSet<>();
    final ThreadPoolExecutor executor;
    final C2MEQueueProbe c2me;
    final long startedAt = System.currentTimeMillis();
    long nextCheckpointIndex;
    long completedChunks;
    long failedChunks;
    int chunkWrites;
    long lastCheckpointSave;
    long lastSavedCheckpointIndex = -1;
    long lastProgressLog;
    boolean distanceManagerDirty;
    boolean paused;
    boolean stopping;

    GenerationTask(ServerLevel level, VoxyBridge.EngineHandle engine, GeneratorSettings settings,
                   CircularCursor cursor) {
        this.level = level;
        this.c2me = C2MEQueueProbe.open(level);
        this.engine = engine;
        this.originBlockX = settings.originX;
        this.originBlockZ = settings.originZ;
        this.radiusChunks = settings.radiusChunks;
        this.threads = settings.threads;
        this.pendingChunks = settings.pendingChunks;
        this.saveQueueLimit = settings.saveQueueLimit;
        this.renderQueueLimit = settings.renderQueueLimit;
        this.totalChunks = CircularCursor.countChunks(settings.radiusChunks);
        this.dispatchCursor = cursor;
        this.checkpointCursor = cursor.copy();
        this.nextCheckpointIndex = cursor.index();
        this.completedChunks = cursor.index();
        this.lastCheckpointSave = System.currentTimeMillis();
        this.lastProgressLog = this.lastCheckpointSave;
        this.executor = new ThreadPoolExecutor(
                settings.threads,
                settings.threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.pendingChunks),
                new WorkerFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    void recordSettled(long sequence) {
        this.settledOutOfOrder.add(sequence);
        while (this.settledOutOfOrder.remove(this.nextCheckpointIndex)) {
            long position = this.checkpointCursor.next();
            if (position == net.minecraft.world.level.ChunkPos.INVALID_CHUNK_POS) {
                throw new IllegalStateException("Checkpoint advanced past the generation range");
            }
            this.nextCheckpointIndex++;
        }
    }

    boolean fullyDispatched() {
        return this.dispatchCursor.isDone();
    }

    long elapsedSeconds() {
        return Math.max(1L, (System.currentTimeMillis() - this.startedAt) / 1000L);
    }

    private static final class WorkerFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Voxy-LOD-Converter-" + this.nextId.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }
    }
}
