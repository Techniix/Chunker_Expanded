package com.hivemc.chunker.conversion.merge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides where each source world goes in the merged world.
 * <p>
 * The first source always keeps its original co-ordinates (offset zero) so the spawn point and player data, which
 * are taken from the first world, stay valid. The other worlds are packed around it without overlapping.
 */
public final class MergeLayout {
    private MergeLayout() {
    }

    /**
     * Compute and apply offsets to every source.
     *
     * @param sources  the sources, with bounds already scanned.
     * @param settings the settings to use.
     */
    public static void apply(List<MergeSource> sources, MergeSettings settings) {
        if (sources.isEmpty()) return;

        boolean anyManual = sources.stream().anyMatch(source -> source.getManualOffset() != null);
        if (anyManual) {
            for (MergeSource source : sources) {
                source.setOffset(source.getManualOffset() != null ? source.getManualOffset() : ChunkOffset.ZERO);
            }
        } else {
            pack(sources, settings);
        }
        validate(sources);
    }

    private static int alignUp(int value, int alignment) {
        return Math.floorDiv(value + alignment - 1, alignment) * alignment;
    }

    private static int slotSize(int min, int max, int gapRegions, int align) {
        // The world starts (min - alignedMin) regions into its slot, so include that in the size
        int alignedMin = Math.floorDiv(min, align) * align;
        return alignUp(max - alignedMin + 1 + gapRegions, align);
    }

    private static void pack(List<MergeSource> sources, MergeSettings settings) {
        int gapRegions = (settings.getGapChunks() + 31) >> 5;
        int align = settings.getAlignRegions();

        // Sources without any chunks stay where they are
        List<MergeSource> placeable = new ArrayList<>();
        for (MergeSource source : sources) {
            if (source.getLayoutBounds() != null) {
                placeable.add(source);
            } else {
                source.setOffset(ChunkOffset.ZERO);
            }
        }
        if (placeable.isEmpty()) return;

        // Size of each slot (bounds + gap, rounded up to the alignment)
        long totalArea = 0;
        int widest = 0;
        for (MergeSource source : placeable) {
            RegionBounds bounds = source.getLayoutBounds();
            int width = slotSize(bounds.minRegionX(), bounds.maxRegionX(), gapRegions, align);
            int depth = slotSize(bounds.minRegionZ(), bounds.maxRegionZ(), gapRegions, align);
            totalArea += (long) width * depth;
            widest = Math.max(widest, width);
        }
        int rowLimit = settings.getLayout() == MergeSettings.Layout.ROW
                ? Integer.MAX_VALUE
                : Math.max(widest, alignUp((int) Math.ceil(Math.sqrt(totalArea)), align));

        // Shelf packing: tallest worlds first so rows stay tidy, the first source is always first in the first row
        MergeSource first = placeable.get(0);
        List<MergeSource> order = new ArrayList<>(placeable.subList(1, placeable.size()));
        order.sort(Comparator.comparingInt((MergeSource source) -> source.getLayoutBounds().depthRegions()).reversed());
        order.add(0, first);

        int cursorX = 0, cursorZ = 0, rowDepth = 0;
        for (MergeSource source : order) {
            RegionBounds bounds = source.getLayoutBounds();
            int width = slotSize(bounds.minRegionX(), bounds.maxRegionX(), gapRegions, align);
            int depth = slotSize(bounds.minRegionZ(), bounds.maxRegionZ(), gapRegions, align);
            if (cursorX > 0 && cursorX + width > rowLimit) {
                cursorX = 0;
                cursorZ += rowDepth;
                rowDepth = 0;
            }

            // Canvas slot position minus the world's own minimum (both aligned) gives the offset
            int slotX = cursorX, slotZ = cursorZ;
            int alignedMinX = Math.floorDiv(bounds.minRegionX(), align) * align;
            int alignedMinZ = Math.floorDiv(bounds.minRegionZ(), align) * align;
            source.setOffset(ChunkOffset.ofRegions(slotX - alignedMinX, slotZ - alignedMinZ));

            cursorX += width;
            rowDepth = Math.max(rowDepth, depth);
        }

        // Shift everything so the first world is not moved
        ChunkOffset firstOffset = first.getOffset();
        for (MergeSource source : placeable) {
            source.setOffset(source.getOffset().subtract(firstOffset));
        }
    }

    /**
     * Find the first pair of sources which overlap in a dimension.
     *
     * @param sources     the sources.
     * @param dimension   the dimension to check.
     * @param scaleNether whether nether offsets are scaled.
     * @return a description of the overlap or null if there isn't one.
     */
    @org.jetbrains.annotations.Nullable
    public static String findOverlap(List<MergeSource> sources, com.hivemc.chunker.conversion.intermediate.world.Dimension dimension, boolean scaleNether) {
        for (int i = 0; i < sources.size(); i++) {
            RegionBounds a = sources.get(i).getBounds(dimension);
            if (a == null) continue;
            a = a.offset(sources.get(i).getOffset(dimension, scaleNether));
            for (int j = i + 1; j < sources.size(); j++) {
                RegionBounds b = sources.get(j).getBounds(dimension);
                if (b == null) continue;
                b = b.offset(sources.get(j).getOffset(dimension, scaleNether));
                if (a.intersects(b)) {
                    return sources.get(i).getName() + " and " + sources.get(j).getName() + " overlap in "
                            + dimension.getIdentifier();
                }
            }
        }
        return null;
    }

    private static void validate(List<MergeSource> sources) {
        for (int i = 0; i < sources.size(); i++) {
            RegionBounds a = sources.get(i).getLayoutBounds();
            if (a == null) continue;
            a = a.offset(sources.get(i).getOffset());
            for (int j = i + 1; j < sources.size(); j++) {
                RegionBounds b = sources.get(j).getLayoutBounds();
                if (b == null) continue;
                b = b.offset(sources.get(j).getOffset());
                if (a.intersects(b)) {
                    throw new IllegalArgumentException("Worlds " + sources.get(i).getName() + " and "
                            + sources.get(j).getName() + " overlap after placement, adjust the offsets.");
                }
            }
        }
    }
}
