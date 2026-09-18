#version 460

#import <voxy:util/depthutils.glsl>

layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec4 cameraBlockPos;
    vec4 negInnerBlock;
    //xy: NDC size of one mask pixel (2/maskWidth, 2/maskHeight); z: how many mask pixels each box
    //is dilated by, 0 = rasterise the boxes exactly as they are
    vec4 maskPixel;
    //xy: viewport/(2*mask) size ratio for the half-res remap below; z: nonzero on the half-res path
    vec4 maskScale;
};

layout(binding = 1, std430) restrict readonly buffer ChunkPosBuffer {
    ivec2[] chunkPos;
};

ivec3 unpackPos(ivec2 pos) {
    return ivec3(pos.y>>10, (pos.x<<12)>>12, ((pos.y<<22)|int(uint(pos.x)>>10))>>10);
}

bool shouldRender(ivec3 icorner) {
    vec3 corner = vec3(mix(mix(ivec3(0), icorner-1, greaterThan(icorner-1, ivec3(0))), icorner+17, lessThan(icorner+17, ivec3(0))))-negInnerBlock.xyz;
    bool visible = (corner.x*corner.x + corner.z*corner.z) < (negInnerBlock.w*negInnerBlock.w);
    visible = visible && abs(corner.y) < negInnerBlock.w;
    return visible;
}

#ifdef TAA
vec2 getTAA();
#endif

void main() {
    uint id = (gl_InstanceID<<5)+gl_BaseInstance+(gl_VertexID>>3);

    ivec3 origin = unpackPos(chunkPos[id])*16;
    origin -= cameraBlockPos.xyz;

    if (!shouldRender(origin)) {
        gl_Position = vec4(-100.0f, -100.0f, -100.0f, 0.0f);
        return;
    }

    ivec3 cubeCornerI = ivec3(gl_VertexID&1, (gl_VertexID>>2)&1, (gl_VertexID>>1)&1)*16;
    //Expand the y height to be big (will be +- 8192)
    //TODO: make it W.R.T world height and offsets
    //cubeCornerI.y = cubeCornerI.y*1024-512;
    gl_Position = MVP * vec4(vec3(cubeCornerI+origin), 1);

    //Screen-space dilation so the rasterised silhouette stays a superset of the box when the mask
    //is coarser than the viewport. Each vertex moves away from the projected box centre by
    //maskPixel.z mask pixels along its dominant axis and proportionally less along the other -
    //never inward for a convex silhouette, unlike a per-axis sign push. Applied in clip space
    //(scaled by w) so the offset is exact after the perspective divide. The direction is the
    //cross-term p.xy*c.w - c.xy*p.w: for a centre in front of the camera that is (pNdc-cNdc) up
    //to a positive scale, and for a centre behind the camera plane it still points away from the
    //visible interior (which then lies toward the flipped centre projection). Vertices with w<=0
    //are left alone - their projection has no usable direction, and a box straddling the camera
    //already covers the whole screen.
    if (maskPixel.z > 0.0f && gl_Position.w > 0.0f) {
        vec4 centre = MVP * vec4(vec3(origin) + 8.0f, 1.0f);
        vec2 dir = gl_Position.xy * centre.w - centre.xy * gl_Position.w;
        float m = max(abs(dir.x) / maskPixel.x, abs(dir.y) / maskPixel.y);
        if (m > 0.0f) {
            gl_Position.xy += dir * (maskPixel.z / m) * gl_Position.w;
        }
    }

    //TODO: FIXME with reverse z need tobe + not -
    gl_Position.z += CLOSER_SIGN*0.0005f;//Bring closer to camera

    #ifdef TAA
    gl_Position.xy += getTAA()*gl_Position.w;//Apply TAA if we have it
    #endif

    //A half-res mask is rounded up, so for an odd viewport its raster spans one viewport pixel
    //more than the LOD pass does; this affine remap keeps mask texel i over viewport pixels
    //2i,2i+1. Last, after the jitter, and applied whatever the sign of w so triangles straddling
    //the camera plane get a single transform.
    if (maskScale.z > 0.0f) {
        gl_Position.xy = (gl_Position.xy + gl_Position.w) * maskScale.xy - gl_Position.w;
    }
}



//Undefine depth stuff
#import <voxy:util/depthutils.glsl>
