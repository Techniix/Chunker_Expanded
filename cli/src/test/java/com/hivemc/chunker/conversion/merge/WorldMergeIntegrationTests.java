package com.hivemc.chunker.conversion.merge;

import com.google.common.io.Resources;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.integration.WorldConversionIntegrationTests;
import com.hivemc.chunker.conversion.encoding.base.Converter.MissingMappingType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Merges real test worlds of both editions and old / new versions together.
 */
public class WorldMergeIntegrationTests {
    private void merge(EncodingType outputType, String... worldNames) throws Exception {
        List<Path> inputs = new ArrayList<>();
        Path output = WorldConversionIntegrationTests.tempFolder();
        try {
            List<MergeSource> sources = new ArrayList<>();
            for (String name : worldNames) {
                Path unzipped = WorldConversionIntegrationTests.unzip(Resources.getResource("integration/worlds/" + name + ".zip"));
                inputs.add(unzipped);
                sources.add(new MergeSource(unzipped.toFile(), null));
            }

            WorldConverter converter = new WorldConverter(UUID.randomUUID()) {
                @Override
                public void logMissingMapping(MissingMappingType type, String identifier) {
                }
            };
            Version version = outputType.getSupportedVersions().stream().max(Version::compareTo).orElseThrow();
            WorldMerger merger = new WorldMerger(sources, new MergeSettings().setGapChunks(0), converter,
                    (worldConverter) -> outputType.createWriter(output.toFile(), version, worldConverter), true, (message) -> {
            });
            WorldMerger.MergeReport report = merger.run();

            assertFalse(converter.isExceptions(), "Exceptions were logged during the merge");
            assertTrue(output.resolve("level.dat").toFile().exists());
            assertTrue(report.getColumns() > 0);
            assertTrue(report.getBlockEntities() > 0);
            assertTrue(report.getBelowZeroColumns() > 0, "Old worlds should be extended below zero");
            assertTrue(report.getSeamChunks() > 0, "Seams should be generated");
            assertTrue(sources.get(0).getOffset().isZero());
        } finally {
            for (Path input : inputs) WorldConversionIntegrationTests.remove(input);
            WorldConversionIntegrationTests.remove(output);
        }
    }

    @Test
    public void testMergeMixedEditionsToJava() throws Exception {
        merge(EncodingType.JAVA, "JAVA_1_12_2", "BEDROCK_R20_80", "JAVA_1_20_5");
    }

    @Test
    public void testMergeMixedEditionsToBedrock() throws Exception {
        merge(EncodingType.BEDROCK, "BEDROCK_R12", "JAVA_1_16_5");
    }
}
