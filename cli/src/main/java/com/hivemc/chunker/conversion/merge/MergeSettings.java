package com.hivemc.chunker.conversion.merge;

/**
 * Settings for merging several worlds into one.
 */
public class MergeSettings {
    /**
     * How sources are arranged when no manual offsets are given.
     */
    public enum Layout {
        /** Pack the worlds into a roughly square grid. */
        GRID,
        /** Place the worlds in a single row along X. */
        ROW
    }

    /**
     * How the gaps between worlds are handled.
     */
    public enum SeamMode {
        /** Leave gaps empty, the game generates them with the output world's seed. */
        NONE,
        /** Chunker generates blended transition terrain in a band around every world. */
        SYNTHESIZE
    }

    private int gapChunks = 32;
    private int alignRegions = 1;
    private Layout layout = Layout.GRID;
    private SeamMode seamMode = SeamMode.SYNTHESIZE;
    private int seamWidthChunks = 16;
    private boolean generateBelowZero = true;
    private boolean javaBlendingHints = false;
    private boolean scaleNether = true;
    private int seaLevel = 63;
    private long seed = 0x5EA11E55L;

    /** Minimum gap between the bounding boxes of two worlds, in chunks (rounded up to whole regions). */
    public int getGapChunks() {
        return gapChunks;
    }

    public MergeSettings setGapChunks(int gapChunks) {
        if (gapChunks < 0) throw new IllegalArgumentException("Gap must not be negative");
        this.gapChunks = gapChunks;
        return this;
    }

    /** Placement alignment in regions, 8 keeps Nether portals linked exactly (Nether offsets are 1/8th). */
    public int getAlignRegions() {
        return alignRegions;
    }

    public MergeSettings setAlignRegions(int alignRegions) {
        if (alignRegions < 1) throw new IllegalArgumentException("Alignment must be at least 1 region");
        this.alignRegions = alignRegions;
        return this;
    }

    public Layout getLayout() {
        return layout;
    }

    public MergeSettings setLayout(Layout layout) {
        this.layout = layout;
        return this;
    }

    public SeamMode getSeamMode() {
        return seamMode;
    }

    public MergeSettings setSeamMode(SeamMode seamMode) {
        this.seamMode = seamMode;
        return this;
    }

    /** How far (in chunks) generated seam terrain reaches out from each world's edge. */
    public int getSeamWidthChunks() {
        return seamWidthChunks;
    }

    public MergeSettings setSeamWidthChunks(int seamWidthChunks) {
        if (seamWidthChunks < 1) throw new IllegalArgumentException("Seam width must be at least 1 chunk");
        this.seamWidthChunks = seamWidthChunks;
        return this;
    }

    /** Whether worlds from before 1.18 get terrain generated below Y=0. */
    public boolean isGenerateBelowZero() {
        return generateBelowZero;
    }

    public MergeSettings setGenerateBelowZero(boolean generateBelowZero) {
        this.generateBelowZero = generateBelowZero;
        return this;
    }

    /**
     * Whether Java 1.18+ output should mark written chunks with blending data, so terrain the game generates later
     * (outside the seams) blends into them instead of forming a wall.
     */
    public boolean isJavaBlendingHints() {
        return javaBlendingHints;
    }

    public MergeSettings setJavaBlendingHints(boolean javaBlendingHints) {
        this.javaBlendingHints = javaBlendingHints;
        return this;
    }

    /** Whether Nether offsets are scaled by 1/8th to keep portal links roughly in place. */
    public boolean isScaleNether() {
        return scaleNether;
    }

    public MergeSettings setScaleNether(boolean scaleNether) {
        this.scaleNether = scaleNether;
        return this;
    }

    /** The sea level used for seam water (water is placed up to seaLevel - 1). */
    public int getSeaLevel() {
        return seaLevel;
    }

    public MergeSettings setSeaLevel(int seaLevel) {
        this.seaLevel = seaLevel;
        return this;
    }

    /** Seed for generated terrain (caves, ores, seam noise). */
    public long getSeed() {
        return seed;
    }

    public MergeSettings setSeed(long seed) {
        this.seed = seed;
        return this;
    }
}
