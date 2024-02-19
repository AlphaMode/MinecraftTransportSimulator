package minecrafttransportsimulator.entities.components;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import minecrafttransportsimulator.baseclasses.AnimationSwitchbox;
import minecrafttransportsimulator.baseclasses.ColorRGB;
import minecrafttransportsimulator.baseclasses.ComputedVariable;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.TransformationMatrix;
import minecrafttransportsimulator.blocks.components.ABlockBase.BlockMaterial;
import minecrafttransportsimulator.entities.instances.APart;
import minecrafttransportsimulator.entities.instances.EntityParticle;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.items.components.AItemPack;
import minecrafttransportsimulator.items.components.AItemSubTyped;
import minecrafttransportsimulator.jsondefs.AJSONMultiModelProvider;
import minecrafttransportsimulator.jsondefs.JSONAnimatedObject;
import minecrafttransportsimulator.jsondefs.JSONAnimationDefinition;
import minecrafttransportsimulator.jsondefs.JSONCameraObject;
import minecrafttransportsimulator.jsondefs.JSONLight;
import minecrafttransportsimulator.jsondefs.JSONParticle;
import minecrafttransportsimulator.jsondefs.JSONRendering.ModelType;
import minecrafttransportsimulator.jsondefs.JSONSound;
import minecrafttransportsimulator.jsondefs.JSONSubDefinition;
import minecrafttransportsimulator.jsondefs.JSONText;
import minecrafttransportsimulator.jsondefs.JSONVariableModifier;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperItemStack;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packets.instances.PacketEntityVariableIncrement;
import minecrafttransportsimulator.packets.instances.PacketEntityVariableSet;
import minecrafttransportsimulator.packets.instances.PacketEntityVariableToggle;
import minecrafttransportsimulator.packloading.PackParser;
import minecrafttransportsimulator.rendering.AModelParser;
import minecrafttransportsimulator.rendering.DurationDelayClock;
import minecrafttransportsimulator.rendering.RenderText;
import minecrafttransportsimulator.rendering.RenderableModelObject;
import minecrafttransportsimulator.sound.SoundInstance;
import minecrafttransportsimulator.systems.CameraSystem;
import minecrafttransportsimulator.systems.ConfigSystem;

/**
 * Base class for entities that are defined via JSON definitions and can be modeled in 3D.
 * This level adds various method for said definitions, which include rendering functions.
 *
 * @author don_bruce
 */
public abstract class AEntityD_Definable<JSONDefinition extends AJSONMultiModelProvider> extends AEntityC_Renderable {
    /**
     * The pack definition for this entity.  May contain extra sections if the super-classes
     * have them in their respective JSONs.
     */
    public final JSONDefinition definition;

    /**
     * The current sub-definition for this entity.
     */
    public JSONSubDefinition subDefinition;

    /**
     * Variable for saving animation definition initialized state.  Is set true on the first tick, but may be set false afterwards to re-initialize animation definitions.
     */
    public boolean animationsInitialized;

    /**
     * Map containing text lines for saved text provided by this entity.
     **/
    public final LinkedHashMap<JSONText, String> text = new LinkedHashMap<>();

    /**
     * Map of computed variables.  These are computed using logic and need to be re-created on core entity makeup changes.
     **/
    private final Map<String, ComputedVariable> computedVariables = new HashMap<>();
    protected final ComputedVariable ZERO_VARIABLE = new ComputedVariable(this, "#0", partialTicks -> 0, false);
    protected final ComputedVariable ONE_VARIABLE = new ComputedVariable(this, "#1", partialTicks -> 1, false);

    private final List<JSONSound> allSoundDefs = new ArrayList<>();
    private final Map<JSONSound, AnimationSwitchbox> soundActiveSwitchboxes = new HashMap<>();
    private final Set<JSONSound> soundDefFalseLastCheck = new HashSet<>();
    private final Map<JSONSound, SoundSwitchbox> soundVolumeSwitchboxes = new HashMap<>();
    private final Map<JSONSound, SoundSwitchbox> soundPitchSwitchboxes = new HashMap<>();
    private final Map<JSONLight, LightSwitchbox> lightBrightnessSwitchboxes = new HashMap<>();
    private final Map<JSONParticle, AnimationSwitchbox> particleActiveSwitchboxes = new HashMap<>();
    private final Map<JSONParticle, AnimationSwitchbox> particleSpawningSwitchboxes = new HashMap<>();
    private final Map<JSONParticle, Long> lastTickParticleSpawned = new HashMap<>();
    private final Map<JSONParticle, Point3D> lastPositionParticleSpawned = new HashMap<>();
    private final Map<JSONVariableModifier, VariableModifierSwitchbox> variableModiferSwitchboxes = new LinkedHashMap<>();
    private long lastTickParticlesSpawned;
    private float lastPartialTickParticlesSpawned;

    /**
     * Maps animated (model) object names to their JSON bits for this entity.  Used for model lookups as the same model might be used on multiple JSONs,
     * and iterating through the entire rendering section of the JSON is time-consuming.
     **/
    public final Map<String, JSONAnimatedObject> animatedObjectDefinitions = new HashMap<>();

    /**
     * Maps animated (model) object names to their switchboxes.  This is created from the JSON definition as each entity has their own switchbox.
     **/
    public final Map<String, AnimationSwitchbox> animatedObjectSwitchboxes = new HashMap<>();

    /**
     * Maps cameras to their respective switchboxes.
     **/
    public final Map<JSONCameraObject, AnimationSwitchbox> cameraSwitchboxes = new LinkedHashMap<>();

    /**
     * Maps light definitions to their current brightness.  This is updated every frame prior to rendering.
     **/
    public final Map<JSONLight, Float> lightBrightnessValues = new HashMap<>();

    /**
     * Maps light definitions to their current color.  This is updated every frame prior to rendering.
     **/
    public final Map<JSONLight, ColorRGB> lightColorValues = new HashMap<>();

    /**
     * Maps light (model) object names to their definitions.  This is created from the JSON definition to prevent the need to do loops.
     **/
    public final Map<String, JSONLight> lightObjectDefinitions = new HashMap<>();

    /**
     * Object lists for models parsed in for this class.  Maps are keyed by the model name.
     **/
    protected static final Map<String, List<RenderableModelObject>> objectLists = new HashMap<>();

    /**
     * Cached item to prevent pack lookups each item request.  May not be used if this is extended for other mods.
     **/
    public AItemPack<JSONDefinition> cachedItem;

    //Radar lists.  Only updated once a tick.  Created when first requested via animations.
    public final List<EntityVehicleF_Physics> aircraftOnRadar = new ArrayList<>();
    public final List<EntityVehicleF_Physics> groundersOnRadar = new ArrayList<>();
    private final Comparator<AEntityB_Existing> entityComparator = new Comparator<AEntityB_Existing>() {
        @Override
        public int compare(AEntityB_Existing o1, AEntityB_Existing o2) {
            return position.isFirstCloserThanSecond(o1.position, o2.position) ? -1 : 1;
        }

    };;

    /**
     * Constructor for synced entities
     **/
    public AEntityD_Definable(AWrapperWorld world, IWrapperPlayer placingPlayer, IWrapperNBT data) {
        super(world, placingPlayer, data);
        String subName = data.getString("subName");
        AItemSubTyped<JSONDefinition> item = PackParser.getItem(data.getString("packID"), data.getString("systemName"), subName);
        this.definition = item != null ? item.definition : generateDefaultDefinition();
        updateSubDefinition(subName);

        //Load text.
        if (definition.rendering != null && definition.rendering.textObjects != null) {
            for (int i = 0; i < definition.rendering.textObjects.size(); ++i) {
                JSONText textDef = definition.rendering.textObjects.get(i);
                text.put(textDef, newlyCreated ? textDef.defaultText : data.getString("textLine" + i));
            }
        }

        //Load variables.
        for (String variableName : data.getStrings("variables")) {
            getVariable(variableName).setTo(data.getDouble(variableName), false);
        }
        if (newlyCreated && definition.initialVariables != null) {
            for (String variable : definition.initialVariables) {
                getVariable(variable).setTo(1D, false);
            }
        }
    }

    /**
     * Constructor for un-synced entities.  Allows for specification of position/motion/angles.
     **/
    public AEntityD_Definable(AWrapperWorld world, Point3D position, Point3D motion, Point3D angles, AItemSubTyped<JSONDefinition> creatingItem) {
        super(world, position, motion, angles);
        this.definition = creatingItem.definition;
        updateSubDefinition(creatingItem.subDefinition.subName);
    }

    @Override
    public String toString() {
        return definition.packID + ":" + definition.systemName + subDefinition.subName;
    }

    @Override
    public void update() {
        super.update();
        world.beginProfiling("EntityD_Level", true);
        if (!animationsInitialized) {
            initializeAnimations();
            animationsInitialized = true;
        }
        if (world.isClient()) {
            spawnParticles(0);
        }
        //Only update radar once a second, and only if we requested it via variables.
        if (definition.general.radarRange > 0 && ticksExisted % 20 == 0) {
            Collection<EntityVehicleF_Physics> allVehicles = world.getEntitiesOfType(EntityVehicleF_Physics.class);
            aircraftOnRadar.clear();
            groundersOnRadar.clear();
            Point3D searchVector = new Point3D();
            Point3D LOSVector = new Point3D();
            for (EntityVehicleF_Physics vehicle : allVehicles) {
                searchVector.set(0, 0, definition.general.radarRange).rotate(orientation);
                LOSVector.set(vehicle.position).subtract(position).normalize();
                double coneAngle = definition.general.radarWidth;
                double angle = Math.abs(Math.toDegrees(Math.acos(searchVector.normalize().dotProduct(LOSVector, false))));
                if (!vehicle.outOfHealth && vehicle != this && (angle < coneAngle && vehicle.position.isDistanceToCloserThan(position, definition.general.radarRange))) {
                    if (vehicle.definition.motorized.isAircraft) {
                        aircraftOnRadar.add(vehicle);
                    } else {
                        groundersOnRadar.add(vehicle);
                    }
                    if (!vehicle.radarsTracking.contains(this)) {
                        vehicle.radarsTracking.add(this);
                    }
                }
            }
            aircraftOnRadar.sort(entityComparator);
            groundersOnRadar.sort(entityComparator);
        }
        world.endProfiling();
    }

    /**
     * Called to perform supplemental update logic on this entity.  This is called after the main {@link #update()}
     * loop, and is used to do updates that require the new state to be ready.  At this point, all "prior" values
     * and current values will be set to their current states.
     */
    public void doPostUpdateLogic() {
        //Update value-based text.  Only do this on clients as servers won't render this text.
        if (world.isClient() && !text.isEmpty()) {
            for (Entry<JSONText, String> textEntry : text.entrySet()) {
                JSONText textDef = textEntry.getKey();
                if (textDef.variableName != null) {
                    textEntry.setValue(getAnimatedTextVariableValue(textDef, 0));
                }
            }
        }
    }

    /**
     * Updates the subDefinition to match the one passed-in.  Used for paint guns to change the sub-def,
     * but should also be called on initial setting to ensure other state-based operations are performed.
     */
    public void updateSubDefinition(String newSubDefName) {
        for (JSONSubDefinition testSubDef : definition.definitions) {
            if (testSubDef.subName.equals(newSubDefName)) {
                //Remove existing constants, if we have them, then add them, if we have them.
                if (subDefinition != null && subDefinition.constants != null) {
                    testSubDef.constants.forEach(constant -> getVariable(constant).setTo(0,  false));
                }
                if (testSubDef.constants != null) {
                    testSubDef.constants.forEach(constant -> getVariable(constant).setTo(1,  false));
                }
                subDefinition = testSubDef;
                cachedItem = PackParser.getItem(definition.packID, definition.systemName, subDefinition.subName);
                return;
            }
        }
        throw new IllegalArgumentException("Tried to get the definition for an object of subName:" + newSubDefName + ".  But that isn't a valid subName for the object:" + definition.packID + ":" + definition.systemName + ".  Report this to the pack author as this is a missing JSON component!");
    }

    /**
     * Called the first update tick after this entity is first constructed, and when the definition on it is reset via hotloading.
     * This should create (and reset) all JSON clocks and other static objects that depend on the definition.
     */
    protected void initializeAnimations() {
        //Update subdef, in case this was modified.
        updateSubDefinition(subDefinition.subName);

        if (definition.rendering != null && definition.rendering.sounds != null) {
            for (SoundInstance sound : sounds) {
                sound.stopSound = true;
            }

            allSoundDefs.clear();
            soundActiveSwitchboxes.clear();
            soundVolumeSwitchboxes.clear();
            soundPitchSwitchboxes.clear();
            for (JSONSound soundDef : definition.rendering.sounds) {
                allSoundDefs.add(soundDef);
                soundActiveSwitchboxes.put(soundDef, new AnimationSwitchbox(this, soundDef.activeAnimations, null));

                if (soundDef.volumeAnimations != null) {
                    soundVolumeSwitchboxes.put(soundDef, new SoundSwitchbox(this, soundDef.volumeAnimations));
                }

                if (soundDef.pitchAnimations != null) {
                    soundPitchSwitchboxes.put(soundDef, new SoundSwitchbox(this, soundDef.pitchAnimations));
                }
            }
        }

        if (definition.rendering != null && definition.rendering.lightObjects != null) {
            lightBrightnessSwitchboxes.clear();
            lightBrightnessValues.clear();
            lightColorValues.clear();
            lightObjectDefinitions.clear();
            for (JSONLight lightDef : definition.rendering.lightObjects) {
                lightObjectDefinitions.put(lightDef.objectName, lightDef);
                if (lightDef.brightnessAnimations != null) {
                    lightBrightnessSwitchboxes.put(lightDef, new LightSwitchbox(this, lightDef.brightnessAnimations));
                }
                lightBrightnessValues.put(lightDef, 0F);
                lightColorValues.put(lightDef, new ColorRGB());
            }
        }

        if (definition.rendering != null && definition.rendering.particles != null) {
            particleActiveSwitchboxes.clear();
            particleSpawningSwitchboxes.clear();
            for (JSONParticle particleDef : definition.rendering.particles) {
                particleActiveSwitchboxes.put(particleDef, new AnimationSwitchbox(this, particleDef.activeAnimations, null));
                if (particleDef.spawningAnimations != null) {
                    particleSpawningSwitchboxes.put(particleDef, new AnimationSwitchbox(this, particleDef.spawningAnimations, null));
                }
                lastTickParticleSpawned.put(particleDef, ticksExisted);
            }
        }

        if (definition.rendering != null && definition.rendering.animatedObjects != null) {
            animatedObjectDefinitions.clear();
            animatedObjectSwitchboxes.clear();
            for (JSONAnimatedObject animatedDef : definition.rendering.animatedObjects) {
                animatedObjectDefinitions.put(animatedDef.objectName, animatedDef);
                if (animatedDef.animations != null) {
                    animatedObjectSwitchboxes.put(animatedDef.objectName, new AnimationSwitchbox(this, animatedDef.animations, animatedDef.applyAfter));
                }
            }
        }

        if (definition.rendering != null && definition.rendering.cameraObjects != null) {
            cameraSwitchboxes.clear();
            for (JSONCameraObject cameraDef : definition.rendering.cameraObjects) {
                if (cameraDef.animations != null) {
                    cameraSwitchboxes.put(cameraDef, new AnimationSwitchbox(this, cameraDef.animations, null));
                }
            }
        }

        //Store text data if we have it, then reset it.
        List<String> oldTextValues = new ArrayList<>(text.values());
        text.clear();
        if (definition.rendering != null && definition.rendering.textObjects != null) {
            for (int i = 0; i < definition.rendering.textObjects.size(); ++i) {
                if (i < oldTextValues.size()) {
                    text.put(definition.rendering.textObjects.get(i), oldTextValues.get(i));
                } else {
                    text.put(definition.rendering.textObjects.get(i), definition.rendering.textObjects.get(i).defaultText);
                }
            }
        }

        //Add variable modifiers.
        if (definition.variableModifiers != null) {
            variableModiferSwitchboxes.clear();
            for (JSONVariableModifier modifier : definition.variableModifiers) {
                if (modifier.animations != null) {
                    variableModiferSwitchboxes.put(modifier, new VariableModifierSwitchbox(this, modifier.animations));
                }
            }

        }

        //Add constants.
        if (definition.constantValues != null) {
            definition.constantValues.forEach((constantKey, constantValue) -> getVariable(constantKey).setTo(constantValue, false));
        }
    }

    @Override
    public void remove() {
        if (isValid) {
            super.remove();
            //Clear radars.
            aircraftOnRadar.clear();
            groundersOnRadar.clear();
        }
    }

    /**
     * Returns the entity as an item stack.  This may or may not have NBT defined on it depending on implementation.
     * The default is to just return our item without data, but this is not assured if this function is overridden.
     */
    public IWrapperItemStack getStack() {
        return cachedItem.getNewStack(null);
    }

    /**
     * Generates the default definition for this entity. Used if the item can't be found.
     * This allows for internally-definable entities.
     */
    public JSONDefinition generateDefaultDefinition() {
        throw new IllegalArgumentException("Was asked to auto-generate a definition on an entity with one not defined.  This is NOT allowed.  The entity must be missing its item.  Perhaps a pack was removed with this entity still in the world?");
    }

    /**
     * Returns the texture that should be bound to this entity for the passed-in object from the model.
     * This may change between render passes, but only ONE texture may be used for any given object render
     * operation!  By default this returns the JSON-defined texture, though the model parser may override this.
     */
    public String getTexture() {
        return definition.getTextureLocation(subDefinition);
    }

    /**
     * Returns true if this entity is lit up, and text should be rendered lit.
     * Note that what text is lit is dependent on the text's definition, so just
     * because text could be lit, does not mean it will be lit if the pack
     * author doesn't want it to be.
     */
    public boolean renderTextLit() {
        return ConfigSystem.client.renderingSettings.brightLights.value;
    }

    /**
     * Returns the color for the text on this entity.  This takes into account the passed-in index.
     * If a color exists at the index, it is returned.  If not, then the passed-in color is returned.
     */
    public ColorRGB getTextColor(int index, ColorRGB defaultColor) {
        if (index != 0) {
            if (subDefinition.secondaryTextColors != null && subDefinition.secondaryTextColors.size() >= index) {
                return subDefinition.secondaryTextColors.get(index - 1);
            } else {
                return defaultColor;
            }
        } else {
            return defaultColor;
        }
    }

    /**
     * Called to update the text on this entity.  Variable is a map with the key as a field name,
     * and the value as the value of that field.  Normally just sets the text to the passed-in values,
     * but may do supplemental logic if desired.
     */
    public void updateText(LinkedHashMap<String, String> textLines) {
        for (Entry<JSONText, String> textEntry : text.entrySet()) {
            String newLine = textLines.get(textEntry.getKey().fieldName);
            if (newLine != null) {
                textEntry.setValue(newLine);
            }
        }
    }

    /**
     * Spawns particles for this entity.  This is called after every render frame, so
     * watch your methods to prevent spam.  Note that this method is not called if the
     * game is paused, as particles are assumed to only be spawned during normal entity
     * updates.
     */
    public void spawnParticles(float partialTicks) {
        //Check all particle defs and update the existing particles accordingly.
        for (Entry<JSONParticle, AnimationSwitchbox> particleEntry : particleActiveSwitchboxes.entrySet()) {
            //Check if the particle should be spawned this tick.
            JSONParticle particleDef = particleEntry.getKey();
            AnimationSwitchbox switchbox = particleEntry.getValue();
            boolean shouldParticleSpawn = switchbox.runSwitchbox(partialTicks, false);

            //Make the particle spawn if able.
            if (shouldParticleSpawn) {
                if (particleDef.distance > 0) {
                    Point3D lastParticlePosition = lastPositionParticleSpawned.get(particleDef);
                    if (lastParticlePosition == null) {
                        lastParticlePosition = position.copy();
                        lastPositionParticleSpawned.put(particleDef, lastParticlePosition);
                        continue;//First tick we are active, checks are assured to fail.
                    }
                    while (!lastParticlePosition.isDistanceToCloserThan(position, particleDef.distance)) {
                        double distanceFactor = particleDef.distance / position.distanceTo(lastParticlePosition);
                        Point3D spawningPosition = lastParticlePosition.copy().interpolate(position, distanceFactor);
                        for (int i = 0; i < particleDef.quantity; ++i) {
                            AnimationSwitchbox spawningSwitchbox = particleSpawningSwitchboxes.get(particleDef);
                            if (spawningSwitchbox != null) {
                                spawningSwitchbox.runSwitchbox(partialTicks, false);
                            }
                            world.addEntity(new EntityParticle(this, particleDef, spawningPosition, spawningSwitchbox));
                        }
                        lastParticlePosition.set(spawningPosition);
                    }
                } else {
                    //If we've never spawned the particle, or have waited a whole tick for constant-spawners, spawn one now.
                    Long particleSpawnTime = lastTickParticleSpawned.get(particleDef);
                    if (particleSpawnTime == null || (particleDef.spawnEveryTick && ticksExisted > particleSpawnTime)) {
                        for (int i = 0; i < particleDef.quantity; ++i) {
                            AnimationSwitchbox spawningSwitchbox = particleSpawningSwitchboxes.get(particleDef);
                            if (spawningSwitchbox != null) {
                                spawningSwitchbox.runSwitchbox(partialTicks, false);
                            }
                            world.addEntity(new EntityParticle(this, particleDef, position, spawningSwitchbox));
                        }
                        lastTickParticleSpawned.put(particleDef, ticksExisted);
                    }
                }
            } else {
                lastTickParticleSpawned.remove(particleDef);
                lastPositionParticleSpawned.remove(particleDef);
            }
        }
    }

    /**
     * Updates the light brightness values contained in {@link #lightBrightnessValues}.  This is done
     * every frame for all light definitions to prevent excess calculations caused by multiple
     * lighting components for the light re-calculating the same value multiple times a frame.
     * An example of this is a light with a bean and flare component.
     */
    public void updateLightBrightness(float partialTicks) {
        if (definition.rendering != null && definition.rendering.lightObjects != null) {
            for (JSONLight lightDef : definition.rendering.lightObjects) {
                float lightBrightness = 1;
                ColorRGB lightColor = null;
                LightSwitchbox switchbox = lightBrightnessSwitchboxes.get(lightDef);
                if (switchbox != null) {
                    if (!switchbox.runLight(partialTicks)) {
                        lightBrightness = 0;
                    } else if (switchbox.definedBrightness) {
                        lightBrightness = switchbox.brightness;
                    }
                    if (lightBrightness < 0) {
                        lightBrightness = 0;
                    }
                    lightBrightnessValues.put(lightDef, lightBrightness);
                    lightColor = switchbox.color;
                } else {
                    lightBrightnessValues.put(lightDef, 1.0F);
                }

                //Set color level.
                if (lightColor != null) {
                    lightColorValues.put(lightDef, lightColor);
                } else if (lightDef.color != null) {
                    lightColorValues.put(lightDef, lightDef.color);
                } else {
                    lightColorValues.put(lightDef, ColorRGB.WHITE);
                }
            }
        }
    }

    /**
     * Custom light switchbox class.
     */
    private static class LightSwitchbox extends AnimationSwitchbox {
        private boolean definedBrightness = false;
        private float brightness = 0;
        private ColorRGB color = null;

        private LightSwitchbox(AEntityD_Definable<?> entity, List<JSONAnimationDefinition> animations) {
            super(entity, animations, null);
        }

        public boolean runLight(float partialTicks) {
            definedBrightness = false;
            brightness = 0;
            color = null;
            return runSwitchbox(partialTicks, true);
        }

        @Override
        public void runTranslation(DurationDelayClock clock, float partialTicks) {
            definedBrightness = true;
            if (clock.animation.axis.x != 0) {
                brightness *= entity.getAnimatedVariableValue(clock, clock.animation.axis.x, partialTicks);
            } else if (clock.animation.axis.y != 0) {
                brightness += entity.getAnimatedVariableValue(clock, clock.animation.axis.y, partialTicks);
            } else {
                brightness = (float) (entity.getAnimatedVariableValue(clock, clock.animation.axis.z, partialTicks));
            }
        }

        @Override
        public void runRotation(DurationDelayClock clock, float partialTicks) {
            double colorFactor = entity.getAnimatedVariableValue(clock, 1.0, -clock.animation.offset, partialTicks);
            double colorX;
            double colorY;
            double colorZ;
            if (color == null) {
                colorX = clock.animation.axis.x * colorFactor + clock.animation.offset;
                colorY = clock.animation.axis.y * colorFactor + clock.animation.offset;
                colorZ = clock.animation.axis.z * colorFactor + clock.animation.offset;
            } else {
                colorX = clock.animation.axis.x * colorFactor + clock.animation.offset + color.red;
                colorY = clock.animation.axis.y * colorFactor + clock.animation.offset + color.green;
                colorZ = clock.animation.axis.z * colorFactor + clock.animation.offset + color.blue;
            }
            if (colorX < 0)
                colorX = 0;
            if (colorY < 0)
                colorY = 0;
            if (colorZ < 0)
                colorZ = 0;
            if (colorX > 1)
                colorX = 1;
            if (colorY > 1)
                colorY = 1;
            if (colorZ > 1)
                colorZ = 1;
            color = new ColorRGB((float) colorX, (float) colorY, (float) colorZ, false);
        }
    }

    @Override
    public void updateSounds(float partialTicks) {
        super.updateSounds(partialTicks);
        //Check all sound defs and update the existing sounds accordingly.
        for (JSONSound soundDef : allSoundDefs) {
            if (soundDef.canPlayOnPartialTicks ^ partialTicks == 0) {
                //Check if the sound should be playing before we try to update state.
            	//First check the animated conditionals, since those drive on/off state.
            	AnimationSwitchbox activeSwitchbox = soundActiveSwitchboxes.get(soundDef);
                boolean shouldSoundStartPlaying = activeSwitchbox.runSwitchbox(partialTicks, true);
                    
                //If we aren't a looping or repeating sound, check if we were true last check.
                //If we were, then we shouldn't play, even if all states are true, as we'd start another sound.
                if (!soundDef.looping && !soundDef.forceSound) {
                    if (shouldSoundStartPlaying) {
                        if (!soundDefFalseLastCheck.remove(soundDef)) {
                            shouldSoundStartPlaying = false;
                        }
                    } else {
                        soundDefFalseLastCheck.add(soundDef);
                    }
                }
                
                //Now that we know if we are enabled, check if the player has the right viewpoint.
                AEntityB_Existing entityRiding = null;
                boolean playerRidingThisEntity = false;
                boolean hasOpenTop = false;
                if(shouldSoundStartPlaying) {
		            entityRiding = InterfaceManager.clientInterface.getClientPlayer().getEntityRiding();
		            AEntityF_Multipart<?> multipartTopLevel = entityRiding instanceof APart ? ((APart) entityRiding).masterEntity : (entityRiding instanceof AEntityF_Multipart ? (AEntityF_Multipart<?>) entityRiding : null);
		            playerRidingThisEntity = multipartTopLevel != null && (multipartTopLevel.equals(this) || multipartTopLevel.allParts.contains(this));
		            hasOpenTop = multipartTopLevel instanceof EntityVehicleF_Physics && ((EntityVehicleF_Physics) multipartTopLevel).definition.motorized.hasOpenTop;
		            shouldSoundStartPlaying = hasOpenTop ? true : (playerRidingThisEntity && InterfaceManager.clientInterface.inFirstPerson() && !CameraSystem.runningCustomCameras) ? !soundDef.isExterior : !soundDef.isInterior;
                }

                //Next, check the distance.
                double distance = 0;
                if(shouldSoundStartPlaying) {
	                Point3D soundPos = soundDef.pos != null ? soundDef.pos.copy().rotate(orientation).add(position) : position;
	                if (shouldSoundStartPlaying) {
	                    distance = soundPos.distanceTo(InterfaceManager.clientInterface.getClientPlayer().getPosition());
	                    if (soundDef.maxDistance != soundDef.minDistance) {
	                        shouldSoundStartPlaying = distance < soundDef.maxDistance && distance > soundDef.minDistance;
	                    } else {
	                        shouldSoundStartPlaying = distance < SoundInstance.DEFAULT_MAX_DISTANCE;
	                    }
	                }
                }
                
                //Finally, play the sound if all checks were true.
                if (shouldSoundStartPlaying) {
                    //Sound should play.  Check if we are a looping sound that has started so we don't double-play.
                    boolean isSoundPlaying = false;
                    if (soundDef.looping) {
                        for (SoundInstance sound : sounds) {
                            if (sound.soundDef == null ? sound.soundName.equals(soundDef.name) : sound.soundDef == soundDef) {
                                isSoundPlaying = true;
                                break;
                            }
                        }
                    }
                    if (!isSoundPlaying) {
                        InterfaceManager.soundInterface.playQuickSound(new SoundInstance(this, soundDef));
                    }
                } else {
                    if (soundDef.looping) {
                        //If sound is playing, stop it.
                        //Non-looping sounds are trigger-based and will stop on their own.
                        for (SoundInstance sound : sounds) {
                            if (sound.soundDef == null ? sound.soundName.equals(soundDef.name) : sound.soundDef == soundDef) {
                                sound.stopSound = true;
                                break;
                            }
                        }
                    }

                    //Go to the next soundDef.  No need to change properties on sounds that shouldn't play.
                    continue;
                }

                //Sound should be playing.  If it's part of the sound list, update properties.
                //Sounds may not be in the list if they have just been queued and haven't started yet.
                for (SoundInstance sound : sounds) {
                    if (sound != null && sound.soundName.equals(soundDef.name)) {
                        //Adjust volume.
                        SoundSwitchbox volumeSwitchbox = soundVolumeSwitchboxes.get(soundDef);
                        boolean definedVolume = false;
                        if (volumeSwitchbox != null) {
                            volumeSwitchbox.runSound(partialTicks);
                            sound.volume = volumeSwitchbox.value;
                            definedVolume = volumeSwitchbox.definedValue;
                        }
                        if (!definedVolume) {
                            sound.volume = 1;
                        } else if (sound.volume < 0) {
                            sound.volume = 0;
                        }

                        //Adjust volume based on distance.
                        if (soundDef.minDistanceVolume == 0 && soundDef.middleDistanceVolume == 0 && soundDef.maxDistanceVolume == 0) {
                            //Default sound distance.
                            double maxDistance = soundDef.maxDistance != 0 ? soundDef.maxDistance : SoundInstance.DEFAULT_MAX_DISTANCE;
                            if (distance > maxDistance) {
                                //Edge-case if we floating-point errors give us badmaths with the distance calcs.
                                sound.volume = 0;
                            } else {
                                sound.volume *= (maxDistance - distance) / (maxDistance);
                            }
                        } else if (soundDef.middleDistance != 0) {
                            //Middle interpolation.
                            if (distance < soundDef.middleDistance) {
                                sound.volume *= (float) (soundDef.minDistanceVolume + (distance - soundDef.minDistance) / (soundDef.middleDistance - soundDef.minDistance) * (soundDef.middleDistanceVolume - soundDef.minDistanceVolume));
                            } else {
                                sound.volume *= (float) (soundDef.middleDistanceVolume + (distance - soundDef.middleDistance) / (soundDef.maxDistance - soundDef.middleDistance) * (soundDef.maxDistanceVolume - soundDef.middleDistanceVolume));
                            }
                        } else {
                            //Min/max.
                            if (distance > soundDef.maxDistance) {
                                //Edge-case if we floating-point errors give us badmaths with the distance calcs.
                                sound.volume = 0;
                            } else {
                                sound.volume *= (float) (soundDef.minDistanceVolume + (distance - soundDef.minDistance) / (soundDef.maxDistance - soundDef.minDistance) * (soundDef.maxDistanceVolume - soundDef.minDistanceVolume));
                            }
                        }

                        //If the player is in a closed-top vehicle that isn't this one, dampen the sound
                        //Unless it's a radio, in which case don't do so.
                        if (!playerRidingThisEntity && entityRiding != null && sound.radio == null && !hasOpenTop && InterfaceManager.clientInterface.inFirstPerson() && !CameraSystem.runningCustomCameras) {
                            sound.volume *= 0.5F;
                        }

                        //Adjust pitch.
                        SoundSwitchbox pitchSwitchbox = soundPitchSwitchboxes.get(soundDef);
                        boolean definedPitch = false;
                        if (pitchSwitchbox != null) {
                            pitchSwitchbox.runSound(partialTicks);
                            sound.pitch = pitchSwitchbox.value;
                            definedPitch = pitchSwitchbox.definedValue;
                        }
                        if (!definedPitch) {
                            sound.pitch = 1;
                        } else if (sound.volume < 0) {
                            sound.pitch = 0;
                        }
                    }
                }
            }
        }
    }

    /**
     * Custom sound switchbox class.
     */
    private static class SoundSwitchbox extends AnimationSwitchbox {
        private boolean definedValue = false;
        private float value = 0;

        private SoundSwitchbox(AEntityD_Definable<?> entity, List<JSONAnimationDefinition> animations) {
            super(entity, animations, null);
        }

        public boolean runSound(float partialTicks) {
            value = 0;
            definedValue = false;
            return runSwitchbox(partialTicks, true);
        }

        @Override
        public void runTranslation(DurationDelayClock clock, float partialTicks) {
            definedValue = true;
            value += entity.getAnimatedVariableValue(clock, clock.animation.axis.y, partialTicks);
        }

        @Override
        public void runRotation(DurationDelayClock clock, float partialTicks) {
            definedValue = true;
            //Parobola is defined with parameter A being x, and H being z.
            double parabolaValue = entity.getAnimatedVariableValue(clock, clock.animation.axis.y, -clock.animation.offset, partialTicks);
            value += clock.animation.axis.x * Math.pow(parabolaValue - clock.animation.axis.z, 2) + clock.animation.offset;
        }
    }

    /**
     * Returns a new computed variable for the passed-in variable.  The default implementation is to just 
     * get the variable assuming it's a basic variable.  As such, super should always be called after any
     * overriding functions, since super will always return a value.
     */
    public ComputedVariable createComputedVariable(String variable) {
        switch (variable) {
            case ("tick"):
                return new ComputedVariable(this, variable, partialTicks -> ticksExisted + partialTicks, true);
            case ("tick_sin"):
                return new ComputedVariable(this, variable, partialTicks -> Math.sin(Math.toRadians(ticksExisted + partialTicks)), true);
            case ("tick_cos"):
                return new ComputedVariable(this, variable, partialTicks -> Math.cos(Math.toRadians(ticksExisted + partialTicks)), true);
            case ("time"):
                return new ComputedVariable(this, variable, partialTicks -> world.getTime(), false);
            case ("random"):
                return new ComputedVariable(this, variable, partialTicks -> Math.random(), true);
            case ("random_flip"):
                return new ComputedVariable(this, variable, partialTicks -> Math.random() < 0.5 ? 0 : 1, true);
            case ("rain_strength"):
                return new ComputedVariable(this, variable, partialTicks -> (int) world.getRainStrength(position), false);
            case ("rain_sin"): {
                return new ComputedVariable(this, variable, partialTicks -> {
                    int rainStrength = (int) world.getRainStrength(position);
                    return rainStrength > 0 ? Math.sin(rainStrength * Math.toRadians(360 * (ticksExisted + partialTicks) / 20)) / 2D + 0.5 : 0;
                }, false);
            }
            case ("rain_cos"): {
                return new ComputedVariable(this, variable, partialTicks -> {
                    int rainStrength = (int) world.getRainStrength(position);
                    return rainStrength > 0 ? Math.cos(rainStrength * Math.toRadians(360 * (ticksExisted + partialTicks) / 20)) / 2D + 0.5 : 0;
                }, false);
            }
            case ("light_sunlight"):
                return new ComputedVariable(this, variable, partialTicks -> world.getLightBrightness(position, false), false);
            case ("light_total"):
                return new ComputedVariable(this, variable, partialTicks -> world.getLightBrightness(position, true), false);
            case ("terrain_distance"):
                return new ComputedVariable(this, variable, partialTicks -> world.getHeight(position), false);
            case ("inliquid"):
                return new ComputedVariable(this, variable, partialTicks -> world.isBlockLiquid(position) ? 1 : 0, false);
            case ("config_simplethrottle"):
                return new ComputedVariable(this, variable, partialTicks -> ConfigSystem.client.controlSettings.simpleThrottle.value ? 1 : 0, false);
            case ("config_innerwindows"):
                return new ComputedVariable(this, variable, partialTicks -> ConfigSystem.client.renderingSettings.innerWindows.value ? 1 : 0, false);
            default: {
                if (variable.endsWith("_cycle")) {
                    String[] parsedVariable = variable.split("_");
                    final int offTime = Integer.parseInt(parsedVariable[0]);
                    final int onTime = Integer.parseInt(parsedVariable[1]);
                    final int totalTime = offTime + onTime + Integer.parseInt(parsedVariable[2]);
                    return new ComputedVariable(this, variable, partialTicks -> {
                        long timeInCycle = ticksExisted % totalTime;
                        return timeInCycle > offTime && timeInCycle - offTime < onTime ? 1 : 0;
                    }, false);
                } else if (variable.startsWith("text_") && variable.endsWith("_present")) {
                    if (definition.rendering != null && definition.rendering.textObjects != null) {
                        final int textIndex = Integer.parseInt(variable.substring("text_".length(), variable.length() - "_present".length())) - 1;
                        if (definition.rendering.textObjects.size() > textIndex) {
                            return new ComputedVariable(this, variable, partialTicks -> !text.get(definition.rendering.textObjects.get(textIndex)).isEmpty() ? 1 : 0, false);
                        } else {
                            return ZERO_VARIABLE;
                        }
                    } else {
                        return ZERO_VARIABLE;
                    }
                } else if (variable.startsWith("blockmaterial_")) {
                    final String materialName = variable.substring("blockmaterial_".length()).toUpperCase();
                    return new ComputedVariable(this, variable, partialTicks -> {
                        BlockMaterial material = world.getBlockMaterial(position);
                        if (material != null) {
                            return material.name().equals(materialName) ? 1 : 0;
                        } else {
                            return 0;
                        }
                    }, false);
                } else if (variable.startsWith("terrain_blockmaterial_")) {
                    final String materialName = variable.substring("terrain_blockmaterial_".length()).toUpperCase();
                    return new ComputedVariable(this, variable, partialTicks -> {
                        double height = world.getHeight(position) + 1;
                        position.y -= height;
                        BlockMaterial material = world.getBlockMaterial(position);
                        position.y += height;
                        if (material != null) {
                            return material.name().equals(materialName) ? 1 : 0;
                        } else {
                            return 0;
                        }
                    }, false);
                } else {
                    //Either a hard-coded value, or one we are wrapping.  No logic required.
                    return new ComputedVariable(this, variable, null);
                }
            }
        }
    }

    /**
     * Similar to {@link #getRawVariableValue(String, float)}, but returns
     * a String for text-based parameters rather than a double.  If no match
     * is found, return null.  Otherwise, return the string.
     */
    public String getRawTextVariableValue(JSONText textDef, float partialTicks) {
        return null;
    }

    /**
     * Returns the value for the passed-in variable, subject to the clamping, and duration/delay requested in the
     * animation definition.  The passed-in offset is used to allow for stacking animations, and should be 0 if
     * this functionality is not required.  Note that the animation offset is applied AFTER the scaling performed by
     * the scale parameter as only the variable value should be scaled, not the offset..
     */
    public final double getAnimatedVariableValue(DurationDelayClock clock, double scaleFactor, double offset, float partialTicks) {
        double value = getVariable(clock.animation.variable).computeValue(partialTicks);
        if (!clock.isUseful) {
            return clampAndScale(value, clock.animation, scaleFactor, offset);
        } else {
            return clampAndScale(clock.getFactoredState(this, value, partialTicks), clock.animation, scaleFactor, offset);
        }
    }

    /**
     * Short-hand version of {@link #getAnimatedVariableValue(DurationDelayClock, double, double, float)}
     * with an offset of 0.0.
     */
    public final double getAnimatedVariableValue(DurationDelayClock clock, double scaleFactor, float partialTicks) {
        return getAnimatedVariableValue(clock, scaleFactor, 0.0, partialTicks);
    }

    /**
     * Helper method to clamp and scale the passed-in variable value based on the passed-in animation,
     * returning it in the proper form.
     */
    private static double clampAndScale(double value, JSONAnimationDefinition animation, double scaleFactor, double offset) {
        if (animation.axis != null) {
            value = (animation.absolute ? Math.abs(value) : value) * scaleFactor + animation.offset + offset;
            if (animation.clampMin != 0 && value < animation.clampMin) {
                value = animation.clampMin;
            } else if (animation.clampMax != 0 && value > animation.clampMax) {
                value = animation.clampMax;
            }
            return value;
        } else {
            return (animation.absolute ? Math.abs(value) : value) * scaleFactor + animation.offset;
        }
    }

    /**
     * Returns the value for the passed-in variable, subject to the formatting and factoring in the
     * text definition.
     */
    public final String getAnimatedTextVariableValue(JSONText textDef, float partialTicks) {
        //Check text values first, then animated values.
        String value = getRawTextVariableValue(textDef, 0);
        if (value == null) {
            return String.format(textDef.variableFormat, getVariable(textDef.variableName).computeValue(partialTicks) * textDef.variableFactor);
        } else {
            return String.format(textDef.variableFormat, value);
        }
    }

    /**
     * Helper method to get a variable object for this entity.
     */
    public ComputedVariable getVariable(String variable) {
        return computedVariables.computeIfAbsent(variable, key -> createComputedVariable(variable));
    }
    
    /**
     * Helper method to remove a variable object for this entity.
     * Useful if one needs to re-create a variable for any reason.
     */
    public void removeVariable(String variable) {
        computedVariables.remove(variable);
    }

    /**
     * Helper method for variable modification.
     */
    protected double adjustVariable(JSONVariableModifier modifier, double currentValue) {
        double modifiedValue = modifier.setValue != 0 ? modifier.setValue : currentValue + modifier.addValue;
        VariableModifierSwitchbox switchbox = variableModiferSwitchboxes.get(modifier);
        if (switchbox != null) {
            switchbox.modifiedValue = (float) modifiedValue;
            if (switchbox.runSwitchbox(0, true)) {
                modifiedValue = switchbox.modifiedValue;
            } else {
                return currentValue;
            }
        }
        if (modifier.minValue != 0 || modifier.maxValue != 0) {
            if (modifiedValue < modifier.minValue) {
                return modifier.minValue;
            } else if (modifiedValue > modifier.maxValue) {
                return modifier.maxValue;
            }
        }
        return modifiedValue;
    }

    /**
     * Returns true if any of the variables in the passed-in list are true.
     */
    public boolean isVariableListTrue(List<List<String>> list) {
        if (list != null) {
            for (List<String> variableList : list) {
                boolean listIsTrue = false;
                for (String variableName : variableList) {
                    if (getVariable(variableName).isActive) {
                        listIsTrue = true;
                        break;
                    }
                }
                if (!listIsTrue) {
                    //List doesn't have any true variables, therefore the value is false.
                    return false;
                }
            }
            //No false lists were found for this collection, therefore the list is true.
        } //No lists found for this entry, therefore no variables are false.

        return true;
    }

    /**
     * Helper method to reset all variables.  Used for state-changes.
     */
    public final void resetVariables() {
        computedVariables.clear();
    }

    /**
     * Special method to close all doors on this entity.
     */
    public final void closeDoors() {
        computedVariables.forEach((variableKey, variableValue) -> {
            if (variableKey.contains("door")) {
                variableValue.setTo(0, true);
            }
        });
    }

    /**
     * Custom variable modifier switchbox class.
     */
    private static class VariableModifierSwitchbox extends AnimationSwitchbox {
        private float modifiedValue = 0;

        private VariableModifierSwitchbox(AEntityD_Definable<?> entity, List<JSONAnimationDefinition> animations) {
            super(entity, animations, null);
        }

        @Override
        public void runTranslation(DurationDelayClock clock, float partialTicks) {
            if (clock.animation.axis.x != 0) {
                modifiedValue *= clock.animation.axis.y == 0 ? entity.getAnimatedVariableValue(clock, clock.animation.axis.x, partialTicks) : Math.pow(entity.getAnimatedVariableValue(clock, clock.animation.axis.x, partialTicks), clock.animation.axis.y); //If the Y axis is zero, simply multiply. If it is not zero, multiply the variable raised to the power of Y.
            } else if (clock.animation.axis.y != 0) {
                modifiedValue += clock.animation.axis.z == 0 ? entity.getAnimatedVariableValue(clock, clock.animation.axis.y, partialTicks) : Math.pow(entity.getAnimatedVariableValue(clock, clock.animation.axis.y, partialTicks), clock.animation.axis.z);
            } else {
                modifiedValue = (float) (entity.getAnimatedVariableValue(clock, clock.animation.axis.z, partialTicks));
            }
        }

        //When a rotation is used, it will return V * (Xsin(V+x) + Ycos(V+y) + Ztan(V+z)) where X, Y, Z is the axis, and x, y, z is the centerPoint. Adding the 'invert' tag will make these inverse trig functions.
        @Override
        public void runRotation(DurationDelayClock clock, float partialTicks) {
        	float trigValue = 0;
        	if (clock.animation.invert) {
        		if (clock.animation.axis.x != 0) {
        			trigValue += clock.animation.axis.x * Math.toDegrees(Math.asin(entity.getAnimatedVariableValue(clock, 1, partialTicks) + clock.animation.centerPoint.x));
        		}
        		if (clock.animation.axis.y != 0) {
	    			trigValue += clock.animation.axis.y * Math.toDegrees(Math.acos(entity.getAnimatedVariableValue(clock, 1, partialTicks) + clock.animation.centerPoint.y));
        		}
        		if (clock.animation.axis.z != 0) {
	    			trigValue += clock.animation.axis.z * Math.toDegrees(Math.atan(entity.getAnimatedVariableValue(clock, 1, partialTicks) + clock.animation.centerPoint.z));
        		}
        	} else {
        		if (clock.animation.axis.x != 0) {
        			trigValue += clock.animation.axis.x * Math.sin(Math.toRadians(entity.getAnimatedVariableValue(clock, 1, partialTicks) + clock.animation.centerPoint.x));
        		}
        		if (clock.animation.axis.y != 0) {
	    			trigValue += clock.animation.axis.y * Math.cos(Math.toRadians(entity.getAnimatedVariableValue(clock, 1, partialTicks) + clock.animation.centerPoint.y));
        		}
        		if (clock.animation.axis.z != 0) {
	    			trigValue += clock.animation.axis.z * Math.tan(Math.toRadians(entity.getAnimatedVariableValue(clock, 1, partialTicks) + clock.animation.centerPoint.z));
        		}
        	}
        	modifiedValue *= trigValue;
        }
    }

    /**
     * Called to update the variable modifiers for this entity.
     */
    protected void updateVariableModifiers() {
        if (definition.variableModifiers != null) {
            for (JSONVariableModifier modifier : definition.variableModifiers) {
            	ComputedVariable variable = getVariable(modifier.variable);
            	variable.setTo(adjustVariable(modifier, variable.currentValue), false);
            }
        }
    }

    @Override
    protected void renderModel(TransformationMatrix transform, boolean blendingEnabled, float partialTicks) {
        //Update internal lighting states.
        world.beginProfiling("LightStateUpdates", true);
        updateLightBrightness(partialTicks);

        //Parse model if it hasn't been already.
        world.beginProfiling("MainModel", false);
        String modelLocation = definition.getModelLocation(subDefinition);
        if (!objectLists.containsKey(modelLocation)) {
            objectLists.put(modelLocation, AModelParser.generateRenderables(this));
        }

        //Render model object individually.
        for (RenderableModelObject modelObject : objectLists.get(modelLocation)) {
            modelObject.render(this, transform, blendingEnabled, partialTicks);
        }

        //Render any static text.
        world.beginProfiling("MainText", false);
        if (!blendingEnabled) {
            for (Entry<JSONText, String> textEntry : text.entrySet()) {
                JSONText textDef = textEntry.getKey();
                if (textDef.attachedTo == null) {
                    RenderText.draw3DText(textEntry.getValue(), this, transform, textDef, false);
                }
            }
        }
        //Handle particles.  Need to only do this once per frame-render.  Shaders may have us render multiple times.
        if (!InterfaceManager.clientInterface.isGamePaused() && !(ticksExisted == lastTickParticlesSpawned && partialTicks == lastPartialTickParticlesSpawned)) {
            world.beginProfiling("Particles", false);
            spawnParticles(partialTicks);
            lastTickParticlesSpawned = ticksExisted;
            lastPartialTickParticlesSpawned = partialTicks;
        }
        world.endProfiling();
    }

    @Override
    protected boolean disableRendering(float partialTicks) {
        //Don't render if we don't have a model.
        return super.disableRendering(partialTicks) || definition.rendering.modelType.equals(ModelType.NONE);
    }

    /**
     * Called externally to reset all caches for all renders.
     */
    public static void clearObjectCaches(AJSONMultiModelProvider definition) {
        for (JSONSubDefinition subDef : definition.definitions) {
            String modelLocation = definition.getModelLocation(subDef);
            List<RenderableModelObject> resetObjects = objectLists.remove(modelLocation);
            if (resetObjects != null) {
                for (RenderableModelObject modelObject : resetObjects) {
                    modelObject.destroy();
                }
            }
        }
    }

    @Override
    public IWrapperNBT save(IWrapperNBT data) {
        super.save(data);
        data.setString("packID", definition.packID);
        data.setString("systemName", definition.systemName);
        data.setString("subName", subDefinition.subName);
        if (!text.isEmpty()) {
            int lineNumber = 0;
            for (String textLine : text.values()) {
                data.setString("textLine" + lineNumber++, textLine);
            }
        }
        List<String> savedNames = new ArrayList<>();
        computedVariables.values().forEach(variable -> variable.saveToNBT(savedNames, data));
        if (!savedNames.isEmpty()) {
            data.setStrings("variables", savedNames);
        }
        return data;
    }

    /**
     * Indicates that this field is a derived value from
     * one of the variables in {@link AEntityD_Definable#variables}.
     * Variables that are derived are parsed from the map every update.
     * To modify them you will need to update their values in the respective
     * variable set via
     * {@link PacketEntityVariableToggle},
     * {@link PacketEntityVariableSet},
     * {@link PacketEntityVariableIncrement}
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.FIELD})
    public @interface DerivedValue {
    }

    /**
     * Indicates that this field is able to be modified via variable modification
     * by the code in {@link AEntityE_Interactable#updateVariableModifiers()},
     * This annotation is only for variables that are NOT derived from states
     * and annotated with {@link DerivedValue}, as those variables can inherently
     * be modified as they are derived from the variable states.
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.FIELD})
    public @interface ModifiableValue {
    }

    /**
     * Indicates that this field is a modified version of a field annotated with
     * {@link ModifiableValue}.  This is done to prevent modifying the parsed
     * definition entry that contains the value, which is why it's stored
     * in a new variable that gets aligned every tick before updates.
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.FIELD})
    public @interface ModifiedValue {
    }
}
