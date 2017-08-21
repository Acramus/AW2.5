package net.shadowmage.ancientwarfare.automation.tile.torque.multiblock;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.shadowmage.ancientwarfare.automation.config.AWAutomationStatics;
import net.shadowmage.ancientwarfare.core.network.NetworkHandler;
import net.shadowmage.ancientwarfare.core.network.PacketBlockEvent;
import net.shadowmage.ancientwarfare.core.util.BlockFinder;
import net.shadowmage.ancientwarfare.core.util.BlockTools;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.List;

public class TileFlywheelStorage extends TileEntity implements ITickable {

    public BlockPos controllerPos;
    public boolean isControl = false;//set to true if this is the control block for a setup
    public int setWidth, setHeight, setCube, setType;//validation params, only 'valid' in the control block.  used by rendering
    public double storedEnergy, maxEnergyStored, maxRpm = 100;
    public double torqueLoss;
    public double rotation, prevRotation;//used in rendering
    private int clientEnergy, clientDestEnergy;
    private int networkUpdateTicks = 0;

    @Override
    public void update() {
        if (isControl) {
            if (world.isRemote) {
                clientNetworkUpdate();
            } else {
                serverNetworkUpdate();
                applyPowerLoss();
            }
        }
    }

    protected void clientNetworkUpdate() {
        updateRotation();
        if (networkUpdateTicks > 0) {
            int diff = clientDestEnergy - clientEnergy;
            clientEnergy += diff / networkUpdateTicks;
            networkUpdateTicks--;
        }
    }

    protected void applyPowerLoss() {
        double eff = 1.d - getEfficiency();
        eff *= 0.1d;
        torqueLoss = storedEnergy * eff;
        storedEnergy -= torqueLoss;
    }

    protected double getEfficiency() {
        int meta = getBlockMetadata();
        switch (meta) {
            case 0:
                return AWAutomationStatics.low_efficiency_factor;
            case 1:
                return AWAutomationStatics.med_efficiency_factor;
            case 2:
                return AWAutomationStatics.high_efficiency_factor;
            default:
                return AWAutomationStatics.low_efficiency_factor;
        }
    }

    protected void serverNetworkUpdate() {
        if (!AWAutomationStatics.enable_energy_network_updates) {
            return;
        }
        networkUpdateTicks--;
        if (networkUpdateTicks <= 0) {
            double percentStored = storedEnergy / maxEnergyStored;
            int total = (int) (percentStored * 100.d);
            if (total != clientEnergy) {
                clientEnergy = total;
                sendDataToClient(1, clientEnergy);
            }
            networkUpdateTicks = AWAutomationStatics.energyMinNetworkUpdateFrequency;
        }
    }

    protected final void sendDataToClient(int type, int data) {
        PacketBlockEvent pkt = new PacketBlockEvent(pos, getBlockType(), (byte) type, (short) data);
        NetworkHandler.sendToAllTrackingChunk(world, pos.getX() >> 4, pos.getZ() >> 4, pkt);
    }

    @Override
    public boolean shouldRenderInPass(int pass) {
        return pass == 0 || pass == 1;
    }

    @Override
    public boolean receiveClientEvent(int a, int b) {
        if (world.isRemote) {
            if (a == 1) {
                clientDestEnergy = b;
                networkUpdateTicks = AWAutomationStatics.energyMinNetworkUpdateFrequency;
            }
        }
        return true;
    }

    protected void updateRotation() {
        double rpm = (double) clientEnergy * 0.01d * this.maxRpm;
        prevRotation = rotation;
        rotation += rpm * AWAutomationStatics.rpmToRpt;
    }

    public void blockBroken() {
        if (isControl) {
            TileEntity tileEntity;
            for(int i = -1; i < 3; i++){
                for(int j = -1; j < setHeight + 1; j++){
                    for(int k = -1; k < 3; k++){
                        tileEntity = world.getTileEntity(pos.add(i, j, k));
                        if(tileEntity instanceof TileFlywheelStorage){
                            ((TileFlywheelStorage) tileEntity).setController(null);
                        }
                    }
                }
            }
            isControl = false;
        } else if (controllerPos != null) {
            TileFlywheelStorage controller = getController();
            controllerPos = null;
            if (controller != null) {
                controller.validateSetup();
            } else {
                informNeighborsToValidate();
            }
        } else {
            informNeighborsToValidate();
        }
    }

    public final void blockPlaced() {
        validateSetup();
    }

    public final void setController(BlockPos pos) {
        this.controllerPos = pos;
        markDirty();
        if (controllerPos != null) {
            BlockTools.notifyBlockUpdate(this);
        }
    }

    protected boolean validateSetup() {
        BlockFinder finder = new BlockFinder(world, getBlockType(), getBlockMetadata(), 30);
        Pair<BlockPos, BlockPos> corners = finder.cross(pos, new BlockPos(3, world.getActualHeight(), 3));
        int minX = corners.getLeft().getX(), minY = corners.getLeft().getY(), minZ = corners.getLeft().getZ();
        int w = corners.getRight().getX() - minX + 1, h = corners.getRight().getY() - minY + 1, l = corners.getRight().getZ() - minZ + 1;
        boolean valid = w == l && (w == 1 || w == 3)  && finder.box(corners);
        if (valid) {
            int cx = w == 1 ? minX : minX + 1;
            int cz = l == 1 ? minZ : minZ + 1;
            setValidSetup(finder.getPositions(), cx, minY, cz, w, h, getBlockMetadata());
        } else {
            finder.connect(corners.getLeft(), new BlockPos(w, h, l));
            setInvalidSetup(finder.getPositions());
        }
        return valid;
    }

    private void setValidSetup(List<BlockPos> set, int cx, int cy, int cz, int size, int height, int type) {
        controllerPos = new BlockPos(cx, cy, cz);
        TileEntity te = world.getTileEntity(controllerPos);
        if (te instanceof TileFlywheelStorage) {
            ((TileFlywheelStorage) te).setAsController(size, height, type);
            for (BlockPos pos : set) {
                te = world.getTileEntity(pos);
                if (te instanceof TileFlywheelStorage) {
                    ((TileFlywheelStorage) te).setController(controllerPos);
                }
            }
        }else{
            controllerPos = null;
        }
    }

    private void setAsController(int size, int height, int type) {
        this.isControl = true;
        this.setWidth = size;
        this.setHeight = height;
        this.setType = type;
        this.setCube = size * size * height;
        double energyPerBlockForType = 1600;
        switch (type) {
            case 0: {
                energyPerBlockForType = AWAutomationStatics.low_storage_energy_max;
                break;
            }
            case 1: {
                energyPerBlockForType = AWAutomationStatics.med_storage_energy_max;
                break;
            }
            case 2: {
                energyPerBlockForType = AWAutomationStatics.high_storage_energy_max;
                break;
            }
        }
        this.maxEnergyStored = (double) setCube * energyPerBlockForType;
        markDirty();
        BlockTools.notifyBlockUpdate(this);
    }

    private void setInvalidSetup(List<BlockPos> set) {
        TileEntity te;
        controllerPos = null;
        isControl = false;
        for (BlockPos pos : set) {
            te = world.getTileEntity(pos);
            if (te instanceof TileFlywheelStorage) {
                ((TileFlywheelStorage) te).setController(null);
                ((TileFlywheelStorage) te).isControl = false;
            }
        }
    }

    private void informNeighborsToValidate() {
        TileEntity te;
        for (EnumFacing d : EnumFacing.VALUES) {
            te = world.getTileEntity(pos.offset(d));
            if (te instanceof TileFlywheelStorage) {
                ((TileFlywheelStorage) te).validateSetup();
            }
        }
    }

    public TileFlywheelStorage getController() {
        if (controllerPos != null) {
            TileEntity te = world.getTileEntity(controllerPos);
            return te instanceof TileFlywheelStorage ? (TileFlywheelStorage) te : null;
        }
        return null;
    }

    @Nullable
    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound tag = new NBTTagCompound();
        if (controllerPos != null) {
            tag.setLong("controllerPos", controllerPos.toLong());
            if (isControl) {
                tag.setBoolean("isControl", true);
                tag.setInteger("setWidth", setWidth);
                tag.setInteger("setHeight", setHeight);
                tag.setInteger("setType", setType);
                tag.setInteger("clientEnergy", clientEnergy);
            }
        }
        return new SPacketUpdateTileEntity(pos, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        super.onDataPacket(net, pkt);
        NBTTagCompound tag = pkt.getNbtCompound();
        controllerPos = tag.hasKey("controllerPos") ? BlockPos.fromLong(tag.getLong("controllerPos")) : null;
        if (controllerPos != null) {
            isControl = tag.getBoolean("isControl");
            if (isControl) {
                setHeight = tag.getInteger("setHeight");
                setWidth = tag.getInteger("setWidth");
                setCube = setWidth * setWidth * setHeight;
                setType = tag.getInteger("type");
                clientEnergy = tag.getInteger("clientEnergy");
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        controllerPos = tag.hasKey("controllerPos") ? BlockPos.fromLong(tag.getLong("controllerPos")) : null;
        if (controllerPos != null) {
            isControl = tag.getBoolean("isControl");
            if (isControl) {
                maxEnergyStored = tag.getDouble("maxEnergy");
                setHeight = tag.getInteger("setHeight");
                setWidth = tag.getInteger("setWidth");
                setCube = setWidth * setWidth * setHeight;
                setType = tag.getInteger("type");
            }
        }
        storedEnergy = tag.getDouble("storedEnergy");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (controllerPos != null) {
            tag.setLong("controllerPos", controllerPos.toLong());
            if (isControl) {
                tag.setBoolean("isControl", true);
                tag.setDouble("maxEnergy", maxEnergyStored);
                tag.setInteger("setWidth", setWidth);
                tag.setInteger("setHeight", setHeight);
                tag.setInteger("setType", setType);
            }
        }
        tag.setDouble("storedEnergy", storedEnergy);
        return tag;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1, pos.getX() + 2, pos.getY() + setHeight, pos.getZ() + 2);
    }

}
