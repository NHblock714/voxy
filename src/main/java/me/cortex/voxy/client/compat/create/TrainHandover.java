package me.cortex.voxy.client.compat.create;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

//Single source of truth for where live carriage rendering ends and the distant train mesh begins.
//Live culls and the distant renderer previously used different thresholds (effective render
//distance vs the entity tracking window), leaving a ring where both drew: the distant body
//(server-streamed pose, a beat behind) under the live actors (true pose) read as consoles and
//blaze burners floating off a train. One shared boundary makes the two mutually exclusive.
public final class TrainHandover {
    //Carriage entities register a 15-chunk client tracking range; vanilla's per-player clamp is
    //min(that, viewDistance * 16) with NO extra margin (ChunkMap.TrackedEntity.updatePlayer), so
    //inside this cap the live train exists exactly as far as chunks render. One chunk of slack off
    //the 240-block type range keeps us clear of the untrack boundary jitter.
    public static final double CREATE_TRACKING_CAP = 224;

    private TrainHandover() {}

    public static double handoverDist() {
        //Full view distance, not view-minus-two: the switch belongs at the vanilla->LOD transition,
        //and small view distances made an early handover glaring (4 chunks rendered, trains going
        //distant at 2)
        return Math.min(CREATE_TRACKING_CAP,
                Minecraft.getInstance().options.getEffectiveRenderDistance() * 16);
    }

    public static boolean beyondLive(Vec3 entityPos, Vec3 cam) {
        double d = handoverDist();
        return entityPos.distanceToSqr(cam) > d * d;
    }
}
