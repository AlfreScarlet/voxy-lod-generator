package dev.voxylod.generation;

import net.minecraft.world.level.ChunkPos;

public final class CircularCursor {
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private int ring;
    private int side;
    private int offset;
    private long index;
    private boolean returnedCenter;

    public CircularCursor(int centerX, int centerZ, int radius) {
        this(centerX, centerZ, radius, 0, 0, 0, 0, false);
    }

    public CircularCursor(int centerX, int centerZ, int radius, int ring, int side, int offset,
                          long index, boolean returnedCenter) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.ring = ring;
        this.side = side;
        this.offset = offset;
        this.index = index;
        this.returnedCenter = returnedCenter;
    }

    public CircularCursor copy() {
        return new CircularCursor(this.centerX, this.centerZ, this.radius, this.ring, this.side,
                this.offset, this.index, this.returnedCenter);
    }

    public long next() {
        if (!this.returnedCenter) {
            this.returnedCenter = true;
            this.ring = 1;
            this.index++;
            return ChunkPos.asLong(this.centerX, this.centerZ);
        }
        long radiusSquared = (long) this.radius * this.radius;
        while (this.ring <= this.radius) {
            int sideLength = sideLength(this.ring, this.side);
            if (this.side >= 4) {
                this.ring++;
                this.side = 0;
                this.offset = 0;
                continue;
            }
            if (this.offset >= sideLength) {
                this.side++;
                this.offset = 0;
                continue;
            }
            int relativeX = 0;
            int relativeZ = 0;
            switch (this.side) {
                case 0 -> {
                    relativeX = -this.ring + this.offset;
                    relativeZ = -this.ring;
                }
                case 1 -> {
                    relativeX = this.ring;
                    relativeZ = -this.ring + 1 + this.offset;
                }
                case 2 -> {
                    relativeX = this.ring - 1 - this.offset;
                    relativeZ = this.ring;
                }
                case 3 -> {
                    relativeX = -this.ring;
                    relativeZ = this.ring - 1 - this.offset;
                }
                default -> throw new IllegalStateException("Invalid ring side");
            }
            this.offset++;
            long distanceSquared = (long) relativeX * relativeX + (long) relativeZ * relativeZ;
            if (distanceSquared <= radiusSquared) {
                this.index++;
                return ChunkPos.asLong(this.centerX + relativeX, this.centerZ + relativeZ);
            }
        }
        return ChunkPos.INVALID_CHUNK_POS;
    }

    public boolean isDone() {
        return this.returnedCenter && this.ring > this.radius;
    }

    public long index() {
        return this.index;
    }

    public int ring() {
        return this.ring;
    }

    public int side() {
        return this.side;
    }

    public int offset() {
        return this.offset;
    }

    public boolean returnedCenter() {
        return this.returnedCenter;
    }

    public static long countChunks(int radius) {
        long count = 0;
        long radiusSquared = (long) radius * radius;
        for (int z = -radius; z <= radius; z++) {
            long remaining = radiusSquared - (long) z * z;
            int x = (int) Math.sqrt(remaining);
            while ((long) (x + 1) * (x + 1) <= remaining) {
                x++;
            }
            while ((long) x * x > remaining) {
                x--;
            }
            count += 2L * x + 1;
        }
        return count;
    }

    private static int sideLength(int ring, int side) {
        if (side == 0) {
            return ring * 2 + 1;
        }
        if (side == 1 || side == 2) {
            return ring * 2;
        }
        return ring * 2 - 1;
    }
}
