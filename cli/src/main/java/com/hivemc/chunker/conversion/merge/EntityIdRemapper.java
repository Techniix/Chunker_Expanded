package com.hivemc.chunker.conversion.merge;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.array.IntArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.LongTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;

import java.util.UUID;

/**
 * Gives entities copied (as original NBT) from the second and later worlds new, deterministic identities, so two
 * worlds can never contain the same entity ID. Bedrock stores entities by their unique ID, so a clash would silently
 * overwrite one of them; Java refuses to load an entity whose UUID already exists.
 * <p>
 * The mapping is a pure function of (world index, old ID), so references between entities of the same world (leads,
 * riders, links) still match after remapping. Owners of pets are players and are not remapped.
 */
public final class EntityIdRemapper {
    private EntityIdRemapper() {
    }

    static long remap(long id, int sourceIndex) {
        if (id == 0 || id == -1) return id; // "No entity" markers
        long mixed = MergeNoise.hash(0x1D5EEDL, sourceIndex, id, 0);
        return mixed == 0 || mixed == -1 ? mixed + 2 : mixed;
    }

    static int[] remap(int[] uuid, int sourceIndex) {
        long most = ((long) uuid[0] << 32) | (uuid[1] & 0xFFFFFFFFL);
        long least = ((long) uuid[2] << 32) | (uuid[3] & 0xFFFFFFFFL);
        long newMost = remap(most, sourceIndex), newLeast = remap(least ^ most, sourceIndex);
        // Keep it a valid version 4 style UUID
        newMost = (newMost & ~0xF000L) | 0x4000L;
        newLeast = (newLeast & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new int[]{(int) (newMost >> 32), (int) newMost, (int) (newLeast >> 32), (int) newLeast};
    }

    /**
     * Remap the identity fields of an entity's original NBT (recursing into riders).
     *
     * @param tag         the entity NBT.
     * @param sourceIndex the index of the world it came from (0 is never remapped).
     */
    public static void remapEntity(CompoundTag tag, int sourceIndex) {
        if (tag == null || sourceIndex == 0) return;

        // Bedrock
        remapLong(tag, "UniqueID", sourceIndex);
        remapLong(tag, "LeasherID", sourceIndex);
        if (tag.get("LinksTag") instanceof ListTag<?, ?> links) {
            for (Object link : links.getValue()) {
                if (link instanceof CompoundTag compound) {
                    remapLong(compound, "entityID", sourceIndex);
                    remapLong(compound, "EntityUniqueID", sourceIndex);
                }
            }
        }

        // Java 1.16+
        if (tag.get("UUID") instanceof IntArrayTag uuid && uuid.getValue() != null && uuid.getValue().length == 4) {
            uuid.setValue(remap(uuid.getValue(), sourceIndex));
        }
        // Java before 1.16
        if (tag.get("UUIDMost") instanceof LongTag && tag.get("UUIDLeast") instanceof LongTag) {
            int[] remapped = remap(split(tag.getLong("UUIDMost"), tag.getLong("UUIDLeast")), sourceIndex);
            tag.put("UUIDMost", ((long) remapped[0] << 32) | (remapped[1] & 0xFFFFFFFFL));
            tag.put("UUIDLeast", ((long) remapped[2] << 32) | (remapped[3] & 0xFFFFFFFFL));
        }
        if (tag.get("UUID") instanceof StringTag stringUuid) {
            try {
                UUID uuid = UUID.fromString(stringUuid.getValue());
                int[] remapped = remap(split(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()), sourceIndex);
                tag.put("UUID", new UUID(((long) remapped[0] << 32) | (remapped[1] & 0xFFFFFFFFL),
                        ((long) remapped[2] << 32) | (remapped[3] & 0xFFFFFFFFL)).toString());
            } catch (IllegalArgumentException ignored) {
                // Not a UUID
            }
        }
        // Java leads attached to another entity
        if (tag.get("leash") instanceof CompoundTag leash) remapEntityReference(leash, sourceIndex);
        if (tag.get("Leash") instanceof CompoundTag leash) remapEntityReference(leash, sourceIndex);

        // Riders
        if (tag.get("Passengers") instanceof ListTag<?, ?> passengers) {
            for (Object passenger : passengers.getValue()) {
                if (passenger instanceof CompoundTag compound) remapEntity(compound, sourceIndex);
            }
        }
    }

    private static void remapEntityReference(CompoundTag reference, int sourceIndex) {
        if (reference.get("UUID") instanceof IntArrayTag uuid && uuid.getValue() != null && uuid.getValue().length == 4) {
            uuid.setValue(remap(uuid.getValue(), sourceIndex));
        }
        if (reference.get("UUIDMost") instanceof LongTag && reference.get("UUIDLeast") instanceof LongTag) {
            int[] remapped = remap(split(reference.getLong("UUIDMost"), reference.getLong("UUIDLeast")), sourceIndex);
            reference.put("UUIDMost", ((long) remapped[0] << 32) | (remapped[1] & 0xFFFFFFFFL));
            reference.put("UUIDLeast", ((long) remapped[2] << 32) | (remapped[3] & 0xFFFFFFFFL));
        }
    }

    private static int[] split(long most, long least) {
        return new int[]{(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
    }

    private static void remapLong(CompoundTag tag, String name, int sourceIndex) {
        Tag<?> value = tag.get(name);
        if (value instanceof LongTag longTag) {
            longTag.setValue(remap(longTag.getValue(), sourceIndex));
        }
    }

}
