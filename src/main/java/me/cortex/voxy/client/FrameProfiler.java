package me.cortex.voxy.client;

import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

//Records what voxy costs per frame while the player moves around, then writes a report. Built for the
//"it drops frames and I do not know which part" case: rather than guess from a static read of the code,
//sample the timers the render system already keeps and let the distribution say where the time goes.
//
//Per frame it takes the cheap numbers only (wall time + the CPU stage timers). The full subsystem debug
//text - queue depths, geometry residency, node counts - is far more expensive to build, so that is
//sampled a few times a second. Everything is buffered in memory and written once on stop, so the
//capture itself does not add IO to the frames it is measuring.
public final class FrameProfiler {
    private FrameProfiler() {}

    private static final int SNAPSHOT_INTERVAL_MS = 500;
    //A frame this long is a stall, not a slow frame - grab the render thread's stack while it is still
    //in whatever was blocking. This is the one thing an external sampling profiler cannot give us,
    //because it cannot know which of its samples landed inside a bad frame.
    private static final long STALL_THRESHOLD_MICROS = 40_000;
    private static final int MAX_STALL_CAPTURES = 40;

    //Named so the report says what each stage is instead of a bare letter
    private static final String[] STAGE_NAMES = {
            "frame total", "voxy all", "voxy main", "voxy dynamic", "voxy postDyn",
            "E maskRaster", "H modelBake", "F traversal", "G buildDrawCalls", "D downloadTick",
            "A nodeUpload", "B scatterWrite", "C cleanerIds",
    };

    private static volatile boolean active;
    private static long startedAtMs;
    private static long endAtMs;
    private static long lastSnapshotMs;
    private static long lastFrameNanos;

    //frame wall time in micros; the stage timers are millis*1000 to keep everything integral
    private static final List<long[]> frames = new ArrayList<>();
    private static final List<String> snapshots = new ArrayList<>();
    private static final List<String> stalls = new ArrayList<>();

    public static boolean isActive() {
        return active;
    }

    public static synchronized String start(int seconds) {
        if (active) {
            return "Frame capture already running, use /voxy debug capture again to stop it early";
        }
        frames.clear();
        snapshots.clear();
        stalls.clear();
        startedAtMs = System.currentTimeMillis();
        endAtMs = startedAtMs + seconds * 1000L;
        lastSnapshotMs = 0;
        lastFrameNanos = 0;
        //GPU timestamps are off by default; turn them on for the capture so the snapshots can separate
        //"the GPU is busy" from "the render thread is busy". Left on afterwards would keep costing
        //queries every frame, so stop() turns it back off.
        GPUTiming.INSTANCE.setEnabled(true);
        active = true;
        return "Frame capture armed for " + seconds + "s - move around the way that drops frames. "
                + "It stops on its own, or run the command again to stop early.";
    }

    //Called at the end of each voxy render. Must stay cheap: it runs inside the frames being measured.
    public static void onFrameEnd() {
        if (!active) {
            return;
        }
        long nowNanos = System.nanoTime();
        long frameMicros = lastFrameNanos == 0 ? 0 : (nowNanos - lastFrameNanos) / 1000;
        lastFrameNanos = nowNanos;
        if (frameMicros > 0) {
            TimingStatistics.update();
            frames.add(new long[]{
                    frameMicros,
                    micros(TimingStatistics.all),
                    micros(TimingStatistics.main),
                    micros(TimingStatistics.dynamic),
                    micros(TimingStatistics.postDynamic),
                    micros(TimingStatistics.E),
                    micros(TimingStatistics.H),
                    micros(TimingStatistics.F),
                    micros(TimingStatistics.G),
                    micros(TimingStatistics.D),
                    micros(TimingStatistics.A),
                    micros(TimingStatistics.B),
                    micros(TimingStatistics.C),
            });
            //The frame that just ENDED was long: the render thread is here, so its own stack is no
            //longer informative, but the worker threads still are - and if the stall repeats the
            //aggregate over several captures points straight at it.
            if (frameMicros >= STALL_THRESHOLD_MICROS && stalls.size() < MAX_STALL_CAPTURES) {
                stalls.add(captureStacks(frameMicros));
            }
        }

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastSnapshotMs >= SNAPSHOT_INTERVAL_MS) {
            lastSnapshotMs = nowMs;
            snapshots.add(snapshot(nowMs));
        }
        if (nowMs >= endAtMs) {
            String msg = stopAndDump();
            Logger.info(msg);
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), false);
            }
        }
    }

    private static long micros(TimingStatistics.TimeSampler sampler) {
        return (long) (sampler.getRolling() * 1000.0);
    }

    //Voxy's own threads plus the render thread. Anything blocking the render thread on a GL fence
    //usually has a partner thread doing the work, so both sides are needed to read a stall.
    private static boolean isInterestingThread(Thread t) {
        String name = t.getName();
        return name.equals("Render thread")
                || name.startsWith("Voxy")
                || name.startsWith("Async Node Manager")
                || name.contains("Ingest")
                || name.contains("Section saving")
                || name.contains("Model factory")
                || name.contains("Render gen");
    }

    private static String captureStacks(long frameMicros) {
        var sb = new StringBuilder();
        sb.append("=== stall ").append(String.format("%.1fms", frameMicros / 1000.0))
                .append(" at t+").append(System.currentTimeMillis() - startedAtMs).append("ms\n");
        try {
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (!isInterestingThread(thread)) {
                    continue;
                }
                StackTraceElement[] trace = entry.getValue();
                if (trace.length == 0) {
                    continue;
                }
                sb.append("  [").append(thread.getName()).append(" ").append(thread.getState()).append("]\n");
                //Deep enough to cross the mod boundary into whatever is actually blocking
                int limit = Math.min(trace.length, 18);
                for (int i = 0; i < limit; i++) {
                    sb.append("      ").append(trace[i]).append('\n');
                }
            }
        } catch (Throwable e) {
            sb.append("  <stack capture failed: ").append(e).append(">\n");
        }
        return sb.toString();
    }

    private static String snapshot(long nowMs) {
        var lines = new ArrayList<String>();
        try {
            var levelRenderer = Minecraft.getInstance().levelRenderer;
            if (levelRenderer instanceof me.cortex.voxy.client.core.IGetVoxyRenderSystem holder) {
                var vrs = holder.voxy$getRenderSystem();
                if (vrs != null) {
                    vrs.addDebugInfo(lines);
                }
            }
        } catch (Throwable e) {
            lines.add("<snapshot failed: " + e + ">");
        }
        return "[t+" + (nowMs - startedAtMs) + "ms] " + String.join(" | ", lines);
    }

    public static synchronized String stopAndDump() {
        if (!active) {
            return "No frame capture running";
        }
        active = false;
        GPUTiming.INSTANCE.setEnabled(false);
        if (frames.isEmpty()) {
            return "Frame capture produced no frames";
        }

        var report = new StringBuilder();
        report.append("voxy frame capture - ").append(frames.size()).append(" frames\n");
        report.append(configLine()).append("\n\n");

        String[] names = STAGE_NAMES;
        report.append(String.format("%-18s %8s %8s %8s %8s %8s%n", "stage(ms)", "p50", "p90", "p99", "max", "mean"));
        for (int col = 0; col < names.length; col++) {
            long[] values = new long[frames.size()];
            for (int i = 0; i < frames.size(); i++) {
                values[i] = frames.get(i)[col];
            }
            Arrays.sort(values);
            double mean = 0;
            for (long v : values) mean += v;
            mean /= values.length;
            report.append(String.format("%-18s %8.2f %8.2f %8.2f %8.2f %8.2f%n", names[col],
                    pct(values, 50) / 1000.0, pct(values, 90) / 1000.0, pct(values, 99) / 1000.0,
                    values[values.length - 1] / 1000.0, mean / 1000.0));
        }

        //Frame-time distribution says whether this is a steady cost or a stutter
        long[] totals = new long[frames.size()];
        for (int i = 0; i < frames.size(); i++) totals[i] = frames.get(i)[0];
        Arrays.sort(totals);
        double medianMs = pct(totals, 50) / 1000.0;
        report.append("\nimplied fps: p50=").append(String.format("%.0f", 1000.0 / Math.max(0.001, medianMs)))
                .append("  p99-frame=").append(String.format("%.1fms", pct(totals, 99) / 1000.0))
                .append("  worst=").append(String.format("%.1fms", totals[totals.length - 1] / 1000.0));

        //How much of the frame budget is spent above the stall threshold at all
        int stallFrames = 0;
        long stallMicros = 0;
        for (long t : totals) {
            if (t >= STALL_THRESHOLD_MICROS) {
                stallFrames++;
                stallMicros += t;
            }
        }
        report.append("\nstalls (>").append(STALL_THRESHOLD_MICROS / 1000).append("ms): ")
                .append(stallFrames).append(" frames, ")
                .append(String.format("%.2fs", stallMicros / 1_000_000.0)).append(" total\n");

        if (!stalls.isEmpty()) {
            report.append("\nstall stacks (").append(stalls.size()).append(" captured, cap ")
                    .append(MAX_STALL_CAPTURES).append("):\n");
            for (String s : stalls) {
                report.append(s);
            }
        }

        report.append("\nsubsystem snapshots (every ").append(SNAPSHOT_INTERVAL_MS).append("ms):\n");
        for (String s : snapshots) {
            report.append(s).append('\n');
        }

        Path out = Path.of("voxy-frame-capture.txt");
        try {
            Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.error("Failed to write frame capture", e);
            return "Frame capture failed to write: " + e;
        }
        frames.clear();
        snapshots.clear();
        stalls.clear();
        return "Frame capture written to " + out.toAbsolutePath() + " (median " + String.format("%.1f", medianMs) + "ms/frame)";
    }

    private static long pct(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int idx = Math.min(sorted.length - 1, (int) ((long) sorted.length * p / 100));
        return sorted[idx];
    }

    private static String configLine() {
        var cfg = me.cortex.voxy.client.config.VoxyConfig.CONFIG;
        var mc = Minecraft.getInstance();
        return "config: sectionRenderDistance=" + cfg.sectionRenderDistance
                + " subDivisionSize=" + cfg.subDivisionSize
                + " renderPressure=" + cfg.renderPressure
                + " leafMode=" + cfg.leafLodMode
                + " vanillaRenderDistance=" + mc.options.getEffectiveRenderDistance()
                + " shaders=" + me.cortex.voxy.client.core.util.IrisUtil.irisShaderPackEnabled()
                + " window=" + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight();
    }
}
