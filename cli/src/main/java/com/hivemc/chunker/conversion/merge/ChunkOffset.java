package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;

/**
 * A horizontal offset, measured in chunks, which is always a whole number of regions (32 chunks) so that region
 * files from a source world map onto exactly one region file in the output.
 *
 * @param chunkX the X offset in chunks.
 * @param chunkZ the Z offset in chunks.
 */
public record ChunkOffset(int chunkX, int chunkZ) {
    public static final ChunkOffset ZERO = new ChunkOffset(0, 0);

    public ChunkOffset {
        if ((chunkX & 31) != 0 || (chunkZ & 31) != 0) {
            throw new IllegalArgumentException("Offsets must be a multiple of 32 chunks (one region), got " + chunkX + ", " + chunkZ);
        }
    }

    /**
     * Create an offset from a region count.
     */
    public static ChunkOffset ofRegions(int regionX, int regionZ) {
        return new ChunkOffset(regionX << 5, regionZ << 5);
    }

    public int regionX() {
        return chunkX >> 5;
    }

    public int regionZ() {
        return chunkZ >> 5;
    }

    public int blockX() {
        return chunkX << 4;
    }

    public int blockZ() {
        return chunkZ << 4;
    }

    public boolean isZero() {
        return chunkX == 0 && chunkZ == 0;
    }

    public ChunkCoordPair apply(ChunkCoordPair pair) {
        return isZero() ? pair : new ChunkCoordPair(pair.chunkX() + chunkX, pair.chunkZ() + chunkZ);
    }

    public RegionCoordPair apply(RegionCoordPair pair) {
        return isZero() ? pair : new RegionCoordPair(pair.regionX() + regionX(), pair.regionZ() + regionZ());
    }

    public ChunkOffset subtract(ChunkOffset other) {
        return new ChunkOffset(chunkX - other.chunkX, chunkZ - other.chunkZ);
    }

    /**
     * The offset to use for the Nether, which uses an 8:1 scale. The result is rounded to the nearest region so
     * region files still line up; for exact portal linking use an alignment of 8 regions.
     *
     * @return the nether offset.
     */
    public ChunkOffset toNether() {
        return ofRegions(Math.round(regionX() / 8f), Math.round(regionZ() / 8f));
    }

    @Override
    public String toString() {
        return "(" + blockX() + ", " + blockZ() + ") blocks";
    }
}
