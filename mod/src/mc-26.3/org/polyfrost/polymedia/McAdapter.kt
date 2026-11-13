package org.polyfrost.polymedia

import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Minecraft

/** 26.2+: the main render target moved onto GameRenderer. */
internal fun mainRenderTarget(mc: Minecraft): RenderTarget = mc.gameRenderer.mainRenderTarget()
