package me.cortex.voxy.client.core.model;

import java.util.Arrays;

/** CPU-only regression; run its main without Minecraft or a GL context. */
public final class BiomeColourRowsTest {
    private static final int WATER = 0xFF734D33;
    private static final int GRASS = 0xFF245E3B;
    private static final int FERN = 0xFF225A34;

    public static void main(String[] args) {
        int base = BiomeBlendPalette.PALETTE_BASE + BiomeBlendPalette.PALETTE_CAPACITY;
        var compactMirror = new BiomeBlendPalette.Snapshot(new int[]{WATER}, new int[]{base}, 1);
        check(compactMirror.colourOf(0, 0) == WATER, "CPU mirror cannot resolve the compact row at the GPU base");
        testBakeAndRebuild();
        testCapacity();
        System.out.println("Biome colour rows regression passed");
    }

    private static void testBakeAndRebuild() {
        var rows = new BiomeColourRows(2);
        var palette = new BiomeBlendPalette();
        var abandoned = rows.prepare(new int[]{GRASS, GRASS});
        var unpublished = palette.prepareRow(2, abandoned, rows.stride());
        check(unpublished.colourOf(2, 1) == GRASS && palette.snapshot().colourOf(2, 1) == -1,
                "Failed bake must not publish its prepared mirror");

        int[] waterColours = {WATER, WATER};
        var water = rows.prepare(waterColours);
        waterColours[1] = GRASS;
        check(water.base() == abandoned.base() && water.newColours()[1] == WATER,
                "Abandoned admission must leave the first base free and capture immutable");
        palette.publish(palette.prepareRow(0, water, rows.stride()));
        rows.commit(water);

        var shared = rows.prepare(new int[]{WATER, WATER});
        check(shared.base() == water.base() && shared.newColours() == null,
                "Identical complete rows must not queue a second colour upload");
        palette.publish(palette.prepareRow(1, shared, rows.stride()));
        rows.commit(shared);
        var old = palette.snapshot();
        check(old.colours().length == 2 && old.colourOf(0, 1) == WATER && old.colourOf(1, 1) == WATER,
                "Shared baked row and mirror diverged");
        check(old.colourOf(2, 0) == -1 && old.colourOf(0, -1) == -1 && old.colourOf(0, 2) == -1,
                "An unrowed model or out-of-stride biome must not read another row");

        var failure = new IllegalStateException("tint capture failed");
        try {
            new BiomeColourRows(3).rebuild(2, i -> {
                if (i == 1) throw failure;
                return new BiomeColourRows.ModelColours(i, new int[]{WATER, WATER, WATER});
            });
            throw new AssertionError("Rebuild swallowed tint capture failure");
        } catch (IllegalStateException actual) {
            check(actual == failure && palette.snapshot() == old, "Failed rebuild must leave the current mirror intact");
        }

        // Models 0 and 1 previously shared their row; the newly registered biome splits them.
        // Column 1 is still a hole with a captured default colour. Model 2 never had a provider.
        var rebuiltRows = new BiomeColourRows(3);
        int[][] captured = {{WATER, WATER, WATER}, {WATER, WATER, GRASS}, {FERN, FERN, FERN}};
        int[] modelIds = {0, 1, 3};
        var rebuilt = rebuiltRows.rebuild(captured.length, i -> new BiomeColourRows.ModelColours(modelIds[i], captured[i]));
        var rebuiltMirror = BiomeBlendPalette.prepareRebuild(rebuilt.pairs(), rebuilt.colours(), rebuiltRows.stride());
        for (int i = 0; i < captured.length; i++) {
            int modelId = modelIds[i];
            int gpuBase = (int) (rebuilt.pairs()[i] >>> 32);
            check(gpuBase == rebuiltMirror.rowBaseByModelId()[modelId], "Rebuild changed the GPU row identity");
            for (int biome = 0; biome < captured[i].length; biome++) {
                int gpuColour = rebuilt.colours()[gpuBase - BiomeColourRows.ROW_BASE + biome];
                check(gpuColour == rebuiltMirror.colourOf(modelId, biome) && gpuColour == captured[i][biome],
                        "Rebuild shifted a biome column or changed a tint");
            }
        }
        check(rebuiltMirror.colourOf(2, 0) == -1 && old.colourOf(1, 2) == -1,
                "Rebuild admitted an unrowed model or changed an older snapshot");
        var repeated = rebuiltRows.prepare(captured[2]);
        check(repeated.base() == rebuiltMirror.rowBaseByModelId()[3] && repeated.newColours() == null,
                "A bake after rebuild did not share the rebuilt row");
    }

    private static void testCapacity() {
        var empty = new BiomeColourRows(0);
        check(empty.prepare(null).base() == BiomeColourRows.FALLBACK_BASE && empty.stride() == 0,
                "A model baked before biome registration needs a valid neutral GPU row");
        var rows = new BiomeColourRows(BiomeColourRows.MAX_BIOMES);
        int rowCount = BiomeColourRows.ROW_CAPACITY / rows.stride();
        var rebuilt = rows.rebuild(rowCount + 1, i -> {
            int[] colours = new int[rows.stride()];
            Arrays.fill(colours, 0xFF123400 + i);
            return new BiomeColourRows.ModelColours(i, colours);
        });
        var mirror = BiomeBlendPalette.prepareRebuild(rebuilt.pairs(), rebuilt.colours(), rows.stride());
        int lastBiome = rows.stride() - 1;
        check(BiomeBlendPalette.PALETTE_BASE == 57_344 && BiomeColourRows.ROW_BASE == 65_536
                        && BiomeColourRows.FALLBACK_BASE == 131_072 && BiomeColourRows.BUFFER_ENTRIES == 131_584,
                "Palette, regular rows and neutral row must be disjoint");
        check(rebuilt.colours().length == BiomeColourRows.ROW_CAPACITY && rebuilt.fallback()
                        && mirror.rowBaseByModelId()[rowCount - 1] + lastBiome == BiomeColourRows.FALLBACK_BASE - 1
                        && mirror.rowBaseByModelId()[rowCount] == BiomeColourRows.FALLBACK_BASE
                        && mirror.colourOf(rowCount, lastBiome) == BiomeColourRows.FALLBACK_COLOUR,
                "Saturation exceeded the upload budget or lost the neutral row");
        int[] first = new int[rows.stride()];
        Arrays.fill(first, 0xFF123400);
        check(rows.prepare(first).base() == BiomeColourRows.ROW_BASE, "A full bank must still share existing rows");

        var unsupported = new BiomeColourRows(BiomeColourRows.MAX_BIOMES + 1);
        var unsupportedRebuild = unsupported.rebuild(1, i -> new BiomeColourRows.ModelColours(0, null));
        check(!unsupported.capturesColours() && unsupported.stride() == BiomeColourRows.MAX_BIOMES
                        && unsupportedRebuild.colours().length == 0 && unsupportedRebuild.fallback(),
                "A stride wider than the biome field must not allocate a full row");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
