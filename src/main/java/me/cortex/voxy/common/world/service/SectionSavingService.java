package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

//TODO: add an option for having synced saving, that is when call enqueueSave, that will instead, instantly
// save to the db, this can be useful for just reducing the amount of thread pools in total
// might have some issues with threading if the same section is saved from multiple threads?
public class SectionSavingService {
    private static final int SOFT_MAX_QUEUE_SIZE = 5_000;

    private final Service service;
    private record SaveEntry(WorldEngine engine, WorldSection section) {}
    private final ConcurrentLinkedDeque<SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();

    public SectionSavingService(ServiceManager sm) {
        this.service = sm.createServiceNoCleanup(() -> this::processJob, 100, "Section saving service");
    }

    //Batch bounds: sections are uncompressed ~66KB each on the wire, so cap by count AND bytes or a
    //world import piles hundreds of MB of native memory into one write.
    private static final int MAX_BATCH_SECTIONS = 64;
    private static final long MAX_BATCH_BYTES = 4L << 20;

    private void processJob() {
        this.processJob(true);
    }

    //consumePermits=false is only for the shutdown drain: Service.shutdown() has already zeroed the
    //permits, so steal() would always fail and the big shutdown save would degrade to one at a time -
    //exactly the case batching is worth the most.
    private void processJob(boolean consumePermits) {
        var task = this.saveQueue.poll();
        if (task == null) {
            return;
        }
        var staged = new ArrayList<WorldSection>(MAX_BATCH_SECTIONS);
        WorldEngine batchEngine = null;
        SectionStorage.SectionSaveBatch batch = null;
        try {
            while (true) {
                //A batch may only ever hold ONE engine's sections: the queue is shared across worlds,
                //and a mixed batch would write one dimension's sections into another's database.
                if (batchEngine != task.engine()) {
                    if (batch != null) {
                        this.finishBatch(batch, staged);
                        batch.close();
                        batch = null;
                    }
                    batchEngine = task.engine();
                    batch = batchEngine.storage.createSaveBatch();
                }
                this.stageSection(batch, staged, task.section());
                if (staged.size() >= MAX_BATCH_SECTIONS || batch.dataSize() >= MAX_BATCH_BYTES) {
                    this.finishBatch(batch, staged);
                }
                //Every extra entry consumed must take its permit with it, or the queue length and the
                //service's job count drift apart (blockTillEmpty would then hang on shutdown)
                if (!consumePermits || !this.service.steal()) {
                    break;
                }
                task = this.saveQueue.poll();
                if (task == null) {
                    break;
                }
            }
        } finally {
            if (batch != null) {
                this.finishBatch(batch, staged);
                batch.close();
            }
        }
    }

    //Per-entry handling is byte-for-byte the old processJob, except saveSection becomes batch.add
    private void stageSection(SectionStorage.SectionSaveBatch batch, List<WorldSection> staged, WorldSection section) {
        section.assertNotFree();
        try {
            //Upstream 0.2.18 save race: clear the dirty flag only AFTER winning the queue exchange -
            //clearing it first opened a window where a concurrent re-dirty was silently swallowed
            if (section.exchangeIsInSaveQueue(false)) {
                section.setNotDirty();
                batch.add(section);
                staged.add(section);
                return;
            } else {
                section.setNotDirty();
            }
        } catch (Exception e) {
            Logger.error("Voxy saver had an exception while executing please check logs and report error", e);
            section.markDirty();
        }
        //Anything that did not make it into the batch is released now, as before
        section.release();
    }

    //release() only after the commit: while a section is held it stays in the tracker's loaded cache,
    //so WorldEngine.isWorldUsed() is true and the idle cleaner cannot free the world (and close the
    //storage) out from under a batch that still has staged writes.
    private void finishBatch(SectionStorage.SectionSaveBatch batch, List<WorldSection> staged) {
        if (staged.isEmpty()) {
            return;
        }
        boolean committed = true;
        try {
            batch.commit();
            me.cortex.voxy.commonImpl.PerfStats.saveBatchCommits.increment();
            me.cortex.voxy.commonImpl.PerfStats.saveBatchSections.add(staged.size());
        } catch (Exception e) {
            committed = false;
            Logger.error("Voxy saver failed to commit a batch of " + staged.size() + " sections", e);
        }
        for (var section : staged) {
            if (!committed) {
                //Dirty was already cleared, so re-mark it: tryUnload's save branch will requeue it
                section.markDirty();
            }
            section.release();
        }
        staged.clear();
    }

    /*
    public void enqueueSave(WorldSection section) {
        if (section._getSectionTracker() != null && section._getSectionTracker().engine != null) {
            this.enqueueSave(section._getSectionTracker().engine, section);
        } else {
            Logger.error("Tried saving world section, but did not have world associated");
        }
    }*/

    public boolean enqueueSave(WorldEngine in, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        //If its not enqueued for saving then enqueue it
        if (section.exchangeIsInSaveQueue(true)) {
            if (!sectionAlreadyAcquired) {
                section.acquire(); //Acquire the section for use
            }

            //Hard limit the save count to prevent OOM
            if ((!nonBlocking) && this.getTaskCount() > SOFT_MAX_QUEUE_SIZE) {
                //wait a bit
                Thread.yield();
                /*
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }*/
                //If we are still full, process entries in the queue ourselves instead of waiting for the service
                while (this.getTaskCount() > SOFT_MAX_QUEUE_SIZE && this.service.isLive()) {
                    if (!this.service.steal()) {
                        break;
                    }
                    this.processJob();
                }
            }

            this.saveQueue.add(new SaveEntry(in, section));
            this.service.execute();
            return true;
        }
        return false;
    }

    public void shutdown() {
        if (this.service.numJobs() != 0) {
            Logger.error("Voxy section saving still in progress, estimated " + this.service.numJobs() + " sections remaining.");
            this.service.blockTillEmpty();
        }
        this.service.shutdown();
        //Manually save any remaining entries. Permits are already zeroed by shutdown(), so drain
        //without stealing them - otherwise this degrades to one section per batch.
        while (!this.saveQueue.isEmpty()) {
            this.processJob(false);
        }
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }
}
