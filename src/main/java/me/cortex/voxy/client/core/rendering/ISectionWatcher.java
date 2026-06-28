package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.common.world.WorldEngine;

public interface ISectionWatcher {
    default boolean watch(int lvl, int x, int y, int z, int types) {
        return this.watch(WorldEngine.getWorldSectionId(lvl, x, y, z), types);
    }

    boolean watch(long position, int types);

    default boolean unwatch(int lvl, int x, int y, int z, int types) {
        return this.unwatch(WorldEngine.getWorldSectionId(lvl, x, y, z), types);
    }

    boolean unwatch(long position, int types);

    default int get(int lvl, int x, int y, int z) {
        return this.get(WorldEngine.getWorldSectionId(lvl, x, y, z));
    }

    int get(long position);

    /**
     * Re-submit a geometry build for an already watched section without
     * changing its watch mask. The default implementation is a no-op so tests
     * and simple watcher implementations do not need to support active retry.
     */
    default void triggerRemesh(long position) {
    }
}

