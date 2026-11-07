package dev.voxylod.generation;

import dev.voxylod.c2me.C2MEQueueProbe;
import dev.voxylod.voxy.VoxyBridge;

import java.util.List;
import java.util.Locale;

final class GenerationDebugHud {
    private long sampledAt;
    private long sampledProgress;
    private long sampledSettled;
    private long lastUpdate;
    private double chunksPerSecond = -1.0;
    private double settledPerSecond = -1.0;
    private List<String> lastLines = List.of("[Voxy LOD] 准备中");

    List<String> start(GenerationTask task) {
        this.sampledAt = task.startedAt;
        this.sampledProgress = task.checkpointCursor.index();
        this.sampledSettled = task.settledChunks();
        this.lastUpdate = 0L;
        this.chunksPerSecond = -1.0;
        this.settledPerSecond = -1.0;
        return update(task);
    }

    List<String> update(GenerationTask task) {
        long now = System.currentTimeMillis();
        long sampleDuration = now - this.sampledAt;
        if (sampleDuration >= 1_000L) {
            long progress = task.checkpointCursor.index();
            long settled = task.settledChunks();
            double instantaneous = (progress - this.sampledProgress) * 1_000.0 / sampleDuration;
            double settledInstantaneous = (settled - this.sampledSettled) * 1_000.0 / sampleDuration;
            this.chunksPerSecond = this.chunksPerSecond < 0.0
                    ? instantaneous : this.chunksPerSecond * 0.7 + instantaneous * 0.3;
            this.settledPerSecond = this.settledPerSecond < 0.0
                    ? settledInstantaneous : this.settledPerSecond * 0.7 + settledInstantaneous * 0.3;
            this.sampledAt = now;
            this.sampledProgress = progress;
            this.sampledSettled = settled;
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
        String settledSpeed = this.settledPerSecond < 0.0
                ? "--" : String.format(Locale.ROOT, "%.1f", this.settledPerSecond);
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
        } else if (task.throttleReason != null) {
            state = "限流 " + task.throttleReason;
        } else {
            state = "生成中";
        }
        C2MEQueueProbe.QueueStatus c2me = task.c2meQueues;
        String c2meQueue = task.c2me.enabled()
                ? "读 " + c2me.pendingReads() + '/' + C2MEQueueProbe.READ_QUEUE_LIMIT
                        + " 命令 " + c2me.storageTasks() + '/' + C2MEQueueProbe.STORAGE_TASK_LIMIT
                        + " 待写 " + c2me.cachedWrites() + '/' + C2MEQueueProbe.WRITE_CACHE_LIMIT
                        + " 写任务 " + c2me.writeFutures() + '/' + C2MEQueueProbe.WRITE_FUTURE_LIMIT
                        + " 卸载 " + c2me.pendingUnloads() + '/' + C2MEQueueProbe.UNLOAD_QUEUE_LIMIT
                : "卸载 " + c2me.pendingUnloads() + '/' + C2MEQueueProbe.UNLOAD_QUEUE_LIMIT;
        this.lastLines = List.of(
                "[Voxy LOD] " + state + " | " + task.level.dimension().location(),
                String.format(Locale.ROOT, "进度 %,d / %,d (%.2f%%) | 失败 %,d",
                        completed, total, percentage, task.failedChunks),
                "速度 完成 " + settledSpeed + " / 断点 " + speed + " 区块/秒 | 预计剩余 " + eta
                        + " | 已运行 " + formatDuration(elapsed),
                "在途 " + task.inFlight.size() + '/' + task.pendingChunks
                        + " | 断点窗口 " + task.completionWindow() + '/' + task.checkpointWindowLimit
                        + " | 转换线程 " + task.threads,
                "Voxy队列 保存 " + queues.saveTasks() + " / 渲染 " + queues.renderTasks()
                        + " | C2ME " + c2meQueue,
                "JVM堆 " + heapUsed + '/' + heapMaximum + " MiB"
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
