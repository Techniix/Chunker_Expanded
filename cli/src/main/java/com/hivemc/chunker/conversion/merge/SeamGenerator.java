package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.biome.layout.ChunkerPaletteBasedBiomes;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.ShortBasedPalette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.SingleValuePalette;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates seamless transition terrain in the gaps around and between merged worlds.
 * <p>
 * Work is done on a grid of 4x4 block cells (the same resolution as biomes):
 * <ol>
 *     <li>Every empty chunk within {@code seamWidth} chunks of a world's edge becomes a target.</li>
 *     <li>The surface height of the target cells is solved as a harmonic (Laplace) field: the real surface heights
 *     along each world's edge are fixed, the outer edge of the band is pinned to a gentle baseline near sea level, and
 *     everything in between relaxes to the smoothest surface joining them. This removes the "chunk wall" where two
 *     worlds or a world and new terrain meet, and between two close worlds it produces a smooth valley / ridge.</li>
 *     <li>Natural detail noise is added, faded out near the edges so the join stays exact.</li>
 *     <li>Surface materials, water and biomes are taken from the nearest world edge (a multi-source flood fill),
 *     with the boundaries between different worlds' materials / biomes domain-warped so they are organic rather than
 *     straight lines.</li>
 * </ol>
 */
public class SeamGenerator {
    private static final int UNKNOWN = -1;
    private static final long SALT_DETAIL = 101;
    private static final long SALT_WARP_X = 102;
    private static final long SALT_WARP_Z = 103;
    private static final long SALT_BASELINE = 104;
    private static final double DETAIL_AMPLITUDE = 4.0;
    private static final double WARP_BLOCKS = 7.0;

    private final SurfaceProfiles profiles;
    private final MergeSettings settings;
    private final boolean belowZero;
    private final MergeNoise noise;
    private final BelowZeroGenerator belowZeroGenerator;
    private final ChunkerBiome defaultBiome;

    // Target chunks and cells
    private final LongOpenHashSet targetChunks = new LongOpenHashSet();
    private final Long2IntOpenHashMap cellIndex = new Long2IntOpenHashMap();
    private int[] cellX = new int[0];
    private int[] cellZ = new int[0];

    // Solved fields
    private float[] height;
    private int[] distance;
    private float[] labelHeight;
    private float[] labelRough;
    private short[] labelTop;
    private short[] labelFiller;
    private short[] labelBiome;
    private boolean[] labelWater;

    /**
     * Create a seam generator.
     *
     * @param profiles     the recorded surface profiles.
     * @param settings     the merge settings.
     * @param belowZero    whether the output has the -64 floor (otherwise terrain starts at 0).
     * @param defaultBiome the biome used where no world is nearby.
     */
    public SeamGenerator(SurfaceProfiles profiles, MergeSettings settings, boolean belowZero, ChunkerBiome defaultBiome) {
        this.profiles = profiles;
        this.settings = settings;
        this.belowZero = belowZero;
        this.noise = new MergeNoise(settings.getSeed() ^ 0x5EA7L);
        this.belowZeroGenerator = new BelowZeroGenerator(settings.getSeed());
        this.defaultBiome = defaultBiome;
        this.cellIndex.defaultReturnValue(UNKNOWN);
    }

    private static long cellKey(int x, int z) {
        return SurfaceProfiles.key(x, z);
    }

    /**
     * Get the target chunks (only valid after {@link #prepare()}).
     */
    public LongSet getTargetChunks() {
        return targetChunks;
    }

    /**
     * Work out the target chunks and solve the height field. Must be called before {@link #generate(int, int)}.
     *
     * @return the number of target chunks.
     */
    public int prepare() {
        collectTargets();
        if (targetChunks.isEmpty()) return 0;
        buildCells();
        floodFill();
        solveHeights();
        return targetChunks.size();
    }

    private void collectTargets() {
        int width = settings.getSeamWidthChunks();
        LongSet occupied = profiles.getOccupied(); // All passes are finished, so no locking is needed
        for (long key : profiles.getProfiles().keySet()) {
            int chunkX = SurfaceProfiles.keyX(key), chunkZ = SurfaceProfiles.keyZ(key);

            // Quick reject: only columns which have an empty neighbour somewhere in range can produce targets
            for (int dx = -width; dx <= width; dx++) {
                for (int dz = -width; dz <= width; dz++) {
                    int x = chunkX + dx, z = chunkZ + dz;
                    if (!occupied.contains(SurfaceProfiles.key(x, z))) {
                        targetChunks.add(SurfaceProfiles.key(x, z));
                    }
                }
            }
        }
    }

    private void buildCells() {
        int count = targetChunks.size() * 16;
        cellX = new int[count];
        cellZ = new int[count];
        int index = 0;
        for (long key : targetChunks) {
            int chunkX = SurfaceProfiles.keyX(key), chunkZ = SurfaceProfiles.keyZ(key);
            for (int i = 0; i < 16; i++) {
                int x = (chunkX << 2) + (i & 3), z = (chunkZ << 2) + (i >> 2);
                cellX[index] = x;
                cellZ[index] = z;
                cellIndex.put(cellKey(x, z), index);
                index++;
            }
        }
        height = new float[count];
        distance = new int[count];
        labelHeight = new float[count];
        labelRough = new float[count];
        labelTop = new short[count];
        labelFiller = new short[count];
        labelBiome = new short[count];
        labelWater = new boolean[count];
    }

    /**
     * Get the fixed ground height of a cell from a written world, or {@link SurfaceProfiles#NO_GROUND}.
     */
    private short fixedGround(int cx, int cz) {
        SurfaceProfiles.Profile profile = profiles.get(cx >> 2, cz >> 2);
        if (profile == null) return SurfaceProfiles.NO_GROUND;
        return profile.ground()[((cz & 3) << 2) | (cx & 3)];
    }

    private static float roughness(SurfaceProfiles.Profile profile) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (short ground : profile.ground()) {
            if (ground == SurfaceProfiles.NO_GROUND) continue;
            min = Math.min(min, ground);
            max = Math.max(max, ground);
        }
        return min == Integer.MAX_VALUE ? 0 : max - min;
    }

    /**
     * The baseline height that the outer edge of the seam band relaxes to.
     */
    double baseline(int cx, int cz) {
        return settings.getSeaLevel() + 3 + 5 * noise.fbm2(SALT_BASELINE, cx / 48.0, cz / 48.0, 3);
    }

    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Multi-source flood fill from every fixed edge cell, giving each target cell the attributes of the nearest
     * world edge and its distance from it.
     */
    private void floodFill() {
        int count = cellX.length;
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        java.util.Arrays.fill(distance, Integer.MAX_VALUE);

        // Seed cells next to real terrain
        for (int i = 0; i < count; i++) {
            for (int[] n : NEIGHBOURS) {
                int nx = cellX[i] + n[0], nz = cellZ[i] + n[1];
                if (cellIndex.get(cellKey(nx, nz)) != UNKNOWN) continue;
                SurfaceProfiles.Profile profile = profiles.get(nx >> 2, nz >> 2);
                if (profile == null) continue;
                int sample = ((nz & 3) << 2) | (nx & 3);
                if (profile.ground()[sample] == SurfaceProfiles.NO_GROUND) continue;
                distance[i] = 1;
                labelHeight[i] = profile.ground()[sample];
                labelRough[i] = roughness(profile);
                labelTop[i] = profile.top()[sample];
                labelFiller[i] = profile.filler()[sample];
                labelBiome[i] = profile.biome()[sample];
                labelWater[i] = profile.water()[sample];
                queue.enqueue(i);
                break;
            }
        }

        // Breadth first expansion
        while (!queue.isEmpty()) {
            int i = queue.dequeueInt();
            for (int[] n : NEIGHBOURS) {
                int j = cellIndex.get(cellKey(cellX[i] + n[0], cellZ[i] + n[1]));
                if (j == UNKNOWN || distance[j] != Integer.MAX_VALUE) continue;
                distance[j] = distance[i] + 1;
                labelHeight[j] = labelHeight[i];
                labelRough[j] = labelRough[i];
                labelTop[j] = labelTop[i];
                labelFiller[j] = labelFiller[i];
                labelBiome[j] = labelBiome[i];
                labelWater[j] = labelWater[i];
                queue.enqueue(j);
            }
        }
    }

    /**
     * Solve the harmonic height field with successive over-relaxation.
     */
    private void solveHeights() {
        int count = cellX.length;
        int[] neighbourIndex = new int[count * 4];
        float[] fixedSum = new float[count];
        byte[] known = new byte[count];

        // Real terrain is fixed (Dirichlet). The open outer edge of the band has no constraint (Neumann), so the
        // surface carries on at the height of the nearest world instead of being pulled to an arbitrary level.
        for (int i = 0; i < count; i++) {
            float sum = 0;
            int n = 0;
            for (int d = 0; d < 4; d++) {
                int nx = cellX[i] + NEIGHBOURS[d][0], nz = cellZ[i] + NEIGHBOURS[d][1];
                int j = cellIndex.get(cellKey(nx, nz));
                neighbourIndex[i * 4 + d] = j;
                if (j != UNKNOWN) {
                    n++;
                } else {
                    short ground = fixedGround(nx, nz);
                    if (ground != SurfaceProfiles.NO_GROUND) {
                        sum += ground;
                        n++;
                    }
                }
            }
            fixedSum[i] = sum;
            known[i] = (byte) n;
        }

        // Initial guess: the nearest edge height fading towards the baseline, which speeds up convergence a lot
        double band = settings.getSeamWidthChunks() * 4.0;
        for (int i = 0; i < count; i++) {
            double base = baseline(cellX[i], cellZ[i]);
            if (distance[i] == Integer.MAX_VALUE) {
                height[i] = (float) base;
            } else {
                height[i] = labelHeight[i];
            }
        }

        // Near optimal over-relaxation factor for the width of the band
        double omega = Math.min(1.95, 2.0 / (1.0 + Math.sin(Math.PI / Math.max(8.0, band))));
        int maxIterations = 1500;
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            float maxDelta = 0;
            for (int i = 0; i < count; i++) {
                float sum = fixedSum[i];
                int base = i * 4;
                for (int n = 0; n < 4; n++) {
                    int j = neighbourIndex[base + n];
                    if (j != UNKNOWN) sum += height[j];
                }
                if (known[i] == 0) continue; // Isolated cell, keep the initial guess
                float target = sum / known[i];
                float delta = (float) (omega * (target - height[i]));
                height[i] += delta;
                maxDelta = Math.max(maxDelta, Math.abs(delta));
            }
            if (maxDelta < 0.01f) break;
        }
    }

    /**
     * The final (detailed) surface height at a cell, using real terrain for written cells.
     */
    private double cellHeight(int cx, int cz) {
        int i = cellIndex.get(cellKey(cx, cz));
        if (i != UNKNOWN) {
            int d = distance[i] == Integer.MAX_VALUE ? 64 : distance[i];
            double fade = Math.min(1.0, (d - 1) / 6.0);
            double amplitude = Math.min(DETAIL_AMPLITUDE, labelRough[i] * 0.5);
            return height[i] + amplitude * fade * noise.fbm2(SALT_DETAIL, cx / 6.0, cz / 6.0, 3);
        }
        short ground = fixedGround(cx, cz);
        return ground != SurfaceProfiles.NO_GROUND ? ground : baseline(cx, cz);
    }

    /**
     * The surface height at a block, bilinearly interpolated between cell centres.
     */
    double blockHeight(int x, int z) {
        double fx = (x - 1.5) / 4.0, fz = (z - 1.5) / 4.0;
        int cx = (int) Math.floor(fx), cz = (int) Math.floor(fz);
        double tx = fx - cx, tz = fz - cz;
        double h00 = cellHeight(cx, cz), h10 = cellHeight(cx + 1, cz);
        double h01 = cellHeight(cx, cz + 1), h11 = cellHeight(cx + 1, cz + 1);
        return (h00 * (1 - tx) + h10 * tx) * (1 - tz) + (h01 * (1 - tx) + h11 * tx) * tz;
    }

    /**
     * Surface attributes looked up at a domain-warped position so borders between worlds look natural.
     */
    private record Surface(ChunkerBlockIdentifier top, ChunkerBlockIdentifier filler, ChunkerBiome biome, boolean water) {
    }

    private Surface surfaceAt(int x, int z) {
        double wx = x + WARP_BLOCKS * noise.noise2(SALT_WARP_X, x / 12.0, z / 12.0);
        double wz = z + WARP_BLOCKS * noise.noise2(SALT_WARP_Z, x / 12.0, z / 12.0);
        int cx = (int) Math.floor(wx / 4.0), cz = (int) Math.floor(wz / 4.0);

        Surface surface = surfaceOfCell(cx, cz);
        if (surface != null) return surface;

        // The warped position wasn't usable, fall back to the cell itself
        surface = surfaceOfCell(x >> 2, z >> 2);
        return surface != null ? surface : new Surface(TerrainBlocks.GRASS_BLOCK, TerrainBlocks.DIRT, defaultBiome, false);
    }

    private Surface surfaceOfCell(int cx, int cz) {
        int i = cellIndex.get(cellKey(cx, cz));
        if (i != UNKNOWN) {
            if (distance[i] == Integer.MAX_VALUE) return null;
            return new Surface(profiles.block(labelTop[i]), profiles.block(labelFiller[i]), profiles.biome(labelBiome[i]), labelWater[i]);
        }
        SurfaceProfiles.Profile profile = profiles.get(cx >> 2, cz >> 2);
        if (profile == null) return null;
        int sample = ((cz & 3) << 2) | (cx & 3);
        if (profile.ground()[sample] == SurfaceProfiles.NO_GROUND) return null;
        return new Surface(profiles.block(profile.top()[sample]), profiles.block(profile.filler()[sample]),
                profiles.biome(profile.biome()[sample]), profile.water()[sample]);
    }

    /**
     * Build the column for a target chunk.
     *
     * @param chunkX the chunk x.
     * @param chunkZ the chunk z.
     * @return the generated column.
     */
    public ChunkerColumn generate(int chunkX, int chunkZ) {
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        int seaTop = settings.getSeaLevel() - 1;
        int minY = belowZero ? BelowZeroGenerator.MIN_Y : 0;

        // Heights for this chunk plus a one block border (for slopes)
        int[][] heights = new int[18][18];
        for (int x = -1; x <= 16; x++) {
            for (int z = -1; z <= 16; z++) {
                int h = (int) Math.round(blockHeight(baseX + x, baseZ + z));
                heights[x + 1][z + 1] = Math.max(minY + 1, Math.min(300, h));
            }
        }

        ChunkerBlockIdentifier[][] top = new ChunkerBlockIdentifier[16][16];
        ChunkerBlockIdentifier[][] filler = new ChunkerBlockIdentifier[16][16];
        boolean[][] wet = new boolean[16][16];
        boolean anyWet = false;
        ChunkerBiome[][] cellBiomes = new ChunkerBiome[4][4];
        int minHeight = Integer.MAX_VALUE, maxHeight = Integer.MIN_VALUE;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int h = heights[x + 1][z + 1];
                minHeight = Math.min(minHeight, h);
                maxHeight = Math.max(maxHeight, h);
                Surface surface = surfaceAt(baseX + x, baseZ + z);

                int slope = Math.max(
                        Math.max(Math.abs(h - heights[x][z + 1]), Math.abs(h - heights[x + 2][z + 1])),
                        Math.max(Math.abs(h - heights[x + 1][z]), Math.abs(h - heights[x + 1][z + 2]))
                );
                ChunkerBlockIdentifier topBlock = surface.top();
                ChunkerBlockIdentifier fillerBlock = surface.filler();

                boolean underwater = surface.water() && h < seaTop;
                wet[x][z] = underwater;
                anyWet |= underwater;
                if (underwater) {
                    // Under water: keep real sea floor materials, otherwise beaches near the surface and gravel deeper
                    if (!surface.water() || !TerrainBlocks.isSurfaceMaterial(topBlock) || isVegetatedSoil(topBlock)) {
                        topBlock = h >= seaTop - 5 ? TerrainBlocks.SAND : TerrainBlocks.GRAVEL;
                        fillerBlock = topBlock == TerrainBlocks.SAND ? TerrainBlocks.SAND : TerrainBlocks.GRAVEL;
                    }
                } else if (slope >= 4) {
                    // Steep slopes become bare stone like cliffs
                    topBlock = TerrainBlocks.STONE;
                    fillerBlock = TerrainBlocks.STONE;
                } else if (surface.water()) {
                    // Above water level but the nearest edge was sea floor: use sand / grass instead
                    topBlock = h <= seaTop + 2 ? TerrainBlocks.SAND : TerrainBlocks.GRASS_BLOCK;
                    fillerBlock = topBlock == TerrainBlocks.SAND ? TerrainBlocks.SAND : TerrainBlocks.DIRT;
                }
                if (topBlock.getType() == ChunkerVanillaBlockType.SAND && fillerBlock.getType() != ChunkerVanillaBlockType.SAND) {
                    fillerBlock = TerrainBlocks.SAND;
                }

                top[x][z] = topBlock;
                filler[x][z] = fillerBlock;
                if ((x & 3) == 1 && (z & 3) == 1) {
                    cellBiomes[x >> 2][z >> 2] = surface.biome();
                }
            }
        }

        ChunkerColumn column = new ChunkerColumn(new ChunkCoordPair(chunkX, chunkZ));
        int minSection = minY >> 4;
        int maxSection = (anyWet ? Math.max(maxHeight, seaTop) : maxHeight) >> 4;
        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            ChunkerChunk chunk = new ChunkerChunk((byte) sectionY);
            chunk.setPalette(buildSection(chunkX, chunkZ, sectionY, heights, top, filler, wet, minHeight, seaTop));
            column.getChunks().put((byte) sectionY, chunk);
        }

        // Biomes are 2D for seams, one 16x16x16 palette shared by every section
        column.setBiomes(buildBiomes(cellBiomes, belowZero ? 24 : 16));
        column.setLightPopulated(false);
        column.setBlendingHint(settings.isJavaBlendingHints());
        return column;
    }

    private static boolean isVegetatedSoil(ChunkerBlockIdentifier identifier) {
        return identifier.getType() == ChunkerVanillaBlockType.GRASS_BLOCK
                || identifier.getType() == ChunkerVanillaBlockType.PODZOL
                || identifier.getType() == ChunkerVanillaBlockType.MYCELIUM
                || identifier.getType() == ChunkerVanillaBlockType.SNOW_BLOCK;
    }

    private Palette<ChunkerBlockIdentifier> buildSection(int chunkX, int chunkZ, int sectionY, int[][] heights,
                                                          ChunkerBlockIdentifier[][] top, ChunkerBlockIdentifier[][] filler,
                                                          boolean[][] wet, int minHeight, int seaTop) {
        int sectionMin = sectionY << 4, sectionMax = sectionMin + 15;

        // Below zero uses the same deep generator as upgraded old worlds (fully underground sections as-is)
        Palette<ChunkerBlockIdentifier> deep = null;
        if (sectionY < 0) {
            deep = belowZeroGenerator.generateSection(chunkX, sectionY, chunkZ, null);
            if (sectionMax < minHeight - 4) return deep;
        }

        // Fully solid sections are plain stone (unless the old-style bedrock floor is in it)
        boolean hasLegacyFloor = !belowZero && sectionY == 0;
        if (!hasLegacyFloor && sectionMax < minHeight - 4) {
            return SingleValuePalette.chunk(TerrainBlocks.STONE);
        }

        ShortBasedPalette<ChunkerBlockIdentifier> palette = new ShortBasedPalette<>(8, 16);
        short air = palette.getOrCreateKey(TerrainBlocks.AIR);
        short stone = palette.getOrCreateKey(TerrainBlocks.STONE);
        short water = -1;
        int baseX = chunkX << 4, baseZ = chunkZ << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int h = heights[x + 1][z + 1];
                short topIndex = palette.getOrCreateKey(top[x][z]);
                short fillerIndex = palette.getOrCreateKey(filler[x][z]);
                for (int localY = 0; localY < 16; localY++) {
                    int y = sectionMin + localY;
                    short index;
                    if (hasLegacyFloor && y <= 4 && (y == 0 || noise.random(SALT_DETAIL + 9, baseX + x, y, baseZ + z) < (5 - y) / 5.0)) {
                        index = palette.getOrCreateKey(TerrainBlocks.BEDROCK);
                    } else if (y > h) {
                        if (wet[x][z] && y <= seaTop) {
                            if (water == -1) water = palette.getOrCreateKey(TerrainBlocks.WATER);
                            index = water;
                        } else {
                            index = air;
                        }
                    } else if (y == h) {
                        index = topIndex;
                    } else if (y >= h - 3) {
                        index = fillerIndex;
                    } else if (deep != null) {
                        index = palette.getOrCreateKey(deep.get(x, localY, z, TerrainBlocks.DEEPSLATE));
                    } else {
                        index = stone;
                    }
                    palette.setPaletteIndex(x, localY, z, index);
                }
            }
        }
        return palette.compact(TerrainBlocks.AIR);
    }

    private ChunkerPaletteBasedBiomes buildBiomes(ChunkerBiome[][] cellBiomes, int sections) {
        Palette<ChunkerBiome> palette;
        ChunkerBiome first = cellBiomes[0][0] == null ? defaultBiome : cellBiomes[0][0];
        boolean single = true;
        for (ChunkerBiome[] row : cellBiomes) {
            for (ChunkerBiome biome : row) {
                if (biome != null && !biome.equals(first)) single = false;
            }
        }

        if (single) {
            palette = new SingleValuePalette<>(16, first);
        } else {
            ShortBasedPalette<ChunkerBiome> shortPalette = new ShortBasedPalette<>(4, 16);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    ChunkerBiome biome = cellBiomes[x >> 2][z >> 2];
                    short index = shortPalette.getOrCreateKey(biome == null ? defaultBiome : biome);
                    for (int y = 0; y < 16; y++) {
                        shortPalette.setPaletteIndex(x, y, z, index);
                    }
                }
            }
            palette = shortPalette;
        }

        List<Palette<ChunkerBiome>> list = new ArrayList<>(sections);
        for (int i = 0; i < sections; i++) {
            list.add(palette);
        }
        return new ChunkerPaletteBasedBiomes(list);
    }

    /**
     * Group the target chunks by region, in a stable order.
     *
     * @return a map of region key to chunk keys.
     */
    public java.util.Map<Long, LongArrayList> targetsByRegion() {
        java.util.Map<Long, LongArrayList> regions = new java.util.TreeMap<>();
        for (long key : targetChunks) {
            int chunkX = SurfaceProfiles.keyX(key), chunkZ = SurfaceProfiles.keyZ(key);
            regions.computeIfAbsent(SurfaceProfiles.key(chunkX >> 5, chunkZ >> 5), ignored -> new LongArrayList()).add(key);
        }
        return regions;
    }
}
