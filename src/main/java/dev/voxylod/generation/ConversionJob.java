package dev.voxylod.generation;

import dev.voxylod.voxy.VoxyBridge;

import java.util.concurrent.atomic.AtomicInteger;

final class ConversionJob implements Runnable {
    private static final int QUEUED = 0;
    private static final int RUNNING = 1;
    private static final int CANCELLED = 2;
    private static final int DONE = 3;

    private final GenerationManager manager;
    private final GenerationTask task;
    final ChunkWork work;
    private final AtomicInteger state = new AtomicInteger(QUEUED);
    private volatile Throwable failure;

    ConversionJob(GenerationManager manager, GenerationTask task, ChunkWork work) {
        this.manager = manager;
        this.task = task;
        this.work = work;
    }

    @Override
    public void run() {
        if (!this.state.compareAndSet(QUEUED, RUNNING)) {
            return;
        }
        try {
            VoxyBridge.ingest(this.work.snapshot);
        } catch (Throwable throwable) {
            this.failure = throwable;
        } finally {
            this.work.snapshot.close();
            this.state.set(DONE);
            this.manager.conversionFinished(this);
        }
    }

    boolean cancel() {
        if (!this.state.compareAndSet(QUEUED, CANCELLED)) {
            return false;
        }
        this.task.executor.remove(this);
        this.work.snapshot.close();
        return true;
    }

    GenerationTask task() {
        return this.task;
    }

    Throwable failure() {
        return this.failure;
    }
}
