package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records a compact description of the surface of every written overworld column near the edge of each world, which
 * the seam generator uses to blend the gaps between worlds. Surfaces are sampled once per 4x4 block cell (the same
 * resolution as biomes) so a column holds 16 samples.
 */
public class SurfaceProfiles {
    public static final short NO_GROUND = Short.MIN_VALUE;

    private final Map<Long, Profile> profiles = new ConcurrentHashMap<>();
    private final LongSet occupied = new LongOpenHashSet();
    private final List<ChunkerBlockIdentifier> blockTable = new ArrayList<>();
    private final Map<ChunkerBlockIdentifier, Short> blockIndex = new ConcurrentHashMap<>();
    private final List<ChunkerBiome> biomeTable = new ArrayList<>();
    private final Map<ChunkerBiome, Short> biomeIndex = new ConcurrentHashMap<>();

    /**
     * The 16 surface samples of a column, index = cellZ * 4 + cellX.
     *
     * @param ground the height of the highest natural ground block, or {@link #NO_GROUND}.
     * @param top    the block table index of the top ground block.
     * @param filler the block table index of the block under the top.
     * @param biome  the biome table index at the surface.
     * @param water  whether the surface is underwater.
     */
    public record Profile(short[] ground, short[] top, short[] filler, short[] biome, boolean[] water) {
    }

    /**
     * Pack a chunk position into a long key.
     */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    /**
     * Mark a column as written so seams never overwrite it.
     */
    public void markOccupied(int chunkX, int chunkZ) {
        synchronized (occupied) {
            occupied.add(key(chunkX, chunkZ));
        }
    }

    public boolean isOccupied(int chunkX, int chunkZ) {
        synchronized (occupied) {
            return occupied.contains(key(chunkX, chunkZ));
        }
    }

    public LongSet getOccupied() {
        return occupied;
    }

    @Nullable
    public Profile get(int chunkX, int chunkZ) {
        return profiles.get(key(chunkX, chunkZ));
    }

    public Map<Long, Profile> getProfiles() {
        return profiles;
    }

    public ChunkerBlockIdentifier block(short index) {
        synchronized (blockTable) {
            return blockTable.get(index);
        }
    }

    public ChunkerBiome biome(short index) {
        synchronized (biomeTable) {
            return biomeTable.get(index);
        }
    }

    private short indexOf(ChunkerBlockIdentifier identifier) {
        return blockIndex.computeIfAbsent(identifier, key -> {
            synchronized (blockTable) {
                blockTable.add(key);
                return (short) (blockTable.size() - 1);
            }
        });
    }

    private short indexOf(ChunkerBiome biome) {
        return biomeIndex.computeIfAbsent(biome, key -> {
            synchronized (biomeTable) {
                biomeTable.add(key);
                return (short) (biomeTable.size() - 1);
            }
        });
    }

    /**
     * Record the surface of a column (call after it has been moved to its output position).
     *
     * @param column            the column.
     * @param biomeIndexOffset  the offset to add to a section Y to get its biome index (4 for 1.18+ worlds).
     * @param fallbackBiome     the biome to use when none is present.
     */
    public void record(ChunkerColumn column, int biomeIndexOffset, ChunkerBiome fallbackBiome) {
        short[] ground = new short[16], top = new short[16], filler = new short[16], biome = new short[16];
        boolean[] water = new boolean[16];
        boolean anyGround = false;

        for (int cellZ = 0; cellZ < 4; cellZ++) {
            for (int cellX = 0; cellX < 4; cellX++) {
                int i = cellZ * 4 + cellX;
                int x = (cellX << 2) + 1, z = (cellZ << 2) + 1;
                Pair<Integer, ChunkerBlockIdentifier> highest = column.getHighestBlock(x, z, TerrainBlocks::isGround);
                if (highest == null) {
                    ground[i] = NO_GROUND;
                    continue;
                }
                anyGround = true;
                int y = highest.first();
                ground[i] = (short) y;
                ChunkerBlockIdentifier topBlock = highest.second();
                top[i] = indexOf(TerrainBlocks.isSurfaceMaterial(topBlock) ? topBlock : TerrainBlocks.STONE);

                // Filler is the first natural block a few blocks under the surface
                ChunkerBlockIdentifier fillerBlock = column.getBlock(x, y - 2, z);
                filler[i] = indexOf(TerrainBlocks.isSurfaceMaterial(fillerBlock) ? fillerBlock : TerrainBlocks.STONE);
                water[i] = TerrainBlocks.isWaterLike(column.getBlock(x, y + 1, z));

                // Biome at the surface (or the fallback if not present)
                ChunkerBiome surfaceBiome = fallbackBiome;
                if (column.getBiomes() != null) {
                    Palette<ChunkerBiome> palette = column.getBiomes().as4X4Palette((y >> 4) + biomeIndexOffset);
                    ChunkerBiome found = palette.get(cellX, (y & 15) >> 2, cellZ, null);
                    if (found != null) surfaceBiome = found;
                }
                biome[i] = indexOf(surfaceBiome);
            }
        }

        if (anyGround) {
            profiles.put(key(column.getPosition().chunkX(), column.getPosition().chunkZ()), new Profile(ground, top, filler, biome, water));
        }
    }

    /**
     * Whether profiles should be recorded for a dimension (seams are only generated for the overworld).
     */
    public static boolean isProfiledDimension(Dimension dimension) {
        return dimension.getIdentifier().equals(Dimension.OVERWORLD.getIdentifier());
    }
}
