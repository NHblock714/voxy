package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.thread.UnifiedServiceThreadPool;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.SectionSavingService;
import me.cortex.voxy.common.world.service.VoxelIngestService;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public abstract class VoxyInstance {
    private volatile boolean isRunning = true;
    private final Thread worldCleaner;
    public final BooleanSupplier savingServiceRateLimiter;
    protected final UnifiedServiceThreadPool threadPool;
    protected final SectionSavingService savingService;
    protected final VoxelIngestService ingestService;

    private final StampedLock activeWorldLock = new StampedLock();
    private final HashMap<WorldIdentifier, WorldEngine> activeWorlds = new HashMap<>();

    protected final ImportManager importManager;

    public VoxyInstance() {
        Logger.info("Initializing voxy instance");
        this.threadPool = new UnifiedServiceThreadPool();
        this.savingService = new SectionSavingService(this.getServiceManager());
        //Ingest fans one job out to up to MAX_LOD_LAYER+1 dirty sections, each of which becomes an
        //acquired 256KiB section in the save queue. An external producer throttling on the ingest
        //queue's depth (a pregen mod feeding tryAutoIngestChunk) cannot see that amplification, so
        //ingest gates on the save backlog itself: while it is deep, ingest stands down and the
        //queue drains, which is the only thing that reopens the gate.
        this.ingestService = new VoxelIngestService(this.getServiceManager(), this::isSaveBacklogClear);
        this.importManager = this.createImportManager();
        this.savingServiceRateLimiter = this::isSaveBacklogClear;
        this.worldCleaner = new Thread(() -> {
            //Catch inside the loop: a throw reaching outside it ends the 1Hz cleaner for the
            //rest of the session and idle engines (memtables, section arrays) pile up
            while (this.isRunning) {
                try {
                    Thread.sleep(1000);
                    this.cleanIdle();
                } catch (InterruptedException ignored) {
                    return;
                } catch (Throwable e) {
                    Logger.error("Exception in world cleaner", e);
                }
            }
        });
        this.worldCleaner.setPriority(Thread.MIN_PRIORITY);
        this.worldCleaner.setName("Active world cleaner");
        this.worldCleaner.setDaemon(true);
        this.worldCleaner.start();
    }

    protected void setNumThreads(int threads) {
        if (threads < 0) {
            throw new IllegalArgumentException("Thread count must not be negative");
        }
        if (this.threadPool.setNumThreads(threads)) {
            Logger.info("Dedicated voxy thread pool size: " + threads);
        }
    }

    public void updateDedicatedThreads() {
        this.setNumThreads(3);
    }

    protected ImportManager createImportManager() {
        return new ImportManager();
    }

    public ServiceManager getServiceManager() {
        return this.threadPool.serviceManager;
    }

    public UnifiedServiceThreadPool getThreadPool() {
        return this.threadPool;
    }

    //Sections queued for a store write, each holding an acquired 256KiB materialised section - the
    //largest single thing voxy holds under a sustained write load, and the one the memory report
    //had no line for
    public int getSaveQueueDepth() {
        return this.savingService.getTaskCount();
    }

    private boolean isSaveBacklogClear() {
        return this.savingService.getTaskCount() < 1200;
    }

    public VoxelIngestService getIngestService() {
        return this.ingestService;
    }

    public ImportManager getImportManager() {
        return this.importManager;
    }

    public WorldEngine getNullable(WorldIdentifier identifier) {
        if (!this.isRunning) {
            return null;
        }
        var cache = identifier.cachedEngineObject;
        WorldEngine world;
        if (cache == null) {
            world = null;
        } else {
            world = cache.get();
            if (world == null) {
                identifier.cachedEngineObject = null;
            } else {
                if (world.isLive()) {
                    if (world.instanceIn != this) {
                        throw new IllegalStateException("World cannot be in identifier cache, alive and not part of this instance");
                    }
                } else {
                    identifier.cachedEngineObject = null;
                    world = null;
                }
            }
        }
        if (world == null) {
            long stamp = this.activeWorldLock.readLock();
            world = this.activeWorlds.get(identifier);
            this.activeWorldLock.unlockRead(stamp);
            if (world != null) {
                identifier.cachedEngineObject = new WeakReference<>(world);
            }
        }
        if (world != null) {
            world.markActive();
        }
        return world;
    }

    public WorldEngine getOrCreate(WorldIdentifier identifier) {
        return this.getOrCreate(identifier, false);
    }

    public WorldEngine getOrCreate(WorldIdentifier identifier, boolean incrementRef) {
        if (!this.isRunning) {
            Logger.error("Tried getting world object on voxy instance but its not running");
            return null;
        }
        var world = this.getNullable(identifier);
        if (world != null) {
            world.markActive();
            if (incrementRef) {
                world.acquireRef();
            }
            return world;
        }
        //createWorld opens storage and can throw (RocksDB LOCK held by another process, corrupt
        //MANIFEST, full disk). A StampedLock has no owner tracking: a leaked write stamp is dead
        //for the whole process - every later lookup, the 1Hz cleaner AND shutdown block
        //un-interruptibly, and the emergency-save path hangs on the same lock before the crash
        //report gets written.
        long stamp = this.activeWorldLock.writeLock();
        try {
            if (!this.isRunning) {
                Logger.error("Tried getting world object on voxy instance but its not running");
                return null;
            }

            world = this.activeWorlds.get(identifier);
            if (world == null) {
                world = this.createWorld(identifier);
            }
            world.markActive();

            if (incrementRef) {
                world.acquireRef();
            }
        } finally {
            this.activeWorldLock.unlockWrite(stamp);
        }
        identifier.cachedEngineObject = new WeakReference<>(world);
        return world;
    }


    protected abstract SectionStorage createStorage(WorldIdentifier identifier);

    private WorldEngine createWorld(WorldIdentifier identifier) {
        if (!this.isRunning) {
            throw new IllegalStateException("Cannot create world while not running");
        }
        if (this.activeWorlds.containsKey(identifier)) {
            throw new IllegalStateException("Existing world with identifier");
        }
        Logger.info("Creating new world engine: " + identifier.getLongHash() + "@" + System.identityHashCode(this));
        var world = new WorldEngine(this.createStorage(identifier), this);
        world.setSaveCallback(this.savingService::enqueueSave);
        this.activeWorlds.put(identifier, world);
        return world;
    }

    public void cleanIdle() {
        List<WorldIdentifier> idleWorlds = null;
        long readStamp = this.activeWorldLock.readLock();
        try {
            for (var entry : this.activeWorlds.entrySet()) {
                if (entry.getValue().isWorldIdle()) {
                    if (idleWorlds == null) {
                        idleWorlds = new ArrayList<>();
                    }
                    idleWorlds.add(entry.getKey());
                }
            }
        } finally {
            this.activeWorldLock.unlockRead(readStamp);
        }

        if (idleWorlds == null) {
            return;
        }

        long writeStamp = this.activeWorldLock.writeLock();
        try {
            for (var id : idleWorlds) {
                var world = this.activeWorlds.remove(id);
                if (world == null) {
                    continue;
                }
                if (!world.isWorldIdle()) {
                    this.activeWorlds.put(id, world);
                    continue;
                }
                Logger.info("Shutting down idle world: " + id.getLongHash());
                try {
                    world.free();
                } catch (Throwable t) {
                    //free() may have died midway. Keep the engine registered rather than orphaning
                    //a half-closed store - a later round or shutdown retries; NOT closing the
                    //storage under it here is deliberate, live sections may still reference it
                    Logger.error("Failed to free idle world " + id.getLongHash() + ", keeping it registered", t);
                    this.activeWorlds.put(id, world);
                }
            }
        } finally {
            this.activeWorldLock.unlockWrite(writeStamp);
        }
    }

    public void addDebug(List<String> debug) {
        debug.add("MemoryBuffer, Count/Size (MB): " + MemoryBuffer.getCount() + "/" + (MemoryBuffer.getTotalSize() / 1_000_000));
        String sectionCounts = this.snapshotWorlds().stream()
                .map(world -> Integer.toString(world.getActiveSectionCount()))
                .collect(Collectors.joining(", "));
        debug.add("I/S/AWSC: " + this.ingestService.getTaskCount() + "/" + this.savingService.getTaskCount() + "/[" + sectionCounts + "]");
    }

    //One line per open engine for the memory report: engines lingering after a dimension hop are
    //the multi-hundred-MB native holders (memtable + section arrays), and this is where a leaked
    //ref shows up by name instead of as an anonymous rise
    public void addMemoryDebug(StringBuilder sb) {
        long stamp = this.activeWorldLock.readLock();
        try {
            for (var entry : this.activeWorlds.entrySet()) {
                var engine = entry.getValue();
                int lru = engine.getSecondaryCacheCount();
                sb.append(String.format("  engine %-28s active %,d, lru %,d (~%,.1f MiB)%n",
                        entry.getKey().key.location(), engine.getActiveSectionCount(),
                        lru, lru * 256.0 / 1024.0));
            }
        } finally {
            this.activeWorldLock.unlockRead(stamp);
        }
    }

    private List<WorldEngine> snapshotWorlds() {
        long stamp = this.activeWorldLock.readLock();
        try {
            return new ArrayList<>(this.activeWorlds.values());
        } finally {
            this.activeWorldLock.unlockRead(stamp);
        }
    }

    private void awaitWorldQuiescence(List<WorldEngine> worlds) {
        long nextReport = System.nanoTime() + 2_000_000_000L;
        while (true) {
            int busyWorlds = 0;
            int loadedSections = 0;
            for (var world : worlds) {
                if (world.isLive() && world.isWorldUsed()) {
                    busyWorlds++;
                    loadedSections += world.getActiveSectionCount();
                }
            }
            if (busyWorlds == 0) {
                return;
            }

            long now = System.nanoTime();
            if (now >= nextReport) {
                Logger.warn("Waiting for " + busyWorlds + " Voxy world engine(s) to release; loaded sections: " + loadedSections);
                nextReport = now + 2_000_000_000L;
            }
            LockSupport.parkNanos(1_000_000L);
        }
    }

    public void shutdown() {
        Logger.info("Shutting down voxy instance");
        this.isRunning = false;
        this.worldCleaner.interrupt();
        try {
            this.worldCleaner.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.error("Interrupted while stopping the Voxy world cleaner", e);
        }

        try {
            this.cleanIdle();
        } catch (Throwable t) {
            //Shutdown must reach the service teardown below even if one idle world fails to free
            Logger.error("cleanIdle failed during shutdown", t);
        }
        var worlds = this.snapshotWorlds();
        for (var world : worlds) {
            this.importManager.cancelImport(world);
        }

        // Keep the saver alive until final section releases have queued their writes.
        try {
            this.ingestService.shutdown();
        } catch (Exception e) {
            Logger.error(e);
        }
        this.awaitWorldQuiescence(worlds);
        try {
            this.savingService.shutdown();
        } catch (Exception e) {
            Logger.error(e);
        }

        long stamp = this.activeWorldLock.writeLock();
        try {
            for (var entry : this.activeWorlds.entrySet()) {
                entry.getKey().cachedEngineObject = null;
                var world = entry.getValue();
                if (world.isLive()) {
                    world.free();
                }
            }
            this.activeWorlds.clear();
        } finally {
            this.activeWorldLock.unlockWrite(stamp);
        }

        try {
            this.threadPool.shutdown();
        } catch (Exception e) {
            Logger.error(e);
        }
        Logger.info("Instance shutdown");
    }

    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return true;
    }

    public boolean isRunning() {
        return this.isRunning;
    }
}