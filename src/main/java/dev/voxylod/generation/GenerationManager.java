package dev.voxylod.generation;

import dev.voxylod.GeneratorSettings;
import dev.voxylod.VoxyLodMod;
import dev.voxylod.mixin.ServerChunkCacheInvoker;
import dev.voxylod.voxy.ChunkSnapshot;
import dev.voxylod.voxy.VoxyBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class GenerationManager {
    private static final TicketType<Long> LOD_TICKET =
            TicketType.<Long>create("voxylod", java.util.Comparator.naturalOrder(), 0);

    private final GeneratorSettings settings;
    private final GenerationDebugHud debugHud = new GenerationDebugHud();
    private MinecraftServer server;
    private volatile GenerationTask active;
    private volatile List<String> debugLines = List.of();
    private volatile String lastStatus = "尚未启动";

    public GenerationManager(GeneratorSettings settings) {
        this.settings = settings;
    }

    public void serverStarted(MinecraftServer server) {
        this.server = server;
        this.active = null;
        this.lastStatus = "尚未启动";
    }

    public String start(ServerLevel level, boolean restart) {
        requireIdle();
        this.server = level.getServer();
        CircularCursor cursor = restart ? null : CheckpointStore.load(level, this.settings);
        boolean resumed = cursor != null && cursor.index() > 0;
        if (cursor != null && (cursor.isDone()
                || cursor.index() == CircularCursor.countChunks(this.settings.radiusChunks))) {
            return "该生成范围已完成；如需重做，请使用 /voxylod restart";
        }
        if (cursor == null) {
            cursor = new CircularCursor(
                    Math.floorDiv(this.settings.originX, 16),
                    Math.floorDiv(this.settings.originZ, 16),
                    this.settings.radiusChunks);
        }
        VoxyBridge.EngineHandle engine = VoxyBridge.open(level);
        GenerationTask task;
        try {
            task = new GenerationTask(level, engine, this.settings, cursor);
        } catch (RuntimeException exception) {
            engine.close();
            throw exception;
        }
        this.active = task;
        this.debugLines = this.debugHud.start(task);
        CheckpointStore.save(task);
        this.lastStatus = "运行中";
        VoxyLodMod.LOGGER.info("{} Voxy LOD generation in {}: origin=[{}, {}], radius={} chunks, total={}, threads={}, inFlightLimit={}",
                resumed ? "Resuming" : "Starting", level.dimension().location(), this.settings.originX,
                this.settings.originZ, this.settings.radiusChunks, task.totalChunks, task.threads, task.pendingChunks);
        return (resumed ? "已恢复任务" : "已启动任务") + "：维度=" + level.dimension().location()
                + "，原点方块=[" + this.settings.originX + ", " + this.settings.originZ + "]"
                + "，半径=" + task.radiusChunks + " 区块，总数=" + task.totalChunks
                + "，线程=" + task.threads + "，在途上限=" + task.pendingChunks;
    }

    public String stop() {
        GenerationTask task = this.active;
        if (task == null) {
            return "当前没有运行中的任务";
        }
        beginStop(task);
        this.lastStatus = "正在停止并释放在途区块";
        return "正在停止任务；已调度但未完成的区块将从断点重新处理";
    }

    public String pause() {
        GenerationTask task = this.active;
        if (task == null || task.stopping) {
            return "当前没有可暂停的任务";
        }
        task.paused = true;
        this.lastStatus = "已暂停派发，等待在途任务排空";
        return "已暂停新任务派发；在途区块会继续完成以释放内存";
    }

    public String resume() {
        GenerationTask task = this.active;
        if (task == null || task.stopping) {
            return "当前没有可恢复的任务";
        }
        task.paused = false;
        this.lastStatus = "运行中";
        return "已恢复任务派发";
    }

    public void requireIdle() {
        if (this.active != null) {
            throw new IllegalStateException("请先执行 /voxylod stop，并等待任务完全停止");
        }
    }

    public List<String> debugLines() {
        return this.active == null
                ? List.of("[Voxy LOD] " + this.lastStatus)
                : this.debugLines;
    }

    public GeneratorSettings settings() {
        return this.settings;
    }

    public String status() {
        StringBuilder message = new StringBuilder();
        GenerationTask task = this.active;
        if (task == null) {
            message.append(this.lastStatus).append("；配置：原点方块=[")
                    .append(this.settings.originX).append(", ").append(this.settings.originZ)
                    .append("]，半径=").append(this.settings.radiusChunks)
                    .append(" 区块，线程=").append(this.settings.threads)
                    .append("，在途上限=").append(this.settings.pendingChunks);
        } else {
            VoxyBridge.QueueStatus queues = VoxyBridge.queueStatus(task.engine);
            double progress = task.totalChunks == 0 ? 100.0
                    : task.checkpointCursor.index() * 100.0 / task.totalChunks;
            message.append(task.stopping ? "停止中" : task.paused ? "已暂停" : "运行中")
                    .append("；连续完成=").append(task.checkpointCursor.index()).append('/').append(task.totalChunks)
                    .append("（").append(String.format(Locale.ROOT, "%.3f", progress)).append("%）")
                    .append("；成功=").append(task.completedChunks)
                    .append("，失败=").append(task.failedChunks)
                    .append("；在途=").append(task.inFlight.size()).append('/').append(task.pendingChunks)
                    .append("，转换线程=").append(task.threads)
                    .append("；Voxy保存/渲染队列=").append(queues.saveTasks()).append('/').append(queues.renderTasks());
        }
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long maximum = runtime.maxMemory() / (1024L * 1024L);
        message.append("；JVM堆=").append(used).append('/').append(maximum).append(" MiB");
        return message.toString();
    }

    public void tick(MinecraftServer server) {
        GenerationTask task = this.active;
        if (task == null || task.level.getServer() != server) {
            return;
        }
        updateDistanceManager(task);
        try {
            task.chunkWrites = task.c2me.pendingWrites();
        } catch (RuntimeException exception) {
            task.paused = true;
            this.lastStatus = "C2ME 写盘队列检查失败，已自动暂停";
            VoxyLodMod.LOGGER.error("C2ME write queue inspection failed; generation paused", exception);
            return;
        }
        this.debugLines = this.debugHud.update(task);
        long now = System.currentTimeMillis();
        if (task.stopping) {
            if (task.inFlight.isEmpty()) {
                finishTask(task, "已停止；断点=" + task.checkpointCursor.index() + '/' + task.totalChunks);
            }
            return;
        }
        if (now - task.lastCheckpointSave >= 15_000L
                && task.lastSavedCheckpointIndex != task.checkpointCursor.index()) {
            CheckpointStore.save(task);
            task.lastCheckpointSave = now;
            task.lastSavedCheckpointIndex = task.checkpointCursor.index();
        }
        if (now - task.lastProgressLog >= 60_000L) {
            task.lastProgressLog = now;
            VoxyLodMod.LOGGER.info("Voxy LOD progress {}/{} ({}%), failed={}, inFlight={}, c2meWrites={}, heap={} MiB",
                    task.checkpointCursor.index(), task.totalChunks,
                    String.format(Locale.ROOT, "%.3f", task.totalChunks == 0 ? 100.0
                            : task.checkpointCursor.index() * 100.0 / task.totalChunks),
                    task.failedChunks, task.inFlight.size(), task.chunkWrites,
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024L * 1024L));
        }
        if (task.paused) {
            return;
        }
        if (task.fullyDispatched()) {
            if (task.inFlight.isEmpty() && VoxyBridge.queueStatus(task.engine).saveTasks() == 0) {
                finishTask(task, "生成并完成保存；成功=" + task.completedChunks + "，失败=" + task.failedChunks);
            }
            return;
        }
        VoxyBridge.QueueStatus queues;
        try {
            queues = VoxyBridge.queueStatus(task.engine);
        } catch (RuntimeException exception) {
            task.paused = true;
            this.lastStatus = "Voxy 队列检查失败，已自动暂停";
            VoxyLodMod.LOGGER.error("Voxy LOD queue inspection failed; generation paused", exception);
            return;
        }
        if (!queues.rendererReady()
                || queues.saveTasks() >= task.saveQueueLimit
                || queues.renderTasks() >= task.renderQueueLimit
                || task.chunkWrites >= dev.voxylod.c2me.C2MEQueueProbe.STORAGE_QUEUE_LIMIT) {
            return;
        }
        long completionWindow = task.dispatchCursor.index() - task.checkpointCursor.index();
        int budget = (int) Math.min(task.threads, task.pendingChunks - completionWindow);
        while (budget-- > 0 && !task.fullyDispatched() && !task.stopping) {
            dispatch(task);
        }
        updateDistanceManager(task);
    }

    public void serverStopping(MinecraftServer server) {
        GenerationTask task = this.active;
        if (task == null || task.level.getServer() != server) {
            this.server = null;
            return;
        }
        beginStop(task);
        CheckpointStore.save(task);
        task.executor.shutdownNow();
        updateDistanceManager(task);
        task.inFlight.clear();
        task.engine.close();
        this.active = null;
        this.server = null;
        this.lastStatus = "服务器停止，任务已取消";
    }

    private void dispatch(GenerationTask task) {
        long sequence = task.dispatchCursor.index();
        long position = task.dispatchCursor.next();
        if (position == ChunkPos.INVALID_CHUNK_POS) {
            return;
        }
        ChunkWork work = new ChunkWork(sequence, position);
        task.inFlight.put(sequence, work);
        ServerChunkCache cache = task.level.getChunkSource();
        ChunkPos chunkPos = new ChunkPos(position);
        try {
            cache.addRegionTicket(LOD_TICKET, chunkPos, 0, position);
            work.ticketHeld = true;
            ((ServerChunkCacheInvoker) cache).voxylod$runDistanceManagerUpdates();
            CompletableFuture<ChunkResult<ChunkAccess>> future = ((ServerChunkCacheInvoker) cache)
                    .voxylod$getChunkFutureMainThread(work.chunkX(), work.chunkZ(), ChunkStatus.FULL, false);
            work.future = future;
            future.whenComplete((result, failure) -> futureCompleted(task, work, result, failure));
        } catch (Throwable throwable) {
            work.cancelled = true;
            removeTicket(task, work);
            settleWork(task, work, false, throwable);
        }
    }

    private void futureCompleted(GenerationTask task, ChunkWork work, ChunkResult<ChunkAccess> result,
                                 Throwable failure) {
        MinecraftServer server = task.level.getServer();
        if (Thread.currentThread() == server.getRunningThread()) {
            captureCompletedChunk(task, work, result, failure);
        } else {
            server.execute(() -> captureCompletedChunk(task, work, result, failure));
        }
    }

    private void captureCompletedChunk(GenerationTask task, ChunkWork work, ChunkResult<ChunkAccess> result,
                                       Throwable failure) {
        if (this.active != task || work.cancelled || task.stopping) {
            return;
        }
        work.future = null;
        removeTicket(task, work);
        if (failure != null || result == null || !result.isSuccess()
                || !(result.orElse(null) instanceof LevelChunk chunk)) {
            settleWork(task, work, false, failure == null
                    ? new IllegalStateException("Chunk generation did not return a full LevelChunk") : failure);
            return;
        }
        try {
            work.snapshot = VoxyBridge.capture(chunk, task.engine.engine());
            ConversionJob conversion = new ConversionJob(this, task, work);
            work.conversion = conversion;
            task.executor.execute(conversion);
        } catch (Throwable throwable) {
            if (work.conversion != null) {
                work.conversion.cancel();
            } else if (work.snapshot != null) {
                work.snapshot.close();
            }
            settleWork(task, work, false, throwable);
        }
    }

    void conversionFinished(ConversionJob conversion) {
        GenerationTask task = conversionTask(conversion);
        if (this.active != task) {
            return;
        }
        MinecraftServer server = task.level.getServer();
        if (Thread.currentThread() == server.getRunningThread()) {
            settleConversion(task, conversion);
        } else {
            server.execute(() -> {
                if (this.active == task) {
                    settleConversion(task, conversion);
                }
            });
        }
    }

    private GenerationTask conversionTask(ConversionJob conversion) {
        return conversion.task();
    }

    private void settleConversion(GenerationTask task, ConversionJob conversion) {
        settleWork(task, conversion.work, conversion.failure() == null, conversion.failure());
    }

    private void settleWork(GenerationTask task, ChunkWork work, boolean success, Throwable failure) {
        if (!task.inFlight.remove(work.sequence, work)) {
            return;
        }
        work.future = null;
        work.snapshot = null;
        work.conversion = null;
        if (success) {
            task.completedChunks++;
        } else {
            task.failedChunks++;
            if (task.failedChunks <= 3 || task.failedChunks % 1000 == 0) {
                VoxyLodMod.LOGGER.error("Voxy LOD chunk [{}, {}] failed", work.chunkX(), work.chunkZ(), failure);
            }
        }
        task.recordSettled(work.sequence);
    }

    private void beginStop(GenerationTask task) {
        if (task.stopping) {
            return;
        }
        task.stopping = true;
        for (ChunkWork work : new ArrayList<>(task.inFlight.values())) {
            work.cancelled = true;
            CompletableFuture<ChunkResult<ChunkAccess>> future = work.future;
            work.future = null;
            removeTicket(task, work);
            if (future != null) {
                future.cancel(false);
                task.inFlight.remove(work.sequence, work);
                continue;
            }
            ConversionJob conversion = work.conversion;
            if (conversion != null && conversion.cancel()) {
                work.snapshot = null;
                work.conversion = null;
                task.inFlight.remove(work.sequence, work);
            }
        }
        updateDistanceManager(task);
    }

    private void finishTask(GenerationTask task, String status) {
        CheckpointStore.save(task);
        task.executor.shutdown();
        task.engine.close();
        this.active = null;
        this.lastStatus = status;
        VoxyLodMod.LOGGER.info(status);
    }

    private void removeTicket(GenerationTask task, ChunkWork work) {
        if (!work.ticketHeld) {
            return;
        }
        work.ticketHeld = false;
        task.level.getChunkSource().removeRegionTicket(LOD_TICKET, new ChunkPos(work.position), 0, work.position);
        task.distanceManagerDirty = true;
    }

    private void updateDistanceManager(GenerationTask task) {
        if (!task.distanceManagerDirty) {
            return;
        }
        task.distanceManagerDirty = false;
        ((ServerChunkCacheInvoker) task.level.getChunkSource()).voxylod$runDistanceManagerUpdates();
    }
}
