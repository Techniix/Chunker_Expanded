package com.hivemc.chunker.conversion.encoding.java.base.reader.legacy;

import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and reads the chunk files of worlds from before Beta 1.3 (Alpha and Beta 1.0 - 1.2). Each chunk is a
 * gzipped NBT file at {@code <world>/<base36(x mod 64)>/<base36(z mod 64)>/c.<base36(x)>.<base36(z)>.dat}.
 */
public final class AlphaChunkFiles {
    private static final Pattern FOLDER = Pattern.compile("[0-9a-z]{1,2}");
    private static final Pattern CHUNK_FILE = Pattern.compile("c\\.(-?[0-9a-z]+)\\.(-?[0-9a-z]+)\\.dat");

    private AlphaChunkFiles() {
    }

    /**
     * A chunk file and the chunk co-ordinates from its name.
     *
     * @param file   the file.
     * @param chunkX the chunk X.
     * @param chunkZ the chunk Z.
     */
    public record ChunkFile(File file, int chunkX, int chunkZ) {
    }

    /**
     * Whether a dimension folder uses the Alpha chunk file layout.
     *
     * @param dimensionFolder the folder (the world folder for the overworld, DIM-1 for the Nether).
     * @return true if any chunk files were found.
     */
    public static boolean isAlphaDimension(File dimensionFolder) {
        File[] first = dimensionFolder.listFiles(file -> file.isDirectory() && FOLDER.matcher(file.getName()).matches());
        if (first == null) return false;
        for (File a : first) {
            File[] second = a.listFiles(file -> file.isDirectory() && FOLDER.matcher(file.getName()).matches());
            if (second == null) continue;
            for (File b : second) {
                String[] files = b.list((parent, name) -> CHUNK_FILE.matcher(name).matches());
                if (files != null && files.length > 0) return true;
            }
        }
        return false;
    }

    /**
     * Find every chunk file in a dimension folder, grouped by region (32x32 chunks).
     *
     * @param dimensionFolder the folder.
     * @return the chunk files for each region.
     */
    public static Map<RegionCoordPair, List<ChunkFile>> findChunks(File dimensionFolder) {
        Map<RegionCoordPair, List<ChunkFile>> result = new HashMap<>();
        File[] first = dimensionFolder.listFiles(file -> file.isDirectory() && FOLDER.matcher(file.getName()).matches());
        if (first == null) return result;
        for (File a : first) {
            File[] second = a.listFiles(file -> file.isDirectory() && FOLDER.matcher(file.getName()).matches());
            if (second == null) continue;
            for (File b : second) {
                File[] files = b.listFiles((parent, name) -> CHUNK_FILE.matcher(name).matches());
                if (files == null) continue;
                for (File file : files) {
                    Matcher matcher = CHUNK_FILE.matcher(file.getName());
                    if (!matcher.matches() || file.length() == 0) continue;
                    try {
                        int chunkX = Integer.parseInt(matcher.group(1), 36);
                        int chunkZ = Integer.parseInt(matcher.group(2), 36);
                        result.computeIfAbsent(new RegionCoordPair(chunkX >> 5, chunkZ >> 5), (ignored) -> new ArrayList<>())
                                .add(new ChunkFile(file, chunkX, chunkZ));
                    } catch (NumberFormatException ignored) {
                        // Not a chunk file
                    }
                }
            }
        }
        return result;
    }

    /**
     * Read a chunk file.
     *
     * @param file the chunk file.
     * @return the chunk's root compound (containing "Level"), or null if it's empty.
     * @throws IOException if it couldn't be read.
     */
    @Nullable
    public static CompoundTag read(File file) throws IOException {
        return Tag.readGZipJavaNBT(file);
    }
}
