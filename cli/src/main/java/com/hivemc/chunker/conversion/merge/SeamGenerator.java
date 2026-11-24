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
    private static final double DETAIL_AMPLITUDE = 22.0;
    private static final double DETAIL_GAIN = 3.0; // The noise has a spread of about a third, this makes it about one
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
    private float[] labelTrees;
    private float[] labelPlants;
    private short[] labelTop;
    private short[] labelFiller;
    private short[] labelBiome;
    // The nearest dry land's biome and plant cover: used for seam land whose nearest edge was a sea or river
    private short[] landBiome;
    private float[] landTrees;
    private float[] landPlants;
    private short[] labelWaterTop;

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
        floodLand();
        smoothRoughness();
        solveHeights();
        return targetChunks.size();
    }

    private void collectTargets() {
        int width = settings.getSeamWidthChunks();
        LongSet occupied = profiles.getOccupied(); // All passes are finished, so no locking is needed
        for (long key : profiles.getProfiles().keySet()) {
            int chunkX = SurfaceProfiles.keyX(key), chunkZ = SurfaceProfiles.keyZ(key);
            // Only chunks on the edge of the written area have empty chunks around them
            if (occupied.contains(SurfaceProfiles.key(chunkX + 1, chunkZ)) && occupied.contains(SurfaceProfiles.key(chunkX - 1, chunkZ))
                    && occupied.contains(SurfaceProfiles.key(chunkX, chunkZ + 1)) && occupied.contains(SurfaceProfiles.key(chunkX, chunkZ - 1))
                    && occupied.contains(SurfaceProfiles.key(chunkX + 1, chunkZ + 1)) && occupied.contains(SurfaceProfiles.key(chunkX - 1, chunkZ - 1))
                    && occupied.contains(SurfaceProfiles.key(chunkX - 1, chunkZ + 1)) && occupied.contains(SurfaceProfiles.key(chunkX + 1, chunkZ - 1))) {
                continue;
            }
            for (int dx = -width; dx <= width; dx++) {
                for (int dz = -width; dz <= width; dz++) {
                    int x = chunkX + dx, z = chunkZ + dz;
                    if (!occupied.contains(SurfaceProfiles.key(x, z))) {
                        targetChunks.add(SurfaceProfiles.key(x, z));
                    }
                }
            }
        }
        fillCorridors(occupied, settings.getMaxCorridorChunks());
        dropDistantProfiles();
    }

    /**
     * Free the profiles of chunks that are too far from any seam to be used (most of each world's interior).
     */
    private void dropDistantProfiles() {
        int reach = 4; // Covers the edge easing, interpolation and the domain warp of surface materials
        profiles.getProfiles().keySet().removeIf(key -> {
            int chunkX = SurfaceProfiles.keyX(key), chunkZ = SurfaceProfiles.keyZ(key);
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (targetChunks.contains(SurfaceProfiles.key(chunkX + dx, chunkZ + dz))) return false;
                }
            }
            return true;
        });
    }

    /**
     * Also fill empty runs of chunks which lie directly between two written chunks (in the same row or column), up to
     * a maximum length. This closes the space between neighbouring worlds that region-aligned placement leaves, and
     * small holes inside worlds, so they're bridged by blended terrain on both sides.
     */
    private void fillCorridors(LongSet occupied, int maxLength) {
        if (maxLength <= 0) return;
        java.util.Map<Integer, it.unimi.dsi.fastutil.ints.IntArrayList> rows = new java.util.HashMap<>();
        java.util.Map<Integer, it.unimi.dsi.fastutil.ints.IntArrayList> columns = new java.util.HashMap<>();
        for (long key : occupied) {
            int x = SurfaceProfiles.keyX(key), z = SurfaceProfiles.keyZ(key);
            rows.computeIfAbsent(z, ignored -> new it.unimi.dsi.fastutil.ints.IntArrayList()).add(x);
            columns.computeIfAbsent(x, ignored -> new it.unimi.dsi.fastutil.ints.IntArrayList()).add(z);
        }
        for (java.util.Map.Entry<Integer, it.unimi.dsi.fastutil.ints.IntArrayList> row : rows.entrySet()) {
            int[] xs = row.getValue().toIntArray();
            java.util.Arrays.sort(xs);
            for (int i = 1; i < xs.length; i++) {
                int gap = xs[i] - xs[i - 1] - 1;
                if (gap <= 0 || gap > maxLength) continue;
                if (profiles.get(xs[i - 1], row.getKey()) == null || profiles.get(xs[i], row.getKey()) == null) continue;
                for (int x = xs[i - 1] + 1; x < xs[i]; x++) targetChunks.add(SurfaceProfiles.key(x, row.getKey()));
            }
        }
        for (java.util.Map.Entry<Integer, it.unimi.dsi.fastutil.ints.IntArrayList> column : columns.entrySet()) {
            int[] zs = column.getValue().toIntArray();
            java.util.Arrays.sort(zs);
            for (int i = 1; i < zs.length; i++) {
                int gap = zs[i] - zs[i - 1] - 1;
                if (gap <= 0 || gap > maxLength) continue;
                if (profiles.get(column.getKey(), zs[i - 1]) == null || profiles.get(column.getKey(), zs[i]) == null) continue;
                for (int z = zs[i - 1] + 1; z < zs[i]; z++) targetChunks.add(SurfaceProfiles.key(column.getKey(), z));
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
        labelTrees = new float[count];
        labelPlants = new float[count];
        labelTop = new short[count];
        labelFiller = new short[count];
        labelBiome = new short[count];
        landBiome = new short[count];
        landTrees = new float[count];
        landPlants = new float[count];
        labelWaterTop = new short[count];
    }

    /**
     * Get the fixed ground height of a cell from a written world, or {@link SurfaceProfiles#NO_GROUND}.
     */
    private short fixedGround(int cx, int cz) {
        SurfaceProfiles.Profile profile = profiles.get(cx >> 2, cz >> 2);
        if (profile == null) return SurfaceProfiles.NO_GROUND;
        return profile.ground()[((cz & 3) << 2) | (cx & 3)];
    }

    private final it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap roughnessCache = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();

    /**
     * How hilly the written terrain around a chunk is: the spread (standard deviation) of ground heights over the
     * surrounding 5x5 chunks, so seams carry on hills at the same scale as the world they join.
     */
    private float roughness(int chunkX, int chunkZ) {
        long key = SurfaceProfiles.key(chunkX, chunkZ);
        if (roughnessCache.containsKey(key)) return roughnessCache.get(key);
        double sum = 0, sumSquares = 0;
        int count = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                SurfaceProfiles.Profile profile = profiles.get(chunkX + dx, chunkZ + dz);
                if (profile == null) continue;
                for (short ground : profile.ground()) {
                    if (ground == SurfaceProfiles.NO_GROUND) continue;
                    sum += ground;
                    sumSquares += (double) ground * ground;
                    count++;
                }
            }
        }
        float result = count < 2 ? 0 : (float) Math.sqrt(Math.max(0, sumSquares / count - (sum / count) * (sum / count)));
        roughnessCache.put(key, result);
        return result;
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
                labelRough[i] = roughness(nx >> 2, nz >> 2);
                labelTrees[i] = profile.treeCover();
                labelPlants[i] = profile.plantCover();
                labelTop[i] = profile.top()[sample];
                labelFiller[i] = profile.filler()[sample];
                labelBiome[i] = profile.biome()[sample];
                labelWaterTop[i] = profile.waterTop()[sample];
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
                labelTrees[j] = labelTrees[i];
                labelPlants[j] = labelPlants[i];
                labelTop[j] = labelTop[i];
                labelFiller[j] = labelFiller[i];
                labelBiome[j] = labelBiome[i];
                labelWaterTop[j] = labelWaterTop[i];
                queue.enqueue(j);
            }
        }
    }

    /**
     * Label every cell with the nearest dry land's biome and plant cover. Without this, land generated next to a sea
     * or river gets an ocean / river biome: bare grass with no trees or plants, and the wrong colours and mobs.
     */
    private void floodLand() {
        int count = cellX.length;
        int[] landDistance = new int[count];
        java.util.Arrays.fill(landDistance, Integer.MAX_VALUE);
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        for (int i = 0; i < count; i++) {
            for (int[] n : NEIGHBOURS) {
                int nx = cellX[i] + n[0], nz = cellZ[i] + n[1];
                if (cellIndex.get(cellKey(nx, nz)) != UNKNOWN) continue;
                SurfaceProfiles.Profile profile = profiles.get(nx >> 2, nz >> 2);
                if (profile == null) continue;
                int sample = ((nz & 3) << 2) | (nx & 3);
                if (profile.ground()[sample] == SurfaceProfiles.NO_GROUND || profile.water(sample)) continue;
                if (isWatery(profiles.biome(profile.biome()[sample]))) continue;
                landDistance[i] = 1;
                landBiome[i] = profile.biome()[sample];
                landTrees[i] = profile.treeCover();
                landPlants[i] = profile.plantCover();
                queue.enqueue(i);
                break;
            }
        }
        while (!queue.isEmpty()) {
            int i = queue.dequeueInt();
            for (int[] n : NEIGHBOURS) {
                int j = cellIndex.get(cellKey(cellX[i] + n[0], cellZ[i] + n[1]));
                if (j == UNKNOWN || landDistance[j] != Integer.MAX_VALUE) continue;
                landDistance[j] = landDistance[i] + 1;
                landBiome[j] = landBiome[i];
                landTrees[j] = landTrees[i];
                landPlants[j] = landPlants[i];
                queue.enqueue(j);
            }
        }
        // Worlds with no dry land at all: plains
        short plains = profiles.biomeId(ChunkerBiome.ChunkerVanillaBiome.PLAINS);
        for (int i = 0; i < count; i++) {
            if (landDistance[i] == Integer.MAX_VALUE) {
                landBiome[i] = plains;
                landTrees[i] = 0.05f;
                landPlants[i] = 0.3f;
            }
        }
    }

    /**
     * Whether a biome is a water biome (sea, river, beach) that shouldn't be given to dry land.
     */
    static boolean isWatery(ChunkerBiome biome) {
        String name = biome.toString().toUpperCase(java.util.Locale.ROOT);
        return name.contains("OCEAN") || name.contains("RIVER") || name.contains("BEACH") || name.contains("SHORE");
    }

    /**
     * Blur the hilliness taken from the nearest edge, so where seams from a rough world and a smooth world meet the
     * hills fade out gradually instead of stopping along a line.
     */
    private void smoothRoughness() {
        int count = cellX.length;
        float[] next = new float[count];
        for (int iteration = 0; iteration < 48; iteration++) {
            for (int i = 0; i < count; i++) {
                float sum = labelRough[i];
                int n = 1;
                for (int[] offset : NEIGHBOURS) {
                    int j = cellIndex.get(cellKey(cellX[i] + offset[0], cellZ[i] + offset[1]));
                    if (j == UNKNOWN) continue;
                    sum += labelRough[j];
                    n++;
                }
                next[i] = sum / n;
            }
            System.arraycopy(next, 0, labelRough, 0, count);
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
     * The solved height at a cell, using real terrain for written cells.
     *
     * @return the height or NaN if the cell is neither generated nor written.
     */
    private double cellHeight(int cx, int cz) {
        int i = cellIndex.get(cellKey(cx, cz));
        if (i != UNKNOWN) return height[i];
        short ground = fixedGround(cx, cz);
        return ground != SurfaceProfiles.NO_GROUND ? ground : Double.NaN;
    }

    /**
     * How much hill detail a cell gets: as rough as the nearest world's terrain, faded in away from its edge.
     */
    private double cellDetail(int cx, int cz) {
        int i = cellIndex.get(cellKey(cx, cz));
        if (i == UNKNOWN) return 0;
        int d = distance[i] == Integer.MAX_VALUE ? 64 : distance[i];
        double fade = Math.min(1.0, (d - 1) / 5.0);
        fade = fade * fade * (3 - 2 * fade);
        return Math.min(DETAIL_AMPLITUDE, labelRough[i] * 1.1) * fade;
    }

    /**
     * The surface height at a block: the solved field bilinearly interpolated between cell centres, plus hills.
     * Cells outside the generated / written area are ignored so the outer edge of a seam doesn't bend towards an
     * arbitrary value.
     */
    double blockHeight(int x, int z) {
        double fx = (x - 1.5) / 4.0, fz = (z - 1.5) / 4.0;
        int cx = (int) Math.floor(fx), cz = (int) Math.floor(fz);
        double tx = fx - cx, tz = fz - cz;
        double[] heights = {cellHeight(cx, cz), cellHeight(cx + 1, cz), cellHeight(cx, cz + 1), cellHeight(cx + 1, cz + 1)};
        double[] details = {cellDetail(cx, cz), cellDetail(cx + 1, cz), cellDetail(cx, cz + 1), cellDetail(cx + 1, cz + 1)};
        double[] weights = {(1 - tx) * (1 - tz), tx * (1 - tz), (1 - tx) * tz, tx * tz};
        double total = 0, detail = 0, weight = 0;
        for (int i = 0; i < 4; i++) {
            if (Double.isNaN(heights[i])) continue;
            total += heights[i] * weights[i];
            detail += details[i] * weights[i];
            weight += weights[i];
        }
        if (weight > 1e-9) {
            double amplitude = detail / weight;
            double hills = amplitude <= 0 ? 0 : amplitude * DETAIL_GAIN * (0.65 * noise.fbm2(SALT_DETAIL, x / 20.0, z / 20.0, 3)
                    + 0.55 * noise.fbm2(SALT_DETAIL + 1, x / 64.0, z / 64.0, 2));
            return total / weight + hills;
        }
        // Nothing known nearby (shouldn't happen for target chunks), use any known corner or the baseline
        for (double height : heights) if (!Double.isNaN(height)) return height;
        return baseline(x >> 2, z >> 2);
    }

    private static final int EDGE_BLEND_BLOCKS = 5;

    private static double easeToEdge(double height, SurfaceProfiles.Profile neighbour, int edge, int index, int distance) {
        if (neighbour == null || distance >= EDGE_BLEND_BLOCKS) return height;
        short edgeHeight = neighbour.edges()[edge][index];
        if (edgeHeight == SurfaceProfiles.NO_GROUND) return height;
        double t = (distance + 1) / (double) (EDGE_BLEND_BLOCKS + 1);
        t = t * t * (3 - 2 * t); // Smoothstep
        return edgeHeight + (height - edgeHeight) * t;
    }

    /**
     * Surface attributes looked up at a domain-warped position so borders between worlds look natural.
     */
    private record Surface(ChunkerBlockIdentifier top, ChunkerBlockIdentifier filler, ChunkerBiome biome, int waterTop,
                           ChunkerBiome landBiome) {
        boolean water() {
            return waterTop != SurfaceProfiles.NO_GROUND;
        }
    }

    private Surface surfaceAt(int x, int z) {
        // Warp further from the edges so borders between worlds' biomes / materials wander naturally
        int ownIndex = cellIndex.get(cellKey(x >> 2, z >> 2));
        double distanceBlocks = ownIndex == UNKNOWN || distance[ownIndex] == Integer.MAX_VALUE ? 0 : distance[ownIndex] * 4.0;
        double warp = WARP_BLOCKS + Math.min(48, distanceBlocks * 0.5);
        double wx = x + warp * noise.noise2(SALT_WARP_X, x / 24.0, z / 24.0);
        double wz = z + warp * noise.noise2(SALT_WARP_Z, x / 24.0, z / 24.0);
        int cx = (int) Math.floor(wx / 4.0), cz = (int) Math.floor(wz / 4.0);

        Surface surface = surfaceOfCell(cx, cz);
        if (surface == null) surface = surfaceOfCell(x >> 2, z >> 2);
        if (surface == null) return biomeSurface(defaultBiome);

        // Edge materials (a shore, a path, a snow cap) only reach a short way, then the biome's normal surface is used
        double reach = MATERIAL_REACH_BLOCKS * (0.7 + 0.6 * (noise.noise2(SALT_WARP_X + 7, x / 10.0, z / 10.0) * 0.5 + 0.5));
        if (distanceBlocks > reach) {
            // Away from the edge: the usual surface of the nearest land's biome (water is decided by sea level)
            Surface natural = biomeSurface(surface.landBiome());
            return new Surface(natural.top(), natural.filler(), surface.biome(), distanceBlocks < reach * 3 ? surface.waterTop() : SurfaceProfiles.NO_GROUND,
                    surface.landBiome());
        }
        return surface;
    }

    private static final double MATERIAL_REACH_BLOCKS = 14;

    /**
     * The usual surface for a biome.
     */
    private static Surface biomeSurface(ChunkerBiome biome) {
        String name = biome.toString().toUpperCase(java.util.Locale.ROOT);
        if (name.contains("DESERT") || name.contains("BEACH")) return new Surface(TerrainBlocks.SAND, TerrainBlocks.SAND, biome, SurfaceProfiles.NO_GROUND, biome);
        if (name.contains("BADLANDS") || name.contains("MESA")) {
            return new Surface(new ChunkerBlockIdentifier(ChunkerVanillaBlockType.RED_SAND), new ChunkerBlockIdentifier(ChunkerVanillaBlockType.TERRACOTTA), biome, SurfaceProfiles.NO_GROUND, biome);
        }
        if (name.contains("STONY") || name.contains("PEAK")) return new Surface(TerrainBlocks.STONE, TerrainBlocks.STONE, biome, SurfaceProfiles.NO_GROUND, biome);
        if (name.contains("MUSHROOM")) return new Surface(new ChunkerBlockIdentifier(ChunkerVanillaBlockType.MYCELIUM), TerrainBlocks.DIRT, biome, SurfaceProfiles.NO_GROUND, biome);
        return new Surface(TerrainBlocks.GRASS_BLOCK, TerrainBlocks.DIRT, biome, SurfaceProfiles.NO_GROUND, biome);
    }

    private Surface surfaceOfCell(int cx, int cz) {
        int i = cellIndex.get(cellKey(cx, cz));
        if (i != UNKNOWN) {
            if (distance[i] == Integer.MAX_VALUE) return null;
            return new Surface(profiles.block(labelTop[i]), profiles.block(labelFiller[i]), profiles.biome(labelBiome[i]), labelWaterTop[i],
                    profiles.biome(landBiome[i]));
        }
        SurfaceProfiles.Profile profile = profiles.get(cx >> 2, cz >> 2);
        if (profile == null) return null;
        int sample = ((cz & 3) << 2) | (cx & 3);
        if (profile.ground()[sample] == SurfaceProfiles.NO_GROUND) return null;
        ChunkerBiome biome = profiles.biome(profile.biome()[sample]);
        ChunkerBiome land = biome;
        if (isWatery(biome) || profile.water(sample)) {
            // A written water cell: borrow the land biome of the nearest generated cell
            int own = nearestCell(cx, cz);
            land = own == UNKNOWN ? profiles.biome(profiles.biomeId(ChunkerBiome.ChunkerVanillaBiome.PLAINS)) : profiles.biome(landBiome[own]);
        }
        return new Surface(profiles.block(profile.top()[sample]), profiles.block(profile.filler()[sample]),
                biome, profile.waterTop()[sample], land);
    }

    /**
     * The biome of a generated column: dry land never gets a sea / river biome (it takes the nearest land's biome),
     * and land that ended up deep under water becomes ocean.
     */
    private static ChunkerBiome columnBiome(Surface surface, boolean underwater, int depth) {
        if (!underwater) return isWatery(surface.biome()) ? surface.landBiome() : surface.biome();
        if (surface.water() || isWatery(surface.biome())) return surface.biome();
        if (depth < 5) return surface.landBiome(); // A pond or shallow lake
        String land = surface.landBiome().toString().toUpperCase(java.util.Locale.ROOT);
        boolean cold = land.contains("SNOW") || land.contains("FROZEN") || land.contains("ICE") || land.contains("GROVE");
        return cold ? ChunkerBiome.ChunkerVanillaBiome.FROZEN_OCEAN : ChunkerBiome.ChunkerVanillaBiome.OCEAN;
    }

    /**
     * A generated cell next to a cell (or the cell itself), or {@link #UNKNOWN}.
     */
    private int nearestCell(int cx, int cz) {
        for (int radius = 0; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int i = cellIndex.get(cellKey(cx + dx, cz + dz));
                    if (i != UNKNOWN) return i;
                }
            }
        }
        return UNKNOWN;
    }

    /**
     * The height of a generated column: the smooth field, eased into the exact edges of written neighbours.
     */
    private int generatedHeight(int wx, int wz, int minY) {
        int chunkX = wx >> 4, chunkZ = wz >> 4, x = wx & 15, z = wz & 15;
        double h = blockHeight(wx, wz);
        h = easeToEdge(h, profiles.get(chunkX - 1, chunkZ), 1, z, x);       // West neighbour's east edge, distance x
        h = easeToEdge(h, profiles.get(chunkX + 1, chunkZ), 0, z, 15 - x);  // East neighbour's west edge
        h = easeToEdge(h, profiles.get(chunkX, chunkZ - 1), 3, x, z);       // North neighbour's south edge
        h = easeToEdge(h, profiles.get(chunkX, chunkZ + 1), 2, x, 15 - z);  // South neighbour's north edge
        return Math.max(minY + 1, Math.min(300, (int) Math.round(h)));
    }

    private static final int WATER_EDGE_BLOCKS = 8;
    private static final int BANK_BLOCKS = 6;

    /**
     * The water surface for a generated column, or {@link SurfaceProfiles#NO_GROUND}. Water at the edge of a written
     * neighbour carries on into the seam at the same level (which may be above sea level for lakes and rivers).
     */
    private int waterTopAt(int wx, int wz, Surface surface, int height) {
        int chunkX = wx >> 4, chunkZ = wz >> 4, x = wx & 15, z = wz & 15;
        int best = SurfaceProfiles.NO_GROUND, bestDistance = WATER_EDGE_BLOCKS;
        int[][] candidates = {
                {chunkX - 1, chunkZ, 1, z, x}, {chunkX + 1, chunkZ, 0, z, 15 - x},
                {chunkX, chunkZ - 1, 3, x, z}, {chunkX, chunkZ + 1, 2, x, 15 - z}
        };
        for (int[] candidate : candidates) {
            if (candidate[4] >= bestDistance) continue;
            SurfaceProfiles.Profile neighbour = profiles.get(candidate[0], candidate[1]);
            if (neighbour == null) continue;
            short level = neighbour.edgeWater()[candidate[2]][candidate[3]];
            if (level == SurfaceProfiles.NO_GROUND) continue;
            best = level;
            bestDistance = candidate[4];
        }
        if (best != SurfaceProfiles.NO_GROUND) return best;
        // Further out, like natural generation, any ground below sea level is under water (seas, lakes and ponds).
        // A lake above sea level sits in a hollow of the world it came from, which the seam has no copy of.
        int seaTop = settings.getSeaLevel() - 1;
        // Old worlds' seas sit a block higher (y=63 before 1.18): carry on whichever sea level the nearest sea had
        int i = cellIndex.get(cellKey(wx >> 2, wz >> 2));
        if (i != UNKNOWN && labelWaterTop[i] != SurfaceProfiles.NO_GROUND && Math.abs(labelWaterTop[i] - seaTop) <= 2) {
            seaTop = labelWaterTop[i];
        }
        return height < seaTop ? seaTop : SurfaceProfiles.NO_GROUND;
    }

    /**
     * The exact ground height and water surface of a column in a written chunk, or null if it's not an edge column
     * of a profiled chunk.
     */
    @org.jetbrains.annotations.Nullable
    private int[] writtenEdge(int wx, int wz) {
        SurfaceProfiles.Profile profile = profiles.get(wx >> 4, wz >> 4);
        if (profile == null) return null;
        int x = wx & 15, z = wz & 15;
        int edge, index;
        if (x == 0) { edge = 0; index = z; }
        else if (x == 15) { edge = 1; index = z; }
        else if (z == 0) { edge = 2; index = x; }
        else if (z == 15) { edge = 3; index = x; }
        else return null;
        short ground = profile.edges()[edge][index];
        if (ground == SurfaceProfiles.NO_GROUND) return null;
        return new int[]{ground, profile.edgeWater()[edge][index]};
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
        int minY = belowZero ? BelowZeroGenerator.MIN_Y : 0;

        // Heights, surfaces and water for this chunk plus a border. The border is worked out exactly the way the
        // neighbouring chunk works it out (or read from a written neighbour), so water and slopes agree across chunks.
        int border = BANK_BLOCKS;
        int size = 16 + border * 2;
        int[][] grid = new int[size][size];
        int[][] gridWater = new int[size][size];
        boolean[][] gridWritten = new boolean[size][size];
        Surface[][] surfaces = new Surface[16][16];
        for (int x = -border; x < 16 + border; x++) {
            for (int z = -border; z < 16 + border; z++) {
                int wx = baseX + x, wz = baseZ + z;
                int gx = x + border, gz = z + border;
                if (targetChunks.contains(SurfaceProfiles.key(wx >> 4, wz >> 4))) {
                    Surface surface = surfaceAt(wx, wz);
                    if (x >= 0 && x <= 15 && z >= 0 && z <= 15) surfaces[x][z] = surface;
                    int h = generatedHeight(wx, wz, minY);
                    int waterTop = waterTopAt(wx, wz, surface, h);
                    grid[gx][gz] = h;
                    gridWater[gx][gz] = waterTop != SurfaceProfiles.NO_GROUND && h < waterTop ? waterTop : SurfaceProfiles.NO_GROUND;
                } else {
                    // A written (or empty) neighbour: use its exact edge
                    int[] written = writtenEdge(wx, wz);
                    gridWritten[gx][gz] = written != null;
                    grid[gx][gz] = written == null ? generatedHeight(wx, wz, minY) : written[0];
                    gridWater[gx][gz] = written == null ? SurfaceProfiles.NO_GROUND : written[1];
                }
            }
        }

        // Dry ground near water rises to a bank at the water's surface, sloping away one block per block, so water
        // never stands as a wall and shores look natural
        int[][] heights = new int[18][18];
        int[][] waterTops = new int[18][18];
        boolean[][] bank = new boolean[16][16];
        int[][] bankLevel = new int[16][16];
        for (int x = -1; x <= 16; x++) {
            for (int z = -1; z <= 16; z++) {
                int gx = x + border, gz = z + border;
                int h = grid[gx][gz];
                int nearestLevel = SurfaceProfiles.NO_GROUND;
                if (gridWater[gx][gz] == SurfaceProfiles.NO_GROUND && !gridWritten[gx][gz]) {
                    int original = h;
                    int cap = Integer.MAX_VALUE;
                    int reach = Math.min(border, Math.min(Math.min(gx, gz), Math.min(size - 1 - gx, size - 1 - gz)));
                    for (int dx = -reach; dx <= reach; dx++) {
                        for (int dz = -reach; dz <= reach; dz++) {
                            int distance = Math.max(Math.abs(dx), Math.abs(dz));
                            if (gridWritten[gx + dx][gz + dz] && gridWater[gx + dx][gz + dz] == SurfaceProfiles.NO_GROUND) {
                                // Stay joined to dry written terrain: rise at most a block per block away from it
                                cap = Math.min(cap, grid[gx + dx][gz + dz] + distance);
                            }
                            int level = gridWater[gx + dx][gz + dz];
                            if (level == SurfaceProfiles.NO_GROUND) continue;
                            int lifted = level - (Math.max(Math.abs(dx), Math.abs(dz)) - 1);
                            if (lifted > h) {
                                h = lifted;
                                nearestLevel = level;
                            }
                        }
                    }
                    h = Math.max(original, Math.min(h, cap));
                }
                heights[x + 1][z + 1] = h;
                waterTops[x + 1][z + 1] = gridWater[gx][gz];
                if (x >= 0 && x <= 15 && z >= 0 && z <= 15) {
                    bank[x][z] = h > grid[gx][gz];
                    bankLevel[x][z] = nearestLevel;
                }
            }
        }

        ChunkerBlockIdentifier[][] top = new ChunkerBlockIdentifier[16][16];
        ChunkerBlockIdentifier[][] filler = new ChunkerBlockIdentifier[16][16];
        boolean[][] wet = new boolean[16][16];
        int[][] columnWaterTop = new int[16][16];
        boolean anyWet = false;
        ChunkerBiome[][] cellBiomes = new ChunkerBiome[4][4];
        int minHeight = Integer.MAX_VALUE, maxHeight = Integer.MIN_VALUE;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int h = heights[x + 1][z + 1];
                Surface surface = surfaces[x][z];
                int waterTop = waterTops[x + 1][z + 1];
                boolean underwater = waterTop != SurfaceProfiles.NO_GROUND;
                minHeight = Math.min(minHeight, h);
                maxHeight = Math.max(maxHeight, underwater ? waterTop : h);

                int slope = Math.max(
                        Math.max(Math.abs(h - heights[x][z + 1]), Math.abs(h - heights[x + 2][z + 1])),
                        Math.max(Math.abs(h - heights[x + 1][z]), Math.abs(h - heights[x + 1][z + 2]))
                );
                ChunkerBlockIdentifier topBlock = surface.top();
                ChunkerBlockIdentifier fillerBlock = surface.filler();

                wet[x][z] = underwater;
                columnWaterTop[x][z] = waterTop;
                anyWet |= underwater;
                if (underwater) {
                    // Under water: keep real sea floor materials, otherwise sand near the surface and gravel deeper
                    if (!surface.water() || !TerrainBlocks.isSurfaceMaterial(topBlock) || isVegetatedSoil(topBlock)) {
                        topBlock = h >= waterTop - 5 ? TerrainBlocks.SAND : TerrainBlocks.GRAVEL;
                        fillerBlock = topBlock == TerrainBlocks.SAND ? TerrainBlocks.SAND : TerrainBlocks.GRAVEL;
                    }
                } else if (slope >= 4) {
                    // Steep slopes become bare stone like cliffs
                    topBlock = TerrainBlocks.STONE;
                    fillerBlock = TerrainBlocks.STONE;
                } else if (surface.water() || bank[x][z]) {
                    // A shore: above the water but the nearest edge was a lake / sea floor
                    int level = bank[x][z] ? bankLevel[x][z] : surface.waterTop();
                    topBlock = h <= level + 2 ? TerrainBlocks.SAND : TerrainBlocks.GRASS_BLOCK;
                    fillerBlock = topBlock == TerrainBlocks.SAND ? TerrainBlocks.SAND : TerrainBlocks.DIRT;
                }
                if (topBlock.getType() == ChunkerVanillaBlockType.SAND && fillerBlock.getType() != ChunkerVanillaBlockType.SAND) {
                    fillerBlock = TerrainBlocks.SAND;
                }

                top[x][z] = topBlock;
                filler[x][z] = fillerBlock;
                if ((x & 3) == 1 && (z & 3) == 1) {
                    cellBiomes[x >> 2][z >> 2] = columnBiome(surface, underwater, underwater ? waterTop - h : 0);
                }
            }
        }

        ChunkerColumn column = new ChunkerColumn(new ChunkCoordPair(chunkX, chunkZ));
        int minSection = minY >> 4;
        int maxSection = maxHeight >> 4;
        for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
            ChunkerChunk chunk = new ChunkerChunk((byte) sectionY);
            chunk.setPalette(buildSection(chunkX, chunkZ, sectionY, heights, top, filler, wet, minHeight, columnWaterTop));
            column.getChunks().put((byte) sectionY, chunk);
        }

        // Biomes are 2D for seams, one 16x16x16 palette shared by every section
        column.setBiomes(buildBiomes(cellBiomes, belowZero ? 24 : 16));

        // Plants and trees
        decorate(column, chunkX, chunkZ, heights, top, wet, columnWaterTop, cellBiomes);
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
                                                          boolean[][] wet, int minHeight, int[][] waterTop) {
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
                        if (wet[x][z] && y <= waterTop[x][z]) {
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

    private static final long SALT_PLANTS = 201;
    private static final long SALT_TREES = 202;
    private static final ChunkerBlockIdentifier SHORT_GRASS = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.SHORT_GRASS);
    private static final ChunkerBlockIdentifier FERN = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.FERN);
    private static final ChunkerBlockIdentifier DEAD_BUSH = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.DEAD_BUSH);
    private static final ChunkerBlockIdentifier CACTUS = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.CACTUS);
    private static final ChunkerBlockIdentifier SNOW_LAYER = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.SNOW);
    private static final ChunkerBlockIdentifier[] FLOWERS = {
            new ChunkerBlockIdentifier(ChunkerVanillaBlockType.DANDELION),
            new ChunkerBlockIdentifier(ChunkerVanillaBlockType.POPPY),
            new ChunkerBlockIdentifier(ChunkerVanillaBlockType.AZURE_BLUET),
            new ChunkerBlockIdentifier(ChunkerVanillaBlockType.OXEYE_DAISY),
            new ChunkerBlockIdentifier(ChunkerVanillaBlockType.CORNFLOWER)
    };

    /**
     * How a biome is decorated.
     *
     * @param trees    chance per block column of a tree (on grass).
     * @param grass    chance of short grass.
     * @param flowers  chance of a flower.
     * @param log      the tree log.
     * @param leaves   the tree leaves.
     * @param conifer  whether trees are conical (spruce style).
     * @param desert   whether to place cacti / dead bushes on sand.
     * @param snow     whether to cover the surface with snow.
     */
    /**
     * Extra features a biome has.
     *
     * @param secondLog    a second tree type mixed in (or null).
     * @param secondLeaves its leaves.
     * @param secondShare  the share of trees that are the second type.
     * @param boulders     mossy boulders per column.
     * @param bushes       small bushes per column.
     * @param lilyPads     lily pads per shallow water column.
     * @param flowerPatchy whether flowers grow in patches (meadows, plains) rather than evenly.
     */
    record Style(@org.jetbrains.annotations.Nullable ChunkerBlockIdentifier secondLog,
                 @org.jetbrains.annotations.Nullable ChunkerBlockIdentifier secondLeaves, double secondShare,
                 double boulders, double bushes, double lilyPads, boolean flowerPatchy) {
        static final Style NONE = new Style(null, null, 0, 0, 0, 0, false);
    }

    record Vegetation(double trees, double grass, double flowers, ChunkerBlockIdentifier log, ChunkerBlockIdentifier leaves,
                      boolean conifer, boolean desert, boolean snow, Style style) {
    }

    private static final ChunkerBlockIdentifier OAK_LOG = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.OAK_LOG);
    private static final ChunkerBlockIdentifier OAK_LEAVES = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.OAK_LEAVES);
    private static final ChunkerBlockIdentifier BIRCH_LOG = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.BIRCH_LOG);
    private static final ChunkerBlockIdentifier BIRCH_LEAVES = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.BIRCH_LEAVES);
    private static final ChunkerBlockIdentifier SPRUCE_LOG = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.SPRUCE_LOG);
    private static final ChunkerBlockIdentifier SPRUCE_LEAVES = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.SPRUCE_LEAVES);
    private static final ChunkerBlockIdentifier JUNGLE_LOG = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.JUNGLE_LOG);
    private static final ChunkerBlockIdentifier JUNGLE_LEAVES = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.JUNGLE_LEAVES);
    private static final ChunkerBlockIdentifier DARK_OAK_LOG = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.DARK_OAK_LOG);
    private static final ChunkerBlockIdentifier DARK_OAK_LEAVES = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.DARK_OAK_LEAVES);
    private static final ChunkerBlockIdentifier ACACIA_LOG = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.ACACIA_LOG);
    private static final ChunkerBlockIdentifier ACACIA_LEAVES = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.ACACIA_LEAVES);
    private static final ChunkerBlockIdentifier CHERRY_LOG = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.CHERRY_LOG);
    private static final ChunkerBlockIdentifier CHERRY_LEAVES = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.CHERRY_LEAVES);
    private static final ChunkerBlockIdentifier SEAGRASS = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.SEAGRASS);
    private static final ChunkerBlockIdentifier LILY_PAD = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.LILY_PAD);
    private static final ChunkerBlockIdentifier SUGAR_CANE = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.SUGAR_CANE);
    private static final ChunkerBlockIdentifier MOSSY_COBBLESTONE = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.MOSSY_COBBLESTONE);
    private static final ChunkerBlockIdentifier COBBLESTONE = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.COBBLESTONE);

    /**
     * Pick the decoration for a biome from its name, roughly matching natural generation's densities.
     */
    static Vegetation vegetation(ChunkerBiome biome) {
        String name = biome.toString().toUpperCase(java.util.Locale.ROOT);
        boolean snow = name.contains("SNOW") || name.contains("FROZEN") || name.contains("ICE") || name.contains("GROVE");
        if (name.contains("OCEAN") || name.contains("RIVER") || name.contains("BEACH") || name.contains("SHORE")
                || name.contains("VOID") || name.contains("NETHER") || name.contains("END")
                || name.contains("PEAK") || name.contains("STONY") || name.contains("DEEP")) {
            return new Vegetation(0, 0.02, 0, OAK_LOG, OAK_LEAVES, false, false, snow || name.contains("PEAK"), Style.NONE);
        }
        if (name.contains("MUSHROOM")) return new Vegetation(0, 0, 0, OAK_LOG, OAK_LEAVES, false, false, false, Style.NONE);
        if (name.contains("DESERT") || name.contains("BADLANDS") || name.contains("MESA")) {
            return new Vegetation(0, 0.01, 0, OAK_LOG, OAK_LEAVES, false, true, false, Style.NONE);
        }
        if (name.contains("TAIGA") || name.contains("GROVE") || name.contains("SNOWY") || name.contains("SPRUCE") || name.contains("PINE")) {
            boolean sparse = name.contains("SLOPES") || name.contains("PLAINS");
            return new Vegetation(sparse ? 0.004 : 0.03, 0.2, 0.004, SPRUCE_LOG, SPRUCE_LEAVES, true, false, snow,
                    new Style(null, null, 0, sparse ? 0 : 0.0012, 0, 0, false));
        }
        if (name.contains("CHERRY")) {
            return new Vegetation(0.012, 0.3, 0.06, CHERRY_LOG, CHERRY_LEAVES, false, false, false, new Style(null, null, 0, 0, 0, 0, true));
        }
        if (name.contains("JUNGLE")) {
            return new Vegetation(name.contains("SPARSE") ? 0.015 : 0.05, 0.4, 0.01, JUNGLE_LOG, JUNGLE_LEAVES, false, false, false,
                    new Style(OAK_LOG, OAK_LEAVES, 0.3, 0, 0.02, 0, false));
        }
        if (name.contains("DARK") || name.contains("ROOFED")) {
            return new Vegetation(0.05, 0.15, 0.005, DARK_OAK_LOG, DARK_OAK_LEAVES, false, false, false,
                    new Style(BIRCH_LOG, BIRCH_LEAVES, 0.15, 0.0008, 0.004, 0, false));
        }
        if (name.contains("BIRCH")) {
            return new Vegetation(0.03, 0.2, 0.012, BIRCH_LOG, BIRCH_LEAVES, false, false, false, new Style(null, null, 0, 0.0005, 0.002, 0, false));
        }
        if (name.contains("SWAMP") || name.contains("MANGROVE")) {
            return new Vegetation(0.012, 0.15, 0.01, OAK_LOG, OAK_LEAVES, false, false, false, new Style(null, null, 0, 0, 0.003, 0.08, false));
        }
        if (name.contains("FOREST") || name.contains("WOOD")) {
            boolean flower = name.contains("FLOWER");
            return new Vegetation(flower ? 0.02 : 0.035, 0.2, flower ? 0.12 : 0.012, OAK_LOG, OAK_LEAVES, false, false, false,
                    new Style(BIRCH_LOG, BIRCH_LEAVES, 0.2, 0.0006, 0.003, 0, flower));
        }
        if (name.contains("SAVANNA")) {
            return new Vegetation(0.004, 0.45, 0.004, ACACIA_LOG, ACACIA_LEAVES, false, false, false, new Style(OAK_LOG, OAK_LEAVES, 0.2, 0, 0.001, 0, false));
        }
        if (name.contains("MEADOW") || name.contains("SUNFLOWER")) {
            return new Vegetation(0.001, 0.45, 0.1, OAK_LOG, OAK_LEAVES, false, false, false, new Style(BIRCH_LOG, BIRCH_LEAVES, 0.5, 0, 0, 0, true));
        }
        if (name.contains("HILLS") || name.contains("WINDSWEPT") || name.contains("MOUNTAIN")) {
            return new Vegetation(0.006, 0.2, 0.004, SPRUCE_LOG, SPRUCE_LEAVES, true, false, snow, new Style(OAK_LOG, OAK_LEAVES, 0.5, 0.0015, 0, 0, false));
        }
        // Plains and anything else: lots of grass, the odd tree and patches of flowers
        return new Vegetation(0.0015, 0.4, 0.03, OAK_LOG, OAK_LEAVES, false, false, snow, new Style(null, null, 0, 0, 0.0008, 0.01, true));
    }

    private void decorate(ChunkerColumn column, int chunkX, int chunkZ, int[][] heights, ChunkerBlockIdentifier[][] top,
                          boolean[][] wet, int[][] waterTop, ChunkerBiome[][] cellBiomes) {
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        boolean[][] occupied = new boolean[16][16];

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (occupied[x][z]) continue;
                int h = heights[x + 1][z + 1];
                ChunkerBiome biome = cellBiomes[x >> 2][z >> 2];
                Vegetation vegetation = blendVegetation(vegetation(biome == null ? defaultBiome : biome), worldX(baseX, x), worldX(baseZ, z));
                Style style = vegetation.style();
                ChunkerVanillaBlockType ground = top[x][z].getType() instanceof ChunkerVanillaBlockType vanilla ? vanilla : null;
                int worldX = baseX + x, worldZ = baseZ + z;
                double roll = noise.random(SALT_PLANTS, worldX, h, worldZ);

                if (wet[x][z]) {
                    // Water: seagrass on the bottom, lily pads on shallow still water
                    int depth = waterTop[x][z] - h;
                    if (depth >= 2 && roll < 0.18 && (ground == ChunkerVanillaBlockType.SAND || ground == ChunkerVanillaBlockType.GRAVEL
                            || ground == ChunkerVanillaBlockType.DIRT || ground == ChunkerVanillaBlockType.CLAY)) {
                        column.setBlock(x, h + 1, z, SEAGRASS);
                    } else if (depth <= 2 && roll > 1 - style.lilyPads() && !vegetation.snow()) {
                        column.setBlock(x, waterTop[x][z] + 1, z, LILY_PAD);
                    }
                    continue;
                }

                boolean soil = ground == ChunkerVanillaBlockType.GRASS_BLOCK || ground == ChunkerVanillaBlockType.PODZOL;
                if ((soil || ground == ChunkerVanillaBlockType.SAND || ground == ChunkerVanillaBlockType.DIRT) && besideWater(wet, waterTop, x, z, h)
                        && !vegetation.snow() && roll < 0.12) {
                    // Sugar cane along the water's edge
                    int height = 1 + (int) (noise.random(SALT_PLANTS + 3, worldX, 0, worldZ) * 3);
                    for (int y = 1; y <= height; y++) column.setBlock(x, h + y, z, SUGAR_CANE);
                    occupied[x][z] = true;
                    continue;
                }

                if (soil) {
                    boolean flat = Math.abs(heights[x][z + 1] - h) <= 1 && Math.abs(heights[x + 2][z + 1] - h) <= 1
                            && Math.abs(heights[x + 1][z] - h) <= 1 && Math.abs(heights[x + 1][z + 2] - h) <= 1;
                    boolean inside = x >= 2 && x <= 13 && z >= 2 && z <= 13;
                    // Trees need room: they're kept inside the chunk and on flat ground. Density varies in groves.
                    double grove = 0.55 + 0.9 * (noise.noise2(SALT_TREES + 5, worldX / 48.0, worldZ / 48.0) * 0.5 + 0.5);
                    if (flat && inside && noise.random(SALT_TREES, worldX, 0, worldZ) < vegetation.trees() * grove) {
                        boolean second = style.secondLog() != null && noise.random(SALT_TREES + 3, worldX, 0, worldZ) < style.secondShare();
                        ChunkerBlockIdentifier log = second ? style.secondLog() : vegetation.log();
                        ChunkerBlockIdentifier leaves = second ? style.secondLeaves() : vegetation.leaves();
                        placeTree(column, x, h + 1, z, log, leaves, vegetation.conifer() && !second, worldX, worldZ, occupied);
                        continue;
                    }
                    if (flat && x >= 1 && x <= 14 && z >= 1 && z <= 14 && noise.random(SALT_TREES + 4, worldX, 0, worldZ) < style.boulders()) {
                        placeBoulder(column, x, h, z, worldX, worldZ, occupied);
                        continue;
                    }
                    if (flat && x >= 1 && x <= 14 && z >= 1 && z <= 14 && noise.random(SALT_TREES + 6, worldX, 0, worldZ) < style.bushes()) {
                        placeBush(column, x, h + 1, z, vegetation.leaves(), occupied);
                        continue;
                    }

                    double flowers = vegetation.flowers();
                    if (style.flowerPatchy()) {
                        // Flowers come in patches of one kind, like natural flower patches
                        double patch = noise.noise2(SALT_PLANTS + 4, worldX / 14.0, worldZ / 14.0);
                        flowers = patch > 0.35 ? Math.min(0.5, flowers * 8) : flowers * 0.1;
                    }
                    if (roll < flowers) {
                        int kind = style.flowerPatchy()
                                ? (int) ((noise.noise2(SALT_PLANTS + 5, worldX / 30.0, worldZ / 30.0) * 0.5 + 0.5) * FLOWERS.length)
                                : (int) (noise.random(SALT_PLANTS + 1, worldX, 0, worldZ) * FLOWERS.length);
                        column.setBlock(x, h + 1, z, FLOWERS[Math.max(0, Math.min(FLOWERS.length - 1, kind))]);
                    } else if (roll < flowers + vegetation.grass()) {
                        column.setBlock(x, h + 1, z, vegetation.conifer() && roll < flowers + vegetation.grass() * 0.4 ? FERN : SHORT_GRASS);
                    } else if (vegetation.snow()) {
                        column.setBlock(x, h + 1, z, SNOW_LAYER);
                    }
                } else if (ground == ChunkerVanillaBlockType.SAND || ground == ChunkerVanillaBlockType.RED_SAND) {
                    if (vegetation.desert()) {
                        if (roll < 0.004 && x > 0 && x < 15 && z > 0 && z < 15) {
                            int height = 1 + (int) (noise.random(SALT_PLANTS + 2, worldX, 0, worldZ) * 3);
                            for (int y = 1; y <= height; y++) column.setBlock(x, h + y, z, CACTUS);
                            occupied[x][z] = true;
                        } else if (roll < 0.012) {
                            column.setBlock(x, h + 1, z, DEAD_BUSH);
                        }
                    }
                } else if (vegetation.snow() && ground != null && TerrainBlocks.isGround(top[x][z])) {
                    column.setBlock(x, h + 1, z, SNOW_LAYER);
                }
            }
        }
    }

    /**
     * Whether a dry column sits right at the edge of water (level with its surface), where sugar cane grows.
     */
    private static boolean besideWater(boolean[][] wet, int[][] waterTop, int x, int z, int h) {
        for (int[] n : NEIGHBOURS) {
            int nx = x + n[0], nz = z + n[1];
            if (nx < 0 || nz < 0 || nx > 15 || nz > 15) continue;
            if (wet[nx][nz] && waterTop[nx][nz] == h) return true;
        }
        return false;
    }

    /**
     * A small mossy boulder, partly sunk into the ground.
     */
    private void placeBoulder(ChunkerColumn column, int x, int h, int z, int worldX, int worldZ, boolean[][] occupied) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                occupied[x + dx][z + dz] = true;
                boolean corner = dx != 0 && dz != 0;
                int top = corner ? 0 : 1;
                if (dx == 0 && dz == 0) top = 2;
                for (int y = 0; y <= top; y++) {
                    if (corner && noise.random(SALT_TREES + 7, worldX + dx, y, worldZ + dz) < 0.5) continue;
                    column.setBlock(x + dx, h + y, z + dz, noise.random(SALT_TREES + 8, worldX + dx, y, worldZ + dz) < 0.6 ? MOSSY_COBBLESTONE : COBBLESTONE);
                }
            }
        }
    }

    /**
     * A bush: a log stump wrapped in leaves (the log stops the leaves decaying).
     */
    private void placeBush(ChunkerColumn column, int x, int y, int z, ChunkerBlockIdentifier leaves, boolean[][] occupied) {
        column.setBlock(x, y, z, OAK_LOG);
        for (int[] n : NEIGHBOURS) {
            if (column.getBlock(x + n[0], y, z + n[1]).isAir()) column.setBlock(x + n[0], y, z + n[1], leaves);
            occupied[x + n[0]][z + n[1]] = true;
        }
        column.setBlock(x, y + 1, z, leaves);
        occupied[x][z] = true;
    }

    private static int worldX(int base, int local) {
        return base + local;
    }

    /**
     * Near a world's edge, use the plant / tree density actually found there, fading to the biome's usual density.
     */
    private Vegetation blendVegetation(Vegetation biome, int x, int z) {
        int i = cellIndex.get(cellKey(x >> 2, z >> 2));
        if (i == UNKNOWN || distance[i] == Integer.MAX_VALUE) return biome;
        double t = Math.min(1.0, distance[i] * 4.0 / VEGETATION_FADE_BLOCKS);
        // A canopy covers ~20 columns per tree
        double edgeTrees = landTrees[i] / 20.0;
        double edgePlants = landPlants[i];
        return new Vegetation(
                edgeTrees + (biome.trees() - edgeTrees) * t,
                edgePlants * 0.85 + (biome.grass() - edgePlants * 0.85) * t,
                edgePlants * 0.15 * (1 - t) + biome.flowers() * t,
                biome.log(), biome.leaves(), biome.conifer(), biome.desert(), biome.snow(), biome.style()
        );
    }

    private static final double VEGETATION_FADE_BLOCKS = 96;

    private void placeTree(ChunkerColumn column, int x, int y, int z, ChunkerBlockIdentifier log, ChunkerBlockIdentifier leaves,
                           boolean conifer, int worldX, int worldZ, boolean[][] occupied) {
        int trunk = 4 + (int) (noise.random(SALT_TREES + 1, worldX, 0, worldZ) * 3);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                occupied[x + dx][z + dz] = true;
            }
        }

        if (conifer) {
            // Spruce: narrowing rings of leaves up the trunk
            trunk += 2;
            for (int level = 2; level <= trunk + 1; level++) {
                int radius = level == trunk + 1 ? 0 : ((trunk - level) % 2 == 0 ? 2 : 1);
                if (level > trunk - 1) radius = Math.min(radius, 1);
                leafLayer(column, x, y + level, z, radius, leaves, false, worldX, worldZ);
            }
        } else {
            // Oak / birch: two wide layers and two narrow layers at the top
            leafLayer(column, x, y + trunk - 2, z, 2, leaves, true, worldX, worldZ);
            leafLayer(column, x, y + trunk - 1, z, 2, leaves, true, worldX, worldZ);
            leafLayer(column, x, y + trunk, z, 1, leaves, true, worldX, worldZ);
            leafLayer(column, x, y + trunk + 1, z, 1, leaves, false, worldX, worldZ);
        }
        for (int i = 0; i < trunk; i++) {
            column.setBlock(x, y + i, z, log);
        }
        // The block under a tree is dirt
        column.setBlock(x, y - 1, z, TerrainBlocks.DIRT);
    }

    private void leafLayer(ChunkerColumn column, int x, int y, int z, int radius, ChunkerBlockIdentifier leaves, boolean trimCorners,
                           int worldX, int worldZ) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius && radius > 0;
                if (corner && (trimCorners ? noise.random(SALT_TREES + 2, worldX + dx, y, worldZ + dz) < 0.5 : true)) continue;
                if (column.getBlock(x + dx, y, z + dz).isAir()) {
                    column.setBlock(x + dx, y, z + dz, leaves);
                }
            }
        }
    }

}
