package me.cortex.voxy.client.core;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL45C.*;

public class IrisVoxyRenderPipeline extends AbstractRenderPipeline {
    private final IrisVoxyRenderPipelineData data;
    private final FullscreenBlit depthBlit;
    public final DepthFramebuffer fbTranslucent = new DepthFramebuffer(this.fb.getFormat());

    private final FullscreenBlit shaderDepthHackFixTransformBlit;

    private final GlBuffer shaderUniforms;
    private final Matrix4f targetTransform = new Matrix4f();

    public IrisVoxyRenderPipeline(RenderProperties properties, IrisVoxyRenderPipelineData data, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(properties, nodeManager, nodeCleaner, traversal, frexSupplier, data.shouldDeferTranslucency());
        this.data = data;
        if (this.data.thePipeline != null) {
            //The factory catches this and falls back to the normal pipeline; the super ctor and the
            //translucent framebuffer initializer have already allocated by here. The foreign binding
            //is left alone - it belongs to whoever bound it.
            this.fbTranslucent.free();
            this.freeConstructorAllocated();
            throw new IllegalStateException("Pipeline data already bound");
        }
        this.data.thePipeline = this;

        //Mirrors of the blank finals for the failure path - the catch cannot read fields the ctor
        //might not have assigned
        GlBuffer shaderUniformsL = null;
        FullscreenBlit shaderDepthHackL = null;
        try {
            //Bind the drawbuffers
            var oDT = this.data.opaqueDrawTargets;
            //Every LOD terrain raster pass writes all of these per fragment. With a pack asking for 6-8
            //targets the same geometry costs that many times the ROP bandwidth it does without shaders,
            //which is the main reason LOD gets dramatically more expensive when a pack is loaded. Logged
            //once so the number is in the log when diagnosing a shaders-only framerate drop.
            Logger.info("Iris LOD framebuffer: " + oDT.length + " opaque draw targets, "
                    + this.data.translucentDrawTargets.length + " translucent");
            int[] binding = new int[oDT.length];
            for (int i = 0; i < oDT.length; i++) {
                binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
                glNamedFramebufferTexture(this.fb.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, oDT[i], 0);
            }
            glNamedFramebufferDrawBuffers(this.fb.framebuffer.id, binding);

            var tDT = this.data.translucentDrawTargets;
            binding = new int[tDT.length];
            for (int i = 0; i < tDT.length; i++) {
                binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
                glNamedFramebufferTexture(this.fbTranslucent.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, tDT[i], 0);
            }
            glNamedFramebufferDrawBuffers(this.fbTranslucent.framebuffer.id, binding);

            this.fb.framebuffer.verify();
            this.fbTranslucent.framebuffer.verify();

            if (data.getUniforms() != null) {
                this.shaderUniforms = shaderUniformsL = new GlBuffer(data.getUniforms().size());
            } else {
                this.shaderUniforms = null;
            }

            if (!this.data.skipShaderDepthHackFix) {
                this.shaderDepthHackFixTransformBlit = shaderDepthHackL = new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/noop.frag");
            } else {
                this.shaderDepthHackFixTransformBlit = null;
            }

            this.depthBlit = new FullscreenBlit(properties, "voxy:post/blit_texture_depth_cutout.frag");
        } catch (RuntimeException | Error e) {
            //A pack that fails framebuffer verification or shader compilation lands here; the
            //factory falls back to the normal pipeline, so the binding must come free and nothing
            //allocated above may outlive the throw
            this.data.thePipeline = null;
            if (shaderUniformsL != null) {
                shaderUniformsL.free();
            }
            if (shaderDepthHackL != null) {
                shaderDepthHackL.delete();
            }
            this.fbTranslucent.free();
            this.freeConstructorAllocated();
            throw e;
        }
    }

    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        modelService.factory.setCustomBlockStateMapping(WorldRenderingSettings.INSTANCE.getBlockStateIds());
    }

    //Any linkage break in the accessor (iris renamed its field) falls back to the bindSamplerToUnit
    //loop, which keeps iris' tracking array truthful at the old per-frame cost - never a stale bind
    private static boolean samplerAccessorBroken;

    @Override
    public void clearTextureAndSamplerBindings(int count) {
        super.clearTextureAndSamplerBindings(count);
        //Forwarded pack samplers raw-bind textures+samplers from unit 6 upward (doBindings), so a
        //pack declaring 7+ reaches past the vanilla-tracked 12 - those units must be cleared raw:
        //GlStateManager only tracks the first 12, passing a bigger count into super would overrun
        //its state array
        var imageSet = this.data.getImageSet();
        if (imageSet != null && count < 6 + imageSet.samplerCount()) {
            int upper = 6 + imageSet.samplerCount();
            for (int i = count; i < upper; i++) {
                glBindTextureUnit(i, 0);
                glBindSampler(i, 0);
            }
            count = upper;
        }
        if (samplerAccessorBroken) {
            me.cortex.voxy.client.core.util.IrisUtil.clearIrisSamplers();
            return;
        }
        try {
            int[] irisSamplerBindings = me.cortex.voxy.client.mixin.iris.IrisRenderSystemAccessor.voxy$getSamplerBindings();
            for (int i = 0; i < Math.min(count, irisSamplerBindings.length); i++) {
                irisSamplerBindings[i] = 0;
            }
        } catch (Throwable t) {
            samplerAccessorBroken = true;
            Logger.warn("Iris sampler accessor unavailable, using the per-unit fallback", t);
            me.cortex.voxy.client.core.util.IrisUtil.clearIrisSamplers();
        }
    }

    @Override
    public void free() {
        if (this.data.thePipeline != this) {
            throw new IllegalStateException();
        }
        this.data.thePipeline = null;

        this.depthBlit.delete();
        this.fbTranslucent.free();

        if (this.shaderDepthHackFixTransformBlit != null) {
            this.shaderDepthHackFixTransformBlit.delete();
        }

        if (this.shaderUniforms != null) {
            this.shaderUniforms.free();
        }

        super.free0();
    }

    @Override
    public void preSetup(Viewport<?> viewport) {
        super.preSetup(viewport);
        if (this.shaderUniforms != null) {
            //Update the uniforms
            long ptr = UploadStream.INSTANCE.uploadTo(this.shaderUniforms);
            this.data.getUniforms().updater().accept(ptr);
            UploadStream.INSTANCE.commit();
        }
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight) {
        this.fb.resize(viewport.width, viewport.height);
        this.fbTranslucent.resize(viewport.width, viewport.height);

        if (!this.data.useViewportDims) {
            srcWidth = viewport.width;
            srcHeight = viewport.height;
        }
        this.initDepthStencil(viewport, sourceFramebuffer, this.fb.framebuffer.id, srcWidth, srcHeight, viewport.width, viewport.height);
        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport, int sourceFrameBuffer) {
        if (this.shaderDepthHackFixTransformBlit != null) {
            this.fb.bind();
            glEnable(GL_DEPTH_TEST);
            glColorMask(false, false, false, false);
            glDepthFunc(GL_ALWAYS);
            glStencilFunc(GL_EQUAL, 0, 0xFF);//set the depth to 1 where the mask is 0 (hook-tagged pixels keep theirs)
            this.shaderDepthHackFixTransformBlit.blit();
            glStencilFunc(GL_EQUAL, 1, 0x1);//revert to the bit0 contract test
            glDepthFunc(this.properties.closerEqualDepthCompare());
            glColorMask(true, true, true, true);
        } else {
            //Packs that skip the depth-hack consume the raw sentinel protocol at vanilla-covered
            //pixels; the setup pass stamped reprojected depth there for the hook geometry, so
            //restore the value they expect
            this.fb.bind();
            this.restoreSentinelDepth();
        }

        glTextureBarrier();

        int msk = GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT;
        glBlitNamedFramebuffer(this.fb.framebuffer.id, this.fbTranslucent.framebuffer.id, 0,0, viewport.width, viewport.height, 0,0, viewport.width, viewport.height, msk, GL_NEAREST);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        // Iris owns the source depth buffer unless the shader pack explicitly
        // opts in to distant-horizon depth. Writing Voxy's opaque depth into it
        // unconditionally makes several packs reject/overwrite the later LOD
        // water composite.
        if (this.data.renderToVanillaDepth) {
            //The blit needs matching dimensions. A pack rendering at a scaled resolution has
            //srcWidth/srcHeight off the viewport's - under useViewportDims the source viewport can
            //be forced into the bottom-left corner for the blit and restored after; without that
            //flag the offsets are unknowable and the blit must stay skipped.
            boolean mustFiddleViewport = srcWidth != viewport.width || srcHeight != viewport.height;
            if (this.data.useViewportDims || !mustFiddleViewport) {
                glColorMask(false, false, false, false);
                if (mustFiddleViewport) {
                    glViewport(0, 0, viewport.width, viewport.height);
                }
                AbstractRenderPipeline.transformBlitDepth(this.depthBlit,
                        this.fbTranslucent.getDepthTex().id, sourceFrameBuffer,
                        viewport, this.targetTransform.set(viewport.vanillaProjection).mul(viewport.modelView));
                if (mustFiddleViewport) {
                    glViewport(0, 0, srcWidth, srcHeight);
                }
                glColorMask(true, true, true, true);
            } else {
                // normally disabled by AbstractRenderPipeline but since we are skipping it we do it here
                glDisable(GL_STENCIL_TEST);
                glDisable(GL_DEPTH_TEST);
            }
        } else {
            // normally disabled by AbstractRenderPipeline but since we are skipping it we do it here
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
        }
    }


    @Override
    public void bindUniforms() {
        this.bindUniforms(UNIFORM_BINDING_POINT);
    }

    @Override
    public void bindUniforms(int bindingPoint) {
        if (this.shaderUniforms != null) {
            GL30.glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, this.shaderUniforms.id);// todo: dont randomly select this to 5
        }
    }

    private void doBindings() {
        this.bindUniforms();
        if (this.data.getSsboSet() != null) {
            this.data.getSsboSet().bindingFunction().accept(FORWARDED_SSBO_BINDING_BASE);
        }
        if (this.data.getImageSet() != null) {
            this.data.getImageSet().bindingFunction().accept(6);
        }
    }
    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
        this.doBindings();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        this.fbTranslucent.bind();
        this.doBindings();
        if (this.data.getBlender() != null) {
            this.data.getBlender().run();
        }
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Using: " + this.getClass().getSimpleName());
        super.addDebug(debug);
    }

    @Override
    public int getSableOcclusionDepthTexture() {
        if (this.data.renderToVanillaDepth || this.fbTranslucent.getDepthTex() == null) {
            return 0;
        }
        return this.fbTranslucent.getDepthTex().id;
    }

    private static final int UNIFORM_BINDING_POINT = 7;//TODO make ths binding point... not randomly 5
    //Forwarded shader-pack SSBOs bind here. Voxy itself uses SSBO 1/2/5, and VoxyRenderSystem saves &
    //restores only binding points [0,10) each frame - base 6 keeps the forwarded set (6-9) inside that
    //window so it gets restored. A base past that window leaks into iris' post-voxy passes.
    //Must stay in lockstep with the GLSL "#define BUFFER_BINDING_INDEX_BASE" below.
    private static final int FORWARDED_SSBO_BINDING_BASE = 6;

    private StringBuilder buildGenericShaderHeader(AbstractSectionRenderer<?, ?> renderer, String input) {
        StringBuilder builder = new StringBuilder(input).append("\n\n\n");

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+UNIFORM_BINDING_POINT+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (this.data.getSsboSet() != null) {
            builder.append("#define BUFFER_BINDING_INDEX_BASE ").append(FORWARDED_SSBO_BINDING_BASE).append("\n");
            builder.append(this.data.getSsboSet().layout()).append("\n\n");
        }

        if (this.data.getImageSet() != null) {
            builder.append("#define BASE_SAMPLER_BINDING_INDEX 6\n");//TODO: DONT RANDOMLY MAKE THIS 6
            builder.append(this.data.getImageSet().layout()).append("\n\n");
        }

        return builder.append("\n\n");
    }



    @Override
    public String patchOpaqueShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        var builder = this.buildGenericShaderHeader(renderer, input);

        builder.append(this.data.opaqueFragPatch());

        return builder.toString();
    }

    @Override
    public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        if (this.data.translucentFragPatch() == null) return null;

        var builder = this.buildGenericShaderHeader(renderer, input);
        builder.append(this.data.translucentFragPatch());
        return builder.toString();
    }

    @Override
    public boolean hasTAA() {
        return this.data.TAA != null;
    }

    @Override
    public String taaFunction(String functionName) {
        return this.taaFunction(UNIFORM_BINDING_POINT, functionName);
    }

    @Override
    public String taaFunction(int uboBindingPoint, String functionName) {
        if (this.data.TAA == null) {
            return null;
        }

        var builder = new StringBuilder();

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+uboBindingPoint+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        builder.append("vec2 ").append(functionName).append("()\n");
        builder.append(this.data.TAA);
        builder.append("\n");
        return builder.toString();
    }

    @Override
    public float[] getRenderScalingFactor() {
        return this.data.resolutionScale;
    }


    @Override
    protected boolean useBoundaryGuardPass() {
        return false;
    }
}
