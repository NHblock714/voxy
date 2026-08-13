package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;

//Rasterizes an AABB per vanilla-rendered chunk section into the depth bounding buffer - the mask
//that keeps LOD terrain from drawing over the vanilla area.
//
//The section set is streamed from sodium's own render-list traversal each time it rebuilds
//(MixinSectionCollector), not tracked from build/unload events: the mask then covers exactly the
//sections sodium draws this frame. Punching by "every built section in range" both over-punched
//(sections built but not drawn - the void ring at the render distance edge, sections mid-rebuild)
//and paid for tens of thousands of boxes when only the visible few thousand matter.
public class ChunkBoundRenderer {
    private static final int INIT_MAX_SECTION_COUNT = 1<<12;

    private GlBuffer chunkPosBuffer = new GlBuffer(INIT_MAX_SECTION_COUNT*8);//Stored as ivec2
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final Shader rasterShader;
    private final RenderProperties properties;

    //CPU-side stream of visible section positions (2 ints each), mirrored to the gpu buffer when
    //it changes. Sodium 0.8 re-streams this every frame even for an identical set - `changed`
    //means "re-streamed", and only the content hash above knows whether anything really moved.
    private int[] visibleSections = new int[INIT_MAX_SECTION_COUNT*2];
    private int count;
    private boolean changed;

    private final AbstractRenderPipeline pipeline;
    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
        this.properties = pipeline.properties;

        String vert = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
        String taa = pipeline.taaFunction("getTAA");
        if (taa != null) {
            this.pipeline = pipeline;
            vert = vert+"\n\n\n"+taa;
        } else {
            this.pipeline = null;
        }

        this.rasterShader = Shader.makeAuto()
                .addSource(ShaderType.VERTEX, vert)
                .defineIf("TAA", taa != null)
                .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
                .apply(this.properties::apply)
                .compile()
                .ubo(0, this.uniformBuffer)
                .ssbo(1, this.chunkPosBuffer);
    }

    //Called from sodium's section-list traversal for every visible built section
    public void put(long pos) {
        this.visibleSections[this.count++] = (int) pos;
        this.visibleSections[this.count++] = (int) (pos >>> 32);
        if (this.count >= this.visibleSections.length - 2) {
            this.visibleSections = Arrays.copyOf(this.visibleSections, (int) (this.visibleSections.length * 1.25));
        }
    }

    //Sodium 0.8 restarts this stream EVERY frame (async occlusion culling re-collects the list),
    //so "the stream restarted" carries no information about the set actually changing. Reuse keys
    //on content instead: the streamed positions are hashed, and only a hash/count change advances
    //the generation - which is also what gates the GPU upload, so an unchanged set skips both the
    //re-raster and the re-upload. Not keyed on `changed` either way: that flag is consumed by the
    //first viewport to upload, so a second viewport the same frame would wrongly keep a mask built
    //from the previous section set.
    private long uploadedHash;
    private int uploadedCount = -1;
    private int contentGeneration;

    //Called when sodium starts rebuilding its render list (and on level swap)
    public void reset() {
        this.count = 0;
        this.changed = true;
    }

    //How many sodium-visible sections the last mask pass rasterised. This is what the mask's fill cost
    //scales with, so it is the number to read when chunk-mesh count is suspected of driving frame time.
    private int lastRenderedSectionCount;

    public int getLastRenderedSectionCount() {
        return this.lastRenderedSectionCount;
    }

    //Chunk-mask reuse (experimentalChunkMaskReuse) tallies, for the F3 line. reuseFail attributes
    //each non-reused frame to the FIRST key that failed (content set changed / buffer invalidated /
    //camera moved / MVP changed / render distance changed) - the split is the diagnostic for why
    //the reuse rate is what it is.
    private long reusedMaskFrames, rasterisedMaskFrames;
    private final long[] reuseFail = new long[5];

    public long getReusedMaskFrames() { return this.reusedMaskFrames; }
    public long getRasterisedMaskFrames() { return this.rasterisedMaskFrames; }

    public String describeReuseState() {
        return "kept " + this.reusedMaskFrames + " raster " + this.rasterisedMaskFrames
                + " gen " + this.contentGeneration
                + " fail[set=" + this.reuseFail[0] + ",inv=" + this.reuseFail[1]
                + ",cam=" + this.reuseFail[2] + ",mvp=" + this.reuseFail[3]
                + ",rd=" + this.reuseFail[4] + "]";
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    public void render(Viewport<?> viewport) {
        final float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance()*16;//In blocks

        if (VoxyConfig.CONFIG.experimentalChunkMaskReuse && this.changed) {
            //Resolve whether the re-streamed set actually differs. Order-dependent FNV over the raw
            //stream: sodium's traversal order is deterministic for an unchanged graph+camera, and a
            //differing order merely hashes as "changed" - a wasted rebuild, never a stale mask.
            long h = 0xcbf29ce484222325L;
            for (int i = 0; i < this.count; i++) {
                h = (h ^ this.visibleSections[i]) * 0x100000001b3L;
            }
            if (h == this.uploadedHash && this.count == this.uploadedCount) {
                this.changed = false;//Identical bytes are already on the GPU - nothing to upload
            } else {
                this.uploadedHash = h;
                this.uploadedCount = this.count;
                this.contentGeneration++;//Real set change - invalidates every held mask
            }
        }

        //The buffer already holds the mask for exactly these inputs - keep it. The content
        //generation covers the section set, the camera/MVP/distance keys cover every uniform the
        //raster reads, and chunkMaskValid covers external clears/resizes. Shader-pack TAA is
        //deliberately NOT a key: a held mask freezes one jitter phase, but the mask is the
        //conservative silhouette of whole section AABBs in the far-field overlap band - the frozen
        //phase steps forward on every rebuild and the pack's TAA resolve absorbs the sub-pixel
        //nudge. (Keying on it locks reuse out entirely under packs that declare TAA - every raster
        //jitters, so no two frames ever match.) Evaluated key-by-key so a failed frame is
        //attributed to what actually broke it.
        //Under shader-pack TAA (this.pipeline != null) the mask is never reused: the seam is only
        //stable when the mask re-rasterises every frame with THAT frame's jitter. Both cheats fail
        //in the field - a reused jittered mask freezes its phase and flickers on every re-raster
        //jump, and an unjittered mask shimmers per frame against the jittered terrain around it.
        //The content-hash upload squash above stays active either way; it is phase-independent.
        if (VoxyConfig.CONFIG.experimentalChunkMaskReuse && this.pipeline == null) {
            if (viewport.chunkMaskContentGen != this.contentGeneration) {
                this.reuseFail[0]++;
            } else if (!viewport.chunkMaskValid) {
                this.reuseFail[1]++;
            } else if (viewport.chunkMaskCamX != viewport.cameraX
                    || viewport.chunkMaskCamY != viewport.cameraY
                    || viewport.chunkMaskCamZ != viewport.cameraZ) {
                this.reuseFail[2]++;
            } else if (!viewport.MVP.equals(viewport.chunkMaskMVP)) {
                this.reuseFail[3]++;
            } else if (viewport.chunkMaskRenderDistance != renderDistance) {
                this.reuseFail[4]++;
            } else {
                this.reusedMaskFrames++;
                //Leave the GL state exactly as the raster path does - downstream passes inherit it
                glEnable(GL_CULL_FACE);
                glEnable(GL_DEPTH_TEST);
                glDepthFunc(this.properties.closerEqualDepthCompare());
                return;
            }
        }
        this.rasterisedMaskFrames++;

        viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());

        int sectionCount = this.count >> 1;
        this.lastRenderedSectionCount = sectionCount;
        if (sectionCount == 0) {
            this.recordMaskInputs(viewport, renderDistance);
            return;
        }

        if (this.changed) {
            if (this.count * 4L > this.chunkPosBuffer.size()) {
                this.chunkPosBuffer.free();
                this.chunkPosBuffer = new GlBuffer((long) Math.ceil(this.count * 1.25) * 4L);
                ((AutoBindingShader) this.rasterShader).ssbo(1, this.chunkPosBuffer);
            }
            long ptr = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 0, this.count * 4L);
            for (int i = 0; i < this.count; i++) {
                MemoryUtil.memPutInt(ptr + i * 4L, this.visibleSections[i]);
            }
            UploadStream.INSTANCE.commit();
            this.changed = false;
        }

        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr; ptr += 4*4*4;

        {//This is recomputed to be in chunk section space not worldsection

            //Camera block pos. floor, not a cast: a cast truncates toward zero, so at negative
            //coordinates it lands one block the wrong way and the sub-block remainder below comes out
            //negative, shifting the whole mask by a block on that side of the origin.
            int bx = (int)Math.floor(viewport.cameraX);
            int by = (int)Math.floor(viewport.cameraY);
            int bz = (int)Math.floor(viewport.cameraZ);
            new Vector3i(bx, by, bz).getToAddress(ptr); ptr += 4*4;

            var negInnerBlock = new Vector3f(
                    (float) (viewport.cameraX - bx),
                    (float) (viewport.cameraY - by),
                    (float) (viewport.cameraZ - bz));


            negInnerBlock.getToAddress(ptr); ptr += 4*3;
            viewport.MVP.translate(negInnerBlock.negate(), new Matrix4f()).getToAddress(matPtr);
            MemoryUtil.memPutFloat(ptr, renderDistance); ptr += 4;
        }
        UploadStream.INSTANCE.commit();


        {
            //need to reverse the winding order since we want the back faces of the AABB, not the front

            glFrontFace(GL_CW);//Reverse winding order

            //"reverse depth buffer" it goes from 0->1 where 1 is far away
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(this.properties.furtherDepthCompare());
        }

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        viewport.depthBoundingBuffer.bind();
        this.rasterShader.bind();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
        if (this.pipeline != null) this.pipeline.bindUniforms();//shader TAA

        //Batch the draws into groups of size 32
        if (sectionCount >= 32) {
            glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, sectionCount/32);
        }
        if (sectionCount%32 != 0) {
            glDrawElementsInstancedBaseInstance(GL_TRIANGLES, 6 * 2 * 3 * (sectionCount%32), GL_UNSIGNED_BYTE, 0, 1, (sectionCount/32)*32);
        }

        {
            glFrontFace(GL_CCW);//Restore winding order

            glDepthFunc(this.properties.closerEqualDepthCompare());

            //TODO: check this is correct
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }

        this.recordMaskInputs(viewport, renderDistance);
    }

    private void recordMaskInputs(Viewport<?> viewport, float renderDistance) {
        viewport.chunkMaskMVP.set(viewport.MVP);
        viewport.chunkMaskCamX = viewport.cameraX;
        viewport.chunkMaskCamY = viewport.cameraY;
        viewport.chunkMaskCamZ = viewport.cameraZ;
        viewport.chunkMaskRenderDistance = renderDistance;
        viewport.chunkMaskContentGen = this.contentGeneration;
        viewport.chunkMaskValid = true;
    }

    public void free() {
        this.rasterShader.free();
        this.uniformBuffer.free();
        this.chunkPosBuffer.free();
    }
}
