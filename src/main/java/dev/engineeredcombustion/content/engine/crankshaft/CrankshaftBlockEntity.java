package dev.engineeredcombustion.content.engine.crankshaft;

import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import dev.engineeredcombustion.content.engine.CrankMath;
import dev.engineeredcombustion.content.engine.EngineState;
import dev.engineeredcombustion.content.engine.EngineStructure;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlockEntity;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Engine controller and host of the authoritative engine simulation.
 *
 * <p>Responsibilities, in order:
 * <ol>
 * <li>Detect and cache the engine structure ({@link EngineStructure}).</li>
 * <li>Read the milestone-1 debug power source (a redstone signal).</li>
 * <li>Advance {@link EngineState}, which owns the authoritative crank angle.</li>
 * <li>Tell the adjacent {@link EngineFlywheelBlockEntity} - and only it - when
 * the engine's rotational output changed.</li>
 * </ol>
 *
 * <p>Note what is <b>not</b> here: nothing in this class touches a Create
 * kinetic network. That boundary is the whole point of the split - milestone 2
 * can rewrite the simulation without touching Create integration, and a Create
 * API change only affects the flywheel.
 */
public class CrankshaftBlockEntity extends BlockEntity implements IHaveGoggleInformation {

	/** Full structure + redstone re-check interval, in ticks, as a safety net. */
	private static final int REVALIDATE_INTERVAL = 20;
	/**
	 * While running, re-send the crank angle this often (ticks) so that client and
	 * server cannot drift apart indefinitely. Everything else is only sent when it
	 * actually changes, so a running engine costs one packet every 10 seconds.
	 */
	private static final int RESYNC_INTERVAL = 200;

	private static final String KEY_RUNNING = "Running";
	private static final String KEY_STRUCTURE_VALID = "StructureValid";
	private static final String KEY_CRANK_ANGLE = "CrankAngle";

	private final EngineState engine = new EngineState();

	@Nullable
	private EngineStructure structure;
	private boolean redstonePowered;

	/**
	 * Only used for client-side debug readouts; the server always derives this
	 * from {@link #structure}.
	 */
	private boolean clientStructureValid;

	private int revalidateCountdown;
	private int resyncCountdown = RESYNC_INTERVAL;

	public CrankshaftBlockEntity(BlockPos pos, BlockState state) {
		super(ECBlockEntityTypes.CRANKSHAFT.get(), pos, state);
	}

	public void tick() {
		if (level == null)
			return;

		if (level.isClientSide) {
			// The client only integrates the crank angle so rendering stays smooth
			// between the rare sync packets. It never decides whether the engine runs.
			engine.tick();
			return;
		}

		if (--revalidateCountdown <= 0) {
			revalidateCountdown = REVALIDATE_INTERVAL;
			refresh();
		}

		engine.tick();

		if (engine.isRunning() && --resyncCountdown <= 0) {
			resyncCountdown = RESYNC_INTERVAL;
			sync();
		}
	}

	/**
	 * Schedules a structure/power re-check for the next tick. Called from
	 * {@code neighborChanged} and by the cylinder when its piston assembly is
	 * installed or removed. Deferring by a tick coalesces the bursts of neighbour
	 * updates a single block placement produces.
	 */
	public void onSurroundingsChanged() {
		revalidateCountdown = 0;
	}

	/** Called from {@code CrankshaftBlock#onRemove} before the block entity dies. */
	public void onEngineRemoved() {
		BlockPos previousFlywheel = structure == null ? null : structure.flywheelPos();
		structure = null;
		engine.setRunning(false);
		notifyKineticAdapter(previousFlywheel);
	}

	private void refresh() {
		if (level == null || level.isClientSide)
			return;

		BlockPos previousFlywheel = structure == null ? null : structure.flywheelPos();

		structure = EngineStructure.detect(level, worldPosition, getAxis());
		redstonePowered = level.hasNeighborSignal(worldPosition);

		BlockPos currentFlywheel = structure == null ? null : structure.flywheelPos();
		boolean structureChanged = !Objects.equals(previousFlywheel, currentFlywheel);
		boolean runningChanged = engine.setRunning(structure != null && redstonePowered);

		if (!runningChanged && !structureChanged)
			return;

		if (structureChanged)
			notifyKineticAdapter(previousFlywheel);
		notifyKineticAdapter(currentFlywheel);
		sync();
	}

	/**
	 * The one place where engine state crosses into Create's world. The flywheel
	 * pulls the actual number back out via {@link #getOutputRpmFor(BlockPos)}.
	 */
	private void notifyKineticAdapter(@Nullable BlockPos flywheelPos) {
		if (flywheelPos == null || level == null || level.isClientSide)
			return;
		if (!level.isLoaded(flywheelPos))
			return;
		if (level.getBlockEntity(flywheelPos) instanceof EngineFlywheelBlockEntity flywheel)
			flywheel.onEngineOutputChanged();
	}

	/**
	 * Rotational output this crankshaft provides to the flywheel at the given
	 * position, in RPM. Returns 0 for any block that is not <i>this</i> engine's
	 * structural flywheel, which is what makes a second flywheel on the opposite
	 * end inert.
	 */
	public float getOutputRpmFor(BlockPos flywheelPos) {
		if (structure == null || !structure.flywheelPos()
			.equals(flywheelPos))
			return 0.0F;
		return engine.getOutputRpm();
	}

	public EngineState getEngineState() {
		return engine;
	}

	public Axis getAxis() {
		return getBlockState().getValue(CrankshaftBlock.HORIZONTAL_AXIS);
	}

	public boolean isStructureValid() {
		return level != null && level.isClientSide ? clientStructureValid : structure != null;
	}

	public boolean isPistonInstalled() {
		if (level == null)
			return false;
		BlockPos cylinderPos = worldPosition.above();
		if (!level.isLoaded(cylinderPos))
			return false;
		return level.getBlockEntity(cylinderPos) instanceof CylinderBlockEntity cylinder
			&& cylinder.hasPistonAssembly();
	}

	// --- persistence & synchronisation -------------------------------------

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		// The crank angle is persisted deliberately: it is one float, and keeping it
		// means a chunk reload does not visibly snap the piston to a new position.
		// Later milestones (combustion timing, firing order) want that continuity too.
		engine.setCrankAngleDegrees(tag.getFloat(KEY_CRANK_ANGLE));
		// Running/valid are only restored so the client has something to draw before
		// the first server tick. The server overwrites both from the actual world in
		// refresh(), which happens on the very next tick.
		engine.setRunning(tag.getBoolean(KEY_RUNNING));
		clientStructureValid = tag.getBoolean(KEY_STRUCTURE_VALID);
	}

	@Override
	public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putFloat(KEY_CRANK_ANGLE, engine.getCrankAngleDegrees());
		tag.putBoolean(KEY_RUNNING, engine.isRunning());
		tag.putBoolean(KEY_STRUCTURE_VALID, isStructureValid());
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

	private void sync() {
		setChanged();
		if (level != null && !level.isClientSide)
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	// --- debug readout ------------------------------------------------------

	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		tooltip.add(Component.translatable("gui.engineered_combustion.engine_stats")
			.withStyle(ChatFormatting.WHITE));
		for (Component line : debugLines())
			tooltip.add(Component.literal(" ")
				.append(line));
		return true;
	}

	public void sendDebugReport(Player player) {
		player.displayClientMessage(Component.translatable("gui.engineered_combustion.engine_stats")
			.withStyle(ChatFormatting.GOLD), false);
		for (Component line : debugLines())
			player.displayClientMessage(line, false);
	}

	private List<Component> debugLines() {
		boolean valid = isStructureValid();
		boolean running = engine.isRunning();
		float angle = engine.getCrankAngleDegrees();
		return List.of(
			Component.translatable("gui.engineered_combustion.structure",
				Component.translatable(valid ? "gui.engineered_combustion.valid" : "gui.engineered_combustion.invalid")
					.withStyle(valid ? ChatFormatting.GREEN : ChatFormatting.RED))
				.withStyle(ChatFormatting.GRAY),
			Component.translatable("gui.engineered_combustion.piston",
				Component.translatable(isPistonInstalled() ? "gui.engineered_combustion.installed"
					: "gui.engineered_combustion.missing")
					.withStyle(isPistonInstalled() ? ChatFormatting.GREEN : ChatFormatting.RED))
				.withStyle(ChatFormatting.GRAY),
			Component.translatable("gui.engineered_combustion.state",
				Component.translatable(running ? "gui.engineered_combustion.running"
					: "gui.engineered_combustion.stopped")
					.withStyle(running ? ChatFormatting.GREEN : ChatFormatting.RED))
				.withStyle(ChatFormatting.GRAY),
			Component.translatable("gui.engineered_combustion.crank_angle", String.format("%.1f", angle))
				.withStyle(ChatFormatting.GRAY),
			Component.translatable("gui.engineered_combustion.piston_position",
				String.format("%.2f", CrankMath.pistonPosition(angle)))
				.withStyle(ChatFormatting.GRAY),
			Component.translatable("gui.engineered_combustion.output_speed",
				String.format("%.0f", engine.getOutputRpm()))
				.withStyle(ChatFormatting.GRAY));
	}
}
