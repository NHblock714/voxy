package me.cortex.voxy.client.core.model;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.function.IntFunction;

/** Bakery-thread table of complete biome colour rows. Bases are GPU colour-buffer indices. */
final class BiomeColourRows {
    static final int MAX_BIOMES = 1 << 9;
    static final int ROW_BASE = BiomeBlendPalette.PALETTE_BASE + BiomeBlendPalette.PALETTE_CAPACITY;
    static final int ROW_CAPACITY = 1 << 16;
    static final int FALLBACK_BASE = ROW_BASE + ROW_CAPACITY;
    static final int BUFFER_ENTRIES = FALLBACK_BASE + MAX_BIOMES;
    static final int FALLBACK_COLOUR = 0xFFFFFFFF;

    private final boolean unsupportedBiomeCount;
    private final int stride;
    private final LinkedHashMap<Key, Integer> bases = new LinkedHashMap<>();
    private int entries;

    BiomeColourRows(int biomeCount) {
        this.unsupportedBiomeCount = biomeCount > MAX_BIOMES;
        this.stride = Math.min(biomeCount, MAX_BIOMES);
    }

    int stride() {
        return this.stride;
    }

    boolean capturesColours() {
        return this.stride > 0 && !this.unsupportedBiomeCount;
    }

    // Admission itself is side-effect free: an abandoned bake cannot leave a row that a later
    // model would reference without its corresponding GPU upload.
    Admission prepare(int[] colours) {
        if (!this.capturesColours()) {
            return new Admission(FALLBACK_BASE, null);
        }
        if (colours.length != this.stride) {
            throw new IllegalArgumentException("Incomplete biome colour row");
        }
        var key = new Key(colours, Arrays.hashCode(colours));
        Integer base = this.bases.get(key);
        if (base != null) {
            return new Admission(base, null);
        }
        if (this.entries + colours.length > ROW_CAPACITY) {
            return new Admission(FALLBACK_BASE, null);
        }
        return new Admission(ROW_BASE + this.entries, new Key(colours.clone(), key.hash()));
    }

    void commit(Admission admission) {
        if (admission.key() != null) {
            if (admission.base() != ROW_BASE + this.entries) {
                throw new IllegalStateException("Stale biome colour row admission");
            }
            this.bases.put(admission.key(), admission.base());
            this.entries += this.stride;
        }
    }

    int[] compactColours() {
        int[] colours = new int[this.entries];
        this.bases.forEach((key, base) -> System.arraycopy(key.colours(), 0, colours, base - ROW_BASE, this.stride));
        return colours;
    }

    Rebuild rebuild(int modelCount, IntFunction<ModelColours> capture) {
        long[] pairs = new long[modelCount];
        boolean fallback = false;
        for (int i = 0; i < modelCount; i++) {
            var model = capture.apply(i);
            var row = this.prepare(model.colours());
            this.commit(row);
            fallback |= row.base() == FALLBACK_BASE;
            pairs[i] = Integer.toUnsignedLong(model.modelId()) | ((long) row.base() << 32);
        }
        return new Rebuild(this.compactColours(), pairs, fallback);
    }

    record ModelColours(int modelId, int[] colours) {}

    record Rebuild(int[] colours, long[] pairs, boolean fallback) {}

    record Admission(int base, Key key) {
        int[] newColours() {
            return this.key == null ? null : this.key.colours();
        }
    }

    private record Key(int[] colours, int hash) {
        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && this.hash == key.hash && Arrays.equals(this.colours, key.colours);
        }
    }
}
