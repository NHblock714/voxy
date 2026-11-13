package me.cortex.voxy.client.core.model.bakery;

import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12C.GL_PACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL45C.nglGetTextureSubImage;

final class AtlasReadback {
    private static final int MAX_STRIP_BYTES = 8 * 1024 * 1024;
    private static final int[] PACK_PARAMETERS = {
            GL_PACK_ROW_LENGTH, GL_PACK_IMAGE_HEIGHT, GL_PACK_SKIP_ROWS, GL_PACK_SKIP_PIXELS,
            GL_PACK_SKIP_IMAGES, GL_PACK_ALIGNMENT, GL_PACK_SWAP_BYTES, GL_PACK_LSB_FIRST
    };

    private AtlasReadback() {}

    static void setup(int texture, SoftwareRasterizer rasterizer) {
        int width;
        int height;
        AtlasTexture tiles = null;
        int[] pixels = null;
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, texture);
            width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Invalid block atlas dimensions: " + width + "x" + height);
            }

            var caps = GL.getCapabilities();
            boolean subImage = (caps.OpenGL45 || caps.GL_ARB_get_texture_sub_image) && caps.glGetTextureSubImage != 0;
            int previousPackBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
            int[] previousPack = new int[PACK_PARAMETERS.length];
            for (int i = 0; i < PACK_PARAMETERS.length; i++) {
                previousPack[i] = glGetInteger(PACK_PARAMETERS[i]);
            }
            try {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
                for (int parameter : PACK_PARAMETERS) {
                    glPixelStorei(parameter, parameter == GL_PACK_ALIGNMENT ? Integer.BYTES : 0);
                }

                if (subImage) {
                    tiles = readStrips(texture, width, height);
                } else {
                    pixels = readLegacy(width, height);
                }
            } finally {
                for (int i = 0; i < PACK_PARAMETERS.length; i++) {
                    glPixelStorei(PACK_PARAMETERS[i], previousPack[i]);
                }
                glBindBuffer(GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            }
        } finally {
            glBindTexture(GL_TEXTURE_2D, previousTexture);
        }
        if (tiles != null) {
            rasterizer.setSamplerTexture(tiles);
        } else {
            rasterizer.setSamplerTexture(pixels, width, height);
        }
    }

    private static AtlasTexture readStrips(int texture, int width, int height) {
        long rowBytes = (long) width * Integer.BYTES;
        if (rowBytes > MAX_STRIP_BYTES) {
            throw new IllegalArgumentException("Block atlas row exceeds the readback bound: " + rowBytes);
        }
        int rowsPerStrip = Math.min(height, (int) (MAX_STRIP_BYTES / rowBytes));
        var pixels = new AtlasTexture(width, height);
        IntBuffer strip = MemoryUtil.memAllocInt(width * rowsPerStrip);
        try {
            long address = MemoryUtil.memAddress(strip);
            for (int y = 0; y < height;) {
                int rows = Math.min(rowsPerStrip, height - y);
                nglGetTextureSubImage(texture, 0, 0, y, 0, width, rows, 1, GL_RGBA, GL_UNSIGNED_BYTE,
                        width * rows * Integer.BYTES, address);
                pixels.copyRows(y, rows, strip);
                y += rows;
            }
        } finally {
            MemoryUtil.memFree(strip);
        }
        return pixels;
    }

    private static int[] readLegacy(int width, int height) {
        int pixelCount = Math.multiplyExact(width, height);
        int[] pixels = new int[pixelCount];
        // Keep the old path native as well: a GL call must not pin the Java atlas array.
        long size = (long) pixelCount * Integer.BYTES;
        long address = MemoryUtil.nmemAlloc(size);
        if (address == 0) throw new OutOfMemoryError("atlas readback buffer: " + size + " bytes");
        try {
            nglGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, address);
            MemoryUtil.memIntBuffer(address, pixelCount).get(pixels);
        } finally {
            MemoryUtil.nmemFree(address);
        }
        return pixels;
    }
}
