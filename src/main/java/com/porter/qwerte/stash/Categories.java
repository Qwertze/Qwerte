package com.porter.qwerte.stash;

import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What players mean by the words they put on signs and type into searches. Maps everyday terms ("redstone stuff",
 * "pvp", "building blocks", "valuables") onto the items they describe, so signs and searches can be written the way
 * people actually talk.
 */
public final class Categories {
    private Categories() {
    }

    /**
     * How well a sign line describes an item. Higher wins: an exact name, then all the words of the line found in the
     * item's name, then a category the item belongs to, then a catch-all. 0 means the line doesn't describe it.
     */
    public static int signScore(String line, ItemStack stack) {
        return signScore(line, describe(stack));
    }

    /**
     * Same as {@link #signScore(String, ItemStack)}, for scoring one item against many lines: the item's normalised
     * name and words are worked out once, and each line's split and normalisation is remembered.
     */
    public static int signScore(String line, Described item) {
        int best = 0;
        for (Part part : prepared(line)) best = Math.max(best, partScore(part, item));
        return best;
    }

    public static Described describe(ItemStack stack) {
        return new Described(stack);
    }

    /**
     * An item with the text derived from it, computed once and only when needed.
     */
    public static final class Described {
        private final ItemStack stack;
        private String name, id;
        private Set<String> words;

        private Described(ItemStack stack) {
            this.stack = stack;
        }

        private String name() {
            if (name == null) name = normalize(stack.getHoverName().getString());
            return name;
        }

        private String id() {
            if (id == null) id = normalize(Categories.id(stack));
            return id;
        }

        private Set<String> words() {
            if (words == null) words = Categories.words(name() + " " + id() + " " + extraWords(stack));
            return words;
        }
    }

    /**
     * A sign line part, normalised once: the label to match and its singular form.
     */
    private record Part(String label, String singularLabel) {
    }

    private static final Map<String, List<Part>> PREPARED = new HashMap<>();

    private static List<Part> prepared(String line) {
        List<Part> parts = PREPARED.get(line);
        if (parts == null) {
            if (PREPARED.size() > 2048) PREPARED.clear();
            parts = new ArrayList<>();
            for (String part : splitList(line)) {
                String label = stripFiller(normalize(part));
                parts.add(new Part(label, label.isEmpty() ? label : singularPhrase(label)));
            }
            PREPARED.put(line, parts);
        }
        return parts;
    }

    /**
     * How well a search query matches an item, 0 if it doesn't.
     */
    public static int searchScore(String query, ItemStack stack) {
        String q = normalize(query);
        if (q.isEmpty()) return 0;

        String name = normalize(stack.getHoverName().getString());
        String id = normalize(id(stack));
        if (q.equals(name) || q.equals(id)) return 100;
        if (name.startsWith(q) || id.startsWith(q)) return 80;
        if (name.contains(q) || id.contains(q)) return 70;

        int score = partScore(q, stack);
        return score == CATCH_ALL_SCORE ? 0 : score;
    }

    private static final int CATCH_ALL_SCORE = 1;

    private static final Set<String> KEEP_WHOLE = Set.of("kit", "pvp kit", "kit shulker", "mixed shulker", "whole shulker",
        "keep whole", "loot shulker", "unsorted shulker");

    /**
     * A sign line asking for mixed shulkers whole, like "kits", instead of having them repacked.
     */
    public static boolean isKeepWhole(String line) {
        for (String part : splitList(line)) {
            if (KEEP_WHOLE.contains(singularPhrase(stripFiller(normalize(part))))) return true;
        }
        return false;
    }

    /**
     * A sign line saying "anything goes here": misc, other, overflow and the like.
     */
    public static boolean isCatchAll(String line) {
        for (String part : splitList(line)) {
            if (CATCH_ALL.contains(stripFiller(normalize(part)))) return true;
        }
        return false;
    }

    private static int partScore(String part, ItemStack stack) {
        String label = stripFiller(normalize(part));
        return partScore(new Part(label, label.isEmpty() ? label : singularPhrase(label)), new Described(stack));
    }

    private static int partScore(Part part, Described item) {
        String label = part.label();
        if (label.isEmpty()) return 0;

        ItemStack stack = item.stack;
        if (label.equals(item.name()) || label.equals(item.id())) return 100;

        // Nicknames: "rockets", "crystals", "obby", "egaps"...
        Set<String> nicknamed = SHORTHANDS.get(part.singularLabel());
        if (nicknamed != null && nicknamed.contains(id(stack))) return 60;

        if (allIn(label, item.words())) return 60;

        Predicate<ItemStack> category = CATEGORIES.get(part.singularLabel());
        if (category != null) return category.test(stack) ? 40 : 0;

        if (CATCH_ALL.contains(label)) return CATCH_ALL_SCORE;

        // A few loose words, like "pvp gear" or "farm crops": any category word that fits
        for (String word : label.split(" ")) {
            Predicate<ItemStack> c = CATEGORIES.get(singular(word));
            if (c != null && c.test(stack)) return 25;
        }

        return 0;
    }

    // Text

    public static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll("[^a-z0-9*& ]", "").replaceAll("\\s+", " ").trim();
    }

    /**
     * "logs, planks & wood" is three things.
     */
    private static List<String> splitList(String line) {
        List<String> parts = new ArrayList<>();
        for (String part : line.toLowerCase(Locale.ROOT).split("[,&/+|;]| and | n ")) {
            if (!part.isBlank()) parts.add(part);
        }
        if (parts.isEmpty()) parts.add(line);
        return parts;
    }

    private static final Set<String> FILLER = Set.of("stuff", "things", "thing", "items", "item", "supplies", "supply",
        "storage", "the", "of", "for", "my", "all", "some", "assorted", "various", "general", "box", "chest", "barrel",
        "here", "only", "extra", "spare", "more");

    private static String stripFiller(String label) {
        StringBuilder out = new StringBuilder();
        for (String word : label.split(" ")) {
            if (word.isEmpty() || FILLER.contains(word)) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(word);
        }
        // "Storage" alone or "Items" alone still means something: keep the original if everything was filler
        return out.isEmpty() ? label : out.toString();
    }

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
        Map.entry("golden", "gold"), Map.entry("wooden", "wood"), Map.entry("stony", "stone"),
        Map.entry("leaf", "leave"), Map.entry("diamonds", "diamond"), Map.entry("netherrite", "netherite"),
        Map.entry("enderpearl", "pearl"), Map.entry("pearls", "pearl"), Map.entry("gapple", "golden apple"),
        Map.entry("xp", "experience"), Map.entry("exp", "experience")
    );

    private static Set<String> words(String text) {
        Set<String> set = new HashSet<>();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            String w = singular(word);
            set.add(w);
            String syn = SYNONYMS.get(w);
            if (syn != null) set.add(syn);
        }
        return set;
    }

    /**
     * Words an item is known by beyond its name: its enchantments (stored ones too, for books) and its potion.
     */
    private static String extraWords(ItemStack stack) {
        StringBuilder out = new StringBuilder();
        for (var type : List.of(DataComponents.ENCHANTMENTS, DataComponents.STORED_ENCHANTMENTS)) {
            var enchants = stack.get(type);
            if (enchants == null) continue;
            for (var holder : enchants.keySet()) out.append(' ').append(path(holder.getRegisteredName()));
        }

        var potion = stack.get(DataComponents.POTION_CONTENTS);
        if (potion != null) potion.potion().ifPresent(holder -> out.append(' ').append(path(holder.getRegisteredName())));

        return normalize(out.toString());
    }

    private static String path(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /**
     * The item a shulker is mostly full of: whichever takes up the most slots, then the most items. Null for anything
     * that isn't a shulker with items in it.
     */
    public static ItemStack dominantContent(ItemStack shulker) {
        if (!Utils.isShulker(shulker.getItem())) return null;

        ItemStack[] contents = new ItemStack[27];
        Utils.getItemsInContainerItem(shulker, contents);
        return mostCommon(contents);
    }

    /**
     * The item taking up the most slots, then the most items, or null if there's nothing. Empty and null slots are
     * skipped.
     */
    public static ItemStack mostCommon(ItemStack[] contents) {
        Map<net.minecraft.world.item.Item, int[]> tally = new HashMap<>();
        Map<net.minecraft.world.item.Item, ItemStack> sample = new HashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) continue;
            int[] t = tally.computeIfAbsent(stack.getItem(), i -> new int[2]);
            t[0]++;
            t[1] += stack.getCount();
            sample.putIfAbsent(stack.getItem(), stack);
        }

        net.minecraft.world.item.Item best = null;
        for (var entry : tally.entrySet()) {
            int[] t = entry.getValue();
            if (best == null || t[0] > tally.get(best)[0] || (t[0] == tally.get(best)[0] && t[1] > tally.get(best)[1])) best = entry.getKey();
        }
        return best == null ? null : sample.get(best);
    }

    private static boolean allIn(String label, Set<String> words) {
        for (String word : label.split(" ")) {
            if (word.isEmpty()) continue;
            String w = singular(word);
            String syn = SYNONYMS.get(w);
            if (!words.contains(w) && (syn == null || !words.contains(syn))) return false;
        }
        return true;
    }

    public static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 4) return word.substring(0, word.length() - 3) + "y";
        if (word.endsWith("oes") && word.length() > 4) return word.substring(0, word.length() - 2);
        if (word.endsWith("ves") && word.length() > 4) return word.substring(0, word.length() - 3) + "f";
        if (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes")) return word.substring(0, word.length() - 2);
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us")) return word.substring(0, word.length() - 1);
        return word;
    }

    private static String singularPhrase(String phrase) {
        StringBuilder out = new StringBuilder();
        for (String word : phrase.split(" ")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(singular(word));
        }
        return out.toString();
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    // Categories

    private static final Map<String, Set<String>> SHORTHANDS = new HashMap<>();

    /**
     * Short names players use for an item, mapped to the items they mean. Stored singular, so plurals work too.
     */
    private static void nick(String items, String... names) {
        Set<String> ids = Set.of(items.split(","));
        for (String name : names) SHORTHANDS.put(singularPhrase(normalize(name)), ids);
    }

    static {
        nick("firework_rocket", "rocket", "firework", "fireworks", "rockets", "elytra rockets", "flight", "boost", "boosts");
        nick("firework_star", "firework star", "star");
        nick("end_crystal", "crystal", "end crystal", "crystals", "cry", "crys");
        nick("ender_pearl", "pearl", "ep", "epearl", "ender pearl");
        nick("ender_eye", "eye", "eye of ender", "eyes of ender", "ender eye");
        nick("obsidian,crying_obsidian", "obby", "obbie", "obsi", "obs");
        nick("golden_apple", "gap", "gapple", "golden apple", "gold apple", "gaps");
        nick("enchanted_golden_apple", "egap", "egapple", "god apple", "notch apple", "notch", "enchanted apple", "egaps");
        nick("totem_of_undying", "totem", "tot", "tots", "totems", "undying");
        nick("experience_bottle", "xp", "exp", "xp bottle", "exp bottle", "bottle", "experience", "bottle o enchanting", "xp bottles");
        nick("respawn_anchor", "anchor", "anchors");
        nick("glowstone", "glowstone", "anchor fuel");
        nick("cooked_beef", "steak", "steaks");
        nick("porkchop,cooked_porkchop", "pork", "porkchop", "chops");
        nick("cooked_beef,cooked_porkchop,cooked_chicken,cooked_mutton,cooked_rabbit,cooked_cod,cooked_salmon", "cooked", "cooked food", "cooked meat");
        nick("golden_carrot", "gold carrot", "golden carrot", "gcarrot", "gcarrots");
        nick("shulker_shell", "shell", "shulker shell");
        nick("cobblestone,cobbled_deepslate", "cobble", "cobbles");
        nick("netherite_scrap", "scrap", "netherite scrap");
        nick("ancient_debris", "debris", "ancient debris");
        nick("netherite_ingot", "netherite ingot", "neth", "neth ingot");
        nick("slime_ball,slime_block", "slime", "slimeball", "slimes");
        nick("honey_bottle,honey_block,honeycomb", "honey");
        nick("ender_chest", "echest", "e chest", "enderchest", "ender chest");
        nick("diamond", "dia", "dias", "diamonds", "diamond gem");
        nick("emerald", "em", "ems", "emmy", "emmies", "emerald");
        nick("lapis_lazuli", "lapis", "lapis lazuli");
        nick("redstone", "redstone dust", "dust");
        nick("gunpowder", "gp", "powder", "sulphur", "sulfur");
        nick("blaze_rod", "blaze rod", "blazerod");
        nick("tnt", "tnt", "dynamite");
        nick("cobweb", "web", "webs", "cobweb");
        nick("bone_meal", "bonemeal", "bm");
        nick("wind_charge", "wind charge", "charge");
        nick("trident", "trident", "trident");
        nick("elytra", "elytra", "elytras", "wing", "wings");
        nick("shield", "shield", "shields");
        nick("name_tag", "nametag", "name tag", "tag");
        nick("saddle", "saddle");
        nick("heart_of_the_sea", "heart", "hots");
        nick("nether_star", "star", "nether star");
        nick("dragon_egg", "egg", "dragon egg");
        nick("beacon", "beacon");
        nick("enchanted_book", "ebook", "e book", "enchanted book", "book");
        nick("splash_potion", "splash", "splash potion");
        nick("lingering_potion", "lingering", "lingering potion");
        nick("chorus_fruit", "chorus", "chorus fruit");
        nick("sea_lantern", "sea lantern", "sealantern");
        nick("hay_block", "hay", "hay bale");
        nick("iron_ingot", "iron", "iron ingot");
        nick("gold_ingot", "gold", "gold ingot");
        nick("copper_ingot", "copper", "copper ingot");
    }

    private static final Set<String> CATCH_ALL = Set.of("misc", "miscellaneous", "other", "others", "*", "overflow",
        "everything", "anything", "random", "rest", "leftover", "leftovers", "unsorted", "mixed", "general");

    private static final Map<String, Predicate<ItemStack>> CATEGORIES = new HashMap<>();

    /**
     * Registers a category under all its names. Names are stored singular, so "tools" and "tool" both work.
     */
    private static void add(Predicate<ItemStack> test, String... names) {
        for (String name : names) CATEGORIES.put(singularPhrase(normalize(name)), test);
    }

    private static Predicate<ItemStack> ids(String... parts) {
        return stack -> {
            String id = id(stack);
            for (String part : parts) if (id.contains(part)) return true;
            return false;
        };
    }

    private static Predicate<ItemStack> exact(String... names) {
        Set<String> set = Set.of(names);
        return stack -> set.contains(id(stack));
    }

    private static Predicate<ItemStack> suffix(String... suffixes) {
        return stack -> {
            String id = id(stack);
            for (String s : suffixes) if (id.endsWith(s)) return true;
            return false;
        };
    }

    private static boolean shulkerWithItems(ItemStack stack) {
        if (!Utils.isShulker(stack.getItem())) return false;
        ItemStack[] contents = new ItemStack[27];
        Utils.getItemsInContainerItem(stack, contents);
        for (ItemStack s : contents) if (s != null && !s.isEmpty()) return true;
        return false;
    }

    private static boolean fullBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item)) return false;
        try {
            return item.getBlock().defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean enchanted(ItemStack stack) {
        var enchants = stack.get(DataComponents.ENCHANTMENTS);
        return enchants != null && !enchants.isEmpty();
    }

    static {
        Predicate<ItemStack> shulker = s -> Utils.isShulker(s.getItem());
        Predicate<ItemStack> pickaxe = s -> s.is(ItemTags.PICKAXES);
        Predicate<ItemStack> axe = s -> s.is(ItemTags.AXES);
        Predicate<ItemStack> shovel = s -> s.is(ItemTags.SHOVELS);
        Predicate<ItemStack> hoe = s -> s.is(ItemTags.HOES);
        Predicate<ItemStack> sword = s -> s.is(ItemTags.SWORDS);
        Predicate<ItemStack> tools = pickaxe.or(axe).or(shovel).or(hoe).or(exact("shears", "flint_and_steel", "fishing_rod", "brush"));
        Predicate<ItemStack> armor = suffix("_helmet", "_chestplate", "_leggings", "_boots").or(exact("elytra", "turtle_helmet", "shield", "wolf_armor"));
        Predicate<ItemStack> weapons = sword.or(axe).or(exact("bow", "crossbow", "trident", "mace")).or(suffix("arrow"));
        Predicate<ItemStack> food = s -> s.has(DataComponents.FOOD);
        Predicate<ItemStack> ores = ids("_ore", "raw_", "ancient_debris");
        Predicate<ItemStack> ingots = suffix("_ingot", "_nugget").or(exact("netherite_scrap", "copper_ingot"));
        Predicate<ItemStack> gems = exact("diamond", "emerald", "lapis_lazuli", "amethyst_shard", "quartz", "prismarine_crystals", "echo_shard");
        Predicate<ItemStack> storageBlocks = exact("iron_block", "gold_block", "diamond_block", "emerald_block", "lapis_block", "redstone_block",
            "netherite_block", "copper_block", "coal_block", "raw_iron_block", "raw_gold_block", "raw_copper_block", "amethyst_block", "quartz_block");
        Predicate<ItemStack> valuables = ids("diamond", "netherite", "emerald").or(exact("totem_of_undying", "elytra", "nether_star", "beacon",
            "enchanted_golden_apple", "dragon_egg", "dragon_head", "trident", "heart_of_the_sea", "shulker_shell", "enchanted_book", "mace",
            "heavy_core", "conduit", "ancient_debris")).or(shulker);
        Predicate<ItemStack> redstone = exact("redstone", "repeater", "comparator", "piston", "sticky_piston", "observer", "hopper", "dropper",
            "dispenser", "lever", "daylight_detector", "target", "tripwire_hook", "redstone_torch", "redstone_lamp", "note_block", "tnt",
            "slime_block", "honey_block", "crafter", "sculk_sensor", "calibrated_sculk_sensor", "lightning_rod", "trapped_chest", "redstone_block",
            "jukebox", "bell").or(suffix("_button", "_pressure_plate", "rail", "_door", "_trapdoor", "_fence_gate"));
        Predicate<ItemStack> rails = suffix("rail");
        Predicate<ItemStack> transport = ids("boat", "raft", "minecart").or(rails).or(exact("saddle", "elytra", "firework_rocket",
            "carrot_on_a_stick", "warped_fungus_on_a_stick", "lead")).or(suffix("_horse_armor"));
        Predicate<ItemStack> light = ids("torch", "lantern", "froglight", "candle").or(exact("glowstone", "sea_lantern", "shroomlight",
            "redstone_lamp", "end_rod", "beacon", "jack_o_lantern", "glow_berries", "glow_lichen"));
        Predicate<ItemStack> heads = suffix("_head", "_skull");
        Predicate<ItemStack> decoration = exact("painting", "item_frame", "glow_item_frame", "flower_pot", "armor_stand", "decorated_pot",
            "chain", "bell", "lantern", "soul_lantern").or(suffix("_banner", "_carpet", "_candle", "candle", "_bed")).or(heads)
            .or(ids("pottery_sherd", "banner_pattern"));
        Predicate<ItemStack> wool = suffix("_wool");
        Predicate<ItemStack> dyes = suffix("_dye");
        Predicate<ItemStack> glass = ids("glass").and(s -> !id(s).equals("glass_bottle"));
        Predicate<ItemStack> concrete = ids("concrete");
        Predicate<ItemStack> terracotta = ids("terracotta");
        Predicate<ItemStack> colorful = wool.or(dyes).or(concrete).or(terracotta).or(suffix("_stained_glass", "_stained_glass_pane", "_carpet",
            "_bed", "_banner", "_candle", "_shulker_box"));
        Predicate<ItemStack> logs = s -> s.is(ItemTags.LOGS);
        Predicate<ItemStack> planks = s -> s.is(ItemTags.PLANKS);
        Predicate<ItemStack> wood = logs.or(planks).or(ids("_wood", "_hyphae", "bamboo_block", "stripped_"))
            .or(s -> {
                String id = id(s);
                return (id.endsWith("_stairs") || id.endsWith("_slab") || id.endsWith("_fence")) && (id.startsWith("oak") || id.startsWith("spruce")
                    || id.startsWith("birch") || id.startsWith("jungle") || id.startsWith("acacia") || id.startsWith("dark_oak")
                    || id.startsWith("mangrove") || id.startsWith("cherry") || id.startsWith("pale_oak") || id.startsWith("bamboo")
                    || id.startsWith("crimson") || id.startsWith("warped"));
            });
        Predicate<ItemStack> stone = s -> {
            String id = id(s);
            if (id.contains("redstone") || id.contains("glowstone") || id.contains("lodestone") || id.contains("grindstone") || id.contains("stonecutter")) return false;
            return s.getItem() instanceof BlockItem && (id.contains("stone") || id.contains("deepslate") || id.contains("andesite")
                || id.contains("diorite") || id.contains("granite") || id.contains("tuff") || id.contains("calcite") || id.contains("basalt")
                || id.contains("cobble"));
        };
        Predicate<ItemStack> dirt = exact("dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "mud", "muddy_mangrove_roots",
            "packed_mud", "moss_block", "pale_moss_block", "clay", "farmland", "dirt_path");
        Predicate<ItemStack> sand = exact("sand", "red_sand", "gravel", "suspicious_sand", "suspicious_gravel").or(ids("sandstone"));
        Predicate<ItemStack> saplings = ids("sapling", "propagule", "azalea");
        Predicate<ItemStack> flowers = exact("dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
            "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony", "torchflower", "pitcher_plant", "pink_petals",
            "spore_blossom", "open_eyeblossom", "closed_eyeblossom", "wildflowers", "cactus_flower").or(suffix("_tulip"));
        Predicate<ItemStack> seeds = suffix("_seeds").or(exact("pitcher_pod", "torchflower_seeds"));
        Predicate<ItemStack> crops = exact("wheat", "carrot", "potato", "beetroot", "melon_slice", "melon", "pumpkin", "sugar_cane", "cocoa_beans",
            "nether_wart", "sweet_berries", "glow_berries", "cactus", "bamboo", "kelp", "hay_block", "chorus_fruit").or(seeds);
        Predicate<ItemStack> plants = saplings.or(flowers).or(seeds).or(ids("leaves", "vine", "moss", "fern", "grass", "lily_pad", "mushroom",
            "fungus", "roots", "dripleaf", "sea_pickle", "seagrass", "coral", "bush", "cactus", "sugar_cane", "bamboo", "kelp", "hanging_roots"));
        Predicate<ItemStack> mobDrops = exact("bone", "bone_meal", "string", "gunpowder", "spider_eye", "rotten_flesh", "ender_pearl", "slime_ball",
            "blaze_rod", "blaze_powder", "ghast_tear", "phantom_membrane", "feather", "leather", "rabbit_hide", "rabbit_foot", "ink_sac",
            "glow_ink_sac", "magma_cream", "shulker_shell", "prismarine_shard", "prismarine_crystals", "nautilus_shell", "turtle_scute",
            "scute", "armadillo_scute", "breeze_rod", "honeycomb", "egg", "wither_skeleton_skull", "nether_star", "dragon_breath", "arrow");
        Predicate<ItemStack> brewing = exact("potion", "splash_potion", "lingering_potion", "glass_bottle", "brewing_stand", "blaze_powder",
            "nether_wart", "fermented_spider_eye", "glistering_melon_slice", "magma_cream", "ghast_tear", "rabbit_foot", "dragon_breath",
            "phantom_membrane", "sugar", "glowstone_dust", "golden_carrot", "pufferfish", "turtle_helmet", "cauldron", "breeze_rod");
        Predicate<ItemStack> potions = exact("potion", "splash_potion", "lingering_potion", "tipped_arrow");
        Predicate<ItemStack> books = exact("book", "writable_book", "written_book", "enchanted_book", "bookshelf", "chiseled_bookshelf", "knowledge_book");
        Predicate<ItemStack> ebooks = exact("enchanted_book");
        Predicate<ItemStack> pvp = exact("end_crystal", "obsidian", "crying_obsidian", "respawn_anchor", "glowstone", "totem_of_undying",
            "golden_apple", "enchanted_golden_apple", "ender_pearl", "experience_bottle", "chorus_fruit", "cobweb", "bow", "crossbow", "mace",
            "wind_charge").or(sword).or(armor).or(potions).or(suffix("arrow"));
        Predicate<ItemStack> gapples = exact("golden_apple", "enchanted_golden_apple");
        Predicate<ItemStack> nether = ids("nether", "crimson", "warped", "blackstone", "basalt", "soul_", "quartz", "glowstone", "magma",
            "shroomlight", "ancient_debris", "blaze", "ghast", "wither").or(exact("netherrack", "gold_nugget"));
        Predicate<ItemStack> end = ids("end_stone", "purpur", "chorus", "end_rod", "shulker", "dragon", "ender_eye", "end_crystal").or(exact("elytra"));
        Predicate<ItemStack> ocean = ids("prismarine", "coral", "kelp", "sponge", "sea_", "nautilus", "turtle", "trident", "conduit",
            "heart_of_the_sea", "_bucket", "fish", "cod", "salmon", "squid", "seagrass");
        Predicate<ItemStack> fish = exact("cod", "salmon", "tropical_fish", "pufferfish", "cooked_cod", "cooked_salmon");
        Predicate<ItemStack> meat = exact("beef", "cooked_beef", "porkchop", "cooked_porkchop", "chicken", "cooked_chicken", "mutton",
            "cooked_mutton", "rabbit", "cooked_rabbit", "rotten_flesh");
        Predicate<ItemStack> music = s -> s.has(DataComponents.JUKEBOX_PLAYABLE) || id(s).startsWith("disc_fragment");
        Predicate<ItemStack> eggs = suffix("_spawn_egg");
        Predicate<ItemStack> utility = exact("shears", "flint_and_steel", "fishing_rod", "lead", "name_tag", "compass", "recovery_compass",
            "clock", "spyglass", "bucket", "water_bucket", "lava_bucket", "brush", "map", "filled_map", "saddle", "bundle");
        Predicate<ItemStack> buckets = suffix("bucket");
        Predicate<ItemStack> junk = exact("rotten_flesh", "poisonous_potato", "dirt", "cobblestone", "cobbled_deepslate", "netherrack", "gravel",
            "andesite", "diorite", "granite", "tuff", "spider_eye", "bone", "string", "stick", "wheat_seeds", "flint", "sand", "kelp",
            "dead_bush", "leather_boots", "leather_helmet", "fishing_rod", "bowl", "lily_pad", "seagrass");
        Predicate<ItemStack> workstations = exact("crafting_table", "furnace", "blast_furnace", "smoker", "anvil", "chipped_anvil",
            "damaged_anvil", "enchanting_table", "grindstone", "stonecutter", "smithing_table", "loom", "cartography_table",
            "fletching_table", "brewing_stand", "cauldron", "composter", "barrel", "lectern", "beehive", "bee_nest", "crafter");
        Predicate<ItemStack> containers = exact("chest", "trapped_chest", "barrel", "ender_chest", "hopper", "dropper", "dispenser", "bundle").or(shulker);
        Predicate<ItemStack> building = s -> fullBlock(s) || suffix("_stairs", "_slab", "_wall").test(s);
        Predicate<ItemStack> templates = ids("smithing_template", "armor_trim");
        Predicate<ItemStack> stackable64 = s -> s.getMaxStackSize() == 64;
        Predicate<ItemStack> stackable16 = s -> s.getMaxStackSize() == 16;
        Predicate<ItemStack> singles = s -> s.getMaxStackSize() == 1;

        add(shulker, "shulker", "shulker box", "shulk", "shulkers box", "sb");
        add(Categories::shulkerWithItems, "full shulker", "filled shulker", "packed shulker", "kit", "loaded shulker", "stocked shulker");
        add(shulker.and(s -> !shulkerWithItems(s)), "empty shulker", "empty box", "spare shulker");
        add(food, "food", "eat", "eats", "snack", "grub", "ration", "meal", "edible", "consumable", "nom");
        add(meat, "meat", "raw meat", "cooked meat", "steak", "protein");
        add(fish, "fish", "seafood");
        add(gapples, "gapple", "golden apple", "gap", "egap", "god apple", "notch apple", "enchanted golden apple");
        add(crops, "crop", "farming", "farm", "harvest", "produce", "veggie", "vegetable", "agriculture", "grain");
        add(seeds, "seed");
        add(saplings, "sapling", "tree", "propagule");
        add(flowers, "flower", "bouquet", "petal");
        add(plants, "plant", "nature", "greenery", "foliage", "garden", "gardening", "vegetation", "organic", "botany");
        add(tools, "tool", "toolkit", "equipment", "implement");
        add(pickaxe, "pickaxe", "pick", "picks", "pickaxes");
        add(axe, "axe", "hatchet");
        add(shovel, "shovel", "spade");
        add(hoe, "hoe");
        add(sword, "sword", "blade");
        add(weapons, "weapon", "combat", "fighting", "melee", "ranged", "arsenal");
        add(armor, "armor", "armour", "gear", "kit armor", "protection", "wearable", "suit");
        add(suffix("_helmet").or(exact("turtle_helmet")), "helmet", "helm", "cap", "hat");
        add(suffix("_chestplate").or(exact("elytra")), "chestplate", "chest plate", "chestpiece");
        add(suffix("_leggings"), "legging", "pant", "leg");
        add(suffix("_boots"), "boot", "shoe");
        add(exact("shield"), "shield");
        add(exact("elytra"), "elytra", "wing", "elytra wing");
        add(suffix("arrow"), "arrow", "ammo", "ammunition");
        add(exact("bow", "crossbow"), "bow", "crossbow", "archery");
        add(Categories::enchanted, "enchanted", "enchanted gear", "godset", "god set", "maxed", "max", "op gear", "enchants");
        add(ebooks, "enchanted book", "ebook", "e book", "enchantment book", "librarian");
        add(books, "book", "library", "reading", "lore");
        add(potions, "potion", "pot", "splash", "brew");
        add(brewing, "brewing", "alchemy", "brewing ingredient", "potion ingredient");
        add(exact("experience_bottle"), "experience", "experience bottle", "xp bottle", "exp bottle", "bottle o enchanting", "xp", "exp");
        add(pvp, "pvp", "crystal pvp", "cpvp", "crystalpvp", "pvp kit", "pvp gear", "fight", "war", "anarchy kit");
        add(exact("end_crystal"), "crystal", "end crystal", "crystals");
        add(exact("obsidian", "crying_obsidian"), "obsidian", "obby", "obsi");
        add(exact("totem_of_undying"), "totem", "totem of undying", "totems", "tots");
        add(exact("ender_pearl"), "pearl", "ender pearl", "epearl", "enderpearl");
        add(exact("respawn_anchor", "glowstone"), "anchor", "respawn anchor", "anchor pvp");
        add(ores, "ore", "raw", "raw ore", "mining", "mined", "mineral");
        add(ingots, "ingot", "metal", "bar", "nugget", "smelted");
        add(gems, "gem", "jewel", "crystal shard", "precious");
        add(storageBlocks, "storage block", "mineral block", "compressed", "metal block", "resource block");
        add(valuables, "valuable", "loot", "treasure", "rare", "precious", "expensive", "important", "dupe", "vault", "safe", "goodies", "best");
        add(ids("diamond"), "diamond", "dias", "diamond stuff");
        add(ids("netherite"), "netherite", "nethe", "netherite stuff");
        add(redstone, "redstone", "redstone component", "circuit", "circuitry", "tech", "technical", "mechanic", "mechanism", "contraption",
            "automation", "machine", "engineering", "logic", "wiring");
        add(rails, "rail", "railway", "track");
        add(transport, "transport", "transportation", "travel", "vehicle", "boat", "minecart", "cart", "ride", "mount", "horse");
        add(exact("firework_rocket", "firework_star"), "firework", "rocket", "fireworks", "elytra fuel", "boost");
        add(light, "light", "lighting", "torch", "lamp", "lantern", "illumination", "glow");
        add(decoration, "decoration", "decor", "deco", "decorative", "furniture", "interior", "aesthetic", "pretty");
        add(heads, "head", "skull", "trophy", "mob head");
        add(suffix("_banner"), "banner", "flag");
        add(suffix("_carpet"), "carpet", "rug");
        add(suffix("_bed"), "bed");
        add(suffix("candle"), "candle");
        add(suffix("_sign", "_hanging_sign"), "sign", "hanging sign");
        add(wool, "wool", "fabric", "cloth");
        add(dyes, "dye", "color", "colour", "paint", "pigment");
        add(colorful, "colored", "coloured", "colorful", "colourful", "rainbow", "dyed");
        add(glass, "glass", "pane", "window");
        add(concrete, "concrete", "concrete powder", "cement");
        add(terracotta, "terracotta", "terra cotta", "clay block", "glazed");
        add(logs, "log", "trunk", "timber");
        add(planks, "plank", "board");
        add(wood, "wood", "lumber", "woodwork", "wooden");
        add(stone, "stone", "rock", "cobble", "cobblestone", "masonry", "brick", "stonework", "deepslate");
        add(dirt, "dirt", "soil", "earth", "mud", "ground");
        add(sand, "sand", "gravel", "desert");
        add(building, "building", "building block", "build", "builder", "construction", "block", "material", "mat", "bulk");
        add(suffix("_stairs"), "stair", "step");
        add(suffix("_slab"), "slab", "half slab");
        add(suffix("_wall"), "wall");
        add(suffix("_fence", "_fence_gate"), "fence", "gate", "fencing");
        add(suffix("_door", "_trapdoor"), "door", "trapdoor", "hatch");
        add(mobDrops, "mob drop", "drop", "mob loot", "monster drop", "grinder", "farm drop", "spawner");
        add(exact("bone", "bone_meal", "bone_block"), "bone", "bonemeal", "bone meal", "fertilizer");
        add(exact("gunpowder", "tnt", "firework_star"), "gunpowder", "explosive", "boom", "sulphur");
        add(exact("string", "cobweb", "white_wool"), "string", "web", "cobweb");
        add(nether, "nether", "hell", "netherrack stuff");
        add(end, "end", "end stuff", "ender", "end city", "the end");
        add(ocean, "ocean", "aquatic", "water", "sea", "marine", "underwater", "monument");
        add(music, "music", "disc", "record", "music disc", "vinyl", "song");
        add(eggs, "spawn egg", "egg", "mob egg");
        add(utility, "utility", "util", "misc tool", "handy");
        add(buckets, "bucket", "pail");
        add(exact("map", "filled_map", "compass", "recovery_compass", "clock", "spyglass"), "map", "navigation", "exploration", "explorer");
        add(workstations, "workstation", "station", "crafting", "utility block", "job block", "furnace");
        add(containers, "container", "chest", "storage container");
        add(templates, "template", "smithing template", "trim", "armor trim", "upgrade");
        add(s -> s.has(DataComponents.CUSTOM_NAME), "named", "renamed", "custom", "named item");
        add(ItemStack::isDamaged, "damaged", "broken", "used", "worn", "repair", "needs repair", "to repair");
        add(junk, "junk", "trash", "garbage", "rubbish", "useless", "scrap", "filler", "dump");
        add(stackable64, "stackable", "stack", "x64", "64", "64s", "full stack");
        add(stackable16, "x16", "16", "16s", "small stack", "half stackable");
        add(singles, "single", "unstackable", "non stackable", "nonstackable", "x1", "1s", "one");
    }
}
