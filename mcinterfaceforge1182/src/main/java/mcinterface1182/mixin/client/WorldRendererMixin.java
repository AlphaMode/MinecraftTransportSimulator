package mcinterface1182.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;

import mcinterface1182.InterfaceRender;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.world.phys.Vec3;

@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {
    @Shadow
    private RenderBuffers renderBuffers;

    /**
     * Need this to render translucent things at the right time.  MC doesn't properly support this natively.
     * Instead, it tries to render translucent things with the regular things and fouls the depth buffer.
     */
    @Inject(method = "renderLevel", at = @At(value = "TAIL"))
    public void inject_renderLevelBlended(PoseStack pMatrixStack, float pPartialTicks, long pFinishTimeNano, boolean pDrawBlockOutline, Camera pCamera, GameRenderer pGameRenderer, LightTexture pLightmap, Matrix4f pProjection, CallbackInfo ci) {
        MultiBufferSource.BufferSource irendertypebuffer$impl = renderBuffers.bufferSource();
        //Set camera offset point for later.
        Vec3 position = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        InterfaceRender.renderCameraOffset.set(position.x, position.y, position.z);
        InterfaceRender.doRenderCall(pMatrixStack, irendertypebuffer$impl, true, pPartialTicks);
    }
}
