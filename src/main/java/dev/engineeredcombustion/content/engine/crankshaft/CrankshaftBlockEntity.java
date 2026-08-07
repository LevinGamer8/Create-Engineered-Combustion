package dev.engineeredcombustion.content.engine.crankshaft;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import dev.engineeredcombustion.content.engine.EnginePhase;
import dev.engineeredcombustion.content.engine.EngineState;
import dev.engineeredcombustion.content.engine.EngineStructure;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlockEntity;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlock;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Engine controller and host of the authoritative engine simulation.
 *
 * <p>Per tick, on both sides:
 * <ol>
 * <li>read the flywheel's <i>actual</i> Create kinetic speed;</li>
 * <li>advance the crank angle by exactly that much.</li>
 * </ol>
 * Additionally on the server:
 * <ol start="3">
 * <li>read the redstone ignition signal, and re-check the structure (throttled);</li>
 * <li>run combustion, inertia and friction;</li>
 * <li>if - and only if - the speed the engine wants to generate changed, tell
 * the flywheel to push it into Create.</li>
 * </ol>
 *
 * <p>Because step 1 and 2 use a value Create already synchronises, client and
 * server derive the same crank angle from the same input without this mod
 * sending a packet per tick. Everything visible (piston, flywheel disc, attached
 * shafts) therefore agrees by construction.
 *
 * <p>Nothing in this class touches a Create kinetic network directly. That stays
 * in {@link EngineFlywheelBlockEntity}.
 */
public class CrankshaftBlockEntity extends BlockEntity implements IHaveGoggleInformation {

	/** Structure + redstone re-check interval, in ticks. */
	private static final int REVALIDATE_INTERVAL = 20;
	/** Crank-angle resync interval while turning, in ticks. */
	private static final int RESYNC_INTERVAL = 200;

	/** Matches Create's {@code forGoggles(tooltip)} indent for a title line. */
	private static final String GOGGLE_TITLE_INDENT = "    ";
	/** Matches Create's {@code forGoggles(tooltip, 1)} indent for detail lines. */
	private static final String GOGGLE_LINE_INDENT = "     ";

	private static final String KEY_CRANK_ANGLE = "CrankAngle";
	private static final String KEY_PHASE = "Phase";
	private static final String KEY_SIMULATED_RPM = "SimulatedRpm";
	private static final String KEY_PUBLISHED_RPM = "PublishedRpm";
	private static final String KEY_IGNITION = "Ignition";
	private static final String KEY_STRUCTURE_VALID = "StructureValid";
	private static final String KEY_REDSTONE_SIGNAL = "RedstoneSignal";

	private final EngineState engine = new EngineState();

	/**
	 * Strongest redstone signal reaching the crankshaft, 0-15. Server-authoritative,
	 * synchronised to the client purely so the debug readout can show it - the
	 * goggle overlay runs client-side and has no other way to know.
	 */
	private int redstoneSignal;

	@Nullable
	private EngineStructure structure;
	/**
	 * The flywheel this crankshaft is mechanically coupled to. Tracked separately
	 * from {@link #structure} on purpose: a crankshaft with no piston installed is
	 * structurally invalid but must still be turnable by an external Create source.
	 */
	@Nullable
	private BlockPos flywheelPos;
	@Nullable
	private EngineFlywheelBlockEntity cachedFlywheel;

	private int revalidateCountdown;
	private int resyncCountdown = RESYNC_INTERVAL;

	public CrankshaftBlockEntity(BlockPos pos, BlockState state) {
		super(ECBlockEntityTypes.CRANKSHAFT.get(), pos, state);
	}

	public void tick() {
		if (level == null)
			return;

		EngineFlywheelBlockEntity flywheel = getFlywheel();
		float mechanicalRpm = flywheel == null ? 0.0F : flywheel.getSpeed();
		engine.advanceCrankAngle(mechanicalRpm);

		if (level.isClientSide) {
			engine.updateClientPowerStroke();
			return;
		}

		if (--revalidateCountdown <= 0) {
			revalidateCountdown = REVALIDATE_INTERVAL;
			refreshStructure(flywheel);
		}

		// Read live every tick. This is cheap (six neighbours) and is the only way
		// the state can never go stale, whatever order neighbour updates arrive in.
		int signalBefore = redstoneSignal;
		redstoneSignal = level.getBestNeighborSignal(worldPosition);

		EnginePhase phaseBefore = engine.getPhase();
		boolean structureValidBefore = engine.isStructureValid();
		boolean generatedSpeedChanged = engine.tickSimulation(structure != null, redstoneSignal > 0,
			flywheel != null && flywheel.hasSource());

		if (generatedSpeedChanged && flywheel != null)
			// The one and only place engine state crosses into Create's world.
			flywheel.onEngineOutputChanged();

		// Anything the client displays has to trigger a block update, not just the
		// things that change the engine's rotation. Toggling redstone on a stopped
		// engine changes no speed and no phase, so without this the client would
		// keep showing the ignition state it was last told about.
		if (generatedSpeedChanged || signalBefore != redstoneSignal || phaseBefore != engine.getPhase()
			|| structureValidBefore != engine.isStructureValid()) {
			sync();
		} else if (engine.getMechanicalRpm() != 0.0F && --resyncCountdown <= 0) {
			resyncCountdown = RESYNC_INTERVAL;
			sync();
		}
	}

	/**
	 * Schedules a structure/ignition re-check for the next tick. Called from
	 * {@code neighborChanged} and by the cylinder when its piston assembly is
	 * installed or removed. Deferring by a tick coalesces the bursts of neighbour
	 * updates a single block placement produces.
	 */
	public void onSurroundingsChanged() {
		revalidateCountdown = 0;
		cachedFlywheel = null;
		flywheelPos = null;
	}

	/** Called from {@code CrankshaftBlock#onRemove} before the block entity dies. */
	public void onEngineRemoved() {
		BlockPos previousFlywheel = flywheelPos;
		structure = null;
		engine.setPhase(EnginePhase.STOPPED);
		engine.setSimulatedRpm(0.0F);
		engine.setPublishedRpm(0.0F);
		flywheelPos = null;
		cachedFlywheel = null;
		if (previousFlywheel != null && level != null && !level.isClientSide && level.isLoaded(previousFlywheel)
			&& level.getBlockEntity(previousFlywheel) instanceof EngineFlywheelBlockEntity flywheel)
			flywheel.onEngineOutputChanged();
	}

	private void refreshStructure(@Nullable EngineFlywheelBlockEntity flywheel) {
		if (level == null || level.isClientSide)
			return;
		structure = EngineStructure.detect(level, worldPosition, getAxis());

		// Safety net. Create normally hands the kinetic source back to us on its own
		// (GeneratingKineticBlockEntity#removeSource sets reActivateSource), but if a
		// running engine ever ends up generating power that the network does not
		// reflect, re-assert it instead of waiting for Create's 60-tick validation.
		if (flywheel != null && engine.getPublishedRpm() != 0.0F && !flywheel.hasSource()
			&& flywheel.getTheoreticalSpeed() == 0.0F)
			flywheel.onEngineOutputChanged();
	}

	// --- mechanical coupling ------------------------------------------------

	/**
	 * The adjacent flywheel along the crankshaft's axis, independent of whether
	 * the engine is structurally complete.
	 */
	@Nullable
	public EngineFlywheelBlockEntity getFlywheel() {
		if (cachedFlywheel != null && !cachedFlywheel.isRemoved())
			return cachedFlywheel;
		cachedFlywheel = null;
		flywheelPos = null;
		if (level == null)
			return null;

		Axis axis = getAxis();
		for (AxisDirection axisDirection : AxisDirection.values()) {
			BlockPos candidate = worldPosition.relative(Direction.get(axisDirection, axis));
			if (!level.isLoaded(candidate))
				continue;
			BlockState state = level.getBlockState(candidate);
			if (!(state.getBlock() instanceof EngineFlywheelBlock))
				continue;
			if (state.getValue(EngineFlywheelBlock.HORIZONTAL_AXIS) != axis)
				continue;
			if (level.getBlockEntity(candidate) instanceof EngineFlywheelBlockEntity flywheel) {
				cachedFlywheel = flywheel;
				flywheelPos = candidate;
				return flywheel;
			}
		}
		return null;
	}

	/**
	 * Rotational speed this engine generates for the flywheel at the given
	 * position, in RPM. Returns 0 for any block that is not the flywheel this
	 * crankshaft is coupled to, which is what makes a second flywheel on the
	 * opposite end inert.
	 *
	 * <p>The value is <i>latched</i>: it only changes when the simulation decides
	 * to publish a new one. Create calls this from validation and propagation at
	 * arbitrary times and must never see a value that drifts every tick.
	 */
	public float getGeneratedRpmFor(BlockPos queryingFlywheelPos) {
		if (flywheelPos == null)
			getFlywheel();
		if (flywheelPos == null || !flywheelPos.equals(queryingFlywheelPos))
			return 0.0F;
		return engine.getPublishedRpm();
	}

	public EngineState getEngineState() {
		return engine;
	}

	public Axis getAxis() {
		return getBlockState().getValue(CrankshaftBlock.HORIZONTAL_AXIS);
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
		engine.setCrankAngleDegrees(tag.getFloat(KEY_CRANK_ANGLE));
		// A running engine should survive a chunk reload rather than silently dying,
		// so the phase and both speeds are restored too. Structure validity and the
		// ignition signal are re-derived from the world on the next server tick.
		engine.setPhase(EnginePhase.byId(tag.getString(KEY_PHASE)));
		engine.setSimulatedRpm(tag.getFloat(KEY_SIMULATED_RPM));
		engine.setPublishedRpm(tag.getFloat(KEY_PUBLISHED_RPM));
		engine.setIgnitionEnabled(tag.getBoolean(KEY_IGNITION));
		engine.setStructureValid(tag.getBoolean(KEY_STRUCTURE_VALID));
		redstoneSignal = tag.getInt(KEY_REDSTONE_SIGNAL);
	}

	@Override
	public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putFloat(KEY_CRANK_ANGLE, engine.getCrankAngleDegrees());
		tag.putString(KEY_PHASE, engine.getPhase()
			.getId());
		tag.putFloat(KEY_SIMULATED_RPM, engine.getSimulatedRpm());
		tag.putFloat(KEY_PUBLISHED_RPM, engine.getPublishedRpm());
		tag.putBoolean(KEY_IGNITION, engine.isIgnitionEnabled());
		tag.putBoolean(KEY_STRUCTURE_VALID, engine.isStructureValid());
		tag.putInt(KEY_REDSTONE_SIGNAL, redstoneSignal);
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

	/**
	 * Create draws the overlay icon at a fixed offset over the top-left of the
	 * tooltip box, and every Create machine leaves room for it because
	 * {@code LangBuilder#forGoggles} indents <i>every</i> line - the title
	 * included - by four spaces (five for detail lines). This tooltip is built
	 * with plain Components, so it has to reproduce that margin itself.
	 */
	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		tooltip.add(Component.literal(GOGGLE_TITLE_INDENT)
			.append(Component.translatable("gui.engineered_combustion.engine_stats")
				.withStyle(ChatFormatting.WHITE)));
		for (Component line : debugLines())
			tooltip.add(Component.literal(GOGGLE_LINE_INDENT)
				.append(line));
		return true;
	}

	/**
	 * Suppresses the Engineer's Goggles icon Create draws next to the overlay.
	 *
	 * <p>Create renders it unconditionally via {@code GuiGameElement.of(item)},
	 * and catnip's {@code GuiItemRenderBuilder#renderItemIntoGUI} has no empty
	 * check of its own - but it delegates to vanilla's
	 * {@code ItemRenderer#render}, which draws nothing for an empty stack. The
	 * indentation above already keeps the icon clear of the text, so if this ever
	 * misbehaves the override can simply be deleted.
	 */
	@Override
	public ItemStack getIcon(boolean isPlayerSneaking) {
		return ItemStack.EMPTY;
	}

	public void sendDebugReport(Player player) {
		player.displayClientMessage(Component.translatable("gui.engineered_combustion.engine_stats")
			.withStyle(ChatFormatting.GOLD), false);
		for (Component line : debugLines())
			player.displayClientMessage(line, false);
	}

	private List<Component> debugLines() {
		List<Component> lines = new ArrayList<>();
		boolean valid = engine.isStructureValid();
		boolean piston = isPistonInstalled();
		boolean ignition = engine.isIgnitionEnabled();
		EnginePhase phase = engine.getPhase();

		lines.add(state("structure", valid, "valid", "invalid"));
		lines.add(state("piston", piston, "installed", "missing"));
		lines.add(Component.translatable("gui.engineered_combustion.redstone_signal",
			Component.literal(Integer.toString(redstoneSignal))
				.withStyle(redstoneSignal > 0 ? ChatFormatting.GREEN : ChatFormatting.RED))
			.withStyle(ChatFormatting.GRAY));
		lines.add(state("ignition", ignition, "enabled", "disabled"));
		lines.add(Component.translatable("gui.engineered_combustion.state",
			Component.translatable(phase.translationKey())
				.withStyle(phase == EnginePhase.RUNNING ? ChatFormatting.GREEN
					: phase == EnginePhase.STOPPED ? ChatFormatting.RED : ChatFormatting.YELLOW))
			.withStyle(ChatFormatting.GRAY));
		lines.add(Component.translatable("gui.engineered_combustion.rotation_source",
			Component.translatable(engine.getRotationSource()
				.translationKey())
				.withStyle(ChatFormatting.WHITE))
			.withStyle(ChatFormatting.GRAY));
		lines.add(number("crank_angle", "%.1f", engine.getCrankAngleDegrees()));
		lines.add(number("mechanical_rpm", "%.1f", engine.getMechanicalRpm()));
		lines.add(number("simulated_rpm", "%.1f", engine.getSimulatedRpm()));
		lines.add(number("generated_rpm", "%.1f", engine.getPublishedRpm()));
		lines.add(number("piston_position", "%.2f", engine.getPistonPosition()));
		lines.add(state("power_stroke", engine.isPowerStrokeActive(), "yes", "no"));
		return lines;
	}

	private static Component state(String key, boolean on, String onKey, String offKey) {
		return Component.translatable("gui.engineered_combustion." + key,
			Component.translatable("gui.engineered_combustion." + (on ? onKey : offKey))
				.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED))
			.withStyle(ChatFormatting.GRAY);
	}

	private static Component number(String key, String format, float value) {
		return Component.translatable("gui.engineered_combustion." + key, String.format(format, value))
			.withStyle(ChatFormatting.GRAY);
	}
}
