package mcinterface1201;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.TransformationMatrix;
import minecrafttransportsimulator.entities.components.AEntityC_Renderable;
import minecrafttransportsimulator.guis.components.AGUIBase;
import minecrafttransportsimulator.guis.components.GUIComponentItem;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IInterfaceRender;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.rendering.GIFParser.GIFImageFrame;
import minecrafttransportsimulator.rendering.GIFParser.ParsedGIF;
import minecrafttransportsimulator.rendering.RenderableData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Interface for the various MC rendering engines.  This class has functions for
 * binding textures, changing lightmap statuses, etc.
 *
 * @author don_bruce
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class InterfaceRender implements IInterfaceRender {
    private static final Map<String, ResourceLocation> onlineTextures = new HashMap<>();
    private static final Map<String, ParsedGIF> animatedGIFs = new HashMap<>();
    private static final Map<ParsedGIF, Map<GIFImageFrame, ResourceLocation>> animatedGIFFrames = new LinkedHashMap<>();

    private static final List<GUIComponentItem> stacksToRender = new ArrayList<>();

    private static final Map<String, RenderType> renderTypes = new HashMap<>();
    private static final Map<RenderableData, BufferData> buffers = new HashMap<>();
    private static final Map<RenderType, List<RenderData>> queuedRenders = new HashMap<>();
    private static final ConcurrentLinkedQueue<BufferData> removedRenders = new ConcurrentLinkedQueue<>();

    private static RenderStateShard.TextureStateShard MISSING_STATE;
    private static RenderStateShard.TextureStateShard BLOCK_STATE;
    private static PoseStack matrixStack;
    private static MultiBufferSource renderBuffer;
    public static Point3D renderCameraOffset = new Point3D();
    private static boolean renderingGUI;
    private static float[] matrixConvertArray = new float[16];

    @Override
    public float[] getBlockBreakTexture(AWrapperWorld world, Point3D position) {
        //Get normal model.
        BlockPos pos = BlockPos.containing(position.x, position.y, position.z);
        BlockState state = ((WrapperWorld) world).world.getBlockState(pos);
        TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getTexture(state, ((WrapperWorld) world).world, pos);
        return new float[] { sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1() };
    }

    @SuppressWarnings("deprecation")
    @Override
    public float[] getDefaultBlockTexture(String name) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(new ResourceLocation(name.replace(":", ":blocks/")));
        return new float[] { sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1() };
    }

    @Override
    public InputStream getTextureStream(String name) {
        try {
            String domain = name.substring("/assets/".length(), name.indexOf("/", "/assets/".length()));
            String location = name.substring("/assets/".length() + domain.length() + 1);
            return Minecraft.getInstance().getResourceManager().open(new ResourceLocation(domain, location));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void renderItemModel(GUIComponentItem component) {
        stacksToRender.add(component);
    }
    
    @Override
    public void renderVertices(RenderableData data, boolean changedSinceLastRender) {
        matrixStack.pushPose();
        Matrix4f matrix4f = convertMatrix4f(data.transform);
        PoseStack.Pose stackEntry = matrixStack.last();
        stackEntry.pose().mul(matrix4f);

        if (data.vertexObject.isLines) {
            VertexConsumer buffer = renderBuffer.getBuffer(RenderType.lines());
            while (data.vertexObject.vertices.hasRemaining()) {
                buffer.vertex(stackEntry.pose(), data.vertexObject.vertices.get(), data.vertexObject.vertices.get(), data.vertexObject.vertices.get());
                buffer.color(data.color.red, data.color.green, data.color.blue, data.alpha);
                buffer.endVertex();
            }
            //Rewind buffer for next read.
            data.vertexObject.vertices.rewind();
        } else {
            String typeID = data.texture + data.isTranslucent + data.lightingMode + data.enableBrightBlending;
            final RenderType renderType;
            if (data.vertexObject.cacheVertices && !renderingGUI) {
            	//Get the render type and data buffer for this entity.
                renderType = renderTypes.computeIfAbsent(typeID, k -> CustomRenderType.create("mts_entity", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 2097152, true, data.isTranslucent, CustomRenderType.createForObject(data).createCompositeState(false)));
                BufferData buffer = buffers.computeIfAbsent(data, k -> new BufferData(renderType, data));

                //Reset buffer if it's not ready.
                if (changedSinceLastRender) {
                    buffer.builder.clear();
                    buffer.isReady = false;
                }
                if (!buffer.isReady) {
                    int index = 0;
                    buffer.builder.begin(VertexFormat.Mode.QUADS, renderType.format());
                    while (data.vertexObject.vertices.hasRemaining()) {
                        //Need to parse these out first since our order differs.
                        float normalX = data.vertexObject.vertices.get();
                        float normalY = data.vertexObject.vertices.get();
                        float normalZ = data.vertexObject.vertices.get();
                        float texU = data.vertexObject.vertices.get();
                        float texV = data.vertexObject.vertices.get();
                        float posX = data.vertexObject.vertices.get();
                        float posY = data.vertexObject.vertices.get();
                        float posZ = data.vertexObject.vertices.get();

                        //Add the vertex format bits.
                        do {
                            buffer.builder.vertex(posX, posY, posZ, data.color.red, data.color.green, data.color.blue, data.alpha, texU, texV, OverlayTexture.NO_OVERLAY, data.worldLightValue, normalX, normalY, normalZ);
                        } while (++index == 3);
                        if (index == 4) {
                            index = 0;
                        }
                    }
                    buffer.isReady = true;
                    buffer.buffer.upload(buffer.builder.end());
                    data.vertexObject.vertices.rewind();
                }

                //Add this buffer to the list to render later.
                List<RenderData> renders = queuedRenders.get(renderType);
                if (renders == null) {
                    renders = new ArrayList<>();
                    queuedRenders.put(renderType, renders);
                }
                renders.add(new RenderData(stackEntry.pose(), buffer.buffer));
            } else {
                renderType = renderTypes.computeIfAbsent(typeID, k -> CustomRenderType.create("mts_entity", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, data.isTranslucent, CustomRenderType.createForObject(data).createCompositeState(false)));
                VertexConsumer buffer = renderBuffer.getBuffer(renderType);
                
                //Now populate the state we requested.
                int index = 0;
                while (data.vertexObject.vertices.hasRemaining()) {
                    //Need to parse these out first since our order differs.
                    float normalX = data.vertexObject.vertices.get();
                    float normalY = data.vertexObject.vertices.get();
                    float normalZ = data.vertexObject.vertices.get();
                    float texU = data.vertexObject.vertices.get();
                    float texV = data.vertexObject.vertices.get();
                    float posX = data.vertexObject.vertices.get();
                    float posY = data.vertexObject.vertices.get();
                    float posZ = data.vertexObject.vertices.get();

                    //Add the vertex.  Yes, we have to multiply this here on the CPU.  Yes, it's retarded because the GPU should be doing the matrix math.
                    //Blaze3d my ass, this is SLOWER than DisplayLists!
                    //We also need to add the 3rd vertex twice, since the buffer wants quads rather than tris.
                    //Yes, we have to render 25% more data because Mojang doesn't wanna move to tris like literally every other game.
                    //Yes, they're stupid.
                    do {
                        buffer.vertex(stackEntry.pose(), posX, posY, posZ);
                        buffer.color(data.color.red, data.color.green, data.color.blue, data.alpha);
                        buffer.uv(texU, texV);
                        buffer.overlayCoords(OverlayTexture.NO_OVERLAY);
                        buffer.uv2(data.worldLightValue);
                        buffer.normal(stackEntry.normal(), normalX, normalY, normalZ);
                        buffer.endVertex();
                    } while (++index == 3);
                    if (index == 4) {
                        index = 0;
                    }
                }
                //Rewind buffer for next read.
                data.vertexObject.vertices.rewind();
            }
        }
        matrixStack.popPose();
    }

    @Override
    public void deleteVertices(RenderableData data) {
        if (data.vertexObject.cacheVertices) {
	    	//Add to removed render list, we should only remove renders AFTER they are rendered.
	    	//This ensures they are un-bound, if the were bound prior.
            //Make sure we actually bound a buffer; just because the main system asks for a bound buffer,
    	    //doesn't mean we actually can give it one.  GUI models are one such case, as they don't work right
            //with bound buffers due to matrix differences.
            BufferData buffer = buffers.remove(data);
            if (buffer != null) {
                removedRenders.add(buffer);
            }
    	}
    }

    @Override
    public int getLightingAtPosition(Point3D position) {
        BlockPos pos = BlockPos.containing(position.x, position.y, position.z);
        return LightTexture.pack(Minecraft.getInstance().level.getBrightness(LightLayer.BLOCK, pos), Minecraft.getInstance().level.getBrightness(LightLayer.SKY, pos));
    }

    @Override
    public boolean shouldRenderBoundingBoxes() {
        return Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes();
    }

    @Override
    public boolean bindURLTexture(String textureURL, InputStream stream) {
        if (stream != null) {
            try {
                NativeImage image = NativeImage.read(NativeImage.Format.RGB, stream);
                DynamicTexture texture = new DynamicTexture(image);
                ResourceLocation textureLocation = Minecraft.getInstance().textureManager.register("mts-url", texture);
                onlineTextures.put(textureURL, textureLocation);
                return true;
            } catch (Exception e) {
                return false;
            }
        } else {
            onlineTextures.put(textureURL, null);
            return true;
        }
    }

    @Override
    public boolean bindURLGIF(String textureURL, ParsedGIF gif) {
        Map<GIFImageFrame, ResourceLocation> gifFrameIndexes = new HashMap<>();
        for (GIFImageFrame frame : gif.frames.values()) {
            try {
                BufferedImage frameBuffer = frame.getImage();
                ByteArrayOutputStream frameArrayStream = new ByteArrayOutputStream();
                ImageIO.write(frameBuffer, "gif", frameArrayStream);
                InputStream frameStream = new ByteArrayInputStream(frameArrayStream.toByteArray());

                NativeImage image = NativeImage.read(NativeImage.Format.RGB, frameStream);
                DynamicTexture texture = new DynamicTexture(image);
                ResourceLocation textureLocation = Minecraft.getInstance().textureManager.register("mts-gif", texture);
                gifFrameIndexes.put(frame, textureLocation);
            } catch (Exception e) {
                return false;
            }
        }
        animatedGIFs.put(textureURL, gif);
        animatedGIFFrames.put(gif, gifFrameIndexes);
        return true;
    }

    /**
     * Helper function to create a new texture state for the specified texture location.
     */
    private static RenderStateShard.TextureStateShard getTexture(String textureLocation) {
        //Check to make sure textures exist.  We delay creating because some mods screw up this stuff in boot.  Cray, looking at you buddy.
        if (MISSING_STATE == null) {
            MISSING_STATE = new RenderStateShard.TextureStateShard(new ResourceLocation("mts:textures/rendering/missing.png"), false, false);
            BLOCK_STATE = new RenderStateShard.TextureStateShard(InventoryMenu.BLOCK_ATLAS, false, false);
        }

        if (animatedGIFs.containsKey(textureLocation)) {
            //Special case for GIFs.
            ParsedGIF parsedGIF = animatedGIFs.get(textureLocation);
            return new RenderStateShard.TextureStateShard(animatedGIFFrames.get(parsedGIF).get(parsedGIF.getCurrentFrame()), false, false);
        } else if (onlineTextures.containsKey(textureLocation)) {
            //Online texture.
            ResourceLocation onlineTexture = onlineTextures.get(textureLocation);
            return onlineTexture != null ? new RenderStateShard.TextureStateShard(onlineTextures.get(textureLocation), false, false) : MISSING_STATE;
        } else if (textureLocation.equals(RenderableData.GLOBAL_TEXTURE_NAME)) {
            //Default texture.
            return BLOCK_STATE;
        } else {
            //If the texture has a colon, it's a short-hand form that needs to be converted.
            String formattedLocation = textureLocation;
            if (textureLocation.contains(":")) {
                formattedLocation = "/assets/" + textureLocation.replace(":", "/");
            }

            //Check if the texture exists.
            if (InterfaceManager.coreInterface.getPackResource(formattedLocation) != null) {
                //Convert the classpath-location to a domain-location path for MC.
                String domain = formattedLocation.substring("/assets/".length(), formattedLocation.indexOf("/", "/assets/".length()));
                String location = formattedLocation.substring("/assets/".length() + domain.length() + 1);
                return new RenderStateShard.TextureStateShard(new ResourceLocation(domain, location), false, false);
            } else {
                InterfaceManager.coreInterface.logError("Could not find texture: " + formattedLocation + " Reverting to fallback texture.");
                return MISSING_STATE;
            }
        }
    }

    /**
     * Renders the main GUI, setting up any transforms or operations as required.
     */
    @SuppressWarnings("deprecation")
    protected static void renderGUI(GuiGraphics graphics, int mouseX, int mouseY, int screenWidth, int screenHeight, float partialTicks, boolean updateGUIs) {
        //Get the buffer for GUI rendering.
        matrixStack = graphics.pose();
        matrixStack.pushPose();
        renderingGUI = true;
        MultiBufferSource.BufferSource guiBuffer = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        renderBuffer = guiBuffer;

        //Render GUIs, re-creating their components if needed.
        //Set Y-axis to inverted to have correct orientation.
        matrixStack.scale(1.0F, -1.0F, 1.0F);
        
        //Render main pass, then blended pass.
        int displayGUIIndex = 0;
        for (AGUIBase gui : AGUIBase.activeGUIs) {
            if (updateGUIs || gui.components.isEmpty()) {
                gui.setupComponentsInit(screenWidth, screenHeight);
            }
            matrixStack.pushPose();
            if (gui.capturesPlayer()) {
                //Translate in front of the main GUI components.
                matrixStack.translate(0, 0, 250);
            } else {
                //Translate far enough to render behind the chat window.
                matrixStack.translate(0, 0, -500 + 250 * displayGUIIndex++);
            }
            gui.render(mouseX, mouseY, false, partialTicks);
            guiBuffer.endBatch();
            //Not needed, since we can't draw to custom buffers with GUIs.
            //renderBuffers();

            //Need to use RenderSystem here, since this is a direct buffer.
            RenderSystem.enableBlend();
            gui.render(mouseX, mouseY, true, partialTicks);
            guiBuffer.endBatch();
            //renderBuffers();
            RenderSystem.disableBlend();
        
            //Render all stacks.  These have to be in the standard GUI reference frame or they won't render.
            matrixStack.scale(1.0F, -1.0F, 1.0F);

            for (GUIComponentItem component : stacksToRender) {
                //Double-check the stack is still present, it might have been un-set since this call.
                if ((WrapperItemStack) component.stackToRender != null) {
                    //Apply existing transform.
                    //Need to translate the z-offset to our value, which includes a -100 for the default added value.
                    //Blit starts at 200 though, plus 32 for the default item render.
//                    float zOffset = Minecraft.getInstance().getItemRenderer().blitOffset; TODO PORT?
//                    Minecraft.getInstance().getItemRenderer().blitOffset = (float) (200 + component.translation.z - 100);
                    if (component.scale != 1.0) {
                        //Need to use RenderSystem here, since we can't access the stack directly for rendering scaling.
                        matrixStack.pushPose();
                        matrixStack.scale(component.scale, component.scale, 1.0F);
                        graphics.renderItem(((WrapperItemStack) component.stackToRender).stack, (int) (component.translation.x / component.scale), (int) (-component.translation.y / component.scale) + 1);
                        matrixStack.popPose();
                    } else {
                        graphics.renderItem(((WrapperItemStack) component.stackToRender).stack, (int) component.translation.x, (int) -component.translation.y);
                    }
//                    Minecraft.getInstance().getItemRenderer().blitOffset = zOffset;
                }
            }
            stacksToRender.clear();
        
            matrixStack.popPose();
        }
        matrixStack.popPose();
        renderingGUI = false;
    }

    /**
     * Converts internal matrix to new external matrix.
     */
    protected static Matrix4f convertMatrix4f(TransformationMatrix transform) {
        Matrix4f matrix4f = new Matrix4f();
        matrix4f.m00((float) transform.m00);
        matrix4f.m01((float) transform.m01);
        matrix4f.m02((float) transform.m02);
        matrix4f.m03((float) transform.m03);
        matrix4f.m10((float) transform.m10);
        matrix4f.m11((float) transform.m11);
        matrix4f.m12((float) transform.m12);
        matrix4f.m13((float) transform.m13);
        matrix4f.m20((float) transform.m20);
        matrix4f.m21((float) transform.m21);
        matrix4f.m22((float) transform.m22);
        matrix4f.m23((float) transform.m23);
        matrix4f.m30((float) transform.m30);
        matrix4f.m31((float) transform.m31);
        matrix4f.m32((float) transform.m32);
        matrix4f.m33((float) transform.m33);
        return matrix4f;
    }

    /**
     * Event that's called to setup the client.  We register our render wrapper
     * class here.
     */
    @SubscribeEvent
    public static void registerRenderer(EntityRenderersEvent.RegisterRenderers event) {
        //Register the global entity rendering class.
        event.registerEntityRenderer(BuilderEntityRenderForwarder.E_TYPE4.get(), manager -> new EntityRenderer<>(manager) {
            @Override
            public ResourceLocation getTextureLocation(BuilderEntityRenderForwarder builder) {
                return null;
            }

            @Override
            public boolean shouldRender(BuilderEntityRenderForwarder builder, Frustum camera, double camX, double camY, double camZ) {
                //Always render the forwarder, no matter where the camera is.
                return true;
            }

            @Override
            public void render(BuilderEntityRenderForwarder builder, float entityYaw, float partialTicks, PoseStack stack, MultiBufferSource buffer, int packedLight) {
                if (builder.playerFollowing == Minecraft.getInstance().player) {
                    //Set camera offset point for later.
                    renderCameraOffset.set(Mth.lerp(partialTicks, builder.xOld, builder.getX()), Mth.lerp(partialTicks, builder.yOld, builder.getY()), Mth.lerp(partialTicks, builder.zOld, builder.getZ()));

                    //Set the stack variables and render.
                    doRenderCall(stack, buffer, false, partialTicks);
                }
            }
        });
        //Register blank classes for the other builders.
        //If we don't, the game crashes when trying to render them.
        event.registerEntityRenderer(BuilderEntityExisting.E_TYPE2.get(), BlankRender::new);
        event.registerEntityRenderer(BuilderEntityLinkedSeat.E_TYPE3.get(), BlankRender::new);
    }

    public static void doRenderCall(PoseStack stack, MultiBufferSource buffer, boolean blendingEnabled, float partialTicks) {
        matrixStack = stack;
        renderBuffer = buffer;
        AWrapperWorld world = InterfaceManager.clientInterface.getClientWorld();
        ConcurrentLinkedQueue<AEntityC_Renderable> allEntities = world.renderableEntities;
        if (allEntities != null) {
            world.beginProfiling("MTSRendering_Setup", true);

            //NOTE: this operation occurs on a ConcurrentLinkedQueue.  Therefore, updates will
            //not occur one after another.  Sanitize your inputs!
            for (AEntityC_Renderable entity : allEntities) {
                matrixStack.pushPose();
                matrixStack.translate(entity.position.x - renderCameraOffset.x, entity.position.y - renderCameraOffset.y, entity.position.z - renderCameraOffset.z);
                entity.render(blendingEnabled, partialTicks);
                matrixStack.popPose();
            }

            //Need to tell the immediate buffer  it's done rendering, else it'll hold onto the data and crash other systems.
            if (renderBuffer instanceof MultiBufferSource.BufferSource source) {
                source.endBatch();
            }

            //Now do the actual render.
            world.beginProfiling("MTSRendering_Execution", false);
            renderBuffers();
            world.endProfiling();
        }
    }

    private static void renderBuffers() {
        //Call order is CRITICAL and will lead to random JME faults with no stacktrace if modified!
        for (Entry<RenderType, List<RenderData>> renderEntry : queuedRenders.entrySet()) {
            RenderType renderType = renderEntry.getKey();
            List<RenderData> datas = renderEntry.getValue();
            if (!datas.isEmpty()) {
                renderType.setupRenderState();
                for (RenderData data : datas) {
                    data.buffer.bind();
                    renderType.format().setupBufferState();
                    data.buffer.drawWithShader(data.matrix, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
                }
                renderType.format().clearBufferState();
                renderType.clearRenderState();
                datas.clear();
            }
        }
        VertexBuffer.unbind();
        if (!removedRenders.isEmpty()) {
            removedRenders.forEach(render -> render.buffer.close());
            removedRenders.clear();
        }
    }
    

    /** Blank render class used to bypass rendering for all other builders.**/
    private static class BlankRender<T extends ABuilderEntityBase> extends EntityRenderer<T> {

        protected BlankRender(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public ResourceLocation getTextureLocation(T pEntity) {
            return null;
        }

        @Override
        public boolean shouldRender(T entity, Frustum frustum, double camX, double camY, double camZ) {
            return false;
        }
    }

    private static class CustomRenderType extends RenderType {
        private CustomRenderType(String name, VertexFormat fmt, VertexFormat.Mode glMode, int size, boolean doCrumbling, boolean depthSorting, Runnable onEnable, Runnable onDisable) {
            super(name, fmt, glMode, size, doCrumbling, depthSorting, onEnable, onDisable);
            throw new IllegalStateException("This class must not be instantiated, this is only here to gain access to the rendering constants.");
        }

        private static CompositeState.CompositeStateBuilder createForObject(RenderableData data) {
            //Create the state builder.  Changed states are active, default states are commented to save processing but still show the state.
            RenderType.CompositeState.CompositeStateBuilder stateBuilder = RenderType.CompositeState.builder();

            stateBuilder.setTextureState(getTexture(data.texture));
            //Transparency is also blend function, so we need to override that with a custom one if we are doing bright blending.
            stateBuilder.setTransparencyState(data.enableBrightBlending ? BRIGHTNESS_TRANSPARENCY : (data.isTranslucent ? PROPER_TRANSLUCENT_TRANSPARENCY : RenderType.NO_TRANSPARENCY));
            //Diffuse lighting is the ambient lighting that auto-shades models.
//            stateBuilder.setDiffuseLightingState(data.lightingMode.disableTextureShadows ? NO_DIFFUSE_LIGHTING : DIFFUSE_LIGHTING);
            //Always smooth shading.
//            stateBuilder.setShadeModelState(SMOOTH_SHADE);
            //Use default alpha to remove alpha fragments in cut-out textures.
//            stateBuilder.setAlphaState(DEFAULT_ALPHA);
            //Depth test is fine, it ensures translucent things don't render in front of everything.
            //stateBuilder.setDepthTestState(LEQUAL_DEPTH_TEST);
            //Cull is fine.  Not sure what this does, actually...
            //stateBuilder.setCullState(NO_CULL);
            //Lightmap is on unless we are bright.
            stateBuilder.setLightmapState(data.lightingMode.disableWorldLighting ? NO_LIGHTMAP : LIGHTMAP);
            //No overlays ever.
            stateBuilder.setOverlayState(NO_OVERLAY);
            //No fog.
            //stateBuilder.setFogState(NO_FOG);
            //No layering.
            //stateBuilder.setLayeringState(NO_LAYERING);
            //Target is always main.
            //stateBuilder.setOutputState(MAIN_TARGET);
            //Default texture is fine.
            //stateBuilder.setTexturingState(DEFAULT_TEXTURING);
            //Not sure what this does, but it should be fine as-is?
            //stateBuilder.setWriteMaskState(COLOR_DEPTH_WRITE);
            //Don't need to poke lines, there's only one state.
            //stateBuilder.setLineState(DEFAULT_LINE);

            //Return.
            return stateBuilder;
        }
    }

    private static class RenderData {
        private final Matrix4f matrix;
        private final VertexBuffer buffer;

        private RenderData(Matrix4f matrix, VertexBuffer buffer) {
            this.matrix = new Matrix4f(matrix);
            this.buffer = buffer;
        }
    }

    private static class BufferData {
        final BufferBuilder builder;
        final VertexBuffer buffer;
        boolean isReady;

        private BufferData() {
            builder = null;
            buffer = null;
        }

        private BufferData(RenderType type, RenderableData data) {
            int vertices = data.vertexObject.vertices.limit() / 8;
            //Convert verts to faces, then back to quad-verts for MC rendering.
            //Add one face extra, since MC will want to increase the buffer if sees it can't handle another vert.
            vertices = ((vertices / 3) + 1) * 4;
            this.builder = new BufferBuilder(type.format().getIntegerSize() * vertices);
            this.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
    }

    /**
     * Proper translucent transparency.  MC's one has the wrong blending function, and
     * we need to disable writing to the depth buffer to prevent culling of other translucent
     * objects, since we don't sort them and we could render behind an already-rendered transparent
     * fragment.  Say if we have two headlight flares.
     */
    private static final RenderStateShard.TransparencyStateShard PROPER_TRANSLUCENT_TRANSPARENCY = new RenderStateShard.TransparencyStateShard("proper_translucent_transparency", () -> {
        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }, () -> {
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
    });

    /**
     * Brightness transparency.  Does a special blending operation to make things behind
     * the object brighter based on the object's alpha value.  More alpha means more bright.
     */
    private static final RenderStateShard.TransparencyStateShard BRIGHTNESS_TRANSPARENCY = new RenderStateShard.TransparencyStateShard("brightness_transparency", () -> {
        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.SRC_ALPHA);
    }, () -> {
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
    });
}
