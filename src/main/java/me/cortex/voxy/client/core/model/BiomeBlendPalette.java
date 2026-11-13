package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

//Palette of box-blended biome colours for LOD quads. Vanilla's smooth biome transition is not a
//gradient: every block's tint is the plain average of the biome colours over a (2r+1)^2 box around
//it, so adjacent blocks step by a fraction of the difference. The mesh build reproduces exactly
//that per voxel, and the blended result needs an address a quad can carry - a synthetic biome id
//will not fit (Terralith already fills most of the 9-bit space), so blends live in a palette
//addressed through the quad's five never-used bits (13-bit index + flag bit).
//
//The palette keeps its original indices, with ordinary biome rows in a separate bank above it.
//Dedup keys are quantised to 5 bits per channel - below what a flat-shaded LOD quad can show, and
//it keeps typical biome-pair usage to a few hundred entries. The entry itself keeps the first
//arrival's exact colour, so a slot is at most a quantisation step off any voxel sharing it.
//
//The CPU mirror uses the same row bases and colours as the GPU uploads. The snapshot is swapped
//whole by the bakery thread and read lock-free by mesh workers.
public final class BiomeBlendPalette {
    //Must match BLEND_PALETTE_BASE in quad_util.glsl
    public static final int PALETTE_BASE = 57344;
    public static final int PALETTE_CAPACITY = 65536 - PALETTE_BASE;

    //stride = biome count each row was built with. A voxel can carry a biome the bakery has not
    //registered yet (ids are minted by the mapper, rows are rebuilt asynchronously), and without
    //the stride bound that id would read into the NEXT model's row - a plain quad recovers when the
    //next biome upload restrides the buffer, but a blend index is baked into geometry that nothing
    //remeshes afterwards, so the wrong colour would stay.
    public record Snapshot(int[] colours, int[] rowBaseByModelId, int stride) {
        static final Snapshot EMPTY = new Snapshot(new int[0], new int[0], 0);

        //-1 = unknown (model has no mirrored row yet, or the biome predates the row); the caller
        //keeps the unblended colour
        public int colourOf(int modelId, int biomeId) {
            if (modelId < 0 || modelId >= this.rowBaseByModelId.length || biomeId < 0 || biomeId >= this.stride) {
                return -1;
            }
            int base = this.rowBaseByModelId[modelId];
            if (base == BiomeColourRows.FALLBACK_BASE) {
                return BiomeColourRows.FALLBACK_COLOUR;
            }
            int idx = base - BiomeColourRows.ROW_BASE + biomeId;
            return idx < 0 || idx >= this.colours.length ? -1 : this.colours[idx];
        }
    }

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private final ConcurrentHashMap<Integer, Integer> quantisedToIndex = new ConcurrentHashMap<>();
    private final AtomicInteger nextIndex = new AtomicInteger();
    //{palette index, ABGR colour}. Drained on the render thread; geometry referencing a new index
    //reaches the GPU no earlier than the next node-manager tick, so drain-before-geometry keeps
    //every index written before its first read.
    private final ConcurrentLinkedQueue<int[]> pendingUploads = new ConcurrentLinkedQueue<>();
    private volatile boolean fullWarned;
    //New blended indices are not useful once any model has fallen back to a neutral row.
    private volatile boolean disabled;

    public Snapshot snapshot() {
        return this.snapshot;
    }

    public void disable() {
        this.disabled = true;
    }

    //Mesh worker threads. -1 = palette full or disabled: the caller keeps the hard edge for that
    //voxel, which beats writing an index the shader would read out of the reserved range.
    public int indexFor(int abgr) {
        if (this.disabled) {
            return -1;
        }
        int key = abgr & 0xF8F8F8;
        return this.quantisedToIndex.computeIfAbsent(key, k -> {
            int idx = this.nextIndex.getAndIncrement();
            if (idx >= PALETTE_CAPACITY) {
                if (!this.fullWarned) {
                    this.fullWarned = true;
                    me.cortex.voxy.common.Logger.warn("Biome blend palette full ("
                            + PALETTE_CAPACITY + " entries) - further transitions keep hard edges");
                }
                return -1;
            }
            this.pendingUploads.add(new int[]{idx, abgr | 0xFF000000});
            return idx;
        });
    }

    //Render thread, before the model factory's budgeted upload drain. Returns whether anything was
    //written so the caller knows a commit is owed even when no model uploads ran.
    public boolean drainUploads(GlBuffer modelColourBuffer) {
        boolean wrote = false;
        int[] entry;
        while ((entry = this.pendingUploads.poll()) != null) {
            long ptr = UploadStream.INSTANCE.upload(modelColourBuffer, (PALETTE_BASE + entry[0]) * 4L, 4);
            MemoryUtil.memPutInt(ptr, entry[1]);
            wrote = true;
        }
        return wrote;
    }

    Snapshot prepareRow(int modelId, BiomeColourRows.Admission row, int stride) {
        var old = this.snapshot;
        if (stride == 0) return old;
        int[] rowBases = growRowBases(old.rowBaseByModelId(), modelId + 1);
        int[] colours = old.colours();
        int[] fresh = row.newColours();
        if (fresh != null) {
            int offset = row.base() - BiomeColourRows.ROW_BASE;
            colours = Arrays.copyOf(colours, offset + fresh.length);
            System.arraycopy(fresh, 0, colours, offset, fresh.length);
        }
        rowBases[modelId] = row.base();
        return new Snapshot(colours, rowBases, stride);
    }

    static Snapshot prepareRebuild(long[] pairs, int[] colours, int stride) {
        int maxModelId = 0;
        for (long pair : pairs) {
            maxModelId = Math.max(maxModelId, (int) pair);
        }
        int[] rowBases = growRowBases(new int[0], maxModelId + 1);
        for (long pair : pairs) {
            rowBases[(int) pair] = (int) (pair >>> 32);
        }
        return new Snapshot(colours, rowBases, stride);
    }

    void publish(Snapshot next) {
        this.snapshot = next;
    }

    private static int[] growRowBases(int[] old, int minLen) {
        int len = Math.max(old.length, minLen);
        int[] fresh = Arrays.copyOf(old, len);
        Arrays.fill(fresh, old.length, len, -1);
        return fresh;
    }
}
