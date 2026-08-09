package dev.engineeredcombustion.content.engine.crankshaft;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.kinetics.base.IRotate.StressImpact;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;

import dev.engineeredcombustion.client.sound.EngineSoundManager;
import dev.engineeredcombustion.content.engine.EngineComponents;
import dev.engineeredcombustion.content.engine.EngineInputs;
import dev.engineeredcombustion.content.engine.EnginePhase;
import dev.engineeredcombustion.content.engine.EngineState;
import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.FuelSupply;
import dev.engineeredcombustion.content.engine.LubricationState;
import dev.engineeredcombustion.content.engine.OilSupply;
import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlockEntity;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlockEntity;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;
import dev.engineeredcombustion.content.engine.sump.OilSumpBlockEntity;
import dev.engineeredcombustion.foundation.ECLang;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECItems;
import dev.engineeredcombustion.registry.ECSounds;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Engine controller, host of the authoritative engine simulation, and the
 * kinetic relay that puts a working shaft output on <i>both</i> ends of the
 * crankshaft.
 *
 * <p>Per tick, on both sides:
 * <ol>
 * <li>read the crankshaft's own <i>actual</i> Create kinetic speed;</li>
 * <li>advance the crank angle by exactly that much.</li>
 * </ol>
 * Additionally on the server:
 * <ol start="3">
 * <li>resolve the engine's components, the redstone ignition signal, the
 * carburetor's throttle setting and the network's load;</li>
 * <li>run combustion, inertia and friction;</li>
 * <li>if - and only if - the speed the engine wants to generate changed, tell
 * the flywheel to push it into Create.</li>
 * </ol>
 *
 * <p>Because step 1 and 2 use a value Create already synchronises, client and
 * server derive the same crank angle from the same input without this mod
 * sending a packet per tick. Everything visible (piston, flywheel disc, attached
 * shafts on either end) therefore agrees by construction.
 *
 * <h2>Kinetics: one source, two shaft faces</h2>
 * This block entity is a plain {@link KineticBlockEntity}. It never generates -
 * {@code getGeneratedSpeed()} is inherited as 0 - and it carries neither stress
 * impact nor stress capacity. The engine's single kinetic source is still
 * {@link EngineFlywheelBlockEntity}, which is the only
 * {@code GeneratingKineticBlockEntity} in the mod.
 *
 * <p>What being kinetic buys is connectivity. The crankshaft sits adjacent to
 * the flywheel along a shared axis, so {@code RotationPropagator} treats the two
 * as a 1:1 axis connection and puts them in one network at one speed; and
 * because {@code CrankshaftBlock} now reports {@code hasShaftTowards} on both
 * ends of that axis, a Shaft on the far side from the flywheel joins the very
 * same network. Two faces, one source, one stress budget, no duplicated power.
 */
public class CrankshaftBlockEntity extends KineticBlockEntity {

	/** Crank-angle resync interval while turning, in ticks. */
	private static final int RESYNC_INTERVAL = 200;

	/**
	 * The spark plug electrode, in the Cylinder block's own coordinates. Must
	 * match {@code SPARK_PLUG_ELECTRODE} in {@code tools/generate_engine_models.py}
	 * - it is the point that model puts the electrode tip at, and a spark that
	 * misses it would be worse than no spark at all.
	 */
	private static final Vec3 SPARK_PLUG_ELECTRODE = new Vec3(13.2D / 16.0D, 13.75D / 16.0D, 8.0D / 16.0D);

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
	 * synchronised to the client purely so the sneak diagnostics can show it - the
	 * goggle overlay runs client-side and has no other way to know.
	 */
	private int redstoneSignal;

	/**
	 * Components resolved at the top of the current server tick, or null outside
	 * one. Never survives the tick that set it - see {@link #engineComponents()}.
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

	@Override
	public void tick() {
		// Create's own kinetic bookkeeping first: attaching to the network,
		// periodic validation, and the stress plumbing. Everything below reads the
		// speed that leaves behind.
		super.tick();
		if (level == null)
			return;

		// The crankshaft's *own* kinetic speed, now that it is a real member of the
		// network. Identical to the flywheel's while the two are coupled - they are
		// a 1:1 axis connection - but this also stays correct when the engine is
		// driven from a Shaft on the crankshaft's far side, and when there is no
		// flywheel at all.
		float mechanicalRpm = getSpeed();
		engine.advanceCrankAngle(mechanicalRpm);

		if (level.isClientSide) {
			engine.updateClientVisuals();
			tickEngineAudio();
			return;
		}

		EngineFlywheelBlockEntity flywheel = getFlywheel();

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

		EngineInputs inputs = new EngineInputs(tickComponents.isMechanicallyValid(), redstoneSignal > 0,
			flywheel != null && flywheel.hasSource(), readThrottle(), readLoadFactor(), speedLimit());
		boolean generatedSpeedChanged = engine.tickSimulation(inputs, fuelSupply, oilSupply, random);

		if (generatedSpeedChanged && flywheel != null)
			// The one and only place engine state crosses into Create's world.
			flywheel.onEngineOutputChanged();

		emitCombustionEffects();
		playTransitionSounds(phaseBefore, startProgressBefore);
		updateIgnitionIndicator();

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
	 * The engine makes its own noise; Create's generic kinetic hum on top of it
	 * would just muddy the loop this mod already manages.
	 */
	@Override
	protected boolean isNoisy() {
		return false;
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

	// --- simulation inputs ---------------------------------------------------

	/**
	 * Main throttle opening, {@code [0, 1]}, taken from the Carburetor's scroll
	 * value.
	 *
	 * <p>An engine with no Carburetor reads 0. That is not a special case worth
	 * worrying about: no Carburetor also means no fuel, so such an engine cannot
	 * run under its own power at any throttle.
	 */
	private float readThrottle() {
		CarburetorBlockEntity carburetor = getCarburetor();
		return carburetor == null ? 0.0F : carburetor.getThrottle();
	}

	/**
	 * How hard the kinetic network is leaning on the engine, as stress over
	 * capacity in {@code [0, 1]}.
	 *
	 * <p>Both figures come from Create's own network bookkeeping, pushed into
	 * every member by {@code KineticNetwork#updateFromNetwork}. They each scale
	 * with speed - Create multiplies the registered per-RPM values by the actual
	 * speed - so the <i>ratio</i> is speed-independent and feeding it back into
	 * the engine's drag cannot run away.
	 *
	 * <p>Zero while Create's stress system is switched off, so a server that
	 * disables stress gets an engine that ignores load, which is the consistent
	 * answer.
	 */
	private float readLoadFactor() {
		if (!StressImpact.isEnabled() || !hasNetwork() || capacity <= 0.0F)
			return 0.0F;
		return Math.min(1.0F, stress / capacity);
	}

	/**
	 * Highest speed this engine may run at or publish.
	 *
	 * <p>Read from Create's live server config rather than assumed: exceeding
	 * {@code maxRotationSpeed} does not merely fail, it makes
	 * {@code RotationPropagator} <i>destroy the block</i>. The config's default is
	 * 256 but its minimum is 64, well below this engine's full-throttle target, so
	 * a server that lowers it has to cap the engine rather than break it.
	 */
	private float speedLimit() {
		return Math.min(EngineTuning.MAX_RPM, AllConfigs.server().kinetics.maxRotationSpeed.get());
	}

	// --- combustion feedback -------------------------------------------------

	/**
	 * Emits the visible spark at the plug when the coil actually fired.
	 *
	 * <p>Server-driven and event-driven: {@code EngineState} sets the flag on the
	 * tick the firing angle is crossed with ignition live, and this consumes it,
	 * so a spark corresponds one-to-one with a real ignition event and never to an
	 * animation timer. One particle per firing - at full throttle that is 3.2 a
	 * second, which is the whole cost of the effect.
	 *
	 * <p>The combustion <i>flash</i> is not sent from here. It is re-derived on
	 * the client from the same authoritative crank angle - see
	 * {@link EngineState#updateClientVisuals()} - so it costs no packets at all.
	 */
	private void emitCombustionEffects() {
		if (!engine.consumeSparkEvent() || !(level instanceof ServerLevel serverLevel))
			return;
		BlockPos cylinderPos = EngineComponents.cylinderPos(worldPosition);
		serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK, cylinderPos.getX() + SPARK_PLUG_ELECTRODE.x,
			cylinderPos.getY() + SPARK_PLUG_ELECTRODE.y, cylinderPos.getZ() + SPARK_PLUG_ELECTRODE.z, 1, 0.0D, 0.0D,
			0.0D, 0.0D);
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

	/**
	 * Keeps the crankcase indicator lamp in step with the ignition signal.
	 *
	 * <p>Only writes when the value actually differs, so a running engine is not
	 * re-meshing its chunk every tick. {@code UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE}
	 * is used deliberately: this is a cosmetic property, so a full neighbour
	 * update would make every ignition toggle ripple through the redstone graph
	 * for nothing, and skipping the shape update keeps it away from
	 * {@code KineticBlock#updateIndirectNeighbourShapes} - which clears kinetic
	 * information - now that this block is on a kinetic network.
	 *
	 * <p>Safe against the block's own teardown - {@code onRemove} only reacts when
	 * the block itself changes, and this keeps the same block.
	 */
	private void updateIgnitionIndicator() {
		if (level == null || level.isClientSide)
			return;
		BlockState state = getBlockState();
		if (!state.hasProperty(CrankshaftBlock.LIT))
			return;
		boolean ignition = engine.isIgnitionEnabled();
		if (state.getValue(CrankshaftBlock.LIT) == ignition)
			return;
		level.setBlock(worldPosition, state.setValue(CrankshaftBlock.LIT, ignition),
			Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
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
	 * <p>Named apart from {@code KineticBlockEntity#tickAudio}, which is Create's
	 * own generic kinetic ambience and is suppressed here by {@link #isNoisy()}.
	 *
	 * <p>Everything it needs is already synchronised - the phase travels in the
	 * block entity's update tag, and the speed comes from Create's own kinetic
	 * sync - so no packet exists purely for sound.
	 */
	@OnlyIn(Dist.CLIENT)
	private void tickEngineAudio() {
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
	 *
	 * <p>Named {@code engineComponents} rather than {@code components} because
	 * {@code BlockEntity#components()} already exists and returns a
	 * {@code DataComponentMap} - the plain name would be an override clash.
	 */
	public EngineComponents engineComponents() {
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
		return engineComponents().carburetor();
	}

	/** The oil sump attached to this engine, or null when it is missing. */
	@Nullable
	public OilSumpBlockEntity getOilSump() {
		return engineComponents().oilSump();
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
	//
	// SmartBlockEntity makes saveAdditional/loadAdditional final and routes both
	// through read/write, so those are the hooks now. The update tag and update
	// packet come from SyncedBlockEntity for free.

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
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
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
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

	private void sync() {
		notifyUpdate();
	}

	// --- goggle overlay -----------------------------------------------------

	/**
	 * Built with catnip's LangBuilder rather than raw Components, so it lays out
	 * exactly like Create's own overlays - including the indentation that leaves
	 * room for the icon.
	 *
	 * <p>Normal goggles show gameplay state. Sneaking adds the diagnostics that
	 * used to clutter the overlay permanently.
	 *
	 * <p>{@code KineticBlockEntity}'s own stress block is deliberately not called:
	 * the crankshaft is a relay with no impact and no capacity, so it would print
	 * nothing, and the engine's real generator stats belong to the Flywheel.
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
		EngineComponents components = engineComponents();
		addThrottleLine(tooltip, components.carburetor());
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
	 * Throttle, read straight off the Carburetor so it is the same number the
	 * simulation used and the same number the lever on the model is showing.
	 * Skipped entirely when there is no Carburetor - a throttle reading for a
	 * control that is not installed would be noise.
	 */
	private void addThrottleLine(List<Component> tooltip, @Nullable CarburetorBlockEntity carburetor) {
		if (carburetor == null)
			return;
		ECLang.translate("gui.throttle", ECLang.number(carburetor.getThrottlePercent())
			.style(ChatFormatting.AQUA)
			.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
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
		boolean valid = engineComponents().isMechanicallyValid();
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
		// Derived from the Carburetor rather than from the simulation's own copy of
		// the throttle: that copy is only ever written on the server, so reading it
		// here - the overlay is client-side - would always have printed idle. The
		// Carburetor's value is synchronised because Create's scroll behaviour
		// synchronises it, so this is the same number the engine is actually using.
		CarburetorBlockEntity carburetor = engineComponents().carburetor();
		float throttle = carburetor == null ? 0.0F : carburetor.getThrottle();
		diagnostic(tooltip, "target_rpm", ECLang.number(EngineTuning.targetRpmForThrottle(throttle))
			.style(ChatFormatting.AQUA));
		diagnostic(tooltip, "generated_rpm", ECLang.number(engine.getPublishedRpm())
			.style(ChatFormatting.AQUA));
		// Network load is deliberately absent. Create already reports stress on the
		// Flywheel, which is this engine's generator, and repeating it here would be
		// the HUD clutter this overlay keeps out of the way behind sneak.
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

	// --- hovering overlay, without goggles ----------------------------------

	/**
	 * What anyone can see by looking at the engine, goggles or not.
	 *
	 * <p>Deliberately only what a person could work out by standing next to a
	 * running engine: whether it is turning, whether it caught, whether it sounds
	 * healthy, whether the ignition is switched on, and roughly where the throttle
	 * lever is sitting. No speeds, no quantities, no counters, and no exact
	 * throttle percentage - those are what the Engineer's Goggles are <i>for</i>,
	 * and handing them out for free would make the goggles pointless.
	 *
	 * <p>Returns false while goggles are worn. Create's overlay renderer calls this
	 * for every player and appends the result <i>after</i> the goggle tooltip, so
	 * without that check a goggle wearer would read the engine's state twice. The
	 * renderer also removes the separator it had already inserted when this returns
	 * false, so declining costs nothing.
	 *
	 * <p>Reaching for the client player here follows Create's own precedent -
	 * {@code KineticBlockEntity#addToTooltip} uses client-only types too. This is
	 * only ever invoked from the overlay renderer, which is client-side by
	 * definition.
	 */
	@Override
	public boolean addToTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		Player player = Minecraft.getInstance().player;
		if (player == null || GogglesItem.isWearingGoggles(player))
			return false;

		ECLang.translate("gui.engine")
			.style(ChatFormatting.WHITE)
			.forGoggles(tooltip);

		EnginePhase phase = engine.getPhase();
		ECLang.translate(observedStateKey(phase))
			.style(phaseColor(phase))
			.forGoggles(tooltip, 1);

		boolean ignition = engine.isIgnitionEnabled();
		ECLang.translate("gui.ignition",
			ECLang.translate(ignition ? "gui.value.enabled" : "gui.value.disabled")
				.style(ignition ? ChatFormatting.GREEN : ChatFormatting.RED)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		CarburetorBlockEntity carburetor = engineComponents().carburetor();
		if (carburetor != null)
			ECLang.translate("gui.throttle_state",
				ECLang.translate(observedThrottleKey(carburetor.getThrottlePercent()))
					.style(ChatFormatting.WHITE)
					.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
		return true;
	}

	/**
	 * A plain-language description of what the engine is audibly and visibly doing.
	 *
	 * <p>Every branch is derived from real simulation state, never guessed. A
	 * running engine is described as rough exactly when lubrication is actually
	 * degraded - which is also when it actually sounds rough - and as stalling
	 * exactly when it has lost combustion and is coasting down.
	 */
	private String observedStateKey(EnginePhase phase) {
		return switch (phase) {
			case RUNNING -> engine.getLubrication() == LubricationState.NORMAL
				? "gui.observed.running_smoothly"
				: "gui.observed.running_rough";
			case COASTING -> "gui.observed.stalling";
			case STARTING -> "gui.observed.starting";
			case CRANKING -> "gui.observed.cranking";
			case STOPPED -> "gui.observed.stopped";
		};
	}

	/**
	 * Throttle as something you could tell by glancing at the lever: three
	 * positions, not a number. The exact percentage stays a goggle reading and a
	 * value-box reading.
	 */
	private static String observedThrottleKey(int throttlePercent) {
		if (throttlePercent <= 15)
			return "gui.observed.throttle_low";
		return throttlePercent >= 70 ? "gui.observed.throttle_high" : "gui.observed.throttle_medium";
	}
}
