package com.hivemc.chunker.conversion.encoding.java.base.resolver.entity.handlers;

import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.resolver.entity.EntityHandler;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.blockentity.handlers.JavaRandomizableContainerBlockEntityHandler;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.item.ChunkerVanillaItemType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.itemstack.ChunkerItemStack;
import com.hivemc.chunker.conversion.intermediate.column.entity.MobEntity;
import com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import com.hivemc.chunker.util.JsonTextUtil;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads / writes the common mob data for Java Edition (all versions from 1.8 onwards).
 * <p>
 * When the original NBT is being copied (same edition conversions) nothing is changed on write, the original data
 * is already exact.
 */
public class JavaMobEntityHandler extends EntityHandler<JavaResolvers, CompoundTag, MobEntity> {
    /**
     * Mobs which use "Tame" rather than an owner to mark them as tamed.
     */
    private static final Set<ChunkerVanillaEntityType> HORSE_LIKE = EnumSet.of(
            ChunkerVanillaEntityType.HORSE, ChunkerVanillaEntityType.DONKEY, ChunkerVanillaEntityType.MULE,
            ChunkerVanillaEntityType.LLAMA, ChunkerVanillaEntityType.TRADER_LLAMA,
            ChunkerVanillaEntityType.SKELETON_HORSE, ChunkerVanillaEntityType.ZOMBIE_HORSE,
            ChunkerVanillaEntityType.CAMEL
    );
    private static final Set<ChunkerVanillaEntityType> SADDLE_BYTE = EnumSet.of(
            ChunkerVanillaEntityType.PIG, ChunkerVanillaEntityType.STRIDER
    );
    private static final Set<ChunkerVanillaEntityType> COLLAR = EnumSet.of(
            ChunkerVanillaEntityType.WOLF, ChunkerVanillaEntityType.CAT
    );
    private static final Set<ChunkerVanillaEntityType> IS_BABY_FLAG = EnumSet.of(
            ChunkerVanillaEntityType.ZOMBIE, ChunkerVanillaEntityType.HUSK, ChunkerVanillaEntityType.DROWNED,
            ChunkerVanillaEntityType.ZOMBIE_VILLAGER, ChunkerVanillaEntityType.ZOMBIFIED_PIGLIN,
            ChunkerVanillaEntityType.PIGLIN
    );
    private static final String[] LEGACY_PROFESSIONS = {"farmer", "librarian", "cleric", "armorer", "butcher", "nitwit"};
    // Java cat types in CatType order and their Bedrock variant numbers
    private static final String[] CAT_TYPES = {"tabby", "black", "red", "siamese", "british_shorthair", "calico", "persian", "ragdoll", "white", "jellie", "all_black"};
    private static final int[] CAT_TO_BEDROCK = {8, 1, 2, 3, 4, 5, 6, 7, 0, 10, 9};
    // Axolotl: Java lucy, wild, gold, cyan, blue / Bedrock lucy, cyan, gold, wild, blue (self inverse)
    private static final int[] AXOLOTL_SWAP = {0, 3, 2, 1, 4};
    private static final String[] FROG_TYPES = {"temperate", "cold", "warm"};
    private static final String[] EQUIPMENT_ARMOR_KEYS = {"feet", "legs", "chest", "head"};

    public JavaMobEntityHandler(ChunkerVanillaEntityType type) {
        super(type, MobEntity.class, () -> new MobEntity(type));
    }

    /**
     * Read a numeric tag as a float regardless of its stored type.
     */
    @Nullable
    static Float number(CompoundTag tag, String name) {
        Tag<?> value = tag.get(name);
        if (value != null && value.getBoxedValue() instanceof Number number) return number.floatValue();
        return null;
    }

    static boolean flag(CompoundTag tag, String name) {
        Float value = number(tag, name);
        return value != null && value != 0;
    }

    private static boolean isType(MobEntity entity, Set<ChunkerVanillaEntityType> types) {
        return entity.getEntityType() instanceof ChunkerVanillaEntityType vanilla && types.contains(vanilla);
    }

    private static boolean isType(MobEntity entity, ChunkerVanillaEntityType type) {
        return entity.getEntityType() == type;
    }

    @Nullable
    private static ChunkerItemStack readItem(JavaResolvers resolvers, @Nullable CompoundTag tag) {
        if (tag == null || tag.size() == 0) return null;
        ChunkerItemStack item = resolvers.readItem(tag);
        return item.getIdentifier().isAir() ? null : item;
    }

    private static CompoundTag writeItem(JavaResolvers resolvers, @Nullable ChunkerItemStack item) {
        if (item == null || item.getIdentifier().isAir()) return new CompoundTag(0);
        return resolvers.writeItem(item).orElseGet(() -> new CompoundTag(0));
    }

    private static int horseChestSlotOffset(Version version) {
        // Chest items of donkeys / mules / llamas use slots 2 onwards in every version (0 and 1 were the saddle and
        // armor slots, which 1.21.5 moved to equipment without renumbering the chest)
        return 2;
    }

    @Override
    public void read(@NotNull JavaResolvers resolvers, @NotNull CompoundTag input, @NotNull MobEntity value) {
        Version version = resolvers.dataVersion().getVersion();

        // Boats stored the wood as a field before 1.21.2
        String boatWood = input.getString("Type", null);
        if (boatWood != null && MobEntity.boatWood(value.getEntityType()) != null) {
            value.setEntityType(MobEntity.boatType(boatWood, MobEntity.isChestBoat(value.getEntityType())));
        }

        // Dropped items
        if (isType(value, ChunkerVanillaEntityType.ITEM)) {
            value.setMainHand(readItem(resolvers, input.getCompound("Item")));
        }
        value.setPowered(value.isPowered() || flag(input, "ShowBottom") && isType(value, ChunkerVanillaEntityType.END_CRYSTAL));

        // Name
        value.setCustomName(input.getOptional("CustomName", Tag.class)
                .map(tag -> tag instanceof StringTag stringTag && version.isLessThan(1, 13, 0)
                        ? JsonTextUtil.fromText(stringTag.getValue())
                        : JsonTextUtil.fromNBT(tag))
                .orElse(null));
        value.setCustomNameVisible(flag(input, "CustomNameVisible"));

        // Health (1.8 used HealF for the float value)
        Float health = number(input, "HealF");
        value.setHealth(health != null ? health : number(input, "Health"));

        // Flags
        Float age = number(input, "Age");
        value.setAge(age == null ? 0 : age.intValue());
        value.setBaby(value.getAge() < 0 || flag(input, "IsBaby"));
        value.setPersistent(flag(input, "PersistenceRequired"));
        value.setNoAI(flag(input, "NoAI"));
        value.setSilent(flag(input, "Silent"));
        value.setInvulnerable(flag(input, "Invulnerable"));
        value.setSitting(flag(input, "Sitting"));
        value.setTamed(flag(input, "Tame") || input.contains("Owner") || input.contains("OwnerUUID")
                || input.contains("OwnerUUIDMost"));
        value.setChested(flag(input, "ChestedHorse"));
        value.setSheared(flag(input, "Sheared"));
        value.setPowered(flag(input, "powered"));
        if (isType(value, ChunkerVanillaEntityType.SHEEP) && input.contains("Color")) {
            Float color = number(input, "Color");
            if (color != null) value.setColor(color.byteValue());
        }
        if (isType(value, COLLAR) && input.contains("CollarColor")) {
            Float color = number(input, "CollarColor");
            if (color != null) value.setColor(color.byteValue());
        }

        readVariant(input, value, version);

        // Villager profession
        CompoundTag villagerData = input.getCompound("VillagerData");
        if (villagerData != null) {
            String profession = villagerData.getString("profession", "minecraft:none");
            value.setProfession(profession.substring(profession.indexOf(':') + 1));
        } else if (input.contains("Profession")) {
            Float profession = number(input, "Profession");
            if (profession != null && profession >= 0 && profession < LEGACY_PROFESSIONS.length) {
                value.setProfession(LEGACY_PROFESSIONS[profession.intValue()]);
            }
        }

        // Equipment
        CompoundTag equipment = input.getCompound("equipment");
        if (equipment != null) {
            // 1.21.5+
            value.setMainHand(readItem(resolvers, equipment.getCompound("mainhand")));
            value.setOffHand(readItem(resolvers, equipment.getCompound("offhand")));
            for (int i = 0; i < 4; i++) {
                value.getArmor()[i] = readItem(resolvers, equipment.getCompound(EQUIPMENT_ARMOR_KEYS[i]));
            }
            if (equipment.contains("saddle")) value.setSaddled(true);
            value.setBodyArmor(readItem(resolvers, equipment.getCompound("body")));
        } else if (input.contains("HandItems") || input.contains("ArmorItems")) {
            // 1.9 - 1.21.4
            List<CompoundTag> hands = compounds(input, "HandItems");
            if (hands != null && !hands.isEmpty()) {
                value.setMainHand(readItem(resolvers, hands.get(0)));
                if (hands.size() > 1) value.setOffHand(readItem(resolvers, hands.get(1)));
            }
            List<CompoundTag> armor = compounds(input, "ArmorItems");
            if (armor != null) {
                for (int i = 0; i < Math.min(4, armor.size()); i++) {
                    value.getArmor()[i] = readItem(resolvers, armor.get(i));
                }
            }
        } else {
            // 1.8: hand, feet, legs, chest, head
            List<CompoundTag> legacy = compounds(input, "Equipment");
            if (legacy != null && !legacy.isEmpty()) {
                value.setMainHand(readItem(resolvers, legacy.get(0)));
                for (int i = 1; i < Math.min(5, legacy.size()); i++) {
                    value.getArmor()[i - 1] = readItem(resolvers, legacy.get(i));
                }
            }
        }
        if (flag(input, "Saddle") || input.contains("SaddleItem")) value.setSaddled(true);
        if (value.getBodyArmor() == null) {
            // 1.20.5 - 1.21.4 used body_armor_item, before that horses used ArmorItem and llamas DecorItem
            CompoundTag body = input.getCompound("body_armor_item");
            if (body == null) body = input.getCompound("ArmorItem");
            if (body == null) body = input.getCompound("DecorItem");
            value.setBodyArmor(readItem(resolvers, body));
        }

        // Un-opened loot (mineshaft minecarts / chest boats)
        String lootTable = input.getString("LootTable", null);
        if (lootTable != null && !lootTable.isEmpty() && resolvers.converter().shouldProcessLootTables()) {
            value.setLootTable(JavaRandomizableContainerBlockEntityHandler.applyVersionSpecificLootTableFixes(resolvers, lootTable, false));
        }

        // Storage
        List<CompoundTag> items = compounds(input, "Items");
        if (items != null) {
            boolean horse = isType(value, HORSE_LIKE);
            int offset = horse ? horseChestSlotOffset(version) : 0;
            byte index = 0;
            for (CompoundTag itemTag : items) {
                int slot = itemTag.getByte("Slot", index) - offset;
                index++;
                if (slot < 0) continue; // Saddle / armor slots, handled above
                ChunkerItemStack item = readItem(resolvers, itemTag);
                if (item != null) value.getInventory().put((byte) slot, item);
            }
        }
    }

    @Override
    public void write(@NotNull JavaResolvers resolvers, @NotNull CompoundTag output, @NotNull MobEntity value) {
        // Same edition copy, the original data is already exact
        if (resolvers.converter().shouldAllowNBTCopying() && value.getOriginalNBT() != null) return;
        Version version = resolvers.dataVersion().getVersion();

        // Name
        if (value.getCustomName() != null) {
            if (version.isLessThan(1, 13, 0)) {
                output.put("CustomName", JsonTextUtil.toLegacy(value.getCustomName(), false));
            } else {
                output.put("CustomName", JsonTextUtil.toNBT(value.getCustomName(), resolvers.dataVersion()));
            }
            if (value.isCustomNameVisible()) output.put("CustomNameVisible", (byte) 1);
        }

        // Boats store the wood as a field before 1.21.2 (the identifier is used afterwards)
        String wood = MobEntity.boatWood(value.getEntityType());
        if (wood != null && version.isLessThan(1, 21, 2)) {
            output.put("Type", wood);
        }

        // Dropped items / end crystals only need their item / base
        if (isType(value, ChunkerVanillaEntityType.ITEM)) {
            if (value.getMainHand() != null) output.put("Item", writeItem(resolvers, value.getMainHand()));
            output.put("PickupDelay", (short) 0);
            return;
        }
        if (isType(value, ChunkerVanillaEntityType.END_CRYSTAL)) {
            output.put("ShowBottom", (byte) (value.isPowered() ? 1 : 0));
            return;
        }

        if (value.getHealth() != null) {
            output.put("Health", value.getHealth());
        }
        if (value.isBaby() || value.getAge() != 0) {
            output.put("Age", value.isBaby() && value.getAge() >= 0 ? -24000 : value.getAge());
        }
        if (value.isBaby() && isType(value, IS_BABY_FLAG)) output.put("IsBaby", (byte) 1);
        if (value.isPersistent()) output.put("PersistenceRequired", (byte) 1);
        if (value.isNoAI()) output.put("NoAI", (byte) 1);
        if (value.isSilent()) output.put("Silent", (byte) 1);
        if (value.isInvulnerable()) output.put("Invulnerable", (byte) 1);
        if (value.isSitting()) output.put("Sitting", (byte) 1);
        if (value.isTamed() && isType(value, HORSE_LIKE)) output.put("Tame", (byte) 1);
        if (value.isChested()) output.put("ChestedHorse", (byte) 1);
        if (value.isSheared()) output.put("Sheared", (byte) 1);
        if (value.isPowered()) output.put("powered", (byte) 1);
        if (value.getColor() != null) {
            if (isType(value, ChunkerVanillaEntityType.SHEEP)) output.put("Color", value.getColor());
            if (isType(value, COLLAR)) output.put("CollarColor", value.getColor());
        }

        writeVariant(output, value, version);

        // Villager profession
        if (value.getProfession() != null && (isType(value, ChunkerVanillaEntityType.VILLAGER) || isType(value, ChunkerVanillaEntityType.ZOMBIE_VILLAGER))) {
            if (version.isGreaterThanOrEqual(1, 14, 0)) {
                CompoundTag villagerData = new CompoundTag(3);
                villagerData.put("profession", "minecraft:" + value.getProfession());
                villagerData.put("type", "minecraft:plains");
                villagerData.put("level", 1);
                output.put("VillagerData", villagerData);
            } else {
                for (int i = 0; i < LEGACY_PROFESSIONS.length; i++) {
                    if (LEGACY_PROFESSIONS[i].equals(value.getProfession())) output.put("Profession", i);
                }
            }
        }

        // Equipment
        boolean anyEquipment = value.getMainHand() != null || value.getOffHand() != null;
        for (ChunkerItemStack armor : value.getArmor()) anyEquipment |= armor != null;
        boolean saddleItem = value.isSaddled() && !isType(value, SADDLE_BYTE);
        if (version.isGreaterThanOrEqual(1, 21, 5)) {
            if (anyEquipment || value.isSaddled() || value.getBodyArmor() != null) {
                CompoundTag equipment = new CompoundTag();
                if (value.getMainHand() != null) equipment.put("mainhand", writeItem(resolvers, value.getMainHand()));
                if (value.getOffHand() != null) equipment.put("offhand", writeItem(resolvers, value.getOffHand()));
                for (int i = 0; i < 4; i++) {
                    if (value.getArmor()[i] != null) equipment.put(EQUIPMENT_ARMOR_KEYS[i], writeItem(resolvers, value.getArmor()[i]));
                }
                if (value.isSaddled()) equipment.put("saddle", writeItem(resolvers, new ChunkerItemStack(ChunkerVanillaItemType.SADDLE)));
                if (value.getBodyArmor() != null) equipment.put("body", writeItem(resolvers, value.getBodyArmor()));
                output.put("equipment", equipment);
            }
        } else {
            if (anyEquipment) {
                if (version.isGreaterThanOrEqual(1, 9, 0)) {
                    ListTag<CompoundTag, Map<String, Tag<?>>> hands = new ListTag<>(TagType.COMPOUND, 2);
                    hands.add(writeItem(resolvers, value.getMainHand()));
                    hands.add(writeItem(resolvers, value.getOffHand()));
                    output.put("HandItems", hands);
                    ListTag<CompoundTag, Map<String, Tag<?>>> armor = new ListTag<>(TagType.COMPOUND, 4);
                    for (ChunkerItemStack item : value.getArmor()) armor.add(writeItem(resolvers, item));
                    output.put("ArmorItems", armor);
                } else {
                    ListTag<CompoundTag, Map<String, Tag<?>>> legacy = new ListTag<>(TagType.COMPOUND, 5);
                    legacy.add(writeItem(resolvers, value.getMainHand()));
                    for (ChunkerItemStack item : value.getArmor()) legacy.add(writeItem(resolvers, item));
                    output.put("Equipment", legacy);
                }
            }
            if (value.getBodyArmor() != null) {
                if (version.isGreaterThanOrEqual(1, 20, 5)) {
                    output.put("body_armor_item", writeItem(resolvers, value.getBodyArmor()));
                } else if (isType(value, ChunkerVanillaEntityType.LLAMA) || isType(value, ChunkerVanillaEntityType.TRADER_LLAMA)) {
                    output.put("DecorItem", writeItem(resolvers, value.getBodyArmor()));
                } else {
                    output.put("ArmorItem", writeItem(resolvers, value.getBodyArmor()));
                }
            }
            if (value.isSaddled()) {
                if (saddleItem) {
                    output.put("SaddleItem", writeItem(resolvers, new ChunkerItemStack(ChunkerVanillaItemType.SADDLE)));
                } else {
                    output.put("Saddle", (byte) 1);
                }
            }
        }

        // Un-opened loot
        if (value.getLootTable() != null && resolvers.converter().shouldProcessLootTables()
                && !JavaRandomizableContainerBlockEntityHandler.UNSUPPORTED_LOOT_TABLES.contains(value.getLootTable())) {
            output.put("LootTable", JavaRandomizableContainerBlockEntityHandler.applyVersionSpecificLootTableFixes(resolvers, value.getLootTable(), true));
        }

        // Storage
        if (!value.getInventory().isEmpty()) {
            int offset = isType(value, HORSE_LIKE) ? horseChestSlotOffset(version) : 0;
            ListTag<CompoundTag, Map<String, Tag<?>>> items = new ListTag<>(TagType.COMPOUND, value.getInventory().size());
            for (Byte2ObjectMap.Entry<ChunkerItemStack> entry : value.getInventory().byte2ObjectEntrySet()) {
                Optional<CompoundTag> item = resolvers.writeItem(entry.getValue());
                if (item.isEmpty()) continue;
                item.get().put("Slot", (byte) (entry.getByteKey() + offset));
                items.add(item.get());
            }
            output.put("Items", items);
        }
    }

    /**
     * Normalise a villager profession from another edition to Java's names.
     */
    public static String javaProfession(String profession) {
        String lower = profession.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "unskilled" -> "none";
            case "sheperd" -> "shepherd";
            default -> lower;
        };
    }

    /**
     * Get a list of compounds, or null if not present.
     */
    @Nullable
    static List<CompoundTag> compounds(CompoundTag tag, String name) {
        ListTag<CompoundTag, Map<String, Tag<?>>> list = tag.getList(name, CompoundTag.class, null);
        if (list == null) return null;
        List<CompoundTag> result = new java.util.ArrayList<>();
        for (CompoundTag entry : list) result.add(entry);
        return result;
    }


    private static int indexOf(String[] values, String value) {
        String stripped = value.substring(value.indexOf(':') + 1);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(stripped)) return i;
        }
        return -1;
    }

    private static void readVariant(CompoundTag input, MobEntity value, Version version) {
        if (!(value.getEntityType() instanceof ChunkerVanillaEntityType type) || !MobEntity.VARIANT_TYPES.contains(type)) return;
        Float number;
        switch (type) {
            case HORSE -> {
                if ((number = number(input, "Variant")) != null) {
                    value.setVariant(number.intValue() & 0xFF);
                    value.setMarkVariant((number.intValue() >> 8) & 0xFF);
                }
            }
            case LLAMA, TRADER_LLAMA, PARROT -> {
                if ((number = number(input, "Variant")) != null) value.setVariant(number.intValue());
            }
            case AXOLOTL -> {
                if ((number = number(input, "Variant")) != null && number >= 0 && number < AXOLOTL_SWAP.length) {
                    value.setVariant(AXOLOTL_SWAP[number.intValue()]);
                }
            }
            case CAT -> {
                int index = -1;
                String name = input.getString("variant", null);
                if (name != null) index = indexOf(CAT_TYPES, name);
                else if ((number = number(input, "CatType")) != null) index = number.intValue();
                if (index >= 0 && index < CAT_TO_BEDROCK.length) value.setVariant(CAT_TO_BEDROCK[index]);
            }
            case FOX -> value.setVariant("snow".equals(input.getString("Type", "red")) ? 1 : 0);
            case MOOSHROOM -> value.setVariant("brown".equals(input.getString("Type", "red")) ? 1 : 0);
            case FROG -> {
                String name = input.getString("variant", null);
                if (name != null && indexOf(FROG_TYPES, name) >= 0) value.setVariant(indexOf(FROG_TYPES, name));
            }
            case RABBIT -> {
                if ((number = number(input, "RabbitType")) != null && number >= 0 && number <= 5) value.setVariant(number.intValue());
            }
            default -> {
            }
        }
    }

    private static void writeVariant(CompoundTag output, MobEntity value, Version version) {
        if (value.getVariant() == null || !(value.getEntityType() instanceof ChunkerVanillaEntityType type)) return;
        int variant = value.getVariant();
        switch (type) {
            case HORSE -> output.put("Variant", (variant & 0xFF) | ((value.getMarkVariant() == null ? 0 : value.getMarkVariant()) << 8));
            case LLAMA, TRADER_LLAMA, PARROT -> output.put("Variant", variant);
            case AXOLOTL -> {
                if (variant >= 0 && variant < AXOLOTL_SWAP.length) output.put("Variant", AXOLOTL_SWAP[variant]);
            }
            case CAT -> {
                for (int i = 0; i < CAT_TO_BEDROCK.length; i++) {
                    if (CAT_TO_BEDROCK[i] != variant) continue;
                    if (version.isGreaterThanOrEqual(1, 19, 0)) output.put("variant", "minecraft:" + CAT_TYPES[i]);
                    else output.put("CatType", i);
                }
            }
            case FOX -> output.put("Type", variant == 1 ? "snow" : "red");
            case MOOSHROOM -> output.put("Type", variant == 1 ? "brown" : "red");
            case FROG -> {
                if (variant >= 0 && variant < FROG_TYPES.length) output.put("variant", "minecraft:" + FROG_TYPES[variant]);
            }
            case RABBIT -> output.put("RabbitType", variant);
            default -> {
            }
        }
    }

}
