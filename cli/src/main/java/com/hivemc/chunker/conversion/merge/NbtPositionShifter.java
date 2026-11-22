package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.array.IntArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort shifting of absolute block positions stored inside preserved (original) NBT, such as a bee's hive,
 * a villager's bed / job site memories, a lead's fence knot or a turtle's home beach. Without this, mobs keep
 * "remembering" places at their old co-ordinates after being moved.
 * <p>
 * Only well known names are touched so relative positions (e.g. structure block offsets) are never changed.
 */
public final class NbtPositionShifter {
    // Compounds holding X / Y / Z ints
    private static final Set<String> XYZ_COMPOUNDS = Set.of("leash", "hivepos", "flowerpos");
    // Flat triplets: prefix + X / Y / Z
    private static final Set<String> FLAT_PREFIXES = Set.of(
            "HomePos", "TravelPos", "Bound", "A", "Sleeping", "TreasurePos"
    );
    // Int arrays [x, y, z]
    private static final Set<String> ARRAY_NAMES = Set.of(
            "leash", "hive_pos", "flower_pos", "home_pos", "sleeping_pos", "bound_pos", "anchor_pos",
            "travel_pos", "treasure_pos", "exit_portal", "pos", "flowerpos", "hivepos", "patrol_target",
            "wander_target", "target"
    );

    private NbtPositionShifter() {
    }

    /**
     * Shift positions within a tag (recursively).
     *
     * @param tag the tag to shift, may be null.
     * @param dx  blocks to add on X.
     * @param dz  blocks to add on Z.
     */
    public static void shift(Tag<?> tag, int dx, int dz) {
        if (tag == null || (dx == 0 && dz == 0)) return;
        if (tag instanceof CompoundTag compound) {
            shiftCompound(compound, dx, dz);
        } else if (tag instanceof ListTag<?, ?> list) {
            for (Object entry : list.getValue()) {
                if (entry instanceof Tag<?> child) shift(child, dx, dz);
            }
        }
    }

    private static void shiftCompound(CompoundTag compound, int dx, int dz) {
        for (Map.Entry<String, Tag<?>> entry : compound.getValue().entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            Tag<?> value = entry.getValue();

            if (value instanceof IntArrayTag array && ARRAY_NAMES.contains(lower)) {
                int[] values = array.getValue();
                if (values != null && values.length == 3) {
                    array.setValue(new int[]{values[0] + dx, values[1], values[2] + dz});
                }
            } else if (value instanceof CompoundTag child && XYZ_COMPOUNDS.contains(lower) && isXYZ(child)) {
                addInt(child, "X", dx);
                addInt(child, "Z", dz);
            } else if (value instanceof CompoundTag || value instanceof ListTag<?, ?>) {
                shift(value, dx, dz);
            }
        }

        // Flat triplets
        for (String prefix : FLAT_PREFIXES) {
            if (compound.get(prefix + "X") instanceof IntTag && compound.get(prefix + "Z") instanceof IntTag
                    && compound.get(prefix + "Y") instanceof IntTag) {
                addInt(compound, prefix + "X", dx);
                addInt(compound, prefix + "Z", dz);
            }
        }
    }

    private static boolean isXYZ(CompoundTag compound) {
        return compound.get("X") instanceof IntTag && compound.get("Y") instanceof IntTag && compound.get("Z") instanceof IntTag;
    }

    private static void addInt(CompoundTag compound, String name, int delta) {
        if (compound.get(name) instanceof IntTag intTag) {
            intTag.setValue(intTag.getValue() + delta);
        }
    }

}
