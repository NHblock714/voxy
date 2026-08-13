package me.cortex.voxy.client.core.rendering;

//Resolved once per terrain-list rebuild instead of per visited section: sodium re-runs the full
//traversal every frame the camera moves, and a per-visit levelRenderer fetch + shadow-state
//query + interface-cast chain costs 10-20ns times 3k-10k visited sections on the render thread.
//Null when voxy is not rendering or when the shadow pass is the traversal running - the reset
//hook (MixinRenderSectionManager) is the single writer, the visit hook (MixinSectionCollector)
//the single reader, both on the render thread.
public final class ChunkBoundMaskSink {
    private ChunkBoundMaskSink() {}

    public static ChunkBoundRenderer active;
}
