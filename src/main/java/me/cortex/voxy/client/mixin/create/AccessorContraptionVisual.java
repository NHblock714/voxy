package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//Exposes ContraptionVisual's own `embedding` field. Accessing it from the CarriageContraptionVisual
//mixin via inherited @Shadow fails ("not located in the target class"); an accessor on the class
//that actually declares the field resolves cleanly, and every subclass inherits the interface.
@Mixin(ContraptionVisual.class)
public interface AccessorContraptionVisual {
    @Accessor("embedding")
    VisualEmbedding voxy$getEmbedding();
}
