package me.cortex.voxy.client.core.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import me.cortex.voxy.common.util.TrackedObject;

import java.lang.reflect.Array;
import java.util.Arrays;

import static org.lwjgl.opengl.ARBTimerQuery.GL_TIMESTAMP;
import static org.lwjgl.opengl.ARBTimerQuery.glQueryCounter;
import static org.lwjgl.opengl.GL15.glDeleteQueries;
import static org.lwjgl.opengl.GL15.glGenQueries;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL33.glGetQueryObjecti64;

public class GPUTiming {
    public static GPUTiming INSTANCE = new GPUTiming();

    private final GlTimestampQuerySet<String> timingSet = new GlTimestampQuerySet(String.class);

    //One rolling set per marker count. Frames do not all emit the same markers (a held command-list
    //frame skips the rebuild passes), and a single positional array would be thrown away and
    //restarted from zero at every switch between the two shapes.
    private static final class Layout {
        final float[] timings;
        final String[] lables;
        long lastTick;
        Layout(int length) {
            this.timings = new float[length];
            this.lables = new String[length];
        }
    }
    private static final int MAX_LAYOUTS = 8;
    private static final long LAYOUT_STALE_TICKS = 240;
    private final Int2ObjectOpenHashMap<Layout> layouts = new Int2ObjectOpenHashMap<>();
    private long tickCounter;

    private boolean enabled = false;

    //Timing has several independent consumers (the F3 line, a frame capture, a profile run) that
    //start and stop at different times; a single boolean let whichever stopped last switch the
    //others off, or left it running after all of them were gone
    public static final int OWNER_F3 = 1, OWNER_CAPTURE = 2, OWNER_PROFILE = 4;
    private int owners;

    public void marker() {
        this.marker(null);
    }

    public void marker(String lable) {
        if (this.enabled) {
            this.timingSet.capture(lable);
        }
    }

    public void enableFor(int owner) {
        this.owners |= owner;
        this.enabled = true;
    }

    public void disableFor(int owner) {
        this.owners &= ~owner;
        this.enabled = this.owners != 0;
    }

    public String getDebug() {
        if (!this.enabled) {
            return "";
        }
        //The fullest recently-fed layout: it carries every pass the shorter ones do
        Layout shown = null;
        for (Layout layout : this.layouts.values()) {
            if (this.tickCounter - layout.lastTick > LAYOUT_STALE_TICKS) {
                continue;
            }
            if (shown == null || layout.timings.length > shown.timings.length) {
                shown = layout;
            }
        }
        StringBuilder str = new StringBuilder("GpuTime: [");
        if (shown != null) {
            for (int i = 0; i < shown.timings.length; i++) {
                if (shown.lables[i] != null) {
                    str.append(shown.lables[i]+":"+String.format("%.2f", shown.timings[i]));
                } else {
                    str.append(String.format("%.2f", shown.timings[i]));
                }
                if (i!=shown.timings.length-1) {
                    str.append(", ");
                }
            }
        }
        str.append(']');
        return str.toString();
    }

    public void tick() {
        this.tickCounter++;
        this.timingSet.download((meta,data)->{
            long current = data[0];

            Layout layout = this.layouts.get(data.length-1);
            if (layout == null) {
                if (this.layouts.size() >= MAX_LAYOUTS) {
                    this.layouts.clear();
                }
                layout = new Layout(data.length-1);
                this.layouts.put(data.length-1, layout);
            }
            layout.lastTick = this.tickCounter;

            Arrays.fill(layout.lables, null);
            for (int i = 1; i < meta.length; i++) {
                long next = data[i];
                long delta = next - current;
                float time = (float) (((double)delta)/1_000_000);
                layout.timings[i-1] = Math.max(layout.timings[i-1]*0.99f+time*0.01f, time);
                layout.lables[i-1] = meta[i-1];
                //Raw per-pass time to the profiler, not the rolling max kept above: a window wants the
                //average over its frames, and the rolling value never comes back down after a spike
                me.cortex.voxy.commonImpl.VoxyProfile.recordGpuMillis(
                        meta[i-1] == null ? ("pass" + (i-1)) : meta[i-1], time);
                current = next;
            }
        });
        me.cortex.voxy.commonImpl.VoxyProfile.noteGpuFrame();
        this.timingSet.tick();
    }

    public void free() {
        this.timingSet.free();
    }

    public interface TimingDataConsumer <T> {
        void accept(T metadata, long[] timings);
    }
    private static final class GlTimestampQuerySet <T> extends TrackedObject {

        private record InflightRequest<T>(int[] queries, T[] meta, TimingDataConsumer<T[]> callback) {
            private boolean callbackIfReady(IntArrayFIFOQueue queryPool) {
                boolean ready = glGetQueryObjecti(this.queries[this.queries.length-1], GL_QUERY_RESULT_AVAILABLE) == GL_TRUE;
                if (!ready) {
                    return false;
                }
                long[] results = new long[this.queries.length];
                for (int i = 0; i < this.queries.length; i++) {
                    results[i] = glGetQueryObjecti64(this.queries[i], GL_QUERY_RESULT);
                    queryPool.enqueue(this.queries[i]);
                }
                this.callback.accept(this.meta, results);
                return true;
            }
        }
        private final IntArrayFIFOQueue POOL = new IntArrayFIFOQueue();
        private final ObjectArrayFIFOQueue<InflightRequest<T>> INFLIGHT = new ObjectArrayFIFOQueue();

        private final int[] queries = new int[64];
        private final T[] metadata;
        private int index;


        private GlTimestampQuerySet(Class<T> metaClass) {
            this.metadata = (T[]) Array.newInstance(metaClass, 64);
        }

        public void capture(T metadata) {
            if (this.index >= this.metadata.length) {
                throw new IllegalStateException();
            }
            int slot = this.index++;
            this.metadata[slot] = metadata;
            int query = this.getQuery();
            glQueryCounter(query, GL_TIMESTAMP);
            this.queries[slot] = query;

        }

        public void download(TimingDataConsumer<T[]> consumer) {
            if (this.index != 0) {
                var queries = Arrays.copyOf(this.queries, this.index);
                var metadata = Arrays.copyOf(this.metadata, this.index);
                Arrays.fill(this.metadata, null);
                this.index = 0;
                this.INFLIGHT.enqueue(new InflightRequest(queries, metadata, consumer));
            }
        }

        public void tick() {
            while (!INFLIGHT.isEmpty()) {
                if (INFLIGHT.first().callbackIfReady(POOL)) {
                    INFLIGHT.dequeue();
                } else {
                    break;
                }
            }
        }

        private int getQuery() {
            if (POOL.isEmpty()) {
                return glGenQueries();
            } else {
                return POOL.dequeueInt();
            }
        }

        @Override
        public void free() {
            super.free0();
            while (!POOL.isEmpty()) {
                glDeleteQueries(POOL.dequeueInt());
            }
            while (!INFLIGHT.isEmpty()) {
                glDeleteQueries(INFLIGHT.dequeue().queries);
            }
        }
    }
    /*
    private static final class GlTimestampQuerySet extends TrackedObject {
        private final int query = glGenQueries();
        public final GlBuffer store;
        public final int[] metadata;
        public int index;
        public GlTimestampQuerySet(int maxCount) {
            this.store = new GlBuffer(maxCount*8L);
            this.metadata = new int[maxCount];
        }

        public void capture(int metadata) {
            if (this.index>this.metadata.length) {
                throw new IllegalStateException();
            }
            int slot = this.index++;
            this.metadata[slot] = metadata;
            glQueryCounter(this.query, GL_TIMESTAMP);//This should be gpu side, so should be fast
            glFinish();
            glGetQueryBufferObjectui64v(this.query, this.store.id, GL_QUERY_RESULT_NO_WAIT, slot*8L);
            glMemoryBarrier(-1);
        }

        public void download(TimingDataConsumer consumer) {
            var meta = Arrays.copyOf(this.metadata, this.index);
            this.index = 0;
            //DownloadStream.INSTANCE.download(this.store, buffer->consumer.accept(meta, buffer));
        }

        @Override
        public void free() {
            super.free0();
            glDeleteQueries(this.query);
            this.store.free();
        }
    }*/
}
