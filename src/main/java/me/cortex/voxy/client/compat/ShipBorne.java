package me.cortex.voxy.client.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModList;

//Sable keeps a ship's blocks and entities in the main level at plot-grid coordinates (around 2.05e7) and
//only moves them onto the ship when rendering. A world-space distance check therefore reads ~2e7 blocks
//for anything riding a ship, so every distance cull would fire on it and nothing on a ship would ever
//draw. The culls ask here first and leave ship-borne content alone: it is drawn against the ship's own
//geometry rather than floating over the LOD, and sable already tracks and frustum-culls its sub-levels.
//
//The sable types are confined to SableShipContent, which the JVM only links once the call below actually
//runs, so a game without sable never loads them and pays a single static boolean.
public final class ShipBorne {
    private static final boolean SABLE_PRESENT = ModList.get() != null && ModList.get().isLoaded("sable");
    //Two fuses, because the calls behind them fail independently and one of them failing must not take
    //the other with it. The gate reaches only SubLevelContainer.inBounds; the self-heal reaches into
    //sable's Flywheel compat, a far larger surface that a half-synced sub-level during world load can
    //throw from on its own. Sharing one flag let that throw turn the gate off, and a gate answering
    //false means every cull measures ship-borne content at its plot coordinates ~2e7 blocks out -
    //kinetics culled away, embedded matrices zeroed, contraption snapshots baked out there. That is
    //the exact failure this class exists to prevent.
    //Volatile: written from a render thread that throws, read from every cull on the next frame.
    private static volatile boolean gateUnavailable;
    private static volatile boolean healUnavailable;

    private ShipBorne() {}

    public static boolean isShipBorne(double x, double z) {
        return inSubLevel(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    public static boolean isShipBorne(BlockPos pos) {
        return inSubLevel(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean anyShipPresent() {
        if (!SABLE_PRESENT || gateUnavailable) {
            return false;
        }
        try {
            return me.cortex.voxy.client.compat.sable.SableShipContent.hasAnyShip();
        } catch (LinkageError | RuntimeException e) {
            gateUnavailable = true;
            return false;
        }
    }

    public static me.cortex.voxy.client.compat.sable.SableScreenBounds.Result shipScreenBounds(
            double cameraX, double cameraY, double cameraZ,
            org.joml.Matrix4f modelView, org.joml.Matrix4f projection, double overhangBlocks) {
        if (!SABLE_PRESENT || gateUnavailable) {
            return me.cortex.voxy.client.compat.sable.SableScreenBounds.Result.allNear();
        }
        try {
            return me.cortex.voxy.client.compat.sable.SableShipContent.shipScreenBounds(
                    cameraX, cameraY, cameraZ, modelView, projection, overhangBlocks);
        } catch (LinkageError | RuntimeException e) {
            gateUnavailable = true;
            return me.cortex.voxy.client.compat.sable.SableScreenBounds.Result.allNear();
        }
    }

    //Self-heal for sable's join-time-only Flywheel plot registration (see SableShipContent) - safe to
    //call every frame, no-ops once the state exists
    public static void ensureShipFlywheelState(net.minecraft.world.entity.Entity entity) {
        if (!SABLE_PRESENT || healUnavailable) {
            return;
        }
        try {
            me.cortex.voxy.client.compat.sable.SableShipContent.ensureFlywheelState(entity);
        } catch (LinkageError | RuntimeException e) {
            //Only the self-heal goes; sable still registers its own plots at join time, so what is lost
            //is the gap-filling for plots that were not known then - a cosmetic degradation next to
            //losing the gate.
            healUnavailable = true;
        }
    }

    private static boolean inSubLevel(int chunkX, int chunkZ) {
        if (!SABLE_PRESENT || gateUnavailable) {
            return false;
        }
        try {
            return me.cortex.voxy.client.compat.sable.SableShipContent.inSubLevel(chunkX, chunkZ);
        } catch (LinkageError | RuntimeException e) {
            gateUnavailable = true;
            return false;
        }
    }
}
