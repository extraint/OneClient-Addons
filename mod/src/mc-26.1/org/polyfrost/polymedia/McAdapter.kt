package org.polyfrost.polymedia

import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Minecraft

/** 26.1.x: the main render target lives on Minecraft. */
internal fun mainRenderTarget(mc: Minecraft): RenderTarget = mc.mainRenderTarget
