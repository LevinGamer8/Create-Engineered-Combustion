package dev.engineeredcombustion.content.engine.carburetor;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.fuel.EngineFuel;
import dev.engineeredcombustion.foundation.ECLang;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * The engine's fuel supply: a small tank that only accepts gasoline.
 *
 * <p>It exposes nothing but the standard NeoForge fluid capability, so Create's
 * pipes, vanilla buckets and any other mod's fluid transport all work through the
 * same path. There is no Create-specific code here at all.
 */
public class CarburetorBlockEntity extends BlockEntity implements IHaveGoggleInformation {

	private static final String KEY_TANK = "Tank";

	/** Rejects anything that is not gasoline, so pipes cannot push junk into it. */
	private final FluidTank tank = new FluidTank(EngineTuning.CARBURETOR_CAPACITY_MB, EngineFuel::isValidFuel) {

		@Override
		protected void onContentsChanged() {
			setChanged();
			if (level != null && !level.isClientSide)
				level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	};

	public CarburetorBlockEntity(BlockPos pos, BlockState state) {
		super(ECBlockEntityTypes.CARBURETOR.get(), pos, state);
	}

	public IFluidHandler getFluidHandler() {
		return tank;
	}

	public FluidStack getFluid() {
		return tank.getFluid();
	}

	public int getCapacity() {
		return tank.getCapacity();
	}

	/** True only for gasoline; an unexpected fluid reports as invalid, not as fuel. */
	public boolean holdsValidFuel() {
		return EngineFuel.isValidFuel(tank.getFluid());
	}

	public boolean hasFuel(int millibuckets) {
		return holdsValidFuel() && tank.getFluidAmount() >= millibuckets;
	}

	/** @return true only when the full amount was removed. */
	public boolean consumeFuel(int millibuckets) {
		if (!hasFuel(millibuckets))
			return false;
		FluidStack drained = tank.drain(millibuckets, IFluidHandler.FluidAction.EXECUTE);
		return drained.getAmount() == millibuckets;
	}

	// --- persistence & synchronisation -------------------------------------

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		tank.readFromNBT(registries, tag.getCompound(KEY_TANK));
	}

	@Override
	public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.put(KEY_TANK, tank.writeToNBT(registries, new CompoundTag()));
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Nullable
	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	// --- goggle overlay -----------------------------------------------------

	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		ECLang.translate("gui.carburetor")
			.style(ChatFormatting.WHITE)
			.forGoggles(tooltip);

		FluidStack fluid = tank.getFluid();
		if (fluid.isEmpty()) {
			ECLang.translate("gui.fuel",
				ECLang.translate("gui.value.empty")
					.style(ChatFormatting.RED)
					.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
			return true;
		}

		boolean valid = holdsValidFuel();
		ECLang.translate("gui.fuel", ECLang.builder()
			.add(fluid.getHoverName()
				.copy())
			.style(valid ? ChatFormatting.GREEN : ChatFormatting.RED)
			.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		if (!valid)
			ECLang.translate("gui.value.not_fuel")
				.style(ChatFormatting.RED)
				.forGoggles(tooltip, 1);

		ECLang.translate("gui.fuel_amount",
			ECLang.number(tank.getFluidAmount())
				.style(ChatFormatting.AQUA)
				.component(),
			ECLang.number(tank.getCapacity())
				.style(ChatFormatting.DARK_GRAY)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		return true;
	}

	@Override
	public ItemStack getIcon(boolean isPlayerSneaking) {
		return new ItemStack(getBlockState().getBlock());
	}
}
