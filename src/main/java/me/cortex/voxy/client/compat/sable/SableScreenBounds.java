package me.cortex.voxy.client.compat.sable;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import me.cortex.voxy.client.core.rendering.LodBoundaryFade;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

//Which pixels the depth shim actually has to bracket, for the sub-levels a pass is about to draw.
//
//Two reductions, and the first is the one that matters. The shim exists so LOD terrain can occlude
//sub-level geometry, and LOD only ever draws beyond the point sodium stops. A sub-level lying wholly
//inside the vanilla render distance therefore has no LOD anywhere in front of it - every LOD fragment
//is further from the camera than the whole plot is - so merging LOD depth cannot change a single pixel
//of it. Those pay nothing. What is left gets bounded to its screen extent rather than the full
//viewport, which is most of the remaining cost for a ship that is small on screen.
public final class SableScreenBounds {
    //Block models overhang their section (fences, banners, mounted blocks); grow the plot box before
    //using it so anything a section layer can rasterize stays inside the reported extent
    private static final double OVERHANG_BLOCKS = 2.0D;
    //A corner this close to the near plane projects to garbage - fall back rather than clip the pass
    private static final float NEAR_W_EPSILON = 1.0e-4f;
    //Pulled in from where LOD can first appear before a plot counts as LOD-free. Sodium has not
    //necessarily built every section it owns - an unbuilt one stays unmasked and voxy fills it, so
    //while chunks stream in LOD can sit closer than any boundary computation says. This margin covers
    //the ordinary case; right after a teleport or dimension change, where whole screens are unbuilt,
    //a near ship can briefly fail to be occluded by the LOD standing in for terrain that has not
    //meshed yet.
    private static final double LOD_FREE_MARGIN_BLOCKS = 64.0D;

    public static final float[] FULLSCREEN = {-1.0f, -1.0f, 1.0f, 1.0f};

    /** Why a pass needs no bracketing, kept apart so the counters say which reduction fired. */
    public enum Skip {
        /** Bracket it - {@link Result#ndc} says over which pixels */
        NONE,
        /** Every sub-level sits inside the radius LOD cannot reach into */
        ALL_NEAR,
        /** Nothing the pass draws lands on screen */
        OFFSCREEN
    }

    public record Result(float[] ndc, Skip skip) {
        static final Result ALL_NEAR = new Result(null, Skip.ALL_NEAR);
        static final Result OFFSCREEN = new Result(null, Skip.OFFSCREEN);

        public static Result allNear() {
            return ALL_NEAR;
        }
    }

    private SableScreenBounds() {
    }

    public static Result of(Iterable<ClientSubLevel> subLevels, double cameraX, double cameraY, double cameraZ,
                            Matrix4f modelView, Matrix4f projection) {
        return of(subLevels, cameraX, cameraY, cameraZ, modelView, projection, OVERHANG_BLOCKS);
    }

    /**
     * @param overhangBlocks how far past the plot box the bracketed pass can draw. Section layers stay
     *                       within a block or two of their own geometry; Flywheel visuals reach further
     *                       (piston poles, pulley ropes), so that path asks for more.
     */
    public static Result of(Iterable<ClientSubLevel> subLevels, double cameraX, double cameraY, double cameraZ,
                            Matrix4f modelView, Matrix4f projection, double overhangBlocks) {
        if (subLevels == null) {
            return Result.ALL_NEAR;
        }

        double lodFreeRadius = lodFreeRadiusBlocks();
        double lodFreeRadiusSq = lodFreeRadius * lodFreeRadius;
        Matrix4f mvp = new Matrix4f(projection).mul(modelView);
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        boolean any = false;

        Vector3d[] corners = new Vector3d[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = new Vector3d();
        }
        Vector4f clip = new Vector4f();

        for (ClientSubLevel subLevel : subLevels) {
            BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
            if (bounds == null) {
                //Plot not synced yet - no box to measure or project, so assume it needs everything
                return new Result(FULLSCREEN, Skip.NONE);
            }

            double x0 = bounds.minX() - overhangBlocks;
            double y0 = bounds.minY() - overhangBlocks;
            double z0 = bounds.minZ() - overhangBlocks;
            double x1 = bounds.maxX() + 1 + overhangBlocks;
            double y1 = bounds.maxY() + 1 + overhangBlocks;
            double z1 = bounds.maxZ() + 1 + overhangBlocks;

            double farthestSq = 0.0D;
            for (int i = 0; i < 8; i++) {
                Vector3d corner = corners[i];
                corner.set((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1);
                //renderPose maps plot space to world space, matching what the camera position is in
                subLevel.renderPose().transformPosition(corner);
                double dx = corner.x - cameraX;
                double dz = corner.z - cameraZ;
                farthestSq = Math.max(farthestSq, dx * dx + dz * dz);
            }

            //Every corner nearer than where LOD can start means the merge cannot change this plot - but
            //it still has to be inside the rect. The rect clips everything the bracketed pass draws,
            //and the pass draws the whole list: a near ship left out of it is a near ship scissored
            //away, vanishing whenever a farther ship's projection happens not to cover it.
            if (farthestSq >= lodFreeRadiusSq) {
                any = true;
            }

            for (int i = 0; i < 8; i++) {
                Vector3d corner = corners[i];
                clip.set((float) (corner.x - cameraX), (float) (corner.y - cameraY), (float) (corner.z - cameraZ), 1.0f);
                mvp.transform(clip);
                if (clip.w <= NEAR_W_EPSILON) {
                    //Camera is inside or right against this sub-level, where it covers most of the view anyway
                    return new Result(FULLSCREEN, Skip.NONE);
                }
                float ndcX = clip.x / clip.w;
                float ndcY = clip.y / clip.w;
                minX = Math.min(minX, ndcX);
                minY = Math.min(minY, ndcY);
                maxX = Math.max(maxX, ndcX);
                maxY = Math.max(maxY, ndcY);
            }
        }

        if (!any) {
            return Result.ALL_NEAR;
        }
        if (minX > 1.0f || maxX < -1.0f || minY > 1.0f || maxY < -1.0f) {
            //Entirely off screen - the pass draws nothing, so the shim has nothing to bracket
            return Result.OFFSCREEN;
        }
        return new Result(new float[]{Math.max(minX, -1.0f), Math.max(minY, -1.0f),
                Math.min(maxX, 1.0f), Math.min(maxY, 1.0f)}, Skip.NONE);
    }

    /** Radius within which no LOD geometry can appear, so nothing inside it needs depth merging. */
    public static double lodFreeRadiusBlocks() {
        //Where LOD can first show up, not where the render distance nominally ends - with the boundary
        //fade on, opaque LOD stops being masked at fadeStart, which is inset+buffer+fadeLength short of
        //it. Reading the fade's own answer keeps this correct when that config changes; with the fade
        //off it reports the render distance and this reduces to the plain radius.
        return Math.max(0.0D, LodBoundaryFade.getDistances().fadeStart() - LOD_FREE_MARGIN_BLOCKS);
    }
}
