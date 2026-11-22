package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.biome.layout.ChunkerBiomes;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;

import java.util.List;
import java.util.function.Function;

/**
 * Wraps biomes from an old (0-255 height) world so that chunk indexes below zero repeat the lowest biome section
 * instead of falling back to the top one. Used once sub-zero sections have been generated for a column.
 */
public class BottomClampedBiomes implements ChunkerBiomes {
    private final ChunkerBiomes delegate;

    public BottomClampedBiomes(ChunkerBiomes delegate) {
        this.delegate = delegate;
    }

    public ChunkerBiomes getDelegate() {
        return delegate;
    }

    @Override
    public ChunkerBiome[] asColumn(ChunkerBiome fallbackBiome) {
        return delegate.asColumn(fallbackBiome);
    }

    @Override
    public ChunkerBiome[] as4X4(ChunkerBiome fallbackBiome) {
        return delegate.as4X4(fallbackBiome);
    }

    @Override
    public List<Palette<ChunkerBiome>> asPalette() {
        return delegate.asPalette();
    }

    @Override
    public Palette<ChunkerBiome> asPalette(int chunkYIndex) {
        return delegate.asPalette(Math.max(0, chunkYIndex));
    }

    @Override
    public Palette<ChunkerBiome> as4X4Palette(int chunkYIndex) {
        return delegate.as4X4Palette(Math.max(0, chunkYIndex));
    }

    @Override
    public void remap(Function<ChunkerBiome, ChunkerBiome> mapping) {
        delegate.remap(mapping);
    }
}
