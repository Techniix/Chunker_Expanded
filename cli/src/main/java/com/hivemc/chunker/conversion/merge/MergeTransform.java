package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.conversion.handlers.LevelConversionHandler;
import com.hivemc.chunker.conversion.handlers.WorldConversionHandler;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.BlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.end.EndGatewayBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.entity.Entity;
import com.hivemc.chunker.conversion.intermediate.column.entity.HangingEntity;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerPortal;
import com.hivemc.chunker.conversion.intermediate.level.map.ChunkerMap;
import com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.scheduling.task.Task;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The pipeline stages which place one source world into the merged world. They run first, straight after the
 * reader, so every later stage (block connections, the writer) sees the final co-ordinates.
 * <ul>
 *     <li>Moves columns, entities, block entities, maps and portals by the source's offset.</li>
 *     <li>Generates terrain below Y=0 for pre-1.18 worlds when the output supports it.</li>
 *     <li>Records surface profiles near the world's edge for seam generation.</li>
 *     <li>Marks columns with blending hints for the game.</li>
 * </ul>
 */
public class MergeTransform {
    private final MergeSource source;
    private final MergeSettings settings;
    private final boolean netherScaled;
    private final boolean outputSupportsBelowZero;
    @Nullable
    private final BelowZeroGenerator belowZeroGenerator;
    @Nullable
    private final SurfaceProfiles profiles;
    private final AtomicLong columns = new AtomicLong();
    private final AtomicLong belowZeroColumns = new AtomicLong();
    private final AtomicLong entities = new AtomicLong();
    private final AtomicLong blockEntities = new AtomicLong();

    /**
     * Create the transform for one source.
     *
     * @param source                  the source being converted.
     * @param settings                the merge settings.
     * @param netherScaled            whether nether offsets are scaled 1/8th.
     * @param outputSupportsBelowZero whether the output format has the -64 overworld floor.
     * @param belowZeroGenerator      the generator for sub-zero terrain or null if disabled.
     * @param profiles                the profile store or null if seams are disabled.
     */
    public MergeTransform(MergeSource source, MergeSettings settings, boolean netherScaled, boolean outputSupportsBelowZero,
                          @Nullable BelowZeroGenerator belowZeroGenerator, @Nullable SurfaceProfiles profiles) {
        this.source = source;
        this.settings = settings;
        this.netherScaled = netherScaled;
        this.outputSupportsBelowZero = outputSupportsBelowZero;
        this.belowZeroGenerator = belowZeroGenerator;
        this.profiles = profiles;
    }

    private ChunkOffset offsetFor(Dimension dimension) {
        return source.getOffset(dimension, netherScaled);
    }

    public long getColumnCount() {
        return columns.get();
    }

    public long getBelowZeroColumnCount() {
        return belowZeroColumns.get();
    }

    public long getEntityCount() {
        return entities.get();
    }

    public long getBlockEntityCount() {
        return blockEntities.get();
    }

    /**
     * Create the level stage.
     */
    public LevelConversionHandler levelHandler(LevelConversionHandler delegate) {
        return new LevelConversionHandler() {
            @Override
            public Task<WorldConversionHandler> convertLevel(ChunkerLevel level) {
                // Remember the height format of the source
                source.setCavesAndCliffs(level.getSettings().CavesAndCliffs);

                // Move maps and portals
                for (ChunkerMap map : level.getMaps()) {
                    ChunkOffset offset = offsetFor(map.getDimension());
                    map.setXCenter(map.getXCenter() + offset.blockX());
                    map.setZCenter(map.getZCenter() + offset.blockZ());
                }
                for (ChunkerPortal portal : level.getPortals()) {
                    ChunkOffset offset = offsetFor(portal.getDimension());
                    portal.setX(portal.getX() + offset.blockX());
                    portal.setZ(portal.getZ() + offset.blockZ());
                }
                return delegate.convertLevel(level);
            }

            @Override
            public void flushLevel() {
                delegate.flushLevel();
            }
        };
    }

    /**
     * Create the world stage.
     */
    public WorldConversionHandler worldHandler(WorldConversionHandler delegate, ChunkerLevel level) {
        return new WorldConversionHandler() {
            @Override
            public Task<com.hivemc.chunker.conversion.handlers.ColumnConversionHandler> convertWorld(ChunkerWorld world) {
                // Move the region list so pre-transform / writers know the final positions
                ChunkOffset offset = offsetFor(world.getDimension());
                if (!offset.isZero()) {
                    Set<RegionCoordPair> moved = new HashSet<>(world.getRegions().size());
                    for (RegionCoordPair region : world.getRegions()) {
                        moved.add(offset.apply(region));
                    }
                    world.setRegions(moved);
                }
                return delegate.convertWorld(world);
            }

            @Override
            public void flushWorld(ChunkerWorld world) {
                delegate.flushWorld(world);
            }

            @Override
            public void flushWorlds() {
                delegate.flushWorlds();
            }
        };
    }

    /**
     * Create the column stage.
     */
    public ColumnConversionHandler columnHandler(ColumnConversionHandler delegate, ChunkerWorld world) {
        Dimension dimension = world.getDimension();
        ChunkOffset offset = offsetFor(dimension);
        boolean overworld = dimension.getIdentifier().equals(Dimension.OVERWORLD.getIdentifier());
        RegionBounds placedBounds = source.getBounds(dimension) == null ? null : source.getBounds(dimension).offset(offset);
        int profileDepth = 32 + settings.getSeamWidthChunks();

        return new ColumnConversionHandler() {
            @Override
            public void convertColumn(ChunkerColumn column) {
                move(column, offset);
                columns.incrementAndGet();
                entities.addAndGet(column.getEntities().size());
                blockEntities.addAndGet(column.getBlockEntities().size());

                if (overworld) {
                    // Old worlds get the -64 to -1 layer
                    if (belowZeroGenerator != null && outputSupportsBelowZero && !source.isCavesAndCliffs()
                            && belowZeroGenerator.fill(column)) {
                        belowZeroColumns.incrementAndGet();
                    }

                    if (profiles != null) {
                        int x = column.getPosition().chunkX(), z = column.getPosition().chunkZ();
                        profiles.markOccupied(x, z);
                        if (placedBounds != null && placedBounds.chunksInside(x, z) < profileDepth) {
                            int biomeOffset = source.isCavesAndCliffs() ? 4 : 0;
                            profiles.record(column, biomeOffset, dimension.getFallbackBiome());
                        }
                    }

                    column.setBlendingHint(settings.isJavaBlendingHints());
                }

                delegate.convertColumn(column);
            }

            @Override
            public void flushRegion(RegionCoordPair regionCoordPair) {
                delegate.flushRegion(offset.apply(regionCoordPair));
            }

            @Override
            public void flushColumns() {
                delegate.flushColumns();
            }
        };
    }

    /**
     * Move a column and everything with an absolute position inside it.
     *
     * @param column the column.
     * @param offset the offset to apply.
     */
    public static void move(ChunkerColumn column, ChunkOffset offset) {
        if (offset.isZero()) return;
        int dx = offset.blockX(), dz = offset.blockZ();
        column.setPosition(offset.apply(column.getPosition()));

        for (BlockEntity blockEntity : column.getBlockEntities()) {
            blockEntity.setX(blockEntity.getX() + dx);
            blockEntity.setZ(blockEntity.getZ() + dz);
            if (blockEntity instanceof EndGatewayBlockEntity gateway) {
                gateway.setExitX(gateway.getExitX() + dx);
                gateway.setExitZ(gateway.getExitZ() + dz);
            }
            NbtPositionShifter.shift(blockEntity.getOriginalNBT(), dx, dz);
        }

        for (Entity entity : column.getEntities()) {
            entity.setPositionX(entity.getPositionX() + dx);
            entity.setPositionZ(entity.getPositionZ() + dz);
            if (entity instanceof HangingEntity hanging) {
                hanging.setTileX(hanging.getTileX() + dx);
                hanging.setTileZ(hanging.getTileZ() + dz);
            }
            NbtPositionShifter.shift(entity.getOriginalNBT(), dx, dz);
        }
    }
}
