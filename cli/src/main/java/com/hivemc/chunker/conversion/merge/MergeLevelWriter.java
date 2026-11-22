package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.writer.ColumnWriter;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.encoding.base.writer.WorldWriter;
import com.hivemc.chunker.conversion.handlers.pretransform.manager.PreTransformManager;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevelSettings;
import com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Wraps a real {@link LevelWriter} so that several conversions ("passes") can write into the same output.
 * <ul>
 *     <li>The first level written becomes the output level (settings, player, spawn), later levels only append
 *     level-wide data such as maps and portals.</li>
 *     <li>One column writer per dimension is kept for the whole merge.</li>
 *     <li>Flushing / freeing the real writer is deferred until the final pass, so files and databases stay open.</li>
 * </ul>
 */
public class MergeLevelWriter implements LevelWriter {
    private final LevelWriter delegate;
    private final Map<String, ColumnWriter> columnWriters = new LinkedHashMap<>();
    private final Map<String, ChunkerWorld> seenWorlds = new LinkedHashMap<>();
    @Nullable
    private WorldWriter worldWriter;
    private boolean finalPass;
    private boolean freed;

    /**
     * Create a merge writer.
     *
     * @param delegate the real writer for the output world.
     */
    public MergeLevelWriter(LevelWriter delegate) {
        this.delegate = delegate;
    }

    public LevelWriter getDelegate() {
        return delegate;
    }

    /**
     * Mark whether the next pass is the last one, which lets the flush / free calls through to the real writer.
     *
     * @param finalPass true if it's the final pass.
     */
    public void setFinalPass(boolean finalPass) {
        this.finalPass = finalPass;
    }

    public boolean isFinalPass() {
        return finalPass;
    }

    @Override
    public synchronized WorldWriter writeLevel(ChunkerLevel chunkerLevel) throws Exception {
        if (worldWriter == null) {
            worldWriter = delegate.writeLevel(chunkerLevel);
        } else {
            delegate.appendLevel(chunkerLevel);
        }
        return new MergeWorldWriter();
    }

    @Override
    public void flushLevel() throws Exception {
        if (finalPass) {
            delegate.flushLevel();
        }
    }

    @Override
    public void free() throws Exception {
        if (finalPass) {
            forceFree();
        }
    }

    /**
     * Free the real writer regardless of which pass this is (used when a merge fails part way through).
     *
     * @throws Exception if freeing failed.
     */
    public synchronized void forceFree() throws Exception {
        if (freed) return;
        freed = true;
        delegate.free();
    }

    @Override
    public void writeCustomLevelSetting(ChunkerLevelSettings chunkerLevelSettings, CompoundTag output, String targetName, Object value) {
        delegate.writeCustomLevelSetting(chunkerLevelSettings, output, targetName, value);
    }

    @Override
    public @Nullable PreTransformManager getPreTransformManager() {
        return delegate.getPreTransformManager();
    }

    @Override
    public EncodingType getEncodingType() {
        return delegate.getEncodingType();
    }

    @Override
    public Version getVersion() {
        return delegate.getVersion();
    }

    @Override
    public Set<ChunkerBiome.ChunkerVanillaBiome> getSupportedBiomes() {
        return delegate.getSupportedBiomes();
    }

    /**
     * World writer which re-uses one column writer per dimension and defers world flushing.
     */
    private class MergeWorldWriter implements WorldWriter {
        @Override
        public ColumnWriter writeWorld(ChunkerWorld chunkerWorld) throws Exception {
            synchronized (MergeLevelWriter.this) {
                String key = chunkerWorld.getDimension().getIdentifier();
                ChunkerWorld seen = seenWorlds.get(key);
                if (seen == null) {
                    seenWorlds.put(key, new ChunkerWorld(chunkerWorld.getDimension(), new java.util.HashSet<>(chunkerWorld.getRegions())));
                } else {
                    seen.getRegions().addAll(chunkerWorld.getRegions());
                }

                ColumnWriter writer = columnWriters.get(key);
                if (writer == null) {
                    writer = new MergeColumnWriter(worldWriter.writeWorld(chunkerWorld));
                    columnWriters.put(key, writer);
                }
                return writer;
            }
        }

        @Override
        public void flushWorld(ChunkerWorld chunkerWorld) {
            // Deferred until flushWorlds on the final pass
        }

        @Override
        public void flushWorlds() throws Exception {
            if (!finalPass) return;
            for (ChunkerWorld world : seenWorlds.values()) {
                worldWriter.flushWorld(world);
            }
            worldWriter.flushWorlds();
        }
    }

    /**
     * Column writer pass-through, kept so the same instance is used for every pass.
     */
    private static class MergeColumnWriter implements ColumnWriter {
        private final ColumnWriter delegate;

        MergeColumnWriter(ColumnWriter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void writeColumn(ChunkerColumn chunkerColumn) throws Exception {
            delegate.writeColumn(chunkerColumn);
        }

        @Override
        public void flushColumns() throws Exception {
            delegate.flushColumns();
        }

        @Override
        public void flushRegion(RegionCoordPair regionCoordPair) {
            delegate.flushRegion(regionCoordPair);
        }

        @Override
        public @Nullable PreTransformManager getPreTransformManager() {
            return delegate.getPreTransformManager();
        }
    }
}
