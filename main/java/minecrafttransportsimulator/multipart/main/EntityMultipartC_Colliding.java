package minecrafttransportsimulator.multipart.main;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import minecrafttransportsimulator.baseclasses.MultipartAxisAlignedBB;
import minecrafttransportsimulator.baseclasses.MultipartAxisAlignedBBCollective;
import minecrafttransportsimulator.dataclasses.PackMultipartObject.PackCollisionBox;
import minecrafttransportsimulator.multipart.parts.APart;
import minecrafttransportsimulator.multipart.parts.PartGroundDevice;
import minecrafttransportsimulator.systems.ConfigSystem;
import minecrafttransportsimulator.systems.RotationSystem;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**Now that we have an existing multipart its time to add the ability to collide with it.
 * This is where we add collision functions and collision AABB methods to allow
 * players to collide with this part.  Note that this does NOT handle interaction as that
 * was done in level B.  Also note that we have still not defined the motions and forces
 * as those are based on the collision properties defined here and will come in level D.
 * We DO take into account the global speed variable for movement, as that's needed for
 * correct collision detection and is not state-dependent.
 * 
 * @author don_bruce
 */
public abstract class EntityMultipartC_Colliding extends EntityMultipartB_Existing{
	private float width;
	private float height;
	/**Collective wrapper that allows for the calling of multiple collision boxes in this multipart.  May be null on the first scan.*/
	private MultipartAxisAlignedBBCollective collisionFrame;
	/**List of current collision boxes in this multipart.  Contains both multipart collision boxes and ground device collision boxes.*/
	private final List<MultipartAxisAlignedBB> currentCollisionBoxes = new ArrayList<MultipartAxisAlignedBB>();
	/**List of interaction boxes.  These are AABBs that can be clicked but NOT collided with.*/
	private final List<MultipartAxisAlignedBB> currentInteractionBoxes = new ArrayList<MultipartAxisAlignedBB>();
	/**Map that keys collision boxes of ground devices to the devices themselves.  Used for ground device collision operations.*/
	protected final Map<MultipartAxisAlignedBB, PartGroundDevice> groundDeviceCollisionBoxMap = new HashMap<MultipartAxisAlignedBB, PartGroundDevice>();
	
	protected final double speedFactor = ConfigSystem.getDoubleConfig("SpeedFactor");
			
	public EntityMultipartC_Colliding(World world){
		super(world);
	}
	
	public EntityMultipartC_Colliding(World world, float posX, float posY, float posZ, float playerRotation, String multipartName){
		super(world, posX, posY, posZ, playerRotation, multipartName);
	}
	
	@Override
	public void onEntityUpdate(){
		super.onEntityUpdate();
		if(pack != null){
			//Make sure the collision bounds for MC are big enough to collide with this entity.
			if(World.MAX_ENTITY_RADIUS < 32){
				World.MAX_ENTITY_RADIUS = 32;
			}
			
			//Populate the box lists.
			currentCollisionBoxes.clear();
			currentInteractionBoxes.clear();
			groundDeviceCollisionBoxMap.clear();
			currentCollisionBoxes.addAll(this.getUpdatedCollisionBoxes());
			for(APart part : this.getMultipartParts()){
				if(part instanceof PartGroundDevice){
					currentCollisionBoxes.add(part.getAABBWithOffset(Vec3d.ZERO));
					groundDeviceCollisionBoxMap.put(currentCollisionBoxes.get(currentCollisionBoxes.size() -1 ), (PartGroundDevice) part);
				}else{
					currentInteractionBoxes.add(part.getAABBWithOffset(Vec3d.ZERO));
				}
			}
		}
	}
	
	@Override
	public AxisAlignedBB getEntityBoundingBox(){
		//Override this to make collision checks work with the multiple collision points.
		return this.getCollisionBoundingBox();
	}
	
	@Override
    @Nullable
    public MultipartAxisAlignedBBCollective getCollisionBoundingBox(){
		//Return custom AABB for multi-collision.
		return this.collisionFrame != null ? this.collisionFrame : new MultipartAxisAlignedBBCollective(this, 1, 1);
    }
	
	/**
	 * Called by systems needing information about collision with this entity.
	 * This is a way to keep other bits from messing with the collision list
	 * and a way to get collisions without going through the wrapper.
	 */
	public List<MultipartAxisAlignedBB> getCurrentCollisionBoxes(){
		return currentCollisionBoxes;
	}
    
	/**
	 * Called to get a set of updated collision lists for this entity.
	 * Do NOT call more than once a tick as this operation is complex and
	 * CPU and RAM intensive!
	 */
	private List<MultipartAxisAlignedBB> getUpdatedCollisionBoxes(){
		if(this.pack != null){
			double furthestWidth = 0;
			double furthestHeight = 0;
			List<MultipartAxisAlignedBB> boxList = new ArrayList<MultipartAxisAlignedBB>();
			for(PackCollisionBox box : pack.collision){
				Vec3d partOffset = new Vec3d(box.pos[0], box.pos[1], box.pos[2]);
				Vec3d offset = RotationSystem.getRotatedPoint(partOffset, rotationPitch, rotationYaw, rotationRoll);
				MultipartAxisAlignedBB newBox = new MultipartAxisAlignedBB(this.getPositionVector().add(offset), partOffset, box.width, box.height);
				boxList.add(newBox);
				furthestWidth = (float) Math.max(furthestWidth, Math.abs(newBox.rel.xCoord) + box.width/2F);
				furthestHeight = (float) Math.max(furthestHeight, Math.abs(newBox.rel.yCoord) + box.height);
				furthestWidth = (float) Math.max(furthestWidth, Math.abs(newBox.rel.zCoord) + box.width/2F);
			}
			this.collisionFrame = new MultipartAxisAlignedBBCollective(this, (float) furthestWidth*2F+0.5F, (float) furthestHeight+0.5F);
			return boxList;
		}else{
			return new ArrayList<MultipartAxisAlignedBB>(0);
		}
	}
	
    /**
     * Checks if the passed-in entity could have clicked this multipart.
     * Result is based on rotation of the entity passed in and the current collision boxes.
     */
	public boolean wasMultipartClicked(Entity entity){
		Vec3d lookVec = entity.getLook(1.0F);
		Vec3d hitVec = entity.getPositionVector().addVector(0, entity.getEyeHeight(), 0);
		for(float f=1.0F; f<4.0F; f += 0.1F){
			//First check the collision boxes.
			for(MultipartAxisAlignedBB box : this.currentCollisionBoxes){
				if(box.isVecInside(hitVec)){
					return true;
				}
			}
			//If we didn't hit a collision box we may have hit an interaction box instead.
			for(MultipartAxisAlignedBB box : this.currentInteractionBoxes){
				if(box.isVecInside(hitVec)){
					return true;
				}
			}
			
			hitVec = hitVec.addVector(lookVec.xCoord*0.1F, lookVec.yCoord*0.1F, lookVec.zCoord*0.1F);
		}
		return false;
	}
	
	/**
	 * Checks collisions and returns the collision depth for a box.
	 * Returns -1 and breaks the ground device if it was a ground device that collided.
	 * Returns -2 destroys the multipart if it hit a core collision box at too high a speed.
	 * Returns -3 if the collision is a ground device and could be moved upwards to not collide (only for X and Z axis).
	 */
	protected float getCollisionForAxis(MultipartAxisAlignedBB box, boolean xAxis, boolean yAxis, boolean zAxis, PartGroundDevice optionalGroundDevice){
		box = box.offset(xAxis ? this.motionX*speedFactor : 0, yAxis ? this.motionY*speedFactor : 0, zAxis ? this.motionZ*speedFactor : 0);
		
		//Add a slight vertical offset to collisions in the X or Z axis to prevent them from catching the ground.
		//Sometimes ground devices and the like end up with a lower level of 3.9999 due to floating-point errors
		//and as such and don't collide correctly with blocks above 4.0.  Can happen at other Y values too, but that
		//one shows up extensively in superflat world testing.
		if(xAxis || zAxis){
			box = box.offset(0, 0.05F, 0);
		}
		List<AxisAlignedBB> collidingAABBList = this.getAABBCollisions(box, optionalGroundDevice);
		
		float collisionDepth = 0;
		for(AxisAlignedBB box2 : collidingAABBList){
			if(xAxis){
				collisionDepth = (float) Math.max(collisionDepth, motionX > 0 ? box.maxX - box2.minX : box2.maxX - box.minX);
			}
			if(yAxis){
				collisionDepth = (float) Math.max(collisionDepth, motionY > 0 ? box.maxY - box2.minY : box2.maxY - box.minY);
			}
			if(zAxis){
				collisionDepth = (float) Math.max(collisionDepth, motionZ > 0 ? box.maxZ - box2.minZ : box2.maxZ - box.minZ);
			}
			if(collisionDepth > 0.3){
				//This could be a collision, but it could also be that it's a ground device and moved
				//into a block and another axis needs to collide here.  Check the motion and bail if so.
				if((xAxis && (Math.abs(motionX) < collisionDepth)) || (yAxis && (Math.abs(motionY) < collisionDepth)) || (zAxis && (Math.abs(motionZ) < collisionDepth))){
					return 0;
				}
			}
		}
		if(optionalGroundDevice != null && !yAxis && collisionDepth > 0){
			//Ground device has collided.
			//Check to see if this collision can be avoided if the device is moved upwards.
			//Expand this box slightly to ensure we see the collision even with floating-point errors.
			collidingAABBList = getAABBCollisions(box.offset(xAxis ? this.motionX*speedFactor : 0, optionalGroundDevice.getHeight()*1.5F, zAxis ? this.motionZ*speedFactor : 0).expandXyz(0.05F), optionalGroundDevice);
			if(collidingAABBList.isEmpty()){
				//Ground device can be moved upward out of the way.
				//Return -3 and deal with this later.
				return -3;
			}else if(collisionDepth > 0.3){
				//Ground device couldn't be moved out of the way and hit too fast;
				//Break it off the multipart and return.
				if(!worldObj.isRemote){
					this.removePart(optionalGroundDevice, true);
				}
				return -1;
			}else{
				return collisionDepth;
			}
		}else if(collisionDepth > 0.3){
			//Not a ground device, therefore we are a core collision box and
			//hit too hard.  This results in an explosion and no more calculations.
			if(!worldObj.isRemote){
				this.destroyAtPosition(box.pos.xCoord, box.pos.yCoord, box.pos.zCoord);
			}
			return -2;
		}else{
			return collisionDepth;
		}
	}

	
	/**
	 * Checks if an AABB is colliding with blocks, and returns the AABB of those blocks.
	 * 
	 * If a soft block is encountered and this entity is going fast enough,
	 * it sets the soft block to air and slows down the entity.
	 * Used to plow though leaves and snow and the like. 
	 */
	protected List<AxisAlignedBB> getAABBCollisions(AxisAlignedBB box, PartGroundDevice optionalGroundDevice){
		//TODO re-do the slowdown physics here to make heavier vehicles not slow down as much.
		int minX = (int) Math.floor(box.minX);
    	int maxX = (int) Math.floor(box.maxX + 1.0D);
    	int minY = (int) Math.floor(box.minY);
    	int maxY = (int) Math.floor(box.maxY + 1.0D);
    	int minZ = (int) Math.floor(box.minZ);
    	int maxZ = (int) Math.floor(box.maxZ + 1.0D);
    	List<AxisAlignedBB> collidingAABBList = new ArrayList<AxisAlignedBB>();
    	
    	for(int i = minX; i < maxX; ++i){
    		for(int j = minY; j < maxY; ++j){
    			for(int k = minZ; k < maxZ; ++k){
    				BlockPos pos = new BlockPos(i, j, k);
    				byte currentBoxes = (byte) collidingAABBList.size();
    				worldObj.getBlockState(pos).addCollisionBoxToList(worldObj, pos, box, collidingAABBList, null);
    				if(collidingAABBList.size() != currentBoxes){
    					float hardness = worldObj.getBlockState(pos).getBlockHardness(worldObj, pos);
    					if(hardness  <= 0.2F && hardness >= 0){
    						worldObj.setBlockToAir(pos);
    						motionX *= 0.95;
    						motionY *= 0.95;
    						motionZ *= 0.95;
    						collidingAABBList.remove(currentBoxes);
    					}
    				}else{
    					if(optionalGroundDevice != null && optionalGroundDevice.pack.groundDevice.canFloat && worldObj.getBlockState(pos).getMaterial().isLiquid()){
    						collidingAABBList.add(worldObj.getBlockState(pos).getBoundingBox(worldObj, pos).offset(pos));
    					}
    				}
    			}
    		}
    	}
		return collidingAABBList;
	}
}
