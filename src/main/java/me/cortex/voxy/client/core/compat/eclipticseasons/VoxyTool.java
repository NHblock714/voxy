package me.cortex.voxy.client.core.compat.eclipticseasons;

import com.teamtea.eclipticseasons.client.util.ClientCon;
import me.cortex.voxy.client.config.VoxyConfig;
import java.lang.reflect.Method;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class VoxyTool {
    public static boolean isVoxyTest() {
        return VoxyConfig.CONFIG.eclipticSeasonsSnowLod;
    }

    public static WorldEngine getWorld(Level level) {
        return VoxyTool.getVoxyInstance().getNullable(WorldIdentifier.of((Level)level));
    }

    public static Mapper getMapper(Level level) {
        WorldEngine world = VoxyTool.getWorld(level);
        return world == null ? null : world.getMapper();
    }

    public static WorldSection getWorldSection(WorldEngine into, SectionPos section) {
        int lvl = 0;
        return into.acquireIfExists(lvl, section.x() >> lvl + 1, section.y() >> lvl + 1, section.z() >> lvl + 1);
    }

    public static WorldSection getWorldSection(Level level, SectionPos section) {
        WorldEngine world = VoxyTool.getWorld(level);
        return world == null ? null : VoxyTool.getWorldSection(world, section);
    }

    //Polled from ClientLevel.tick. The snow-change flag is what EclipticSeasons raises when the term
    //rolls over, and consuming it starts one pass over the stored LOD.
    public static void tryUpdate() {
        if (!VoxyTool.isVoxyTest()) {
            return;
        }
        if (!VoxyConfig.CONFIG.eclipticSeasonsLodAutoReload) {
            return;
        }
        Level level = ClientCon.getUseLevel();
        if (level == null || !ClientCon.getAgent().isSnowChange() || SeasonalSnowRefresher.isRunning()) {
            return;
        }
        //Snow-depth broadcasts repeat for as long as it is snowing, and each pass walks the whole
        //store. The flag is left unconsumed, so a season change inside the window is deferred to the
        //next tick past it, never lost. Manual /voxy debug seasons refresh bypasses this by calling
        //start directly.
        if (System.currentTimeMillis() - SeasonalSnowRefresher.lastPassEndMillis < 60_000) {
            return;
        }
        //Nullable: the get-or-create variant would stand up an engine and a RocksDB store for a
        //dimension nothing else references, every time a term rolls over
        WorldEngine engine = WorldIdentifier.ofEngineNullable((Level)level);
        if (engine == null || !engine.isLive()) {
            //Do not consume the flag with nowhere to put the work - the next tick can try again
            return;
        }
        ClientCon.agent.setSnowChange(false);
        SeasonalSnowRefresher.start(level, engine);
    }

    @Nullable
    private static VoxyInstance getVoxyInstance() {
        VoxyInstance instance = null;
        try {
            Class<?> clazz = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
            Method method = clazz.getDeclaredMethod("getInstance", new Class[0]);
            instance = (VoxyInstance)method.invoke(null, new Object[0]);
        }
        catch (Exception exception) {
            // empty catch block
        }
        return instance;
    }
}

