package me.cortex.voxy.client.compat.create;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

//Single source of truth for where live carriage rendering ends and the distant train mesh begins.
//Live culls and the distant renderer previously used different thresholds (effective render
//distance vs the entity tracking window), leaving a two-chunk ring where both drew: the distant
//body (server-streamed pose, a beat behind) under the live actors (true pose) read as consoles and
//blaze burners floating off a train. One shared boundary makes the two mutually exclusive.
public final class TrainHandover {
    //Create's carriage entities stop being tracked past ~14 chunks regardless of how far chunks
    //render; past that the live path cannot be trusted to draw anything.
    public static final double CREATE_TRACKING_CAP = 224;

    private TrainHandover() {}

    public static double handoverDist() {
        return Math.min(CREATE_TRACKING_CAP,
                (Minecraft.getInstance().options.getEffectiveRenderDistance() - 2) * 16);
    }

    public static boolean beyondLive(Vec3 entityPos, Vec3 cam) {
        double d = handoverDist();
        return entityPos.distanceToSqr(cam) > d * d;
    }
}
