package dev.voxylod.voxy;

import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChunkSnapshot {
    final WorldEngine engine;
    final int chunkX;
    final int chunkZ;
    final int minSectionY;
    final SectionSnapshot[] sections;
    private final AtomicBoolean closed = new AtomicBoolean();

    ChunkSnapshot(WorldEngine engine, int chunkX, int chunkZ, int minSectionY, SectionSnapshot[] sections) {
        this.engine = engine;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSectionY = minSectionY;
        this.sections = sections;
    }

    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            Arrays.fill(this.sections, null);
            if (this.engine.isLive()) {
                this.engine.releaseRef();
            }
        }
    }

    record SectionSnapshot(
            PalettedContainer<BlockState> states,
            PalettedContainerRO<Holder<Biome>> biomes,
            DataLayer blockLight,
            DataLayer skyLight,
            boolean onlyAir) {
    }
}
