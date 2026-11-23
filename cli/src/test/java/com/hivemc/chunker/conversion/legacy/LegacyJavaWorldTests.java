package com.hivemc.chunker.conversion.legacy;

import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.Converter;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.conversion.handlers.LevelConversionHandler;
import com.hivemc.chunker.conversion.handlers.WorldConversionHandler;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.BlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.container.randomizable.ChestBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.sign.SignBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.item.ChunkerVanillaItemType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.itemstack.ChunkerItemProperty;
import com.hivemc.chunker.conversion.intermediate.column.chunk.itemstack.ChunkerItemStack;
import com.hivemc.chunker.conversion.intermediate.column.entity.Entity;
import com.hivemc.chunker.conversion.intermediate.column.entity.MobEntity;
import com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.scheduling.function.ThrowableSupplier;
import com.hivemc.chunker.scheduling.task.Environment;
import com.hivemc.chunker.scheduling.task.Task;
import com.hivemc.chunker.scheduling.task.TaskWeight;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Worlds from before Java 1.8 (Alpha chunk files, McRegion and early Anvil) are read and converted with their
 * terrain, chests, signs and mobs.
 */
public class LegacyJavaWorldTests {
    /**
     * The old formats that are built for testing.
     */
    enum Format {
        ALPHA,      // Alpha 1.0 - Beta 1.2: one file per chunk
        MCREGION,   // Beta 1.3 - Release 1.1: .mcr regions, 128 high block arrays
        ANVIL_1_7   // Release 1.2 - 1.7.10: Anvil with numeric item IDs and plain text signs
    }

    private static final int GROUND = 64;

    @TempDir
    Path temp;

    @ParameterizedTest
    @EnumSource(Format.class)
    public void testOldWorldConvertsToModernJava(Format format) throws Exception {
        Path input = temp.resolve("input");
        buildWorld(input.toFile(), format);
        Path output = temp.resolve("output_java");
        convert(input, output, EncodingType.JAVA);
        verify(output, true);
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    public void testOldWorldConvertsToBedrock(Format format) throws Exception {
        Path input = temp.resolve("input");
        buildWorld(input.toFile(), format);
        Path output = temp.resolve("output_bedrock");
        convert(input, output, EncodingType.BEDROCK);
        verify(output, false);
    }

    @org.junit.jupiter.api.Test
    public void testOldWorldsMergeWithModernWorld() throws Exception {
        Path alpha = temp.resolve("alpha"), beta = temp.resolve("beta");
        buildWorld(alpha.toFile(), Format.ALPHA);
        buildWorld(beta.toFile(), Format.MCREGION);
        Path modern = com.hivemc.chunker.conversion.integration.WorldConversionIntegrationTests.unzip(
                com.google.common.io.Resources.getResource("integration/worlds/JAVA_1_20_5.zip"));
        Path output = temp.resolve("merged");
        try {
            List<com.hivemc.chunker.conversion.merge.MergeSource> sources = List.of(
                    new com.hivemc.chunker.conversion.merge.MergeSource(alpha.toFile(), null),
                    new com.hivemc.chunker.conversion.merge.MergeSource(beta.toFile(), null),
                    new com.hivemc.chunker.conversion.merge.MergeSource(modern.toFile(), null));
            WorldConverter converter = converter();
            Version version = EncodingType.BEDROCK.getSupportedVersions().stream().max(Version::compareTo).orElseThrow();
            com.hivemc.chunker.conversion.merge.WorldMerger merger = new com.hivemc.chunker.conversion.merge.WorldMerger(
                    sources, new com.hivemc.chunker.conversion.merge.MergeSettings(), converter,
                    (worldConverter) -> EncodingType.BEDROCK.createWriter(output.toFile(), version, worldConverter), true, (message) -> {
            });
            com.hivemc.chunker.conversion.merge.WorldMerger.MergeReport report = merger.run();
            assertFalse(converter.isExceptions(), "Exceptions were logged during the merge");
            assertEquals("Old World", sources.get(1).getDisplayName());
            assertTrue(report.getBelowZeroColumns() >= 32, "Both old worlds are extended below zero");
            assertTrue(report.getBlockEntities() >= 4, "Both old worlds keep their chests and signs");

            // Both old worlds' chests made it, at their new positions
            Map<Long, ChunkerColumn> columns = readColumns(output);
            int chests = 0;
            for (ChunkerColumn column : columns.values()) {
                for (BlockEntity blockEntity : column.getBlockEntities()) {
                    if (blockEntity instanceof ChestBlockEntity chest && chest.getItems().containsKey((byte) 0)) chests++;
                }
            }
            assertTrue(chests >= 2, "Expected the chests of both old worlds, found " + chests);
        } finally {
            com.hivemc.chunker.conversion.integration.WorldConversionIntegrationTests.remove(modern);
        }
    }

    private static void verify(Path world, boolean java) throws Exception {
        Map<Long, ChunkerColumn> columns = readColumns(world);
        assertEquals(16, columns.size(), "Every chunk should be converted");

        // Terrain keeps its blocks
        ChunkerColumn home = columns.get(key(0, 0));
        assertEquals(ChunkerVanillaBlockType.GRASS_BLOCK, home.getBlock(3, GROUND, 3).getType());
        assertEquals(ChunkerVanillaBlockType.STONE, home.getBlock(3, 30, 3).getType());
        assertEquals(ChunkerVanillaBlockType.BEDROCK, home.getBlock(3, 0, 3).getType());
        assertEquals(ChunkerVanillaBlockType.SAND, columns.get(key(-2, -2)).getBlock(4, GROUND, 4).getType());
        assertEquals(ChunkerVanillaBlockType.CACTUS, columns.get(key(-2, -2)).getBlock(4, GROUND + 1, 4).getType());

        // The chest keeps its contents
        ChestBlockEntity chest = null;
        SignBlockEntity sign = null;
        for (BlockEntity blockEntity : home.getBlockEntities()) {
            if (blockEntity instanceof ChestBlockEntity found) chest = found;
            if (blockEntity instanceof SignBlockEntity found) sign = found;
        }
        assertNotNull(chest, "Chest should be kept");
        ChunkerItemStack diamonds = chest.getItems().get((byte) 0);
        assertNotNull(diamonds);
        assertEquals(ChunkerVanillaItemType.DIAMOND, diamonds.getIdentifier());
        assertEquals(5, diamonds.get(ChunkerItemProperty.AMOUNT));
        ChunkerItemStack stone = chest.getItems().get((byte) 3);
        assertNotNull(stone);
        assertEquals(64, stone.get(ChunkerItemProperty.AMOUNT));

        // The sign keeps its (plain text) message
        assertNotNull(sign, "Sign should be kept");
        assertTrue(sign.getFront().getLines().get(0).toString().contains("Hello world"), "Sign text: " + sign.getFront().getLines());

        // Mobs and the chest minecart (a single "Minecart" with a type before 1.6) are kept
        List<Entity> entities = new ArrayList<>();
        for (ChunkerColumn column : columns.values()) entities.addAll(column.getEntities());
        assertTrue(entities.stream().anyMatch(entity -> entity.getEntityType() == ChunkerVanillaEntityType.PIG), "Pig: " + entities);
        MobEntity cart = (MobEntity) entities.stream()
                .filter(entity -> entity.getEntityType() == ChunkerVanillaEntityType.CHEST_MINECART)
                .findFirst().orElseThrow(() -> new AssertionError("Chest minecart missing: " + entities));
        ChunkerItemStack iron = cart.getInventory().get((byte) 0);
        assertNotNull(iron, "Chest minecart contents should be kept");
        assertEquals(ChunkerVanillaItemType.IRON_INGOT, iron.getIdentifier());

        // Biomes were estimated (Alpha / Beta) or kept (Anvil): spruce forest is taiga, sand and cactus desert
        assertEquals(ChunkerBiome.ChunkerVanillaBiome.TAIGA, surfaceBiome(home, java));
        assertEquals(ChunkerBiome.ChunkerVanillaBiome.DESERT, surfaceBiome(columns.get(key(-2, -2)), java));
    }

    private static ChunkerBiome surfaceBiome(ChunkerColumn column, boolean java) {
        // Modern worlds store 3D biomes from Y=-64, so the section index is offset by 4
        return column.getBiomes().as4X4Palette((GROUND >> 4) + 4).get(1, 0, 1, null);
    }

    // ---- World building ----

    /**
     * Build a 4x4 chunk world (chunks -2 to 1) in an old format: stone with grass on top, a desert chunk with cactus,
     * a spruce forest chunk at 0, 0 with a chest, a sign, a pig and a chest minecart.
     */
    static void buildWorld(File directory, Format format) throws IOException {
        directory.mkdirs();
        CompoundTag data = new CompoundTag();
        data.put("RandomSeed", 12345L);
        data.put("SpawnX", 0);
        data.put("SpawnY", GROUND + 1);
        data.put("SpawnZ", 0);
        data.put("Time", 1000L);
        data.put("LastPlayed", 0L);
        data.put("SizeOnDisk", 0L);
        if (format != Format.ALPHA) {
            data.put("LevelName", "Old World");
            data.put("version", format == Format.MCREGION ? 19132 : 19133);
        }
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        Tag.writeGZipJavaNBT(new File(directory, "level.dat"), root);

        Map<Long, CompoundTag> chunks = new HashMap<>();
        for (int chunkX = -2; chunkX < 2; chunkX++) {
            for (int chunkZ = -2; chunkZ < 2; chunkZ++) {
                CompoundTag chunk = format == Format.ANVIL_1_7 ? anvilChunk(chunkX, chunkZ) : oldChunk(chunkX, chunkZ);
                chunks.put(key(chunkX, chunkZ), chunk);
            }
        }

        if (format == Format.ALPHA) {
            for (Map.Entry<Long, CompoundTag> entry : chunks.entrySet()) {
                int chunkX = (int) (entry.getKey() >> 32), chunkZ = (int) (long) entry.getKey();
                File folder = new File(directory, Integer.toString(Math.floorMod(chunkX, 64), 36) + "/" + Integer.toString(Math.floorMod(chunkZ, 64), 36));
                folder.mkdirs();
                Tag.writeGZipJavaNBT(new File(folder, "c." + Integer.toString(chunkX, 36) + "." + Integer.toString(chunkZ, 36) + ".dat"), entry.getValue());
            }
        } else {
            File region = new File(directory, "region");
            region.mkdirs();
            String extension = format == Format.MCREGION ? ".mcr" : ".mca";
            // Chunks -2..1 span regions -1 and 0 on each axis
            for (int regionX = -1; regionX <= 0; regionX++) {
                for (int regionZ = -1; regionZ <= 0; regionZ++) {
                    writeRegion(new File(region, "r." + regionX + "." + regionZ + extension), chunks, regionX, regionZ);
                }
            }
        }
    }

    /**
     * Fill the old 16x128x16 arrays (Y fastest, then Z, then X).
     */
    private static CompoundTag oldChunk(int chunkX, int chunkZ) {
        byte[] blocks = new byte[32768];
        byte[] data = new byte[16384];
        byte[] skyLight = new byte[16384];
        byte[] blockLight = new byte[16384];
        Arrays.fill(skyLight, (byte) 0xFF);
        boolean desert = chunkX == -2 && chunkZ == -2;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y <= GROUND; y++) {
                    int id = y == 0 ? 7 : y < GROUND - 3 ? 1 : y < GROUND ? (desert ? 12 : 3) : (desert ? 12 : 2);
                    blocks[y + (z << 7) + (x << 11)] = (byte) id;
                }
            }
        }
        CompoundTag level = new CompoundTag();
        level.put("xPos", chunkX);
        level.put("zPos", chunkZ);
        level.put("LastUpdate", 1000L);
        level.put("TerrainPopulated", (byte) 1);
        level.put("HeightMap", new byte[256]);
        if (desert) blocks[(GROUND + 1) + (4 << 7) + (4 << 11)] = 81; // Cactus
        if (chunkX == 0 && chunkZ == 0) {
            // A spruce forest: 8 trees of spruce logs (data 1)
            for (int tree = 0; tree < 8; tree++) {
                int tx = 1 + (tree % 4) * 4, tz = tree < 4 ? 12 : 14;
                for (int y = GROUND + 1; y <= GROUND + 6; y++) {
                    int index = y + (tz << 7) + (tx << 11);
                    blocks[index] = 17;
                    setNibble(data, index, 1);
                }
            }
            blocks[(GROUND + 1) + (5 << 7) + (5 << 11)] = 54; // Chest
            blocks[(GROUND + 1) + (7 << 7) + (7 << 11)] = 63; // Sign post
        }
        level.put("Blocks", blocks);
        level.put("Data", data);
        level.put("SkyLight", skyLight);
        level.put("BlockLight", blockLight);
        addContents(level, chunkX, chunkZ);
        CompoundTag root = new CompoundTag();
        root.put("Level", level);
        return root;
    }

    /**
     * A 1.2 - 1.7 Anvil chunk (sections, numeric item IDs, plain text signs).
     */
    private static CompoundTag anvilChunk(int chunkX, int chunkZ) {
        CompoundTag old = oldChunk(chunkX, chunkZ);
        CompoundTag upgraded = com.hivemc.chunker.conversion.encoding.java.base.reader.legacy.LegacyChunkUpgrader.toAnvil(old);
        CompoundTag level = upgraded.getCompound("Level");
        // Anvil stored real biomes: forest chunk is taiga (5), desert is desert (2), others plains (1)
        byte[] biomes = new byte[256];
        Arrays.fill(biomes, (byte) (chunkX == 0 && chunkZ == 0 ? 5 : chunkX == -2 && chunkZ == -2 ? 2 : 1));
        level.put("Biomes", biomes);
        return upgraded;
    }

    private static void addContents(CompoundTag level, int chunkX, int chunkZ) {
        ListTag<CompoundTag, Map<String, Tag<?>>> tileEntities = new ListTag<>(TagType.COMPOUND);
        ListTag<CompoundTag, Map<String, Tag<?>>> entities = new ListTag<>(TagType.COMPOUND);
        if (chunkX == 0 && chunkZ == 0) {
            CompoundTag chest = new CompoundTag();
            chest.put("id", "Chest");
            chest.put("x", 5);
            chest.put("y", GROUND + 1);
            chest.put("z", 5);
            ListTag<CompoundTag, Map<String, Tag<?>>> items = new ListTag<>(TagType.COMPOUND);
            items.add(item(0, 264, 5));  // Diamonds
            items.add(item(3, 1, 64));   // Stone
            chest.put("Items", items);
            tileEntities.add(chest);

            CompoundTag sign = new CompoundTag();
            sign.put("id", "Sign");
            sign.put("x", 7);
            sign.put("y", GROUND + 1);
            sign.put("z", 7);
            sign.put("Text1", "Hello world");
            sign.put("Text2", "");
            sign.put("Text3", "");
            sign.put("Text4", "");
            tileEntities.add(sign);

            CompoundTag pig = mob("Pig", 8.5, 8.5);
            pig.put("Health", (short) 10);
            pig.put("Saddle", (byte) 0);
            entities.add(pig);

            CompoundTag cart = mob("Minecart", 10.5, 10.5);
            cart.put("Type", 1);
            ListTag<CompoundTag, Map<String, Tag<?>>> cartItems = new ListTag<>(TagType.COMPOUND);
            cartItems.add(item(0, 265, 3)); // Iron ingots
            cart.put("Items", cartItems);
            entities.add(cart);
        }
        level.put("TileEntities", tileEntities);
        level.put("Entities", entities);
    }

    private static CompoundTag item(int slot, int id, int count) {
        CompoundTag item = new CompoundTag();
        item.put("Slot", (byte) slot);
        item.put("id", (short) id);
        item.put("Damage", (short) 0);
        item.put("Count", (byte) count);
        return item;
    }

    private static CompoundTag mob(String id, double x, double z) {
        CompoundTag mob = new CompoundTag();
        mob.put("id", id);
        mob.put("Pos", ListTag.fromValues(TagType.DOUBLE, List.of(x, (double) GROUND + 1, z)));
        mob.put("Motion", ListTag.fromValues(TagType.DOUBLE, List.of(0.0, 0.0, 0.0)));
        mob.put("Rotation", ListTag.fromValues(TagType.FLOAT, List.of(0f, 0f)));
        mob.put("FallDistance", 0f);
        mob.put("Fire", (short) -20);
        mob.put("Air", (short) 300);
        mob.put("OnGround", (byte) 1);
        return mob;
    }

    private static void setNibble(byte[] array, int index, int value) {
        int i = index >> 1;
        if ((index & 1) == 0) array[i] = (byte) ((array[i] & 0xF0) | value);
        else array[i] = (byte) ((array[i] & 0x0F) | (value << 4));
    }

    /**
     * Write a region file (the container is the same for McRegion and Anvil): an offset table, a timestamp table
     * then zlib compressed chunks padded to 4096 byte sectors.
     */
    private static void writeRegion(File file, Map<Long, CompoundTag> chunks, int regionX, int regionZ) throws IOException {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(8192);
            int sector = 2;
            for (Map.Entry<Long, CompoundTag> entry : chunks.entrySet()) {
                int chunkX = (int) (entry.getKey() >> 32), chunkZ = (int) (long) entry.getKey();
                if ((chunkX >> 5) != regionX || (chunkZ >> 5) != regionZ) continue;
                byte[] compressed = zlib(Tag.writeUncompressedJavaNBT(entry.getValue()));
                int sectors = (compressed.length + 5 + 4095) / 4096;
                out.seek(4L * ((chunkX & 31) + (chunkZ & 31) * 32));
                out.writeInt((sector << 8) | sectors);
                out.seek(sector * 4096L);
                out.writeInt(compressed.length + 1);
                out.writeByte(2);
                out.write(compressed);
                sector += sectors;
                out.setLength(sector * 4096L);
            }
        }
    }

    private static byte[] zlib(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return output.toByteArray();
    }

    // ---- Conversion helpers ----

    private static WorldConverter converter() {
        return new WorldConverter(UUID.randomUUID()) {
            @Override
            public void logMissingMapping(Converter.MissingMappingType type, String identifier) {
            }
        };
    }

    private static void convert(Path input, Path output, EncodingType type) {
        WorldConverter converter = converter();
        LevelReader reader = EncodingType.findReader(input.toFile(), converter).orElseThrow(() -> new AssertionError("Old world wasn't recognised"));
        Version version = type.getSupportedVersions().stream().max(Version::compareTo).orElseThrow();
        LevelWriter writer = type.createWriter(output.toFile(), version, converter).orElseThrow();
        converter.convert(reader, writer).future().join();
        assertFalse(converter.isExceptions(), "Conversion should not log exceptions");
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Read every overworld column of a world.
     */
    static Map<Long, ChunkerColumn> readColumns(Path world) throws Exception {
        WorldConverter converter = converter();
        LevelReader reader = EncodingType.findReader(world.toFile(), converter).orElseThrow();
        Map<Long, ChunkerColumn> columns = new ConcurrentHashMap<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Environment environment = Task.environment("Read", 4, failure::set, null);
        try {
            Task.asyncConsume("Read", TaskWeight.NORMAL, reader::readLevel, new LevelConversionHandler() {
                @Override
                public Task<WorldConversionHandler> convertLevel(ChunkerLevel level) {
                    WorldConversionHandler handler = new WorldConversionHandler() {
                        @Override
                        public Task<ColumnConversionHandler> convertWorld(ChunkerWorld world) {
                            boolean overworld = world.getDimension().getIdentifier().equals(Dimension.OVERWORLD.getIdentifier());
                            ColumnConversionHandler columnHandler = new ColumnConversionHandler() {
                                @Override
                                public void convertColumn(ChunkerColumn column) {
                                    if (overworld) columns.put(key(column.getPosition().chunkX(), column.getPosition().chunkZ()), column);
                                }

                                @Override
                                public void flushRegion(RegionCoordPair regionCoordPair) {
                                }

                                @Override
                                public void flushColumns() {
                                }
                            };
                            return Task.async("Columns", TaskWeight.NONE, (ThrowableSupplier<ColumnConversionHandler>) () -> columnHandler);
                        }

                        @Override
                        public void flushWorld(ChunkerWorld world) {
                        }

                        @Override
                        public void flushWorlds() {
                        }
                    };
                    return Task.async("World", TaskWeight.NONE, (ThrowableSupplier<WorldConversionHandler>) () -> handler);
                }

                @Override
                public void flushLevel() {
                }
            });
        } finally {
            environment.close();
        }
        environment.future().get();
        if (failure.get() != null) throw new AssertionError("Reading failed", failure.get());
        return columns;
    }
}
