package dev.voxylod.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunkio.common.C2MEStorageThread", remap = false)
public abstract class C2MEStorageReadProbe {
    @Unique
    private final AtomicInteger voxylod$pendingReads = new AtomicInteger();

    @Unique
    public int voxylod$pendingReads() {
        return this.voxylod$pendingReads.get();
    }

    @Inject(method = "getChunkData", at = @At("RETURN"), remap = false)
    private void voxylod$trackRead(long position, StreamTagVisitor scanner,
                                   CallbackInfoReturnable<CompletableFuture<CompoundTag>> callback) {
        CompletableFuture<CompoundTag> future = callback.getReturnValue();
        if (future == null || future.isDone()) {
            return;
        }
        this.voxylod$pendingReads.incrementAndGet();
        future.whenComplete((result, failure) -> this.voxylod$pendingReads.decrementAndGet());
    }
}
