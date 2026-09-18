package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class MixinDebugScreenOverlay {

    @Shadow
    private boolean renderDebug;

    @Unique
    private boolean lastDebugEnabledState = false;

    //render() only runs while the overlay is shown, so it can see F3 opening but never closing;
    //toggleOverlay() is the one path that sees both
    @Inject(method = "toggleOverlay", at = @At("TAIL"))
    private void onToggle(CallbackInfo ci) {
        this.voxy$syncDebugState();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void manageGpuTiming(GuiGraphics guiGraphics, CallbackInfo ci) {
        this.voxy$syncDebugState();
    }

    @Unique
    private void voxy$syncDebugState() {
        boolean isDebugOpen = this.renderDebug;
        if (isDebugOpen != this.lastDebugEnabledState) {
            this.lastDebugEnabledState = isDebugOpen;
            if (isDebugOpen) {
                GPUTiming.INSTANCE.enableFor(GPUTiming.OWNER_F3);
            } else {
                GPUTiming.INSTANCE.disableFor(GPUTiming.OWNER_F3);
            }
            RenderStatistics.enabled = isDebugOpen;
        }
    }

    @Inject(at = @At("RETURN"), method = "getGameInformation")
    protected void getGameInformation(CallbackInfoReturnable<List<String>> info) {
        List<String> voxyLines = new ArrayList<>();

        if (!VoxyCommon.isAvailable()) {
            voxyLines.add(ChatFormatting.RED + "voxy-"+VoxyCommon.MOD_VERSION);//Voxy installed, not avalible
            info.getReturnValue().addAll(voxyLines);
            return;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            voxyLines.add(ChatFormatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);//Voxy avalible, no instance active
            info.getReturnValue().addAll(voxyLines);
            return;
        }
        VoxyRenderSystem vrs = null;
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr != null) vrs = ((IGetVoxyRenderSystem) wr).voxy$getRenderSystem();

        //Voxy instance active
        voxyLines.add((vrs==null?ChatFormatting.DARK_GREEN:ChatFormatting.GREEN)+"voxy-"+VoxyCommon.MOD_VERSION);

        //lines.addLineToSection();
        List<String> instanceLines = new ArrayList<>();
        instance.addDebug(instanceLines);
        voxyLines.addAll(instanceLines);

        if (vrs != null) {
            List<String> renderLines = new ArrayList<>();
            vrs.addDebugInfo(renderLines);
            voxyLines.addAll(renderLines);
        }

        info.getReturnValue().addAll(voxyLines);
    }
}