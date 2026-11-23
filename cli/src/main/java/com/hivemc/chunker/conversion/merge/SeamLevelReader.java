package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.conversion.handlers.LevelConversionHandler;
import com.hivemc.chunker.conversion.handlers.WorldConversionHandler;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevelSettings;
import com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.scheduling.task.ProgressiveTask;
import com.hivemc.chunker.scheduling.task.Task;
import com.hivemc.chunker.scheduling.task.TaskWeight;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A "reader" which produces the generated seam columns, so seams go through exactly the same conversion pipeline and
 * writer as the real worlds. It is run as the final pass of a merge.
 */
public class SeamLevelReader implements LevelReader {
    /**
     * How many seam regions are generated at the same time.
     */
    private static final int MAX_REGIONS_IN_FLIGHT = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    private final SeamGenerator generator;
    private final ChunkerLevel baseLevel;
    private final boolean belowZero;
    private final EncodingType encodingType;
    private final Version version;
    private final Set<ChunkerBiome.ChunkerVanillaBiome> supportedBiomes;

    /**
     * Create the seam reader.
     *
     * @param generator       the prepared seam generator.
     * @param baseLevel       the level of the first world (settings are copied from it).
     * @param belowZero       whether the output has the -64 floor.
     * @param encodingType    the output encoding type.
     * @param version         the output version.
     * @param supportedBiomes the output's supported biomes.
     */
    public SeamLevelReader(SeamGenerator generator, ChunkerLevel baseLevel, boolean belowZero, EncodingType encodingType,
                           Version version, Set<ChunkerBiome.ChunkerVanillaBiome> supportedBiomes) {
        this.generator = generator;
        this.baseLevel = baseLevel;
        this.belowZero = belowZero;
        this.encodingType = encodingType;
        this.version = version;
        this.supportedBiomes = supportedBiomes;
    }

    /**
     * Generate every {@code step}th region starting at {@code index}, one after another.
     */
    private void generateLane(List<Map.Entry<Long, LongArrayList>> entries, int index, int step,
                              ColumnConversionHandler columnConversionHandler) {
        if (index >= entries.size()) return;
        Map.Entry<Long, LongArrayList> entry = entries.get(index);
        RegionCoordPair region = new RegionCoordPair(SurfaceProfiles.keyX(entry.getKey()), SurfaceProfiles.keyZ(entry.getKey()));
        LongArrayList chunks = entry.getValue();
        Task.async("Generating seam region", TaskWeight.NORMAL, () -> {
                    for (int i = 0; i < chunks.size(); i++) {
                        long key = chunks.getLong(i);
                        columnConversionHandler.convertColumn(generator.generate(SurfaceProfiles.keyX(key), SurfaceProfiles.keyZ(key)));
                    }
                }).then("Seam region - Flushing", TaskWeight.MEDIUM, () -> columnConversionHandler.flushRegion(region))
                .then("Next seam region", TaskWeight.NORMAL, () -> generateLane(entries, index + step, step, columnConversionHandler));
    }

    @Override
    public void readLevel(LevelConversionHandler levelConversionHandler) {
        // The seam level copies the base settings, but always uses the height of the output
        ChunkerLevelSettings settings = ChunkerLevelSettings.fromJSON(baseLevel.getSettings().toJSON());
        settings.CavesAndCliffs = belowZero;
        ChunkerLevel level = new ChunkerLevel(settings, null, new ArrayList<>(), null, new ArrayList<>());

        Map<Long, LongArrayList> regions = generator.targetsByRegion();
        Set<RegionCoordPair> regionSet = new HashSet<>();
        for (long region : regions.keySet()) {
            regionSet.add(new RegionCoordPair(SurfaceProfiles.keyX(region), SurfaceProfiles.keyZ(region)));
        }

        ProgressiveTask<Void> worldReading = levelConversionHandler.convertLevel(level).thenConsume("Generating Seams", TaskWeight.HIGHEST, (worldConversionHandler) -> {
            if (worldConversionHandler == null) return;
            ChunkerWorld world = new ChunkerWorld(Dimension.OVERWORLD, regionSet);

            Task<ColumnConversionHandler> convertWorld = worldConversionHandler.convertWorld(world);
            Task<Void> regionProcessing = convertWorld.thenConsume("Generating seam regions", TaskWeight.HIGHER, (columnConversionHandler) -> {
                if (columnConversionHandler == null) return;

                ProgressiveTask<Void> generating = Task.async("Generating seam regions", TaskWeight.HIGHER, () -> {
                    // Generating is much faster than writing, so only a few regions are generated at once: each lane
                    // starts its next region once the previous one has been written. Without this every seam column
                    // would be generated up front and held in memory waiting to be written.
                    List<Map.Entry<Long, LongArrayList>> entries = new ArrayList<>(regions.entrySet());
                    int lanes = Math.max(1, Math.min(MAX_REGIONS_IN_FLIGHT, entries.size()));
                    for (int lane = 0; lane < lanes; lane++) {
                        generateLane(entries, lane, lanes, columnConversionHandler);
                    }
                });
                generating.then("Flushing seam columns", TaskWeight.MEDIUM, columnConversionHandler::flushColumns);
            });
            regionProcessing.then("Flushing seam world", TaskWeight.MEDIUM, () -> worldConversionHandler.flushWorld(world))
                    .then("Flushing Worlds", TaskWeight.MEDIUM, worldConversionHandler::flushWorlds);
        });

        worldReading.then("Flushing Level", TaskWeight.MEDIUM, levelConversionHandler::flushLevel);
    }

    @Override
    public EncodingType getEncodingType() {
        return encodingType;
    }

    @Override
    public Version getVersion() {
        return version;
    }

    @Override
    public Set<ChunkerBiome.ChunkerVanillaBiome> getSupportedBiomes() {
        return supportedBiomes;
    }
}
