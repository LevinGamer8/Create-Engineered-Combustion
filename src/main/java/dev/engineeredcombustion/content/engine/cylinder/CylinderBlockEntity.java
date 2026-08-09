package dev.engineeredcombustion.content.engine.cylinder;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import dev.engineeredcombustion.content.engine.CrankMath;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import dev.engineeredcombustion.foundation.ECLang;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds whether a Piston Assembly has been inserted into this cylinder.
 *
 * <p>A piston and a cylinder occupy the same physical volume, so the piston is
 * an <i>item</i> that gets installed into a placed cylinder rather than a second
 * block. This block entity stores that single fact and nothing else: the piston
 * has no independent state, because its position is derived from the
 * crankshaft's crank angle.
 */
public class CylinderBlockEntity extends BlockEntity implements IHaveGoggleInformation {

	private static final String KEY_PISTON_INSTALLED = "PistonInstalled";

	private boolean pistonInstalled;

	/** Client-side render cache only; never used for game logic. */
	@Nullable
	private CrankshaftBlockEntity cachedCrankshaft;

	public CylinderBlockEntity(BlockPos pos, BlockState state) {
		super(ECBlockEntityTypes.CYLINDER.get(), pos, state);
	}

	public boolean hasPistonAssembly() {
		return pistonInstalled;
	}

	/** @return false when a piston assembly is already installed. */
	public boolean installPistonAssembly() {
		if (pistonInstalled)
			return false;
		setPistonInstalled(true);
		return true;
	}

	/** @return false when there was nothing to remove. */
	public boolean removePistonAssembly() {
		if (!pistonInstalled)
			return false;
		setPistonInstalled(false);
		return true;
	}

	private void setPistonInstalled(boolean installed) {
		pistonInstalled = installed;
		setChanged();
		if (level == null || level.isClientSide)
			return;
		level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		// Installing or removing the piston changes structural validity without
		// changing any block state, so the crankshaft has to be told explicitly.
		if (level.getBlockEntity(worldPosition.below()) instanceof CrankshaftBlockEntity crankshaft)
			crankshaft.onSurroundingsChanged();
	}

	/**
	 * Crank angle driving this cylinder's piston, interpolated into the current
	 * frame. Returns 0 when there is no crankshaft below.
	 *
	 * <p>This is the mechanism that keeps the animation honest: there is no local
	 * animation counter here, the piston reads the same crank angle the engine
	 * simulation advances.
	 */
	public float getCrankAngleForRender(float partialTicks) {
		CrankshaftBlockEntity crankshaft = getCrankshaft();
		return crankshaft == null ? 0.0F : crankshaft.getEngineState()
			.getRenderCrankAngleDegrees(partialTicks);
	}

	/**
	 * Which way the crankshaft below runs, so the renderer knows which plane the
	 * connecting rod swings in. Falls back to the axis the models are authored
	 * for when there is no crankshaft; with nothing to drive it the rod is
	 * vertical anyway, so the choice is invisible.
	 */
	public Direction.Axis getEngineAxisForRender() {
		CrankshaftBlockEntity crankshaft = getCrankshaft();
		return crankshaft == null ? Direction.Axis.X : crankshaft.getAxis();
	}

	@Nullable
	private CrankshaftBlockEntity getCrankshaft() {
		if (cachedCrankshaft != null && !cachedCrankshaft.isRemoved())
			return cachedCrankshaft;
		cachedCrankshaft = null;
		if (level != null && level.getBlockEntity(worldPosition.below()) instanceof CrankshaftBlockEntity crankshaft)
			cachedCrankshaft = crankshaft;
		return cachedCrankshaft;
	}

	// --- persistence & synchronisation -------------------------------------

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		pistonInstalled = tag.getBoolean(KEY_PISTON_INSTALLED);
	}

	@Override
	public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putBoolean(KEY_PISTON_INSTALLED, pistonInstalled);
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

	/** Deliberately concise - the full engine diagnostic lives on the crankshaft. */
	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		ECLang.translate("gui.engineered_combustion.cylinder_stats")
			.style(ChatFormatting.WHITE)
			.forGoggles(tooltip);

		ECLang.translate("gui.engineered_combustion.piston",
			ECLang.translate(pistonInstalled ? "gui.engineered_combustion.installed"
				: "gui.engineered_combustion.missing")
				.style(pistonInstalled ? ChatFormatting.GREEN : ChatFormatting.RED)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		if (pistonInstalled)
			ECLang.translate("gui.engineered_combustion.piston_position",
				ECLang.number(CrankMath.pistonPosition(getCrankAngleForRender(0.0F)))
					.style(ChatFormatting.AQUA)
					.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
		return true;
	}

	@Override
	public ItemStack getIcon(boolean isPlayerSneaking) {
		return new ItemStack(ECItems.CYLINDER.get());
	}
}
