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

    public static CircularCursor at(int centerX, int centerZ, int radius, long targetIndex) {
        if (radius < 0) {
            throw new IllegalArgumentException("Radius must not be negative");
        }
        long total = countChunks(radius);
        if (targetIndex < 0 || targetIndex > total) {
            throw new IllegalArgumentException("Start index must be between 0 and " + total + ", got " + targetIndex);
        }
        if (targetIndex == 0) {
            return new CircularCursor(centerX, centerZ, radius);
        }
        if (targetIndex == total) {
            return new CircularCursor(centerX, centerZ, radius, radius + 1, 0, 0, total, true);
        }

        long[] ringEnds = new long[radius + 1];
        ringEnds[0] = 1;
        for (int ring = 1; ring <= radius; ring++) {
            ringEnds[ring] = ringEnds[ring - 1] + countRing(radius, ring);
        }
        int low = 1;
        int high = radius;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ringEnds[middle] <= targetIndex) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        int ring = low;
        int remaining = (int) (targetIndex - ringEnds[ring - 1]);
        for (int side = 0; side < 4; side++) {
            int count = countSide(radius, ring, side);
            if (remaining < count) {
                return new CircularCursor(centerX, centerZ, radius, ring, side,
                        sideOffset(radius, ring, side, remaining), targetIndex, true);
            }
            remaining -= count;
        }
        throw new IllegalStateException("Unable to locate circular cursor index " + targetIndex);
    }

    private static long countRing(int radius, int ring) {
        long count = 0;
        for (int side = 0; side < 4; side++) {
            count += countSide(radius, ring, side);
        }
        return count;
    }

    private static int countSide(int radius, int ring, int side) {
        int reach = ringReach(radius, ring);
        return switch (side) {
            case 0 -> reach * 2 + 1;
            case 1 -> {
                int lower = Math.max(-ring + 1, -reach);
                yield Math.max(0, reach - lower + 1);
            }
            case 2 -> {
                int upper = Math.min(ring - 1, reach);
                yield Math.max(0, upper + reach + 1);
            }
            case 3 -> {
                int lower = Math.max(-ring + 1, -reach);
                int upper = Math.min(ring - 1, reach);
                yield Math.max(0, upper - lower + 1);
            }
            default -> throw new IllegalArgumentException("Invalid ring side");
        };
    }

    private static int sideOffset(int radius, int ring, int side, int index) {
        int reach = ringReach(radius, ring);
        return switch (side) {
            case 0 -> ring - reach + index;
            case 1 -> Math.max(-ring + 1, -reach) + ring - 1 + index;
            case 2, 3 -> ring - 1 - Math.min(ring - 1, reach) + index;
            default -> throw new IllegalArgumentException("Invalid ring side");
        };
    }

    private static int ringReach(int radius, int ring) {
        long remaining = (long) radius * radius - (long) ring * ring;
        int reach = (int) Math.sqrt(remaining);
        while ((long) (reach + 1) * (reach + 1) <= remaining) {
            reach++;
        }
        while ((long) reach * reach > remaining) {
            reach--;
        }
        return Math.min(ring, reach);
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
