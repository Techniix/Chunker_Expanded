package com.hivemc.chunker.cli.messenger.messaging.request;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hivemc.chunker.cli.messenger.messaging.BasicMessage;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * A request to merge several worlds into one (see the merge package).
 */
public class MergeRequest extends BasicMessage {
    private final UUID anonymousId;
    private final List<String> inputPaths;
    private final String outputPath;
    private final String outputType;
    private final boolean copyNbt;
    @Nullable
    private final Integer gapChunks;
    @Nullable
    private final Integer seamWidthChunks;
    @Nullable
    private final Boolean seams;
    @Nullable
    private final Integer maxCorridorChunks;
    @Nullable
    private final Boolean belowZero;
    @Nullable
    private final String layout;
    @Nullable
    private final Integer alignRegions;
    private final boolean javaBlending;
    private final boolean skipMaps;
    private final boolean skipLootTables;
    @Nullable
    private final Boolean enableCompact;
    @Nullable
    private final JsonElement nbtSettings; // A JsonElement so an explicit null in the request is accepted

    public MergeRequest(UUID anonymousId, List<String> inputPaths, String outputPath, String outputType, boolean copyNbt,
                        @Nullable Integer gapChunks, @Nullable Integer seamWidthChunks, @Nullable Boolean seams,
                        @Nullable Boolean belowZero, @Nullable String layout, @Nullable Integer alignRegions,
                        boolean javaBlending, boolean skipMaps, boolean skipLootTables, @Nullable Boolean enableCompact,
                        @Nullable JsonObject nbtSettings) {
        this.anonymousId = anonymousId;
        this.inputPaths = inputPaths;
        this.outputPath = outputPath;
        this.outputType = outputType;
        this.copyNbt = copyNbt;
        this.gapChunks = gapChunks;
        this.seamWidthChunks = seamWidthChunks;
        this.seams = seams;
        this.maxCorridorChunks = null;
        this.belowZero = belowZero;
        this.layout = layout;
        this.alignRegions = alignRegions;
        this.javaBlending = javaBlending;
        this.skipMaps = skipMaps;
        this.skipLootTables = skipLootTables;
        this.enableCompact = enableCompact;
        this.nbtSettings = nbtSettings;
    }

    public UUID getAnonymousId() {
        return anonymousId;
    }

    public List<String> getInputPaths() {
        return inputPaths;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public String getOutputType() {
        return outputType;
    }

    public boolean isCopyNbt() {
        return copyNbt;
    }

    @Nullable
    public Integer getGapChunks() {
        return gapChunks;
    }

    @Nullable
    public Integer getSeamWidthChunks() {
        return seamWidthChunks;
    }

    @Nullable
    public Boolean getSeams() {
        return seams;
    }

    @Nullable
    public Boolean getBelowZero() {
        return belowZero;
    }

    @Nullable
    public String getLayout() {
        return layout;
    }

    @Nullable
    public Integer getAlignRegions() {
        return alignRegions;
    }

    /**
     * The widest gap between worlds that is filled with generated terrain (0 to leave gaps for the game to generate).
     *
     * @return the number of chunks or null for the default.
     */
    @Nullable
    public Integer getMaxCorridorChunks() {
        return maxCorridorChunks;
    }

    public boolean isJavaBlending() {
        return javaBlending;
    }

    public boolean isSkipMaps() {
        return skipMaps;
    }

    public boolean isSkipLootTables() {
        return skipLootTables;
    }

    @Nullable
    public Boolean getEnableCompact() {
        return enableCompact;
    }

    @Nullable
    public JsonObject getNbtSettings() {
        return nbtSettings != null && nbtSettings.isJsonObject() ? nbtSettings.getAsJsonObject() : null;
    }
}
