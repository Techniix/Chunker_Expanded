package com.hivemc.chunker.conversion.encoding.base.writer;

import com.hivemc.chunker.conversion.encoding.base.LevelReaderWriter;
import com.hivemc.chunker.conversion.handlers.pretransform.manager.PreTransformManager;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevelSettings;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * A writer which can write the level data.
 */
public interface LevelWriter extends LevelReaderWriter {
    /**
     * Called when the level information should be written.
     *
     * @param chunkerLevel the level data.
     * @return the world writer to use.
     * @throws Exception if something went wrong.
     */
    WorldWriter writeLevel(ChunkerLevel chunkerLevel) throws Exception;

    /**
     * Called when the level has finished writing.
     *
     * @throws Exception if something went wrong.
     */
    default void flushLevel() throws Exception {
        // It isn't required to implement this method, it can be useful for flushing level writing
    }

    /**
     * Write a custom level setting which is annotated as custom.
     *
     * @param chunkerLevelSettings the settings object.
     * @param output               the output root NBT.
     * @param targetName           the target name of the setting.
     * @param value                the value to write.
     */
    default void writeCustomLevelSetting(ChunkerLevelSettings chunkerLevelSettings, CompoundTag output, String targetName, Object value) {
        throw new IllegalArgumentException("Custom setting writing is not implemented for this writer");
    }

    @Override
    default boolean isReader() {
        return false;
    }

    /**
     * Append the level-wide data (such as maps and portals) of an additional level into a level which has already
     * been written with {@link #writeLevel(ChunkerLevel)}. This is used when merging several worlds into one output,
     * the base level settings / player are kept from the first level and this method should only add data which can
     * co-exist (writers should re-number IDs where required so they don't collide).
     *
     * @param chunkerLevel the additional level.
     * @throws Exception if it failed to append the data.
     */
    default void appendLevel(ChunkerLevel chunkerLevel) throws Exception {
        // By default, nothing is appended
    }

    @Nullable
    default PreTransformManager getPreTransformManager() {
        return null;
    }
}
