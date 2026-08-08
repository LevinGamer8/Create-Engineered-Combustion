package dev.engineeredcombustion.content.engine.sump;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.LubricationState;
import dev.engineeredcombustion.content.fuel.EngineLubricant;
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
 * The engine's oil reservoir: a small tank that only accepts engine oil.
 *
 * <p>Structurally the mirror image of the carburetor - it exposes nothing but
 * the standard NeoForge fluid capability, so Create's pipes, vanilla buckets and
 * any other mod's fluid transport all reach it through the same path, with no
 * Create-specific code and no per-mod integration.
 */
public class OilSumpBlockEntity extends BlockEntity implements IHaveGoggleInformation {

	private static final String KEY_TANK = "Tank";

	/** Rejects anything that is not engine oil, so pipes cannot push junk into it. */
	private final FluidTank tank = new FluidTank(EngineTuning.OIL_CAPACITY_MB, EngineLubricant::isValidOil) {

		@Override
		protected void onContentsChanged() {
			setChanged();
			if (level != null && !level.isClientSide)
				level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	};

	public OilSumpBlockEntity(BlockPos pos, BlockState state) {
		super(ECBlockEntityTypes.OIL_SUMP.get(), pos, state);
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

	/** True only for engine oil; an unexpected fluid reports as invalid, not as oil. */
	public boolean holdsValidOil() {
		return EngineLubricant.isValidOil(tank.getFluid());
	}

	/** Usable oil in the tank, in millibuckets. Anything that is not oil counts as none. */
	public int getOilAmount() {
		return holdsValidOil() ? tank.getFluidAmount() : 0;
	}

	public LubricationState getLubricationState() {
		return LubricationState.forAmount(getOilAmount());
	}

	/** @return true only when the full amount was removed, so the tank can never go negative. */
	public boolean consumeOil(int millibuckets) {
		if (getOilAmount() < millibuckets)
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
		ECLang.translate("gui.oil_sump")
			.style(ChatFormatting.WHITE)
			.forGoggles(tooltip);

		FluidStack fluid = tank.getFluid();
		boolean valid = holdsValidOil();
		ECLang.translate("gui.fluid", (fluid.isEmpty()
			? ECLang.translate("gui.value.empty")
				.style(ChatFormatting.RED)
			: ECLang.builder()
				.add(fluid.getHoverName()
					.copy())
				.style(valid ? ChatFormatting.GREEN : ChatFormatting.RED)).component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		if (!fluid.isEmpty() && !valid)
			ECLang.translate("gui.value.not_oil")
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

		LubricationState lubrication = getLubricationState();
		ECLang.translate("gui.lubrication_supply", ECLang.translate(lubrication.translationKey())
			.style(lubricationColor(lubrication))
			.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		return true;
	}

	/** Shared with the engine overlay so both read the same way. */
	public static ChatFormatting lubricationColor(LubricationState lubrication) {
		return switch (lubrication) {
			case NORMAL -> ChatFormatting.GREEN;
			case LOW -> ChatFormatting.GOLD;
			case DRY -> ChatFormatting.RED;
		};
	}

	@Override
	public ItemStack getIcon(boolean isPlayerSneaking) {
		return new ItemStack(getBlockState().getBlock());
	}
}
