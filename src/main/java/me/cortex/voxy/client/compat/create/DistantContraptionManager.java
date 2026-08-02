package me.cortex.voxy.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.RenderShape;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//Client-side "leave-behind snapshot" store for distant Create contraptions (bearings/windmills,
//pistons, gantries, mounted/minecart contraptions). Unlike trains - which the server streams because
//they run in unloaded chunks - a contraption is an entity the player necessarily walked past, so the
//data is already client-side. While a contraption sits inside the render distance we keep its baked
//block mesh and its live world transform up to date; once it crosses out (or its chunk unloads) the
//snapshot freezes and DistantContraptionRenderer draws it statically, holding the pose/rotation it
//had when the player left. Pure client, no server sampling, no protocol.
public final class DistantContraptionManager {
    private DistantContraptionManager() {}

    //Coordinate range of the per-carriage byte packing reused from the train path
    private static final int MAX_LOCAL = 127;

    public static final class Snapshot {
        CarriageMeshBaker.BakedCarriage mesh;
        //The blocks the mesh was built from. Kept because nothing else keeps them: the entity is the
        //only other copy and it is gone by the time the snapshot matters, and the mesh itself is an
        //opaque VBO. Without this a snapshot can never be re-baked, only held or lost - which is what
        //makes the resident set a one-way ratchet. About 12 bytes a block plus the state reference.
        Source source;
        //Vertex bytes this snapshot's mesh took when it last had one. Kept across a drop so admission
        //can ask whether rebuilding it would exceed the budget, rather than only whether there is room
        //right now - the two differ by exactly the size of the thing being admitted, which is what made
        //rebuild and evict chase each other every tick.
        long lastMeshBytes;
        //M_local from AbstractContraptionEntity.applyLocalTransforms; the world position is kept
        //separately as doubles so the draw can be camera-relative without float world-coord error.
        final Matrix4f local = new Matrix4f();
        double x, y, z;
        //How far the structure reaches from its anchor, from the source blocks. A radius rather than a
        //box because bearing poses rotate freely; every distance test against x/y/z alone understates a
        //long structure by up to this much.
        double boundRadius;
        //This entity type's tracking range in blocks. The presence cleanup may only trust "absent means
        //gone" inside it, and the types differ a lot - plain contraptions 80, gantries 160, stationary
        //320. Stored records carry it (store FORMAT 2); legacy records fall back to the default: 80 is
        //the smallest, so the verdict stays sound for whichever type the record was.
        double trackingBlocks = 80.0;
        ResourceLocation dim;
        int lightPacked = -1;
        long lastSeenMs;
        //Set once a bake ran on a non-empty contraption but produced no drawable mesh (all non-MODEL
        //blocks); stops the per-tick 64KB re-bake retry for structures that can never draw.
        boolean bakeGaveNothing;
        //Bearing/piston-driven: pose froze at the reach boundary (one refresh on the crossing tick)
        boolean frozenControlled;
        //The entity's world position changed between the last two refreshes. An anchored contraption
        //(bearing) freezes with only its angle stale; a translating one (gantry, piston, minecart
        //mount) freezes at a position the real structure immediately leaves, and every packet about
        //its later life - disassembly included - goes to the tracking clients it no longer has.
        //Inside the render distance a frozen copy of a mover is wrong the moment it freezes, standing
        //misplaced over loaded terrain; the renderer draws it only past the render distance, where a
        //leave-behind is the only information there is.
        boolean movedWhileSeen;
        //Network id of the entity behind the last refresh, for the renderer's frame-time lookup -
        //Level.getEntity(int) is the public O(1) path; the UUID re-check guards against id reuse
        int entityId = -1;
        //The entity appeared in entitiesForRendering this tick. Manager-side state only: the unseen
        //transition is when the record is written to storage. The renderer's yield samples entity
        //presence per frame instead (trackedEntity) - add/remove drains on the frame task queue, and
        //a tick-stale answer doubles or blanks the structure for several frames at every tracking
        //crossing.
        volatile boolean live;
        //Gantry rail identity: the movement axis plus the entity's two perpendicular coordinates. A
        //ghost's body sphere only meets a live crane's when the two poses overlap, but every pose of
        //one gantry shares its rail - matching on the rail reaches ghosts anywhere along the travel
        //line. -1 = not a gantry. Never persisted: a restored record has no entity to vouch that the
        //rail still belongs to the same structure.
        int railAxis = -1;
        double railU, railV;

        public Matrix4f local() { return this.local; }
        public boolean live() { return this.live; }
        public double x() { return this.x; }
        public double y() { return this.y; }
        public double z() { return this.z; }
        public ResourceLocation dim() { return this.dim; }
        public int lightPacked() { return this.lightPacked; }
        public CarriageMeshBaker.BakedCarriage mesh() { return this.mesh; }
        public Source source() { return this.source; }
        public boolean movedWhileSeen() { return this.movedWhileSeen; }
        public double trackingBlocks() { return this.trackingBlocks; }
    }

    private static final Map<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    //Read from storage on world entry and baked a few per tick, nearest first, so re-entering a world
    //with a lot of stored structures does not stall on one frame's worth of mesh uploads.
    private static final int BAKES_PER_TICK = 2;
    private static final PoseStack SCRATCH_POSE = new PoseStack();

    //Diagnostics for /voxy debug trains
    public static volatile int snapshotCount;

    //Refresh the snapshot of every loaded contraption within the LOD radius, whatever its distance.
    //Chunks load in a horizontal cylinder (full world height) while rendering culls to a sphere, so a
    //contraption straight down a deep mine is still LOADED and its motion is live even though it is
    //past the render distance - that one keeps animating in the LOD. A contraption whose chunk unloads
    //(the player walked away horizontally) simply drops out of entitiesForRendering, so its snapshot
    //stops refreshing and freezes at the last pose. Only bounded by the LOD radius (past it we never
    //draw). Runs on the client tick - applyLocalTransforms only reads entity state, no render context.
    //Did this controlled contraption's bearing/piston block just self-recapture its kinetic snapshot?
    //Consuming the notification re-freezes the structure pose on the same tick (drivetrain alignment).
    private static boolean anchorRecaptured(AbstractContraptionEntity ce) {
        var anchor = ((me.cortex.voxy.client.mixin.create.AccessorControlledContraptionEntity) ce).voxy$getControllerPos();
        return anchor != null && KineticSnapshots.consumeAnchorRecapture(anchor);
    }

    public static void update(ClientLevel level, double camX, double camY, double camZ, double maxDist) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || !VoxyConfig.CONFIG.distantContraptions) {
            if (!SNAPSHOTS.isEmpty()) {
                clearAll();
            }
            return;
        }
        loadStoredOnce(level);
        long now = System.currentTimeMillis();
        double maxDistSq = maxDist * maxDist;
        var dimId = level.dimension().location();

        var seenThisTick = new java.util.HashSet<UUID>();
        var liveBodies = new ArrayList<double[]>();
        var liveRails = new ArrayList<double[]>();
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof AbstractContraptionEntity ce)) {
                continue;
            }
            seenThisTick.add(ce.getUUID());
            //Trains (CarriageContraptionEntity, a subclass of OrientedContraptionEntity) have their own
            //dedicated remote-LOD path - DistantTrainRenderer + the server-side CreateTrainSampler that
            //streams their poses even through unloaded chunks. Snapshotting them here too would double-
            //draw and leave a frozen ghost where a train drove past. Non-train contraptions
            //(bearings/gantries/pistons/minecart-mounted OrientedContraptionEntity) still belong here.
            if (ce instanceof CarriageContraptionEntity) {
                continue;
            }
            //A contraption riding a sable ship is stored at plot-grid coordinates and only moved onto the
            //ship at render time, so a snapshot of it would be drawn ~2e7 blocks out. Sable renders it.
            //The raw coordinate check stays even if the gate misreads - during a teleport the gate's
            //container lookup can land on a tick where it answers false, and one such tick is enough to
            //mint a snapshot with a plot anchor and a ship-rebase pose, a structure that later draws
            //thousands of blocks from anywhere it ever stood.
            if (Math.abs(ce.getX()) > 1.0e6 || Math.abs(ce.getZ()) > 1.0e6
                    || me.cortex.voxy.client.compat.ShipBorne.isShipBorne(ce.getX(), ce.getZ())) {
                continue;
            }
            double dx = ce.getX() - camX, dy = ce.getY() - camY, dz = ce.getZ() - camZ;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) {
                //Past the LOD radius entirely: never drawn, no reason to refresh
                continue;
            }
            Contraption contraption = ce.getContraption();
            if (contraption == null) {
                continue;
            }
            var snap = SNAPSHOTS.computeIfAbsent(ce.getUUID(), k -> new Snapshot());
            snap.trackingBlocks = ce.getType().clientTrackingRange() * 16.0;
            //Tracked this tick. Feeds the unload-tick save gate and shields the mesh from GPU-budget
            //eviction; render-time visibility is the renderer's per-frame call (hiddenThisFrame), not
            //this flag - an EC-hidden entity's stand-in must keep its mesh resident to draw at all.
            snap.live = true;
            if (snap.mesh == null && !snap.bakeGaveNothing) {
                //A contraption first seen from afar often has no block data yet (the NBT arrives after
                //the entity), so keep retrying while it is empty. But once it has blocks and the bake
                //still produced no mesh (a structure of purely non-MODEL blocks), stop - re-baking a
                //64KB native buffer every tick forever for a snapshot that can never draw was pure waste.
                if (!contraption.getBlocks().isEmpty()) {
                    var collected = collectBlocks(contraption);
                    snap.mesh = bakeBlocks(collected);
                    snap.source = snap.mesh == null ? null : collected;
                    snap.boundRadius = snap.source == null ? 0.0 : boundRadiusOf(collected);
                    snap.bakeGaveNothing = snap.mesh == null;
                }
            } else if (snap.bakeGaveNothing) {
                me.cortex.voxy.commonImpl.PerfStats.contraptionRebakeSkipped.increment();
            }
            if (snap.mesh == null) {
                //Bookkeeping runs while the mesh is pending: the motion test needs two positions from
                //consecutive ticks, so a structure that is only tracked for the few ticks its blocks
                //take to arrive has no warm window at the first refresh that can use one - and a
                //mid-travel freeze that reads as parked is the one record the save gate exists to
                //block. Only the pose stays behind (there is nothing to draw yet); a radius-0 body
                //still supersedes ghosts whose own boundRadius covers the contact.
                trackMotion(ce, snap, now);
                snap.x = ce.getX();
                snap.y = ce.getY();
                snap.z = ce.getZ();
                snap.dim = dimId;
                snap.lastSeenMs = now;
                snap.entityId = ce.getId();
                recordRail(ce, snap, liveRails);
                liveBodies.add(new double[]{ce.getX(), ce.getY(), ce.getZ(), snap.boundRadius});
                continue;
            }
            //Bearing/piston/gantry-driven contraptions freeze at the reach boundary rather than
            //staying live: their controller block's moving parts (the bearing's top disc) are frozen
            //there by the kinetic snapshot, and a disc stopped mid-spin under a still-rotating sail
            //reads as misalignment. Freezing both halves at the same boundary keeps them meshed.
            //lastSeen still refreshes so the frozen snapshot is not evicted while loaded.
            if (ce instanceof com.simibubi.create.content.contraptions.ControlledContraptionEntity
                    && snap.lightPacked >= 0) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                double reach = mc.options.getEffectiveRenderDistance() * 16.0;
                double camDx = ce.getX() - camX, camDy = ce.getY() - camY, camDz = ce.getZ() - camZ;
                if (camDx * camDx + camDy * camDy + camDz * camDz > reach * reach) {
                    //One last full refresh ON the crossing tick, then freeze: without it the frozen
                    //pose is the tick before the boundary while the bearing disc snapshot captures the
                    //tick after - at high rpm that couple of degrees reads as the halves misaligning.
                    if (!snap.frozenControlled) {
                        snap.frozenControlled = true;
                        //Freeze tick: recapture the controller's kinetic snapshot now, so the bearing
                        //disc and the structure hold the same tick's angle - each side freezing on
                        //whichever tick it happened to cross the boundary was the residual mesh offset
                        var anchor = ((me.cortex.voxy.client.mixin.create.AccessorControlledContraptionEntity) ce).voxy$getControllerPos();
                        if (anchor != null) {
                            KineticSnapshots.recaptureAt(anchor);
                        }
                    } else if (anchorRecaptured(ce)) {
                        //The bearing disc just recaptured on its own (its boundary crossing, or the
                        //sweep): fall through to the full pose refresh below so both halves re-freeze
                        //on this same tick
                    } else {
                        //Settle-follow: a frozen structure that keeps spinning stays frozen, but when
                        //it decelerates to a stop after the freeze (power cut), the frozen pose is
                        //stale mid-spin while the bearing disc recaptures the stopped angle. Track the
                        //live pose while the per-tick change is small (settling), hold while large.
                        SCRATCH_POSE.pushPose();
                        try {
                            ce.applyLocalTransforms(SCRATCH_POSE, 1.0f);
                            var live = SCRATCH_POSE.last().pose();
                            float ax = snap.local.m00(), ay = snap.local.m01(), az = snap.local.m02();
                            float bx = live.m00(), by = live.m01(), bz = live.m02();
                            double dot = (ax * bx + ay * by + az * bz)
                                    / (Math.sqrt(ax * ax + ay * ay + az * az) * Math.sqrt(bx * bx + by * by + bz * bz) + 1.0e-9);
                            if (dot > 0.9986) { //under ~3 deg since the freeze pose: settling, follow it
                                snap.local.set(live);
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            SCRATCH_POSE.popPose();
                        }
                        //A held snapshot is still accounted for. Motion has to come from the entity's
                        //own last tick: the diff against the frozen snap.x measures the whole stroke
                        //travelled since the freeze, so it never clears and a machine that parks
                        //while held would stay marked as moving - suppressed inside the reach and
                        //vetoed from disk for as long as it stands there. Parking re-anchors it to
                        //its resting spot; the pose is already settle-followed above, and a spinning
                        //bearing translates by nothing, so its disc pairing freeze is untouched. The
                        //live body is published every tick because the supersession pass reaps the
                        //previous stroke's ghost, and a stroke cycle can be shorter than the gaps
                        //between full-refresh ticks.
                        double hmx = ce.getX() - ce.xOld, hmy = ce.getY() - ce.yOld, hmz = ce.getZ() - ce.zOld;
                        boolean parked = hmx * hmx + hmy * hmy + hmz * hmz <= 1.0e-9;
                        snap.movedWhileSeen = !parked;
                        if (parked) {
                            snap.x = ce.getX();
                            snap.y = ce.getY();
                            snap.z = ce.getZ();
                        }
                        snap.entityId = ce.getId();
                        liveBodies.add(new double[]{ce.getX(), ce.getY(), ce.getZ(), snap.boundRadius});
                        snap.lastSeenMs = now;
                        continue;
                    }
                } else {
                    snap.frozenControlled = false;
                }
            }
            //Live pose while in range - frozen the moment the player leaves (also the shared refresh
            //path for the freeze tick and the anchor-recapture re-freeze above)
            SCRATCH_POSE.pushPose();
            try {
                ce.applyLocalTransforms(SCRATCH_POSE, 1.0f);
                var pose = SCRATCH_POSE.last().pose();
                //A structure-local pose translates by at most its own extent. A plot-scale translation
                //means something rebased this entity between spaces mid-capture (sable moves ship
                //content from plot coordinates onto the ship at render time, and a teleport can land a
                //tick where the ship gate misreads) - drawn at the entity's world position, that matrix
                //puts the structure thousands of blocks from where it belongs. Keep the previous pose.
                if (Math.abs(pose.m30()) < 100_000f && Math.abs(pose.m31()) < 100_000f
                        && Math.abs(pose.m32()) < 100_000f) {
                    snap.local.set(pose);
                }
            } catch (Throwable ignored) {
            } finally {
                SCRATCH_POSE.popPose();
            }
            trackMotion(ce, snap, now);
            snap.x = ce.getX();
            snap.y = ce.getY();
            snap.z = ce.getZ();
            snap.dim = dimId;
            snap.lightPacked = DistantLightSampler.samplePeek(level,
                    (int) Math.floor(ce.getX()), (int) Math.floor(ce.getY()), (int) Math.floor(ce.getZ()));
            snap.lastSeenMs = now;
            snap.entityId = ce.getId();
            recordRail(ce, snap, liveRails);
            liveBodies.add(new double[]{ce.getX(), ce.getY(), ce.getZ(), snap.boundRadius});
        }

        var storage = storageFor(level);
        for (var entry : SNAPSHOTS.entrySet()) {
            if (!seenThisTick.contains(entry.getKey())) {
                var snap = entry.getValue();
                //The tick it stops being live is the tick its pose stops changing, so that is when the
                //record is worth writing. A pose frozen mid-travel is not worth keeping: it is wrong
                //already and the structure mints a fresh record when it is next seen.
                if (snap.live && !snap.movedWhileSeen && storage != null) {
                    ContraptionStore.save(storage, entry.getKey(), snap);
                }
                snap.live = false;
            }
        }

        //A live contraption whose body overlaps a different snapshot's body supersedes it. Gantries
        //and pistons mint a new entity UUID on every assembly, so the old record over the same travel
        //line can only be this structure's previous life - and no cleanup path reaches it from here:
        //the disassembly packet went to tracking clients it no longer had, disassembledInPlace samples
        //the ghost's stale positions rather than where the blocks really returned, and presence needs
        //the player next to the stale anchor. The 2s guard is the same entity-sync grace as below, so
        //a tracked neighbour whose sync hiccups a tick beside another live structure is not deleted.
        //A wrong hit costs a snapshot the next approach re-mints; storage goes with it (removeDead) or
        //the ghost returns on the next world entry.
        //Rail identity reaches where body overlap cannot: a gantry ghost frozen mid-travel sits
        //anywhere along the line, usually nowhere near where the live crane happens to be right now,
        //and past the tracking range the overlap is geometrically impossible. Mid-travel poses are
        //never persisted, so a moved ghost on a live crane's rail can only be a previous life (or a
        //second carriage's equally-wrong mid-travel freeze, which re-mints on the next approach).
        if (!liveBodies.isEmpty() || !liveRails.isEmpty()) {
            for (var entry : SNAPSHOTS.entrySet()) {
                var s = entry.getValue();
                if (seenThisTick.contains(entry.getKey()) || !dimId.equals(s.dim)
                        || now - s.lastSeenMs < 2000) {
                    continue;
                }
                boolean superseded = false;
                for (double[] body : liveBodies) {
                    double ox = s.x - body[0], oy = s.y - body[1], oz = s.z - body[2];
                    double touch = s.boundRadius + body[3];
                    if (ox * ox + oy * oy + oz * oz < touch * touch) {
                        superseded = true;
                        break;
                    }
                }
                if (!superseded && s.movedWhileSeen && s.railAxis >= 0) {
                    for (double[] rail : liveRails) {
                        if ((int) rail[0] == s.railAxis && Math.abs(rail[1] - s.railU) < 0.5
                                && Math.abs(rail[2] - s.railV) < 0.5) {
                            superseded = true;
                            break;
                        }
                    }
                }
                if (superseded) {
                    removeDead(entry.getKey());
                }
            }
        }

        bakeDormant(camX, camY, camZ, maxDist);

        //Leave-behinds are permanent while far away: the entity drops off the client at the server's
        //entity tracking range, far inside the LOD radius, so any time-based expiry deletes the
        //snapshot long before the player is far enough to look back at it. Cleanup is presence-based
        //instead: within the radius where this entity type would certainly be tracked, a snapshot whose
        //entity did not appear this tick no longer exists (disassembled/removed). The radius is the
        //type's own tracking range - a flat few dozen blocks left bearing structures undeletable from
        //anywhere but right next to the anchor. Clamped inside the render distance, past which even a
        //tracked entity is not guaranteed to be sent.
        //
        //The anchor is a single point and the structure reaches boundRadius past it, so a player next
        //to the far end of a long ghost can be well outside the anchor's tracking radius - where entity
        //absence proves nothing, since a live entity would not be tracked from here either. There the
        //blocks themselves answer: a structure disassembled in place stands as real world blocks
        //exactly where the ghost draws them, and sampling the ghost against the level settles it.
        double reach = net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        SNAPSHOTS.entrySet().removeIf(entry -> {
            var s = entry.getValue();
            if (seenThisTick.contains(entry.getKey())) {
                return false;
            }
            //Neither mesh nor source: it cannot draw, rebake, or persist, and the only thing that
            //refills it is a live re-sighting, which mints its own fields anyway. Nothing on disk
            //either - the save rejects a null source.
            if (s.mesh == null && s.source == null && now - s.lastSeenMs >= 2000) {
                return true;
            }
            double sx = s.x - camX, sy = s.y - camY, sz = s.z - camZ;
            double anchorDistSq = sx * sx + sy * sy + sz * sz;
            double presenceRadius = Math.min(s.trackingBlocks, Math.max(16.0, reach - 8.0));
            if (anchorDistSq > presenceRadius * presenceRadius) {
                //Anchor beyond certain tracking. If the camera is at least near the body, ask the level.
                double bodyReach = presenceRadius + s.boundRadius;
                if (anchorDistSq <= bodyReach * bodyReach
                        && now - s.lastSeenMs >= 2000 && disassembledInPlace(level, s)) {
                    if (s.mesh != null) {
                        s.mesh.close();
                    }
                    if (storage != null) {
                        ContraptionStore.remove(storage, entry.getKey());
                    }
                    return true;
                }
                return false;
            }
            //Grace only for entity-sync lag: any longer and a player who disassembles a structure and
            //walks off crosses the presence line before the check fires, leaving a permanent ghost.
            if (now - s.lastSeenMs < 2000) {
                return false;
            }
            //Presence is only proof where the client also has block data: with the render distance
            //past the server's view distance, a snapshot can sit within the presence radius while its
            //chunk was never sent - entity absence there says nothing about the blocks.
            if (!level.isLoaded(net.minecraft.core.BlockPos.containing(s.x, s.y, s.z))) {
                return false;
            }
            if (s.mesh != null) {
                s.mesh.close();
            }
            //Presence removal means the structure no longer exists - the record goes with it, or the
            //same ghost restores at the next world entry and has to be walked to all over again.
            if (storage != null) {
                ContraptionStore.remove(storage, entry.getKey());
            }
            return true;
        });

        //An upper bound the presence check cannot provide. Presence only fires within a few dozen blocks,
        //so a snapshot the player leaves behind and never walks back to is kept for the whole session -
        //and one left in another dimension is kept forever, since the renderer skips it on dim and the
        //check above never looks at dim either. Both cost the same VBO as a visible one.
        //
        //This is an addition to the presence check, not a replacement: a time-based expiry would delete
        //legitimate snapshots long before the player is far enough away to look back at them, which is
        //why there is none. Distance is safe because anything past the render radius is not drawn.
        SNAPSHOTS.entrySet().removeIf(entry -> {
            var s = entry.getValue();
            if (seenThisTick.contains(entry.getKey())) {
                return false;
            }
            //The anchor is a point; the body reaches boundRadius past it. Without the margin a long
            //structure whose anchor sits just past the line has its mesh dropped while its near end is
            //still on screen.
            double evictDist = maxDist + 32.0 + s.boundRadius;
            double evictDistSq = evictDist * evictDist;
            //Another dimension's snapshot is not coming back into view here, and the renderer filters on
            //dimension anyway, so that one goes entirely - the record is on disk if it is worth keeping.
            if (!dimId.equals(s.dim)) {
                dropMesh(s);
                return true;
            }
            double sx = s.x - camX, sy = s.y - camY, sz = s.z - camZ;
            if ((sx * sx + sy * sy + sz * sz) > evictDistSq) {
                //Only the mesh. The block list it was built from is a few kilobytes against a few
                //hundred for the mesh, and keeping it is what lets the structure come back on approach
                //rather than waiting for the next world load to read it off disk again.
                dropMesh(s);
            }
            return false;
        });

        enforceGpuBudget(camX, camY, camZ);
        snapshotCount = SNAPSHOTS.size();
    }

    //Consecutive-refresh difference when the window is warm; across a gap the stored position is old
    //and the difference measures the gap, not motion, so the entity's own last-tick position seeds the
    //flag instead - a mover sighted for a single tick must still count as moving, or its mid-travel
    //pose reaches the disk. Parked entities sync bit-identical positions, so the epsilon only has to
    //clear float dust.
    private static void trackMotion(AbstractContraptionEntity ce, Snapshot snap, long now) {
        double mx, my, mz;
        if (now - snap.lastSeenMs < 150) {
            mx = ce.getX() - snap.x;
            my = ce.getY() - snap.y;
            mz = ce.getZ() - snap.z;
        } else {
            mx = ce.getX() - ce.xOld;
            my = ce.getY() - ce.yOld;
            mz = ce.getZ() - ce.zOld;
        }
        snap.movedWhileSeen = mx * mx + my * my + mz * mz > 1.0e-9;
    }

    //Rail identity for gantries: the movement axis plus the entity's two perpendicular coordinates.
    //Same-rail poses differ only along the axis, and adjacent parallel rails are a full block apart,
    //so a half-block tolerance separates them cleanly.
    private static void recordRail(AbstractContraptionEntity ce, Snapshot snap, List<double[]> liveRails) {
        if (!(ce instanceof com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity)) {
            return;
        }
        var move = ((me.cortex.voxy.client.mixin.create.AccessorGantryContraptionEntity) ce).voxy$getMovementAxis();
        if (move == null) {
            return;
        }
        var axis = move.getAxis();
        double u = axis == net.minecraft.core.Direction.Axis.X ? ce.getY() : ce.getX();
        double v = axis == net.minecraft.core.Direction.Axis.Z ? ce.getY() : ce.getZ();
        snap.railAxis = axis.ordinal();
        snap.railU = u;
        snap.railV = v;
        liveRails.add(new double[]{axis.ordinal(), u, v});
    }

    //Everything a bake consumes, kept together because both halves come out of the same walk over the
    //contraption and both are needed to reproduce it - the copycat model data is read from block entity
    //nbt that goes away with the entity.
    public record Source(List<ShapeBlock> blocks,
                         Map<BlockPos, net.neoforged.neoforge.client.model.data.ModelData> modelData) {
        public int blockCount() {
            return this.blocks.size();
        }
    }

    //Furthest block corner from the anchor, so distance tests can extend a point to the whole
    //structure. A radius, not a box: bearing poses rotate freely and a sphere holds under any of them.
    private static double boundRadiusOf(Source source) {
        int furthestSq = 0;
        for (var b : source.blocks()) {
            int ax = Math.abs(b.x()) + 1, ay = Math.abs(b.y()) + 1, az = Math.abs(b.z()) + 1;
            int d = ax * ax + ay * ay + az * az;
            if (d > furthestSq) {
                furthestSq = d;
            }
        }
        return Math.sqrt(furthestSq);
    }

    //Is this snapshot standing over its own blocks, placed back into the world? Disassembly returns
    //the structure's blocks to the level in the same pose the ghost draws, so a strong majority of
    //exact state matches at the ghost's own positions is its signature. Air says nothing - a live
    //structure's blocks ride the entity and leave air behind them - so only conclusive samples count,
    //and doubt keeps the snapshot: a lingering ghost is a wrong image, a deleted live structure is a
    //hole where something real stands.
    private static boolean disassembledInPlace(ClientLevel level, Snapshot s) {
        var source = s.source;
        if (source == null || source.blocks().isEmpty()) {
            return false;
        }
        var blocks = source.blocks();
        int samples = Math.min(12, blocks.size());
        int step = Math.max(1, blocks.size() / samples);
        int matched = 0, conclusive = 0;
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        var v = new org.joml.Vector3f();
        for (int i = 0; i < blocks.size() && conclusive < samples; i += step) {
            var b = blocks.get(i);
            //Same transform the draw uses: world = anchor + M_local * (block centre)
            v.set(b.x() + 0.5f, b.y() + 0.5f, b.z() + 0.5f);
            s.local.transformPosition(v);
            pos.set(net.minecraft.util.Mth.floor(s.x + v.x),
                    net.minecraft.util.Mth.floor(s.y + v.y),
                    net.minecraft.util.Mth.floor(s.z + v.z));
            if (!level.isLoaded(pos)) {
                continue;
            }
            var worldState = level.getBlockState(pos);
            if (worldState.isAir()) {
                continue;
            }
            conclusive++;
            if (worldState == b.state()) {
                matched++;
            }
        }
        return conclusive >= 6 && matched * 4 >= conclusive * 3;
    }

    private static Source collectBlocks(Contraption contraption) {
        List<ShapeBlock> blocks = new ArrayList<>();
        Map<BlockPos, net.neoforged.neoforge.client.model.data.ModelData> blockEntityData = null;
        for (var entry : contraption.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            var state = entry.getValue().state();
            if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            if (Math.abs(pos.getX()) > MAX_LOCAL || Math.abs(pos.getY()) > MAX_LOCAL || Math.abs(pos.getZ()) > MAX_LOCAL) {
                continue;
            }
            blocks.add(new ShapeBlock((byte) pos.getX(), (byte) pos.getY(), (byte) pos.getZ(), state));
            //Copycat looks live in the captured block entity nbt, not the state
            var copycatData = me.cortex.voxy.commonImpl.compat.CreateCopycatCompat
                    .materialFromContraptionNbt(state, entry.getValue().nbt());
            if (copycatData != null) {
                if (blockEntityData == null) {
                    blockEntityData = new HashMap<>();
                }
                blockEntityData.put(pos, copycatData);
            }
        }
        return new Source(blocks, blockEntityData);
    }

    private static CarriageMeshBaker.BakedCarriage bakeBlocks(Source source) {
        return CarriageMeshBaker.bake(source.blocks(), source.modelData());
    }


    private static me.cortex.voxy.common.config.section.SectionStorage storageFor(ClientLevel level) {
        var engine = me.cortex.voxy.commonImpl.WorldIdentifier.ofEngineNullable(level);
        return engine == null ? null : engine.storage;
    }

    //Which dimension's records have been read. Reading is driven from the tick rather than a level
    //event because it needs voxy's world engine for the dimension to exist, and nothing guarantees that
    //has happened by the time a level load fires - a miss there would leave the feature silently doing
    //nothing, which is indistinguishable from having stored nothing.
    private static ResourceLocation loadedFor;

    private static void loadStoredOnce(ClientLevel level) {
        var here = level.dimension().location();
        if (here.equals(loadedFor)) {
            return;
        }
        var storage = storageFor(level);
        if (storage == null) {
            //Engine not up yet - try again next tick
            return;
        }
        loadedFor = here;
        loadStored(level);
    }

    public static void loadStored(ClientLevel level) {
        var storage = storageFor(level);
        if (storage == null) {
            return;
        }
        var here = level.dimension().location();
        int restored = 0;
        for (var entry : ContraptionStore.loadAll(storage)) {
            //Another dimension's records stay on disk; they are read again when the player goes there
            if (!here.equals(entry.dim()) || SNAPSHOTS.containsKey(entry.id())) {
                continue;
            }
            //A plot-scale anchor is a ship-borne capture that slipped through a gate misread; drawn
            //where it says, it lands thousands of blocks from anything real. Records already written
            //that way stay dead on disk rather than coming back every world entry.
            if (Math.abs(entry.x()) > 1.0e6 || Math.abs(entry.z()) > 1.0e6) {
                continue;
            }
            //Dormant: it knows what it is made of and where it stood, and nothing has been uploaded for
            //it yet. The same pass that rebuilds an evicted snapshot picks these up, nearest first, so
            //there is one path for "came back into range" and "was just read off disk".
            var snap = new Snapshot();
            snap.source = entry.source();
            snap.boundRadius = boundRadiusOf(entry.source());
            snap.local.set(entry.pose());
            snap.x = entry.x();
            snap.y = entry.y();
            snap.z = entry.z();
            snap.dim = entry.dim();
            snap.trackingBlocks = entry.trackingBlocks();
            snap.lastSeenMs = System.currentTimeMillis();
            snap.live = false;
            SNAPSHOTS.put(entry.id(), snap);
            restored++;
        }
        snapshotCount = SNAPSHOTS.size();
        if (restored != 0) {
            me.cortex.voxy.common.Logger.info("Restored " + restored + " distant contraption(s) for " + here);
        }
    }



    private static void dropMesh(Snapshot snap) {
        if (snap.mesh != null) {
            snap.lastMeshBytes = snap.mesh.mesh.gpuByteSize();
            snap.mesh.close();
            snap.mesh = null;
            me.cortex.voxy.commonImpl.PerfStats.contraptionSnapshotEvicted.increment();
        }
    }

    //A snapshot that still knows what it is made of but has no mesh right now. It draws nothing and
    //costs no vertex memory until something brings it back.
    private static boolean isDormant(Snapshot snap) {
        return snap.mesh == null && snap.source != null && !snap.bakeGaveNothing;
    }

    //Vertex memory is the bound that matters - one dense structure can hold as much as a hundred small
    //ones at the same distance, so a distance cap alone says nothing about what is actually held.
    //Furthest first, because that is the one whose absence is least likely to be noticed and the one
    //least likely to be wanted back soon.
    private static void enforceGpuBudget(double camX, double camY, double camZ) {
        long budget = (long) VoxyConfig.CONFIG.distantContraptionGpuBudgetMiB * 1024L * 1024L;
        if (budget <= 0) {
            return;
        }
        long resident = 0;
        for (var snap : SNAPSHOTS.values()) {
            if (snap.mesh != null) {
                resident += snap.mesh.mesh.gpuByteSize();
            }
        }
        //Down to a fraction of the budget rather than exactly to it, or the next structure to come into
        //range evicts one and the one after that evicts it back
        long target = (budget * 9L) / 10L;
        while (resident > budget) {
            Snapshot furthest = null;
            double furthestDistSq = -1;
            for (var snap : SNAPSHOTS.values()) {
                if (snap.mesh == null || snap.live) {
                    continue;
                }
                double dx = snap.x - camX, dy = snap.y - camY, dz = snap.z - camZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > furthestDistSq) {
                    furthestDistSq = distSq;
                    furthest = snap;
                }
            }
            if (furthest == null) {
                //Everything left is live, i.e. Create is drawing it and we are not holding it for long
                break;
            }
            resident -= furthest.mesh.mesh.gpuByteSize();
            dropMesh(furthest);
            if (resident <= target) {
                break;
            }
        }
        residentGpuBytes = resident;
    }

    //Rebuilds a few dormant snapshots per tick, nearest first, while there is budget for them. Same
    //pacing as the restore path for the same reason: baking uploads a buffer.
    private static void bakeDormant(double camX, double camY, double camZ, double maxDist) {
        long budget = (long) VoxyConfig.CONFIG.distantContraptionGpuBudgetMiB * 1024L * 1024L;
        double maxDistSq = maxDist * maxDist;
        //Candidates rejected for size this tick. Without this the loop keeps picking the same nearest
        //one, finds it does not fit, and burns its whole allowance doing nothing.
        var tooBig = new java.util.HashSet<Snapshot>();
        for (int done = 0; done < BAKES_PER_TICK; done++) {
            Snapshot nearest = null;
            double nearestDistSq = Double.MAX_VALUE;
            for (var snap : SNAPSHOTS.values()) {
                if (!isDormant(snap) || tooBig.contains(snap)) {
                    continue;
                }
                double dx = snap.x - camX, dy = snap.y - camY, dz = snap.z - camZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                //Admission by the body, not the anchor: a long structure needs its mesh back as soon as
                //any of it can be on screen
                double admitDist = maxDist + snap.boundRadius;
                if (distSq > admitDist * admitDist || distSq >= nearestDistSq) {
                    continue;
                }
                nearestDistSq = distSq;
                nearest = snap;
            }
            if (nearest == null) {
                return;
            }
            //Would rebuilding it overflow the budget? Asking whether there is room now instead admits a
            //mesh that immediately puts the total over, the eviction pass takes it straight back out,
            //and the two repeat every tick - a full bake and buffer upload, twenty times a second.
            //A snapshot never yet baked has no size to check, so it is admitted and measured.
            if (budget > 0 && nearest.lastMeshBytes > 0
                    && residentGpuBytes + nearest.lastMeshBytes > budget) {
                tooBig.add(nearest);
                continue;
            }
            var mesh = bakeBlocks(nearest.source);
            if (mesh == null) {
                nearest.bakeGaveNothing = true;
                continue;
            }
            nearest.mesh = mesh;
            nearest.lastMeshBytes = mesh.mesh.gpuByteSize();
            if (nearest.lightPacked < 0) {
                //Sampled rather than stored: the sampler reads voxy's own voxel store, so it answers for
                //an unloaded chunk, and a value taken now matches the terrain it will be drawn against.
                //-1 is also the "never refreshed" sentinel the freeze logic reads, so it has to go.
                var mc = Minecraft.getInstance();
                if (mc.level != null) {
                    nearest.lightPacked = DistantLightSampler.samplePeek(mc.level,
                            (int) Math.floor(nearest.x), (int) Math.floor(nearest.y), (int) Math.floor(nearest.z));
                }
            }
            residentGpuBytes += mesh.mesh.gpuByteSize();
        }
    }

    public static long residentGpuBytes() {
        return residentGpuBytes;
    }

    private static volatile long residentGpuBytes;

    //Frame-accurate presence for the renderer's yield: the entity behind this snapshot, if it is in
    //the level right now. Entity add/remove drains on the per-frame task queue, read here at draw
    //time - the same values the entity renderer acts on this frame. Hiddenness is the caller's
    //separate question (hiddenThisFrame): present-but-hidden and absent lead to different verdicts.
    public static net.minecraft.world.entity.Entity trackedEntity(UUID id, Snapshot snap) {
        if (snap.entityId < 0) {
            return null;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        var entity = level.getEntity(snap.entityId);
        return entity != null && id.equals(entity.getUUID()) && !entity.isRemoved() ? entity : null;
    }

    //EntityCulling cancels the vanilla entity pass, and nowheel bridges the verdict to Flywheel by
    //deleting the culled entity's visual outright and blocking re-creation while the cull holds - so
    //backend-on does not mean the body draws. Hidden = EC says culled AND no pipeline is left
    //holding geometry: backend-off means the EC-cancelled vanilla pass was the only owner, and
    //backend-on counts only while a visual object actually exists.
    public static boolean hiddenThisFrame(net.minecraft.world.entity.Entity entity) {
        if (!NowheelCulled.isCulled(entity)) {
            return false;
        }
        if (!dev.engine_room.flywheel.api.visualization.VisualizationManager.supportsVisualization(entity.level())) {
            return true;
        }
        return !FlywheelVisuals.hasVisual(entity);
    }

    public static Map<UUID, Snapshot> snapshots() {
        return SNAPSHOTS;
    }

    //Disassembly observed while the blocks land beyond the client's chunk data: deleting the snapshot
    //leaves nothing at all in the LOD - the placed blocks never reach this client, and voxy's stored
    //terrain predates them. Keep the copy as a leave-behind at the final resting pose instead (the
    //packet is processed before the entity discard, so this pose is where the blocks really landed);
    //presence and in-place sampling reap it against the real blocks on the next approach.
    public static void retireToLeaveBehind(AbstractContraptionEntity ce) {
        var snap = SNAPSHOTS.get(ce.getUUID());
        if (snap == null) {
            return;
        }
        SCRATCH_POSE.pushPose();
        try {
            ce.applyLocalTransforms(SCRATCH_POSE, 1.0f);
            var pose = SCRATCH_POSE.last().pose();
            if (Math.abs(pose.m30()) < 100_000f && Math.abs(pose.m31()) < 100_000f
                    && Math.abs(pose.m32()) < 100_000f) {
                snap.local.set(pose);
            }
        } catch (Throwable ignored) {
        } finally {
            SCRATCH_POSE.popPose();
        }
        snap.x = ce.getX();
        snap.y = ce.getY();
        snap.z = ce.getZ();
        snap.movedWhileSeen = false;
        snap.frozenControlled = false;
        snap.live = false;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            var storage = storageFor(level);
            if (storage != null) {
                ContraptionStore.save(storage, ce.getUUID(), snap);
            }
        }
    }

    //A contraption that died (disassembled back into blocks, broken, killed) no longer exists - its
    //snapshot must go immediately. Only unloading (the player walking away) freezes a leave-behind.
    public static void removeDead(UUID id) {
        Snapshot snap = SNAPSHOTS.remove(id);
        if (snap != null && snap.mesh != null) {
            snap.mesh.close();
        }
        //And out of storage, or the next world entry restores a structure that was taken apart. Every
        //removal that means "gone" deletes its record - this one, presence, in-place disassembly - as
        //opposed to the distance checks, which only mean "not drawn from here" and keep it.
        var level = Minecraft.getInstance().level;
        if (level != null) {
            var storage = storageFor(level);
            if (storage != null) {
                ContraptionStore.remove(storage, id);
            }
        }
        snapshotCount = SNAPSHOTS.size();
    }

    public static void clearAll() {
        for (var snap : SNAPSHOTS.values()) {
            if (snap.mesh != null) {
                snap.mesh.close();
            }
        }
        SNAPSHOTS.clear();
        loadedFor = null;
        snapshotCount = 0;
    }
}
