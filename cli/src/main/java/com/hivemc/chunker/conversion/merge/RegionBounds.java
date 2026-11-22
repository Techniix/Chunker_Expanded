package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * An inclusive bounding box of region co-ordinates.
 */
public record RegionBounds(int minRegionX, int minRegionZ, int maxRegionX, int maxRegionZ) {
    /**
     * Compute the bounds of a set of regions.
     *
     * @return the bounds or null if the collection is empty.
     */
    @Nullable
    public static RegionBounds of(Collection<RegionCoordPair> regions) {
        if (regions.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (RegionCoordPair region : regions) {
            minX = Math.min(minX, region.regionX());
            minZ = Math.min(minZ, region.regionZ());
            maxX = Math.max(maxX, region.regionX());
            maxZ = Math.max(maxZ, region.regionZ());
        }
        return new RegionBounds(minX, minZ, maxX, maxZ);
    }

    @Nullable
    public static RegionBounds union(@Nullable RegionBounds a, @Nullable RegionBounds b) {
        if (a == null) return b;
        if (b == null) return a;
        return new RegionBounds(
                Math.min(a.minRegionX, b.minRegionX),
                Math.min(a.minRegionZ, b.minRegionZ),
                Math.max(a.maxRegionX, b.maxRegionX),
                Math.max(a.maxRegionZ, b.maxRegionZ)
        );
    }

    public int widthRegions() {
        return maxRegionX - minRegionX + 1;
    }

    public int depthRegions() {
        return maxRegionZ - minRegionZ + 1;
    }

    public RegionBounds offset(ChunkOffset offset) {
        return new RegionBounds(
                minRegionX + offset.regionX(),
                minRegionZ + offset.regionZ(),
                maxRegionX + offset.regionX(),
                maxRegionZ + offset.regionZ()
        );
    }

    public boolean intersects(RegionBounds other) {
        return minRegionX <= other.maxRegionX && maxRegionX >= other.minRegionX
                && minRegionZ <= other.maxRegionZ && maxRegionZ >= other.minRegionZ;
    }

    /**
     * Distance in chunks from a chunk position to the edge of these bounds (0 or negative when outside / on edge).
     *
     * @return how many chunks inside the bounds the position is, negative if outside.
     */
    public int chunksInside(int chunkX, int chunkZ) {
        int minChunkX = minRegionX << 5, minChunkZ = minRegionZ << 5;
        int maxChunkX = (maxRegionX << 5) + 31, maxChunkZ = (maxRegionZ << 5) + 31;
        int dx = Math.min(chunkX - minChunkX, maxChunkX - chunkX);
        int dz = Math.min(chunkZ - minChunkZ, maxChunkZ - chunkZ);
        return Math.min(dx, dz);
    }

    @Override
    public String toString() {
        return "regions [" + minRegionX + ", " + minRegionZ + "] -> [" + maxRegionX + ", " + maxRegionZ + "]"
                + " (" + (widthRegions() * 512) + "x" + (depthRegions() * 512) + " blocks)";
    }
}
