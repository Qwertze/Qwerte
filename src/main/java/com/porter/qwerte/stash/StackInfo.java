package com.porter.qwerte.stash;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.EmptyBlockGetter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * What the sorter knows about one stack: enough to sort, search and label it. Mirrors the proxy plugin's item. The
 * name is always the item's own ("Diamond Sword"); the anvil name is only kept in {@link #customText}, for display, and
 * is never matched against anything.
 */
public final class StackInfo {
    /** The stack this was read from. */
    public final ItemStack stack;
    /** The registry path, like "diamond_sword". */
    public final String id;
    public final int count;
    /** The item's default name. Never the anvil name. */
    public final String name;
    public final boolean customName;
    /** What the player named it, for display only. Null if it has no custom name. */
    public final String customText;
    public final int maxStack;
    public final boolean food;
    public final boolean music;
    public final boolean block;
    public final boolean damaged;
    /** Enchantment names (registry paths), stored ones included. */
    public final List<String> enchants = new ArrayList<>();
    /** Stored enchantments (enchanted books) and their levels, by registry path. */
    public final java.util.Map<String, Integer> storedLevels = new java.util.HashMap<>();
    /** Enchantments on gear and their levels, by registry path. */
    public final java.util.Map<String, Integer> levels = new java.util.HashMap<>();
    /** The potion's registry path, like "strong_strength", or null. */
    public final String potion;
    /**
     * For items whose id doesn't say what they are (potions, tipped arrows, enchanted books, suspicious stew, rockets,
     * goat horns, ...): the component value that does. Null for everything else.
     */
    public final Object typeValue;

    private List<StackInfo> contents;
    private Boolean fullBlock;

    private StackInfo(ItemStack stack) {
        this.stack = stack;
        this.id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        this.count = stack.getCount();
        // The item's own name; getHoverName would give the anvil name
        this.name = stack.getItemName().getString();

        var custom = stack.get(DataComponents.CUSTOM_NAME);
        this.customName = custom != null;
        this.customText = custom == null ? null : custom.getString();

        this.maxStack = stack.getMaxStackSize();
        this.food = stack.has(DataComponents.FOOD);
        this.music = stack.has(DataComponents.JUKEBOX_PLAYABLE);
        this.block = stack.getItem() instanceof BlockItem;
        this.damaged = stack.isDamaged();

        addEnchants(stack.get(DataComponents.ENCHANTMENTS), false);
        addEnchants(stack.get(DataComponents.STORED_ENCHANTMENTS), true);

        var potionContents = stack.get(DataComponents.POTION_CONTENTS);
        String kind = null;
        if (potionContents != null && potionContents.potion().isPresent()) kind = path(potionContents.potion().get().getRegisteredName());
        this.potion = kind;

        this.typeValue = typeDefined(id) ? typeValueOf(stack, id) : null;
    }

    /**
     * The info for a stack, or null for an empty one.
     */
    public static StackInfo of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return new StackInfo(stack);
    }

    private void addEnchants(ItemEnchantments enchantments, boolean stored) {
        if (enchantments == null) return;
        for (var holder : enchantments.keySet()) {
            String name = path(holder.getRegisteredName());
            enchants.add(name);
            (stored ? storedLevels : levels).put(name, enchantments.getLevel(holder));
        }
    }

    private static String path(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    public boolean is(TagKey<net.minecraft.world.item.Item> tag) {
        return stack.is(tag);
    }

    public boolean isShulker() {
        return id.endsWith("shulker_box");
    }

    public boolean isFullStack() {
        return count >= maxStack;
    }

    /** Whether the item is a block with a full collision cube. Worked out when first asked. */
    public boolean fullBlock() {
        if (fullBlock == null) {
            boolean full = false;
            if (stack.getItem() instanceof BlockItem item) {
                try {
                    full = item.getBlock().defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                } catch (RuntimeException e) {
                    full = false;
                }
            }
            fullBlock = full;
        }
        return fullBlock;
    }

    /**
     * What's inside a shulker or other container item. Empty for everything else, and for empty containers.
     */
    public List<StackInfo> contents() {
        if (contents == null) {
            List<StackInfo> out = new ArrayList<>();
            var inside = stack.get(DataComponents.CONTAINER);
            if (inside != null) {
                for (ItemStack s : inside.nonEmptyItems()) {
                    StackInfo info = of(s);
                    if (info != null) out.add(info);
                }
            }
            contents = out;
        }
        return contents;
    }

    private boolean holdsItems() {
        if (!contents().isEmpty()) return true;
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        return bundle != null && !bundle.isEmpty();
    }

    /**
     * For messages and search: the item's own name, and the player's name for it if it has one, like
     * "Diamond Sword (named steak)". Not used for matching.
     */
    public String label() {
        if (!customName) return name;
        return customText == null || customText.isBlank() ? name + " (named)" : name + " (named " + customText + ")";
    }

    /** The label of a stack, or its plain name if it is empty. */
    public static String label(ItemStack stack) {
        StackInfo info = of(stack);
        return info == null ? stack.getItemName().getString() : info.label();
    }

    /**
     * What two stacks have to share to be packed into one shulker: the item, and for items whose id doesn't say what
     * they are, the component value that does. Never enchantments, damage, a name or a trim on gear.
     */
    public record BulkKey(net.minecraft.world.item.Item item, Object typeValue) {
    }

    /**
     * The key stacks are bulked by, or null for shulkers and anything with items inside, which are never repacked.
     */
    public BulkKey bulkKey() {
        // Nothing is ever packed into a bundle, and a bundle is never packed into anything
        if (isShulker() || holdsItems() || stack.has(DataComponents.BUNDLE_CONTENTS)) return null;
        return new BulkKey(stack.getItem(), typeValue);
    }

    private static final Set<String> TYPE_DEFINED = Set.of(
        "potion", "splash_potion", "lingering_potion", "tipped_arrow", "enchanted_book", "suspicious_stew",
        "firework_rocket", "goat_horn", "ominous_bottle", "filled_map", "player_head", "written_book", "firework_star",
        "shield");

    private static boolean typeDefined(String id) {
        return TYPE_DEFINED.contains(id) || id.endsWith("_banner");
    }

    private static Object typeValueOf(ItemStack stack, String id) {
        if (id.endsWith("_banner")) return stack.get(DataComponents.BANNER_PATTERNS);
        return switch (id) {
            case "potion", "splash_potion", "lingering_potion", "tipped_arrow" -> stack.get(DataComponents.POTION_CONTENTS);
            case "enchanted_book" -> stack.get(DataComponents.STORED_ENCHANTMENTS);
            case "suspicious_stew" -> stack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
            case "firework_rocket" -> stack.get(DataComponents.FIREWORKS);
            case "goat_horn" -> stack.get(DataComponents.INSTRUMENT);
            case "filled_map" -> stack.get(DataComponents.MAP_ID);
            case "player_head" -> stack.get(DataComponents.PROFILE);
            case "written_book" -> stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            case "firework_star" -> stack.get(DataComponents.FIREWORK_EXPLOSION);
            case "shield" -> Arrays.asList(stack.get(DataComponents.BASE_COLOR), stack.get(DataComponents.BANNER_PATTERNS));
            default -> stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
        };
    }
}
