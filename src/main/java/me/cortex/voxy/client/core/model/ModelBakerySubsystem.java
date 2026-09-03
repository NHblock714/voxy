package me.cortex.voxy.client.core.model;


import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class ModelBakerySubsystem {
    //Redo to just make it request the block faces with the async texture download stream which
    // basicly solves all the render stutter due to the baking

    //Load-bearing for VSS, which cancels shutdown() at HEAD to run its own teardown - so the body here
    //does not execute in a pack that has it, and anything added to it silently never runs. Release of
    //bakery resources has to go in ModelFactory.free()/ModelStore.free(), which VSS still calls: the
    //atlas alone is 12288x8192 RGBA8 with four mips, orphaned per world change if it is missed, and
    //TrackedObject's cleaner only logs the leak rather than freeing it.
    private final ModelStore storage = new ModelStore();
    public final ModelFactory factory;
    private final Mapper mapper;

    private final Thread processingThread;
    private volatile boolean isRunning = true;
    private volatile Throwable processingThreadException;
    public ModelBakerySubsystem(Mapper mapper) {
        this.mapper = mapper;
        try {
            this.factory = new ModelFactory(mapper, this.storage);
            this.processingThread = new Thread(()->{//TODO replace this with something good/integrate it into the async processor so that we just have less threads overall
                while (this.isRunning) {
                    while (this.factory.processAllThings());
                    //Interrupt = stop request from a teardown that cannot reach isRunning (VSS replaces
                    //shutdown()). Checked after the drain so a normal unpark still processes its work.
                    if (Thread.interrupted()) {
                        break;
                    }
                    LockSupport.park();
                }
            }, "Model factory processor");
            this.processingThread.setUncaughtExceptionHandler((t,e)->{
                this.isRunning = false;
                if (e == null) {
                    e = new RuntimeException("unhandled excpetion not added");
                }
                this.processingThreadException = e;
            });
            this.processingThread.start();
        } catch (RuntimeException | Error e) {
            //The storage's field initializer already checked the atlas out of the reuse cache; a
            //failure past that point orphans ~537MiB of VRAM unless it goes back
            this.storage.free();
            throw e;
        }
    }

    //Ahead of the pipeline: the blend indices the mesh workers wrote into geometry must exist in
    //the colour buffer before that geometry is uploaded and drawn, which happens at the head of
    //the pipeline - not at this subsystem's own budgeted upload tick, which runs after the draws.
    public void drainBlendPalette() {
        this.factory.drainBlendPalette();
    }

    public void tick(long totalBudget) {
        if (this.processingThreadException != null) {
            Logger.error(this.processingThreadException.getStackTrace().toString(), this.processingThreadException);
            throw new RuntimeException(this.processingThreadException);
        }
        this.factory.processUploads(totalBudget);
    }

    public void shutdown() {
        this.isRunning = false;
        //Interrupt as well as unpark: a full re-bake queue is minutes of drain, and the untimed
        //join below would hold the render thread for all of it. The drain aborts within one bake
        //on interrupt; whatever it left queued is heap-only, and factory.free() right after this
        //drains the queued native results.
        this.processingThread.interrupt();
        LockSupport.unpark(this.processingThread);
        try {
            this.processingThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        this.factory.free();
        this.storage.free();
    }

    //This is on this side only and done like this as only worker threads call this code
    private final ReentrantLock seenIdsLock = new ReentrantLock();
    private final ReentrantLock enqueueLock = new ReentrantLock();
    private final IntOpenHashSet seenIds = new IntOpenHashSet(6000);//TODO: move to a lock free concurrent hashmap
    public void requestBlockBake(int blockId) {
        if (this.mapper.getBlockStateCount() <= blockId
                && !me.cortex.voxy.common.world.other.SeasonalIdSpace.resolvesToState(this.mapper, blockId)) {
            Logger.error("Error, got bakeing request for out of range state id. StateId: " + blockId + " max id: " + this.mapper.getBlockStateCount(), new Exception());
            return;
        }
        this.seenIdsLock.lock();
        if (!this.seenIds.add(blockId)) {
            this.seenIdsLock.unlock();
            return;
        }
        this.seenIdsLock.unlock();
        //addEntry resolves the state and touches mod-supplied code; a throw with the lock held
        //would block every voxy worker (and hijacked sodium builders) forever at the next
        //lock() with exactly one stack trace in the log and no deadlock evidence anywhere
        this.enqueueLock.lock();
        try {
            this.factory.addEntry(blockId);
        } catch (Throwable t) {
            //Un-mark the id so a later request can retry the bake instead of the block staying
            //model-less for the session
            this.seenIdsLock.lock();
            try {
                this.seenIds.remove(blockId);
            } finally {
                this.seenIdsLock.unlock();
            }
            throw t;
        } finally {
            this.enqueueLock.unlock();
        }
        LockSupport.unpark(this.processingThread);
    }

    public void addBiome(Mapper.BiomeEntry biomeEntry) {
        this.factory.addBiome(biomeEntry);
        LockSupport.unpark(this.processingThread);
    }

    public void addDebugData(List<String> debug) {
        debug.add(String.format("IF/MC: %03d, %04d", this.factory.getInflightCount(),  this.factory.getBakedCount()));//Model bake queue/in flight/model baked count
    }

    public ModelStore getStore() {
        return this.storage;
    }

    public boolean areQueuesEmpty() {
        return this.factory.getInflightCount() == 0;
    }

    public int getProcessingCount() {
        return this.factory.getInflightCount();
    }
}
