package me.cortex.voxy.client.compat.create;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import me.cortex.voxy.client.compat.ShipBorne;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

//Shared distance-cull for Create's placed kinetic block-entity visuals (shafts, cogs, gearboxes, fans,
//belts, waterwheels and the machines: press/mixer/deployer/arm/...). Their moving parts are Flywheel
//RotatingInstances with no distance limit of their own: once the block's chunk is client-loaded they
//submit as GPU instances at full detail, and under iris+colorwheel (Flywheel forced on) that is the
//only draw path. EntityCulling (nowheel) only occlusion-culls them, which fails over voxy LOD where
//there is no real block to occlude - so a spinning shaft floats past the render distance on top of the
//LOD. Chunks load in a horizontal cylinder (full height) while rendering culls to a sphere, so the
//worst case is a machine straight down a deep mine: still loaded, still animating, but well past the
//render sphere.
//
//We hide every instance a visual owns beyond the effective render distance (3D spherical, honoring
//height) and reveal them on return, cutting the moving part exactly where the static body drops to LOD.
//collectCrumblingInstances enumerates a visual's drawable instances without allocating, so it is the one
//type-agnostic handle to reach them all whatever the machine. setVisible drops the instance from the
//instancer's draw list and is fully reversible - the RotatingInstance keeps its axis/speed/offset.
public final class KineticCull {
    private KineticCull() {}

    //Constant consumers so the per-frame collectCrumblingInstances walk never allocates.
    private static final Consumer<Instance> HIDE = instance -> {
        if (instance != null) {
            instance.setVisible(false);
        }
    };
    private static final Consumer<Instance> SHOW = instance -> {
        if (instance != null) {
            instance.setVisible(true);
            //A block update may have re-pushed rotation params while the instance was hidden; mark it so
            //the reveal reuploads the current state.
            instance.setChanged();
        }
    };

    //Cut exactly at the effective render distance (already min of client/server, spherical), where the
    //vanilla block mesh hands over to the LOD copy. No margin - the moving part should vanish precisely
    //as its static body becomes LOD.
    //Frame-constant; refreshed by KineticSnapshots.tick, read from Flywheel's plan workers - a volatile
    //read is the whole per-visual cost. Negative until the first refresh (and after clearAll), which
    //falls back to the live computation so those frames cull identically.
    static volatile double cachedReachSq = -1;

    private static double reachSq() {
        double cached = cachedReachSq;
        if (cached >= 0) {
            return cached;
        }
        double reach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        return reach * reach;
    }

    //For the other per-frame cull sites (track visuals): same cached value, public face
    public static double reachSqShared() {
        return reachSq();
    }

    private static boolean beyond(BlockPos pos, double camX, double camY, double camZ) {
        double dx = pos.getX() + 0.5 - camX;
        double dy = pos.getY() + 0.5 - camY;
        double dz = pos.getZ() + 0.5 - camZ;
        return (dx * dx + dy * dy + dz * dz) > reachSq();
    }

    //Flywheel visual path: the camera comes from the frame context.
    public static boolean beyond(BlockPos pos, DynamicVisual.Context ctx, net.minecraft.world.level.Level visualLevel) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics) {
            return false;
        }
        //A contraption visual is built against Create's virtual render world and reports a
        //contraption-local position, so the distance from it to the camera means nothing - the
        //structure carries its parts wherever it goes and Create draws them with it.
        if (visualLevel != Minecraft.getInstance().level) {
            return false;
        }
        //Ship-borne machines render natively, uncut: a ship is one connected drivetrain, and any
        //snapshot/recapture scheme desynchronises adjacent shafts. Ships are few, so the full
        //Flywheel render is cheap; LOD-depth occlusion is the vanilla-depth writeback's concern.
        if (ShipBorne.isShipBorne(pos)) {
            return false;
        }
        Vec3 cam = ctx.camera().getPosition();
        return beyond(pos, cam.x, cam.y, cam.z);
    }

    //Distance culls may only apply to block entities living in the real client level: Ponder
    //scenes, schematic previews and contraption render worlds all carry scene-local positions
    //that read as "beyond" against the world camera - culling those blanks the Ponder UI's
    //curved tracks and station overlays. Create itself never world-camera-culls a PonderLevel.
    public static boolean isWorldPlaced(net.minecraft.world.level.block.entity.BlockEntity be) {
        return be.getLevel() == Minecraft.getInstance().level;
    }

    //Vanilla-BER fallback path (Flywheel backend off): the camera comes from the game renderer.
    public static boolean beyondForRender(BlockPos pos) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics || ShipBorne.isShipBorne(pos)) {
            return false;
        }
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return false;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double dx = pos.getX() + 0.5 - cam.x;
        double dy = pos.getY() + 0.5 - cam.y;
        double dz = pos.getZ() + 0.5 - cam.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > reachSq()) {
            return true;
        }
        //The sweep reclaims a snapshot only 16 blocks inside the reach (hysteresis against boundary
        //churn) while the distant renderer draws any section whose centre is past reach-14, so a BER
        //machine in the outer band can have its live spin and its frozen copy on screen at once (the
        //turntable ghost). Ownership is settled draw-side, with no capture or rebake involved:
        //exactly where the frozen copy will draw, the live render stands down. The section-centre
        //test mirrors DistantKineticRenderer's gate - without it the hand-off leaves a sliver where
        //neither side draws.
        double reach = Math.sqrt(reachSq());
        double inner = Math.max(0, reach - 16.0);
        if (distSq <= inner * inner) {
            return false;
        }
        double cx = (pos.getX() & ~15) + 8 - cam.x;
        double cy = (pos.getY() & ~15) + 8 - cam.y;
        double cz = (pos.getZ() & ~15) + 8 - cam.z;
        double gate = Math.max(0, reach - 14.0);
        return cx * cx + cy * cy + cz * cz >= gate * gate && KineticSnapshots.drawsSnapAt(pos);
    }

    public static void hide(BlockEntityVisual visual) {
        visual.collectCrumblingInstances(HIDE);
        AzimuthBehaviourIndex.apply(visual, HIDE);
    }

    public static void show(BlockEntityVisual visual) {
        visual.collectCrumblingInstances(SHOW);
        AzimuthBehaviourIndex.apply(visual, SHOW);
    }

}
