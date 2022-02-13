package minecrafttransportsimulator.rendering.components;

import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.baseclasses.TransformationMatrix;
import minecrafttransportsimulator.entities.components.AEntityC_Renderable;
import minecrafttransportsimulator.mcinterface.InterfaceClient;
import minecrafttransportsimulator.mcinterface.InterfaceRender;

/**Base Entity rendering class.  
 *
 * @author don_bruce
 */
public abstract class ARenderEntity<RenderedEntity extends AEntityC_Renderable>{
	private static final Point3D interpolatedPositionHolder = new Point3D();
	private static final RotationMatrix interpolatedOrientationHolder = new RotationMatrix();
	private static final TransformationMatrix translatedMatrix = new TransformationMatrix();
	private static final TransformationMatrix rotatedMatrix = new TransformationMatrix();
	
	/**
	 *  Called to render this entity.  This is the setup method that sets states to the appropriate values.
	 *  After this, the main model rendering method is called.
	 */
	public final void render(RenderedEntity entity, boolean blendingEnabled, float partialTicks){
		//If we need to render, do so now.
		entity.world.beginProfiling("RenderSetup", true);
		if(!disableRendering(entity, partialTicks)){
			//Get the render offset.
			//This is the interpolated movement, plus the prior position.
			interpolatedPositionHolder.set(entity.prevPosition);
			interpolatedPositionHolder.interpolate(entity.position, partialTicks);
			
			//Subtract the entity's position by the render entity position to get the delta for translating.
			interpolatedPositionHolder.subtract(InterfaceClient.getRenderViewEntity().getRenderedPosition(partialTicks));
			
			//Get interpolated orientation.
			entity.getInterpolatedOrientation(interpolatedOrientationHolder, partialTicks);
	       
	        //Set up lighting.
	        InterfaceRender.setLightingToPosition(entity.position);
	        
	        //Set up matrixes.
	        translatedMatrix.resetTransforms();
	        translatedMatrix.setTranslation(interpolatedPositionHolder);
			rotatedMatrix.set(translatedMatrix);
			rotatedMatrix.applyRotation(interpolatedOrientationHolder);
			double scale = entity.prevScale + (entity.scale - entity.prevScale)*partialTicks;
			rotatedMatrix.applyScaling(scale, scale, scale);
			
	        //Render the main model.
	        entity.world.endProfiling();
	        renderModel(entity, rotatedMatrix, blendingEnabled, partialTicks);
			
			//End rotation render matrix.
			//Render holoboxes.
			if(blendingEnabled){
				renderHolographicBoxes(entity, translatedMatrix);
			}
			
			//Render bounding boxes.
			if(!blendingEnabled && InterfaceRender.shouldRenderBoundingBoxes()){
				entity.world.beginProfiling("BoundingBoxes", true);
				renderBoundingBoxes(entity, translatedMatrix);
				entity.world.endProfiling();
			}
			
			//Handle sounds.  These will be partial-tick only ones.
			//Normal sounds are handled on the main tick loop.
			entity.world.beginProfiling("Sounds", true);
			entity.updateSounds(partialTicks);
		}
		entity.world.endProfiling();
	}
	
	/**
	 *  If rendering needs to be skipped for any reason, return true here.
	 */
	protected boolean disableRendering(RenderedEntity entity, float partialTicks){
		//Don't render on the first tick, as we might have not created some variables yet.
		return entity.ticksExisted == 0;
	}
	
	/**
	 *  Called to render the main model.  At this point the matrix state will be aligned
	 *  to the position and rotation of the entity relative to the player-camera.
	 */
	protected abstract void renderModel(RenderedEntity entity, TransformationMatrix transform, boolean blendingEnabled, float partialTicks);
	
	/**
	 *  Called to render holdgraphic boxes.  These shouldn't rotate with the model, so rotation is not present here.
	 *  However, at this point the transforms will be set to the entity position, as it is assumed everything will
	 *  at least be relative to it.
	 *  Also, this method is only called when blending is enabled, because holographic stuff ain't solid.
	 */
	protected void renderHolographicBoxes(RenderedEntity entity, TransformationMatrix transform){};
	
	/**
	 *  Renders the bounding boxes for the entity, if any are present.
	 *  At this point, the translation and rotation done for the rendering 
	 *  will be un-done, as boxes need to be rendered according to their world state.
	 *  The passed-in transform is between the player and the entity.
	 */
	public void renderBoundingBoxes(RenderedEntity entity, TransformationMatrix transform){
		entity.boundingBox.renderWireframe(entity, transform, null, null);	
	}
}
