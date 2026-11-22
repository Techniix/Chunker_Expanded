package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A world which is being merged, along with what was discovered about it and where it was placed.
 */
public class MergeSource {
    private final File directory;
    private final String name;
    @Nullable
    private final ChunkOffset manualOffset;

    // Discovered during the scan
    private final Map<String, RegionBounds> boundsByDimension = new LinkedHashMap<>();
    @Nullable
    private EncodingType encodingType;
    @Nullable
    private Version version;
    private boolean cavesAndCliffs = true;

    // Decided by the layout
    private ChunkOffset offset = ChunkOffset.ZERO;
    private int index;

    /**
     * Create a source.
     *
     * @param directory    the world directory.
     * @param manualOffset an explicit offset or null to let the layout decide.
     */
    public MergeSource(File directory, @Nullable ChunkOffset manualOffset) {
        this.directory = directory;
        this.name = directory.getName();
        this.manualOffset = manualOffset;
    }

    public File getDirectory() {
        return directory;
    }

    public String getName() {
        return name;
    }

    @Nullable
    public ChunkOffset getManualOffset() {
        return manualOffset;
    }

    public Map<String, RegionBounds> getBoundsByDimension() {
        return boundsByDimension;
    }

    @Nullable
    public RegionBounds getBounds(Dimension dimension) {
        return boundsByDimension.get(dimension.getIdentifier());
    }

    /**
     * The bounds used for placing the world, the overworld if present otherwise everything.
     */
    @Nullable
    public RegionBounds getLayoutBounds() {
        RegionBounds overworld = boundsByDimension.get(Dimension.OVERWORLD.getIdentifier());
        if (overworld != null) return overworld;
        RegionBounds all = null;
        for (RegionBounds bounds : boundsByDimension.values()) {
            all = RegionBounds.union(all, bounds);
        }
        return all;
    }

    @Nullable
    public EncodingType getEncodingType() {
        return encodingType;
    }

    public void setEncodingType(@Nullable EncodingType encodingType) {
        this.encodingType = encodingType;
    }

    @Nullable
    public Version getVersion() {
        return version;
    }

    public void setVersion(@Nullable Version version) {
        this.version = version;
    }

    /**
     * Whether the world was saved with the 1.18+ (-64 to 320) overworld height.
     */
    public boolean isCavesAndCliffs() {
        return cavesAndCliffs;
    }

    public void setCavesAndCliffs(boolean cavesAndCliffs) {
        this.cavesAndCliffs = cavesAndCliffs;
    }

    /**
     * The position of this world in the merge order (0 is the main world).
     */
    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public ChunkOffset getOffset() {
        return offset;
    }

    public void setOffset(ChunkOffset offset) {
        this.offset = offset;
    }

    /**
     * Get the offset for a specific dimension.
     *
     * @param dimension  the dimension.
     * @param scaleNether whether the nether should use 1/8th of the offset.
     * @return the offset.
     */
    public ChunkOffset getOffset(Dimension dimension, boolean scaleNether) {
        if (scaleNether && dimension.getIdentifier().equals(Dimension.NETHER.getIdentifier())) {
            return offset.toNether();
        }
        return offset;
    }

    @Override
    public String toString() {
        return name + " (" + encodingType + " " + version + ")";
    }
}
