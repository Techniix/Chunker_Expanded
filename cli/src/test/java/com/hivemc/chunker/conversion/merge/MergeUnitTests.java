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
                ChunkerColumn a = flatColumn(x, z, 70);
                profiles.markOccupied(x, z);
                profiles.record(a, 0, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
                ChunkerColumn b = flatColumn(x + 10, z, 110);
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
        assertEquals(70, generator.blockHeight(64, 24), 3.0, "Seam should start at world A's height");
        assertEquals(110, generator.blockHeight(159, 24), 3.0, "Seam should end at world B's height");
        double mid = generator.blockHeight(112, 24);
        assertTrue(mid > 80 && mid < 100, "Middle of the seam should be between the two worlds, got " + mid);

        // Generated column is solid below the surface, has deepslate / bedrock below zero and no unexpected water
        ChunkerColumn column = generator.generate(7, 1);
        assertEquals(-4, column.getChunks().firstByteKey());
        assertEquals(ChunkerVanillaBlockType.BEDROCK, column.getBlock(3, -64, 3).getType());
        int surface = column.getHighestBlock(8, 8, block -> !block.isAir()).first();
        assertTrue(surface > 70 && surface < 110);
        assertNotEquals(ChunkerVanillaBlockType.WATER, column.getBlock(8, surface, 8).getType());

        // Biomes come from the nearest world: plains near A, desert near B
        Set<ChunkerBiome> nearA = Set.of(generator.generate(4, 1).getBiomes().as4X4Palette(8).get(0, 0, 0));
        Set<ChunkerBiome> nearB = Set.of(generator.generate(9, 1).getBiomes().as4X4Palette(8).get(3, 0, 3));
        assertTrue(nearA.contains(ChunkerBiome.ChunkerVanillaBiome.PLAINS));
        assertTrue(nearB.contains(ChunkerBiome.ChunkerVanillaBiome.DESERT));
    }

    @Test
    public void testEntityIdRemapping() {
        CompoundTag bedrock = new CompoundTag();
        bedrock.put("UniqueID", -4294967200L);
        bedrock.put("OwnerNew", -4294967295L);
        CompoundTag again = new CompoundTag();
        again.put("UniqueID", -4294967200L);

        EntityIdRemapper.remapEntity(bedrock, 0);
        assertEquals(-4294967200L, bedrock.getLong("UniqueID"), "The main world is never remapped");

        EntityIdRemapper.remapEntity(bedrock, 1);
        EntityIdRemapper.remapEntity(again, 2);
        assertNotEquals(-4294967200L, bedrock.getLong("UniqueID"));
        assertNotEquals(bedrock.getLong("UniqueID"), again.getLong("UniqueID"), "Different worlds get different IDs");
        assertEquals(-4294967295L, bedrock.getLong("OwnerNew"), "Pet owners (players) are kept");

        // Same world and ID remap to the same value (so references still match)
        CompoundTag first = new CompoundTag();
        first.put("UUID", new int[]{1, 2, 3, 4});
        CompoundTag leash = new CompoundTag();
        leash.put("UUID", new int[]{1, 2, 3, 4});
        CompoundTag leashed = new CompoundTag();
        leashed.put("leash", leash);
        EntityIdRemapper.remapEntity(first, 3);
        EntityIdRemapper.remapEntity(leashed, 3);
        assertArrayEquals(first.getIntArray("UUID"), leash.getIntArray("UUID"));
        assertFalse(java.util.Arrays.equals(new int[]{1, 2, 3, 4}, first.getIntArray("UUID")));
    }

    @Test
    public void testSeamFillsCorridorBetweenWorlds() {
        SurfaceProfiles profiles = new SurfaceProfiles();
        // Two worlds 40 chunks apart, more than twice the seam width
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                profiles.markOccupied(x, z);
                profiles.record(flatColumn(x, z, 30), 0, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
                profiles.markOccupied(x + 44, z);
                profiles.record(flatColumn(x + 44, z, 30), 0, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
            }
        }
        SeamGenerator generator = new SeamGenerator(profiles, new MergeSettings().setSeamWidthChunks(4), true, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
        generator.prepare();
        for (int x = 4; x < 44; x++) {
            assertTrue(generator.getTargetChunks().contains(SurfaceProfiles.key(x, 1)), "Corridor chunk " + x + " should be filled");
        }
        // Without corridors the middle stays empty
        SeamGenerator noCorridors = new SeamGenerator(profiles, new MergeSettings().setSeamWidthChunks(4).setMaxCorridorChunks(0), true, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
        noCorridors.prepare();
        assertFalse(noCorridors.getTargetChunks().contains(SurfaceProfiles.key(24, 1)));
    }

    @Test
    public void testSeamJoinsExactlyAndHasVegetation() {
        SurfaceProfiles profiles = new SurfaceProfiles();
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                profiles.markOccupied(x, z);
                profiles.record(flatColumn(x, z, 70), 0, ChunkerBiome.ChunkerVanillaBiome.FOREST);
            }
        }
        SeamGenerator generator = new SeamGenerator(profiles, new MergeSettings().setSeamWidthChunks(8), true, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
        generator.prepare();

        // Next to the world the surface is exactly the world's height
        ChunkerColumn next = generator.generate(4, 1);
        for (int z = 0; z < 16; z++) {
            int ground = next.getHighestBlock(0, z, TerrainBlocks::isGround).first();
            assertEquals(70, ground, 1, "Seam must join the world's edge at z=" + z);
        }

        // Further out there are plants / trees on grass
        int plants = 0;
        for (int chunkX = 6; chunkX < 11; chunkX++) {
            ChunkerColumn column = generator.generate(chunkX, 1);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    var top = column.getHighestBlock(x, z, block -> !block.isAir());
                    if (top != null && !TerrainBlocks.isGround(top.second())) plants++;
                }
            }
        }
        assertTrue(plants > 50, "Expected vegetation in forest seams, found " + plants);
    }


    @Test
    public void testInteriorEdgesOfLargeWorldsAreProfiled() {
        // A large world: its explored area can end (or have holes) far from the edge of its bounding box
        MergeSource source = new MergeSource(new File("large"), null);
        source.getBoundsByDimension().put(Dimension.OVERWORLD.getIdentifier(), new RegionBounds(-10, -10, 10, 10));
        SurfaceProfiles profiles = new SurfaceProfiles();
        MergeTransform transform = new MergeTransform(source, new MergeSettings(), false, true, null, profiles);
        var handler = transform.columnHandler(new com.hivemc.chunker.conversion.handlers.ColumnConversionHandler() {
            @Override
            public void convertColumn(ChunkerColumn column) {
            }

            @Override
            public void flushRegion(RegionCoordPair regionCoordPair) {
            }

            @Override
            public void flushColumns() {
            }
        }, new com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld(Dimension.OVERWORLD, Set.of(new RegionCoordPair(0, 0))));
        handler.convertColumn(flatColumn(5, 5, 90));
        assertNotNull(profiles.get(5, 5), "Chunks deep inside the bounds must be profiled so seams next to them join up");
    }

    @Test
    public void testSeamFillsHoleInsideWorldAtItsHeight() {
        SurfaceProfiles profiles = new SurfaceProfiles();
        // A 40x40 chunk world at height 90 with a 6x6 unexplored hole in the middle
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 40; z++) {
                if (x >= 17 && x < 23 && z >= 17 && z < 23) continue;
                profiles.markOccupied(x, z);
                profiles.record(flatColumn(x, z, 90), 0, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
            }
        }
        SeamGenerator generator = new SeamGenerator(profiles, new MergeSettings().setSeamWidthChunks(8), true, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
        generator.prepare();
        for (int x = 17; x < 23; x++) {
            assertTrue(generator.getTargetChunks().contains(SurfaceProfiles.key(x, 20)), "Hole chunk " + x + " should be filled");
        }
        assertNull(profiles.get(10, 10), "Profiles far from any seam are freed");
        ChunkerColumn hole = generator.generate(17, 20);
        for (int z = 0; z < 16; z++) {
            assertEquals(90, hole.getHighestBlock(0, z, TerrainBlocks::isGround).first(), 1, "Hole must join the world at z=" + z);
        }
        ChunkerColumn middle = generator.generate(20, 20);
        int ground = middle.getHighestBlock(8, 8, TerrainBlocks::isGround).first();
        assertEquals(90, ground, 6, "A hole is filled at the surrounding height, not sea level");
    }

    @Test
    public void testSeamCarriesLakesWithoutWaterWalls() {
        SurfaceProfiles profiles = new SurfaceProfiles();
        // A world whose ground is at 60 with a lake up to Y=70 over it (above sea level)
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                ChunkerColumn column = flatColumn(x, z, 60);
                for (int bx = 0; bx < 16; bx++) {
                    for (int bz = 0; bz < 16; bz++) {
                        for (int y = 61; y <= 70; y++) column.setBlock(bx, y, bz, TerrainBlocks.WATER);
                    }
                }
                profiles.markOccupied(x, z);
                profiles.record(column, 0, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
            }
        }
        SeamGenerator generator = new SeamGenerator(profiles, new MergeSettings().setSeamWidthChunks(6), true, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
        generator.prepare();

        // The lake carries on at its own level next to the world
        ChunkerColumn next = generator.generate(4, 1);
        assertEquals(ChunkerVanillaBlockType.WATER, next.getBlock(0, 70, 8).getType());
        assertTrue(next.getBlock(0, 71, 8).isAir());

        // Nowhere does water stand next to dry ground lower than its surface
        java.util.Map<Long, ChunkerColumn> columns = new java.util.HashMap<>();
        for (int chunkX = 4; chunkX < 9; chunkX++) {
            for (int chunkZ = 0; chunkZ < 4; chunkZ++) columns.put(SurfaceProfiles.key(chunkX, chunkZ), generator.generate(chunkX, chunkZ));
        }
        int walls = 0;
        for (int x = 64; x < 143; x++) {
            for (int z = 0; z < 63; z++) {
                int[] a = surface(columns, x, z), b = surface(columns, x + 1, z), c = surface(columns, x, z + 1);
                for (int[] n : new int[][]{b, c}) {
                    if (a[1] > 0 && n[1] == 0 && n[0] < a[1]) walls++;
                    if (n[1] > 0 && a[1] == 0 && a[0] < n[1]) walls++;
                }
            }
        }
        assertEquals(0, walls, "Water must not stand as a wall next to lower dry ground");
    }

    /**
     * Ground height and the water surface above it (0 if dry) of a block column.
     */
    private static int[] surface(java.util.Map<Long, ChunkerColumn> columns, int x, int z) {
        ChunkerColumn column = columns.get(SurfaceProfiles.key(x >> 4, z >> 4));
        int ground = column.getHighestBlock(x & 15, z & 15, TerrainBlocks::isGround).first();
        int water = 0;
        for (int y = ground + 1; TerrainBlocks.isWaterLike(column.getBlock(x & 15, y, z & 15)); y++) water = y;
        return new int[]{ground, water};
    }

    @Test
    public void testSeamGroundBelowSeaLevelIsWater() {
        SurfaceProfiles profiles = new SurfaceProfiles();
        // A world whose land is at 66 next to a sea floor at 40 (dry, as if the sea was drained): the seam must not
        // leave dry ground below sea level, which would hold back water as walls
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                profiles.markOccupied(x, z);
                profiles.record(flatColumn(x, z, x < 2 ? 66 : 40), 0, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
            }
        }
        SeamGenerator generator = new SeamGenerator(profiles, new MergeSettings().setSeamWidthChunks(6), true, ChunkerBiome.ChunkerVanillaBiome.PLAINS);
        generator.prepare();
        for (int chunkX = 4; chunkX < 9; chunkX++) {
            ChunkerColumn column = generator.generate(chunkX, 2);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int ground = column.getHighestBlock(x, z, TerrainBlocks::isGround).first();
                    if (ground < 62) {
                        assertTrue(TerrainBlocks.isWaterLike(column.getBlock(x, 62, z)), "Ground at " + ground + " below sea level should be under water");
                    }
                }
            }
        }
        // Dry land next to it keeps a land biome (not ocean) so it gets plants
        assertFalse(SeamGenerator.isWatery(generator.generate(0, -2).getBiomes().as4X4Palette(8).get(0, 0, 0)));
        // Land that ended up deep under water is ocean
        assertTrue(SeamGenerator.isWatery(generator.generate(5, 2).getBiomes().as4X4Palette(6).get(0, 0, 0)));
    }
}
