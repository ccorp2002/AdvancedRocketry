package zmaster587.advancedRocketry.tile.multiblock.energy;

import io.netty.buffer.ByteBuf;

import java.util.LinkedList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.Configuration;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.item.ItemSatelliteIdentificationChip;
import zmaster587.libVulpes.api.IUniversalEnergyTransmitter;
import zmaster587.libVulpes.block.BlockMeta;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModuleText;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.multiblock.TileMultiBlock;
import zmaster587.libVulpes.tile.multiblock.TileMultiPowerProducer;
import zmaster587.libVulpes.util.Vector3F;

public class TileMicrowaveReciever extends TileMultiPowerProducer implements ITickable {

	static final BlockMeta iron_block = new BlockMeta(AdvancedRocketryBlocks.blockSolarPanel);
	static final Object[][][] structure = new Object[][][] {
		{
			{iron_block, '*', '*', '*', iron_block},
			{'*', iron_block, iron_block, iron_block, '*'},
			{'*', iron_block, 'c', iron_block,'*'},
			{'*', iron_block, iron_block, iron_block, '*'},
			{iron_block, '*', '*', '*', iron_block},
		}};

	List<Long> connectedSatellites;
	boolean initialCheck;
	int powerMadeLastTick, prevPowerMadeLastTick;
	ModuleText textModule;
	public TileMicrowaveReciever() {
		connectedSatellites = new LinkedList<Long>();
		initialCheck = false;
		textModule = new ModuleText(40, 20, "Generating 0 RF/t", 0x2b2b2b);
	}

	@Override
	public List<ModuleBase> getModules(int ID, EntityPlayer player) {
		List<ModuleBase> modules = super.getModules(ID, player);

		modules.add(textModule);

		return modules;
	}

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		return super.getRenderBoundingBox().grow(0, 2000, 0).offset(0, 1000, 0);
	}

	@Override
	public boolean shouldHideBlock(World world, BlockPos pos, IBlockState tile) {
		return false;
	}

	@Override
	public Object[][][] getStructure() {
		return structure;
	}

	@Override
	public List<BlockMeta> getAllowableWildCardBlocks() {
		List<BlockMeta> blocks = super.getAllowableWildCardBlocks();

		blocks.addAll(TileMultiBlock.getMapping('I'));
		blocks.add(iron_block);
		blocks.addAll(TileMultiBlock.getMapping('p'));

		return blocks;
	}

	@Override
	public String getMachineName() {
		return "tile.microwaveReciever.name";
	}

	public int getPowerMadeLastTick() {
		return powerMadeLastTick;
	}

	@Override
	public void onInventoryUpdated() {
		super.onInventoryUpdated();

		List list = new LinkedList<Long>();

		for(IInventory inv : itemInPorts) {
			for(int i = 0; i < inv.getSizeInventory(); i++) {
				ItemStack stack = inv.getStackInSlot(i);
				if(stack != null && stack.getItem() instanceof ItemSatelliteIdentificationChip) {
					ItemSatelliteIdentificationChip item = (ItemSatelliteIdentificationChip)stack.getItem();
					list.add(item.getSatelliteId(stack));
				}
			}
		}


		connectedSatellites = list;

	}

	@Override
	public void update() {

		if(!initialCheck && !world.isRemote) {
			completeStructure = attemptCompleteStructure(world.getBlockState(pos));
			onInventoryUpdated();
			initialCheck = true;
		}

		if(!isComplete())
			return;

		//Periodically check for obstructing blocks above the panel
		if(!world.isRemote && getPowerMadeLastTick() > 0 && world.getTotalWorldTime() % 100 == 0) {
			Vector3F<Integer> offset = getControllerOffset(getStructure());


			List<Entity> entityList = world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(this.getPos().getX() - offset.x, this.getPos().getY(), this.getPos().getZ() - offset.z, this.getPos().getX() - offset.x + getStructure()[0][0].length, 256, this.getPos().getZ() - offset.z + getStructure()[0].length));

			for(Entity e : entityList) {
				e.setFire(5);
			}

			for(int x=0 ; x < getStructure()[0][0].length; x++) {
				for(int z=0 ; z < getStructure()[0].length; z++) {

					BlockPos pos2;
					IBlockState state = world.getBlockState(pos2 = (world.getHeight(pos.add(x - offset.x, 128, z - offset.z)).add(0, -1, 0)));

					if(pos2.getY() > this.getPos().getY()) {
						if(!world.isAirBlock(pos2.add(0,1,0))) {
							world.setBlockToAir(pos2);
							world.playSound((double)pos2.getX(), (double)pos2.getY(), (double)pos2.getZ(), new SoundEvent(new ResourceLocation("fire.fire")), SoundCategory.BLOCKS, 1f, 3f, false);
						}
					}
				}
			}
		}

		DimensionProperties properties;
		if(!world.isRemote && (DimensionManager.getInstance().isDimensionCreated(world.provider.getDimension()) || world.provider.getDimension() == 0)) {
			properties = DimensionManager.getInstance().getDimensionProperties(world.provider.getDimension());

			int energyRecieved = 0;

			if(enabled) {
				for(long lng : connectedSatellites) {
					SatelliteBase satellite =  properties.getSatellite(lng);

					if(satellite instanceof IUniversalEnergyTransmitter) {
						energyRecieved += ((IUniversalEnergyTransmitter)satellite).transmitEnergy(EnumFacing.UP, false);
					}
				}
			}
			powerMadeLastTick = (int) (energyRecieved*Configuration.microwaveRecieverMulitplier);

			if(powerMadeLastTick != prevPowerMadeLastTick) {
				prevPowerMadeLastTick = powerMadeLastTick;
				PacketHandler.sendToNearby(new PacketMachine(this, (byte)1), world.provider.getDimension(),pos, 128);

			}
			producePower(powerMadeLastTick);
		}
		if(world.isRemote)
			textModule.setText("Generating " + powerMadeLastTick + " RF/t");
	}

	@Override
	public SPacketUpdateTileEntity getUpdatePacket() {
		NBTTagCompound nbt = new NBTTagCompound();
		nbt.setBoolean("canRender", canRender);
		nbt.setInteger("amtPwr", powerMadeLastTick);
		writeNetworkData(nbt);
		return new SPacketUpdateTileEntity(pos, 0, nbt);
	}

	@Override
	public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
		NBTTagCompound nbt = pkt.getNbtCompound();

		canRender = nbt.getBoolean("canRender");
		powerMadeLastTick = nbt.getInteger("amtPwr");
		readNetworkData(nbt);
	}
	
	@Override
	public NBTTagCompound getUpdateTag() {
		NBTTagCompound nbt = new NBTTagCompound();
		nbt.setBoolean("canRender", canRender);
		nbt.setInteger("amtPwr", powerMadeLastTick);
		writeToNBT(nbt);
		return nbt;
	}

	@Override
	public void handleUpdateTag(NBTTagCompound nbt) {
		powerMadeLastTick = nbt.getInteger("amtPwr");
		canRender = nbt.getBoolean("canRender");
		readNetworkData(nbt);
	}
	
	

	@Override
	public void writeDataToNetwork(ByteBuf out, byte id) {
		super.writeDataToNetwork(out, id);

		if(id == 1) {
			out.writeInt(powerMadeLastTick);
		}
	}

	@Override
	public void readDataFromNetwork(ByteBuf in, byte packetId,
			NBTTagCompound nbt) {
		super.readDataFromNetwork(in, packetId, nbt);	

		if(packetId == 1) {
			nbt.setInteger("amtPwr", in.readInt());
		}
	}

	@Override
	public void useNetworkData(EntityPlayer player, Side side, byte id,
			NBTTagCompound nbt) {
		super.useNetworkData(player, side, id, nbt);

		if(id == 1) {
			powerMadeLastTick = nbt.getInteger("amtPwr");
		}
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);

		int[] intArray = new int[connectedSatellites.size()*2];

		for( int i =0; i < connectedSatellites.size()*2; i += 2 ) {
			connectedSatellites.get(i/2);
			intArray[i] = (int) (connectedSatellites.get(i/2) & 0xFFFFFFFF);
			intArray[i+1] = (int) ((connectedSatellites.get(i/2) >>> 32) & 0xFFFFFFFF);
		}

		nbt.setIntArray("satilliteList", intArray);

		return nbt;
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);

		int intArray[] = nbt.getIntArray("satilliteList");
		connectedSatellites.clear();
		for( int i =0; i < intArray.length/2; i+=2 ) {
			connectedSatellites.add(intArray[i] | (((long)intArray[i+1]) << 32));
		}

	}

}
