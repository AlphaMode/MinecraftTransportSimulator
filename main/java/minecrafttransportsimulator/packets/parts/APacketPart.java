package minecrafttransportsimulator.packets.parts;

import io.netty.buffer.ByteBuf;
import minecrafttransportsimulator.multipart.main.EntityMultipartA_Base;
import minecrafttransportsimulator.multipart.parts.APart;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public abstract class APacketPart implements IMessage{
	protected int id;
	protected byte subId;

	public APacketPart(){}
	
	public APacketPart(APart part){
		this.id = part.multipart.getEntityId();
		this.subId = part.multipart.getMultipartPartIndex(part);
	}
	
	@Override
	public void fromBytes(ByteBuf buf){
		this.id = buf.readInt();
		this.subId = buf.readByte();
	}

	@Override
	public void toBytes(ByteBuf buf){
		buf.writeInt(this.id);
		buf.writeByte(this.subId);
	}
	
	protected static APart getMultipartPartFromMessage(APacketPart message, MessageContext ctx){
		EntityMultipartA_Base multipart;
		if(ctx.side.isServer()){
			multipart = (EntityMultipartA_Base) ctx.getServerHandler().playerEntity.worldObj.getEntityByID(message.id);
		}else{
			multipart = (EntityMultipartA_Base) Minecraft.getMinecraft().theWorld.getEntityByID(message.id);
		}
		if(multipart != null){
			APart part = multipart.getMultipartPartByIndex(message.subId);
			if(part != null){
				return part;
			}
		}
		return null;
	}
}
