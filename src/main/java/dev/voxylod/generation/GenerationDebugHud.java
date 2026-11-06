package dev.voxylod.generation;

import dev.voxylod.c2me.C2MEQueueProbe;
import dev.voxylod.voxy.VoxyBridge;

import java.util.List;
import java.util.Locale;

final class GenerationDebugHud {
    private long sampledAt;
    private long sampledProgress;
    private long lastUpdate;
    private double chunksPerSecond = -1.0;
    private List<String> lastLines = List.of("[Voxy LOD] 准备中");

    List<String> start(GenerationTask task) {
        this.sampledAt = task.startedAt;
        this.sampledProgress = task.checkpointCursor.index();
        this.lastUpdate = 0L;
        this.chunksPerSecond = -1.0;
        return update(task);
    }

    List<String> update(GenerationTask task) {
        long now = System.currentTimeMillis();
        long sampleDuration = now - this.sampledAt;
        if (sampleDuration >= 1_000L) {
            long progress = task.checkpointCursor.index();
            double instantaneous = (progress - this.sampledProgress) * 1_000.0 / sampleDuration;
            this.chunksPerSecond = this.chunksPerSecond < 0.0
                    ? instantaneous : this.chunksPerSecond * 0.7 + instantaneous * 0.3;
            this.sampledAt = now;
            this.sampledProgress = progress;
        }
        if (now - this.lastUpdate < 250L) {
            return this.lastLines;
        }
        this.lastUpdate = now;

        VoxyBridge.QueueStatus queues;
        try {
            queues = VoxyBridge.queueStatus(task.engine);
        } catch (RuntimeException exception) {
            queues = new VoxyBridge.QueueStatus(-1, -1, false);
        }
        long completed = task.checkpointCursor.index();
        long total = task.totalChunks;
        long remaining = Math.max(0L, total - completed);
        double percentage = total == 0L ? 100.0 : completed * 100.0 / total;
        String speed = this.chunksPerSecond < 0.0
                ? "--" : String.format(Locale.ROOT, "%.1f", this.chunksPerSecond);
        String eta = task.paused || task.stopping || task.fullyDispatched() || this.chunksPerSecond <= 0.001
                ? "--" : formatDuration((long) Math.ceil(remaining / this.chunksPerSecond));
        long elapsed = Math.max(0L, (now - task.startedAt) / 1_000L);
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long heapMaximum = runtime.maxMemory() / (1024L * 1024L);
        String state;
        if (task.stopping) {
            state = "停止中";
        } else if (task.paused) {
            state = "已暂停";
        } else if (task.fullyDispatched()) {
            state = "等待保存";
        } else if (!queues.rendererReady()) {
            state = "等待 Voxy";
        } else if (queues.saveTasks() >= task.saveQueueLimit
                || queues.renderTasks() >= task.renderQueueLimit
                || task.chunkWrites >= C2MEQueueProbe.STORAGE_QUEUE_LIMIT) {
            state = "队列限流";
        } else {
            state = "生成中";
        }
        String c2meQueue = task.c2me.enabled()
                ? task.chunkWrites + "/" + C2MEQueueProbe.STORAGE_QUEUE_LIMIT : "--";
        this.lastLines = List.of(
                "[Voxy LOD] " + state + " | " + task.level.dimension().location(),
                String.format(Locale.ROOT, "进度 %,d / %,d (%.2f%%) | 失败 %,d",
                        completed, total, percentage, task.failedChunks),
                "速度 " + speed + " 区块/秒 | 预计剩余 " + eta + " | 已运行 " + formatDuration(elapsed),
                "在途 " + task.inFlight.size() + '/' + task.pendingChunks
                        + " | 转换线程 " + task.threads
                        + " | Voxy队列 保存 " + queues.saveTasks() + " / 渲染 " + queues.renderTasks(),
                "C2ME存储队列 " + c2meQueue + " | JVM堆 " + heapUsed + '/' + heapMaximum + " MiB"
                        + " | 原点 [" + task.originBlockX + ", " + task.originBlockZ + ']'
                        + " | 半径 " + task.radiusChunks + " 区块");
        return this.lastLines;
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3_600L;
        long minutes = seconds / 60L % 60L;
        long secondPart = seconds % 60L;
        if (hours >= 24L) {
            return String.format(Locale.ROOT, "%d天 %02d:%02d:%02d",
                    hours / 24L, hours % 24L, minutes, secondPart);
        }
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secondPart);
    }
}
