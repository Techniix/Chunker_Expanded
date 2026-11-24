package com.hivemc.chunker.cli;

import com.google.common.base.Stopwatch;
import com.hivemc.chunker.cli.messenger.Messenger;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.merge.ChunkOffset;
import com.hivemc.chunker.conversion.merge.MergeSettings;
import com.hivemc.chunker.conversion.merge.MergeSource;
import com.hivemc.chunker.conversion.merge.WorldMerger;
import com.hivemc.chunker.scheduling.task.TrackedTask;
import picocli.CommandLine;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Command line entry point for merging many worlds into one.
 * Example: java -jar chunker-cli.jar merge -f JAVA_1_21_11 -o merged worldA worldB bedrockWorldC
 */
@CommandLine.Command(
        name = "merge",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = {
                "Merge several Java and/or Bedrock worlds (any supported version) into one large world.",
                "The first world keeps its position, spawn and player data; the others are placed around it.",
                "Pre-1.18 worlds get deepslate terrain generated below Y=0 and the gaps between worlds are filled",
                "with generated terrain that blends heights, materials and biomes from one world's edge to the next."
        }
)
public class MergeCLI implements Callable<Integer> {
    @CommandLine.Parameters(arity = "1..*", paramLabel = "WORLD", description = "World directories to merge (first one is the main world).")
    private List<File> worlds = new ArrayList<>();

    @CommandLine.Option(names = {"--outputFormat", "-f"}, required = true, converter = EncodingTypeValidator.class,
            description = "Output format, e.g. JAVA_1_21_11 or BEDROCK_R21_130. INPUT uses the first world's format.")
    private String format;

    @CommandLine.Option(names = {"--outputDirectory", "-o"}, required = true, description = "Directory to write the merged world to.")
    private File outputDirectory;

    @CommandLine.Option(names = "--gap", defaultValue = "32", description = "Minimum gap between worlds in chunks (rounded up to whole regions). Default: ${DEFAULT-VALUE}.")
    private int gapChunks;

    @CommandLine.Option(names = "--layout", defaultValue = "GRID", description = "GRID or ROW. Default: ${DEFAULT-VALUE}.")
    private MergeSettings.Layout layout;

    @CommandLine.Option(names = "--offset", description = "Manual placement, WORLD_NAME=X,Z in blocks (multiples of 512). If any are given, unlisted worlds stay at 0,0.")
    private Map<String, String> offsets = new HashMap<>();

    @CommandLine.Option(names = "--seams", defaultValue = "SYNTHESIZE", description = "SYNTHESIZE (generate blended terrain between worlds) or NONE. Default: ${DEFAULT-VALUE}.")
    private MergeSettings.SeamMode seams;

    @CommandLine.Option(names = "--seam-width", defaultValue = "16", description = "How far seams reach from each world, in chunks. Default: ${DEFAULT-VALUE}.")
    private int seamWidth;

    @CommandLine.Option(names = "--max-corridor", defaultValue = "96", description = "Fill straight runs of empty chunks up to this long between worlds (and holes inside them) with blended terrain, 0 to disable. Default: ${DEFAULT-VALUE}.")
    private int maxCorridor;

    @CommandLine.Option(names = "--no-below-zero", description = "Don't generate terrain below Y=0 for pre-1.18 worlds.")
    private boolean noBelowZero;

    @CommandLine.Option(names = "--leave-gaps-to-game", description = "Java output only, experimental: only make a narrow blended ring around each world and leave the space between worlds for Minecraft to generate (with structures), blending into the ring. Same as --seam-width 3 --max-corridor 0 --java-blending.")
    private boolean leaveGapsToGame;

    @CommandLine.Option(names = "--java-blending", description = "Experimental: write Java blending_data so terrain the game generates later blends into the merged chunks.")
    private boolean javaBlending;

    @CommandLine.Option(names = "--align-regions", defaultValue = "1", description = "Placement alignment in regions; 8 keeps Nether portals linked exactly. Default: ${DEFAULT-VALUE}.")
    private int alignRegions;

    @CommandLine.Option(names = "--no-nether-scale", description = "Move the Nether by the same amount as the overworld instead of 1/8th.")
    private boolean noNetherScale;

    @CommandLine.Option(names = "--sea-level", defaultValue = "63", description = "Sea level for seam water. Default: ${DEFAULT-VALUE}.")
    private int seaLevel;

    @CommandLine.Option(names = "--seed", description = "Seed for generated caves / ores / seam detail.")
    private Long seed;

    @CommandLine.Option(names = {"--keepOriginalNBT", "-k"}, description = "Copy original NBT for worlds of the same edition as the output (keeps mobs and other data exactly).")
    private boolean keepOriginalNBT;

    @Override
    public Integer call() throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();

        // Sources
        Map<String, ChunkOffset> manual = new HashMap<>();
        for (Map.Entry<String, String> entry : offsets.entrySet()) {
            String[] parts = entry.getValue().split(",");
            if (parts.length != 2) throw new CommandLine.ParameterException(new CommandLine(this), "Offsets must be X,Z: " + entry.getValue());
            int x = Integer.parseInt(parts[0].trim()), z = Integer.parseInt(parts[1].trim());
            if (x % 512 != 0 || z % 512 != 0) {
                throw new CommandLine.ParameterException(new CommandLine(this), "Offsets must be multiples of 512 blocks: " + entry.getValue());
            }
            manual.put(entry.getKey(), new ChunkOffset(x >> 4, z >> 4));
        }
        List<MergeSource> sources = new ArrayList<>();
        for (File world : worlds) {
            if (!world.isDirectory()) {
                System.err.println("Not a directory: " + world);
                return 2;
            }
            sources.add(new MergeSource(world, manual.get(world.getName())));
        }

        // Settings
        MergeSettings settings = new MergeSettings()
                .setGapChunks(gapChunks)
                .setLayout(layout)
                .setSeamMode(seams)
                .setSeamWidthChunks(seamWidth)
                .setMaxCorridorChunks(maxCorridor)
                .setGenerateBelowZero(!noBelowZero)
                .setJavaBlendingHints(javaBlending)
                .setAlignRegions(alignRegions)
                .setScaleNether(!noNetherScale)
                .setSeaLevel(seaLevel);
        if (seed != null) settings.setSeed(seed);
        if (leaveGapsToGame) {
            settings.setSeamWidthChunks(3).setMaxCorridorChunks(0).setJavaBlendingHints(true);
        }

        WorldConverter converter = new WorldConverter(UUID.randomUUID());
        converter.setCompactionSignal((started) -> System.out.println(started ? "Compacting world, this may take a while..." : "Finished compacting world."));

        WorldMerger merger = new WorldMerger(sources, settings, converter, (worldConverter) -> createWriter(worldConverter, sources), keepOriginalNBT, System.out::println);
        merger.setPassMonitor(MergeCLI::printProgress);

        WorldMerger.MergeReport report = merger.run();
        report.writeGuide(outputDirectory);
        for (String line : report.getLines()) System.out.println(line);
        System.out.println(report);
        Duration duration = stopwatch.elapsed();
        System.out.printf(Locale.ROOT, "Merge complete! Took %dh %dm %ds%n", duration.toHoursPart(), duration.toMinutesPart(), duration.toSecondsPart());
        return 0;
    }

    private Optional<? extends LevelWriter> createWriter(WorldConverter worldConverter, List<MergeSource> sources) {
        if (format.equalsIgnoreCase("INPUT")) {
            MergeSource first = sources.get(0);
            EncodingType type = first.getEncodingType();
            return type == null ? Optional.empty() : type.createWriter(outputDirectory, first.getVersion(), worldConverter);
        }
        return Messenger.findWriter(format, worldConverter, outputDirectory);
    }

    private static void printProgress(String name, TrackedTask<Void> task) {
        System.out.println(name);
        double last = -1;
        while (!task.future().isDone()) {
            double progress = task.getProgress();
            if (progress - last >= 0.05 || (progress == 1 && last != 1)) {
                System.out.printf(Locale.ROOT, "  %.0f%%%n", progress * 100);
                last = progress;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
