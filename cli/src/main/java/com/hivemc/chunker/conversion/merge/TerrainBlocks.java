package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;

import java.util.Locale;

/**
 * Shared block identifiers and block classification helpers for the merge generators.
 */
public final class TerrainBlocks {
    public static final ChunkerBlockIdentifier AIR = ChunkerBlockIdentifier.AIR;
    public static final ChunkerBlockIdentifier STONE = of(ChunkerVanillaBlockType.STONE);
    public static final ChunkerBlockIdentifier DEEPSLATE = of(ChunkerVanillaBlockType.DEEPSLATE);
    public static final ChunkerBlockIdentifier TUFF = of(ChunkerVanillaBlockType.TUFF);
    public static final ChunkerBlockIdentifier BEDROCK = of(ChunkerVanillaBlockType.BEDROCK);
    public static final ChunkerBlockIdentifier DIRT = of(ChunkerVanillaBlockType.DIRT);
    public static final ChunkerBlockIdentifier GRASS_BLOCK = of(ChunkerVanillaBlockType.GRASS_BLOCK);
    public static final ChunkerBlockIdentifier SAND = of(ChunkerVanillaBlockType.SAND);
    public static final ChunkerBlockIdentifier SANDSTONE = of(ChunkerVanillaBlockType.SANDSTONE);
    public static final ChunkerBlockIdentifier GRAVEL = of(ChunkerVanillaBlockType.GRAVEL);
    public static final ChunkerBlockIdentifier WATER = of(ChunkerVanillaBlockType.WATER);
    public static final ChunkerBlockIdentifier LAVA = of(ChunkerVanillaBlockType.LAVA);
    public static final ChunkerBlockIdentifier DEEPSLATE_DIAMOND_ORE = of(ChunkerVanillaBlockType.DEEPSLATE_DIAMOND_ORE);
    public static final ChunkerBlockIdentifier DEEPSLATE_REDSTONE_ORE = of(ChunkerVanillaBlockType.DEEPSLATE_REDSTONE_ORE);
    public static final ChunkerBlockIdentifier DEEPSLATE_IRON_ORE = of(ChunkerVanillaBlockType.DEEPSLATE_IRON_ORE);
    public static final ChunkerBlockIdentifier DEEPSLATE_GOLD_ORE = of(ChunkerVanillaBlockType.DEEPSLATE_GOLD_ORE);
    public static final ChunkerBlockIdentifier DEEPSLATE_LAPIS_ORE = of(ChunkerVanillaBlockType.DEEPSLATE_LAPIS_ORE);
    public static final ChunkerBlockIdentifier DEEPSLATE_COPPER_ORE = of(ChunkerVanillaBlockType.DEEPSLATE_COPPER_ORE);
    public static final ChunkerBlockIdentifier COAL_ORE = of(ChunkerVanillaBlockType.COAL_ORE);
    public static final ChunkerBlockIdentifier IRON_ORE = of(ChunkerVanillaBlockType.IRON_ORE);

    private TerrainBlocks() {
    }

    private static ChunkerBlockIdentifier of(ChunkerBlockType type) {
        return new ChunkerBlockIdentifier(type);
    }

    /**
     * Whether the block is a liquid (water / lava).
     */
    public static boolean isLiquid(ChunkerBlockIdentifier identifier) {
        ChunkerBlockType type = identifier.getType();
        return type == ChunkerVanillaBlockType.WATER || type == ChunkerVanillaBlockType.LAVA;
    }

    /**
     * Whether the block is water or a water-like block (seagrass / kelp / bubble columns count as water for surfaces).
     */
    public static boolean isWaterLike(ChunkerBlockIdentifier identifier) {
        ChunkerBlockType type = identifier.getType();
        if (type == ChunkerVanillaBlockType.WATER) return true;
        if (!(type instanceof ChunkerVanillaBlockType vanilla)) return false;
        String name = vanilla.name();
        return name.contains("SEAGRASS") || name.startsWith("KELP") || name.equals("BUBBLE_COLUMN");
    }

    /**
     * Whether a block is natural "ground" which terrain heights should be measured from. Vegetation, trees, snow
     * layers and man-made blocks are ignored so builds and forests don't distort the blended seams.
     */
    public static boolean isGround(ChunkerBlockIdentifier identifier) {
        if (identifier.isAir()) return false;
        ChunkerBlockType type = identifier.getType();
        if (!(type instanceof ChunkerVanillaBlockType vanilla)) return false;
        String name = vanilla.name();
        switch (name) {
            case "STONE", "GRANITE", "DIORITE", "ANDESITE", "DEEPSLATE", "TUFF", "CALCITE", "BEDROCK",
                 "GRASS_BLOCK", "DIRT", "COARSE_DIRT", "PODZOL", "ROOTED_DIRT", "MYCELIUM", "MUD", "CLAY",
                 "SAND", "RED_SAND", "GRAVEL", "SANDSTONE", "RED_SANDSTONE", "SNOW_BLOCK", "POWDER_SNOW",
                 "ICE", "PACKED_ICE", "BLUE_ICE", "TERRACOTTA", "MOSS_BLOCK", "DRIPSTONE_BLOCK",
                 "NETHERRACK", "SOUL_SAND", "SOUL_SOIL", "END_STONE", "BASALT", "BLACKSTONE", "MAGMA_BLOCK",
                 "SMOOTH_BASALT", "PALE_MOSS_BLOCK" -> {
                return true;
            }
            default -> {
                return name.endsWith("_TERRACOTTA") && !name.contains("GLAZED") || name.endsWith("_ORE");
            }
        }
    }

    /**
     * Whether a block is a sensible surface material to copy onto generated seam terrain.
     */
    public static boolean isSurfaceMaterial(ChunkerBlockIdentifier identifier) {
        return isGround(identifier) && identifier.getType() != ChunkerVanillaBlockType.BEDROCK
                && !(identifier.getType() instanceof ChunkerVanillaBlockType vanilla && vanilla.name().endsWith("_ORE"));
    }

    /**
     * Human readable name, for logging.
     */
    public static String name(ChunkerBlockIdentifier identifier) {
        return identifier.getType().toString().toLowerCase(Locale.ROOT);
    }
}
