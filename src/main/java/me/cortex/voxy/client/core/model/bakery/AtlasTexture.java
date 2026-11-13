package me.cortex.voxy.client.core.model.bakery;

import java.nio.IntBuffer;

final class AtlasTexture {
    private static final int TILE_SHIFT = 8;
    private static final int TILE_SIZE = 1 << TILE_SHIFT;
    private static final int TILE_MASK = TILE_SIZE - 1;

    final int width;
    final int height;
    private final int tilesWide;
    private final int[][] tiles;

    AtlasTexture(int width, int height) {
        this.width = width;
        this.height = height;
        this.tilesWide = ((width - 1) >>> TILE_SHIFT) + 1;
        int tilesHigh = ((height - 1) >>> TILE_SHIFT) + 1;
        this.tiles = new int[Math.multiplyExact(this.tilesWide, tilesHigh)][];
    }

    int get(int x, int y) {
        int[] tile = this.tiles[(y >>> TILE_SHIFT) * this.tilesWide + (x >>> TILE_SHIFT)];
        return tile == null ? 0 : tile[(y & TILE_MASK) * TILE_SIZE + (x & TILE_MASK)];
    }

    void copyRows(int firstRow, int rows, IntBuffer strip) {
        for (int row = 0; row < rows; row++) {
            int y = firstRow + row;
            int tileRow = (y >>> TILE_SHIFT) * this.tilesWide;
            int inTile = (y & TILE_MASK) * TILE_SIZE;
            int inStrip = row * this.width;
            for (int tileX = 0, x = 0; x < this.width; tileX++, x += TILE_SIZE) {
                int length = Math.min(TILE_SIZE, this.width - x);
                int[] tile = this.tiles[tileRow + tileX];
                if (tile == null) {
                    // A missing tile represents all-zero RGBA, not just zero alpha.
                    if (!hasNonZero(strip, inStrip + x, length)) continue;
                    tile = this.tiles[tileRow + tileX] = new int[TILE_SIZE * TILE_SIZE];
                }
                strip.get(inStrip + x, tile, inTile, length);
            }
        }
    }

    private static boolean hasNonZero(IntBuffer strip, int start, int length) {
        for (int i = 0; i < length; i++) {
            if (strip.get(start + i) != 0) return true;
        }
        return false;
    }
}
