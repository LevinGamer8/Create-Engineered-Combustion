package dev.engineeredcombustion.content.engine.carburetor;

import java.util.List;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.fuel.EngineFuel;
import dev.engineeredcombustion.foundation.ECLang;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECItems;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * The engine's fuel supply and its throttle control.
 *
 * <p>The tank exposes nothing but the standard NeoForge fluid capability, so
 * Create's pipes, vanilla buckets and any other mod's fluid transport all work
 * through the same path.
 *
 * <p>Two things were added on top of that in this milestone, and both live here
 * because the carburetor is where a real engine puts them:
 * <ul>
 * <li>the <b>throttle</b>, as a Create {@link ScrollValueBehaviour} - so it uses
 * Create's own value UI, its own packet, its own persistence and its own
 * server-authoritative path rather than a bespoke screen;</li>
 * <li>whether an <b>Air Filter</b> is installed. An item rather than a block:
 * the engine is already five blocks tall, and an air cleaner is a part you bolt
 * onto a carburetor, not a machine of its own.</li>
 * </ul>
 */
public class CarburetorBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

	private static final String KEY_TANK = "Tank";
	private static final String KEY_AIR_FILTER = "AirFilter";

	/**
	 * Main throttle opening as a whole percentage.
	 *
	 * <p>Create's value UI is integer-based, so the authoritative value is an
	 * integer percent and the simulation divides it down. That keeps the number
	 * the player sets, the number the HUD prints, the number the lever renders
	 * from and the number the engine simulates identical, with no rounding
	 * disagreement between them.
	 */
	ScrollValueBehaviour throttle;

	private boolean airFilterInstalled;

	/**
	 * Tank sync bookkeeping. The tank changes once per combustion event - up to
	 * 3.2 times a second at full throttle - and pushing a block entity update for
	 * every millibucket would be pure noise on the wire, because the float bowl is
	 * only drawn in {@link EngineTuning#FUEL_LEVEL_RENDER_STEPS} discrete steps
	 * anyway.
	 */
	private boolean tankDirty;
	private int ticksSinceTankSync = EngineTuning.TANK_SYNC_INTERVAL_TICKS;
	private int lastSyncedLevelStep = -1;

	/** Rejects anything that is not gasoline, so pipes cannot push junk into it. */
	private final FluidTank tank = new FluidTank(EngineTuning.CARBURETOR_CAPACITY_MB, EngineFuel::isValidFuel) {

		@Override
		protected void onContentsChanged() {
			setChanged();
			if (level == null || level.isClientSide)
				return;
			tankDirty = true;
			flushTankSync();
		}
	};

	public CarburetorBlockEntity(BlockPos pos, BlockState state) {
		super(ECBlockEntityTypes.CARBURETOR.get(), pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		throttle = new ThrottleValueBehaviour(ECLang.translate("gui.throttle_label")
			.component(), this, new ThrottleValueBoxTransform());
		throttle.between(EngineTuning.THROTTLE_MIN_PERCENT, EngineTuning.THROTTLE_MAX_PERCENT);
		throttle.value = EngineTuning.THROTTLE_DEFAULT_PERCENT;
		throttle.withFormatter(percent -> percent + "%");
		behaviours.add(throttle);
	}

	@Override
	public void tick() {
		super.tick();
		if (level == null || level.isClientSide)
			return;
		if (ticksSinceTankSync < Integer.MAX_VALUE)
			ticksSinceTankSync++;
		flushTankSync();
	}

	/**
	 * Pushes the tank to clients when it is worth pushing: the moment the visible
	 * level actually steps, and otherwise no more often than
	 * {@link EngineTuning#TANK_SYNC_INTERVAL_TICKS}.
	 *
	 * <p>The goggle overlay still prints the exact amount, and it is read from the
	 * client's copy, so this trades at most half a second of staleness for the
	 * traffic. At full throttle that is under 2 mB of a 1000 mB tank.
	 */
	private void flushTankSync() {
		if (!tankDirty || level == null || level.isClientSide)
			return;
		if (getFuelLevelStep() == lastSyncedLevelStep
			&& ticksSinceTankSync < EngineTuning.TANK_SYNC_INTERVAL_TICKS)
			return;
		tankDirty = false;
		ticksSinceTankSync = 0;
		lastSyncedLevelStep = getFuelLevelStep();
		level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
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

	// --- fuel level, for the float bowl -------------------------------------

	/**
	 * How full the float bowl should be drawn, quantised to
	 * {@link EngineTuning#FUEL_LEVEL_RENDER_STEPS}.
	 *
	 * <p>Quantising is what lets the tank sync be throttled at all - a continuous
	 * height would make every millibucket visible and so make every millibucket
	 * worth sending.
	 */
	public int getFuelLevelStep() {
		if (tank.getFluidAmount() <= 0 || getCapacity() <= 0)
			return 0;
		int steps = EngineTuning.FUEL_LEVEL_RENDER_STEPS;
		// Any non-empty tank shows at least one step: a bowl drawn bone dry while
		// it still holds fuel would contradict the reading right above it.
		return Math.max(1, Mth.ceil(steps * (float) tank.getFluidAmount() / getCapacity()));
	}

	/** The same level as a fraction in {@code [0, 1]}, for the renderer. */
	public float getFuelFillFraction() {
		return getFuelLevelStep() / (float) EngineTuning.FUEL_LEVEL_RENDER_STEPS;
	}

	// --- throttle ------------------------------------------------------------

	/** Main throttle opening as a whole percentage, {@code [0, 100]}. */
	public int getThrottlePercent() {
		return throttle == null ? EngineTuning.THROTTLE_DEFAULT_PERCENT
			: Mth.clamp(throttle.getValue(), EngineTuning.THROTTLE_MIN_PERCENT, EngineTuning.THROTTLE_MAX_PERCENT);
	}

	/** The same opening as a fraction in {@code [0, 1]}, for the simulation. */
	public float getThrottle() {
		return getThrottlePercent() / (float) EngineTuning.THROTTLE_MAX_PERCENT;
	}

	// --- air filter ----------------------------------------------------------

	public boolean hasAirFilter() {
		return airFilterInstalled;
	}

	/** @return false when a filter is already fitted. */
	public boolean installAirFilter() {
		if (airFilterInstalled)
			return false;
		setAirFilterInstalled(true);
		return true;
	}

	/** @return false when there was nothing to take off. */
	public boolean removeAirFilter() {
		if (!airFilterInstalled)
			return false;
		setAirFilterInstalled(false);
		return true;
	}

	private void setAirFilterInstalled(boolean installed) {
		airFilterInstalled = installed;
		setChanged();
		if (level != null && !level.isClientSide)
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	// --- persistence & synchronisation -------------------------------------
	//
	// SmartBlockEntity makes saveAdditional/loadAdditional final and routes both
	// through read/write; the update tag and packet come from SyncedBlockEntity.
	// The throttle is written by the ScrollValueBehaviour itself, in super.

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		tank.readFromNBT(registries, tag.getCompound(KEY_TANK));
		airFilterInstalled = tag.getBoolean(KEY_AIR_FILTER);
		lastSyncedLevelStep = getFuelLevelStep();
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.put(KEY_TANK, tank.writeToNBT(registries, new CompoundTag()));
		tag.putBoolean(KEY_AIR_FILTER, airFilterInstalled);
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
		} else {
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
		}

		// Shown even when empty: "0 / 1000 mB" is a reading, and a carburetor with
		// no line at all would look like it had no tank.
		ECLang.translate("gui.fuel_amount",
			ECLang.number(tank.getFluidAmount())
				.style(fluid.isEmpty() ? ChatFormatting.RED : ChatFormatting.AQUA)
				.component(),
			ECLang.number(tank.getCapacity())
				.style(ChatFormatting.DARK_GRAY)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		// Deliberately not colour-coded as a warning. Running unfiltered is a valid
		// way to run this engine today - it is a state, not a fault.
		ECLang.translate("gui.air_intake",
			ECLang.translate(airFilterInstalled ? "gui.value.filtered" : "gui.value.unfiltered")
				.style(airFilterInstalled ? ChatFormatting.GREEN : ChatFormatting.GOLD)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		ECLang.translate("gui.throttle", ECLang.number(getThrottlePercent())
			.style(ChatFormatting.AQUA)
			.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		return true;
	}

	@Override
	public ItemStack getIcon(boolean isPlayerSneaking) {
		return new ItemStack(getBlockState().getBlock());
	}

	// --- Create value UI wiring ---------------------------------------------

	/**
	 * The throttle's scroll value, with one behavioural tweak.
	 *
	 * <p>Create's {@code ValueSettingsInputHandler} swallows the right-click that
	 * lands on a value box, which would make the carburetor impossible to fuel or
	 * to fit a filter to while aiming at the throttle. {@code bypassesInput} is
	 * Create's own hook for exactly that: with a fluid container or an Air Filter
	 * in hand, the value UI stands aside and the block's normal interaction runs.
	 */
	private static class ThrottleValueBehaviour extends ScrollValueBehaviour {

		ThrottleValueBehaviour(Component label, SmartBlockEntity be, ValueBoxTransform slot) {
			super(label, be, slot);
		}

		@Override
		public boolean bypassesInput(ItemStack mainhandItem) {
			return mainhandItem.is(ECItems.AIR_FILTER.get()) || !FluidUtil.getFluidHandler(mainhandItem)
				.isEmpty();
		}
	}

	/**
	 * Where the throttle's value box sits: centred on whichever horizontal face
	 * the player is looking at.
	 *
	 * <p>Sided rather than pinned to one face because the Carburetor has no
	 * facing property - it is the same shape whichever way the engine runs - so
	 * there is no "front" to prefer. Vertical faces are excluded: the block above
	 * is open air and the one below is the cylinder head, and a value box floating
	 * over either reads as belonging to the wrong block.
	 */
	private static class ThrottleValueBoxTransform extends ValueBoxTransform.Sided {

		@Override
		protected Vec3 getSouthLocation() {
			return VecHelper.voxelSpace(8, 8, 15.5);
		}

		@Override
		protected boolean isSideActive(BlockState state, Direction direction) {
			return direction.getAxis()
				.isHorizontal();
		}
	}
}
