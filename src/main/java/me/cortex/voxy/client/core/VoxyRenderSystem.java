package me.cortex.voxy.client.core;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30.glGetIntegeri;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

public class VoxyRenderSystem {
    // Hot-reloadable render pressure tables. Index is VoxyConfig.renderPressure:
    // 0 = maximum FPS / slowest LOD catch-up, 4 = fastest LOD catch-up / highest frame pressure.
    private static final long[] MODEL_BAKE_BUDGET_LOW_FPS = {75_000L, 150_000L, 250_000L, 450_000L, 900_000L};
    private static final long[] MODEL_BAKE_BUDGET_BUSY = {150_000L, 300_000L, 500_000L, 750_000L, 1_200_000L};
    private static final long[] MODEL_BAKE_BUDGET_IDLE = {300_000L, 550_000L, 900_000L, 1_350_000L, 2_000_000L};
    private static final int[] TOP_LEVEL_NODE_PROCESS_RATE = {4, 8, 12, 24, 40};

    private final WorldEngine worldIn;


    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;


    private final RenderDistanceTracker renderDistanceTracker;
    public final ChunkBoundRenderer chunkBoundRenderer;

    private final ViewportSelector<?> viewportSelector;

    private final AbstractRenderPipeline pipeline;
    private final RenderProperties properties;

    private final int[] savedBufferBindings = new int[10];
    //How many texture units the post-pass cleanup clears: vanilla and iris both live inside the
    //first twelve; iris' own tracking array is cleared to the same count by the pipeline override
    private static final int CLEARED_TEXTURE_BINDING_COUNT = 12;
    private final int[] viewportDimensions = new int[4];
    private final Matrix4f projectionScratch = new Matrix4f();
    private final Matrix4f modifiedProjectionScratch = new Matrix4f();


    public String getPipelineName() { return this.pipeline == null ? "none" : this.pipeline.getClass().getSimpleName(); }

    private static AbstractSectionRenderer.Factory<?,? extends IGeometryData> getRenderBackendFactory() {
        //TODO: need todo a thing where selects optimal section render based on if supports the pipeline and geometry data type
        return MDICSectionRenderer.FACTORY;
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        //Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even if the rest of loading takes more
        // than timeout, we keep the world acquired
        world.acquireRef();
        Logger.info("Creating Voxy render system");

        if (Minecraft.getInstance().options.renderDistance().get()<3) {
            String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
            Logger.warn(msg);
            Minecraft.getInstance().getChatListener().handleSystemMessage(Component.literal(msg), false);
        }

        //Fking HATE EVERYTHING AAAAAAAAAAAAAAAA
        for (int i = 0; i < this.savedBufferBindings.length; i++) {
            this.savedBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        //Mirrors of the blank-final fields, for the failure path: the catch cannot read finals the
        //ctor might not have assigned yet
        ModelBakerySubsystem modelServiceL = null;
        RenderGenerationService renderGenL = null;
        me.cortex.voxy.client.core.gl.GlBuffer geomBufferL = null;
        BasicSectionGeometryData geometryDataL = null;
        AsyncNodeManager nodeManagerL = null;
        NodeCleaner nodeCleanerL = null;
        HierarchicalOcclusionTraverser traversalL = null;
        AbstractRenderPipeline pipelineL = null;
        ViewportSelector<?> viewportSelectorL = null;
        ChunkBoundRenderer chunkBoundRendererL = null;
        try {
            //wait for opengl to be finished, this should hopefully ensure all memory allocations are free
            glFinish();
            glFinish();

            this.worldIn = world;

            this.properties = RenderProperties.getRenderProperties();
            var backendFactory = getRenderBackendFactory();
            {
                this.modelService = modelServiceL = new ModelBakerySubsystem(world.getMapper());
                this.renderGen = renderGenL = new RenderGenerationService(world, this.modelService, sm, IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));

                geomBufferL = RenderResourceReuse.getOrCreateGeometryBuffer();
                this.geometryData = geometryDataL = new BasicSectionGeometryData(1<<20, geomBufferL);

                this.nodeManager = nodeManagerL = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
                this.nodeCleaner = nodeCleanerL = new NodeCleaner(this.nodeManager);
                this.traversal = traversalL = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

                //The callback is a single slot; the beacon tracker listens to the same signal, so both
                //consumers share one registration rather than the second silently replacing the first
                world.setDirtyCallback((s, flags, msk) -> {
                    this.nodeManager.worldEvent(s, flags, msk);
                    me.cortex.voxy.client.core.beacon.BeaconBeamTracker.onSectionDirty(s, flags);
                });

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);

                this.nodeManager.start();
            }

            this.pipeline = pipelineL = RenderPipelineFactory.createPipeline(this.properties, this.nodeManager, this.nodeCleaner, this.traversal, this::frexStillHasWork);
            this.pipeline.setupExtraModelBakeryData(this.modelService);//Configure the model service

            //Late stage traversal compile for shaders with taa
            this.traversal.lateStageCompile(this.pipeline);

            //Compile the Create distant renderers' shaders here rather than on first draw - linking
            //them blocks the render thread long enough to be a visible hitch mid-gameplay
            me.cortex.voxy.client.compat.create.DistantShaders.warmup(this.pipeline);


            var sectionRenderer = backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewportSelector = viewportSelectorL = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                int minSec = Minecraft.getInstance().level.getMinSection() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSection() - 1) >> 5;

                //Do some very cheeky stuff for MiB
                if (VoxyCommon.IS_MINE_IN_ABYSS) {//TODO: make this somehow configurable
                    minSec = -8;
                    maxSec = 7;
                }

                this.renderDistanceTracker = new RenderDistanceTracker(this.getTopLevelNodeProcessRate(),
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.chunkBoundRenderer = chunkBoundRendererL = new ChunkBoundRenderer(this.pipeline);

            Logger.info("Voxy render system created with " + this.geometryData.getMaxCapacity() + " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() + "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
        } catch (RuntimeException e) {
            //A failed creation has already taken the two cache-held giants (geometry buffer up to
            //4GiB, ~537MiB atlas) and started two worker threads - and the iris-disable fallback in
            //MixinLevelRenderer re-creates the system immediately after, so anything not returned
            //here is allocated twice over. Each step fused separately: cleanup must never mask the
            //original failure.
            try {
                world.setDirtyCallback(null);
                world.getMapper().setBiomeCallback(null);
            } catch (Throwable ignored) {
            }
            if (nodeManagerL != null) try { nodeManagerL.stop(); } catch (Throwable t) { Logger.error("ctor cleanup: nodeManager", t); }
            if (modelServiceL != null) try { modelServiceL.shutdown(); } catch (Throwable t) { Logger.error("ctor cleanup: modelService", t); }
            if (renderGenL != null) try { renderGenL.shutdown(); } catch (Throwable t) { Logger.error("ctor cleanup: renderGen", t); }
            if (traversalL != null) try { traversalL.free(); } catch (Throwable t) { Logger.error("ctor cleanup: traversal", t); }
            if (nodeCleanerL != null) try { nodeCleanerL.free(); } catch (Throwable t) { Logger.error("ctor cleanup: nodeCleaner", t); }
            if (geometryDataL != null) {
                try { geometryDataL.free(); } catch (Throwable t) { Logger.error("ctor cleanup: geometryData", t); }
                try { RenderResourceReuse.giveBackGeometryBuffer(geometryDataL.getGeometryBuffer()); } catch (Throwable ignored) {}
            } else if (geomBufferL != null) {
                RenderResourceReuse.giveBackGeometryBuffer(geomBufferL);
            }
            if (pipelineL != null) try { pipelineL.free(); } catch (Throwable t) { Logger.error("ctor cleanup: pipeline", t); }
            if (viewportSelectorL != null) try { viewportSelectorL.free(); } catch (Throwable t) { Logger.error("ctor cleanup: viewports", t); }
            if (chunkBoundRendererL != null) try { chunkBoundRendererL.free(); } catch (Throwable t) { Logger.error("ctor cleanup: chunkBound", t); }
            //Released LAST so the engine idle-closer cannot race the cleanup above
            world.releaseRef();
            throw e;
        }

        for (int i = 0; i < this.savedBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, this.savedBufferBindings[i]);
        }

        this.pipeline.clearTextureAndSamplerBindings(CLEARED_TEXTURE_BINDING_COUNT);
    }


    public Viewport<?> setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) {
            return null;
        }

        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;//10+4
            cameraY += (16+(256-32-sector*30))*16;
        }

        //cameraY += 100;
        var voxyProjection = computeProjectionMat(this.properties, vanillaProjection);

        glGetIntegerv(GL_VIEWPORT, this.viewportDimensions);

        int width = this.viewportDimensions[2];
        int height = this.viewportDimensions[3];

        {//Apply render scaling factor
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width*factor[0]);
                height = (int) (height*factor[1]);
            }
        }
        if (width == 0 || height == 0) {
            Logger.error("Cannot create a Voxy viewport with zero width or height");
            return null;
        }

        viewport
                .setVanillaProjection(vanillaProjection)
                .setProjection(voxyProjection)
                .setModelView(modelView)
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .update();

        if (VoxyClient.getOcclusionDebugState()==0) {
            viewport.frameId++;
        }

        return viewport;
    }

    //Blindness and darkness are supposed to take the world away, and vanilla does that by collapsing
    //fog to a few blocks. The LOD is drawn into its own target with its own fog, so vanilla's collapse
    //never reaches it and the distant world stayed lit behind a black foreground. Rather than trying to
    //reproduce vanilla's band on our side, just do not draw: the whole point of the effect is that there
    //is nothing to see. Sits above the pipeline split, so it covers the shader path too, where our fog
    //uniforms do not even exist.
    //
    //The fog end is checked as well as the effect, because darkness ramps in over 22 ticks
    //(MobEffects.DARKNESS is registered with setBlendDuration(22)) and vanilla lerps its fog from the
    //full far plane down. For about a second at each end of the pulse the effect is present while
    //vanilla is still drawing everything - dropping the LOD then would blink the distant world out
    //while the near world stayed bright. Waiting for vanilla's own band to close keeps the two in step.
    //Live test for a medium that takes vision away. Asked of the camera every frame rather than read
    //from a stored value: a cached one that stops being refreshed strands, and a stranded WATER state
    //tints every LOD in the world blue after surfacing.
    //Only the mob effects, not fluids. Vanilla's fluid fogs are fixed distances - water 96*waterVision,
    //lava 1.0, powder snow 2.0 - so none of them scale off farPlaneDistance and none of them need the
    //render-distance inputs neutralised. Including fluids there would also clip LOD geometry underwater,
    //since getDepthFar is the projection far plane and the LOD reaches well past vanilla's.
    public static boolean visionEffectPresent() {
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return false;
        }
        return mc.gameRenderer.getMainCamera().getEntity()
                    instanceof net.minecraft.world.entity.LivingEntity living
                && (living.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                    || living.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS));
    }

    public static boolean restrictingMediumPresent() {
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return false;
        }
        var camera = mc.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE) {
            return true;
        }
        return camera.getEntity() instanceof net.minecraft.world.entity.LivingEntity living
                && (living.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                    || living.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS));
    }

    //What renderOpaque actually saw this frame. The command that reports it runs on the main thread
    //outside the render pass, where the fog state is whatever the last writer left - reading it there
    //describes a different moment than the one that matters.
    private static volatile float lastRenderFogEnd = -1;
    private static volatile float lastRenderVanillaFar = -1;
    private static volatile boolean lastRenderSkipped;


    //The fog RenderSystem holds while sodium's terrain pass runs, which is what
    //ChunkShaderFogComponent feeds the chunk shaders - the ground truth for what the terrain beside
    //the LOD is actually wearing.
    private static volatile float terrainFogEndAtRender = -1;
    private static volatile float terrainFogStartAtRender = -1;
    public static float getTerrainFogEndAtRender() { return terrainFogEndAtRender; }

    public static float getTerrainFogStartAtRender() { return terrainFogStartAtRender; }
    public static float getLastRenderFogEnd() { return lastRenderFogEnd; }
    public static float getLastRenderVanillaFar() { return lastRenderVanillaFar; }
    public static boolean wasLastRenderSkipped() { return lastRenderSkipped; }

    private static boolean visionRestricted() {
        var mc = Minecraft.getInstance();
        if (mc.options == null || mc.gameRenderer == null) {
            lastRenderSkipped = false;
            return false;
        }
        if (!(mc.gameRenderer.getMainCamera().getEntity()
                instanceof net.minecraft.world.entity.LivingEntity living)) {
            lastRenderSkipped = false;
            return false;
        }

        //Work out how far vanilla lets the player see, using vanilla's own formulas rather than reading
        //back a fog value. Reading it back does not work here: several setupFog calls run per frame with
        //different far planes (our own GameRenderer.getDepthFar wrap raises one of them to 32*4*srd), so
        //whichever call happens to be last leaves a number that means nothing without knowing which far
        //plane produced it. Computing it directly needs no such context.
        float viewDistance = mc.options.getEffectiveRenderDistance() * 16.0f;
        float restricted = Float.MAX_VALUE;

        var blindness = living.getEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
        if (blindness != null) {
            //FogRenderer.BlindnessFogFunction
            restricted = blindness.isInfiniteDuration()
                    ? 5.0f
                    : net.minecraft.util.Mth.lerp(
                            Math.min(1.0f, blindness.getDuration() / 20.0f), viewDistance, 5.0f);
        }

        var darkness = living.getEffect(net.minecraft.world.effect.MobEffects.DARKNESS);
        if (darkness != null) {
            //FogRenderer.DarknessFogFunction. Blend factor ramps over 22 ticks at each end, so this
            //tracks the pulse instead of snapping the world away the instant the effect appears.
            float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);
            float f = net.minecraft.util.Mth.lerp(
                    darkness.getBlendFactor(living, partialTick), viewDistance, 15.0f);
            restricted = Math.min(restricted, f);
        }

        lastRenderFogEnd = restricted;
        lastRenderVanillaFar = viewDistance;
        //Only once vanilla is actually showing less than the player's normal view is there nothing left
        //for the LOD to add.
        boolean skip = restricted < viewDistance * 0.9f;
        lastRenderSkipped = skip;
        return skip;
    }

    public void renderOpaque(Viewport<?> viewport) {
        if (viewport == null) {
            return;
        }
        if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Cannot render Voxy with an empty viewport");
            return;
        }
        terrainFogStartAtRender = RenderSystem.getShaderFogStart();
        terrainFogEndAtRender = RenderSystem.getShaderFogEnd();
        if (visionRestricted()) {
            return;
        }

        //Cheap and idempotent; done here so the profiler can attribute work to the render thread
        //without a ThreadLocal on every instrumented call
        me.cortex.voxy.commonImpl.VoxyProfile.markRenderThread();
        //The LOD pass itself. Without this the profile's render-thread total counts only the distant
        //Create renderers hanging off the pipeline hook - the terrain path that dominates the frame is
        //absent, so the total reads far lower than what voxy actually spends.
        long tLod = me.cortex.voxy.commonImpl.VoxyProfile.begin();
        TimingStatistics.resetSamplers();

        TimingStatistics.all.start();
        //Marks the frame as in-flight so the capture watchdog can sample this thread mid-stall
        me.cortex.voxy.client.FrameProfiler.onFrameStart();
        GPUTiming.INSTANCE.marker();//Start marker
        TimingStatistics.main.start();

        for (int i = 0; i < this.savedBufferBindings.length; i++) {
            this.savedBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        //Assert the depth state rather than trusting whatever the previous renderer left behind - a
        //foreign depthFunc/mask makes edge terrain flicker, with and without shaders.
        com.mojang.blaze3d.platform.GlStateManager._enableDepthTest();
        com.mojang.blaze3d.platform.GlStateManager._depthFunc(this.properties.closerEqualDepthCompare());
        com.mojang.blaze3d.platform.GlStateManager._depthMask(true);

        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int boundFB = oldFB;

        glGetIntegerv(GL_VIEWPORT, this.viewportDimensions);

        glViewport(0, 0, viewport.width, viewport.height);

        if (boundFB == 0) {
            throw new IllegalStateException("Cannot use the default framebuffer as cannot source from it");
        }

        this.pipeline.preSetup(viewport);

        TimingStatistics.E.start();
        //"CB": the hole-punch mask rasterises one AABB per sodium-visible section at full viewport
        //resolution, so its cost tracks how many chunk meshes are loaded rather than anything voxy
        //controls. TimingStatistics.E only measures the submission, which reads ~0 no matter how
        //expensive the fill is - this GPU marker is the only way to see the real number.
        GPUTiming.INSTANCE.marker("CB");
        if (!VoxyClient.disableSodiumChunkRender() && !IrisUtil.irisShadowActive()) {
            this.chunkBoundRenderer.render(viewport);
        } else {
            viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
            //The clear wipes content the reuse-keys can't see changing
            viewport.invalidateChunkMask();
        }
        TimingStatistics.E.stop();


        GPUTiming.INSTANCE.marker();
        //Before the pipeline uploads and draws this frame's geometry: sections built off-thread can
        //carry blend-palette indices whose colours are still only queued
        this.modelService.drainBlendPalette();
        // Run the LOD pipeline.
        this.pipeline.runPipeline(viewport, boundFB, this.viewportDimensions[2], this.viewportDimensions[3]);
        GPUTiming.INSTANCE.marker();


        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        PrintfDebugUtil.tick();

        //As much dynamic runtime stuff here
        {
            //Tick upload stream (this is ok to do here as upload ticking is just memory management)
            UploadStream.INSTANCE.tick();

            this.renderDistanceTracker.setProcessRate(this.getTopLevelNodeProcessRate());
            //Hot-follow the config field every frame (the tracker early-exits when unchanged, one
            //int compare): only two call sites push a distance change into the live ring - the ctor
            //and the GUI slider - so a change written by any other path (NeoForge Mods->Config
            //Reloading, commands, another mod) leaves the ring at the old radius until renderer
            //recreation. Following the field here makes every write converge within a frame.
            this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            //Same hot-follow treatment for the array pool budget (the setter early-exits when
            //unchanged; WorldSection is common code and cannot read client config itself)
            me.cortex.voxy.common.world.WorldSection.setArrayPoolCapMiB(VoxyConfig.CONFIG.sectionArrayPoolMiB);
            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ)
                    && VoxyClient.isFrexActive()) {
            }
            TimingStatistics.H.start();
            // Done here as it allows less GL state resetup. The budget is read from config every
            // frame, so changing the LOD build pressure option is hot-reloadable and does not need
            // renderer recreation.
            long modelBakeBudget = this.getModelBakeBudgetNanos();
            this.modelService.tick(modelBakeBudget);
            TimingStatistics.H.stop();
        }
        GPUTiming.INSTANCE.marker();
        TimingStatistics.postDynamic.stop();

        GPUTiming.INSTANCE.tick();

        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
        glViewport(this.viewportDimensions[0], this.viewportDimensions[1],
                this.viewportDimensions[2], this.viewportDimensions[3]);

        {//Reset state manager stuffs
            glUseProgram(0);
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);

            GlStateManager._glBindVertexArray(0);//Clear binding

            //One pass over the units, iris' own tracking array included via the pipeline override -
            //the separate 16-unit bindSamplerToUnit loop this replaces existed only because clearing
            //GL state alone leaves iris' cached bindings stale
            this.pipeline.clearTextureAndSamplerBindings(CLEARED_TEXTURE_BINDING_COUNT);

            // Restore the shader-storage bindings captured before the LOD pass.
        for (int i = 0; i < this.savedBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, this.savedBufferBindings[i]);
            }

        }

        TimingStatistics.all.stop();
        me.cortex.voxy.commonImpl.VoxyProfile.end("render/lodPipeline", tLod);

        //No-op unless a capture is armed (/voxy debug capture)
        me.cortex.voxy.client.FrameProfiler.onFrameEnd();
    }



    private long getModelBakeBudgetNanos() {
        int pressure = VoxyConfig.CONFIG.getRenderPressureLevel();
        int fps = Minecraft.getInstance().getFps();
        if (fps <= 0) {
            fps = 60;
        }

        int renderTasks = this.renderGen.getTaskCount();

        // When FPS is already low or the section generation queue is backing up, spend less
        // render-thread time on model baking. This keeps movement smooth and lets LOD catch up
        // when the CPU/GPU has headroom again.
        if (fps < 40 || renderTasks > 1_000) {
            return MODEL_BAKE_BUDGET_LOW_FPS[pressure];
        }
        if (fps < 55 || renderTasks > 400) {
            return MODEL_BAKE_BUDGET_BUSY[pressure];
        }
        return MODEL_BAKE_BUDGET_IDLE[pressure];
    }

    private int getTopLevelNodeProcessRate() {
        return TOP_LEVEL_NODE_PROCESS_RATE[VoxyConfig.CONFIG.getRenderPressureLevel()];
    }


    //Left uncalled, as in the base. It raises subDivisionSize by INCREASE_PER_SECOND/fps every frame
    //that fps < 55 and persists the result to the config, so one heavy session ratchets a hand-tuned 28
    //up to 126 and leaves every distant LOD mushy for good - worst head-on, since the subdivision test's
    //screen-space metric is smallest at screen centre. Wire it up only with a decay path and no persist.
    private void autoBalanceSubDivSize() {
        // Only raise quality when the mesh queue is under control.
        boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
        int fps = Minecraft.getInstance().getFps();
        int MIN_FPS = 55;
        int MAX_FPS = 65;
        float INCREASE_PER_SECOND = 60;
        float DECREASE_PER_SECOND = 30;
        if (fps < MIN_FPS) {
            VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize + INCREASE_PER_SECOND / Math.max(1f, fps), 256);
        }

        if (MAX_FPS < fps && canDecreaseSize) {
            VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize - DECREASE_PER_SECOND / Math.max(1f, fps), 28);
        }
    }

    public static float getRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }

    private Matrix4f computeProjectionMat(RenderProperties properties, Matrix4fc base) {

        // Preserve projection changes applied by Minecraft, such as view bobbing.
        var rawMCProj = RenderSystem.getProjectionMatrix();
        var extraProjection = rawMCProj.invert(this.projectionScratch).mul(base);

        float near = getRenderDistance() <= 32.0f ? 8.0f : 16.0f;
        near = VoxyClient.disableSodiumChunkRender() ? 0.1f : near;

        float far = 16 * 3000;

        // Reverse-Z swaps the near and far mapping.
        if (properties.isReverseZ()) {
            float tmp = near;
            near = far;
            far = tmp;
        }

        return extraProjection.mulLocal(
                this.modifiedProjectionScratch.set(rawMCProj)
                .m22((properties.isZero2One()?far:(far+near)) / (near - far))
                .m32((properties.isZero2One()?far:(far+far)) * near / (near - far))
        );
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) {
            return false;
        }
        UploadStream.INSTANCE.tick();
        this.modelService.tick(100_000_000);
        GL11.glFinish();
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount() != 0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance + 1));
    }

    public Viewport<?> getViewport() {
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.viewportSelector.getViewport();
    }

    public int getSableOcclusionDepthTexture() {
        return this.pipeline.getSableOcclusionDepthTexture();
    }

    //The pipeline type is fixed at world entry (shader state at creation time); path selection for
    //the distant train/track renderers must follow it, not the live shader toggle.
    public boolean isIrisPipeline() {
        return this.pipeline instanceof IrisVoxyRenderPipeline;
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("Buf/Tex [#/Mb]: [" + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize()/1_000_000) + "],[" + GlTexture.getCount() + "/" + (GlTexture.getEstimatedTotalSize()/1_000_000)+"]");
        //Sodium-visible sections drive the hole-punch mask's fill cost (see the "CB" GPU marker)
        debug.add("Mask sections (sodium visible): " + this.chunkBoundRenderer.getLastRenderedSectionCount()
                + (VoxyConfig.CONFIG.experimentalChunkMaskReuse
                        ? " | maskReuse: " + this.chunkBoundRenderer.describeReuseState()
                        : ""));
        {
            this.modelService.addDebugData(debug);
            this.renderGen.addDebugData(debug);
            this.nodeManager.addDebug(debug);
            this.pipeline.addDebug(debug);
        }
        {
            TimingStatistics.update();
            debug.add("Voxy frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", " + TimingStatistics.main.pVal()+ ", " + TimingStatistics.postDynamic.pVal()+ ", " + TimingStatistics.all.pVal());
            debug.add("Voxy LOD build pressure: " + VoxyConfig.CONFIG.getRenderPressureLevel() + ", model bake budget ns: " + this.getModelBakeBudgetNanos() + ", node process rate: " + this.getTopLevelNodeProcessRate());
            debug.add("Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", " + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal());
            debug.add("Extra 2 time: " + TimingStatistics.E.pVal() + ", " + TimingStatistics.F.pVal() + ", " + TimingStatistics.G.pVal() + ", " + TimingStatistics.H.pVal() + ", " + TimingStatistics.I.pVal());
        }
        debug.add(GPUTiming.INSTANCE.getDebug());
        PrintfDebugUtil.addToOut(debug);
    }

    //Fuse: log-and-continue per component. One throw must not skip the frees behind it - the
    //geometry buffer and atlas in particular return to the reuse cache here, and a skipped return
    //means the next system allocates a second full-size copy.
    private static void shutdownStep(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            Logger.error("Error shutting down renderer component: " + what, t);
        }
    }

    public void addMemoryDebug(StringBuilder sb) {
        sb.append(String.format("  geometry store      %,d MiB committed / %,d MiB used%n",
                ((BasicSectionGeometryData) this.geometryData).getCommittedBytes() >> 20,
                this.nodeManager.getUsedGeometryCapacity() >> 20));
        sb.append(String.format("  gl objects          buf %,d (%,d MiB), tex %,d (%,d MiB)%n",
                GlBuffer.getCount(), GlBuffer.getTotalSize() >> 20,
                GlTexture.getCount(), GlTexture.getEstimatedTotalSize() >> 20));
        int[] q = this.nodeManager.queueDepths();
        sb.append(String.format("  node queues         req %,d, rem %,d, geom %,d, child %,d%n",
                q[0], q[1], q[2], q[3]));
        sb.append(String.format("  upload/download     %,d+%,d MiB rings, %,d/%,d KiB in flight%n",
                UploadStream.INSTANCE.getRingCapacityBytes() >> 20,
                DownloadStream.INSTANCE.getRingCapacityBytes() >> 20,
                UploadStream.INSTANCE.getRingUsedBytes() >> 10,
                DownloadStream.INSTANCE.getRingUsedBytes() >> 10));
    }

    public void shutdown() {
        Logger.info("Flushing download stream");
        //Through the fuse like everything else: a throw here (context reset tripping a fence
        //check) runs ahead of every shutdownStep and would skip the geometry-buffer return, the
        //atlas release and the bakery join in one stroke
        shutdownStep("download stream flush", DownloadStream.INSTANCE::flushWaitClear);
        Logger.info("Shutting down rendering");
        shutdownStep("callbacks", () -> {
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);
        });
        shutdownStep("node manager", this.nodeManager::stop);
        shutdownStep("model service", this.modelService::shutdown);
        shutdownStep("render generation", this.renderGen::shutdown);
        shutdownStep("traversal", this.traversal::free);
        shutdownStep("node cleaner", this.nodeCleaner::free);
        shutdownStep("geometry store", () -> {
            try {
                this.geometryData.free();
            } finally {
                //Return the buffer even if the decommit threw: re-commit over live pages is
                //idempotent (ARB_sparse_buffer); an orphaned multi-GiB buffer object is gone until
                //full instance shutdown.
                if (((BasicSectionGeometryData)this.geometryData).isExternalGeometryBuffer) {
                    RenderResourceReuse.giveBackGeometryBuffer(((BasicSectionGeometryData)this.geometryData).getGeometryBuffer());
                }
            }
        });
        shutdownStep("chunk bound renderer", this.chunkBoundRenderer::free);
        shutdownStep("viewports", this.viewportSelector::free);
        Logger.info("Shutting down render pipeline");
        shutdownStep("pipeline", this.pipeline::free);

        Logger.info("Flushing download stream");
        try {
            shutdownStep("download stream final flush", DownloadStream.INSTANCE::flushWaitClear);
        } finally {
            //The world ref release is the one step that must never be skipped: a held ref keeps
            //the engine (and its RocksDB) open for the rest of the process
            this.worldIn.releaseRef();
        }
        Logger.info("Render shutdown completed");
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }
}
