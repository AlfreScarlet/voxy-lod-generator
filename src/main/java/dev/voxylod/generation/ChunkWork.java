package dev.voxylod.generation;

import dev.voxylod.voxy.ChunkSnapshot;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.concurrent.CompletableFuture;

final class ChunkWork {
    final long sequence;
    final long position;
    CompletableFuture<ChunkResult<ChunkAccess>> future;
    ChunkSnapshot snapshot;
    ConversionJob conversion;
    boolean cancelled;
    boolean ticketHeld;

    ChunkWork(long sequence, long position) {
        this.sequence = sequence;
        this.position = position;
    }

    int chunkX() {
        return ChunkPos.getX(this.position);
    }

    int chunkZ() {
        return ChunkPos.getZ(this.position);
    }
}
