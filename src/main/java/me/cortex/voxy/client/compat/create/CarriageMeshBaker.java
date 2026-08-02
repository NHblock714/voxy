package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

//Bakes a carriage block list into a single mesh in the distant vertex format - straight from the
//block models' BakedQuad assets, no vanilla vertex pipeline anywhere. Distant carriages draw as
//one rigid mesh with a pose transform, so all the per-block work happens exactly once per shape.
public final class CarriageMeshBaker {
    private CarriageMeshBaker() {}

    //Create types appear only in the kinetic-partial branch of the bake. The train shape payload
    //handler registers unconditionally, so a Create-less client must still bake the static geometry;
    //without this gate every kinetic block throws a swallowed NoClassDefFoundError per block instead
    //of skipping cleanly.
    private static final boolean CREATE_LOADED =
            net.neoforged.fml.ModList.get() != null && net.neoforged.fml.ModList.get().isLoaded("create");

    //The shape grid dressed up as a level slice: connected-texture model wrappers (casings, glass,
    //framed blocks) resolve their ModelData by querying neighbouring block states, and biome colour
    //resolvers ask for a tint - answered from the grid and from the real level at the camera (the
    //shape is baked near where it was seen, so the camera biome is the honest choice). Light queries
    //borrow the real engine; nothing here is expected to ask it during getModelData.
    private record GridSlice(Map<BlockPos, BlockState> grid) implements net.minecraft.world.level.BlockAndTintGetter {
        @Override
        public BlockState getBlockState(BlockPos pos) {
            var state = this.grid.get(pos);
            return state != null ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        @Nullable
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public float getShade(net.minecraft.core.Direction direction, boolean shaded) {
            return 1.0f;
        }

        @Override
        public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
            return Minecraft.getInstance().level.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) {
            var level = Minecraft.getInstance().level;
            if (level == null) {
                return 0xFFFFFF;
            }
            var cam = Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition();
            return level.getBlockTint(cam, resolver);
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    public static final class BakedCarriage {
        public final DistantMesh mesh;
        public final AABB localBounds;

        BakedCarriage(DistantMesh mesh, AABB localBounds) {
            this.mesh = mesh;
            this.localBounds = localBounds;
        }

        public void close() {
            this.mesh.free();
        }
    }

    @Nullable
    public static BakedCarriage bake(List<ShapeBlock> blocks) {
        return bake(blocks, null);
    }

    //blockEntityData: per-pos ModelData recovered from captured block entities (copycat materials);
    //blocks whose look lives entirely in that data render nothing without it
    @Nullable
    public static BakedCarriage bake(List<ShapeBlock> blocks,
                                     @Nullable Map<BlockPos, net.neoforged.neoforge.client.model.data.ModelData> blockEntityData) {
        if (blocks.isEmpty()) {
            return null;
        }
        Map<BlockPos, BlockState> grid = new HashMap<>(blocks.size() * 2);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var block : blocks) {
            grid.put(new BlockPos(block.x(), block.y(), block.z()), block.state());
            minX = Math.min(minX, block.x()); maxX = Math.max(maxX, block.x());
            minY = Math.min(minY, block.y()); maxY = Math.max(maxY, block.y());
            minZ = Math.min(minZ, block.z()); maxZ = Math.max(maxZ, block.z());
        }

        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        var builder = new DistantMeshBuilder();
        var cursor = new BlockPos.MutableBlockPos();
        var slice = new GridSlice(grid);
        var kineticTransform = new org.joml.Matrix4f();
        for (var entry : grid.entrySet()) {
            var state = entry.getValue();
            if (state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            var pos = entry.getKey();
            try {
                //Tint through the grid slice: biome resolvers answer with the camera-position biome
                //colour (grass on a snowy-plains train is pale, not default green); -1 = no resolver.
                int tint = Minecraft.getInstance().getBlockColors().getColor(state, slice, pos, 0);
                var model = dispatcher.getBlockModel(state);
                //Connected-texture wrappers resolve their connections against the shape itself.
                //Copycats read their material from block entity data the grid cannot supply - feed
                //the recovered (or skeleton-fallback) data through as the block entity's share so
                //the wrapper's own getModelData still gets to derive occlusion from the slice.
                var beData = blockEntityData == null ? null : blockEntityData.get(pos);
                if (beData == null && me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.isCopycatState(state)) {
                    beData = me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.materialFromContraptionNbt(state, null);
                }
                net.neoforged.neoforge.client.model.data.ModelData modelData;
                try {
                    modelData = model.getModelData(slice, pos, state,
                            beData != null ? beData : net.neoforged.neoforge.client.model.data.ModelData.EMPTY);
                } catch (Throwable t) {
                    modelData = beData != null ? beData : net.neoforged.neoforge.client.model.data.ModelData.EMPTY;
                }
                //Carriages move through the sky; bake at full skylight and dim per-draw
                builder.blockModel(state, model,
                        pos.getX(), pos.getY(), pos.getZ(), 15, 0,
                        direction -> {
                            var neighbor = grid.get(cursor.setWithOffset(pos, direction));
                            return neighbor != null && neighbor.canOcclude();
                        }, tint == -1 ? 0xFFFFFF : tint, modelData);
                //Kinetic moving parts: Create swaps shaft/cog baked models for a wrapper that answers
                //the per-layer chunk path with nothing (the BER/visual owns them live), so the
                //emission above produced zero quads for them. The vanilla 3-arg getQuads is the one
                //query the wrapper does not override - bake the real rotating json through it, at the
                //offset-only t=0 angle every frozen drivetrain part shares, evaluated at the
                //contraption-LOCAL position (which is exactly the position Create's own on-contraption
                //block entities use). The chunk-visibility gate keeps statics out: a json the emission
                //above already drew must not appear twice, spun.
                if (CREATE_LOADED && state.getBlock() instanceof com.simibubi.create.content.kinetics.base.IRotate rotate) {
                    if (state.getBlock() instanceof com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock) {
                        KineticSnapshots.bakeGantryCarriage(builder, kineticTransform, state, pos,
                                pos.getX(), pos.getY(), pos.getZ(), 15, 0);
                    } else if (!KineticSnapshots.chunkVisible(model, state)) {
                        var axis = rotate.getRotationAxis(state);
                        float angleRad = (float) Math.toRadians(
                                com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual
                                        .rotationOffset(state, axis, pos) % 360.0f);
                        kineticTransform.identity()
                                .translate(pos.getX(), pos.getY(), pos.getZ())
                                .translate(0.5f, 0.5f, 0.5f);
                        KineticSnapshots.rotateAboutAxis(kineticTransform, axis, angleRad);
                        kineticTransform.translate(-0.5f, -0.5f, -0.5f);
                        builder.transformedModel(model, kineticTransform, 15, 0);
                    }
                }
            } catch (Throwable ignored) {
                //A single broken third party model must not sink the whole carriage
            }
        }

        //build() owns the staging buffer only once it returns; a throw on the way through leaves the
        //memAlloc'd buffer with no owner, and this runs every tick for a contraption that keeps failing.
        //KineticSnapshots.rebake already guards its build the same way.
        DistantMesh mesh;
        try {
            mesh = builder.build();
        } catch (Throwable t) {
            builder.discard();
            me.cortex.voxy.common.Logger.error("Distant carriage bake failed for " + blocks.size() + " blocks", t);
            return null;
        }
        if (mesh == null) {
            me.cortex.voxy.common.Logger.error("Distant carriage bake produced no geometry from " + blocks.size() + " blocks");
            return null;
        }
        return new BakedCarriage(mesh, new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1));
    }
}
