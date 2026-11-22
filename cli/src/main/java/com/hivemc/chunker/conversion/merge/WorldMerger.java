package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.conversion.handlers.LevelConversionHandler;
import com.hivemc.chunker.conversion.handlers.WorldConversionHandler;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.scheduling.task.Environment;
import com.hivemc.chunker.scheduling.task.Task;
import com.hivemc.chunker.scheduling.task.TaskWeight;
import com.hivemc.chunker.scheduling.task.TrackedTask;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Merges several worlds, of any supported edition and version, into one large world.
 * <ol>
 *     <li><b>Scan</b> - each world's level data and region list is read (no chunks) to find its size.</li>
 *     <li><b>Layout</b> - worlds are placed so they don't overlap; the first world keeps its co-ordinates so its spawn
 *     and player data stay valid.</li>
 *     <li><b>Passes</b> - each world is converted in turn through the normal Chunker pipeline into a single shared
 *     writer, with {@link MergeTransform} moving everything into place and generating the sub-zero layer for old
 *     worlds.</li>
 *     <li><b>Seams</b> - a final pass generates blended terrain around / between the worlds.</li>
 * </ol>
 */
public class WorldMerger {
    private final List<MergeSource> sources;
    private final MergeSettings settings;
    private final WorldConverter converter;
    private final Function<WorldConverter, Optional<? extends LevelWriter>> writerFactory;
    private final boolean keepOriginalNBT;
    private final Consumer<String> log;
    @Nullable
    private BiConsumer<String, TrackedTask<Void>> passMonitor;
    @Nullable
    private ChunkerLevel firstLevel;
    private boolean netherScaled;

    /**
     * Create a merger.
     *
     * @param sources         the worlds to merge, the first is the "main" world.
     * @param settings        merge settings.
     * @param converter       the converter (configure options such as mappings on it before calling run).
     * @param writerFactory   creates the output writer, it is called after the scan (so INPUT style formats can be
     *                        resolved) and must use the converter given to it.
     * @param keepOriginalNBT whether original NBT should be kept for worlds of the same edition as the output (this
     *                        keeps mobs and other data Chunker doesn't model).
     * @param log             where to send progress messages.
     */
    public WorldMerger(List<MergeSource> sources, MergeSettings settings, WorldConverter converter,
                       Function<WorldConverter, Optional<? extends LevelWriter>> writerFactory, boolean keepOriginalNBT,
                       Consumer<String> log) {
        if (sources.isEmpty()) throw new IllegalArgumentException("At least one world is required");
        this.sources = sources;
        this.settings = settings;
        this.converter = converter;
        this.writerFactory = writerFactory;
        this.keepOriginalNBT = keepOriginalNBT;
        this.log = log;
    }

    /**
     * Set a callback which is given each conversion pass so progress can be displayed.
     *
     * @param passMonitor the callback, it may block until the task completes (the merger waits anyway).
     */
    public void setPassMonitor(@Nullable BiConsumer<String, TrackedTask<Void>> passMonitor) {
        this.passMonitor = passMonitor;
    }

    public List<MergeSource> getSources() {
        return sources;
    }

    /**
     * Run the merge.
     *
     * @return a summary of what was merged.
     * @throws Exception if the merge failed.
     */
    public MergeReport run() throws Exception {
        MergeReport report = new MergeReport();

        // 1. Scan
        for (MergeSource source : sources) {
            scan(source);
            log.accept("Found " + source + ": " + describeBounds(source));
        }

        // 2. Layout
        MergeLayout.apply(sources, settings);
        netherScaled = settings.isScaleNether();
        if (netherScaled && MergeLayout.findOverlap(sources, Dimension.NETHER, true) != null) {
            log.accept("Scaled Nether placements would overlap, using the overworld offsets for the Nether instead "
                    + "(use a larger --align-regions, e.g. 8, to keep Nether portals linked).");
            netherScaled = false;
        }
        for (Dimension dimension : List.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.THE_END)) {
            String overlap = MergeLayout.findOverlap(sources, dimension, netherScaled);
            if (overlap != null) throw new IllegalStateException("Placement failed: " + overlap);
        }
        for (MergeSource source : sources) {
            log.accept("Placing " + source.getName() + " at offset " + source.getOffset());
        }

        // 3. Output writer (NBT copying must be decided before writer resolvers are built)
        converter.setAllowNBTCopying(keepOriginalNBT);
        LevelWriter writer = writerFactory.apply(converter).orElseThrow(() -> new IllegalStateException("Failed to create the output writer"));
        EncodingType outputType = writer.getEncodingType();
        boolean outputBelowZero = writer.getVersion().isGreaterThanOrEqual(1, 18, 0);
        MergeLevelWriter mergeWriter = new MergeLevelWriter(writer);
        log.accept("Writing " + outputType.getName() + " " + writer.getVersion());

        boolean seams = settings.getSeamMode() == MergeSettings.SeamMode.SYNTHESIZE;
        SurfaceProfiles profiles = seams ? new SurfaceProfiles() : null;
        BelowZeroGenerator belowZero = settings.isGenerateBelowZero() ? new BelowZeroGenerator(settings.getSeed()) : null;

        try {
            // 4. One pass per world
            for (int i = 0; i < sources.size(); i++) {
                MergeSource source = sources.get(i);
                boolean sameEdition = source.getEncodingType() == outputType;
                converter.setAllowNBTCopying(keepOriginalNBT && sameEdition);
                if (keepOriginalNBT && !sameEdition) {
                    log.accept("Note: " + source.getName() + " is a different edition, mobs and other un-modelled data "
                            + "can't be copied from it (chests, item frames and paintings still convert).");
                }

                LevelReader reader = EncodingType.findReader(source.getDirectory(), converter)
                        .orElseThrow(() -> new IllegalStateException("No reader for " + source.getDirectory()));
                MergeTransform transform = new MergeTransform(source, settings, netherScaled, outputBelowZero, belowZero, profiles);
                converter.setExtraHandlers(transform::levelHandler, transform::worldHandler, transform::columnHandler);
                mergeWriter.setFinalPass(i == sources.size() - 1 && !seams);

                runPass("Merging " + source.getName() + " (" + (i + 1) + "/" + sources.size() + ")", reader, mergeWriter);
                report.addSource(source, transform);
                log.accept(String.format("  %s: %d columns, %d block entities, %d entities%s",
                        source.getName(), transform.getColumnCount(), transform.getBlockEntityCount(),
                        transform.getEntityCount(),
                        transform.getBelowZeroColumnCount() > 0 ? ", " + transform.getBelowZeroColumnCount() + " columns extended below Y=0" : ""));
            }

            // 5. Seams
            if (seams) {
                converter.setExtraHandlers(null, null, null);
                mergeWriter.setFinalPass(true);
                SeamGenerator generator = new SeamGenerator(profiles, settings, outputBelowZero, Dimension.OVERWORLD.getFallbackBiome());
                long start = System.currentTimeMillis();
                int targets = generator.prepare();
                log.accept("Seams: " + targets + " chunks to generate (solved in " + (System.currentTimeMillis() - start) + "ms)");
                report.setSeamChunks(targets);

                ChunkerLevel base = firstLevel != null ? firstLevel : new ChunkerLevel();
                SeamLevelReader seamReader = new SeamLevelReader(generator, base, outputBelowZero, outputType,
                        writer.getVersion(), writer.getSupportedBiomes());
                runPass("Generating seams", seamReader, mergeWriter);
            }
        } catch (Throwable throwable) {
            try {
                mergeWriter.forceFree();
            } catch (Throwable ignored) {
                // Already failing
            }
            throw throwable;
        } finally {
            converter.setExtraHandlers(null, null, null);
        }

        return report;
    }

    private void runPass(String name, LevelReader reader, LevelWriter writer) throws Exception {
        TrackedTask<Void> task = converter.convert(reader, writer);
        if (passMonitor != null) {
            passMonitor.accept(name, task);
        }
        try {
            task.future().get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(name + " failed", e.getCause());
        }
        if (converter.isCancelled()) {
            throw new IllegalStateException(name + " was cancelled");
        }
    }

    private static String describeBounds(MergeSource source) {
        if (source.getBoundsByDimension().isEmpty()) return "no chunks";
        StringBuilder builder = new StringBuilder();
        source.getBoundsByDimension().forEach((dimension, bounds) -> {
            if (!builder.isEmpty()) builder.append("; ");
            builder.append(dimension).append(' ').append(bounds);
        });
        return builder.toString();
    }

    /**
     * Read the level data and region lists of a source without reading any chunks.
     */
    private void scan(MergeSource source) throws Exception {
        converter.setAllowNBTCopying(false);
        LevelReader reader = EncodingType.findReader(source.getDirectory(), converter)
                .orElseThrow(() -> new IllegalStateException("Couldn't detect a Minecraft world in " + source.getDirectory()));
        source.setEncodingType(reader.getEncodingType());
        source.setVersion(reader.getVersion());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Environment environment = Task.environment("Scanning " + source.getName(), 4, failure::set, null);
        try {
            Task.asyncConsume("Reading Level", TaskWeight.NORMAL, reader::readLevel, new LevelConversionHandler() {
                @Override
                public Task<WorldConversionHandler> convertLevel(ChunkerLevel level) {
                    source.setCavesAndCliffs(level.getSettings().CavesAndCliffs);
                    if (firstLevel == null && source == sources.get(0)) firstLevel = level;
                    WorldConversionHandler worldHandler = new WorldConversionHandler() {
                        @Override
                        public Task<ColumnConversionHandler> convertWorld(ChunkerWorld world) {
                            RegionBounds bounds = RegionBounds.of(world.getRegions());
                            if (bounds != null) {
                                synchronized (source) {
                                    source.getBoundsByDimension().merge(world.getDimension().getIdentifier(), bounds, RegionBounds::union);
                                }
                            }
                            return Task.async("Skipping columns", TaskWeight.NONE, (com.hivemc.chunker.scheduling.function.ThrowableSupplier<ColumnConversionHandler>) () -> null);
                        }

                        @Override
                        public void flushWorld(ChunkerWorld world) {
                        }

                        @Override
                        public void flushWorlds() {
                        }
                    };
                    return Task.async("Scanned level", TaskWeight.NONE, () -> worldHandler);
                }

                @Override
                public void flushLevel() {
                }
            });
        } finally {
            environment.close();
        }

        try {
            environment.future().get();
        } finally {
            try {
                reader.free();
            } catch (Exception e) {
                // Ignore failures to free a reader used for scanning
            }
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Failed to scan " + source.getName(), failure.get());
        }
    }

    /**
     * A summary of a merge.
     */
    public static class MergeReport {
        private final List<String> lines = new ArrayList<>();
        private long columns;
        private long blockEntities;
        private long entities;
        private long belowZeroColumns;
        private int seamChunks;

        void addSource(MergeSource source, MergeTransform transform) {
            columns += transform.getColumnCount();
            blockEntities += transform.getBlockEntityCount();
            entities += transform.getEntityCount();
            belowZeroColumns += transform.getBelowZeroColumnCount();
            lines.add(source.getName() + " -> offset " + source.getOffset());
        }

        void setSeamChunks(int seamChunks) {
            this.seamChunks = seamChunks;
        }

        public long getColumns() {
            return columns;
        }

        public long getBlockEntities() {
            return blockEntities;
        }

        public long getEntities() {
            return entities;
        }

        public long getBelowZeroColumns() {
            return belowZeroColumns;
        }

        public int getSeamChunks() {
            return seamChunks;
        }

        public List<String> getLines() {
            return lines;
        }

        @Override
        public String toString() {
            return "Merged " + lines.size() + " worlds: " + columns + " columns, " + blockEntities + " block entities, "
                    + entities + " entities, " + belowZeroColumns + " columns extended below Y=0, "
                    + seamChunks + " seam chunks generated";
        }
    }
}
