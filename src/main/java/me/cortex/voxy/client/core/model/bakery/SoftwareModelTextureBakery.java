package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureImage;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

public class SoftwareModelTextureBakery {
    // Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer opaqueVC = new ReuseVertexConsumer();
    private final ReuseVertexConsumer translucentVC = new ReuseVertexConsumer(1/* has discard */);
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer();


    public SoftwareModelTextureBakery() {
    }

    public void setupTexture() {
        var texture = Minecraft.getInstance().getTextureManager().getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));

        int textureId = texture.getId();

        if (!RenderSystem.isOnRenderThread()) {
            CompletableFuture<Void> future = new CompletableFuture<>();

            RenderSystem.recordRenderCall(() -> {
                try {
                    _doSetupTexture(textureId);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            future.join();
        } else {
            _doSetupTexture(textureId);
        }
    }

    private void _doSetupTexture(int glId) {
        glBindTexture(GL_TEXTURE_2D, glId);
        int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
        int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);

        int[] pixels = new int[width * height];
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

        this.rasterizer.setSamplerTexture(pixels, width, height);
    }


    //Returns whether the model is a centred ground cross (two diagonal cards - flowers, saplings,
    //tall grass): the probe rides the quad lists the bake already walks, so it costs no extra model
    //lookups. Axis-aligned cards (crops, wall vines), horizontal cards (lily pads) and models with
    //any direction-culled quads are rejected.
    private boolean bakeBlockModel(BlockState state, RenderType layer) {
        if (state.getRenderShape() != RenderShape.MODEL) {
            //Vanilla only draws the json model for MODEL-shaped states. ENTITYBLOCK_ANIMATED blocks
            //(Create cogwheels and friends) still carry a full json the game never renders - baking it
            //shows up at LOD range as a boxy ghost of geometry that does not exist up close. Their
            //distant look is owned by the kinetic snapshots, which capture the real rendered parts.
            return false;
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        boolean crossCandidate = true;
        int diagonalFamilies = 0;
        int unculledQuadCount = 0;

        for (Direction direction : new Direction[] { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, null }) {
            //Chunk-semantics query (render type passed): NeoForge models can answer differently per
            //render type - Create's kinetic blocks return their full json to type-less queries (the
            //BER's spin model) but NOTHING to the chunk layers, which is why they don't double-render
            //up close. Baking type-less pulled that json in and boxed cogwheels at LOD range.
            var quads = model.getQuads(state, direction, new SingleThreadedRandomSource(42L),
                    net.neoforged.neoforge.client.model.data.ModelData.EMPTY, layer);
            if (direction != null && !quads.isEmpty()) {
                crossCandidate = false;
            }
            for (var quad : quads) {
                if (direction == null && crossCandidate) {
                    int family = classifyGroundCrossQuad(quad.getVertices());
                    if (family == 0) {
                        crossCandidate = false;
                    } else {
                        diagonalFamilies |= family;
                        unculledQuadCount++;
                    }
                }
                (layer == RenderType.translucent() ? this.translucentVC : this.opaqueVC)
                        .quad(quad, state.is(BlockTags.LEAVES), layer);
            }
        }
        //Both diagonal families must be present: a single slanted card is decoration, not a plant
        return crossCandidate && unculledQuadCount >= 2 && diagonalFamilies == 0b11;
    }

    //Bit 0/1 for the two diagonal plane families, or zero when the quad is not a centred vertical
    //diagonal card; opposite winding lands in the same family.
    private static int classifyGroundCrossQuad(int[] vertices) {
        if (vertices.length < 16 || (vertices.length & 3) != 0) {
            return 0;
        }
        int stride = vertices.length / 4;
        float x0 = Float.intBitsToFloat(vertices[0]);
        float y0 = Float.intBitsToFloat(vertices[1]);
        float z0 = Float.intBitsToFloat(vertices[2]);
        float x1 = Float.intBitsToFloat(vertices[stride]);
        float y1 = Float.intBitsToFloat(vertices[stride + 1]);
        float z1 = Float.intBitsToFloat(vertices[stride + 2]);
        float x2 = Float.intBitsToFloat(vertices[stride * 2]);
        float y2 = Float.intBitsToFloat(vertices[stride * 2 + 1]);
        float z2 = Float.intBitsToFloat(vertices[stride * 2 + 2]);
        float x3 = Float.intBitsToFloat(vertices[stride * 3]);
        float y3 = Float.intBitsToFloat(vertices[stride * 3 + 1]);
        float z3 = Float.intBitsToFloat(vertices[stride * 3 + 2]);

        float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
        float bx = x2 - x0, by = y2 - y0, bz = z2 - z0;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float normalLengthSq = nx * nx + ny * ny + nz * nz;
        if (normalLengthSq < 1.0e-8f) {
            return 0;
        }
        float normalLength = (float) Math.sqrt(normalLengthSq);

        //Lily pads and other horizontal cards have a mostly vertical normal
        if (Math.abs(ny) > normalLength * 0.12f) {
            return 0;
        }
        //Crops and vines use axis-aligned cards; ground crosses have balanced X/Z normals
        float major = Math.max(Math.abs(nx), Math.abs(nz));
        float minor = Math.min(Math.abs(nx), Math.abs(nz));
        if (major < 1.0e-5f || minor < major * 0.55f) {
            return 0;
        }
        //The plane must pass through the middle of the cell - rejects slanted decorative faces that
        //merely happen to have a diagonal normal
        float centerX = (x0 + x1 + x2 + x3) * 0.25f - 0.5f;
        float centerY = (y0 + y1 + y2 + y3) * 0.25f - 0.5f;
        float centerZ = (z0 + z1 + z2 + z3) * 0.25f - 0.5f;
        float planeDistance = Math.abs(nx * centerX + ny * centerY + nz * centerZ) / normalLength;
        if (planeDistance > 0.0625f) {
            return 0;
        }
        return nx * nz >= 0.0f ? 0b01 : 0b10;
    }

    private void bakeFluidState(BlockState state, int face, RenderType layer) {
        BlockAndTintGetter getter = new BlockAndTintGetter() {
            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }
            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                //This is such a stupid and bad hack, we can inject tinting state here since this is called
                // before the quad is added
                //TODO: need to make a quad once tinting thing
                translucentVC.setDefaultMeta(translucentVC.getDefaultMeta()|4);//Tinting
                opaqueVC.setDefaultMeta(opaqueVC.getDefaultMeta()|4);//Tinting
                return -1;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }

            @Override
            public float getShade(Direction direction, boolean bl) {
                return 0;
            }
        
        };
        
        VertexConsumer vc = this.opaqueVC;;

        if (layer == RenderType.translucent()) vc = this.translucentVC;
        if (layer == RenderType.cutout()) {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()|1);//set discard
        } else {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()&~1);//remove discard
        }
        Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, getter, vc, state, state.getFluidState());
        this.translucentVC.setDefaultMeta(0);//Reset default meta
        this.opaqueVC.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX() * pos.getX() + fv.getY() * pos.getY() + fv.getZ() * pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.opaqueVC.free();
        this.translucentVC.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE
            * ModelFactory.MODEL_TEXTURE_SIZE) * 8;
    // The outputBuffer layout is different from the non software rasterized
    // ModelTextureBakery
    // in this version the values are simply appended
    // (0,0),(1,0),(2,0),(0,1),(1,1),(2,1)

    public int renderToOutput(BlockState state, long outputBuffer) {
        MemoryUtil.memSet(outputBuffer, 0, 16 * 16 * 8 * 6);

        boolean isBlock = true;
        if (state.getBlock() instanceof LiquidBlock) {
            isBlock = false;
        }

        RenderType blockRenderLayer = null;
        if (state.getBlock() instanceof LiquidBlock) {
            blockRenderLayer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
        } else {
            if (state.getBlock() instanceof LeavesBlock) {
                blockRenderLayer = RenderType.solid();
            } else {
                blockRenderLayer = ItemBlockRenderTypes.getChunkRenderType(state);
            }
        }

        // TODO: support block model entities
        // BakedBlockEntityModel bbem = null;
        if (state.hasBlockEntity()) {
            // bbem = BakedBlockEntityModel.bake(state);
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        boolean anyTranslucent = false;
        boolean anyDiscard = false;
        boolean crossPlant = false;
        if (isBlock) {
            this.opaqueVC.reset();
            this.translucentVC.reset();
            crossPlant = this.bakeBlockModel(state, blockRenderLayer);
            isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
            isAnyDarkend |= this.opaqueVC.anyDarkendTex | this.translucentVC.anyDarkendTex;
            anyTranslucent |= !this.translucentVC.isEmpty();
            anyDiscard |= this.opaqueVC.anyDiscard;
            if (!(this.opaqueVC.isEmpty() && this.translucentVC.isEmpty())) {// only render if there... is shit to
                                                                             // render
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);
                    this.rasterizer.clear();
                    this.rasterizer.setBlending(false);
                    this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                    this.rasterizer.setBlending(true);
                    this.rasterizer.raster(VIEWS[i], this.translucentVC);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(),
                            outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
                }
            }
        } else {// Is fluid, slow path :(

            if (!(state.getBlock() instanceof LiquidBlock))
                throw new IllegalStateException();
            for (int i = 0; i < VIEWS.length; i++) {
                this.opaqueVC.reset();
                this.translucentVC.reset();
                this.bakeFluidState(state, i, blockRenderLayer);
                if (this.opaqueVC.isEmpty() && this.translucentVC.isEmpty())
                    continue;
                isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
                isAnyDarkend |= this.opaqueVC.anyDarkendTex | this.translucentVC.anyDarkendTex;
                anyTranslucent |= !this.translucentVC.isEmpty();
                anyDiscard |= this.opaqueVC.anyDiscard;

                this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);

                // The projection matrix
                this.rasterizer.clear();
                this.rasterizer.setBlending(false);
                this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                this.rasterizer.setBlending(true);
                this.rasterizer.raster(VIEWS[i], this.translucentVC);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
            }
        }

        return (isAnyShaded ? 1 : 0) | (isAnyDarkend ? 2 : 0) | (anyTranslucent ? 4 : 0) | (anyDiscard ? 8 : 0) | (crossPlant ? 16 : 0);
    }

    static {
        // the face/direction is the face (e.g. down is the down face)
        addView(0, -90, 0, 0, 0);// Direction.DOWN
        addView(1, 90, 0, 0, 0b100);// Direction.UP

        addView(2, 0, 180, 0, 0b001);// Direction.NORTH
        addView(3, 0, 0, 0, 0);// Direction.SOUTH

        addView(4, 0, 90, 270, 0b100);// Direction.WEST
        addView(5, 0, 270, 270, 0);// Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 0, 1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1, 0, 0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 1, 0), yaw));
        stack.last().pose().mul(new Matrix4f().scale(1 - 2 * (flip & 1), 1 - (flip & 2), 1 - ((flip >> 1) & 2)));
        stack.translate(-0.5f, -0.5f, -0.5f);
        var mat = new Matrix4f(stack.last().pose());

        mat = new Matrix4f().set(
                2, 0, 0, 0,
                0, 2, 0, 0,
                0, 0, -2, 0,
                -1, -1, 1, 1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1 / Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
