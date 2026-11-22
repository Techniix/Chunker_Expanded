package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.nbt.tags.array.IntArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class MergeUnitTests {
    private static MergeSource source(String name, int minRX, int minRZ, int maxRX, int maxRZ) {
        MergeSource source = new MergeSource(new File(name), null);
        source.getBoundsByDimension().put(Dimension.OVERWORLD.getIdentifier(), new RegionBounds(minRX, minRZ, maxRX, maxRZ));
        return source;
    }

    @Test
    public void testOffsetsMustBeRegionAligned() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkOffset(5, 0));
        ChunkOffset offset = ChunkOffset.ofRegions(2, -1);
        assertEquals(new ChunkCoordPair(64 + 3, -32 + 4), offset.apply(new ChunkCoordPair(3, 4)));
        assertEquals(new RegionCoordPair(2, -1), offset.apply(new RegionCoordPair(0, 0)));
        assertEquals(ChunkOffset.ofRegions(1, 0), ChunkOffset.ofRegions(8, 2).toNether());
    }

    @Test
    public void testLayoutKeepsFirstWorldAndAvoidsOverlap() {
        List<MergeSource> sources = List.of(
                source("a", -2, -2, 1, 1),
                source("b", -1, -1, 0, 0),
                source("c", -3, -3, 3, 3),
                source("d", 0, 0, 0, 0)
        );
        MergeLayout.apply(sources, new MergeSettings().setGapChunks(32));
        assertTrue(sources.get(0).getOffset().isZero(), "First world must not move");
        assertNull(MergeLayout.findOverlap(sources, Dimension.OVERWORLD, false));

        // Every pair is at least one region apart
        for (int i = 0; i < sources.size(); i++) {
            for (int j = i + 1; j < sources.size(); j++) {
                RegionBounds a = sources.get(i).getLayoutBounds().offset(sources.get(i).getOffset());
                RegionBounds b = sources.get(j).getLayoutBounds().offset(sources.get(j).getOffset());
                RegionBounds grownA = new RegionBounds(a.minRegionX() - 1, a.minRegionZ() - 1, a.maxRegionX() + 1, a.maxRegionZ() + 1);
                assertFalse(grownA.intersects(b), "Gap missing between " + i + " and " + j);
            }
        }
    }

    @Test
    public void testLayoutAlignment() {
        List<MergeSource> sources = List.of(source("a", 0, 0, 2, 2), source("b", 5, 5, 9, 9), source("c", -4, 1, -1, 3));
        MergeLayout.apply(sources, new MergeSettings().setAlignRegions(8));
        for (MergeSource source : sources) {
            assertEquals(0, source.getOffset().regionX() % 8);
            assertEquals(0, source.getOffset().regionZ() % 8);
        }
        assertNull(MergeLayout.findOverlap(sources, Dimension.OVERWORLD, false));
    }

    @Test
    public void testManualOverlapRejected() {
        MergeSource a = new MergeSource(new File("a"), ChunkOffset.ZERO);
        a.getBoundsByDimension().put(Dimension.OVERWORLD.getIdentifier(), new RegionBounds(0, 0, 1, 1));
        MergeSource b = new MergeSource(new File("b"), ChunkOffset.ofRegions(1, 0));
        b.getBoundsByDimension().put(Dimension.OVERWORLD.getIdentifier(), new RegionBounds(0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> MergeLayout.apply(List.of(a, b), new MergeSettings()));
    }

    @Test
    public void testNbtPositionShifting() {
        CompoundTag bee = new CompoundTag();
        bee.put("hive_pos", new int[]{10, 64, 20});
        CompoundTag leash = new CompoundTag();
        leash.put("X", 1);
        leash.put("Y", 2);
        leash.put("Z", 3);
        bee.put("Leash", leash);
        bee.put("HomePosX", 5);
        bee.put("HomePosY", 6);
        bee.put("HomePosZ", 7);
        // Relative structure block offsets must not move
        bee.put("posX", 1);
        bee.put("posY", 1);
        bee.put("posZ", 1);
        CompoundTag brain = new CompoundTag();
        CompoundTag memory = new CompoundTag();
        CompoundTag value = new CompoundTag();
        value.put("pos", new int[]{100, 70, 200});
        memory.put("value", value);
        brain.put("minecraft:home", memory);
        bee.put("Brain", brain);

        NbtPositionShifter.shift(bee, 512, -1024);

        assertArrayEquals(new int[]{522, 64, -1004}, bee.getIntArray("hive_pos"));
        assertEquals(513, leash.getInt("X"));
        assertEquals(-1021, leash.getInt("Z"));
        assertEquals(517, bee.getInt("HomePosX"));
        assertEquals(-1017, bee.getInt("HomePosZ"));
        assertEquals(1, bee.getInt("posX"));
        assertArrayEquals(new int[]{612, 70, -824}, ((IntArrayTag) value.get("pos")).getValue());
    }

    private static ChunkerColumn flatColumn(int chunkX, int chunkZ, int ground) {
        ChunkerColumn column = new ChunkerColumn(new ChunkCoordPair(chunkX, chunkZ));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                column.setBlock(x, 0, z, TerrainBlocks.BEDROCK);
                for (int y = 1; y < ground; y++) column.setBlock(x, y, z, TerrainBlocks.STONE);
                column.setBlock(x, ground, z, TerrainBlocks.GRASS_BLOCK);
            }
        }
        return column;
    }

    @Test
    public void testBelowZeroGeneration() {
        ChunkerColumn column = flatColumn(3, -7, 10);
        BelowZeroGenerator generator = new BelowZeroGenerator(42);
        assertTrue(generator.fill(column));
        assertEquals(-4, column.getChunks().firstByteKey());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                assertEquals(ChunkerVanillaBlockType.BEDROCK, column.getBlock(x, -64, z).getType());
                assertNotEquals(ChunkerVanillaBlockType.BEDROCK, column.getBlock(x, 0, z).getType(), "Old floor should be replaced");
            }
        }
        // Mostly deepslate
        int deepslate = 0;
        for (int y = -60; y < 0; y++) {
            if (column.getBlock(8, y, 8).getType() == ChunkerVanillaBlockType.DEEPSLATE) deepslate++;
        }
        assertTrue(deepslate > 20, "Expected mostly deepslate, got " + deepslate);
        // Already extended columns are not touched again
        assertFalse(generator.fill(column));

        // Deterministic
        ChunkerColumn other = flatColumn(3, -7, 10);
        new BelowZeroGenerator(42).fill(other);
        for (int y = -64; y < 0; y++) {
            assertEquals(column.getBlock(5, y, 9), other.getBlock(5, y, 9));
        }
    }

    @Test
    public void testBelowZeroSkipsVoidWorlds() {
        ChunkerColumn column = new ChunkerColumn(new ChunkCoordPair(0, 0));
        column.setBlock(0, 70, 0, TerrainBlocks.STONE); // Sky island, no bedrock floor
        assertFalse(new BelowZeroGenerator(1).fill(column));
        assertEquals(4, column.getChunks().firstByteKey());
    }

    @Test
    public void testSeamBlendsHeightsBetweenWorlds() {
        SurfaceProfiles profiles = new SurfaceProfiles();
        // World A: chunks x 0..3 at height 10, world B: chunks x 10..13 at height 50, both z 0..3
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                ChunkerColumn a = flatColumn(x, z, 10);
                profiles.markOccupied(x, z);
                profiles.record(a, 0, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
                ChunkerColumn b = flatColumn(x + 10, z, 50);
                profiles.markOccupied(x + 10, z);
                profiles.record(b, 0, ChunkerBiome.ChunkerVanillaBiome.DESERT);
            }
        }

        SeamGenerator generator = new SeamGenerator(profiles, new MergeSettings().setSeamWidthChunks(6), true, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
        int targets = generator.prepare();
        assertTrue(targets > 0);
        assertTrue(generator.getTargetChunks().contains(SurfaceProfiles.key(6, 1)));
        assertFalse(generator.getTargetChunks().contains(SurfaceProfiles.key(2, 1)), "Existing chunks must never be targets");

        // Heights rise smoothly across the gap between the two worlds (x = 64 .. 159)
        double previous = Double.NEGATIVE_INFINITY;
        for (int x = 64; x < 160; x += 4) {
            double height = generator.blockHeight(x, 24);
            assertTrue(height >= previous - 0.5, "Height should not drop at x=" + x + " (" + height + " < " + previous + ")");
            previous = height;
        }
        assertEquals(10, generator.blockHeight(64, 24), 3.0, "Seam should start at world A's height");
        assertEquals(50, generator.blockHeight(159, 24), 3.0, "Seam should end at world B's height");
        double mid = generator.blockHeight(112, 24);
        assertTrue(mid > 20 && mid < 40, "Middle of the seam should be between the two worlds, got " + mid);

        // Generated column is solid below the surface, has deepslate / bedrock below zero and no unexpected water
        ChunkerColumn column = generator.generate(7, 1);
        assertEquals(-4, column.getChunks().firstByteKey());
        assertEquals(ChunkerVanillaBlockType.BEDROCK, column.getBlock(3, -64, 3).getType());
        int surface = column.getHighestBlock(8, 8, block -> !block.isAir()).first();
        assertTrue(surface > 10 && surface < 50);
        assertNotEquals(ChunkerVanillaBlockType.WATER, column.getBlock(8, surface, 8).getType());

        // Biomes come from the nearest world: plains near A, desert near B
        Set<ChunkerBiome> nearA = Set.of(generator.generate(4, 1).getBiomes().as4X4Palette(8).get(0, 0, 0));
        Set<ChunkerBiome> nearB = Set.of(generator.generate(9, 1).getBiomes().as4X4Palette(8).get(3, 0, 3));
        assertTrue(nearA.contains(ChunkerBiome.ChunkerVanillaBiome.PLAINS));
        assertTrue(nearB.contains(ChunkerBiome.ChunkerVanillaBiome.DESERT));
    }
}
