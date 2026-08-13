package me.cortex.voxy.client.core.model.bakery;

import net.caffeinemc.mods.sodium.api.util.ColorMixer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

//Rasterizes in integer fixed point rather than float. A quad is two triangles sharing a diagonal,
//and float edge functions can disagree about pixels on that shared edge between the two passes -
//seam pixels that are missed or double-covered, differently per CPU/JIT. Integer edges make the
//coverage test exact: the strict >0 first triangle plus the >=0 second cover the diagonal exactly
//once, and the bake output is bit-identical everywhere.
public class SoftwareRasterizer {
    //9 integer bits = +-512 of screen-space COORDINATE range, which is what a projected vertex has
    //to fit in: a model reaches well past its own block, so the headroom over targetSize is the
    //point of the setting. Edge products are computed in long precisely so that they never have to
    //fit here - an int product wraps at a ~23px span and flips the area's sign, which culls both
    //triangles and bakes the quad as nothing.
    private static final int INTEGER_BITS = 9;
    private static final int TOTAL_INTEGER_BITS = INTEGER_BITS + 1;
    private static final int FIXED_POINT_BITS = 32 - TOTAL_INTEGER_BITS;
    private static final long FIXED_POINT_BIT_SCALE = (1 << FIXED_POINT_BITS) - 1;

    private final Vector4f scratch = new Vector4f();

    private final Vector3f scratch1 = new Vector3f();
    private final Vector3f scratch2 = new Vector3f();
    private final Vector3f scratch3 = new Vector3f();
    private final Vector3f scratch4 = new Vector3f();
    //quad meta uv
    private final Vector3f qmuv1 = new Vector3f();
    private final Vector3f qmuv2 = new Vector3f();
    private final Vector3f qmuv3 = new Vector3f();
    private final Vector3f qmuv4 = new Vector3f();


    private final Vector3i scratchR1 = new Vector3i();
    private final Vector3i scratchR2 = new Vector3i();
    private final Vector3i scratchR3 = new Vector3i();
    //Attributes (meta, u, v)
    private final Vector3f a1 = new Vector3f();
    private final Vector3f a2 = new Vector3f();
    private final Vector3f a3 = new Vector3f();
    private int quadColour;

    private static final long DEPTH_MASK = ((1L<<24)-1)<<(64-24);
    private static final long CLEAR_VALUE = DEPTH_MASK;//set the depth to max value and rest of bits to 0

    private final int targetSize;
    private final long[] framebuffer;

    private boolean cullBackFace;
    private boolean doTheBlending;
    private boolean replaceTranslucentColour;

    private int samplerWidth;
    private int samplerHeight;
    private int[] samplerTexture;

    public SoftwareRasterizer(int targetSize) {
        int testExpect = targetSize*targetSize;
        int testGot = fromFixed2Int(toFixed(targetSize*targetSize));
        if (testExpect != testGot) {
            throw new IllegalStateException("Target resolution not supported, not enough precision bits. got: " + testGot + ", expect: " + testExpect);
        }
        this.targetSize = targetSize;
        this.framebuffer = new long[targetSize*targetSize];
    }

    public void setFaceCull(boolean isBackFaceCulling) {
        this.cullBackFace = isBackFaceCulling;
    }

    public void setBlending(boolean blending) {
        this.setBlending(blending, false);
    }

    public void setBlending(boolean blending, boolean replaceTranslucentColour) {
        this.doTheBlending = blending;
        this.replaceTranslucentColour = replaceTranslucentColour;
    }

    public void setSamplerTexture(int[] texture, int width, int height) {
        if (texture.length != width * height) {
            throw new IllegalArgumentException("Texture dimensions do not match pixel count");
        }
        this.samplerTexture = texture;
        this.samplerWidth = width;
        this.samplerHeight = height;
    }

    private int sampleTexture(float u, float v) {
        int pu = Math.clamp(Math.round(u*this.samplerWidth-0.5f), 0, this.samplerWidth-1);
        int pv = Math.clamp(Math.round(v*this.samplerHeight-0.5f), 0, this.samplerHeight-1);
        return this.samplerTexture[this.samplerWidth*pv+pu];
    }

    public void clear() {
        Arrays.fill(this.framebuffer, CLEAR_VALUE);
    }

    public void raster(Matrix4f mvp, ReuseVertexConsumer vertices) {
        this.raster(mvp, vertices.getAddress(), vertices.quadCount());
    }
    public void raster(Matrix4f mvp, long verticesAddr, int quadCount) {
        if (quadCount == 0) return;
        for (int i = 0; i < quadCount; i++) {
            this.rasterQuad(mvp, verticesAddr+ReuseVertexConsumer.VERTEX_FORMAT_SIZE*4L*i);
        }
    }

    private void rasterQuad(Matrix4f transform, long addr) {
        loadTransformPos(transform, addr, 0, this.scratch1, this.qmuv1);
        loadTransformPos(transform, addr, 1, this.scratch2, this.qmuv2);
        loadTransformPos(transform, addr, 2, this.scratch3, this.qmuv3);
        loadTransformPos(transform, addr, 3, this.scratch4, this.qmuv4);
        this.quadColour = MemoryUtil.memGetInt(addr + 24);


        // Split the quad into triangles 0-1-2 and 2-3-0.
        toFixed(this.scratchR1, this.scratch1);
        toFixed(this.scratchR2, this.scratch2);
        toFixed(this.scratchR3, this.scratch3);
        this.a1.set(this.qmuv1);
        this.a2.set(this.qmuv2);
        this.a3.set(this.qmuv3);
        this.rasterTriangle(false);
        toFixed(this.scratchR1, this.scratch3);
        toFixed(this.scratchR2, this.scratch4);
        toFixed(this.scratchR3, this.scratch1);
        this.a1.set(this.qmuv3);
        this.a2.set(this.qmuv4);
        this.a3.set(this.qmuv1);
        this.rasterTriangle(true);
    }

    private void rasterTriangle(boolean orZero) {
        Vector3i v1 = this.scratchR1;
        Vector3i v2 = this.scratchR2;
        Vector3i v3 = this.scratchR3;

        long area = edge(v1, v2, v3);

        // Alpha-cutout cross quads are two-sided during offline model baking.
        int meta = Float.floatToRawIntBits(this.a1.x);
        if ((meta & 1) == 0 && (area < 0) == this.cullBackFace) {
            return;
        }

        if (Math.abs(fromFixed(area))<0.001) {
            return;//Degenerate triangle
        }

        int minX = fromFixed2Int(Math.max(Math.min(Math.min(v1.x, v2.x), v3.x), 0));
        int maxX = fromFixed2Int(Math.min(Math.max(Math.max(v1.x, v2.x), v3.x), toFixed(this.targetSize-1)));
        int minY = fromFixed2Int(Math.max(Math.min(Math.min(v1.y, v2.y), v3.y), 0));
        int maxY = fromFixed2Int(Math.min(Math.max(Math.max(v1.y, v2.y), v3.y), toFixed(this.targetSize-1)));

        for (int py = minY; py<=maxY; py++) {
            for (int px = minX; px<=maxX; px++) {
                int cx = toFixed(px)+toFixed(0.5f);
                int cy = toFixed(py)+toFixed(0.5f);
                int w1 = fixedDiv(edge(v2, v3, cx, cy), area);
                int w2 = fixedDiv(edge(v3, v1, cx, cy), area);
                int w3 = toFixed(1.0f)-w1-w2;
                //Strict >0 for the first triangle, >=0 for the second: pixels exactly on the shared
                //diagonal belong to one triangle only, which is what a float epsilon can never
                //guarantee
                if ((w1>0&&w2>0&&w3>0)||(orZero&&w1>=0&&w2>=0&&w3>=0)) {
                    float b1 = fromFixed(w1);
                    float b2 = fromFixed(w2);
                    float b3 = fromFixed(w3);
                    float z = Math.fma(b1, fromFixed(this.scratchR1.z), Math.fma(b2, fromFixed(this.scratchR2.z), b3 * fromFixed(this.scratchR3.z)));
                    this.rasterPixel(px+py*this.targetSize, b1, b2, b3, z);
                }
            }
        }
    }

    private void rasterPixel(int index, float b1, float b2, float b3, float z) {
        z = Math.fma(z,0.5f,0.5f);
        if (z < 0.0f && -0.000001f <= z) {
            z = 0;
        }
        if (z < 0.0f || z > 1.0f) {
            return;
        }


        int meta = Float.floatToRawIntBits(this.a1.x);
        float u = Math.fma(b1, this.a1.y, Math.fma(b2, this.a2.y, b3 * this.a3.y));
        float v = Math.fma(b1, this.a1.z, Math.fma(b2, this.a2.z, b3 * this.a3.z));

        int colour = this.sampleTexture(u, v);
        if (this.quadColour != 0xFFFFFFFF) {
            colour = multiplyAbgr(colour, this.quadColour);
        }


        final int ALPHA_CUTOFF_THRESHOLD = 0;
        if ((meta & 1) != 0 && (colour >>> 24) <= ALPHA_CUTOFF_THRESHOLD) {
            return;
        }

        this.framebuffer[index] += (1L<<32);

        long depthVal = ((long) (((double)z)*((1<<24)-1)))<<(64-24);
        if (depthVal == DEPTH_MASK) {
            depthVal--;
        }
        if (Long.compareUnsigned(this.framebuffer[index],depthVal)<=0) {
            return;
        }
        this.framebuffer[index] &= ~DEPTH_MASK;
        this.framebuffer[index] |= depthVal;

        this.framebuffer[index] &= ~(1L<<39);
        this.framebuffer[index] |= ((long)(meta&4))<<37;

        int srcColour = (int) this.framebuffer[index];
        this.framebuffer[index] &= ~Integer.toUnsignedLong(-1);

        if (this.doTheBlending && !this.replaceTranslucentColour) {
            colour = doBlending(srcColour, colour);
        }


        this.framebuffer[index] |= Integer.toUnsignedLong(colour);
    }


    private static int doBlending(int scr, int dst) {
        int srcAlpha = (scr>>>24)&0xFF;
        if (srcAlpha == 0) {
            return dst;
        }
        int dstAlpha = (dst>>>24)&0xFF;
        scr &= ~(0xFF<<24);
        dst &= ~(0xFF<<24);
        int blendAlpha = Math.min(0xFF,srcAlpha+((dstAlpha*(255-srcAlpha))>>8));
        int blend = ColorMixer.mix(dst, scr, dstAlpha);
        return blend|(blendAlpha<<24);
    }

    private static int multiplyAbgr(int base, int tint) {
        int a = (((base >>> 24) & 0xFF) * ((tint >>> 24) & 0xFF)) / 255;
        int b = (((base >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF)) / 255;
        int g = (((base >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF)) / 255;
        int r = ((base & 0xFF) * (tint & 0xFF)) / 255;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    //Edges are computed and kept in long. A model is not confined to its block: a quad spanning more
    //than ~23 target pixels makes the fixed-point product exceed int, and the wrap flips the area's
    //sign - both triangles then fail the winding gate and the whole quad bakes as nothing. Only the
    //COORDINATE range has to fit int (that is what INTEGER_BITS bounds); the products must not.
    private static long edge(Vector3i a, Vector3i b, Vector3i c) {
        return fixedMul(c.x-a.x,b.y-a.y) - fixedMul(c.y-a.y, b.x-a.x);
    }

    private static long edge(Vector3i a, Vector3i b, int cx, int cy) {
        return fixedMul(cx-a.x,b.y-a.y) - fixedMul(cy-a.y, b.x-a.x);
    }


    private static int toFixed(float a) {
        return (int) (((double)a)*(double) FIXED_POINT_BIT_SCALE);
    }

    private static int toFixed(int a) {
        return (int) (a*FIXED_POINT_BIT_SCALE);
    }

    private static void toFixed(Vector3i dst, Vector3f src) {
        dst.set(toFixed(src.x), toFixed(src.y), toFixed(src.z));
    }

    private static float fromFixed(long a) {
        return (float) (((double)a)/(double)FIXED_POINT_BIT_SCALE);
    }

    private static int fromFixed2Int(int a) {
        return (int) (a/FIXED_POINT_BIT_SCALE);
    }

    private static long fixedMul(int a, int b) {
        return (((long)a) * ((long)b))/FIXED_POINT_BIT_SCALE;
    }

    //Barycentric weight: an edge over the area, both long. Near-degenerate slivers can push the
    //ratio past int range, and a plain cast would wrap the sign - saturate instead. The bound is
    //loose enough that any genuinely-inside pixel (weights in [0, toFixed(1)]) is untouched, and
    //tight enough that toFixed(1)-w1-w2 cannot overflow int either.
    private static int fixedDiv(long a, long b) {
        long q = (a*FIXED_POINT_BIT_SCALE)/b;
        return (int) Math.clamp(q, -(1L << 28), 1L << 28);
    }


    private void loadTransformPos(Matrix4f transform, long addr, int vert, Vector3f out, Vector3f otherAttributesOut) {
        this.scratch.setFromAddress(addr+vert*ReuseVertexConsumer.VERTEX_FORMAT_SIZE);
        otherAttributesOut.setFromAddress(addr+vert*ReuseVertexConsumer.VERTEX_FORMAT_SIZE+3*4);
        this.scratch.w = 1.0f;
        var vec = transform.transformProject(this.scratch);
        if (Math.abs(this.scratch.w-1.0f)>0.000001f)
            throw new IllegalStateException();
        out.set(
                Math.fma(vec.x, 0.5f, 0.5f) * this.targetSize,
                Math.fma(vec.y, 0.5f, 0.5f) * this.targetSize,
                vec.z
        );
    }

    public long[] getRawFramebuffer() {
        return this.framebuffer;
    }
}
