package me.cortex.voxy.client.compat.create;

import dev.engine_room.flywheel.api.instance.Instance;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

//azimuth (bits_n_bobs' framework) renders extra moving parts through per-behaviour visuals hanging off
//a block entity's main visual - a cogwheel's chain strap is one ScrollTransformedInstance living there,
//with no per-frame callback of its own (the scroll is GPU-clock driven). Our cull runs on the parent
//visual, whose collectCrumblingInstances never enumerates the behaviour instances, so they kept drawing
//past the render distance. Behaviour visuals register their instance walker here (keyed by the parent),
//and the cull walks them alongside the parent's own instances.
//
//The stored walker must never STRONGLY reach the key: the walker object is the behaviour visual
//itself, whose parentVisual field IS the key, and a strong value-to-key edge makes a WeakHashMap
//entry immortal - every chunk re-entry then permanently retains a whole visual graph, instances
//included. The walker is held weakly; azimuth's parent visual strongly holds its behaviours while
//alive, so the weak reference stays valid exactly as long as it should. This class carries no
//azimuth types; the registering mixin only applies when azimuth is present. All access happens on
//Flywheel's frame threads and the main thread, hence the synchronized map + copy-on-write lists.
public final class AzimuthBehaviourIndex {
    private AzimuthBehaviourIndex() {}

    public interface Walker {
        void voxy$walkInstances(Consumer<Instance> action);
    }

    private static final Map<Object, List<WeakReference<Walker>>> BEHAVIOURS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    public static void register(Object parentVisual, Walker walker) {
        if (parentVisual == null) {
            return;
        }
        BEHAVIOURS.computeIfAbsent(parentVisual, k -> new CopyOnWriteArrayList<>()).add(new WeakReference<>(walker));
    }

    //Applies the action to every behaviour instance registered under this visual. A walker that throws
    //(its instance already deleted mid-teardown) or was collected is dropped rather than retried.
    public static void apply(Object parentVisual, Consumer<Instance> action) {
        List<WeakReference<Walker>> walkers = BEHAVIOURS.get(parentVisual);
        if (walkers == null) {
            return;
        }
        for (WeakReference<Walker> ref : walkers) {
            Walker walker = ref.get();
            if (walker == null) {
                walkers.remove(ref);
                continue;
            }
            try {
                walker.voxy$walkInstances(action);
            } catch (Throwable e) {
                walkers.remove(ref);
            }
        }
    }

    public static int size() {
        return BEHAVIOURS.size();
    }
}
