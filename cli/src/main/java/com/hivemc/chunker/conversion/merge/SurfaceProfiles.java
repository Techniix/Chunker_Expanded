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
     * @param waterTop the Y of the highest water block above each sample's ground, or {@link #NO_GROUND} if it's dry.
     * @param edges  exact ground heights along the four edges: [west x=0, east x=15, north z=0, south z=15][16].
     * @param edgeWater the Y of the highest water block above each edge column, or {@link #NO_GROUND} (same layout).
     * @param treeCover  the fraction of samples under a tree canopy.
     * @param plantCover the fraction of samples with a plant (grass, flowers...) on the ground.
     */
    public record Profile(short[] ground, short[] top, short[] filler, short[] biome, short[] waterTop, short[][] edges,
                          short[][] edgeWater, float treeCover, float plantCover) {
        /**
         * Whether a sample is under water.
         */
        public boolean water(int sample) {
            return waterTop[sample] != NO_GROUND;
        }
    }

    private static boolean isTreePart(ChunkerBlockIdentifier identifier) {
        String name = identifier.getType().toString();
        return name.endsWith("_LOG") || name.endsWith("_LEAVES") || name.endsWith("_WOOD") || name.endsWith("_STEM")
                || name.equals("MUSHROOM_STEM") || name.endsWith("_MUSHROOM_BLOCK");
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
        short[] waterTop = new short[16];
        boolean anyGround = false;
        int trees = 0, plants = 0, samples = 0;

        for (int cellZ = 0; cellZ < 4; cellZ++) {
            for (int cellX = 0; cellX < 4; cellX++) {
                int i = cellZ * 4 + cellX;
                int x = (cellX << 2) + 1, z = (cellZ << 2) + 1;
                Pair<Integer, ChunkerBlockIdentifier> highest = column.getHighestBlock(x, z, TerrainBlocks::isGround);
                if (highest == null) {
                    ground[i] = NO_GROUND;
                    waterTop[i] = NO_GROUND;
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
                waterTop[i] = waterTopAbove(column, x, y, z);

                // Vegetation cover, so seams can continue it
                if (waterTop[i] == NO_GROUND) {
                    samples++;
                    ChunkerBlockIdentifier above = column.getBlock(x, y + 1, z);
                    if (!above.isAir() && !isTreePart(above)) plants++;
                    for (int dy = 1; dy <= 14; dy++) {
                        if (isTreePart(column.getBlock(x, y + dy, z))) {
                            trees++;
                            break;
                        }
                    }
                }

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
            // Exact heights along the edges, used to join seams precisely
            short[][] edges = new short[4][16];
            short[][] edgeWater = new short[4][16];
            for (int i = 0; i < 16; i++) {
                int[][] positions = {{0, i}, {15, i}, {i, 0}, {i, 15}};
                for (int edge = 0; edge < 4; edge++) {
                    int x = positions[edge][0], z = positions[edge][1];
                    edges[edge][i] = groundHeight(column, x, z);
                    edgeWater[edge][i] = edges[edge][i] == NO_GROUND ? NO_GROUND : waterTopAbove(column, x, edges[edge][i], z);
                }
            }
            profiles.put(key(column.getPosition().chunkX(), column.getPosition().chunkZ()), new Profile(ground, top, filler, biome, waterTop, edges, edgeWater,
                    samples == 0 ? 0 : trees / (float) samples, samples == 0 ? 0 : plants / (float) samples));
        }
    }

    /**
     * The Y of the highest water block in the run of water directly above the ground, or {@link #NO_GROUND}.
     */
    private static short waterTopAbove(ChunkerColumn column, int x, int groundY, int z) {
        int y = groundY + 1;
        if (!TerrainBlocks.isWaterLike(column.getBlock(x, y, z))) return NO_GROUND;
        while (y < 319 && TerrainBlocks.isWaterLike(column.getBlock(x, y + 1, z))) y++;
        return (short) y;
    }

    private static short groundHeight(ChunkerColumn column, int x, int z) {
        Pair<Integer, ChunkerBlockIdentifier> highest = column.getHighestBlock(x, z, TerrainBlocks::isGround);
        return highest == null ? NO_GROUND : highest.first().shortValue();
    }

    /**
     * Whether profiles should be recorded for a dimension (seams are only generated for the overworld).
     */
    public static boolean isProfiledDimension(Dimension dimension) {
        return dimension.getIdentifier().equals(Dimension.OVERWORLD.getIdentifier());
    }
}
