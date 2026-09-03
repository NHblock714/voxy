package me.cortex.voxy.client.core.compat.eclipticseasons;

import me.cortex.voxy.client.core.model.bakery.ReuseVertexConsumer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

//Indirection so the core classes never link against EclipticSeasons: every method body that
//touches its classes lives behind this interface, and the field is only assigned when the mod is
//present (see the registration in VoxyClient).
public final class SeasonalLod {
    public static volatile View view = null;

    private SeasonalLod() {}

    //Bottom line under the EsCompatGate version floor: an ES build the floor lets through can
    //still lack a symbol the view only touches mid-mesh, which surfaces as a LinkageError on a
    //mesh worker. Retrying would throw the identical error for every remeshed section, so the callers
    //fall back to the season-neutral data and put the whole view down.
    public static void disarm(LinkageError error) {
        if (view == null) return;
        view = null;
        me.cortex.voxy.common.Logger.error(
                "Seasonal LOD disabled: EclipticSeasons symbol missing at mesh time", error);
    }

    public record SeasonalBakedModel(BakedModel model, boolean replace) { }

    public interface View {
        //Returns source untouched, or a private copy with render-only ids swapped in. The input
        //is the section's shared backing array (WorldSection.materialize) - it is read by ingest,
        //save and other mesh workers concurrently and must never be written.
        long[] substituteSection(WorldEngine world, WorldSection section, long[] source);

        //In-place seasonal pass over the four lateral neighbour face slices already pulled into
        //the mesh worker's private scratch, so same-id face culling at section borders sees the
        //same substitution on both sides.
        void substituteLateralSlices(WorldEngine world, WorldSection section,
                                     long[] neighborFaces, int neighborMsk);

        //A block whose vanilla colour provider returns a season-dependent constant without ever
        //touching getBlockTint - the probe in ModelFactory#isBiomeDependentColour cannot see it
        //and would freeze the bake-time colour into the model. The provider is passed as Object
        //to keep this interface free of EclipticSeasons types; implementations instanceof it
        //against ES's own colour sources so data-driven SeasonalColorOverrides entries are caught
        //too, not just the hardwired leaf blocks.
        boolean isSeasonalConstantTint(BlockState state, Object colourProvider);

        SeasonalBakedModel resolveSeasonalModel(BlockState state, ResourceLocation modelId);

        void renderSnowOverlay(BlockState state, RenderType layer,
                               ReuseVertexConsumer translucentVC, ReuseVertexConsumer opaqueVC);
    }
}
