package org.gtlcore.gtlcore.common.item.manipulator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Only lightweight user settings live on the tool; snapshots belong to SavedData. */
public final class ManipulatorSettings {

    public enum Mode {

        BUILD(0),
        REMOVE(1),
        REPLACE(3),
        COPY(4),
        MOVE(5);

        // Persistent IDs retain their original values after removal of cable mode (2).
        private final int id;

        Mode(int id) {
            this.id = id;
        }

        private static Mode byId(int id) {
            for (Mode mode : values()) if (mode.id == id) return mode;
            return BUILD;
        }
    }

    private static final int REMOVED_CABLE_MODE = 2;

    public enum Shape {
        LINE,
        BOX,
        CYLINDER,
        SPHERE
    }

    public enum Removal {
        ALL,
        REPLACEABLE,
        NONE
    }

    public Mode mode = Mode.BUILD;
    public Shape shape = Shape.BOX;
    public Removal removal = Removal.REPLACEABLE;
    public BlockPos a;
    public BlockPos b;
    public BlockPos anchor;
    public String dimension = "";
    public boolean paste;
    public final int[] rotation = new int[3];
    public final boolean[] mirror = new boolean[3];
    public final int[] repeat = { 1, 1, 1 };
    public final int[] spacing = new int[3];
    public final BlockState[] materials = { Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(),
            Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState() };
    public final List<BlockState> whitelist = new ArrayList<>();

    public static ManipulatorSettings read(ItemStack stack) {
        return read(stack.getOrCreateTag().getCompound("Manipulator"));
    }

    public static ManipulatorSettings read(CompoundTag tag) {
        var s = new ManipulatorSettings();
        s.mode = Mode.byId(tag.getInt("Mode"));
        s.shape = Shape.values()[Math.floorMod(tag.getInt("Shape"), Shape.values().length)];
        s.removal = tag.contains("Removal") ? Removal.values()[Math.floorMod(tag.getInt("Removal"), Removal.values().length)] : Removal.REPLACEABLE;
        if (tag.contains("A")) s.a = BlockPos.of(tag.getLong("A"));
        if (tag.contains("B")) s.b = BlockPos.of(tag.getLong("B"));
        if (tag.contains("Anchor")) s.anchor = BlockPos.of(tag.getLong("Anchor"));
        s.dimension = tag.getString("Dimension");
        s.paste = tag.getBoolean("Paste");
        for (int i = 0; i < 3; i++) {
            s.rotation[i] = Math.floorMod(tag.getInt("Rotation" + i), 4);
            s.mirror[i] = tag.getBoolean("Mirror" + i);
            s.repeat[i] = Math.max(1, tag.getInt("Repeat" + i));
            s.spacing[i] = tag.getInt("Spacing" + i);
        }
        for (int i = 0; i < s.materials.length; i++) {
            if (tag.contains("Material" + i)) s.materials[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("Material" + i));
        }
        for (Tag entry : tag.getList("Whitelist", Tag.TAG_COMPOUND)) {
            s.whitelist.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), (CompoundTag) entry));
        }
        if (isRemovedMode(tag)) {
            // A removed mode must never become a build operation on its old selection.
            s.a = null;
            s.b = null;
            s.anchor = null;
            s.dimension = "";
            s.paste = false;
        }
        return s;
    }

    public static boolean isRemovedMode(CompoundTag tag) {
        return tag.getInt("Mode") == REMOVED_CABLE_MODE;
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Mode", mode.id);
        tag.putInt("Shape", shape.ordinal());
        tag.putInt("Removal", removal.ordinal());
        if (a != null) tag.putLong("A", a.asLong());
        if (b != null) tag.putLong("B", b.asLong());
        if (anchor != null) tag.putLong("Anchor", anchor.asLong());
        tag.putString("Dimension", dimension);
        tag.putBoolean("Paste", paste);
        for (int i = 0; i < 3; i++) {
            tag.putInt("Rotation" + i, rotation[i]);
            tag.putBoolean("Mirror" + i, mirror[i]);
            tag.putInt("Repeat" + i, repeat[i]);
            tag.putInt("Spacing" + i, spacing[i]);
        }
        for (int i = 0; i < materials.length; i++) tag.put("Material" + i, NbtUtils.writeBlockState(materials[i]));
        ListTag list = new ListTag();
        whitelist.forEach(state -> list.add(NbtUtils.writeBlockState(state)));
        tag.put("Whitelist", list);
        return tag;
    }

    public void save(ItemStack stack) {
        stack.getOrCreateTag().put("Manipulator", save());
    }

    public BlockPos min() {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    public BlockPos size() {
        return new BlockPos(Math.abs(a.getX() - b.getX()) + 1, Math.abs(a.getY() - b.getY()) + 1, Math.abs(a.getZ() - b.getZ()) + 1);
    }

    public long volume() {
        BlockPos size = size();
        return (long) size.getX() * size.getY() * size.getZ();
    }

    public boolean template() {
        return mode == Mode.COPY || mode == Mode.MOVE;
    }
}
