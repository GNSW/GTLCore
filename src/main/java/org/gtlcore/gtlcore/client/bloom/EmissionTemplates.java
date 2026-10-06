package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.mixin.bloom.ConnectedModelAccessor;

import com.lowdragmc.lowdraglib.client.model.forge.CustomBakedModelImpl;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** Reuses position-independent emission geometry; dynamic/tinted/AO models keep the normal renderer. */
final class EmissionTemplates {

    private static final Direction[] SIDES = Direction.values();
    private static final byte[] EMPTY = new byte[0];
    private final Map<BlockState, Template> templates = new IdentityHashMap<>();
    private final RandomSource random = RandomSource.create(0);
    private final PoseStack pose = new PoseStack();
    private final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
    private final BufferBuilder buffer = new BufferBuilder(4096);
    private final float[] brightness = new float[4];
    private final int[] light = { 0xf000f0, 0xf000f0, 0xf000f0, 0xf000f0 };

    static BakedModel localModel(BakedModel model) {
        // Only our own alias wrapper is transparent on the local path. Other wrappers may
        // implement connected textures, model data or position-dependent geometry.
        while (model instanceof EmissiveAliases.Model alias) model = alias.localDelegate();
        return model;
    }

    static BakedModel retainedModel(BakedModel model) {
        model = localModel(model);
        // LDLib's exact CTM wrapper derives its quads from this delegate and neighbouring
        // appearances. It is safe to retain each state at its own position, but must never
        // enter the position-independent template path below. Unknown wrappers still fail
        // the caller's exact SimpleBakedModel check.
        if (model.getClass() == CustomBakedModelImpl.class && model instanceof ConnectedModelAccessor connected)
            return localModel(connected.gtlcore$bloomParent());
        return model;
    }

    byte[] vertices(BlockState state, BakedModel model, BlockAndTintGetter level, BlockPos pos,
                    int x, int y, int z) {
        if (!templates.containsKey(state)) templates.put(state, compile(state, localModel(model), level));
        Template template = templates.get(state);
        if (template == null) return null;
        int mask = 64;
        for (int side = 0; side < SIDES.length; side++) {
            if (template.faces[side].length != 0 && Block.shouldRenderFace(state, level, pos, SIDES[side],
                    neighbour.setWithOffset(pos, SIDES[side])))
                mask |= 1 << side;
        }
        return template.vertices(mask, x, y, z);
    }

    private Template compile(BlockState state, BakedModel model, BlockAndTintGetter level) {
        if (model.getClass() != SimpleBakedModel.class || state.hasBlockEntity() || state.hasOffsetFunction()) return null;
        boolean full = BloomRules.fullBlock(state);
        var layers = model.getRenderTypes(state, random, ModelData.EMPTY);
        var faces = new ArrayList<ArrayList<BakedQuad>>(7);
        for (int i = 0; i < 7; i++) faces.add(new ArrayList<>());
        for (var layer : layers) {
            boolean ao = model.useAmbientOcclusion(state, layer);
            for (int i = 0; i < 7; i++) {
                for (BakedQuad quad : model.getQuads(state, i == 6 ? null : SIDES[i], random, ModelData.EMPTY, layer)) {
                    if (!full && !BloomMetadata.matches(quad)) continue;
                    // Biome/custom tint and ambient occlusion depend on the surrounding world.
                    // Never infer static geometry from a sample of a dynamic model's quads.
                    if (quad.isTinted() || ao && quad.hasAmbientOcclusion()) return null;
                    faces.get(i).add(quad);
                }
            }
        }
        byte[][] vertices = new byte[7][];
        for (int i = 0; i < 7; i++) {
            if (faces.get(i).isEmpty()) {
                vertices[i] = EMPTY;
                continue;
            }
            buffer.begin(VertexFormat.Mode.QUADS, EmissionFormat.BLOCK);
            try {
                for (BakedQuad quad : faces.get(i)) {
                    java.util.Arrays.fill(brightness, level.getShade(quad.getDirection(), quad.isShade()));
                    // The bloom shader consumes position/color/UV only; light-map and normals
                    // are deliberately not dependencies of this emission-only template.
                    buffer.putBulkData(pose.last(), quad, brightness, 1, 1, 1, light, OverlayTexture.NO_OVERLAY, true);
                }
                var data = buffer.end();
                try {
                    ByteBuffer bytes = data.vertexBuffer();
                    vertices[i] = new byte[bytes.remaining()];
                    bytes.get(vertices[i]);
                } finally {
                    data.release();
                }
            } finally {
                if (buffer.building()) {
                    var discarded = buffer.endOrDiscardIfEmpty();
                    if (discarded != null) discarded.release();
                }
                buffer.discard();
            }
        }
        return new Template(vertices);
    }

    void clear() {
        templates.clear();
    }

    private record Template(byte[][] faces) {

        byte[] vertices(int mask, int x, int y, int z) {
            int size = 0;
            for (int i = 0; i < faces.length; i++) if ((mask & 1 << i) != 0) size += faces[i].length;
            if (size == 0) return EMPTY;
            byte[] result = new byte[size];
            int offset = 0;
            for (int i = 0; i < faces.length; i++) if ((mask & 1 << i) != 0) {
                System.arraycopy(faces[i], 0, result, offset, faces[i].length);
                offset += faces[i].length;
            }
            ByteBuffer vertices = ByteBuffer.wrap(result).order(ByteOrder.nativeOrder());
            for (int i = 0; i < size; i += 32) {
                vertices.putFloat(i, vertices.getFloat(i) + x);
                vertices.putFloat(i + 4, vertices.getFloat(i + 4) + y);
                vertices.putFloat(i + 8, vertices.getFloat(i + 8) + z);
            }
            return result;
        }
    }
}
