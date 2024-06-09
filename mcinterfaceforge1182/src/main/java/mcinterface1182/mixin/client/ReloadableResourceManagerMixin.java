package mcinterface1182.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mcinterface1182.InterfaceSound;
import minecrafttransportsimulator.entities.components.AEntityD_Definable;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import net.minecraft.server.packs.resources.ReloadableResourceManager;

@Mixin(ReloadableResourceManager.class)
public abstract class ReloadableResourceManagerMixin {

    /**
     * Kill off any sounds and models.  Their cached indexes will get fouled here if we don't.
     */
    @Inject(method = "close", at = @At(value = "TAIL"))
    public void inject_close(CallbackInfo ci) {
        //FIXME need to figure out the right class to put this in for a F3+T reload.
        //Stop all sounds, since sound slots will have changed.
        InterfaceSound.stopAllSounds();

        //Clear all model caches, since OpenGL indexes will have changed.
        AWrapperWorld world = InterfaceManager.clientInterface.getClientWorld();
        if (world != null) {
        	for(AEntityD_Definable<?> entity : world.getEntitiesExtendingType(AEntityD_Definable.class)) {
        		entity.resetModelsAndAnimations();
        	}
        }
    }
}
