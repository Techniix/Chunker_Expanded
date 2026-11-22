package com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.entity.handlers;

import com.google.common.io.Resources;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hivemc.chunker.conversion.encoding.base.resolver.entity.EntityHandler;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.blockentity.handlers.BedrockRandomizableContainerBlockEntityHandler;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.entity.handlers.JavaMobEntityHandler;
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

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads / writes the common mob data for Bedrock Edition.
 * <p>
 * Bedrock stores which behaviour "component groups" are active on an entity in its {@code definitions} list. When
 * writing a translated mob, the groups the game would apply when spawning it (adult / baby / tamed / chested /
 * profession) are written, taken from a table derived from the vanilla behaviour pack
 * (see tools/gen_bedrock_entity_definitions.py).
 */
public class BedrockMobEntityHandler extends EntityHandler<BedrockResolvers, CompoundTag, MobEntity> {
    private static final JsonObject DEFINITIONS = loadDefinitions();
    private static final String[] COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
            "purple", "blue", "brown", "green", "red", "black"
    };
    private static final Set<ChunkerVanillaEntityType> HORSE_LIKE = EnumSet.of(
            ChunkerVanillaEntityType.HORSE, ChunkerVanillaEntityType.DONKEY, ChunkerVanillaEntityType.MULE,
            ChunkerVanillaEntityType.LLAMA, ChunkerVanillaEntityType.TRADER_LLAMA,
            ChunkerVanillaEntityType.SKELETON_HORSE, ChunkerVanillaEntityType.ZOMBIE_HORSE,
            ChunkerVanillaEntityType.CAMEL
    );
    /**
     * The unique ID of the local (host) player, used as the owner of translated pets.
     */
    public static final long LOCAL_PLAYER_ID = -4294967295L;

    public BedrockMobEntityHandler(ChunkerVanillaEntityType type) {
        super(type, MobEntity.class, () -> new MobEntity(type));
    }

    private static JsonObject loadDefinitions() {
        try (Reader reader = new InputStreamReader(Resources.getResource("bedrock/entity_definitions.json").openStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static List<String> groups(@Nullable JsonObject entry, String key) {
        if (entry == null || !entry.has(key) || !entry.get(key).isJsonArray()) return Collections.emptyList();
        List<String> groups = new ArrayList<>();
        for (JsonElement element : entry.getAsJsonArray(key)) groups.add(element.getAsString());
        return groups;
    }

    /**
     * Work out the component groups a translated mob should have.
     *
     * @param identifier the Bedrock identifier.
     * @param value      the mob.
     * @return the list of group names (without the + prefix).
     */
    public static List<String> definitionGroups(String identifier, MobEntity value) {
        JsonObject entry = DEFINITIONS.has(identifier) ? DEFINITIONS.getAsJsonObject(identifier) : null;
        List<String> groups;
        if (value.isBaby() && entry != null && entry.has("baby")) {
            groups = groups(entry, "baby");
        } else if (value.isChested() && entry != null && entry.has("chested")) {
            groups = groups(entry, "chested");
        } else if (value.isTamed() && entry != null && entry.has("tamed")) {
            groups = groups(entry, "tamed");
        } else {
            groups = groups(entry, "adult");
        }
        groups = new ArrayList<>(groups);

        // Variants (villager professions, charged creepers)
        JsonObject variants = entry != null && entry.has("variants") ? entry.getAsJsonObject("variants") : null;
        String variant = null;
        if (value.getProfession() != null) {
            variant = bedrockProfession(value.getProfession());
        } else if (value.isPowered()) {
            variant = "charged";
        }
        if (variants != null && variant != null && variants.has(variant) && !value.isBaby()) {
            List<String> variantGroups = new ArrayList<>();
            for (JsonElement element : variants.getAsJsonArray(variant)) variantGroups.add(element.getAsString());
            // Charged creepers only add a group, professions replace the base
            if (variant.equals("charged")) groups.addAll(variantGroups);
            else groups = variantGroups;
        }

        // Swap state dependent groups
        for (int i = 0; i < groups.size(); i++) {
            String group = groups.get(i);
            if (value.isSaddled() && group.endsWith("_unsaddled")) groups.set(i, group.replace("_unsaddled", "_saddled"));
            if (value.isSheared() && group.endsWith("_wooly")) groups.set(i, group.replace("_wooly", "_sheared"));
            if (value.getColor() != null && value.getEntityType() == ChunkerVanillaEntityType.SHEEP) {
                for (String color : COLORS) {
                    if (group.equals("minecraft:sheep_" + color)) {
                        groups.set(i, "minecraft:sheep_" + COLORS[value.getColor() & 15]);
                    }
                }
            }
        }
        // Appearance variant groups set the variant component, so swap them for the right one
        swapVariantGroup(entry, "variant_groups", value.getVariant(), groups);
        swapVariantGroup(entry, "mark_variant_groups", value.getMarkVariant(), groups);
        if (value.isSheared() && value.getEntityType() == ChunkerVanillaEntityType.SHEEP && !groups.contains("minecraft:sheep_sheared")) {
            groups.add("minecraft:sheep_sheared");
        }
        return groups;
    }

    private static void swapVariantGroup(@Nullable JsonObject entry, String key, @Nullable Integer value, List<String> groups) {
        if (entry == null || value == null || !entry.has(key)) return;
        JsonObject mapping = entry.getAsJsonObject(key);
        if (!mapping.has(String.valueOf(value))) return;
        String wanted = mapping.get(String.valueOf(value)).getAsString();
        java.util.Set<String> all = new java.util.HashSet<>();
        for (Map.Entry<String, JsonElement> option : mapping.entrySet()) all.add(option.getValue().getAsString());
        boolean replaced = false;
        for (int i = 0; i < groups.size(); i++) {
            if (all.contains(groups.get(i))) {
                groups.set(i, wanted);
                replaced = true;
            }
        }
        if (!replaced) groups.add(wanted);
    }

    /**
     * Convert a (Java style) profession name to Bedrock's component group name.
     */
    public static String bedrockProfession(String profession) {
        return switch (profession) {
            case "none" -> "unskilled";
            case "shepherd" -> "sheperd"; // Spelt like this in the vanilla behaviour pack
            default -> profession;
        };
    }

    @Nullable
    private static ChunkerItemStack readItem(BedrockResolvers resolvers, @Nullable CompoundTag tag) {
        if (tag == null || tag.size() == 0) return null;
        ChunkerItemStack item = resolvers.readItem(tag);
        return item.getIdentifier().isAir() ? null : item;
    }

    private static CompoundTag writeItem(BedrockResolvers resolvers, @Nullable ChunkerItemStack item) {
        if (item != null && !item.getIdentifier().isAir()) {
            Optional<CompoundTag> written = resolvers.writeItem(item);
            if (written.isPresent()) return written.get();
        }
        // Bedrock uses an empty item rather than an empty compound
        CompoundTag empty = new CompoundTag(4);
        empty.put("Name", "");
        empty.put("Count", (byte) 0);
        empty.put("Damage", (short) 0);
        empty.put("WasPickedUp", (byte) 0);
        return empty;
    }

    @Nullable
    private static CompoundTag first(CompoundTag input, String name) {
        List<CompoundTag> list = compounds(input, name);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    @Override
    public void read(@NotNull BedrockResolvers resolvers, @NotNull CompoundTag input, @NotNull MobEntity value) {
        // Boat wood is stored as a variant
        if (MobEntity.boatWood(value.getEntityType()) != null && input.contains("Variant")) {
            int variant = input.getInt("Variant", 0);
            if (variant >= 0 && variant < MobEntity.BOAT_WOODS.length) {
                value.setEntityType(MobEntity.boatType(MobEntity.BOAT_WOODS[variant], MobEntity.isChestBoat(value.getEntityType())));
            }
        }

        // Dropped items
        if (value.getEntityType() == ChunkerVanillaEntityType.ITEM) {
            value.setMainHand(readItem(resolvers, input.getCompound("Item")));
            return;
        }
        if (value.getEntityType() == ChunkerVanillaEntityType.END_CRYSTAL) {
            value.setPowered(input.getByte("ShowBottom", (byte) 0) != 0);
            return;
        }

        // Name
        String name = input.getString("CustomName", null);
        if (name != null && !name.isEmpty()) value.setCustomName(JsonTextUtil.fromText(name));
        value.setCustomNameVisible(input.getByte("CustomNameVisible", (byte) 0) != 0);

        // Health is stored as an attribute
        List<CompoundTag> attributes = compounds(input, "Attributes");
        if (attributes != null) {
            for (CompoundTag attribute : attributes) {
                if ("minecraft:health".equals(attribute.getString("Name", null)) && attribute.get("Current") != null
                        && attribute.get("Current").getBoxedValue() instanceof Number current) {
                    value.setHealth(current.floatValue());
                }
            }
        }

        // Flags
        value.setBaby(input.getByte("IsBaby", (byte) 0) != 0);
        value.setAge(input.getInt("Age", 0));
        if (value.isBaby() && value.getAge() >= 0) value.setAge(-24000);
        value.setPersistent(input.getByte("Persistent", (byte) 0) != 0);
        value.setTamed(input.getByte("IsTamed", (byte) 0) != 0);
        value.setSitting(input.getByte("Sitting", (byte) 0) != 0);
        value.setSaddled(input.getByte("Saddled", (byte) 0) != 0);
        value.setChested(input.getByte("Chested", (byte) 0) != 0);
        value.setSheared(input.getByte("Sheared", (byte) 0) != 0);
        value.setInvulnerable(input.getByte("Invulnerable", (byte) 0) != 0);
        if ((value.getEntityType() == ChunkerVanillaEntityType.SHEEP || value.getEntityType() == ChunkerVanillaEntityType.WOLF
                || value.getEntityType() == ChunkerVanillaEntityType.CAT) && input.contains("Color")) {
            value.setColor(input.getByte("Color", (byte) 0));
        }

        // Appearance variants
        if (MobEntity.VARIANT_TYPES.contains(value.getEntityType())) {
            if (input.contains("Variant")) value.setVariant(input.getInt("Variant", 0));
            if (input.contains("MarkVariant")) value.setMarkVariant(input.getInt("MarkVariant", 0));
        }

        // Definitions carry charged creepers / professions
        List<String> definitions = new ArrayList<>();
        ListTag<StringTag, String> definitionList = input.getList("definitions", StringTag.class, null);
        if (definitionList != null) {
            for (StringTag tag : definitionList) definitions.add(tag.getValue());
        }
        if (definitions.contains("+minecraft:charged_creeper")) value.setPowered(true);
        if (value.getEntityType() == ChunkerVanillaEntityType.VILLAGER || value.getEntityType() == ChunkerVanillaEntityType.ZOMBIE_VILLAGER) {
            String profession = input.getString("PreferredProfession", null);
            if (profession == null) {
                for (String definition : definitions) {
                    String group = definition.substring(1);
                    if (DEFINITIONS.has("minecraft:villager_v2")
                            && DEFINITIONS.getAsJsonObject("minecraft:villager_v2").getAsJsonObject("variants").has(group)) {
                        profession = group;
                    }
                }
            }
            if (profession != null) value.setProfession(JavaMobEntityHandler.javaProfession(profession));
        }

        // Equipment
        value.setMainHand(readItem(resolvers, first(input, "Mainhand")));
        value.setOffHand(readItem(resolvers, first(input, "Offhand")));
        List<CompoundTag> armor = compounds(input, "Armor");
        if (armor != null) {
            // Bedrock order is head, chest, legs, feet
            for (int i = 0; i < Math.min(4, armor.size()); i++) {
                value.getArmor()[3 - i] = readItem(resolvers, armor.get(i));
            }
        }

        // Un-opened loot (mineshaft minecarts / chest boats)
        String lootTable = input.getString("LootTable", null);
        if (lootTable != null && !lootTable.isEmpty() && resolvers.converter().shouldProcessLootTables()) {
            value.setLootTable(BedrockRandomizableContainerBlockEntityHandler.bedrockLootTableToChunker(lootTable));
        }

        // Storage, horse style inventories keep the saddle (or a llama's carpet) in slot 0 and horse armor in slot 1
        List<CompoundTag> items = compounds(input, "ChestItems");
        if (items == null) items = compounds(input, "Items");
        if (items != null) {
            boolean horse = HORSE_LIKE.contains(value.getEntityType());
            byte index = 0;
            for (CompoundTag itemTag : items) {
                byte slot = itemTag.getByte("Slot", index);
                index++;
                ChunkerItemStack item = readItem(resolvers, itemTag);
                if (item == null) continue;
                if (horse) {
                    boolean llama = value.getEntityType() == ChunkerVanillaEntityType.LLAMA || value.getEntityType() == ChunkerVanillaEntityType.TRADER_LLAMA;
                    if (slot == 0) {
                        if (llama) value.setBodyArmor(item);
                        else value.setSaddled(true);
                    } else if (slot == 1 && value.getEntityType() == ChunkerVanillaEntityType.HORSE) {
                        value.setBodyArmor(item);
                    } else {
                        value.getInventory().put((byte) (slot - 1), item);
                    }
                } else {
                    value.getInventory().put(slot, item);
                }
            }
        }
    }

    @Override
    public void write(@NotNull BedrockResolvers resolvers, @NotNull CompoundTag output, @NotNull MobEntity value) {
        // Same edition copy, the original data is already exact
        if (resolvers.converter().shouldAllowNBTCopying() && value.getOriginalNBT() != null) return;

        String identifier = output.getString("identifier", null);
        if (identifier == null) return;

        // Boats store the wood as a variant
        String wood = MobEntity.boatWood(value.getEntityType());
        if (wood != null) {
            for (int i = 0; i < MobEntity.BOAT_WOODS.length; i++) {
                if (MobEntity.BOAT_WOODS[i].equals(wood)) output.put("Variant", i);
            }
        }

        // Definitions
        ListTag<StringTag, String> definitions = new ListTag<>(TagType.STRING);
        definitions.add(new StringTag("+" + identifier));
        for (String group : definitionGroups(identifier, value)) {
            definitions.add(new StringTag("+" + group));
        }
        output.put("definitions", definitions);

        // Dropped items / end crystals only need their item / base
        if (value.getEntityType() == ChunkerVanillaEntityType.ITEM) {
            if (value.getMainHand() != null) output.put("Item", writeItem(resolvers, value.getMainHand()));
            return;
        }
        if (value.getEntityType() == ChunkerVanillaEntityType.END_CRYSTAL) {
            output.put("ShowBottom", (byte) (value.isPowered() ? 1 : 0));
            return;
        }


        // Name
        if (value.getCustomName() != null) {
            output.put("CustomName", JsonTextUtil.toLegacy(value.getCustomName(), true));
            output.put("CustomNameVisible", (byte) (value.isCustomNameVisible() ? 1 : 0));
        }

        // Flags
        output.put("IsBaby", (byte) (value.isBaby() ? 1 : 0));
        if (value.isBaby()) output.put("Age", value.getAge() < 0 ? value.getAge() : -24000);
        output.put("Persistent", (byte) (value.isPersistent() ? 1 : 0));
        if (value.isTamed()) {
            output.put("IsTamed", (byte) 1);
            if (!HORSE_LIKE.contains(value.getEntityType())) {
                // Pets are given to the local player so they stay tame
                output.put("OwnerNew", LOCAL_PLAYER_ID);
            }
        }
        if (value.isSitting()) output.put("Sitting", (byte) 1);
        if (value.isSaddled()) output.put("Saddled", (byte) 1);
        if (value.isChested()) output.put("Chested", (byte) 1);
        if (value.isSheared()) output.put("Sheared", (byte) 1);
        if (value.isInvulnerable()) output.put("Invulnerable", (byte) 1);
        if (value.getColor() != null) output.put("Color", value.getColor());
        if (value.getVariant() != null && MobEntity.VARIANT_TYPES.contains(value.getEntityType())) {
            output.put("Variant", value.getVariant());
            if (value.getMarkVariant() != null) output.put("MarkVariant", value.getMarkVariant());
        }
        if (value.getProfession() != null && value.getEntityType() == ChunkerVanillaEntityType.VILLAGER) {
            output.put("PreferredProfession", bedrockProfession(value.getProfession()));
        }

        // Equipment
        boolean anyEquipment = value.getMainHand() != null || value.getOffHand() != null;
        for (ChunkerItemStack armor : value.getArmor()) anyEquipment |= armor != null;
        if (anyEquipment) {
            ListTag<CompoundTag, Map<String, Tag<?>>> mainHand = new ListTag<>(TagType.COMPOUND, 1);
            mainHand.add(writeItem(resolvers, value.getMainHand()));
            output.put("Mainhand", mainHand);
            ListTag<CompoundTag, Map<String, Tag<?>>> offHand = new ListTag<>(TagType.COMPOUND, 1);
            offHand.add(writeItem(resolvers, value.getOffHand()));
            output.put("Offhand", offHand);
            ListTag<CompoundTag, Map<String, Tag<?>>> armor = new ListTag<>(TagType.COMPOUND, 4);
            for (int i = 3; i >= 0; i--) armor.add(writeItem(resolvers, value.getArmor()[i]));
            output.put("Armor", armor);
        }

        // Un-opened loot
        if (value.getLootTable() != null && resolvers.converter().shouldProcessLootTables()
                && !BedrockRandomizableContainerBlockEntityHandler.UNSUPPORTED_LOOT_TABLES.contains(value.getLootTable())) {
            output.put("LootTable", BedrockRandomizableContainerBlockEntityHandler.chunkerLootTableToBedrock(value.getLootTable()));
        }

        // Storage, horse style inventories keep the saddle (or a llama's carpet) in slot 0 and horse armor in slot 1
        boolean horse = HORSE_LIKE.contains(value.getEntityType());
        boolean llama = value.getEntityType() == ChunkerVanillaEntityType.LLAMA || value.getEntityType() == ChunkerVanillaEntityType.TRADER_LLAMA;
        ListTag<CompoundTag, Map<String, Tag<?>>> items = new ListTag<>(TagType.COMPOUND, value.getInventory().size() + 2);
        if (horse) {
            ChunkerItemStack slotZero = llama ? value.getBodyArmor()
                    : value.isSaddled() ? new ChunkerItemStack(com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.item.ChunkerVanillaItemType.SADDLE) : null;
            if (slotZero != null) {
                resolvers.writeItem(slotZero).ifPresent(item -> {
                    item.put("Slot", (byte) 0);
                    items.add(item);
                });
            }
            if (value.getEntityType() == ChunkerVanillaEntityType.HORSE && value.getBodyArmor() != null) {
                resolvers.writeItem(value.getBodyArmor()).ifPresent(item -> {
                    item.put("Slot", (byte) 1);
                    items.add(item);
                });
            }
        }
        for (Byte2ObjectMap.Entry<ChunkerItemStack> entry : value.getInventory().byte2ObjectEntrySet()) {
            Optional<CompoundTag> item = resolvers.writeItem(entry.getValue());
            if (item.isEmpty()) continue;
            item.get().put("Slot", (byte) (entry.getByteKey() + (horse ? 1 : 0)));
            items.add(item.get());
        }
        if (items.size() > 0) output.put("ChestItems", items);
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

}
