package minecrafttransportsimulator.rendering.components;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import minecrafttransportsimulator.baseclasses.AEntityC_Definable;
import minecrafttransportsimulator.jsondefs.JSONAnimatedObject;
import minecrafttransportsimulator.jsondefs.JSONAnimationDefinition;

/**This class represents an object that can be rendered from an OBJ model.  This object is a set of
 * faces that are rendered during the main rendering routine.  Various transforms may be performed on
 * this object via the extended rendering classes.  These transforms are applied to the mesh prior
 * to rendering, either manipulating the mesh directly, or manipulating the OpenGL state.
 *
 * @author don_bruce
 */
public class RenderableModelObject<AnimationEntity extends AEntityC_Definable<?>> extends RenderableTransform<AnimationEntity>{
	private final String modelName;
	public final String objectName;
	private final Float[][] vertices;
	public final String applyAfter;
	
	private static final Map<String, Map<String, Integer>> displayLists = new HashMap<String, Map<String, Integer>>();
	
	public RenderableModelObject(String modelName, String objectName, JSONAnimatedObject definition, Float[][] vertices, AnimationEntity entity){
		super(definition != null ? definition.animations : new ArrayList<JSONAnimationDefinition>());
		this.modelName = modelName;
		this.objectName = objectName;
		this.vertices = vertices;
		
		if(definition != null){
			this.applyAfter = definition.applyAfter;
		}else{
			//Roller found.  Create a transform for it.
			this.applyAfter = null;
			if(objectName.toLowerCase().contains("roller")){
				transforms.add(new TransformTreadRoller<AnimationEntity>(objectName, vertices));
			}	
		}
		if(objectName.contains("&")){
			transforms.add(new TransformLight<AnimationEntity>(modelName, objectName, vertices));
		}
		if(objectName.toLowerCase().contains("window")){
			transforms.add(new TransformWindow<AnimationEntity>(vertices));
		}
		if(objectName.toLowerCase().contains("translucent")){
			transforms.add(new TransformTranslucent<AnimationEntity>());
		}else{
			transforms.add(new TransformSolid<AnimationEntity>());
		}
		if(objectName.toLowerCase().endsWith("url")){
			transforms.add(new TransformOnlineTexture<AnimationEntity>(objectName));
		}
	}
	
	/**
	 *  Renders this object, applying any transforms that need to happen.  This method also
	 *  renders any objects that depend on this object's transforms after rendering.
	 */
	public void render(AnimationEntity entity, float partialTicks, List<RenderableModelObject<AnimationEntity>> allObjects){
		GL11.glPushMatrix();
		if(doPreRenderTransforms(entity, partialTicks)){
			//Render, caching the displayList if needed.
			if(!displayLists.containsKey(modelName) || !displayLists.get(modelName).containsKey(objectName)){
				int displayListIndex = OBJParser.generateDisplayList(vertices);
				if(!displayLists.containsKey(modelName)){
					displayLists.put(modelName, new HashMap<String, Integer>());
				}
				displayLists.get(modelName).put(objectName, displayListIndex);
			}
			GL11.glCallList(displayLists.get(modelName).get(objectName));
			
			//Do post-render logic.
			doPostRenderTransforms(entity, partialTicks);
			
			//Render text on this object.
			if(InterfaceRender.renderTextMarkings(entity, objectName)){
				InterfaceRender.recallTexture();
			}
			
			//Render any parts that depend on us before we pop our state.
			for(RenderableModelObject<AnimationEntity> modelObject : allObjects){
				if(objectName.equals(modelObject.applyAfter)){
					modelObject.render(entity, partialTicks, allObjects);
				}
			}
		}
		
		//Pop state.
		GL11.glPopMatrix();
	}
	
	/**
	 *  Used to reset the display list in dev mode to allow the re-loading of models.
	 */
	public void resetDisplayList(){
		if(displayLists.containsKey(modelName) && displayLists.get(modelName).containsKey(objectName)){
			GL11.glDeleteLists(displayLists.get(modelName).remove(objectName), 1);
		}
	}
}
