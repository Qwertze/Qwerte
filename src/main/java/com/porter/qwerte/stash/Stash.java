package com.porter.qwerte.stash;

import com.porter.qwerte.QwerteAddon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * A named stash: its containers, what was in them when last seen, and which ones are excluded from sorting or used as
 * drop-off points. Saved per server under {@code meteor-client/qwerte/stashes}, so a stash is only scanned once and
 * then kept up to date as containers are opened.
 */
public class Stash {
    public static class Box {
        public final BlockPos pos;
        public final List<BlockPos> parts;
        public final List<String> labels = new ArrayList<>();
        public ItemStack[] contents;
        public boolean excluded;
        public boolean dropOff;
        public boolean unreachable;
        public int order;
        // The text of the sign the sorter put up on this container itself, and where, saved with the stash
        public String autoLabel;
        public BlockPos autoSignPos;
        // Putting a sign on it failed this run
        public boolean labelFailed;
        // Never used to gather stacks or shulkers for compacting
        public boolean noCompact;

        public Box(BlockPos pos, List<BlockPos> parts) {
            this.pos = pos;
            this.parts = parts;
        }

        public boolean known() {
            return contents != null;
        }

        public int free() {
            int count = 0;
            for (ItemStack stack : contents) if (stack.isEmpty()) count++;
            return count;
        }

        /**
         * Joins in sorting: not excluded, read at least once and possible to open.
         */
        public boolean sortable() {
            return known() && !unreachable && !excluded;
        }

        public boolean has(BlockPos pos) {
            return parts.contains(pos);
        }
    }

    /**
     * A shulker with stacks in it the sorter put down and could not pick up again.
     */
    public static class Orphan {
        public final BlockPos pos;
        public final String item;
        public final int stacks;
        public final String when;
        // The dimension it stands in, empty if unknown
        public final String dimension;

        public Orphan(BlockPos pos, String item, int stacks, String when, String dimension) {
            this.pos = pos;
            this.item = item;
            this.stacks = stacks;
            this.when = when;
            this.dimension = dimension == null ? "" : dimension;
        }
    }

    public final List<Orphan> orphans = new ArrayList<>();
    public final String name;
    public final String server;
    public final List<Box> boxes = new ArrayList<>();
    private boolean dirty;

    // The perimeter: two corners (inclusive), either may be unset. It only applies once both are
    public BlockPos corner1, corner2;

    public static final int MAX_X = 256, MAX_Y = 128, MAX_Z = 256;

    public String areaDimension = "";
    private static Object cachedLevel;
    private static String cachedDimension = "";

    /** The dimension of the level we are in, cached per level. */
    public static String currentDimension() {
        if (mc.level == null) return "";
        if (mc.level != cachedLevel) {
            cachedLevel = mc.level;
            cachedDimension = mc.level.dimension().toString();
        }
        return cachedDimension;
    }

    /** Both corners are set, whatever dimension they are in. */
    public boolean hasCorners() {
        return corner1 != null && corner2 != null;
    }

    /** The perimeter applies: both corners are set, and we are in the dimension they were set in. */
    public boolean hasArea() {
        return corner1 != null && corner2 != null && (areaDimension.isEmpty() || areaDimension.equals(currentDimension()));
    }

    public BlockPos areaMin() {
        return new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
    }

    public BlockPos areaMax() {
        return new BlockPos(Math.max(corner1.getX(), corner2.getX()), Math.max(corner1.getY(), corner2.getY()), Math.max(corner1.getZ(), corner2.getZ()));
    }

    /**
     * Whether the position is inside the perimeter, grown by {@code margin} blocks on every side. False without one.
     */
    public boolean inArea(double x, double y, double z, int margin) {
        if (!hasArea()) return false;
        BlockPos min = areaMin(), max = areaMax();
        return x >= min.getX() - margin && x < max.getX() + 1 + margin && y >= min.getY() - margin && y < max.getY() + 1 + margin
            && z >= min.getZ() - margin && z < max.getZ() + 1 + margin;
    }

    public boolean inArea(BlockPos pos) {
        return inArea(pos.getX(), pos.getY(), pos.getZ(), 0);
    }

    /** Width, height and depth of the perimeter in blocks. */
    public int[] areaSize() {
        BlockPos min = areaMin(), max = areaMax();
        return new int[] { max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1 };
    }

    /** Whether the two corners would span more than the most allowed. */
    public static boolean tooBig(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + 1 > MAX_X || Math.abs(a.getY() - b.getY()) + 1 > MAX_Y || Math.abs(a.getZ() - b.getZ()) + 1 > MAX_Z;
    }

    /** "[x1, y1, z1] to [x2, y2, z2] (W x H x D)" for the perimeter. */
    public String areaText() {
        BlockPos min = areaMin(), max = areaMax();
        int[] size = areaSize();
        return String.format("[%d, %d, %d] to [%d, %d, %d] (%d\u00d7%d\u00d7%d)", min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ(), size[0], size[1], size[2]);
    }

    public Stash(String name, String server) {
        this.name = name;
        this.server = server;
    }

    public Box boxAt(BlockPos pos) {
        for (Box box : boxes) if (box.has(pos)) return box;
        return null;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public int knownCount() {
        return (int) boxes.stream().filter(Box::known).count();
    }

    public int itemCount() {
        int count = 0;
        for (Box box : boxes) {
            if (!box.known()) continue;
            for (ItemStack stack : box.contents) count += stack.getCount();
        }
        return count;
    }

    // Searching

    /**
     * One kind of item found in the stash: how many in total, and where.
     */
    public static class Result {
        public final ItemStack sample;
        public final List<Spot> spots = new ArrayList<>();
        public int total;
        int score;

        Result(ItemStack sample) {
            this.sample = sample.copyWithCount(1);
        }

        public Spot best() {
            return spots.stream().max(Comparator.comparingInt(s -> s.count)).orElse(null);
        }
    }

    public record Spot(Box box, int count, boolean inShulker) {}

    /**
     * Everything matching {@code query}, best match first, counting items inside shulkers too.
     */
    public List<Result> search(String query) {
        List<Result> results = new ArrayList<>();
        if (Categories.normalize(query).isEmpty()) return results;

        for (Box box : boxes) {
            if (!box.known()) continue;

            for (ItemStack stack : box.contents) {
                if (stack.isEmpty()) continue;
                add(results, query, stack, box, false);

                if (Utils.isShulker(stack.getItem())) {
                    ItemStack[] inside = new ItemStack[27];
                    Utils.getItemsInContainerItem(stack, inside);
                    for (ItemStack inner : inside) {
                        if (inner != null && !inner.isEmpty()) add(results, query, inner, box, true);
                    }
                }
            }
        }

        results.sort(Comparator.<Result>comparingInt(r -> -r.score).thenComparingInt(r -> -r.total));
        return results;
    }

    private static void add(List<Result> results, String query, ItemStack stack, Box box, boolean inShulker) {
        int score = Categories.searchScore(query, stack);
        if (score <= 0) return;

        Result result = null;
        for (Result r : results) {
            if (ItemStack.isSameItemSameComponents(r.sample, stack)) {
                result = r;
                break;
            }
        }
        if (result == null) {
            result = new Result(stack);
            result.score = score;
            results.add(result);
        }

        result.total += stack.getCount();
        Spot spot = null;
        for (Spot s : result.spots) {
            if (s.box() == box && s.inShulker() == inShulker) {
                spot = s;
                break;
            }
        }
        if (spot != null) result.spots.set(result.spots.indexOf(spot), new Spot(box, spot.count() + stack.getCount(), inShulker));
        else result.spots.add(new Spot(box, stack.getCount(), inShulker));
    }

    // Saving and loading

    private static Path folder() {
        return MeteorClient.FOLDER.toPath().resolve("qwerte").resolve("stashes");
    }

    private static Path file(String name, String server) {
        String safe = (server + "_" + name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return folder().resolve(safe + ".nbt");
    }

    public static String currentServer() {
        if (mc.isSingleplayer()) return "singleplayer";
        return mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "unknown";
    }

    public static boolean exists(String name) {
        return Files.exists(file(name, currentServer()));
    }

    public static void delete(String name) {
        try {
            Files.deleteIfExists(file(name, currentServer()));
        } catch (IOException e) {
            QwerteAddon.LOG.error("Couldn't delete stash {}", name, e);
        }
    }

    public void save() {
        if (mc.level == null) return;
        RegistryOps<Tag> ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);

        CompoundTag root = new CompoundTag();
        root.putString("name", name);
        root.putString("server", server);

        ListTag list = new ListTag();
        for (Box box : boxes) {
            CompoundTag tag = new CompoundTag();
            tag.put("parts", positions(box.parts));
            tag.putBoolean("excluded", box.excluded);
            tag.putBoolean("dropOff", box.dropOff);
            if (box.noCompact) tag.putBoolean("noCompact", true);
            if (box.autoLabel != null) tag.putString("autoLabel", box.autoLabel);
            if (box.autoSignPos != null) tag.put("autoSignPos", positions(List.of(box.autoSignPos)));

            if (box.known()) {
                tag.putInt("size", box.contents.length);
                ListTag items = new ListTag();
                for (int i = 0; i < box.contents.length; i++) {
                    if (box.contents[i].isEmpty()) continue;
                    int slot = i;
                    ItemStack.CODEC.encodeStart(ops, box.contents[i]).result().ifPresent(item -> {
                        CompoundTag entry = new CompoundTag();
                        entry.putInt("slot", slot);
                        entry.put("item", item);
                        items.add(entry);
                    });
                }
                tag.put("items", items);
            }

            list.add(tag);
        }
        root.put("boxes", list);

        ListTag lost = new ListTag();
        for (Orphan orphan : orphans) {
            CompoundTag tag = new CompoundTag();
            tag.put("pos", positions(List.of(orphan.pos)));
            tag.putString("item", orphan.item);
            tag.putInt("stacks", orphan.stacks);
            tag.putString("when", orphan.when);
            tag.putString("dimension", orphan.dimension);
            lost.add(tag);
        }
        root.put("orphans", lost);

        if (corner1 != null) root.put("corner1", positions(List.of(corner1)));
        if (corner2 != null) root.put("corner2", positions(List.of(corner2)));
        if (!areaDimension.isEmpty()) root.putString("areaDimension", areaDimension);

        try {
            Files.createDirectories(folder());
            NbtIo.writeCompressed(root, file(name, server));
            dirty = false;
        } catch (IOException e) {
            QwerteAddon.LOG.error("Couldn't save stash {}", name, e);
        }
    }

    /**
     * The stash called {@code name} on this server, or null if there isn't one saved.
     */
    public static Stash load(String name) {
        String server = currentServer();
        Path path = file(name, server);
        if (!Files.exists(path) || mc.level == null) return null;

        RegistryOps<Tag> ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Stash stash = new Stash(name, server);

        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            // Saved before the perimeter existed: there are no corners, which is no perimeter
            if (root.get("corner1") instanceof ListTag c1) {
                List<BlockPos> at = readPositions(c1);
                if (!at.isEmpty()) stash.corner1 = at.getFirst();
            }
            if (root.get("corner2") instanceof ListTag c2) {
                List<BlockPos> at = readPositions(c2);
                if (!at.isEmpty()) stash.corner2 = at.getFirst();
            }
            String areaDim = string(root.get("areaDimension"));
            if (areaDim != null) stash.areaDimension = areaDim;
            ListTag list = (ListTag) root.get("boxes");
            if (list == null) return stash;

            for (Tag t : list) {
                CompoundTag tag = (CompoundTag) t;
                List<BlockPos> parts = readPositions((ListTag) tag.get("parts"));
                if (parts.isEmpty()) continue;

                Box box = new Box(parts.getFirst(), parts);
                box.excluded = flag(tag, "excluded");
                box.dropOff = flag(tag, "dropOff");
                box.noCompact = flag(tag, "noCompact");
                box.autoLabel = string(tag.get("autoLabel"));
                if (tag.get("autoSignPos") instanceof ListTag sign) {
                    List<BlockPos> at = readPositions(sign);
                    if (!at.isEmpty()) box.autoSignPos = at.getFirst();
                }

                int size = number(tag.get("size"), -1);
                if (size >= 0) {
                    box.contents = new ItemStack[size];
                    java.util.Arrays.fill(box.contents, ItemStack.EMPTY);

                    if (tag.get("items") instanceof ListTag items) {
                        for (Tag e : items) {
                            CompoundTag entry = (CompoundTag) e;
                            int slot = number(entry.get("slot"), -1);
                            if (slot < 0 || slot >= box.contents.length || entry.get("item") == null) continue;
                            ItemStack.CODEC.parse(ops, entry.get("item")).result().ifPresent(stack -> box.contents[slot] = stack);
                        }
                    }
                }

                stash.boxes.add(box);
            }
            if (root.get("orphans") instanceof ListTag lost) {
                for (Tag t : lost) {
                    if (!(t instanceof CompoundTag tag) || !(tag.get("pos") instanceof ListTag at)) continue;
                    List<BlockPos> pos = readPositions(at);
                    if (pos.isEmpty()) continue;
                    String item = string(tag.get("item"));
                    String when = string(tag.get("when"));
                    stash.orphans.add(new Orphan(pos.getFirst(), item == null ? "items" : item, number(tag.get("stacks"), 0), when == null ? "" : when,
                        string(tag.get("dimension"))));
                }
            }
        } catch (IOException | ClassCastException e) {
            QwerteAddon.LOG.error("Couldn't load stash {}", name, e);
        }

        return stash;
    }

    private static boolean flag(CompoundTag tag, String key) {
        return number(tag.get(key), 0) != 0;
    }

    /**
     * Reads a number tag. Numeric tags changed between Minecraft versions, but reading through NbtOps works on all.
     */
    private static int number(Tag tag, int fallback) {
        if (tag == null) return fallback;
        return NbtOps.INSTANCE.getNumberValue(tag).result().map(Number::intValue).orElse(fallback);
    }

    /**
     * Reads a string tag. String access on compound tags changed between Minecraft versions, but reading through NbtOps
     * works on all.
     */
    private static String string(Tag tag) {
        if (tag == null) return null;
        return NbtOps.INSTANCE.getStringValue(tag).result().orElse(null);
    }

    private static ListTag positions(List<BlockPos> list) {
        ListTag tags = new ListTag();
        for (BlockPos pos : list) {
            ListTag xyz = new ListTag();
            xyz.add(IntTag.valueOf(pos.getX()));
            xyz.add(IntTag.valueOf(pos.getY()));
            xyz.add(IntTag.valueOf(pos.getZ()));
            tags.add(xyz);
        }
        return tags;
    }

    private static List<BlockPos> readPositions(ListTag tags) {
        List<BlockPos> list = new ArrayList<>();
        if (tags == null) return list;

        for (Tag t : tags) {
            if (!(t instanceof ListTag xyz) || xyz.size() != 3) continue;
            list.add(new BlockPos(number(xyz.get(0), 0), number(xyz.get(1), 0), number(xyz.get(2), 0)));
        }
        return list;
    }
}
