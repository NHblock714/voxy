package me.cortex.voxy.client;

import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

//One command, one self-contained report a tester can upload without being asked follow-up
//questions. Wraps a FrameProfiler capture (frame percentiles, GPU timings, stall stacks,
//subsystem snapshots) with everything static that bug triage always ends up asking for:
//hardware, driver, JVM sizing, the full live voxy config, mod versions, and the perf/memory
//panels at both ends of the window so counter DELTAS are readable, not just lifetime sums.
//F3 render statistics readback is forced on for the window (the shader side is always compiled)
//so the section/quad counts in the snapshots are real numbers, and restored afterwards.
public final class FullReport {
    private FullReport() {}

    private static volatile boolean prevRenderStats;
    private static volatile String startSections;
    private static volatile java.util.LinkedHashMap<String, Long> perfBaseline;
    private static long gcBaseCount, gcBaseMs;

    private static long[] gcTotals() {
        long count = 0, ms = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount();
            long t = bean.getCollectionTime();
            if (c > 0) count += c;
            if (t > 0) ms += t;
        }
        return new long[]{count, ms};
    }

    public static String start(int seconds) {
        if (FrameProfiler.isActive()) {
            return FrameProfiler.stopAndDump();
        }
        var head = new StringBuilder();
        head.append("==== voxy full report - ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append(" ====\n\n");
        appendVersions(head);
        appendHardware(head);
        appendGameState(head);
        appendConfig(head);
        head.append("---- perf counters at capture start (session-cumulative) ----\n");
        head.append(me.cortex.voxy.commonImpl.PerfStats.report()).append('\n');
        head.append("\n---- memory at capture start ----\n");
        head.append(VoxyCommands.buildMemoryReport()).append('\n');
        startSections = head.toString();

        prevRenderStats = RenderStatistics.enabled;
        RenderStatistics.enabled = true;
        perfBaseline = me.cortex.voxy.commonImpl.PerfStats.snapshotForDelta();
        long[] gc = gcTotals();
        gcBaseCount = gc[0];
        gcBaseMs = gc[1];
        //The per-system CPU table + per-pass GPU averages ride the same window - this used to
        //take a second command and a second capture
        if (!me.cortex.voxy.commonImpl.VoxyProfile.isRunning()) {
            me.cortex.voxy.commonImpl.VoxyProfile.start();
        }

        FrameProfiler.armFullReport("voxy-fullreport.txt", FullReport::finishSections);
        return FrameProfiler.start(seconds) + " Full report will land in voxy-fullreport.txt.";
    }

    //Runs inside FrameProfiler.stopAndDump, before the file is written
    private static String finishSections() {
        RenderStatistics.enabled = prevRenderStats;
        var sb = new StringBuilder();

        if (me.cortex.voxy.commonImpl.VoxyProfile.isRunning()) {
            me.cortex.voxy.commonImpl.VoxyProfile.stop();
            sb.append("\n---- per-system CPU + per-pass GPU (this window) ----\n");
            sb.append(me.cortex.voxy.commonImpl.VoxyProfile.report()).append('\n');
        }

        sb.append("\n---- window deltas ----\n");
        long[] gc = gcTotals();
        sb.append(String.format("  gc                  %,d collections, %,d ms paused%n",
                gc[0] - gcBaseCount, gc[1] - gcBaseMs));
        var base = perfBaseline;
        if (base != null) {
            var now = me.cortex.voxy.commonImpl.PerfStats.snapshotForDelta();
            for (var e : now.entrySet()) {
                long delta = e.getValue() - base.getOrDefault(e.getKey(), 0L);
                if (delta != 0) {
                    sb.append(String.format("  %-32s %+,d%n", e.getKey(), delta));
                }
            }
        }

        sb.append("\n---- perf counters at capture end (subtract the start section for the window) ----\n");
        sb.append(me.cortex.voxy.commonImpl.PerfStats.report()).append('\n');
        sb.append("\n---- memory at capture end ----\n");
        sb.append(VoxyCommands.buildMemoryReport()).append('\n');
        sb.append("\n---- renderer state (last frame of window) ----\n");
        try {
            var lines = new ArrayList<String>();
            //The FULL instance debug set, not just the F3 statistics arrays: drawClamp state,
            //traversal TLN#, node manager residency - every experimental option's observation
            //promise hangs on these lines being in the report
            var levelRenderer = net.minecraft.client.Minecraft.getInstance().levelRenderer;
            if (levelRenderer instanceof me.cortex.voxy.client.core.IGetVoxyRenderSystem holder) {
                var vrs = holder.voxy$getRenderSystem();
                if (vrs != null) {
                    vrs.addDebugInfo(lines);
                }
            }
            RenderStatistics.addDebug(lines);
            for (var l : lines) {
                sb.append("  ").append(l).append('\n');
            }
        } catch (Throwable t) {
            sb.append("  <unavailable: ").append(t).append(">\n");
        }
        //The static half goes FIRST in the file; FrameProfiler appends this whole string, so
        //prepend it here
        return startSections + "\n---- frame capture ----\n(percentile table above)\n" + sb;
    }

    private static void appendVersions(StringBuilder sb) {
        sb.append("---- versions ----\n");
        for (String id : new String[]{"voxy", "sodium", "iris", "create", "flywheel", "neoforge"}) {
            ModList.get().getModContainerById(id).ifPresent(c ->
                    sb.append(String.format("  %-10s %s%n", id, c.getModInfo().getVersion())));
        }
        sb.append('\n');
    }

    private static void appendHardware(StringBuilder sb) {
        sb.append("---- hardware ----\n");
        //Runs on the render thread (client commands execute there), so GL queries are legal
        try {
            sb.append("  gpu        ").append(GL11C.glGetString(GL11C.GL_RENDERER)).append('\n');
            sb.append("  vendor     ").append(GL11C.glGetString(GL11C.GL_VENDOR)).append('\n');
            sb.append("  driver     ").append(GL11C.glGetString(GL11C.GL_VERSION)).append('\n');
            if (GL.getCapabilities().GL_NVX_gpu_memory_info) {
                int totalKb = GL11C.glGetInteger(0x9048);//GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX
                int freeKb = GL11C.glGetInteger(0x9049);//GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX
                sb.append(String.format("  vram       %,d MiB free / %,d MiB total%n", freeKb >> 10, totalKb >> 10));
            }
        } catch (Throwable t) {
            sb.append("  <gl query failed: ").append(t).append(">\n");
        }
        var rt = Runtime.getRuntime();
        sb.append(String.format("  cpu        %s %s, %d logical cores%n",
                System.getProperty("os.name"), System.getProperty("os.arch"), rt.availableProcessors()));
        sb.append(String.format("  jvm        %s, heap max %,d MiB%n",
                System.getProperty("java.version"), rt.maxMemory() >> 20));
        try {
            sb.append("  jvm args   ").append(String.join(" ",
                    ManagementFactory.getRuntimeMXBean().getInputArguments())).append('\n');
        } catch (Throwable ignored) {
        }
        sb.append('\n');
    }

    private static void appendGameState(StringBuilder sb) {
        var mc = Minecraft.getInstance();
        sb.append("---- game state ----\n");
        sb.append("  fps        ").append(mc.getFps()).append('\n');
        sb.append("  window     ").append(mc.getWindow().getWidth()).append('x').append(mc.getWindow().getHeight()).append('\n');
        sb.append("  vanilla rd ").append(mc.options.getEffectiveRenderDistance()).append(" chunks\n");
        sb.append("  shaders    ").append(me.cortex.voxy.client.core.util.IrisUtil.irisShaderPackEnabled()).append('\n');
        if (mc.level != null) {
            sb.append("  dimension  ").append(mc.level.dimension().location()).append('\n');
            if (mc.player != null) {
                sb.append(String.format("  position   %.0f %.0f %.0f%n", mc.player.getX(), mc.player.getY(), mc.player.getZ()));
            }
        }
        sb.append('\n');
    }

    private static void appendConfig(StringBuilder sb) {
        sb.append("---- voxy config (live values, not the file) ----\n");
        try {
            sb.append(new GsonBuilder().setPrettyPrinting().create().toJson(VoxyConfig.CONFIG)).append('\n');
        } catch (Throwable t) {
            sb.append("  <serialize failed: ").append(t).append(">\n");
        }
        sb.append('\n');
    }
}
