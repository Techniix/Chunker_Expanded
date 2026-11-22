package com.hivemc.chunker.conversion.merge;

import com.google.common.io.Resources;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.Converter;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.conversion.handlers.LevelConversionHandler;
import com.hivemc.chunker.conversion.handlers.WorldConversionHandler;
import com.hivemc.chunker.conversion.integration.WorldConversionIntegrationTests;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.entity.Entity;
import com.hivemc.chunker.conversion.intermediate.column.entity.MobEntity;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld;
import com.hivemc.chunker.scheduling.function.ThrowableSupplier;
import com.hivemc.chunker.scheduling.task.Environment;
import com.hivemc.chunker.scheduling.task.Task;
import com.hivemc.chunker.scheduling.task.TaskWeight;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mobs, armor stands, minecarts and boats are translated between Java and Bedrock (previously they were dropped
 * unless NBT was copied within the same edition).
 */
public class MobTranslationIntegrationTests {
    private static WorldConverter converter() {
        return new WorldConverter(UUID.randomUUID()) {
            @Override
            public void logMissingMapping(Converter.MissingMappingType type, String identifier) {
            }
        };
    }

    private static void convert(Path input, Path output, EncodingType type) {
        WorldConverter converter = converter();
        LevelReader reader = EncodingType.findReader(input.toFile(), converter).orElseThrow();
        Version version = type.getSupportedVersions().stream().max(Version::compareTo).orElseThrow();
        LevelWriter writer = type.createWriter(output.toFile(), version, converter).orElseThrow();
        converter.convert(reader, writer).future().join();
        assertFalse(converter.isExceptions());
    }

    /**
     * Read every entity of a world.
     */
    static List<Entity> readEntities(Path world) throws Exception {
        WorldConverter converter = converter();
        LevelReader reader = EncodingType.findReader(world.toFile(), converter).orElseThrow();
        ConcurrentLinkedQueue<Entity> entities = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Environment environment = Task.environment("Read", 4, failure::set, null);
        try {
            Task.asyncConsume("Read", TaskWeight.NORMAL, reader::readLevel, new LevelConversionHandler() {
                @Override
                public Task<WorldConversionHandler> convertLevel(ChunkerLevel level) {
                    WorldConversionHandler handler = new WorldConversionHandler() {
                        @Override
                        public Task<ColumnConversionHandler> convertWorld(ChunkerWorld world) {
                            ColumnConversionHandler columns = new ColumnConversionHandler() {
                                @Override
                                public void convertColumn(ChunkerColumn column) {
                                    entities.addAll(column.getEntities());
                                }

                                @Override
                                public void flushRegion(RegionCoordPair regionCoordPair) {
                                }

                                @Override
                                public void flushColumns() {
                                }
                            };
                            return Task.async("Columns", TaskWeight.NONE, (ThrowableSupplier<ColumnConversionHandler>) () -> columns);
                        }

                        @Override
                        public void flushWorld(ChunkerWorld world) {
                        }

                        @Override
                        public void flushWorlds() {
                        }
                    };
                    return Task.async("World", TaskWeight.NONE, () -> handler);
                }

                @Override
                public void flushLevel() {
                }
            });
        } finally {
            environment.close();
        }
        environment.future().get();
        reader.free();
        if (failure.get() != null) throw new AssertionError(failure.get());
        return new ArrayList<>(entities);
    }

    private static Map<String, Integer> countTypes(List<Entity> entities) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Entity entity : entities) counts.merge(entity.getEntityType().toString(), 1, Integer::sum);
        return counts;
    }

    private static Map<String, Integer> describe(List<Entity> entities) {
        // A summary of translated details which should survive a round trip
        Map<String, Integer> details = new TreeMap<>();
        for (Entity entity : entities) {
            if (!(entity instanceof MobEntity mob)) continue;
            String key = mob.getEntityType() + (mob.isBaby() ? " baby" : "")
                    + (mob.getColor() != null ? " color=" + mob.getColor() : "")
                    + (mob.getVariant() != null ? " variant=" + mob.getVariant() : "")
                    + (mob.getMarkVariant() != null ? " mark=" + mob.getMarkVariant() : "")
                    + (mob.getProfession() != null ? " profession=" + mob.getProfession() : "")
                    + (mob.isSaddled() ? " saddled" : "")
                    + (mob.getCustomName() != null ? " named" : "");
            details.merge(key, 1, Integer::sum);
        }
        return details;
    }

    @Test
    public void testMobsRoundTripJavaBedrockJava() throws Exception {
        Path input = WorldConversionIntegrationTests.unzip(Resources.getResource("integration/worlds/JAVA_1_20_5.zip"));
        Path bedrock = WorldConversionIntegrationTests.tempFolder();
        Path java = WorldConversionIntegrationTests.tempFolder();
        try {
            List<Entity> original = readEntities(input);
            convert(input, bedrock, EncodingType.BEDROCK);
            List<Entity> viaBedrock = readEntities(bedrock);
            convert(bedrock, java, EncodingType.JAVA);
            List<Entity> roundTrip = readEntities(java);

            Map<String, Integer> originalTypes = countTypes(original);
            assertTrue(originalTypes.keySet().stream().anyMatch(type -> type.equals("VILLAGER")), "Test world should contain villagers");

            // Every mob type that exists in the source survives both conversions with the same count
            Map<String, Integer> bedrockTypes = countTypes(viaBedrock);
            Map<String, Integer> roundTripTypes = countTypes(roundTrip);
            for (Map.Entry<String, Integer> entry : originalTypes.entrySet()) {
                assertEquals(entry.getValue(), bedrockTypes.get(entry.getKey()), "Bedrock count for " + entry.getKey());
                assertEquals(entry.getValue(), roundTripTypes.get(entry.getKey()), "Round trip count for " + entry.getKey());
            }

            // Translated details (colours, variants, professions, names...) survive too
            assertEquals(describe(original), describe(roundTrip));
        } finally {
            WorldConversionIntegrationTests.remove(input);
            WorldConversionIntegrationTests.remove(bedrock);
            WorldConversionIntegrationTests.remove(java);
        }
    }

    @Test
    public void testMobsFromBedrockToJava() throws Exception {
        Path input = WorldConversionIntegrationTests.unzip(Resources.getResource("integration/worlds/BEDROCK_R20_80.zip"));
        Path java = WorldConversionIntegrationTests.tempFolder();
        try {
            List<Entity> original = readEntities(input);
            convert(input, java, EncodingType.JAVA);
            List<Entity> converted = readEntities(java);
            assertEquals(countTypes(original), countTypes(converted));
            assertTrue(original.stream().anyMatch(entity -> entity instanceof MobEntity), "Test world should contain mobs");
        } finally {
            WorldConversionIntegrationTests.remove(input);
            WorldConversionIntegrationTests.remove(java);
        }
    }

    @Test
    public void testBoatWoodHelpers() {
        assertEquals("dark_oak", MobEntity.boatWood(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.DARK_OAK_CHEST_BOAT));
        assertTrue(MobEntity.isChestBoat(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.BAMBOO_CHEST_RAFT));
        assertEquals(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.SPRUCE_BOAT, MobEntity.boatType("spruce", false));
        assertEquals(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.OAK_CHEST_BOAT, MobEntity.fallbackType(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.CHERRY_CHEST_BOAT));
        assertNull(MobEntity.fallbackType(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.COW));
        assertEquals(Optional.empty(), Optional.ofNullable(MobEntity.boatWood(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.COW)));
    }

    private static Version version(EncodingType type, int major, int minor, int patch) {
        return type.getSupportedVersions().stream().filter(v -> v.isLessThanOrEqual(major, minor, patch))
                .max(Version::compareTo).orElseThrow();
    }

    private static void convert(Path input, Path output, EncodingType type, Version version) {
        WorldConverter converter = converter();
        LevelReader reader = EncodingType.findReader(input.toFile(), converter).orElseThrow();
        LevelWriter writer = type.createWriter(output.toFile(), version, converter).orElseThrow();
        converter.convert(reader, writer).future().join();
        assertFalse(converter.isExceptions());
    }

    private static com.hivemc.chunker.conversion.intermediate.column.chunk.itemstack.ChunkerItemStack item(
            com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.item.ChunkerVanillaItemType type, int amount) {
        var stack = new com.hivemc.chunker.conversion.intermediate.column.chunk.itemstack.ChunkerItemStack(type);
        stack.put(com.hivemc.chunker.conversion.intermediate.column.chunk.itemstack.ChunkerItemProperty.AMOUNT, amount);
        return stack;
    }

    /**
     * Write a small world containing the given entities (on a stone floor) in the given format.
     */
    private static void writeWorld(Path output, EncodingType type, Version version, List<Entity> entities) {
        WorldConverter converter = converter();
        LevelWriter writer = type.createWriter(output.toFile(), version, converter).orElseThrow();
        LevelReader reader = new LevelReader() {
            @Override
            public void readLevel(LevelConversionHandler handler) {
                var settings = new com.hivemc.chunker.conversion.intermediate.level.ChunkerLevelSettings();
                settings.CavesAndCliffs = true;
                ChunkerLevel level = new ChunkerLevel(settings, null, new ArrayList<>(), null, new ArrayList<>());
                java.util.Set<RegionCoordPair> regions = java.util.Set.of(new RegionCoordPair(0, 0));
                handler.convertLevel(level).thenConsume("World", TaskWeight.HIGHER, worldHandler -> {
                    ChunkerWorld world = new ChunkerWorld(com.hivemc.chunker.conversion.intermediate.world.Dimension.OVERWORLD, regions);
                    worldHandler.convertWorld(world).thenConsume("Columns", TaskWeight.HIGHER, columns -> {
                        ChunkerColumn column = new ChunkerColumn(new com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair(0, 0));
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) column.setBlock(x, 63, z, TerrainBlocks.STONE);
                        }
                        column.getEntities().addAll(entities);
                        columns.convertColumn(column);
                        columns.flushColumns();
                    }).then("Flush", TaskWeight.MEDIUM, () -> worldHandler.flushWorld(world)).then("Flush", TaskWeight.MEDIUM, worldHandler::flushWorlds);
                }).then("Flush", TaskWeight.MEDIUM, handler::flushLevel);
            }

            @Override
            public EncodingType getEncodingType() {
                return writer.getEncodingType();
            }

            @Override
            public Version getVersion() {
                return writer.getVersion();
            }

            @Override
            public java.util.Set<com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome.ChunkerVanillaBiome> getSupportedBiomes() {
                return writer.getSupportedBiomes();
            }
        };
        converter.convert(reader, writer).future().join();
        assertFalse(converter.isExceptions());
    }

    private static MobEntity mob(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType type, double x) {
        MobEntity mob = new MobEntity(type);
        mob.setPositionX(x);
        mob.setPositionY(64);
        mob.setPositionZ(8.5);
        return mob;
    }

    private static String inventoryDescription(List<Entity> entities) {
        StringBuilder builder = new StringBuilder();
        entities.stream().filter(entity -> entity instanceof MobEntity)
                .map(entity -> (MobEntity) entity)
                .sorted(java.util.Comparator.comparing(entity -> entity.getEntityType().toString()))
                .forEach(mob -> {
                    builder.append(mob.getEntityType()).append(mob.isSaddled() ? " saddled" : "").append(mob.isChested() ? " chested" : "")
                            .append(mob.isTamed() ? " tamed" : "")
                            .append(" body=").append(mob.getBodyArmor() == null ? null : mob.getBodyArmor().getIdentifier())
                            .append(" loot=").append(mob.getLootTable());
                    mob.getInventory().forEach((slot, stack) -> {
                        Object amount = stack.get(com.hivemc.chunker.conversion.intermediate.column.chunk.itemstack.ChunkerItemProperty.AMOUNT);
                        builder.append(" [").append(slot).append('=').append(stack.getIdentifier()).append('x').append(amount).append(']');
                    });
                    builder.append('\n');
                });
        return builder.toString();
    }

    @Test
    public void testInventoriesAndEquipmentRoundTrip() throws Exception {

        MobEntity donkey = mob(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.DONKEY, 2.5);
        donkey.setTamed(true);
        donkey.setSaddled(true);
        donkey.setChested(true);
        donkey.getInventory().put((byte) 0, item(com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.item.ChunkerVanillaItemType.DIAMOND, 5));
        donkey.getInventory().put((byte) 14, item(com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.item.ChunkerVanillaItemType.GOLD_INGOT, 64));

        MobEntity horse = mob(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.HORSE, 5.5);
        horse.setTamed(true);
        horse.setSaddled(true);
        horse.setBodyArmor(item(com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.item.ChunkerVanillaItemType.DIAMOND_HORSE_ARMOR, 1));

        MobEntity llama = mob(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.LLAMA, 8.5);
        llama.setTamed(true);
        llama.setChested(true);
        llama.setBodyArmor(new com.hivemc.chunker.conversion.intermediate.column.chunk.itemstack.ChunkerItemStack(
                new com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier(
                        com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType.RED_CARPET)));
        llama.getInventory().put((byte) 2, item(com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.item.ChunkerVanillaItemType.EMERALD, 3));

        MobEntity minecart = mob(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.CHEST_MINECART, 11.5);
        minecart.getInventory().put((byte) 26, item(com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.item.ChunkerVanillaItemType.IRON_INGOT, 7));

        MobEntity lootCart = mob(com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType.CHEST_MINECART, 13.5);
        lootCart.setLootTable("minecraft:chests/abandoned_mineshaft");

        Path original = WorldConversionIntegrationTests.tempFolder();
        Path bedrock = WorldConversionIntegrationTests.tempFolder();
        Path oldJava = WorldConversionIntegrationTests.tempFolder();
        Path java = WorldConversionIntegrationTests.tempFolder();
        try {
            writeWorld(original, EncodingType.JAVA, EncodingType.JAVA.getSupportedVersions().stream().max(Version::compareTo).orElseThrow(),
                    List.of(donkey, horse, llama, minecart, lootCart));
            String expected = inventoryDescription(readEntities(original));
            assertTrue(expected.contains("DIAMOND_HORSE_ARMOR") && expected.contains("GOLD_INGOT") && expected.contains("abandoned_mineshaft"), expected);

            convert(original, bedrock, EncodingType.BEDROCK, EncodingType.BEDROCK.getSupportedVersions().stream().max(Version::compareTo).orElseThrow());
            assertEquals(expected, inventoryDescription(readEntities(bedrock)), "Java -> Bedrock");

            convert(bedrock, oldJava, EncodingType.JAVA, version(EncodingType.JAVA, 1, 20, 4));
            assertEquals(expected, inventoryDescription(readEntities(oldJava)), "Bedrock -> Java 1.20.4");

            convert(oldJava, java, EncodingType.JAVA, EncodingType.JAVA.getSupportedVersions().stream().max(Version::compareTo).orElseThrow());
            assertEquals(expected, inventoryDescription(readEntities(java)), "Java 1.20.4 -> latest Java");
        } finally {
            for (Path path : List.of(original, bedrock, oldJava, java)) WorldConversionIntegrationTests.remove(path);
        }
    }

}
