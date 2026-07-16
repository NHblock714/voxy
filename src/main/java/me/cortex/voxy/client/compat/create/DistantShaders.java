package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL20C.nglUniformMatrix4fv;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

//Programs and binding helpers for the distant mesh pipeline. Compiled lazily on the render thread.
//On the iris pipeline the fragment shader gets the shader pack's voxy patch appended
//(patchOpaqueShader), so our fragments write the full g-buffer exactly like LOD terrain - the
//shader pack's existing voxy support covers us with no per-pack work.
public final class DistantShaders {
    private static Shader vertexLight;
    private static Shader uniformLight;

    private static Shader patchedVertexLight;
    private static Shader patchedUniformLight;
    private static AbstractRenderPipeline patchedOwner;
    private static boolean patchFailed;

    private DistantShaders() {}

    //uniformLightVariant: per-draw light uniform (moving carriages) vs per-vertex baked light (tracks)
    public static Shader forPipeline(AbstractRenderPipeline pipeline, boolean uniformLightVariant) {
        String patch = null;
        try {
            patch = pipeline.patchOpaqueShader(null, "");
        } catch (Throwable ignored) {
        }
        boolean patched = patch != null;
        if (!patched) {
            return uniformLightVariant ? uniformLight() : vertexLight();
        }

        //Patch content follows the pipeline instance (i.e. the loaded shader pack)
        if (patchedOwner != pipeline) {
            freePatched();
            patchedOwner = pipeline;
            patchFailed = false;
        }
        if (patchFailed) {
            return uniformLightVariant ? uniformLight() : vertexLight();
        }
        try {
            if (uniformLightVariant) {
                if (patchedUniformLight == null) {
                    patchedUniformLight = compilePatched(pipeline, true);
                }
                return patchedUniformLight;
            }
            if (patchedVertexLight == null) {
                patchedVertexLight = compilePatched(pipeline, false);
            }
            return patchedVertexLight;
        } catch (Throwable e) {
            patchFailed = true;
            Logger.error("Failed to compile shader-pack patched distant shader; falling back to plain (visuals degraded under shaders)", e);
            return uniformLightVariant ? uniformLight() : vertexLight();
        }
    }

    private static Shader compilePatched(AbstractRenderPipeline pipeline, boolean uniformLightVariant) {
        String frag = pipeline.patchOpaqueShader(null, ShaderLoader.parse("voxy:compat/distant.frag"));
        return Shader.make()
                .define("PATCHED_SHADER")
                .defineIf("UNIFORM_LIGHT", uniformLightVariant)
                .add(ShaderType.VERTEX, "voxy:compat/distant.vert")
                .addSource(ShaderType.FRAGMENT, frag)
                .compile().name(uniformLightVariant ? "distant_patched_uniform" : "distant_patched_vertex");
    }

    private static void freePatched() {
        if (patchedVertexLight != null) {
            patchedVertexLight.free();
            patchedVertexLight = null;
        }
        if (patchedUniformLight != null) {
            patchedUniformLight.free();
            patchedUniformLight = null;
        }
    }

    private static Shader vertexLight() {
        if (vertexLight == null) {
            vertexLight = Shader.make()
                    .add(ShaderType.VERTEX, "voxy:compat/distant.vert")
                    .add(ShaderType.FRAGMENT, "voxy:compat/distant.frag")
                    .compile().name("distant_vertex_light");
        }
        return vertexLight;
    }

    private static Shader uniformLight() {
        if (uniformLight == null) {
            uniformLight = Shader.make()
                    .define("UNIFORM_LIGHT")
                    .add(ShaderType.VERTEX, "voxy:compat/distant.vert")
                    .add(ShaderType.FRAGMENT, "voxy:compat/distant.frag")
                    .compile().name("distant_uniform_light");
        }
        return uniformLight;
    }

    //Raw binds; the surrounding renderStateGuarded restores whatever was here before
    public static void bindTextures() {
        int atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId();
        glBindTextureUnit(0, atlas);
        glBindSampler(0, 0);
        LightMapHelper.bind(1);
    }

    public static void uploadTransform(Matrix4f transform) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var buf = stack.mallocFloat(16);
            transform.get(buf);
            nglUniformMatrix4fv(0, 1, false, org.lwjgl.system.MemoryUtil.memAddress(buf));
        }
    }

    //Nearest quarter turn of a model yaw (degrees, +Y right-handed) for the vertex shader's baked
    //face re-aim; UNIFORM_LIGHT variants only
    public static void uploadFaceRotation(float yawDegrees) {
        int steps = Math.round(yawDegrees / 90.0f) & 3;
        org.lwjgl.opengl.GL30C.glUniform1ui(5, steps);
    }
}
