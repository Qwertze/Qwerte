package com.porter.qwerte.stash;

import com.porter.qwerte.stash.Stash.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Where the signs of a container can be, and what they say.
 */
public final class Containers {
    private static final Direction[] HORIZONTALS = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };

    private Containers() {}

    /**
     * What a read of a container's signs found: the labels, and whether there is any sign at all, blank or not (a sign
     * just placed has no text yet).
     */
    public record Signs(List<String> labels, boolean anyBlock) {}

    /**
     * Every block a sign for this container can be at: beside each part, and on top of it.
     */
    public static List<BlockPos> signSpots(Box box) {
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos part : box.parts) {
            for (Direction dir : HORIZONTALS) spots.add(part.relative(dir));
            spots.add(part.above());
        }
        return spots;
    }

    /**
     * The signs on the side of a container facing away from it (wall signs and wall hanging signs), or standing on top
     * of it, or null if any of the places they could be in isn't loaded: a container out of range is not a container
     * without signs. Each line is a label, and so are all the lines of a face read together.
     */
    public static Signs read(Stash stash, Box box) {
        if (mc.level == null) return null;

        List<String> labels = new ArrayList<>();
        boolean any = false;
        for (BlockPos part : box.parts) {
            if (!mc.level.isLoaded(part)) return null;

            for (Direction dir : HORIZONTALS) {
                BlockPos signPos = part.relative(dir);
                if (!mc.level.isLoaded(signPos)) return null;

                BlockState state = mc.level.getBlockState(signPos);
                // A wall hanging sign hangs between the blocks at its ends, whichever way it faces, so any beside the
                // container is its own unless another container of the stash is right beside it too
                boolean ours = (state.getBlock() instanceof WallSignBlock && state.getValue(WallSignBlock.FACING) == dir)
                    || (state.getBlock() instanceof WallHangingSignBlock && !sharedWithAnother(stash, box, signPos));
                if (ours) {
                    any = true;
                    addLines(labels, signPos);
                }
                else if (mc.level.getBlockEntity(signPos) instanceof SignBlockEntity) any = true;
            }

            BlockPos above = part.above();
            if (!mc.level.isLoaded(above)) return null;
            if (mc.level.getBlockState(above).getBlock() instanceof StandingSignBlock) {
                any = true;
                addLines(labels, above);
            }
            else if (mc.level.getBlockEntity(above) instanceof SignBlockEntity) any = true;
        }
        return new Signs(labels, any);
    }

    /**
     * A sign at this place that could just as well belong to another container of the stash (one with a part right beside
     * it) is nobody's.
     */
    private static boolean sharedWithAnother(Stash stash, Box box, BlockPos signPos) {
        if (stash == null) return false;
        for (Box other : stash.boxes) {
            if (other == box) continue;
            for (BlockPos part : other.parts) {
                if (part.getY() == signPos.getY() && Math.abs(part.getX() - signPos.getX()) + Math.abs(part.getZ() - signPos.getZ()) == 1) return true;
            }
        }
        return false;
    }

    private static void addLines(List<String> labels, BlockPos signPos) {
        if (!(mc.level.getBlockEntity(signPos) instanceof SignBlockEntity sign)) return;

        for (var text : List.of(sign.getFrontText(), sign.getBackText())) {
            List<String> lines = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) lines.add(text.getMessage(i, false).getString());
            labels.addAll(labelsOf(lines));
        }
    }

    /**
     * The labels a sign face gives: every line that has text, and all of them joined when there is more than one.
     */
    public static List<String> labelsOf(List<String> lines) {
        List<String> labels = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        int count = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            labels.add(line);
            if (!joined.isEmpty()) joined.append(' ');
            joined.append(line);
            count++;
        }
        if (count > 1) labels.add(joined.toString());
        return labels;
    }

    /**
     * A place for a sign on a container: the block the sign stands in, which side of the container it is on, the part of
     * the container it is against, and where to stand to put it up.
     */
    public record Face(BlockPos sign, Direction dir, BlockPos part, BlockPos stand) {}

    // How far from the eyes to the middle of the chest's side a sign can be put up from, inside the reach of 4.5
    private static final double SIGN_REACH = 4.0;
    private static final double SNEAKING_EYE = 1.27;

    /**
     * Where a sign can go on this container, best first: the side it faces (the front) of each part, then the other
     * sides. A place needs air to put the sign in, and not to be inside any container of the stash. Null if a chunk
     * isn't loaded.
     */
    public static List<Face> signFaces(Stash stash, Box box) {
        if (mc.level == null) return null;
        List<Face> faces = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (BlockPos part : box.parts) {
                if (!mc.level.isLoaded(part)) return null;
                BlockState state = mc.level.getBlockState(part);
                Direction front = null;
                if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) front = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                else if (state.hasProperty(BlockStateProperties.FACING)) {
                    Direction facing = state.getValue(BlockStateProperties.FACING);
                    if (facing.getAxis().isHorizontal()) front = facing;
                }

                for (Direction dir : HORIZONTALS) {
                    if ((pass == 0) != (dir == front)) continue;
                    BlockPos cell = part.relative(dir);
                    if (!mc.level.isLoaded(cell)) return null;
                    if (!mc.level.getBlockState(cell).isAir() || stash.boxAt(cell) != null) continue;
                    BlockPos stand = standFor(stash, cell, dir, part);
                    if (stand != null) faces.add(new Face(cell, dir, part, stand));
                }
            }
        }
        return faces;
    }

    /**
     * A place to stand to put a sign at {@code sign} against {@code part}: on the side the sign is on, with a solid floor
     * and room for the head, clear of every container, close, and from where a ray from the sneaking eyes to the middle of
     * the side hits that part on that side. The closest to the sign first, or null if there is none.
     */
    private static BlockPos standFor(Stash stash, BlockPos sign, Direction dir, BlockPos part) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos feet = sign.offset(dx, dy, dz);
                    if (!mc.level.isLoaded(feet)) continue;
                    // On the side of the sign
                    if ((feet.getX() - part.getX()) * dir.getStepX() + (feet.getZ() - part.getZ()) * dir.getStepZ() < 1) continue;
                    BlockPos head = feet.above();
                    if (stash.boxAt(feet) != null || stash.boxAt(head) != null) continue;
                    if (!free(feet) || !free(head)) continue;
                    BlockPos floor = feet.below();
                    if (!mc.level.getBlockState(floor).isFaceSturdy(mc.level, floor, Direction.UP)) continue;
                    if (!seesSide(feet, dir, part)) continue;

                    double distance = dx * dx + dy * dy + dz * dz;
                    if (distance < bestDistance) {
                        best = feet;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean free(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return true;
        return state.getCollisionShape(mc.level, pos).isEmpty() && state.getFluidState().isEmpty() && !(state.getBlock() instanceof BaseFireBlock);
    }

    /**
     * From the sneaking eyes standing at {@code feet}, the ray to a point just inside the middle of the side of the part
     * hits that part, on that side, within reach.
     */
    private static boolean seesSide(BlockPos feet, Direction dir, BlockPos part) {
        Vec3 eyes = new Vec3(feet.getX() + 0.5, feet.getY() + SNEAKING_EYE, feet.getZ() + 0.5);
        Vec3 point = new Vec3(part.getX() + 0.5 + dir.getStepX() * 0.4, part.getY() + 0.5, part.getZ() + 0.5 + dir.getStepZ() * 0.4);
        double length = eyes.distanceTo(point);
        if (length > SIGN_REACH) return false;

        Vec3 end = eyes.add(point.subtract(eyes).scale(SIGN_REACH / length));
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(part) && hit.getDirection() == dir;
    }
}
