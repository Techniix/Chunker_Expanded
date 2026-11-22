package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.ShortBasedPalette;
import org.jetbrains.annotations.Nullable;

/**
 * Generates the Y -64 to -1 layer for columns which came from a world saved before Caves &amp; Cliffs (1.18),
 * mirroring what the game does when it upgrades an old world: the old bedrock floor at Y 0-4 is replaced and
 * deepslate terrain with caves, ores, tuff, lava and a new bedrock floor is added underneath.
 * <p>
 * The game's own retro-generation is only triggered by Java's data fixers (which Chunker bypasses as it writes the
 * target format directly) so this is done in Chunker instead, which also means it works for both editions.
 * <p>
 * Only x/z positions which have bedrock at Y=0 are extended (the same rule the game uses), so void, sky-block and
 * custom worlds are left untouched.
 * <p>
 * Like the game, noise is sampled on a coarse grid (every 4 blocks horizontally and 8 vertically) and interpolated,
 * which keeps this fast enough to run on every column of a large world.
 */
public class BelowZeroGenerator {
    public static final int MIN_Y = -64;
    private static final long SALT_CHEESE = 11;
    private static final long SALT_SPAGHETTI_A = 12;
    private static final long SALT_SPAGHETTI_B = 13;
    private static final long SALT_TUFF = 14;
    private static final long SALT_ORE = 15;
    private static final long SALT_BEDROCK = 16;
    private static final int LAVA_LEVEL = -55;
    private static final int CAVE_CEILING = -7; // Caves stay below this so the old world isn't undermined

    private final MergeNoise noise;

    /**
     * Create a new generator.
     *
     * @param seed the seed used for caves / ores.
     */
    public BelowZeroGenerator(long seed) {
        this.noise = new MergeNoise(seed);
    }

    /**
     * Check whether a column is from an old height world and doesn't have any sub-zero data yet.
     *
     * @param column the column.
     * @return true if it has chunks and none below 0.
     */
    public static boolean needsBelowZero(ChunkerColumn column) {
        return !column.getChunks().isEmpty() && column.getChunks().firstByteKey() >= 0;
    }

    /**
     * Fill the area below zero for a column.
     *
     * @param column the column (in its final, offset position as co-ordinates are used for noise).
     * @return true if anything was generated.
     */
    public boolean fill(ChunkerColumn column) {
        if (!needsBelowZero(column)) return false;

        // Find which positions have the old bedrock floor
        boolean[][] hasFloor = new boolean[16][16];
        boolean any = false;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                hasFloor[x][z] = column.getBlock(x, 0, z).getType() == ChunkerVanillaBlockType.BEDROCK;
                any |= hasFloor[x][z];
            }
        }
        if (!any) return false;

        // Replace the old bedrock pattern at Y 0-4 with deepslate (only where the floor exists)
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (!hasFloor[x][z]) continue;
                for (int y = 0; y <= 4; y++) {
                    if (column.getBlock(x, y, z).getType() == ChunkerVanillaBlockType.BEDROCK) {
                        column.setBlock(x, y, z, TerrainBlocks.DEEPSLATE);
                    }
                }
            }
        }

        // Build the four new sub-chunks
        int chunkX = column.getPosition().chunkX();
        int chunkZ = column.getPosition().chunkZ();
        for (int chunkY = -4; chunkY < 0; chunkY++) {
            ChunkerChunk chunk = new ChunkerChunk((byte) chunkY);
            chunk.setPalette(generateSection(chunkX, chunkY, chunkZ, hasFloor));
            column.getChunks().put((byte) chunkY, chunk);
        }

        // Lighting needs recalculating as new sections were added
        column.setLightPopulated(false);

        // Biome indexes below zero should repeat the lowest biome rather than wrap to the top
        if (column.getBiomes() != null && !(column.getBiomes() instanceof BottomClampedBiomes)) {
            column.setBiomes(new BottomClampedBiomes(column.getBiomes()));
        }
        return true;
    }

    /**
     * Generate a 16x16x16 section below zero.
     *
     * @param chunkX the chunk x.
     * @param chunkY the section y (-4 to -1).
     * @param chunkZ the chunk z.
     * @param mask   which x/z positions to generate, null for all (others are air).
     * @return the palette for the section.
     */
    public Palette<ChunkerBlockIdentifier> generateSection(int chunkX, int chunkY, int chunkZ, boolean @Nullable [][] mask) {
        int baseX = chunkX << 4, baseY = chunkY << 4, baseZ = chunkZ << 4;

        // Coarse noise grids, 5x3x5 corners for a 16x16x16 section (4 block horizontal, 8 block vertical cells)
        double[][][] cheese = sampleGrid(SALT_CHEESE, baseX, baseY, baseZ, 48, 24);
        double[][][] spagA = sampleGrid(SALT_SPAGHETTI_A, baseX, baseY, baseZ, 32, 16);
        double[][][] spagB = sampleGrid(SALT_SPAGHETTI_B, baseX, baseY, baseZ, 32, 16);
        double[][][] tuff = sampleGrid(SALT_TUFF, baseX, baseY, baseZ, 14, 10);

        // Ore clusters, one possible cluster per 4x4x4 cell
        Ore[][][] ores = new Ore[4][4][4];
        for (int cx = 0; cx < 4; cx++) {
            for (int cy = 0; cy < 4; cy++) {
                for (int cz = 0; cz < 4; cz++) {
                    ores[cx][cy][cz] = oreCell((baseX >> 2) + cx, (baseY >> 2) + cy, (baseZ >> 2) + cz);
                }
            }
        }

        ShortBasedPalette<ChunkerBlockIdentifier> palette = new ShortBasedPalette<>(12, 16);
        short air = palette.getOrCreateKey(TerrainBlocks.AIR); // Index 0 so masked positions stay air
        short deepslate = palette.getOrCreateKey(TerrainBlocks.DEEPSLATE);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (mask != null && !mask[x][z]) continue;
                for (int localY = 0; localY < 16; localY++) {
                    int y = baseY + localY;
                    int worldX = baseX + x, worldZ = baseZ + z;
                    ChunkerBlockIdentifier block;

                    if (y == MIN_Y) {
                        block = TerrainBlocks.BEDROCK;
                    } else if (y <= MIN_Y + 4 && noise.random(SALT_BEDROCK, worldX, y, worldZ) < (MIN_Y + 5 - y) / 5.0) {
                        block = TerrainBlocks.BEDROCK;
                    } else if (y > MIN_Y + 4 && y < CAVE_CEILING && isCave(
                            interpolate(cheese, x, localY, z),
                            interpolate(spagA, x, localY, z),
                            interpolate(spagB, x, localY, z)
                    )) {
                        block = y <= LAVA_LEVEL ? TerrainBlocks.LAVA : TerrainBlocks.AIR;
                    } else if (interpolate(tuff, x, localY, z) > 0.62) {
                        block = TerrainBlocks.TUFF;
                    } else {
                        Ore ore = ores[x >> 2][localY >> 2][z >> 2];
                        block = ore != null && ore.contains(worldX, y, worldZ) ? ore.block : TerrainBlocks.DEEPSLATE;
                    }

                    short index = block == TerrainBlocks.DEEPSLATE ? deepslate
                            : block == TerrainBlocks.AIR ? air
                            : palette.getOrCreateKey(block);
                    palette.setPaletteIndex(x, localY, z, index);
                }
            }
        }

        return palette.compact(TerrainBlocks.AIR);
    }

    /**
     * Whether a position is inside a cave, combining large "cheese" caverns and thin "spaghetti" tunnels.
     */
    static boolean isCave(double cheese, double spaghettiA, double spaghettiB) {
        if (cheese > 0.55) return true;
        return Math.abs(spaghettiA) < 0.045 && Math.abs(spaghettiB) < 0.045;
    }

    private double[][][] sampleGrid(long salt, int baseX, int baseY, int baseZ, double horizontalScale, double verticalScale) {
        double[][][] grid = new double[5][3][5];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 5; k++) {
                    grid[i][j][k] = noise.noise3(
                            salt,
                            (baseX + (i << 2)) / horizontalScale,
                            (baseY + (j << 3)) / verticalScale,
                            (baseZ + (k << 2)) / horizontalScale
                    );
                }
            }
        }
        return grid;
    }

    private static double interpolate(double[][][] grid, int x, int y, int z) {
        int i = x >> 2, j = y >> 3, k = z >> 2;
        double fx = (x & 3) / 4.0, fy = (y & 7) / 8.0, fz = (z & 3) / 4.0;
        double c00 = grid[i][j][k] + (grid[i + 1][j][k] - grid[i][j][k]) * fx;
        double c10 = grid[i][j + 1][k] + (grid[i + 1][j + 1][k] - grid[i][j + 1][k]) * fx;
        double c01 = grid[i][j][k + 1] + (grid[i + 1][j][k + 1] - grid[i][j][k + 1]) * fx;
        double c11 = grid[i][j + 1][k + 1] + (grid[i + 1][j + 1][k + 1] - grid[i][j + 1][k + 1]) * fx;
        double c0 = c00 + (c10 - c00) * fy;
        double c1 = c01 + (c11 - c01) * fy;
        return c0 + (c1 - c0) * fz;
    }

    @Nullable
    private Ore oreCell(int cellX, int cellY, int cellZ) {
        double roll = noise.random(SALT_ORE, cellX, cellY, cellZ);
        int y = cellY << 2;
        ChunkerBlockIdentifier type;
        if (roll < 0.010 && y < -48) type = TerrainBlocks.DEEPSLATE_DIAMOND_ORE;
        else if (roll < 0.030 && y < -32) type = TerrainBlocks.DEEPSLATE_REDSTONE_ORE;
        else if (roll < 0.050) type = TerrainBlocks.DEEPSLATE_IRON_ORE;
        else if (roll < 0.062) type = TerrainBlocks.DEEPSLATE_GOLD_ORE;
        else if (roll < 0.072) type = TerrainBlocks.DEEPSLATE_LAPIS_ORE;
        else if (roll < 0.080 && y > -16) type = TerrainBlocks.DEEPSLATE_COPPER_ORE;
        else return null;

        return new Ore(
                type,
                (cellX << 2) + noise.random(SALT_ORE + 1, cellX, cellY, cellZ) * 4,
                (cellY << 2) + noise.random(SALT_ORE + 2, cellX, cellY, cellZ) * 4,
                (cellZ << 2) + noise.random(SALT_ORE + 3, cellX, cellY, cellZ) * 4,
                0.9 + noise.random(SALT_ORE + 4, cellX, cellY, cellZ) * 1.1
        );
    }

    private record Ore(ChunkerBlockIdentifier block, double x, double y, double z, double radius) {
        boolean contains(int blockX, int blockY, int blockZ) {
            double dx = blockX + 0.5 - x, dy = blockY + 0.5 - y, dz = blockZ + 0.5 - z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }
    }
}
