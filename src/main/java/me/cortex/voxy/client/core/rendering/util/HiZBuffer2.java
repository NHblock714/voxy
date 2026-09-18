package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.common.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.GL33C.glDeleteSamplers;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL42C.*;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;

//Same contract as HiZBuffer - texture id, packed pixel size, buildMipChain/free - with the levels
//above 0 written by compute dispatches instead of one fullscreen draw per level. Level 0 keeps the
//same fullscreen gather (blit.fsh under the same defines) so the chain reads identically; the
//folds above it go through shared memory only, so no subgroup layout assumption is involved.
//Storage is R32F because depth formats cannot be bound as images, which also means no level of
//this chain can serve as a depth attachment. A shader that fails to compile degrades to the
//inherited draw chain, so a holder never has to know which one is active.
public class HiZBuffer2 extends HiZBuffer {
    private static final int LEVELS_PER_DISPATCH = 6;
    private static final int TILE = 64;
    private static boolean computeBroken;
    private static boolean fallbackLogged;

    private final RenderProperties properties;
    private final Shader mip;
    private final Shader level0;
    private final GlFramebuffer fb;
    private final int sampler;
    private GlTexture texture;
    private int levels;
    private int width;
    private int height;

    public static HiZBuffer createOrFallback(RenderProperties properties) {
        if (computeBroken) {
            logFallback("experimentalHiZCompute: compute hi-z unavailable on this GPU/driver, using the draw chain", null);
            //Still reports itself as a fallback: the log line fires once per process, so this is
            //what tells a later renderer's F3 line apart from the flag being off
            return new HiZBuffer(properties) {
                @Override
                public String describe() {
                    return "draw(compute fallback)";
                }
            };
        }
        return new HiZBuffer2(properties);
    }

    private static void logFallback(String msg, Throwable cause) {
        if (fallbackLogged) {
            return;
        }
        fallbackLogged = true;
        if (cause != null) {
            Logger.error(msg, cause);
        } else {
            Logger.warn(msg);
        }
    }

    private HiZBuffer2(RenderProperties properties) {
        super(properties);
        this.properties = properties;
        Shader mipL = null;
        Shader level0L = null;
        try {
            mipL = Shader.make()
                    .apply(properties::apply)
                    .add(ShaderType.COMPUTE, "voxy:hiz/hiz.comp")
                    .compile()
                    .name("HiZ Mip");
            level0L = Shader.make()
                    .apply(properties::apply)
                    .define("OUTPUT_COLOUR")
                    .add(ShaderType.VERTEX, "voxy:hiz/blit.vsh")
                    .add(ShaderType.FRAGMENT, "voxy:hiz/blit.fsh")
                    .compile()
                    .name("HiZ Level0");
        } catch (RuntimeException e) {
            if (mipL != null) {
                mipL.free();
                mipL = null;
            }
            computeBroken = true;
            logFallback("experimentalHiZCompute: compute hi-z shaders failed to compile, using the draw chain", e);
        }
        this.mip = mipL;
        this.level0 = level0L;
        if (mipL == null) {
            this.fb = null;
            this.sampler = 0;
            return;
        }
        this.fb = new GlFramebuffer().name("HiZ Compute");
        glNamedFramebufferDrawBuffer(this.fb.id, GL_COLOR_ATTACHMENT0);
        this.sampler = glGenSamplers();
        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    private void alloc(int width, int height) {
        //Same level count expression as the draw chain, so both allocate the same levels
        this.levels = (int)Math.ceil(Math.log(Math.max(width, height))/Math.log(2));
        this.texture = new GlTexture().store(GL_R32F, this.levels, width, height).name("HiZ Compute");
        glTextureParameteri(this.texture.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glTextureParameteri(this.texture.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        this.width  = width;
        this.height = height;

        this.fb.bind(GL_COLOR_ATTACHMENT0, this.texture, 0).verify();
    }

    @Override
    public void buildMipChain(int srcDepthTex, int width, int height) {
        if (this.mip == null) {
            super.buildMipChain(srcDepthTex, width, height);
            return;
        }
        int w = Integer.highestOneBit(width);
        int h = Integer.highestOneBit(height);
        if (this.width != w || this.height != h) {
            if (this.texture != null) {
                this.texture.free();
                this.texture = null;
            }
            this.alloc(w, h);
        }

        int boundFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        //Level 0 is a colour write, so blend and the colour mask decide whether it lands at all
        boolean blend = glIsEnabled(GL_BLEND);
        boolean maskR, maskG, maskB, maskA;
        try (var stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            maskR = mask.get(0) != 0;
            maskG = mask.get(1) != 0;
            maskB = mask.get(2) != 0;
            maskA = mask.get(3) != 0;
        }

        {//Level 0: the same fullscreen gather as the draw chain, written as colour
            glBindVertexArray(GlVertexArray.STATIC_VAO);
            this.level0.bind();
            glBindFramebuffer(GL_FRAMEBUFFER, this.fb.id);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glColorMask(true, true, true, true);
            glBindTextureUnit(0, srcDepthTex);
            glBindSampler(0, this.sampler);
            glUniform1i(0, 0);
            glViewport(0, 0, this.width, this.height);
            glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
            glBindVertexArray(0);
            glBindFramebuffer(GL_FRAMEBUFFER, boundFB);
        }
        if (blend) {
            glEnable(GL_BLEND);
        }
        glColorMask(maskR, maskG, maskB, maskA);

        {//Levels 1..: each dispatch reads one level and writes the next six; the framebuffer write
         //above is ordered before the first dispatch by GL itself (the fb is unbound before it), the
         //image stores need the barrier before the next dispatch or the traversal fetches them
            this.mip.bind();
            glBindTextureUnit(0, this.texture.id);
            glBindSampler(0, this.sampler);
            int src = 0;
            int srcW = this.width;
            int srcH = this.height;
            while (src < this.levels - 1) {
                int out = Math.min(LEVELS_PER_DISPATCH, this.levels - 1 - src);
                for (int i = 1; i <= LEVELS_PER_DISPATCH; i++) {
                    if (i <= out) {
                        glBindImageTexture(i, this.texture.id, src + i, false, 0, GL_WRITE_ONLY, GL_R32F);
                    } else {
                        glBindImageTexture(i, 0, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
                    }
                }
                glUniform1i(0, src);
                glUniform1i(1, out);
                glDispatchCompute((srcW + TILE - 1) / TILE, (srcH + TILE - 1) / TILE, 1);
                glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                src += out;
                srcW = Math.max(srcW >> out, 1);
                srcH = Math.max(srcH >> out, 1);
            }
            for (int i = 1; i <= LEVELS_PER_DISPATCH; i++) {
                glBindImageTexture(i, 0, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
            }
        }

        //Leave the depth state exactly as the draw chain does
        glDepthMask(true);
        glDepthFunc(this.properties.closerEqualDepthCompare());
        glDisable(GL_DEPTH_TEST);
        glViewport(0, 0, width, height);
    }

    @Override
    public void free() {
        super.free();
        if (this.mip == null) {
            return;
        }
        this.fb.free();
        if (this.texture != null) {
            this.texture.free();
            this.texture = null;
        }
        glDeleteSamplers(this.sampler);
        this.level0.free();
        this.mip.free();
    }

    @Override
    public int getHizTextureId() {
        return this.mip == null ? super.getHizTextureId() : this.texture.id;
    }

    @Override
    public int getPackedLevels() {
        return this.mip == null ? super.getPackedLevels() : (this.width<<16)|this.height;
    }

    @Override
    public String describe() {
        return this.mip == null ? "draw(compute fallback)" : "compute";
    }
}
