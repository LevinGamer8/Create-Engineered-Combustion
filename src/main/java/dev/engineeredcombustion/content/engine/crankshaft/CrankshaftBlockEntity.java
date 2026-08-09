package dev.engineeredcombustion.content.engine.crankshaft;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import dev.engineeredcombustion.content.engine.EnginePhase;
import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.FuelSupply;
import dev.engineeredcombustion.content.engine.LubricationState;
import dev.engineeredcombustion.content.engine.OilSupply;
import dev.engineeredcombustion.content.engine.sump.OilSumpBlockEntity;
import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlockEntity;
import dev.engineeredcombustion.content.engine.EngineState;
import dev.engineeredcombustion.content.engine.EngineComponents;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlockEntity;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;
import dev.engineeredcombustion.client.sound.EngineSoundManager;
import dev.engineeredcombustion.foundation.ECLang;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECItems;
import dev.engineeredcombustion.registry.ECSounds;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.fluids.FluidStack;

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
 * <li>resolve the engine's components and read the redstone ignition signal;</li>
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

	/** Crank-angle resync interval while turning, in ticks. */
	private static final int RESYNC_INTERVAL = 200;

	private static final String KEY_CRANK_ANGLE = "CrankAngle";
	private static final String KEY_PHASE = "Phase";
	private static final String KEY_SIMULATED_RPM = "SimulatedRpm";
	private static final String KEY_PUBLISHED_RPM = "PublishedRpm";
	private static final String KEY_IGNITION = "Ignition";
	private static final String KEY_STRUCTURE_VALID = "StructureValid";
	private static final String KEY_REDSTONE_SIGNAL = "RedstoneSignal";
	private static final String KEY_START_PROGRESS = "StartProgress";
	private static final String KEY_START_REQUIRED = "StartRequired";
	private static final String KEY_FUEL_AVAILABLE = "FuelAvailable";
	private static final String KEY_LUBRICATION = "Lubrication";
	private static final String KEY_OIL_WEAR = "OilWear";

	private final EngineState engine = new EngineState();

	/**
	 * Picks how many firing cycles a start attempt needs. Lives on the block
	 * entity rather than coming from the level so it can never be evaluated
	 * client-side - tickSimulation only ever runs on the server.
	 */
	private final java.util.Random random = new java.util.Random();

	/**
	 * Strongest redstone signal reaching the crankshaft, 0-15. Server-authoritative,
	 * synchronised to the client purely so the debug readout can show it - the
	 * goggle overlay runs client-side and has no other way to know.
	 */
	private int redstoneSignal;

	/**
	 * Components resolved at the top of the current server tick, or null outside
	 * one. Never survives the tick that set it - see {@link #components()}.
	 */
	@Nullable
	private EngineComponents tickComponents;

	/**
	 * The flywheel this crankshaft is mechanically coupled to.
	 *
	 * <p>Cached separately from {@link #tickComponents} because Create asks for the
	 * generated speed at arbitrary times, outside our tick, and the answer has to
	 * be the same coupling every time. It is also tracked independently of whether
	 * the engine is structurally complete: a crankshaft with no piston installed
	 * cannot run, but must still be turnable by an external Create source.
	 *
	 * <p>The position rule itself lives in {@link EngineComponents#findFlywheelPos}
	 * - this only caches its answer.
	 */
	@Nullable
	private BlockPos flywheelPos;
	@Nullable
	private EngineFlywheelBlockEntity cachedFlywheel;

	private int resyncCountdown = RESYNC_INTERVAL;

	/**
	 * Bridges the simulation to the carburetor. EngineState never learns what a
	 * fluid is; it only asks whether a combustion event can be paid for.
	 */
	private final FuelSupply fuelSupply = new FuelSupply() {

		@Override
		public boolean hasFuel() {
			CarburetorBlockEntity carburetor = getCarburetor();
			return carburetor != null && carburetor.hasFuel(EngineTuning.FUEL_PER_COMBUSTION_MB);
		}

		@Override
		public boolean consume(int millibuckets) {
			CarburetorBlockEntity carburetor = getCarburetor();
			return carburetor != null && carburetor.consumeFuel(millibuckets);
		}
	};

	/**
	 * Bridges the simulation to the oil sump. A missing sump is not an error
	 * condition here - it simply reads as DRY, which the friction model already
	 * knows how to punish.
	 */
	private final OilSupply oilSupply = new OilSupply() {

		@Override
		public LubricationState lubrication() {
			OilSumpBlockEntity sump = getOilSump();
			return sump == null ? LubricationState.DRY : sump.getLubricationState();
		}

		@Override
		public boolean consume(int millibuckets) {
			OilSumpBlockEntity sump = getOilSump();
			return sump != null && sump.consumeOil(millibuckets);
		}
	};

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
			tickAudio();
			return;
		}

		// Resolved once per server tick and held only for the duration of that tick.
		// The fuel and oil supplies read it, so combustion, fuel draw and lubrication
		// all act on one consistent snapshot - and it is the same call the overlay
		// makes, which is what keeps the HUD from ever contradicting the simulation.
		tickComponents = resolveComponents();
		reassertKineticSourceIfNeeded(flywheel);

		// Read live every tick. This is cheap (six neighbours) and is the only way
		// the state can never go stale, whatever order neighbour updates arrive in.
		int signalBefore = redstoneSignal;
		redstoneSignal = level.getBestNeighborSignal(worldPosition);

		EnginePhase phaseBefore = engine.getPhase();
		boolean structureValidBefore = engine.isStructureValid();
		int startProgressBefore = engine.getStartProgress();
		boolean fuelBefore = engine.isFuelAvailable();
		LubricationState lubricationBefore = engine.getLubrication();
		boolean generatedSpeedChanged = engine.tickSimulation(tickComponents.isMechanicallyValid(),
			redstoneSignal > 0, flywheel != null && flywheel.hasSource(), fuelSupply, oilSupply, random);

		if (generatedSpeedChanged && flywheel != null)
			// The one and only place engine state crosses into Create's world.
			flywheel.onEngineOutputChanged();

		playTransitionSounds(phaseBefore, startProgressBefore);

		// Anything the client displays has to trigger a block update, not just the
		// things that change the engine's rotation. Toggling redstone on a stopped
		// engine changes no speed and no phase, so without this the client would
		// keep showing the ignition state it was last told about.
		if (generatedSpeedChanged || signalBefore != redstoneSignal || phaseBefore != engine.getPhase()
			|| structureValidBefore != engine.isStructureValid()
			|| startProgressBefore != engine.getStartProgress() || fuelBefore != engine.isFuelAvailable()
			|| lubricationBefore != engine.getLubrication()) {
			sync();
		} else if (engine.getMechanicalRpm() != 0.0F && --resyncCountdown <= 0) {
			resyncCountdown = RESYNC_INTERVAL;
			sync();
		}

		// The snapshot is valid only for the tick that took it. Dropping it here is
		// what guarantees no block entity reference is ever held across ticks.
		tickComponents = null;
	}

	/**
	 * Drops the cached flywheel coupling. Called from {@code neighborChanged} and
	 * by the cylinder when its piston assembly is installed or removed.
	 *
	 * <p>Nothing else needs invalidating: components are resolved fresh every tick
	 * and every time the overlay asks, so placing or removing a Carburetor, an Oil
	 * Sump or a Cylinder is picked up on the next tick with nothing to invalidate
	 * and no stale block entity to hold.
	 */
	public void onSurroundingsChanged() {
		cachedFlywheel = null;
		flywheelPos = null;
	}

	/** Called from {@code CrankshaftBlock#onRemove} before the block entity dies. */
	public void onEngineRemoved() {
		BlockPos previousFlywheel = flywheelPos;
		tickComponents = null;
		engine.setPhase(EnginePhase.STOPPED);
		engine.setSimulatedRpm(0.0F);
		engine.setPublishedRpm(0.0F);
		flywheelPos = null;
		cachedFlywheel = null;
		if (previousFlywheel != null && level != null && !level.isClientSide && level.isLoaded(previousFlywheel)
			&& level.getBlockEntity(previousFlywheel) instanceof EngineFlywheelBlockEntity flywheel)
			flywheel.onEngineOutputChanged();
	}

	/**
	 * Safety net. Create normally hands the kinetic source back to us on its own
	 * ({@code GeneratingKineticBlockEntity#removeSource} sets reActivateSource), but
	 * if a running engine ever ends up generating power the network does not
	 * reflect, re-assert it instead of waiting for Create's 60-tick validation.
	 */
	private void reassertKineticSourceIfNeeded(@Nullable EngineFlywheelBlockEntity flywheel) {
		if (flywheel != null && engine.getPublishedRpm() != 0.0F && !flywheel.hasSource()
			&& flywheel.getTheoreticalSpeed() == 0.0F)
			flywheel.onEngineOutputChanged();
	}

	// --- audio --------------------------------------------------------------

	/**
	 * Plays the one-shot sounds, driven by the transitions the simulation just
	 * made rather than by any timer of their own.
	 *
	 * <p>Server side only, and always through {@code Level#playSound} with a null
	 * player, which broadcasts to everyone in range. Deriving the sounds from the
	 * authoritative state means there is nothing to keep in step: a firing attempt
	 * is audible exactly when start progress advanced, and no extra packet, event
	 * or client-side guess is involved. It also makes double-playing impossible,
	 * because the client never independently decides any of this.
	 */
	private void playTransitionSounds(EnginePhase phaseBefore, int startProgressBefore) {
		if (level == null)
			return;
		EnginePhase phase = engine.getPhase();

		// One cough per banked firing opportunity. The final cycle is deliberately
		// not included: it becomes the catch instead, so starting reads as
		// "puff, puff, BRUMM" rather than "puff, puff, puff+BRUMM".
		if (engine.getStartProgress() > startProgressBefore)
			playSound(ECSounds.ENGINE_FIRE_ATTEMPT.get(), EngineTuning.SOUND_FIRE_ATTEMPT_VOLUME,
				EngineTuning.mapMechanicalRpmToCrankingPitch(engine.getMechanicalRpm()));

		// The catch. Only on the transition, so it can never repeat while running.
		if (phaseBefore == EnginePhase.STARTING && phase == EnginePhase.RUNNING)
			playSound(ECSounds.ENGINE_START.get(), EngineTuning.SOUND_START_VOLUME, 1.0F);

		// Only a running engine can stop; a start attempt that is simply abandoned
		// stays silent, and an engine loading in stopped never transitions at all.
		if (phaseBefore.generatesPower() && phase == EnginePhase.STOPPED) {
			boolean wantedToRun = engine.isIgnitionEnabled();
			playSound(wantedToRun ? ECSounds.ENGINE_STALL.get() : ECSounds.ENGINE_STOP.get(),
				wantedToRun ? EngineTuning.SOUND_STALL_VOLUME : EngineTuning.SOUND_STOP_VOLUME, 1.0F);
		}
	}

	private void playSound(SoundEvent event, float volume, float pitch) {
		if (level == null || level.isClientSide)
			return;
		level.playSound(null, worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D,
			event, SoundSource.BLOCKS, volume, pitch);
	}

	/**
	 * Keeps this engine's continuous sound alive for one more tick.
	 *
	 * <p>{@code @OnlyIn(Dist.CLIENT)} is what keeps the client sound classes off a
	 * dedicated server: the method and its references are stripped there, so
	 * nothing can accidentally load {@code Minecraft}. Create's steam whistle
	 * handles its audio the same way.
	 *
	 * <p>Everything it needs is already synchronised - the phase travels in the
	 * block entity's update tag, and the speed comes from Create's own kinetic
	 * sync via the flywheel - so no packet exists purely for sound.
	 */
	@OnlyIn(Dist.CLIENT)
	private void tickAudio() {
		if (level instanceof ClientLevel clientLevel)
			EngineSoundManager.tick(clientLevel, worldPosition, engine.getPhase(), engine.getMechanicalRpm(),
				engine.getLubrication());
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

		BlockPos candidate = EngineComponents.findFlywheelPos(level, worldPosition, getAxis());
		if (candidate == null)
			return null;
		if (!(level.getBlockEntity(candidate) instanceof EngineFlywheelBlockEntity flywheel))
			return null;

		cachedFlywheel = flywheel;
		flywheelPos = candidate;
		return flywheel;
	}

	/**
	 * This engine's components, resolved against the world right now.
	 *
	 * <p>The single entry point every subsystem uses - combustion, fuel draw,
	 * lubrication, the overlay and the diagnostics. During a server tick it returns
	 * the snapshot taken at the top of that tick, so everything the simulation does
	 * in one tick sees one consistent set of parts; anywhere else (notably the
	 * client-side goggle overlay) it resolves fresh.
	 *
	 * <p>It is deliberately side-agnostic. The overlay renders on the client and
	 * used to consult a server-only field, which is how a running engine could
	 * report "No Carburetor" while burning fuel from one.
	 */
	public EngineComponents components() {
		return tickComponents != null ? tickComponents : resolveComponents();
	}

	/**
	 * Always a fresh look at the world. Never cached beyond the tick that asked,
	 * so no block entity reference here can outlive the block it belongs to.
	 */
	private EngineComponents resolveComponents() {
		BlockPos pos = worldPosition;
		return level == null
			? new EngineComponents(pos, getAxis(), EngineComponents.cylinderPos(pos), null, null, null,
				EngineComponents.carburetorPos(pos), null, EngineComponents.oilSumpPos(pos), null)
			: EngineComponents.resolve(level, pos, getAxis());
	}

	/**
	 * The carburetor attached to this engine, or null when it is missing. Read
	 * through the shared resolver so the position rule stays in one place.
	 */
	@Nullable
	public CarburetorBlockEntity getCarburetor() {
		return components().carburetor();
	}

	/** The oil sump attached to this engine, or null when it is missing. */
	@Nullable
	public OilSumpBlockEntity getOilSump() {
		return components().oilSump();
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
		BlockPos cylinderPos = EngineComponents.cylinderPos(worldPosition);
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
		engine.setStartAttempt(tag.getInt(KEY_START_PROGRESS), tag.getInt(KEY_START_REQUIRED));
		engine.setFuelAvailable(tag.getBoolean(KEY_FUEL_AVAILABLE));
		engine.setLubrication(LubricationState.byId(tag.getString(KEY_LUBRICATION)));
		// Persisted so a chunk reload does not hand the player free oil by
		// discarding the revolutions already banked towards the next draw.
		engine.setCombustionEventsSinceOilDraw(tag.getInt(KEY_OIL_WEAR));
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
		tag.putInt(KEY_START_PROGRESS, engine.getStartProgress());
		tag.putInt(KEY_START_REQUIRED, engine.getRequiredStartCycles());
		tag.putBoolean(KEY_FUEL_AVAILABLE, engine.isFuelAvailable());
		tag.putString(KEY_LUBRICATION, engine.getLubrication()
			.getId());
		tag.putInt(KEY_OIL_WEAR, engine.getCombustionEventsSinceOilDraw());
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

	// --- goggle overlay -----------------------------------------------------

	/**
	 * Built with catnip's LangBuilder rather than raw Components, so it lays out
	 * exactly like Create's own overlays - including the indentation that leaves
	 * room for the icon.
	 *
	 * <p>Normal goggles show gameplay state. Sneaking adds the diagnostics that
	 * used to clutter the overlay permanently.
	 */
	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		EnginePhase phase = engine.getPhase();

		ECLang.translate("gui.engine")
			.style(ChatFormatting.WHITE)
			.forGoggles(tooltip);

		// A coasting engine that has run dry reads as "Stalling" - same phase, but
		// the player cares about the reason, not the internal name.
		String phaseKey = phase == EnginePhase.COASTING && !engine.isFuelAvailable()
			? "gui.phase.stalling"
			: phase.translationKey();
		ECLang.translate("gui.state", ECLang.translate(phaseKey)
			.style(phaseColor(phase))
			.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		ECLang.translate("gui.speed", ECLang.number(engine.getMechanicalRpm())
			.style(ChatFormatting.AQUA)
			.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		boolean ignition = engine.isIgnitionEnabled();
		ECLang.translate("gui.ignition",
			ECLang.translate(ignition ? "gui.value.enabled" : "gui.value.disabled")
				.style(ignition ? ChatFormatting.GREEN : ChatFormatting.RED)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		// One resolution for the whole overlay, and the same call the simulation
		// makes. If combustion can draw from a Carburetor, these lines describe that
		// same Carburetor - they cannot disagree.
		EngineComponents components = components();
		addFuelLines(tooltip, components.carburetor());
		addLubricationLines(tooltip, components.oilSump());

		if (phase == EnginePhase.STARTING)
			ECLang.translate("gui.start_progress",
				ECLang.number(engine.getStartProgress())
					.style(ChatFormatting.GOLD)
					.component(),
				ECLang.number(engine.getRequiredStartCycles())
					.style(ChatFormatting.DARK_GRAY)
					.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);

		if (isPlayerSneaking)
			addDiagnostics(tooltip);
		return true;
	}

	/**
	 * Fuel, distinguishing the three cases the player actually needs told apart:
	 * no Carburetor at all, a Carburetor that is empty, and one with fuel in it.
	 *
	 * <p>"No Carburetor" now means precisely that the component is absent. It used
	 * to also appear whenever the overlay simply could not see one, which is how it
	 * could contradict an engine that was burning fuel.
	 */
	private void addFuelLines(List<Component> tooltip, @Nullable CarburetorBlockEntity carburetor) {
		if (carburetor == null) {
			ECLang.translate("gui.fuel",
				ECLang.translate("gui.value.no_carburetor")
					.style(ChatFormatting.RED)
					.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
			return;
		}

		FluidStack fluid = carburetor.getFluid();
		boolean usable = carburetor.holdsValidFuel();
		ECLang.translate("gui.fuel", (fluid.isEmpty()
			? ECLang.translate("gui.value.empty")
				.style(ChatFormatting.RED)
			: ECLang.builder()
				.add(fluid.getHoverName()
					.copy())
				.style(usable ? ChatFormatting.GREEN : ChatFormatting.RED)).component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		// Shown whenever the tank exists, including at zero - an installed but empty
		// Carburetor reads "Empty" and "0 / 1000 mB", which is different information
		// from having no Carburetor.
		ECLang.translate("gui.fuel_level", ECLang.number(fluid.getAmount())
			.style(fluid.isEmpty() ? ChatFormatting.RED : ChatFormatting.AQUA)
			.component(),
			ECLang.number(carburetor.getCapacity())
				.style(ChatFormatting.DARK_GRAY)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
	}

	/**
	 * Lubrication state, and how much oil is left.
	 *
	 * <p>The state line is always shown - a dry engine is the single most useful
	 * thing the overlay can tell a player about why it will not pull. The quantity
	 * line is skipped when there is no sump, because "0 mB" would imply a tank
	 * that exists.
	 */
	private void addLubricationLines(List<Component> tooltip, @Nullable OilSumpBlockEntity sump) {
		LubricationState lubrication = sump == null ? LubricationState.DRY : sump.getLubricationState();

		ECLang.translate("gui.lubrication", ECLang.translate(lubrication.translationKey())
			.style(OilSumpBlockEntity.lubricationColor(lubrication))
			.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		if (sump == null) {
			ECLang.translate("gui.oil", ECLang.translate("gui.value.no_oil_sump")
				.style(ChatFormatting.RED)
				.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
			return;
		}

		int oil = sump.getOilAmount();
		if (oil <= 0) {
			ECLang.translate("gui.oil", ECLang.translate("gui.value.empty")
				.style(ChatFormatting.RED)
				.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
			return;
		}

		ECLang.translate("gui.oil_level", ECLang.number(oil)
			.style(lubrication == LubricationState.LOW ? ChatFormatting.GOLD : ChatFormatting.AQUA)
			.component(),
			ECLang.number(sump.getCapacity())
				.style(ChatFormatting.DARK_GRAY)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
	}

	/** Sneak-only diagnostics, so the normal overlay stays readable. */
	private void addDiagnostics(List<Component> tooltip) {
		ECLang.translate("gui.diagnostics")
			.style(ChatFormatting.DARK_GRAY)
			.forGoggles(tooltip);

		// Resolved from the world rather than read from the synced simulation flag,
		// so this line answers "is the engine assembled correctly right now" using
		// the same rule the server uses to decide whether it may run.
		boolean valid = components().isMechanicallyValid();
		diagnostic(tooltip, "structure", ECLang
			.translate(valid ? "gui.value.valid" : "gui.value.invalid")
			.style(valid ? ChatFormatting.GREEN : ChatFormatting.RED));
		diagnostic(tooltip, "rotation_source", ECLang.translate(engine.getRotationSource()
			.translationKey())
			.style(ChatFormatting.WHITE));
		diagnostic(tooltip, "redstone_signal", ECLang.number(redstoneSignal)
			.style(redstoneSignal > 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
		diagnostic(tooltip, "crank_angle", ECLang.number(engine.getCrankAngleDegrees())
			.style(ChatFormatting.AQUA));
		diagnostic(tooltip, "simulated_rpm", ECLang.number(engine.getSimulatedRpm())
			.style(ChatFormatting.AQUA));
		diagnostic(tooltip, "generated_rpm", ECLang.number(engine.getPublishedRpm())
			.style(ChatFormatting.AQUA));
	}

	private static void diagnostic(List<Component> tooltip, String key, LangBuilder value) {
		ECLang.translate("gui." + key, value.component())
			.style(ChatFormatting.DARK_GRAY)
			.forGoggles(tooltip, 1);
	}

	private static ChatFormatting phaseColor(EnginePhase phase) {
		return switch (phase) {
			case RUNNING -> ChatFormatting.GREEN;
			case STARTING, CRANKING, COASTING -> ChatFormatting.GOLD;
			case STOPPED -> ChatFormatting.RED;
		};
	}

	/** The engine's own component, so the overlay reads as part of this machine. */
	@Override
	public ItemStack getIcon(boolean isPlayerSneaking) {
		return new ItemStack(ECItems.CRANKSHAFT.get());
	}

	/** Chat report for the right-click debug path; plain text, works server-side. */
	public void sendDebugReport(Player player) {
		player.displayClientMessage(ECLang.translate("gui.engine")
			.style(ChatFormatting.GOLD)
			.component(), false);
		player.displayClientMessage(Component.literal(String.format(
			"phase=%s  mech=%.1f  sim=%.1f  gen=%.1f  angle=%.1f  redstone=%d  fuel=%s  start=%d/%d",
			engine.getPhase(), engine.getMechanicalRpm(), engine.getSimulatedRpm(), engine.getPublishedRpm(),
			engine.getCrankAngleDegrees(), redstoneSignal, engine.isFuelAvailable(), engine.getStartProgress(),
			engine.getRequiredStartCycles()))
			.withStyle(ChatFormatting.GRAY), false);
	}
}
