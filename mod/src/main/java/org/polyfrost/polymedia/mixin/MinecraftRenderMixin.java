package org.polyfrost.polymedia.mixin;

import net.minecraft.client.Minecraft;
import org.polyfrost.polymedia.PolyMedia;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftRenderMixin {
    /** End of every rendered frame: the main render target now holds the finished image (HUD included). */
    @Inject(method = "renderFrame", at = @At("TAIL"))
    private void polymedia$onFrameEnd(boolean advanceGameTime, CallbackInfo ci) {
        PolyMedia.onFrameEnd();
    }
}
