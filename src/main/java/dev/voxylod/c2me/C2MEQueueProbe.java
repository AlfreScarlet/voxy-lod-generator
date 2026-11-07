package dev.voxylod.c2me;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.ChunkStorage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class C2MEQueueProbe {
    public static final int READ_QUEUE_LIMIT = 64;
    public static final int STORAGE_TASK_LIMIT = 64;
    public static final int WRITE_CACHE_LIMIT = 64;
    public static final int WRITE_FUTURE_LIMIT = 128;
    public static final int UNLOAD_QUEUE_LIMIT = 64;

    private static final Field PENDING_UNLOADS;
    private static final Field UNLOAD_QUEUE;
    private static final Field WORKER;
    private static final Field BACKEND;
    private static final Field TASKS;
    private static final Field CACHE;
    private static final Field WRITES;
    private static final Method PENDING_READS;
    private static final RuntimeException FAILURE;

    static {
        try {
            PENDING_UNLOADS = ChunkMap.class.getDeclaredField("pendingUnloads");
            UNLOAD_QUEUE = ChunkMap.class.getDeclaredField("unloadQueue");
            PENDING_UNLOADS.setAccessible(true);
            UNLOAD_QUEUE.setAccessible(true);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    static {
        Field worker = null;
        Field backend = null;
        Field tasks = null;
        Field cache = null;
        Field writes = null;
        Method pendingReads = null;
        RuntimeException failure = null;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = C2MEQueueProbe.class.getClassLoader();
            }
            Class<?> vanillaInterface = Class.forName(
                    "com.ishland.c2me.rewrites.chunkio.common.C2MEStorageVanillaInterface", false, loader);
            Class<?> storageThread = Class.forName(
                    "com.ishland.c2me.rewrites.chunkio.common.C2MEStorageThread", false, loader);
            worker = ChunkStorage.class.getDeclaredField("worker");
            backend = vanillaInterface.getDeclaredField("backend");
            tasks = storageThread.getDeclaredField("taskSize");
            cache = storageThread.getDeclaredField("cache");
            writes = storageThread.getDeclaredField("writeFutures");
            pendingReads = storageThread.getMethod("voxylod$pendingReads");
            worker.setAccessible(true);
            backend.setAccessible(true);
            tasks.setAccessible(true);
            cache.setAccessible(true);
            writes.setAccessible(true);
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException | LinkageError exception) {
            failure = new IllegalStateException("C2ME 存储队列接口不可用", exception);
        }
        WORKER = worker;
        BACKEND = backend;
        TASKS = tasks;
        CACHE = cache;
        WRITES = writes;
        PENDING_READS = pendingReads;
        FAILURE = failure;
    }

    private final Object backend;
    private final Map<?, ?> pendingUnloads;
    private final Queue<?> unloadQueue;

    private C2MEQueueProbe(Object backend, ChunkMap chunkMap) throws IllegalAccessException {
        this.backend = backend;
        this.pendingUnloads = (Map<?, ?>) PENDING_UNLOADS.get(chunkMap);
        this.unloadQueue = (Queue<?>) UNLOAD_QUEUE.get(chunkMap);
    }

    public static C2MEQueueProbe open(ServerLevel level) {
        if (FAILURE != null) {
            throw FAILURE;
        }
        try {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            Object backend = null;
            if (BACKEND != null) {
                backend = BACKEND.get(WORKER.get(chunkMap));
                if (backend == null) {
                    throw new IllegalStateException("C2ME 存储后端尚未初始化");
                }
            }
            return new C2MEQueueProbe(backend, chunkMap);
        } catch (IllegalAccessException | RuntimeException exception) {
            throw new IllegalStateException("无法读取 C2ME 存储后端", exception);
        }
    }

    public boolean enabled() {
        return this.backend != null;
    }

    public QueueStatus queueStatus() {
        int unloads = this.pendingUnloads.size() + this.unloadQueue.size();
        if (this.backend == null) {
            return new QueueStatus(0, 0, 0, 0, unloads);
        }
        try {
            int tasks = ((AtomicInteger) TASKS.get(this.backend)).get();
            int cached = ((Map<?, ?>) CACHE.get(this.backend)).size();
            int writes = ((Set<?>) WRITES.get(this.backend)).size();
            int reads = (int) PENDING_READS.invoke(this.backend);
            return new QueueStatus(reads, tasks, cached, writes, unloads);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("无法读取 C2ME 存储队列", exception);
        }
    }

    public record QueueStatus(int pendingReads, int storageTasks, int cachedWrites, int writeFutures,
                              int pendingUnloads) {
        public static final QueueStatus EMPTY = new QueueStatus(0, 0, 0, 0, 0);

        public String saturatedReason() {
            if (this.pendingReads >= READ_QUEUE_LIMIT) {
                return "C2ME读取队列";
            }
            if (this.storageTasks >= STORAGE_TASK_LIMIT) {
                return "C2ME存储命令队列";
            }
            if (this.cachedWrites >= WRITE_CACHE_LIMIT) {
                return "C2ME待写数据队列";
            }
            if (this.writeFutures >= WRITE_FUTURE_LIMIT) {
                return "C2ME写盘任务队列";
            }
            if (this.pendingUnloads >= UNLOAD_QUEUE_LIMIT) {
                return "区块卸载队列";
            }
            return null;
        }
    }
}
