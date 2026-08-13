package me.cortex.voxy.client.core.rendering.section.backend.mdic;


import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;

import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
import static org.lwjgl.opengl.ARBIndirectParameters.glMultiDrawElementsIndirectCountARB;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;

//Uses MDIC to render the sections
public class MDICSectionRenderer extends AbstractSectionRenderer<MDICViewport, BasicSectionGeometryData> {
    public static final Factory<MDICViewport, BasicSectionGeometryData> FACTORY = AbstractSectionRenderer.Factory.create(MDICSectionRenderer.class);

    public static final int OPAQUE_DRAW_COUNT = 400_000;//in draw calls
    public static final int TRANSLUCENT_DRAW_COUNT = 100_000;//in draw calls
    public static final int TEMPORAL_DRAW_COUNT = 100_000;//in draw calls
    private static final int TRANSLUCENT_OFFSET = OPAQUE_DRAW_COUNT;//in draw calls
    private static final int TEMPORAL_OFFSET = TRANSLUCENT_OFFSET+TRANSLUCENT_DRAW_COUNT;//in draw calls
    private static final int STATISTICS_BUFFER_BINDING = 8;
    private final Shader terrainShader;
    private final Shader translucentTerrainShader;

    private final Shader commandGenShader = Shader.make()
            .define("TRANSLUCENT_WRITE_BASE", 1024)
            .define("TEMPORAL_OFFSET", TEMPORAL_OFFSET)

            .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 7)

            .define("HAS_STATISTICS")
            .define("STATISTICS_BUFFER_BINDING", STATISTICS_BUFFER_BINDING)

            //Read at renderer construction - toggling the option needs a renderer recreation
            .defineIf("OPAQUE_NEAR_FIRST", me.cortex.voxy.client.config.VoxyConfig.CONFIG.experimentalOpaqueNearFirst)

            .add(ShaderType.COMPUTE, "voxy:lod/gl46/cmdgen.comp")
            .compile();

    private final Shader prepShader = Shader.make()
            .add(ShaderType.COMPUTE, "voxy:lod/gl46/prep.comp")
            .compile();

    private final Shader cullShader;

    private final Shader prefixSumShader = Shader.make()
            //Use subgroup prefix sum if possible otherwise use dodgy... slow prefix sum
            .add(ShaderType.COMPUTE, Capabilities.INSTANCE.subgroup?"voxy:util/prefixsum/inital3.comp":"voxy:util/prefixsum/simple.comp")
            .define("IO_BUFFER", 0)
            .compile();

    private final Shader translucentGenShader = Shader.make()
            .add(ShaderType.COMPUTE, "voxy:lod/gl46/buildtranslucents.comp")
            .define("TRANSLUCENT_WRITE_BASE", 1024)//The size of the prefix sum array
            .define("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 5)
            .define("TRANSLUCENT_OFFSET", TRANSLUCENT_OFFSET)

            .compile();

    private final GlBuffer uniform = new GlBuffer(1024).zero();//TODO move to viewport?

    //TODO: needs to be in the viewport, since it contains the compute indirect call/values
    private final GlBuffer distanceCountBuffer = new GlBuffer(1024*4+TRANSLUCENT_DRAW_COUNT*4).zero();//TODO move to viewport?

    //Statistics
    private final GlBuffer statisticsBuffer = new GlBuffer(1024).zero();

    private final AbstractRenderPipeline pipeline;
    private final float fluidDatumY;
    private final Matrix4f uniformMatrix = new Matrix4f();
    public MDICSectionRenderer(AbstractRenderPipeline pipeline, ModelStore modelStore, BasicSectionGeometryData geometryData) {
        super(pipeline.properties, modelStore, geometryData);
        this.pipeline = pipeline;
        var level = Minecraft.getInstance().level;
        this.fluidDatumY = level == null ? -1.0e9f : level.getSeaLevel() - (7.0f / 64.0f);
        //The pipeline can be used to transform the renderer in abstract ways

        String vertex = ShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        String taa = pipeline.taaFunction("taaShift");
        if (taa != null) {
            vertex += "\n"+taa;//inject it at the end
        }
        var builder = Shader.make()
                .apply(this.properties::apply)
                .defineIf("TAA_PATCH", taa != null)
                .defineIf("DEBUG_RENDER", false)

                //.defineIf("USE_NV_JANK", Capabilities.INSTANCE.isNvidia)//TODO: fix use capability to try compile the jank thing to see if it can be and use that

                //.defineIf("USE_NV_BARRY", Capabilities.INSTANCE.nvBarryCoords)

                .addSource(ShaderType.VERTEX, vertex);

        //Apply per face tinting
        addDirectionalFaceTint(builder, Minecraft.getInstance().level);

        String frag = ShaderLoader.parse("voxy:lod/gl46/quads.frag");

        String opaqueFrag = pipeline.patchOpaqueShader(this, frag);
        opaqueFrag = opaqueFrag==null?frag:opaqueFrag;

        //TODO: find a more robust/nicer way todo this
        this.terrainShader = tryCompilePatchedOrNormal(builder, opaqueFrag, frag);

        String translucentFrag = pipeline.patchTranslucentShader(this, frag);
        translucentFrag = translucentFrag==null?frag:translucentFrag;

        this.translucentTerrainShader = tryCompilePatchedOrNormal(builder.define("TRANSLUCENT"), translucentFrag, frag);

        if (this.pipeline.hasTAA()) {
            this.cullShader = Shader.make()
                    .apply(this.properties::apply)
                    .addSource(ShaderType.VERTEX, ShaderLoader.parse("voxy:lod/gl46/cull/raster.vert")+"\n\n\n\n"+pipeline.taaFunction("getTAA"))
                    .define("TAA")
                    .add(ShaderType.FRAGMENT, "voxy:lod/gl46/cull/raster.frag")
                    .compile();
        } else {
            this.cullShader = Shader.make()
                    .apply(this.properties::apply)
                    .add(ShaderType.VERTEX, "voxy:lod/gl46/cull/raster.vert")
                    .add(ShaderType.FRAGMENT, "voxy:lod/gl46/cull/raster.frag")
                    .compile();
        }
    }

    private void uploadUniformBuffer(MDICViewport viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniform, 0, 1024);
        
        var mat = this.uniformMatrix.set(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4*4*4;

        viewport.section.getToAddress(ptr); ptr += 4*3;

        if (viewport.frameId<0) {
            Logger.error("Frame ID negative, this will cause things to break, wrapping around");
            viewport.frameId &= 0x7fffffff;
        }
        MemoryUtil.memPutInt(ptr, viewport.frameId&0x7fffffff); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;
        MemoryUtil.memPutFloat(ptr, this.fluidDatumY); ptr += 4;
        //std140: these follow fluidDatumY at 96/100/104/108 and round the block to 112. Must stay in
        //lockstep with SceneUniform in gl46/bindings.glsl - a short write feeds garbage into the enable
        //flag, which silently toggles the chunk-bounds mask.
        var boundary = me.cortex.voxy.client.core.rendering.LodBoundaryFade.getDistances();
        MemoryUtil.memPutFloat(ptr, boundary.enabled() ? 1.0f : 0.0f); ptr += 4;
        MemoryUtil.memPutFloat(ptr, boundary.fadeStart()); ptr += 4;
        MemoryUtil.memPutFloat(ptr, boundary.fadeEnd()); ptr += 4;
        MemoryUtil.memPutInt(ptr, viewport.prevBuildFrameId & 0x7fffffff); ptr += 4;

        UploadStream.INSTANCE.commit();
    }


    private void bindRenderingBuffers(MDICViewport viewport) {
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometryManager.getGeometryBuffer().id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, this.geometryManager.getMetadataBuffer().id);
        this.modelStore.bind(3, 4, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.positionScratchBuffer.id);
        LightMapHelper.bind(1);
        glBindTextureUnit(2, viewport.depthBoundingBuffer.getDepthTex().id);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, viewport.drawCallBuffer.id);
        glBindBuffer(GL_PARAMETER_BUFFER_ARB, viewport.drawCountCallBuffer.id);
    }

    private void renderTerrain(MDICViewport viewport, long indirectOffset, long drawCountOffset, int maxDrawCount) {
        //RenderLayer.getCutoutMipped().startDrawing();


        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(this.properties.closerEqualDepthCompare());
        this.terrainShader.bind();
        glBindVertexArray(GlVertexArray.STATIC_VAO);//Needs to be before binding
        this.pipeline.setupAndBindOpaque(viewport);
        this.bindRenderingBuffers(viewport);

        glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);//Barrier everything is needed
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);

        if (VoxyClient.getOcclusionDebugState()==3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }
        glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, indirectOffset, drawCountOffset, maxDrawCount, 0);
        if (VoxyClient.getOcclusionDebugState()==3) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }

        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
        glBindSampler(1, 0);
        glBindTextureUnit(1, 0);

        //RenderLayer.getCutoutMipped().endDrawing();
    }

    //Feedback clamp for the three indirect-count draws. The formula-based maxDrawCount is
    //proportional to RESIDENT section count, and the command frontend pays per potential record up
    //to that bound regardless of the GPU-side count - on a long session that is milliseconds of
    //GPU time for records that never draw, which vanishes on renderer recreation because the
    //resident set resets. The clamp follows the OBSERVED command counts (read back from the
    //drawCountCallBuffer, a few frames latent) with 1.5x headroom; a readback that ever reaches
    //the bound that was passed means real truncation, and the fuse then reverts to the formula for
    //the rest of the session - degrading toward the pre-clamp behaviour, never accumulating error.
    //Per-lane floors: the opaque lane needs headroom for view swings, but the translucent and
    //temporal lanes sit near zero when still - a shared 16384 floor made the command frontend
    //predicate thousands of empty records per frame on lanes that draw almost nothing. A
    //truncation on either small lane self-heals (temporal misses are repainted by the next
    //frame's opaque lane, a translucent miss is one frame of missing water) and each lane's
    //strike fuse reverts only itself.
    private static final int[] DRAW_CLAMP_FLOORS = {16384, 4096, 2048};
    private static final int DRAW_CLAMP_WINDOW_FRAMES = 240;
    //A truncation has to keep happening to count: the readback is several frames behind the bound
    //it is compared against, so a single hit is far more likely to be that skew than real loss.
    private static final int DRAW_CLAMP_TRUNCATION_STRIKES = 120;
    private final int[] drawCountWindowMax = new int[3];
    private final int[] drawCountLastWindowMax = new int[3];
    //Per lane, the smallest bound this clamp has actually imposed recently - Integer.MAX_VALUE
    //while the formula itself was the binding constraint (small resident set), which is when a
    //naive comparison against "the last bound passed" reports saturation that never happened.
    private final int[] recentBindingClamp = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
    private final int[] truncationStrikes = new int[3];
    private int drawClampFrameCounter;
    private final boolean[] drawClampDisabled = new boolean[3];

    private int clampMaxDraws(int lane, int formula) {
        if (this.drawClampDisabled[lane]) {
            return formula;
        }
        int observed = Math.max(this.drawCountWindowMax[lane], this.drawCountLastWindowMax[lane]);
        int clamp = Math.max(observed + (observed >> 1), DRAW_CLAMP_FLOORS[lane]);
        if (clamp < formula) {
            //Only a clamp below the formula can truncate anything
            this.recentBindingClamp[lane] = Math.min(this.recentBindingClamp[lane], clamp);
            return clamp;
        }
        return formula;
    }

    private void onDrawCountsRead(int opaque, int translucent, int temporal) {
        int[] counts = {opaque, translucent, temporal};
        for (int lane = 0; lane < 3; lane++) {
            int c = counts[lane];
            if (c > this.drawCountWindowMax[lane]) {
                this.drawCountWindowMax[lane] = c;
            }
            if (this.drawClampDisabled[lane]) {
                continue;
            }
            if (c >= this.recentBindingClamp[lane]) {
                //Real truncation: widen immediately (the clamp follows the window maximum, so
                //seeding it with the truncated count restores headroom within a frame) and only
                //fall back to the formula if it keeps happening anyway - and only for this lane
                this.drawCountWindowMax[lane] = Math.max(this.drawCountWindowMax[lane], c * 2);
                if (++this.truncationStrikes[lane] >= DRAW_CLAMP_TRUNCATION_STRIKES) {
                    this.drawClampDisabled[lane] = true;
                    me.cortex.voxy.common.Logger.warn("Draw-count clamp kept truncating (lane " + lane
                            + ", count " + c + "), reverting to formula bounds for this session");
                }
            } else if (this.truncationStrikes[lane] > 0) {
                this.truncationStrikes[lane]--;
            }
        }
        if (++this.drawClampFrameCounter >= DRAW_CLAMP_WINDOW_FRAMES) {
            this.drawClampFrameCounter = 0;
            for (int lane = 0; lane < 3; lane++) {
                this.drawCountLastWindowMax[lane] = this.drawCountWindowMax[lane];
                this.drawCountWindowMax[lane] = 0;
                this.recentBindingClamp[lane] = Integer.MAX_VALUE;
            }
        }
    }

    @Override
    public void renderOpaque(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;

        this.uploadUniformBuffer(viewport);

        this.renderTerrain(viewport, 0, 4*3, this.clampMaxDraws(0, Math.min((int)(this.geometryManager.getSectionCount()*4.4+128), OPAQUE_DRAW_COUNT)));
    }

    @Override
    public void renderTranslucent(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;

        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(this.properties.closerEqualDepthCompare());
        this.translucentTerrainShader.bind();
        glBindVertexArray(GlVertexArray.STATIC_VAO);//Needs to be before binding
        this.pipeline.setupAndBindTranslucent(viewport);
        this.bindRenderingBuffers(viewport);

        glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);//Barrier everything is needed
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);
        glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, TRANSLUCENT_OFFSET*5*4, 4*4, this.clampMaxDraws(1, Math.min(this.geometryManager.getSectionCount(), TRANSLUCENT_DRAW_COUNT)), 0);

        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
        glBindSampler(1, 0);
        glBindTextureUnit(1, 0);

        glDisable(GL_BLEND);
    }

    @Override
    public void buildDrawCalls(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        this.uploadUniformBuffer(viewport);
        //Can do a sneeky trick, since the sectionRenderList is a list to things to render, it invokes the culler
        // which only marks visible sections


        {//Dispatch prep
            this.prepShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.getRenderList().id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute(1,1,1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }

        GPUTiming.INSTANCE.marker("OT");
        {//Test occlusion
            this.cullShader.bind();
            if (this.pipeline.hasTAA()) this.pipeline.bindUniforms();//Used for shader TAA
            if (Capabilities.INSTANCE.repFragTest) {
                glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            }
            glBindVertexArray(GlVertexArray.STATIC_VAO);
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.visibilityBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, viewport.indirectLookupBuffer.id);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(this.properties.closerEqualDepthCompare());
            glColorMask(false, false, false, false);
            glDepthMask(false);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);
            glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_BYTE, 6*4);
            glDepthMask(true);
            glColorMask(true, true, true, true);
            glDisable(GL_DEPTH_TEST);
            if (Capabilities.INSTANCE.repFragTest) {
                glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            }
        }

        GPUTiming.INSTANCE.marker("CG");

        {//Generate the commands
            this.distanceCountBuffer.zeroRange(0, 1024*4);
            this.commandGenShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, viewport.visibilityBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.indirectLookupBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, viewport.positionScratchBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, this.distanceCountBuffer.id);

            //HAS_STATISTICS is always compiled (the F3 toggle only gates the readback), so the
            //shader's atomic writes always target binding 8 - the bind must be unconditional or
            //those writes hit an unbound SSBO. Zeroing only matters when someone will read.
            if (RenderStatistics.enabled) {
                this.statisticsBuffer.zero();
            }
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, STATISTICS_BUFFER_BINDING, this.statisticsBuffer.id);

            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchComputeIndirect(0);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);

            //Feeds the draw-count clamp: the actual emitted command counts live at offsets 12/16/20
            //(opaque/translucent/temporal, matching the renderTerrain drawCountOffsets 4*3/4*4/4*5)
            DownloadStream.INSTANCE.download(viewport.drawCountCallBuffer, down -> this.onDrawCountsRead(
                    MemoryUtil.memGetInt(down.address + 12),
                    MemoryUtil.memGetInt(down.address + 16),
                    MemoryUtil.memGetInt(down.address + 20)));

            if (RenderStatistics.enabled) {
                DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                    final int LAYERS = WorldEngine.MAX_LOD_LAYER+1;
                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.visibleSections[i] = MemoryUtil.memGetInt(down.address+i*4L);
                    }

                    for (int i = 0; i < LAYERS; i++) {
                        RenderStatistics.quadCount[i] = MemoryUtil.memGetInt(down.address+LAYERS*4L+i*4L);
                    }
                });
            }
        }

        GPUTiming.INSTANCE.marker("TS");
        {//Do translucency sorting
            this.prefixSumShader.bind();
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, this.distanceCountBuffer.id);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);//Am unsure if is needed
            glDispatchCompute(1,1,1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            this.translucentGenShader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.drawCountCallBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.geometryManager.getMetadataBuffer().id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, viewport.indirectLookupBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, this.distanceCountBuffer.id);

            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, viewport.drawCountCallBuffer.id);//This isnt great but its a nice trick to bound it, even if its inefficent ;-;
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT|GL_UNIFORM_BARRIER_BIT);
            glDispatchComputeIndirect(0);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        }

    }

    @Override
    public void renderTemporal(MDICViewport viewport) {
        if (this.geometryManager.getSectionCount() == 0) return;
        //Render temporal
        this.renderTerrain(viewport, TEMPORAL_OFFSET*5*4, 4*5, this.clampMaxDraws(2, Math.min(this.geometryManager.getSectionCount(), TEMPORAL_DRAW_COUNT)));
    }

    @Override
    public void addDebug(List<String> lines) {
        super.addDebug(lines);
        //The clamp silently reverting is exactly the state a report needs to expose - it decides
        //whether the resident-scaling draw cost is bounded this session or back on the formula
        lines.add("drawClamp: " + (this.drawClampDisabled[0] ? "R" : "a") + (this.drawClampDisabled[1] ? "R" : "a") + (this.drawClampDisabled[2] ? "R" : "a")
                + " lastWin[" + this.drawCountLastWindowMax[0] + "," + this.drawCountLastWindowMax[1] + "," + this.drawCountLastWindowMax[2] + "]"
                + " strikes[" + this.truncationStrikes[0] + "," + this.truncationStrikes[1] + "," + this.truncationStrikes[2] + "]");
    }

    @Override
    public MDICViewport createViewport() {
        return new MDICViewport(this.properties, this.geometryManager.getMaxSectionCount());
    }

    @Override
    public void free() {
        this.uniform.free();
        this.distanceCountBuffer.free();
        this.translucentTerrainShader.free();
        this.terrainShader.free();
        this.commandGenShader.free();
        this.cullShader.free();
        this.prepShader.free();
        this.translucentGenShader.free();
        this.prefixSumShader.free();
        this.statisticsBuffer.free();
    }
}
