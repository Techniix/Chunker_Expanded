package com.hivemc.chunker.conversion.encoding.java.base.reader.legacy;

import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.util.JsonTextUtil;

import java.util.Map;

/**
 * Upgrades chunk NBT from before the Anvil format (Alpha and Beta 1.3 - Release 1.1, which store a chunk as one
 * 16x128x16 block array) into the Anvil layout (16x16x16 sections) read by the Java column reader, and fixes data that
 * changed before 1.8 (minecart types, hanging entity directions and plain text signs).
 * <p>
 * The block IDs and data values are the same numeric IDs used up to 1.12, so the normal legacy block mappings apply.
 */
public final class LegacyChunkUpgrader {
    // Numeric block IDs used for estimating biomes
    private static final int AIR = 0, GRASS = 2, DIRT = 3, SAND = 12, GRAVEL = 13, LOG = 17, LEAVES = 18, FLOWING_WATER = 8,
            WATER = 9, SNOW_LAYER = 78, ICE = 79, SNOW = 80, CACTUS = 81, TALL_GRASS = 31, DEAD_BUSH = 32,
            YELLOW_FLOWER = 37, RED_FLOWER = 38, BROWN_MUSHROOM = 39, RED_MUSHROOM = 40, REEDS = 83, SANDSTONE = 24;

    // Legacy (pre-1.13) numeric biome IDs
    static final byte OCEAN = 0, PLAINS = 1, DESERT = 2, EXTREME_HILLS = 3, FOREST = 4, TAIGA = 5, FROZEN_OCEAN = 10,
            ICE_PLAINS = 12, BEACH = 16, BIRCH_FOREST = 27, COLD_TAIGA = 30;

    private static final int OLD_HEIGHT = 128;
    private static final int SEA_LEVEL = 63; // Water surface in Alpha / Beta is y = 63

    private LegacyChunkUpgrader() {
    }

    /**
     * Whether a chunk is stored in the pre-Anvil layout (one block array for the whole chunk).
     *
     * @param root the chunk's root compound.
     * @return true if it needs upgrading with {@link #toAnvil(CompoundTag)}.
     */
    public static boolean isPreAnvil(CompoundTag root) {
        CompoundTag level = root.getCompound("Level", root);
        byte[] blocks = level.getByteArray("Blocks", null);
        return blocks != null && blocks.length == 16 * 16 * OLD_HEIGHT && !level.contains("Sections");
    }

    /**
     * Upgrade a pre-Anvil chunk to the Anvil layout.
     *
     * @param root the chunk's root compound (with or without the "Level" wrapper).
     * @return a new root compound with a "Level" compound in the Anvil layout.
     */
    public static CompoundTag toAnvil(CompoundTag root) {
        CompoundTag old = root.getCompound("Level", root);
        byte[] blocks = old.getByteArray("Blocks", null);
        byte[] data = old.getByteArray("Data", null);
        byte[] skyLight = old.getByteArray("SkyLight", null);
        byte[] blockLight = old.getByteArray("BlockLight", null);

        CompoundTag level = new CompoundTag();
        level.put("xPos", old.getInt("xPos"));
        level.put("zPos", old.getInt("zPos"));
        level.put("LastUpdate", old.getLong("LastUpdate", 0L));
        level.put("TerrainPopulated", old.getByte("TerrainPopulated", (byte) 1));
        level.put("LightPopulated", (byte) (skyLight != null ? 1 : 0));
        level.put("V", (byte) 1);
        level.put("InhabitedTime", 0L);

        // Sections, skipping empty ones like Anvil does
        ListTag<CompoundTag, Map<String, Tag<?>>> sections = new ListTag<>(TagType.COMPOUND);
        for (int sectionY = 0; sectionY < OLD_HEIGHT / 16; sectionY++) {
            byte[] sectionBlocks = new byte[4096];
            byte[] sectionData = new byte[2048];
            byte[] sectionSky = new byte[2048];
            byte[] sectionBlockLight = new byte[2048];
            boolean empty = true;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        int oldIndex = oldIndex(x, (sectionY << 4) + y, z);
                        int newIndex = (y << 8) | (z << 4) | x;
                        sectionBlocks[newIndex] = blocks[oldIndex];
                        if (blocks[oldIndex] != 0) empty = false;
                        setNibble(sectionData, newIndex, getNibble(data, oldIndex));
                        setNibble(sectionSky, newIndex, skyLight == null ? 15 : getNibble(skyLight, oldIndex));
                        setNibble(sectionBlockLight, newIndex, getNibble(blockLight, oldIndex));
                    }
                }
            }
            if (empty) continue;

            CompoundTag section = new CompoundTag();
            section.put("Y", (byte) sectionY);
            section.put("Blocks", sectionBlocks);
            section.put("Data", sectionData);
            section.put("SkyLight", sectionSky);
            section.put("BlockLight", sectionBlockLight);
            sections.add(section);
        }
        level.put("Sections", sections);

        // Height map (first block above the highest non-air block), Anvil uses ints indexed by z * 16 + x
        int[] heightMap = new int[256];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int y = OLD_HEIGHT - 1;
                while (y > 0 && blocks[oldIndex(x, y, z)] == AIR) y--;
                heightMap[(z << 4) | x] = y + 1;
            }
        }
        level.put("HeightMap", heightMap);

        // Biomes weren't saved before Release 1.2 (they were worked out from the seed), so estimate them from terrain
        level.put("Biomes", estimateBiomes(blocks, data));

        // Entities, block entities and scheduled ticks keep their format
        copyList(old, level, "Entities");
        copyList(old, level, "TileEntities");
        copyList(old, level, "TileTicks");

        CompoundTag result = new CompoundTag();
        result.put("Level", level);
        return result;
    }

    /**
     * Fix data in a chunk that changed before Java 1.8 (safe to apply to any chunk read with the 1.8 reader).
     *
     * @param root the chunk's root compound.
     */
    public static void fixPre18(CompoundTag root) {
        CompoundTag level = root.getCompound("Level", root);
        ListTag<CompoundTag, Map<String, Tag<?>>> entities = level.getList("Entities", CompoundTag.class, null);
        if (entities != null) {
            for (CompoundTag entity : entities) fixEntity(entity);
        }
        ListTag<CompoundTag, Map<String, Tag<?>>> blockEntities = level.getList("TileEntities", CompoundTag.class, null);
        if (blockEntities != null) {
            for (CompoundTag blockEntity : blockEntities) fixBlockEntity(blockEntity);
        }
    }

    private static void fixEntity(CompoundTag entity) {
        String id = entity.getString("id", "");

        // Before 1.6 every minecart was "Minecart" with a type number
        if (id.equals("Minecart") && entity.contains("Type")) {
            int type = entity.getInt("Type", 0);
            entity.put("id", type == 1 ? "MinecartChest" : type == 2 ? "MinecartFurnace" : "MinecartRideable");
            entity.remove("Type");
        }

        // Paintings / item frames: "Dir" (Beta) then "Direction" (1.5 - 1.7) became "Facing"
        if (!entity.contains("Facing")) {
            if (entity.contains("Direction")) {
                entity.put("Facing", (byte) entity.getByte("Direction", (byte) 0));
            } else if (entity.contains("Dir")) {
                int dir = entity.getByte("Dir", (byte) 0);
                int[] toDirection = {2, 1, 0, 3}; // The conversion vanilla applied when "Direction" was introduced
                entity.put("Facing", (byte) toDirection[dir & 3]);
            }
        }

        // Riders are stored inside the entity they ride
        CompoundTag riding = entity.getCompound("Riding");
        if (riding != null) fixEntity(riding);
    }

    private static void fixBlockEntity(CompoundTag blockEntity) {
        // Signs stored plain text before 1.8 (JSON text after)
        if ("Sign".equals(blockEntity.getString("id", ""))) {
            for (int i = 1; i <= 4; i++) {
                String text = blockEntity.getString("Text" + i, null);
                if (text != null && !looksLikeJson(text)) {
                    blockEntity.put("Text" + i, JsonTextUtil.toJSON(JsonTextUtil.fromText(text)));
                }
            }
        }
    }

    /**
     * Whether sign text is already JSON text (1.8+) rather than plain text.
     */
    static boolean looksLikeJson(String text) {
        String trimmed = text.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[") || (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2);
    }

    /**
     * Estimate the biomes of a pre-1.2 chunk from its terrain: snow means a cold biome, sand and cactus a desert,
     * lots of trees a forest (spruce: taiga, birch: birch forest), high ground hills and deep water ocean.
     *
     * @param blocks the old 16x128x16 block array.
     * @param data   the old data nibble array.
     * @return a biome byte array indexed by z * 16 + x.
     */
    static byte[] estimateBiomes(byte[] blocks, byte[] data) {
        int snowTops = 0, sandTops = 0, cactus = 0, logs = 0, spruceLogs = 0, birchLogs = 0, landColumns = 0;
        long groundTotal = 0;
        int[] ground = new int[256];
        int[] groundBlock = new int[256];
        int[] waterTop = new int[256];

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int column = (z << 4) | x;
                waterTop[column] = -1;
                boolean snowed = false;
                int y = OLD_HEIGHT - 1;
                // Walk down past air, plants and trees to the ground, noting what was on top
                for (; y > 0; y--) {
                    int id = blocks[oldIndex(x, y, z)] & 0xFF;
                    if (id == AIR || id == TALL_GRASS || id == DEAD_BUSH || id == YELLOW_FLOWER || id == RED_FLOWER
                            || id == BROWN_MUSHROOM || id == RED_MUSHROOM || id == REEDS) continue;
                    if (id == SNOW_LAYER || id == ICE) {
                        snowed = true;
                        if (id == ICE && waterTop[column] < 0) waterTop[column] = y;
                        continue;
                    }
                    if (id == LOG) {
                        logs++;
                        int type = getNibble(data, oldIndex(x, y, z)) & 3;
                        if (type == 1) spruceLogs++;
                        else if (type == 2) birchLogs++;
                        continue;
                    }
                    if (id == LEAVES) continue;
                    if (id == CACTUS) {
                        cactus++;
                        continue;
                    }
                    if (id == WATER || id == FLOWING_WATER) {
                        if (waterTop[column] < 0) waterTop[column] = y;
                        continue;
                    }
                    break;
                }
                ground[column] = y;
                groundBlock[column] = blocks[oldIndex(x, y, z)] & 0xFF;
                if (snowed) snowTops++;
                if (waterTop[column] < 0) {
                    landColumns++;
                    groundTotal += y;
                    if (groundBlock[column] == SAND || groundBlock[column] == SANDSTONE) sandTops++;
                }
            }
        }

        // The chunk's overall biome
        byte chunkBiome;
        double averageGround = landColumns == 0 ? SEA_LEVEL : (double) groundTotal / landColumns;
        if (snowTops >= 64) {
            chunkBiome = logs >= 12 ? COLD_TAIGA : ICE_PLAINS;
        } else if (cactus > 0 || (landColumns > 0 && sandTops >= landColumns * 0.6 && landColumns >= 64)) {
            chunkBiome = DESERT;
        } else if (logs >= 30) {
            chunkBiome = spruceLogs > logs / 2 ? TAIGA : birchLogs > logs / 2 ? BIRCH_FOREST : FOREST;
        } else if (averageGround >= 90) {
            chunkBiome = EXTREME_HILLS;
        } else {
            chunkBiome = PLAINS;
        }

        byte[] biomes = new byte[256];
        for (int column = 0; column < 256; column++) {
            byte biome = chunkBiome;
            if (waterTop[column] >= SEA_LEVEL - 1 && ground[column] <= SEA_LEVEL - 8) {
                // Deep water at sea level is ocean
                biome = snowTops >= 64 ? FROZEN_OCEAN : OCEAN;
            } else if (chunkBiome != DESERT && (groundBlock[column] == SAND || groundBlock[column] == GRAVEL)
                    && ground[column] >= SEA_LEVEL - 4 && ground[column] <= SEA_LEVEL + 3) {
                // Sand or gravel at the water's edge is a beach
                biome = BEACH;
            }
            biomes[column] = biome;
        }
        return biomes;
    }

    private static void copyList(CompoundTag from, CompoundTag to, String name) {
        Tag<?> tag = from.get(name, Tag.class);
        if (tag != null) to.put(name, tag);
    }

    /**
     * The index of a block in the old 16x128x16 arrays (Y changes fastest, then Z, then X).
     */
    static int oldIndex(int x, int y, int z) {
        return y + (z << 7) + (x << 11);
    }

    private static int getNibble(byte[] array, int index) {
        if (array == null || (index >> 1) >= array.length) return 0;
        int value = array[index >> 1];
        return (index & 1) == 0 ? value & 0x0F : (value >> 4) & 0x0F;
    }

    private static void setNibble(byte[] array, int index, int value) {
        int i = index >> 1;
        if ((index & 1) == 0) {
            array[i] = (byte) ((array[i] & 0xF0) | (value & 0x0F));
        } else {
            array[i] = (byte) ((array[i] & 0x0F) | ((value & 0x0F) << 4));
        }
    }
}
