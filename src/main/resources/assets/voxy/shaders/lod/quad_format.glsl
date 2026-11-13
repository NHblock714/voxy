#ifdef QUAD_DATA_USE_64_BIT
#define Quad uint64_t

#define Eu32(data, amountBits, shift) (uint((data)>>(shift))&((1u<<(amountBits))-1))
#define QUAD_BITS Eu32

vec3 extractPos(uint64_t quad) {
    //TODO: pull out the majic constants into #defines (specifically the shift amount)
    return vec3(Eu32(quad, 5, 21), Eu32(quad, 5, 16), Eu32(quad, 5, 11));
}

ivec2 extractSize(uint64_t quad) {
    return ivec2(Eu32(quad, 4, 3), Eu32(quad, 4, 7)) + ivec2(1);//the + 1 is cause you cant actually have a 0 size quad
}

uint extractStateId(uint64_t quad) {
    return Eu32(quad, 16, 26);
}

uint extractBiomeId(uint64_t quad) {
    return Eu32(quad, 9, 46);
}

uint extractLightId(uint64_t quad) {
    return Eu32(quad, 8, 55);
}

uint extractBlendIdx(uint64_t quad) {
    return Eu32(quad, 9, 46) | (Eu32(quad, 4, 42) << 9);
}

bool isQuadEmpty(uint64_t quad) {
    return quad == uint64_t(0);
}

#else
//TODO: FIXME, ivec2 swaps around the data of the x and y cause its written in little endian

#define Quad ivec2
#define QUAD_BITS Eu32v

uint Eu32v(ivec2 data, int amount, int shift) {
    if (shift > 31) {
        shift -= 32;
        return (uint(data.y)>>uint(shift))&((1u<<uint(amount))-1);
    } else {
        return (uint(data.x)>>uint(shift))&((1u<<uint(amount))-1);
    }
}

vec3 extractPos(ivec2 quad) {
    return vec3(Eu32v(quad, 5, 21), Eu32v(quad, 5, 16), Eu32v(quad, 5, 11));
}

ivec2 extractSize(ivec2 quad) {
    return ivec2(Eu32v(quad, 4, 3), Eu32v(quad, 4, 7)) + ivec2(1);//the + 1 is cause you cant actually have a 0 size quad
}

uint extractStateId(ivec2 quad) {
    // The 16-bit model ID ends before the blend/surface bits at 42..45.
    return Eu32v(quad, 6, 26)|(Eu32v(quad, 10, 32)<<6);
}

uint extractBiomeId(ivec2 quad) {
    return Eu32v(quad, 9, 46);
}

uint extractLightId(ivec2 quad) {
    return Eu32v(quad, 8, 55);
}

uint extractBlendIdx(ivec2 quad) {
    return Eu32v(quad, 9, 46) | (Eu32v(quad, 4, 42) << 9);
}

bool isQuadEmpty(ivec2 quad) {
    return all(equal(quad, ivec2(0)));
}
#endif

// Escape faces 6/7 are blended, surface-terminating Z/X sides. Bit 63 is their
// polarity rather than a blend flag, leaving all 13 palette index bits intact.
uint extractFace(Quad quad) {
    uint face = QUAD_BITS(quad, 3, 0);
    return face < 6u ? face : ((face - 5u) << 1u) | QUAD_BITS(quad, 1, 63);
}

uint quadUsesBlendPalette(Quad quad) {
    return QUAD_BITS(quad, 3, 0) >= 6u ? 1u : QUAD_BITS(quad, 1, 63);
}

bool quadEndsAtFluidSurface(Quad quad) {
    return QUAD_BITS(quad, 3, 0) >= 6u
            || (QUAD_BITS(quad, 1, 63) == 0u && QUAD_BITS(quad, 1, 45) != 0u);
}

#undef QUAD_BITS
