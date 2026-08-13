package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;

import static org.lwjgl.opengl.ARBSparseBuffer.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;

public class BasicSectionGeometryData implements IGeometryData {
    public static final int SECTION_METADATA_SIZE = 32;
    private final GlBuffer sectionMetadataBuffer;
    private final GlBuffer geometryBuffer;
    public final boolean isExternalGeometryBuffer;

    private final int maxSectionCount;
    private int currentSectionCount;

    public BasicSectionGeometryData(int maxSectionCount, GlBuffer geometryBuffer) {
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = new GlBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        //8 Cause a quad is 8 bytes
        if ((geometryBuffer.size()%8)!=0) {
            throw new IllegalStateException();
        }
        this.geometryBuffer = geometryBuffer;
        this.isExternalGeometryBuffer = true;
    }

    public BasicSectionGeometryData(int maxSectionCount, long geometryCapacity) {
        this.isExternalGeometryBuffer = false;
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = new GlBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        //8 Cause a quad is 8 bytes
        if ((geometryCapacity%8)!=0) {
            throw new IllegalStateException();
        }
        long start = System.currentTimeMillis();
        String msg = "Creating and zeroing " + (geometryCapacity/(1024*1024)) + "MB geometry buffer";
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            msg += " driver states " + (Capabilities.INSTANCE.getFreeDedicatedGpuMemory()/(1024*1024)) + "MB of free memory";
        }
        Logger.info(msg);
        Logger.info("if your game crashes/exits here without any other log message, try manually decreasing the geometry capacity");
        glGetError();//Clear any errors
        GlBuffer buffer = null;
        if (!(Capabilities.INSTANCE.isNvidia&&ThreadUtils.isWindows&&Capabilities.INSTANCE.sparseBuffer)) {//This hack makes it so it doesnt crash on renderdoc
            buffer = new GlBuffer(geometryCapacity, false);//Only do this if we are not on nvidia
            //TODO: FIXME: TEST, see if the issue is that we are trying to zero the entire buffer, try only zeroing increments
            // or dont zero it at all
        } else {
            Logger.info("Running on nvidia, using workaround sparse buffer allocation");
        }
        int error = glGetError();
        if (error != GL_NO_ERROR || buffer == null) {
            if ((buffer == null || error == GL_OUT_OF_MEMORY) && Capabilities.INSTANCE.sparseBuffer) {
                if (buffer != null) {
                    Logger.error("Failed to allocate geometry buffer, attempting workaround with sparse buffers");
                    buffer.free();
                }
                buffer = new GlBuffer(geometryCapacity, GL_SPARSE_STORAGE_BIT_ARB);
                //buffer.zero();
                error = glGetError();
                if (error != GL_NO_ERROR) {
                    buffer.free();
                    throw new IllegalStateException("Unable to allocate geometry buffer using workaround, got gl error " + error);
                }
            } else {
                throw new IllegalStateException("Unable to allocate geometry buffer, got gl error " + error);
            }
        }
        this.geometryBuffer = buffer;
        long delta = System.currentTimeMillis() - start;
        Logger.info("Successfully allocated the geometry buffer in " + delta + "ms");
    }

    private long sparseCommitment = 0;//Tracks the current range of the allocated sparse buffer

    //Page commitment is a driver page-table operation that stalls the command stream, so pages a
    //frame writes must already be committed by an earlier frame. The step bounds the single-call
    //stall; the slack must exceed the per-frame apply cap on geometry uploads times the number of
    //lead frames wanted (2MB/frame cap, two frames of lead).
    private static final long COMMIT_STEP = 16L << 20;
    private static final long COMMIT_SLACK = COMMIT_STEP / 4;
    //Commitment offset/size must be multiples of the driver page size; 64KB is the floor so a
    //driver reporting smaller pages keeps the coarser (still valid) granularity
    private final long commitAlign = Math.max(65536L, me.cortex.voxy.client.core.gl.Capabilities.INSTANCE.sparseBufferPageSize);

    public void ensureAccessable(int maxElementAccess) {
        //If we are a sparse buffer, ensure the memory upto the requested size is allocated
        if (!this.geometryBuffer.isSparse()) {
            return;
        }
        long need = ((Integer.toUnsignedLong(maxElementAccess)*8L + this.commitAlign-1)/this.commitAlign)*this.commitAlign;
        if (this.sparseCommitment < need) {
            //The pages are written this frame, committing now is mandatory; include a full step so
            //the following frames stay inside committed space
            this.commitUpTo(need + COMMIT_STEP);
        } else if (this.sparseCommitment - need < COMMIT_SLACK) {
            //Within slack of the boundary: take the page-table cost now, while nothing in this
            //frame touches the pages being committed
            this.commitUpTo(this.sparseCommitment + COMMIT_STEP);
        }
    }

    private void commitUpTo(long target) {
        target = ((target + this.commitAlign-1)/this.commitAlign)*this.commitAlign;
        //A commitment range may only end unaligned when it ends exactly at the buffer end, which
        //this clamp produces; without it the call errors, commits nothing, and the advanced
        //tracker would leave every later write on uncommitted pages
        target = Math.min(target, this.geometryBuffer.size());
        if (target <= this.sparseCommitment) {
            return;
        }
        glBindBuffer(GL_ARRAY_BUFFER, this.geometryBuffer.id);
        glBufferPageCommitmentARB(GL_ARRAY_BUFFER, this.sparseCommitment, target-this.sparseCommitment, true);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        this.sparseCommitment = target;
    }

    //Render-thread read (client commands execute there)
    public long getCommittedBytes() {
        return this.geometryBuffer.isSparse() ? this.sparseCommitment : this.geometryBuffer.size();
    }

    public GlBuffer getGeometryBuffer() {
        return this.geometryBuffer;
    }

    public GlBuffer getMetadataBuffer() {
        return this.sectionMetadataBuffer;
    }

    @Override
    public int getSectionCount() {
        return this.currentSectionCount;
    }

    public void setSectionCount(int count) {
        this.currentSectionCount = count;
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public long getGeometryCapacityBytes() {//In bytes
        return this.geometryBuffer.size();
    }

    @Override
    public void free() {
        this.sectionMetadataBuffer.free();

        long gpuMemory = 0;
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            glFinish();
            gpuMemory = Capabilities.INSTANCE.getFreeDedicatedGpuMemory();
        }
        if (this.geometryBuffer.isSparse()) {
            glBindBuffer(GL_ARRAY_BUFFER, this.geometryBuffer.id);
            glBufferPageCommitmentARB(GL_ARRAY_BUFFER, 0, this.sparseCommitment, false);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }

        glFinish();

        if (!this.isExternalGeometryBuffer) {
            this.geometryBuffer.free();
            glFinish();
            if (Capabilities.INSTANCE.canQueryGpuMemory) {
                long releaseSize = (long) (this.geometryBuffer.size() * 0.75);//if gpu memory usage drops by 75% of the expected value assume we freed it
                if (this.geometryBuffer.isSparse()) {//If we are using sparse buffers, use the commited size instead
                    releaseSize = (long) (this.sparseCommitment * 0.75);
                }
                if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory <= releaseSize) {
                    Logger.info("Attempting to wait for gpu memory to release");
                    long start = System.currentTimeMillis();

                    long TIMEOUT = 400;

                    while (System.currentTimeMillis() - start < TIMEOUT) {//Wait up to 2.5 seconds for memory to release
                        glFinish();
                        if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory > releaseSize) break;
                    }
                    if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory <= releaseSize) {
                        Logger.warn("Failed to wait for gpu memory to be freed, this could indicate an issue with the driver");
                    }
                }
            }
        }
    }

    @Override
    public long getMaxCapacity() {
        return this.geometryBuffer.size();
    }
}
