package dev.engineeredcombustion.content.engine.crankshaft;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.kinetics.base.IRotate.StressImpact;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.infrastructure.config.AllConfigs;

import dev.engineeredcombustion.client.sound.EngineSoundManager;
import dev.engineeredcombustion.content.engine.CombustionAudio;
import dev.engineeredcombustion.content.engine.EngineComponents;
import dev.engineeredcombustion.content.engine.EngineInputs;
import dev.engineeredcombustion.content.engine.EnginePhase;
import dev.engineeredcombustion.content.engine.EngineState;
import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.FuelSupply;
import dev.engineeredcombustion.content.engine.LubricationState;
import dev.engineeredcombustion.content.engine.OilSupply;
import dev.engineeredcombustion.content.engine.RotationSource;
import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlockEntity;
import dev.engineeredcombustion.content.engine.control.ControlMode;
import dev.engineeredcombustion.content.engine.control.EngineControlState;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlockEntity;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;
import dev.engineeredcombustion.content.engine.sump.OilSumpBlockEntity;
import dev.engineeredcombustion.foundation.ECLang;
import dev.engineeredcombustion.network.EngineCombustionEventsPayload;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECItems;
import dev.engineeredcombustion.registry.ECSounds;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Engine controller, host of the authoritative engine simulation, and the
 * kinetic relay that puts a working shaft output on <i>both</i> ends of the
 * crankshaft.
 *
 * <p>Per tick, on both sides ({@link #tickRotation()}):
 * <ol>
 * <li>read the crankshaft's own <i>actual</i> Create kinetic speed, and whether
 * Create is holding the shaft at it;</li>
 * <li>reconcile the engine's momentum with that - absorbing an externally
 * imposed speed, or free-running on stored momentum when nothing is driving the
 * shaft;</li>
 * <li>advance the crank angle by whatever the crank is really turning at.</li>
 * </ol>
 * Additionally on the server:
 * <ol start="4">
 * <li>resolve the engine's components and, in one place, its control inputs -
 * see {@link #resolveControlState()} - plus the network's load;</li>
 * <li>run combustion, inertia and friction, and decide - once - whether the
 * engine is actively generating;</li>
 * <li>if - and only if - the speed the engine wants to generate changed, tell
 * the flywheel to push it into Create.</li>
 * </ol>
 *
 * <p>Steps 1 to 3 read only values Create already synchronises, so client and
 * server derive the same crank angle from the same input without this mod
 * sending a packet per tick. Everything visible (piston, flywheel disc, attached
 * shafts on either end) therefore agrees by construction. The one exception is a
 * freewheeling engine, which has no Create speed left to read: both sides
 * integrate the same deterministic spin-down, and the server confirms it every
 * {@link #COAST_RESYNC_INTERVAL} ticks.
 *
 * <h2>Two state systems, one reconciliation</h2>
 * A world save writes the engine twice. Create persists its own {@code Speed},
 * {@code Source} and {@code Network} for every kinetic block, cached Stress
 * Capacity included; this mod persists the engine's physical state. Neither can
 * be allowed to win by loading first.
 *
 * <p>The rule is that the <b>simulation is authoritative and Create's kinetic
 * speed is its published representation</b>. So loading restores only the
 * engine's own physics - crank angle, signed angular velocity, phase, controls -
 * and raises {@link #needsPostLoadReconcile}; the first server tick that can
 * actually see the engine's blocks re-derives generation from the world and
 * force-publishes the result through {@link #reconcileAfterLoad}, whatever Create
 * came back holding. Nothing touches the kinetic network from inside
 * {@link #read}.
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
	 * Resync interval while the engine is freewheeling, in ticks.
	 *
	 * <p>Much shorter, because this is the only state in which the client is
	 * integrating the engine's rotation rather than reading a speed Create already
	 * synchronises for it. A whole spin-down is about eight seconds, so this costs
	 * on the order of eight block-entity updates per shutdown - and it means any
	 * divergence at all is corrected long before it could become visible.
	 */
	private static final int COAST_RESYNC_INTERVAL = 20;

	/**
	 * The spark gap, in the Cylinder block's own coordinates: between the centre
	 * electrode's tip and the ground strap under it, inside the combustion
	 * chamber. Must match {@code SPARK_PLUG_ELECTRODE} in
	 * {@code tools/generate_engine_models.py} - it is the point that model leaves
	 * the gap at, and a spark that misses it would be worse than no spark at all.
	 */
	private static final Vec3 SPARK_PLUG_ELECTRODE = new Vec3(11.90D / 16.0D, 13.79D / 16.0D, 8.0D / 16.0D);

	private static final String KEY_CRANK_ANGLE = "CrankAngle";
	private static final String KEY_PHASE = "Phase";
	private static final String KEY_SIMULATED_RPM = "SimulatedRpm";
	private static final String KEY_PUBLISHED_RPM = "PublishedRpm";
	private static final String KEY_IGNITION = "Ignition";
	private static final String KEY_MANUAL_IGNITION = "ManualIgnition";
	private static final String KEY_CONTROL_MODULE = "ControlModule";
	private static final String KEY_STRUCTURE_VALID = "StructureValid";
	private static final String KEY_REDSTONE_SIGNAL = "RedstoneSignal";
	private static final String KEY_START_PROGRESS = "StartProgress";
	private static final String KEY_START_REQUIRED = "StartRequired";
	private static final String KEY_FUEL_AVAILABLE = "FuelAvailable";
	private static final String KEY_SPARK_PLUG = "SparkPlug";
	private static final String KEY_LUBRICATION = "Lubrication";
	private static final String KEY_OIL_WEAR = "OilWear";
	private static final String KEY_SPARK_EVENT = "SparkEvent";
	private static final String KEY_COMBUSTION_EVENT = "CombustionEvent";
	private static final String KEY_GENERATING = "Generating";
	private static final String KEY_COMBUSTION_AGE = "TicksSinceCombustion";
	private static final String KEY_CYLINDER_INDEX = "CylinderIndex";
	private static final String KEY_CYLINDER_COUNT = "CylinderCount";
	private static final String KEY_SPARK_PLUG_MASK = "SparkPlugMask";
	private static final String KEY_OVERSIZED = "Oversized";

	private final EngineState engine = new EngineState();

	/**
	 * Picks how many firing cycles a start attempt needs. Lives on the block
	 * entity rather than coming from the level so it can never be evaluated
	 * client-side - tickSimulation only ever runs on the server.
	 */
	private final java.util.Random random = new java.util.Random();

	/**
	 * Strongest redstone signal reaching the crankshaft, 0-15, or <b>0 whenever no
	 * mode that reads redstone is selected</b>. Server-authoritative, synchronised
	 * to the client so the overlay can show it - the overlay runs client-side and
	 * has no other way to know.
	 *
	 * <p>Held at 0 rather than merely ignored, so that "is redstone doing anything
	 * to this engine" is answerable from one field: see
	 * {@link #readRedstoneSignal()}.
	 */
	private int redstoneSignal;

	/**
	 * Position of the ignition switch on the crankcase. This is the player's
	 * setting, not the engine's state: in a redstone ignition mode the engine may
	 * be running with this false, and it survives such a mode unchanged, which is
	 * what makes pulling the module out predictable.
	 *
	 * <p><b>A new engine comes with the switch on.</b> The switch is not a start
	 * button - leaving it on starts nothing, because the engine still needs a valid
	 * structure, a Spark Plug, gasoline and several successful cranking cycles
	 * before it can catch - so the only thing an off-by-default switch achieved was
	 * one mandatory click on every engine a player ever built. Building the machine
	 * and cranking it is now the whole of starting it.
	 *
	 * <p>Off is still a real, sticky choice: a switch the player turned off stays
	 * off, across chunk reloads and restarts, because {@link #read} only overwrites
	 * this when the tag it is loading actually carries the key. That is what keeps
	 * the default a property of <i>new</i> engines rather than of every engine that
	 * happens to be loading.
	 */
	private boolean manualIgnition = true;

	/** Whether a Redstone Control Module is plugged into the engine's controls. */
	private boolean controlModuleInstalled;

	/**
	 * Turns this engine's combustion events into sound, and measures how often they
	 * are arriving.
	 *
	 * <p>Only ever driven from client-side code paths, but deliberately built out of
	 * dist-neutral types so that holding one costs a dedicated server nothing: it
	 * touches {@code Level}, {@code BlockPos} and the sound registry and nothing
	 * else, and {@code Level#playLocalSound} is already an empty method outside the
	 * client.
	 */
	private final CombustionAudio combustionAudio = new CombustionAudio();

	/**
	 * What redstone at this engine is allowed to do. Only consulted while a
	 * Redstone Control Module is installed - see {@link #getControlMode()} - and
	 * only editable then, because the value box is
	 * {@code onlyActiveWhen(module installed)}.
	 *
	 * <p>Create owns its persistence, its packet and its UI. Package-private and
	 * assigned in {@link #addBehaviours}, exactly like the Carburetor's throttle.
	 */
	ScrollOptionBehaviour<ControlMode> controlMode;

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
	 * <p>The position rule itself lives in {@link EngineComponents#findFlywheel}
	 * - this only caches its answer.
	 */
	@Nullable
	private BlockPos flywheelPos;
	@Nullable
	private EngineFlywheelBlockEntity cachedFlywheel;

	private int resyncCountdown = RESYNC_INTERVAL;

	/**
	 * Set when this engine's state came off disk, and cleared by the first server
	 * tick that reconciles it with Create.
	 *
	 * <p><b>Why a flag and not work done in {@code read}.</b> Reading NBT happens
	 * while the chunk is still being assembled: neighbouring block entities may not
	 * exist yet, the level cannot be safely asked about blocks a chunk away, and
	 * nothing that touches the kinetic network belongs there. So loading only
	 * restores the engine's own physical state and records that it has not yet been
	 * squared with Create; {@link #reconcileAfterLoad} - running inside a normal
	 * server tick, with the structure resolved and the simulation live - is what
	 * makes it true.
	 */
	private boolean needsPostLoadReconcile;

	/**
	 * Ticks the reconciliation has spent waiting for the rest of the engine to
	 * load. Bounded by {@link EngineTuning#POST_LOAD_RECONCILE_WAIT_TICKS}.
	 */
	private int postLoadWaitTicks;

	/**
	 * Who was turning the shaft on the previous tick, so a handoff can be noticed.
	 * A change here is a discontinuity, not drift, and the generated speed has to
	 * be republished at once rather than waiting out a reconciliation interval.
	 */
	private boolean wasExternallyDriven;

	/**
	 * This section's place along its engine's crank axis, and how many sections the
	 * engine has.
	 *
	 * <p>Both are re-derived from the world every server tick and synchronised, and
	 * between them they are the whole of what a crankshaft section needs to know
	 * about being part of a bigger engine:
	 * <ul>
	 * <li>{@code cylinderIndex == 0} means this section is the <b>controller</b> -
	 * the one block entity that simulates, owns the master crank angle, holds the
	 * controls and talks to Create;</li>
	 * <li>the index fixes this section's crank phase, so its throw, its piston and
	 * its combustion all happen at
	 * {@code masterCrankAngle + i * 360 / cylinderCount};</li>
	 * <li>and it locates the controller by arithmetic alone -
	 * {@code worldPosition.relative(negative, cylinderIndex)} - so a follower never
	 * has to search for the engine it belongs to, on either side.</li>
	 * </ul>
	 *
	 * <p>Persisted so that the first tick after a world load compares the layout it
	 * derives against the layout the engine actually had, rather than against a
	 * default that would look like the player had just rebuilt the engine.
	 */
	private int cylinderIndex;
	private int cylinderCount = 1;

	/**
	 * Whether this section belongs to a run of more crankcases than
	 * {@link EngineTuning#MAX_CYLINDERS} allows.
	 *
	 * <p>Held here, and not merely computed inside
	 * {@link EngineComponents.Placement}, because it has to <i>disqualify this block
	 * entity</i> rather than just describe the world: an oversized run has no
	 * controller at all, so {@link #isEngineController()} reads this and every
	 * section of such a run declines to simulate. Computing the flag and then
	 * ignoring it is precisely how a five-section run used to split into a working
	 * inline-4 and a stray extra engine.
	 *
	 * <p>Persisted, so a reload does not briefly present an over-long run as a valid
	 * engine before the first tick re-derives it.
	 */
	private boolean oversized;

	/**
	 * Set while the crank run could not be verified because a chunk it passes
	 * through is not loaded.
	 *
	 * <p>Deliberately <b>not</b> persisted: it describes what this server tick could
	 * see, not anything about the engine, and it clears itself as soon as the chunks
	 * come back. While it is set the engine is suspended - no controller, no
	 * combustion, no generated speed and no Stress Capacity - but its stored layout
	 * and every player-configured control are left exactly as they were, because the
	 * one thing that must never happen is a chunk unload quietly re-deriving a
	 * shorter engine out of the part that is still visible.
	 */
	private boolean assemblySuspended;

	/**
	 * Which cylinders have a Spark Plug, as a bitmask. Controller-only state, kept
	 * here purely so it can be synchronised: the client's overlays and its
	 * per-cylinder spark effects need it, and the Cylinder block entities that own
	 * the truth may be blocks the client has not been told about yet.
	 */
	private int sparkPlugMask;

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

	/**
	 * Registers the control-mode selector as a normal Create value box.
	 *
	 * <p>Three deliberate choices, all of them Create's own API rather than a
	 * bespoke screen:
	 * <ul>
	 * <li>{@code onlyActiveWhen} - an engine with no module has no mode to pick, so
	 * the box does not exist for it: it is neither drawn, nor hit-tested, nor
	 * editable ({@code ScrollValueRenderer} and {@code ValueSettingsInputHandler}
	 * both skip inactive behaviours).</li>
	 * <li>{@code requiresWrench} - the switch is operated bare-handed, so the value
	 * box must not swallow that click. Gating it on a wrench keeps the two
	 * interactions apart without either one having to know about the other, and
	 * keeps a configuration widget off the crankcase during normal play.</li>
	 * <li>the sides - the two <i>flanks</i> of the crankcase, which is where the
	 * switch and the tell-tale are. Never the ends of the crank axis: those are
	 * shaft faces, and a value box floating over one would read as belonging to
	 * whatever is bolted there.</li>
	 * </ul>
	 */
	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
		controlMode = new ScrollOptionBehaviour<>(ControlMode.class, ECLang.translate("gui.control_module")
			.component(), this, new CenteredSideValueBoxTransform(CrankshaftBlockEntity::isControlSide));
		controlMode.requiresWrench();
		controlMode.onlyActiveWhen(this::hasControlModule);
		behaviours.add(controlMode);
	}

	/** The crankcase flanks: horizontal, and not along the crank axis. */
	private static boolean isControlSide(BlockState state, Direction direction) {
		if (!state.hasProperty(CrankshaftBlock.HORIZONTAL_AXIS))
			return false;
		return direction.getAxis()
			.isHorizontal()
			&& direction.getAxis() != state.getValue(CrankshaftBlock.HORIZONTAL_AXIS);
	}

	@Override
	public void tick() {
		// Create's own kinetic bookkeeping first: attaching to the network,
		// periodic validation, and the stress plumbing. Everything below reads the
		// speed that leaves behind.
		super.tick();
		if (level == null)
			return;

		// Where this section sits in its engine, re-derived from the world before
		// anything is decided on the strength of it. On the client the same two
		// numbers arrive synchronised.
		if (!level.isClientSide)
			updateEnginePlacement();

		// EVERY section runs Create's kinetic bookkeeping above, and nothing else.
		// A follower has no simulation of its own: it contributes a cylinder to the
		// controller's engine and reads that engine's state back for its throw, its
		// overlay and its controls. That is the difference between an inline-4 and
		// four engines bolted together, and it is enforced here.
		if (!isEngineController())
			return;

		tickRotation();

		if (level.isClientSide) {
			// Spin the flywheel down when nothing on the network is driving it. The
			// client has to integrate this itself - a coasting engine generates
			// nothing, so Create has no speed left to synchronise - and it is safe
			// because the coast is deterministic from a state the server did sync.
			engine.tickClientCoast();
			// Ages the chamber flashes. New ones are started by playCombustionEvents
			// when an event payload arrives, which is a separate path from this tick -
			// a flash that starts this tick is therefore never aged on the tick it
			// started, whichever order the two happen in.
			engine.updateClientVisuals();
			tickEngineAudio();
			return;
		}

		// A change of who is turning the shaft is a discontinuity: the value Create
		// is holding may bear no relation to what the engine is doing, and the
		// correction must not wait out a reconciliation interval.
		if (wasExternallyDriven != engine.isExternallyDriven()) {
			wasExternallyDriven = engine.isExternallyDriven();
			engine.requestGeneratedRepublish();
		}

		// An engine loading in cannot be judged until the blocks it is made of are
		// there to judge. Waiting costs a tick or two on a chunk boundary; not
		// waiting would mean declaring the engine broken - and tearing down its
		// kinetic network - because a neighbour was late.
		if (needsPostLoadReconcile && !engineNeighbourhoodLoaded()
			&& postLoadWaitTicks++ < EngineTuning.POST_LOAD_RECONCILE_WAIT_TICKS)
			return;

		// Resolved once per server tick and held only for the duration of that tick.
		// The fuel and oil supplies read it, so combustion, fuel draw and lubrication
		// all act on one consistent snapshot - and it is the same call the overlay
		// makes, which is what keeps the HUD from ever contradicting the simulation.
		tickComponents = resolveComponents();

		// The crank run was verified above; this is the rest of the engine. A Cylinder,
		// the Flywheel, the Carburetor or the Oil Sump may sit in a chunk that is not
		// loaded, and an engine cannot be judged - or run - against parts nobody can
		// see. Fail closed: no combustion, no fuel or oil drawn, no start progress, and
		// no capacity derived from the fraction of the structure that happens to be
		// visible.
		if (setAssemblySuspended(!tickComponents.chunksLoaded())) {
			tickComponents = null;
			return;
		}

		EngineFlywheelBlockEntity flywheel = tickComponents.flywheel();
		sparkPlugMask = tickComponents.sparkPlugMask();
		// Skipped on the reconciliation tick: that already republishes
		// unconditionally, and from the engine's real state rather than from the
		// provisional value this safety net would push.
		if (!needsPostLoadReconcile)
			reassertKineticSourceIfNeeded(flywheel);

		// Read live every tick, and only in a mode that uses it. Reading here is the
		// only way the state can never go stale, whatever order neighbour updates
		// arrive in.
		int signalBefore = redstoneSignal;
		redstoneSignal = readRedstoneSignal();

		// The one place control inputs become simulation inputs. Everything below -
		// combustion, the tell-tale, the switch model, the overlay - reads this
		// single resolved answer rather than consulting redstone or the carburetor
		// again on its own. The overlay runs the very same resolution client-side,
		// off the same synchronised values, which is why no effective throttle has to
		// travel on the wire.
		EngineControlState control = resolveControlState();

		EnginePhase phaseBefore = engine.getPhase();
		boolean structureValidBefore = engine.isStructureValid();
		int startProgressBefore = engine.getStartProgress();
		boolean fuelBefore = engine.isFuelAvailable();
		boolean sparkPlugBefore = engine.isSparkPlugInstalled();
		LubricationState lubricationBefore = engine.getLubrication();
		int firingBefore = engine.getFiringCylinderCount();
		int[] sparkEventsBefore = engine.copyOfSparkEventIds();
		int[] combustionEventsBefore = engine.copyOfCombustionEventIds();

		boolean generatingBefore = engine.isActivelyGenerating();

		EngineInputs inputs = new EngineInputs(tickComponents.isMechanicallyValid(), control.ignitionEnabled(),
			tickComponents.cylinderCount(), sparkPlugMask, control.throttle(), readLoadFactor(), speedLimit());
		boolean generatedSpeedChanged = engine.tickSimulation(inputs, fuelSupply, oilSupply, random);

		// This tick is the engine's first since the world was loaded, and the
		// simulation above has just re-derived everything from the world: whether the
		// structure is intact, whether there is a plug, fuel and oil, and therefore
		// whether the engine is generating at all. Now - and not in read() - is when
		// Create is told.
		// THREE SEPARATE QUESTIONS, and the reason FIX 1 exists is that they used to
		// be answered by one:
		//
		//   generated speed  - what Create is told this engine turns the network at.
		//   capacity basis   - how many cylinders are genuinely firing, which is the
		//                      multiplier on the Stress Capacity Create caches.
		//   passive load     - the drag a NON-generating engine puts on the network.
		//
		// The first is `generatedSpeedChanged`. The other two both change exactly when
		// `firingBefore` or `generatingBefore` moves, and either can move while the
		// published speed does not: an engine held at a steady speed by another source
		// that loses a Spark Plug changes its capacity basis and nothing else.
		boolean capacityBasisChanged = firingBefore != engine.getFiringCylinderCount()
			|| generatingBefore != engine.isActivelyGenerating();

		boolean reconciled = needsPostLoadReconcile;
		if (reconciled) {
			reconcileAfterLoad(flywheel);
		} else if (flywheel != null) {
			// The one and only place engine state crosses into Create's world.
			if (generatedSpeedChanged)
				// Republishing the speed already refreshes both cached stress figures -
				// see GeneratingKineticBlockEntity#updateGeneratedRotation - so this
				// covers the capacity change too and must not be doubled up.
				flywheel.onEngineOutputChanged();
			else if (capacityBasisChanged)
				// Speed unchanged, capacity changed: refresh only the caches that
				// actually moved, rather than re-propagating the whole network for a
				// multiplier.
				flywheel.onEngineCapacityChanged();
		}

		playTransitionSounds(phaseBefore);
		updateIgnitionIndicator();

		// This tick's sparks and combustions, as one small packet rather than as a
		// full block entity synchronisation per event - see
		// EngineCombustionEventsPayload. The counters themselves are untouched and
		// still persisted: they are the engine's own record of what happened, and the
		// goggle diagnostics and the post-load comparison still read them. What they
		// no longer do is force the whole engine onto the wire eight times a second.
		dispatchCombustionEvents(sparkEventsBefore, combustionEventsBefore);

		// Anything the client displays has to trigger a block update, not just the
		// things that change the engine's rotation. Toggling redstone on a stopped
		// engine changes no speed and no phase, so without this the client would
		// keep showing the ignition state it was last told about.
		if (generatedSpeedChanged || reconciled || signalBefore != redstoneSignal
			|| phaseBefore != engine.getPhase() || structureValidBefore != engine.isStructureValid()
			|| startProgressBefore != engine.getStartProgress() || fuelBefore != engine.isFuelAvailable()
			|| sparkPlugBefore != engine.isSparkPlugInstalled()
			|| lubricationBefore != engine.getLubrication()
			|| firingBefore != engine.getFiringCylinderCount()
			|| generatingBefore != engine.isActivelyGenerating()) {
			syncAndRearmResync();
		} else if (engine.getMechanicalRpm() != 0.0F && --resyncCountdown <= 0) {
			syncAndRearmResync();
		}

		// The snapshot is valid only for the tick that took it. Dropping it here is
		// what guarantees no block entity reference is ever held across ticks.
		tickComponents = null;
	}

	/**
	 * Reconciles the engine's momentum with the kinetic network, then advances the
	 * crank. Runs on <b>both</b> sides, from values Create synchronises for us.
	 *
	 * <p>Three questions, and the whole of what the engine is told about rotation:
	 * <ul>
	 * <li><b>how fast</b> - the crankshaft's own kinetic speed. Identical to the
	 * flywheel's while the two are coupled, and still correct when the engine is
	 * driven from a Shaft on the crankshaft's far side or has no flywheel at
	 * all;</li>
	 * <li><b>is Create holding the shaft</b> - true whenever it has a speed to
	 * impose, and deliberately also true at zero on an <i>overstressed</i> network.
	 * An overstressed network is jammed, not absent: it stops the engine rather
	 * than releasing it to freewheel, which is why {@code isOverStressed} is asked
	 * separately from the speed (Create's {@code getSpeed} already reports 0 for
	 * both cases);</li>
	 * <li><b>is it somebody else's rotation</b> - whether the flywheel has a kinetic
	 * source of its own, which is Create's own answer to "this block is being
	 * driven from elsewhere" and is synchronised for the client.</li>
	 * </ul>
	 */
	private void tickRotation() {
		float shaftSpeed = getSpeed();
		boolean shaftDriven = shaftSpeed != 0.0F || isOverStressed();
		EngineFlywheelBlockEntity flywheel = getFlywheel();
		engine.tickRotation(shaftSpeed, shaftDriven, flywheel != null && flywheel.hasSource());
	}

	/**
	 * Whether the engine is actively generating <i>for the flywheel asking</i>.
	 *
	 * <p>The flywheel's single question, and the reason the position check is here
	 * rather than there: a flywheel this crankshaft is not coupled to - one bolted
	 * to the far end while another already claims the near one - drives nothing and
	 * must therefore contribute neither capacity nor drag on this engine's account.
	 *
	 * <p>The generation half of the answer comes from
	 * {@link EngineState#isActivelyGenerating()}, which is the mod's one authority
	 * on the question. Nothing here re-derives it.
	 */
	public boolean isGeneratingFor(BlockPos queryingFlywheelPos) {
		if (!drivesFlywheelAt(queryingFlywheelPos))
			return false;
		return getEngineState().isActivelyGenerating();
	}

	/**
	 * How many of this engine's cylinders are genuinely firing, for the flywheel
	 * asking. Zero for any flywheel this engine does not drive.
	 *
	 * <p>What Stress Capacity is scaled by, so an inline-4 supplies four times what
	 * a single does - and an inline-4 with a dead plug three quarters of that.
	 */
	public int getFiringCylinderCountFor(BlockPos queryingFlywheelPos) {
		if (!drivesFlywheelAt(queryingFlywheelPos))
			return 0;
		return getEngineState().getFiringCylinderCount();
	}

	/**
	 * Whether the flywheel at this position is the one this whole engine drives.
	 *
	 * <p>The position check that stops a flywheel bolted to a second engine's far
	 * end - or either of a pair, one at each end - from being paid on this engine's
	 * account. Asked of the engine's resolved assembly, so on an inline-4 it is the
	 * flywheel beyond the run rather than beyond this particular section.
	 */
	private boolean drivesFlywheelAt(BlockPos queryingFlywheelPos) {
		if (flywheelPos == null)
			getFlywheel();
		return flywheelPos != null && flywheelPos.equals(queryingFlywheelPos);
	}

	/**
	 * The engine makes its own noise; Create's generic kinetic hum on top of it
	 * would just muddy the loops this mod already manages.
	 */
	@Override
	protected boolean isNoisy() {
		return false;
	}

	/**
	 * Re-derives the flywheel coupling. Called from {@code neighborChanged} and by
	 * the cylinder when its piston assembly is installed or removed.
	 *
	 * <p>Nothing else needs invalidating: components are resolved fresh every tick
	 * and every time the overlay asks, so placing or removing a Carburetor, an Oil
	 * Sump or a Cylinder is picked up on the next tick with nothing to invalidate
	 * and no stale block entity to hold.
	 */
	public void onSurroundingsChanged() {
		BlockPos previous = flywheelPos;
		cachedFlywheel = null;
		flywheelPos = null;
		// A part fitted anywhere on the engine is a change to the whole engine, so
		// the section that actually runs it has to hear about it too - the Cylinder
		// three along notifies the section below itself, which may be a follower.
		if (level != null && !level.isClientSide && cylinderIndex != 0) {
			CrankshaftBlockEntity controller = getEngineController();
			if (controller != this)
				controller.onSurroundingsChanged();
		}
		if (previous == null || level == null || level.isClientSide)
			return;

		// Re-resolve at once, because a coupling that just *moved* leaves the old
		// flywheel holding a generated speed nobody will ever revise: this block
		// stops calling it, and Create only asks a source for its speed when
		// something tells it to. That is what a second flywheel appearing on the far
		// end would otherwise do - the engine goes structurally invalid and stops
		// combusting, while the flywheel it used to drive keeps the network turning
		// on a value that no longer means anything.
		getFlywheel();
		if (previous.equals(flywheelPos))
			return;
		if (level.isLoaded(previous)
			&& level.getBlockEntity(previous) instanceof EngineFlywheelBlockEntity stale)
			// Answers 0 now - getGeneratedRpmFor only pays out to the coupled
			// flywheel - so this detaches it rather than handing it a new speed.
			stale.onEngineOutputChanged();
	}

	/**
	 * Called from {@code CrankshaftBlock#onRemove} before the block entity dies.
	 *
	 * <p>Mining any section of a multi-cylinder engine takes that engine apart, so
	 * the whole thing has to be brought down here rather than only the block that
	 * was hit. The surviving sections re-derive their new, shorter layout on their
	 * next tick and stop themselves - see {@link #updateEnginePlacement} - but the
	 * generator has to be told <i>now</i>, while this block entity can still name
	 * it, or a flywheel would be left turning the network on a speed nobody will
	 * ever revise.
	 */
	public void onEngineRemoved() {
		BlockPos previousFlywheel = flywheelPos;
		tickComponents = null;
		CrankshaftBlockEntity controller = getEngineController();
		if (controller != this && !controller.isRemoved()) {
			controller.onEngineRemoved();
			return;
		}
		engine.setPhase(EnginePhase.STOPPED);
		engine.setSimulatedRpm(0.0F);
		engine.setPublishedRpm(0.0F);
		engine.setActivelyGenerating(false);
		engine.setTicksSinceCombustion(-1);
		flywheelPos = null;
		cachedFlywheel = null;
		if (previousFlywheel != null && level != null && !level.isClientSide && level.isLoaded(previousFlywheel)
			&& level.getBlockEntity(previousFlywheel) instanceof EngineFlywheelBlockEntity flywheel)
			// reconcile rather than merely update: this also forces the cached Stress
			// Capacity to zero, so an engine cannot leave a ghost of its own power
			// behind on a network by being taken apart.
			flywheel.reconcileEngineOutput();
	}

	// --- engine assembly ----------------------------------------------------

	/**
	 * Re-derives this section's place in its engine, and reacts when it changed.
	 *
	 * <p>Server side, once per tick, from block states alone - see
	 * {@link EngineComponents#locate}. Deriving it every tick rather than caching
	 * it is what makes adding or removing a crankcase section take effect
	 * immediately with nothing to invalidate.
	 *
	 * <p><b>A change of shape stops the engine.</b> Cutting an inline-4 in half
	 * leaves two engines that were never started, one of them run by a block entity
	 * that has spent its life as a follower with no simulation in it; resuming
	 * either from whatever happened to be in its fields would be arbitrary at best.
	 * So both stop, hand Create a generated speed of zero, and wait to be cranked
	 * again - which is the behaviour a player can predict, and the one that cannot
	 * leave ghost Stress Capacity on a network.
	 *
	 * <p>The layout is persisted, so a world load compares against the layout the
	 * engine really had rather than against a default - otherwise every reload
	 * would look like the player had just rebuilt the engine and would stop it.
	 */
	private void updateEnginePlacement() {
		if (level == null || level.isClientSide)
			return;
		EngineComponents.Placement placement = EngineComponents.locate(level, worldPosition, getAxis());

		// A run whose ends could not both be seen is not evidence of anything. Adopting
		// the count derived from the visible part is exactly how an inline-4 across a
		// chunk border used to come back as an inline-2, and how a follower whose
		// controller had unloaded used to promote itself. So nothing is adopted: the
		// engine suspends, keeping its stored layout and every player-set control, and
		// re-derives once the chunks are back.
		if (placement.status() == EngineAssemblyStatus.INCOMPLETE_CHUNKS) {
			setAssemblySuspended(true);
			return;
		}

		boolean nowOversized = placement.oversized();
		if (placement.index() == cylinderIndex && placement.count() == cylinderCount && nowOversized == oversized)
			return;

		// A demotion from controller to follower is the one shape change that can
		// silently take the player's controls away with it: they live on the block
		// entity that runs the engine, and that is about to be a different block. Hand
		// them over BEFORE this section stops being a controller, while it still is
		// the one that owns them.
		if (cylinderIndex == 0 && placement.index() > 0 && placement.isComplete())
			migrateControllerConfigurationTo(placement.controllerPos());

		boolean wasRunning = engine.getPhase() != EnginePhase.STOPPED || engine.getPublishedRpm() != 0.0F;
		cylinderIndex = placement.index();
		cylinderCount = placement.count();
		oversized = nowOversized;
		engine.setLayout(cylinderCount, sparkPlugMask);

		// The engine this block entity was simulating no longer exists in the shape
		// it was simulating. Stop it here, before anything downstream can act on a
		// state that describes a machine that has been taken apart.
		if (wasRunning)
			stopForRebuild();
		setChanged();
		sync();
	}

	/**
	 * Suspends or releases the simulation, according to whether this engine's
	 * assembly can be verified against the world right now.
	 *
	 * <p>Called from two places, for the two ways the world can be too dark to judge
	 * an engine by: {@link #updateEnginePlacement()} when the crank run itself passes
	 * through an unloaded chunk, and {@link #tick()} when a Cylinder, the Flywheel,
	 * the Carburetor or the Oil Sump does.
	 *
	 * <p>Suspension is fail-closed and <b>non-destructive</b>, which is the balance
	 * this whole mechanism strikes:
	 * <ul>
	 * <li>the engine stops at once and Create's cached Stress Capacity is forced to
	 * zero, so a half-visible engine can never leave ghost capacity on a network, and
	 * no combustion, fuel draw, oil draw or start progress happens while it cannot be
	 * verified;</li>
	 * <li>the stored layout, the ignition switch, the Control Module and the selected
	 * mode are all left untouched, so nothing the player configured is lost to a
	 * chunk unload;</li>
	 * <li>no controller is re-chosen and no migration runs, so a run can never be
	 * re-derived into a shorter engine or split into two by a chunk going away.</li>
	 * </ul>
	 *
	 * <p>Both edges are idempotent - only the tick that actually changes the state
	 * does any work - so an engine at the edge of the loaded area does not
	 * re-propagate a kinetic network twenty times a second.
	 *
	 * <p>Deliberately <i>not</i> part of {@link #isEngineController()}. A controller
	 * that suspended itself has to keep being the controller, or nothing would ever
	 * run the check that releases it again.
	 *
	 * @return true when the engine is suspended and must not be simulated this tick
	 */
	private boolean setAssemblySuspended(boolean suspended) {
		if (suspended == assemblySuspended)
			return suspended;
		assemblySuspended = suspended;
		if (suspended) {
			stopForRebuild();
		} else {
			// The engine was stopped on the way in, so there is no stale momentum to
			// reconcile - what has to happen is that Create is told again. It has been
			// holding a generated speed and a capacity of zero, and the engine has to
			// earn both back from combustion rather than inherit them.
			engine.requestGeneratedRepublish();
			EngineFlywheelBlockEntity flywheel = resolveComponents().flywheel();
			if (flywheel != null)
				flywheel.reconcileEngineOutput();
		}
		setChanged();
		sync();
		return suspended;
	}

	/**
	 * Hands this engine's persistent controller-local configuration to the section
	 * that is taking over as controller.
	 *
	 * <p>Adding a crankcase to the <b>negative</b> end of a run makes the new block
	 * the controller and demotes the old one to a follower. Everything the player
	 * configured lives on the controller, so without this the ignition switch, the
	 * Redstone Control Module and the selected control mode would all be left on a
	 * block that no longer has any say in the engine - and the module would later
	 * drop from the wrong block, or from both.
	 *
	 * <p>What moves is exactly the configuration, and nothing else:
	 * <ul>
	 * <li><b>the ignition switch position</b> - a switch the player turned off stays
	 * off across a rebuild;</li>
	 * <li><b>the Control Module</b>, as an ownership transfer rather than a copy: the
	 * new controller has it and this one does not, so mining either section
	 * afterwards drops exactly one module;</li>
	 * <li><b>the selected control mode</b>, through
	 * {@code ScrollValueBehaviour#setValue} rather than by writing NBT behind the
	 * behaviour's back - the behaviour holds the value, and a tag that disagreed
	 * with it would be overwritten the next time it saved.</li>
	 * </ul>
	 *
	 * <p>What deliberately does <b>not</b> move: the running engine state, the crank
	 * angle, the momentum and the redstone signal. A shape change stops the engine by
	 * design, and the signal is a live input the new controller samples from its own
	 * neighbours on its very next tick - carrying it over would let a lever that is
	 * nowhere near the new block go on commanding the engine.
	 *
	 * <p>Idempotent by construction: it is reached only on the tick this section's
	 * index leaves 0, and that index is written immediately afterwards, so it cannot
	 * run twice for one rebuild. It is also independent of block entity tick order -
	 * whether the new controller has already ticked with its own defaults or has not
	 * ticked at all, this overwrites those defaults with the real configuration.
	 */
	private void migrateControllerConfigurationTo(BlockPos newControllerPos) {
		if (level == null || level.isClientSide || newControllerPos.equals(worldPosition))
			return;
		if (!level.isLoaded(newControllerPos))
			return;
		if (!(level.getBlockEntity(newControllerPos) instanceof CrankshaftBlockEntity successor))
			return;
		if (successor == this)
			return;

		successor.manualIgnition = manualIgnition;
		successor.controlModuleInstalled = controlModuleInstalled;
		// The behaviour owns this value, its persistence and its packet, so it is set
		// through the behaviour. setValue also marks the successor changed and sends
		// its data, which is what carries the new box to the client.
		if (controlMode != null && successor.controlMode != null)
			successor.controlMode.setValue(controlMode.getValue());

		// One module, one owner. Clearing it here is what makes the transfer a move
		// rather than a duplication, and it is what CrankshaftBlock#onRemove reads
		// when it decides whether to drop the item.
		controlModuleInstalled = false;
		// The follower has no controls left to be commanded through, and a stale
		// number here would still be printed by the overlay.
		redstoneSignal = 0;

		successor.setChanged();
		successor.sync();
	}

	/**
	 * Brings this section's engine to a halt because its structure changed, and
	 * makes sure Create hears about it.
	 */
	private void stopForRebuild() {
		engine.setPhase(EnginePhase.STOPPED);
		engine.setSimulatedRpm(0.0F);
		engine.setActivelyGenerating(false);
		engine.setTicksSinceCombustion(-1);
		engine.setPublishedRpm(0.0F);
		engine.requestGeneratedRepublish();
		// Whatever flywheel this section's old engine drove has to be told, or it
		// would keep the network turning on a generated speed nobody will revise.
		if (level != null && !level.isClientSide) {
			EngineFlywheelBlockEntity flywheel = resolveComponents().flywheel();
			if (flywheel != null)
				flywheel.reconcileEngineOutput();
		}
	}

	/**
	 * Whether this section is the one that runs the engine.
	 *
	 * <p>Exactly one section of any engine answers true - the one at the negative
	 * end of the run - so there is exactly one simulation, one crank angle, one
	 * throttle and one kinetic source however many cylinders are bolted together.
	 */
	public boolean isEngineController() {
		return cylinderIndex == 0 && !oversized;
	}

	/**
	 * Whether this section is part of a run longer than
	 * {@link EngineTuning#MAX_CYLINDERS} sections.
	 *
	 * <p>True for <i>every</i> section of such a run, not only the ones past the
	 * limit, which is what makes the answer the same wherever the player looks and
	 * what stops the first four sections from quietly forming a working engine.
	 */
	public boolean isOversized() {
		return oversized;
	}

	/** This section's 0-based place along the crank axis, and its cylinder's index. */
	public int getCylinderIndex() {
		return cylinderIndex;
	}

	/** How many cylinders this section's engine has. */
	public int getCylinderCount() {
		return cylinderCount;
	}

	/**
	 * The crank phase this section's throw runs at, in degrees.
	 *
	 * <p>0, 90, 180 or 270 on an inline-4. The same number the simulation fires
	 * this cylinder at, which is what puts the crank pin the player can see through
	 * the crankcase window under the rod that combustion actually pushed.
	 */
	public float getPhaseOffsetDegrees() {
		return EngineTuning.cylinderPhaseOffsetDegrees(cylinderIndex, cylinderCount);
	}

	/**
	 * Where the section that runs this engine is. Arithmetic, never a search.
	 *
	 * <p>Answers this section's own position when there is no controller to point
	 * at - an oversized run has none by design, and a suspended one has none until
	 * its chunks are back. Both then resolve to {@code this}, which reports a stopped
	 * engine: the honest answer for a build that is not an engine, and one that
	 * cannot accidentally nominate an inner section as the head of a sub-engine.
	 */
	public BlockPos getControllerPos() {
		if (cylinderIndex == 0 || oversized)
			return worldPosition;
		return worldPosition.relative(Direction.get(Direction.AxisDirection.NEGATIVE, getAxis()), cylinderIndex);
	}

	/**
	 * The block entity that runs this engine, or {@code this} when the controller
	 * cannot be reached.
	 *
	 * <p>Falling back to itself is deliberate: a follower whose controller is in a
	 * chunk the client has not received yet must still answer questions about
	 * itself - its axis, its block state, its position - without a null check at
	 * every call site. What it will report is a stopped engine, which is the
	 * honest answer when the engine cannot be seen.
	 */
	public CrankshaftBlockEntity getEngineController() {
		if (level == null)
			return this;
		BlockPos controllerPos = getControllerPos();
		if (controllerPos.equals(worldPosition))
			return this;
		if (!level.isLoaded(controllerPos))
			return this;
		if (!(level.getBlockEntity(controllerPos) instanceof CrankshaftBlockEntity controller))
			return this;
		// It must actually BE a controller. The index this position was derived from
		// is at most one tick old, and a section that has just been cut off from its
		// engine - or one whose run has grown too long, or whose chunks are half away
		// - would otherwise hand out a block entity that is itself a follower, and
		// every delegating method here would follow the chain again. Requiring a real
		// controller makes the hop exactly one deep, always.
		return controller.isEngineController() ? controller : this;
	}

	// --- post-load reconciliation -------------------------------------------

	/**
	 * Squares Create's restored generator state with what the engine actually is.
	 *
	 * <p>Two state systems persist across a world save. Create writes its own
	 * {@code Speed}, {@code Source} and {@code Network} - including a cached Stress
	 * Capacity per source - and restores them verbatim. This mod writes the
	 * engine's physical state. On load they can disagree about anything the world
	 * did while the chunk was gone, and about anything that was merely
	 * <i>in flight</i> when the save was taken; whichever of them loads first is
	 * pure accident and must not be what decides the answer.
	 *
	 * <p>The engine's simulation wins, always. This is called from the first server
	 * tick that has resolved the engine's components and run the simulation once,
	 * so by now generation has been re-derived from the world - structure, plug,
	 * fuel, oil, speed - rather than trusted from NBT. Publishing it here is
	 * unconditional and bypasses the normal rate limits: an engine that is
	 * generating replaces Create's restored speed and capacity with its own, and
	 * one that is not forces both to zero.
	 *
	 * <p>That second half is what keeps a save from resurrecting the free-power
	 * exploit. Create's {@code KineticNetwork#addSilently} re-registers a source
	 * with the capacity it had on disk; an engine that lost its fuel, its plug or
	 * its cylinder while unloaded would otherwise keep handing that capacity out
	 * until something happened to ask it again.
	 */
	private void reconcileAfterLoad(@Nullable EngineFlywheelBlockEntity flywheel) {
		needsPostLoadReconcile = false;
		postLoadWaitTicks = 0;
		if (flywheel != null)
			flywheel.reconcileEngineOutput();
	}

	/**
	 * Whether every position the component resolver looks at can be read right now.
	 *
	 * <p>An engine is three blocks tall and three long, so it may straddle a chunk
	 * boundary, and on a world load its Cylinder or Flywheel can be a tick or two
	 * behind its Crankshaft. Resolving components against an unloaded chunk answers
	 * "absent", which for the one tick that decides the reconciliation would mean
	 * an intact engine being reconciled as a broken one.
	 *
	 * <p>Deliberately only asks whether the chunks are <i>loaded</i>, never whether
	 * the blocks are there. A genuinely missing Carburetor still reconciles as a
	 * missing Carburetor - this cannot make an incomplete engine wait for ever.
	 */
	private boolean engineNeighbourhoodLoaded() {
		return level != null && resolveComponents().chunksLoaded();
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

	// --- controls ------------------------------------------------------------

	/**
	 * <b>The</b> authoritative resolution of this engine's control inputs.
	 *
	 * <p>Everything that wants to know whether the ignition is live or how far the
	 * throttle is open calls this, and nothing anywhere else reads redstone or
	 * decides what a mode means. That is what makes the default - manual - a
	 * property of one method rather than of every subsystem: with no module
	 * installed {@link #getControlMode()} is {@link ControlMode#MANUAL}, so
	 * {@link #readRedstoneSignal()} never even looks at the neighbours and the
	 * player's own switch and Carburetor are the only inputs that exist.
	 *
	 * <p>Safe on both sides. Every value it reads - the module flag, the mode, the
	 * switch position, the signal, the Carburetor's throttle - is synchronised, so
	 * the overlay resolves exactly what the simulation resolved.
	 */
	public EngineControlState resolveControlState() {
		// One engine, one set of controls. A follower resolves its controller's,
		// which is why clicking any crankcase of an inline-4 works the same switch
		// and why all four tell-tales agree.
		CrankshaftBlockEntity controller = getEngineController();
		if (controller != this)
			return controller.resolveControlState();

		ControlMode mode = getControlMode();
		int signal = mode.usesRedstone() ? redstoneSignal : 0;
		boolean ignition = mode.controlsIgnition() ? signal > 0 : manualIgnition;
		int throttlePercent =
			mode.controlsThrottle() ? mode.commandedThrottlePercent(signal) : manualThrottlePercent();
		return new EngineControlState(mode, ignition, throttlePercent, signal);
	}

	/**
	 * The strongest signal reaching this block, or 0 when nothing is listening.
	 *
	 * <p>This is the hard guarantee that a default engine ignores redstone: with no
	 * module, or in {@link ControlMode#MANUAL}, the neighbours are never sampled at
	 * all, so a lever slapped onto the crankcase cannot change the ignition, the
	 * throttle, or even the number the overlay prints.
	 */
	private int readRedstoneSignal() {
		if (level == null || !getControlMode().usesRedstone())
			return 0;
		return level.getBestNeighborSignal(worldPosition);
	}

	/**
	 * The player's stored throttle setting, as a whole percentage, straight off the
	 * Carburetor's scroll value.
	 *
	 * <p>Automation never writes here. A redstone throttle mode produces its own
	 * commanded value in {@link #resolveControlState()} and leaves this one alone,
	 * which is why returning to manual control restores the setting the player last
	 * dialled in rather than whatever the signal happened to be.
	 *
	 * <p>An engine with no Carburetor reads 0. That is not a special case worth
	 * worrying about: no Carburetor also means no fuel, so such an engine cannot
	 * run under its own power at any throttle.
	 */
	public int manualThrottlePercent() {
		CarburetorBlockEntity carburetor = getCarburetor();
		return carburetor == null ? EngineTuning.THROTTLE_MIN_PERCENT : carburetor.getThrottlePercent();
	}

	/**
	 * The selected control mode, or {@link ControlMode#MANUAL} whenever no module
	 * is installed - including on an engine that had one configured and lost it, so
	 * removing the module always reverts to manual control immediately.
	 */
	public ControlMode getControlMode() {
		CrankshaftBlockEntity controller = getEngineController();
		if (controller != this)
			return controller.getControlMode();
		return controlModuleInstalled && controlMode != null ? ControlMode.byOrdinal(controlMode.getValue())
			: ControlMode.MANUAL;
	}

	/**
	 * Whether <i>this section</i> is the one carrying the module.
	 *
	 * <p>Deliberately the local field and not the engine's answer. It gates the
	 * value box, which belongs to the block the module is plugged into, and it is
	 * what {@code onRemove} asks before dropping the item - and an engine-wide
	 * answer there would have every section of an inline-4 drop a module the player
	 * only ever crafted one of.
	 */
	public boolean hasControlModule() {
		return controlModuleInstalled;
	}

	/** Whether this section's engine has a module installed, wherever it sits. */
	public boolean engineHasControlModule() {
		return getEngineController().controlModuleInstalled;
	}

	/**
	 * Flips the ignition switch and reports the new position.
	 *
	 * <p>Server side; the block's interaction handles the client. Nothing is
	 * validated here on purpose - switching the ignition on with no fuel, no piston
	 * or no flywheel is a perfectly reasonable thing for a player to do, and the
	 * engine simply will not catch.
	 */
	public boolean toggleManualIgnition() {
		CrankshaftBlockEntity controller = getEngineController();
		if (controller != this)
			return controller.toggleManualIgnition();
		setManualIgnition(!manualIgnition);
		return manualIgnition;
	}

	/**
	 * The player worked the ignition switch: flip it, click, and say what it now
	 * reads.
	 *
	 * <p>Feedback is deliberately not a chat line. The switch itself moves on the
	 * model, the tell-tale on the crankcase lights, a lever click plays, and the
	 * new position is written to the action bar - which is where Create puts this
	 * kind of confirmation and which does not accumulate in the chat log.
	 */
	public void toggleIgnitionFor(Player player) {
		boolean on = toggleManualIgnition();
		playSound(SoundEvents.LEVER_CLICK, 0.4F, on ? 0.72F : 0.58F);
		ECLang.translate("gui.ignition", ECLang.translate(on ? "gui.value.enabled" : "gui.value.disabled")
			.style(on ? ChatFormatting.GREEN : ChatFormatting.RED)
			.component())
			.style(ChatFormatting.WHITE)
			.sendStatus(player);
	}

	public void setManualIgnition(boolean on) {
		CrankshaftBlockEntity controller = getEngineController();
		if (controller != this) {
			controller.setManualIgnition(on);
			return;
		}
		if (manualIgnition == on)
			return;
		manualIgnition = on;
		setChanged();
		// The switch model and the tell-tale both follow the *effective* ignition,
		// which the next tick recomputes; this update is what carries the new switch
		// position to the client in the meantime.
		sync();
	}

	/** @return false when a module is already installed. */
	public boolean installControlModule() {
		CrankshaftBlockEntity controller = getEngineController();
		if (controller != this)
			// One engine, one module: it always goes into the section that runs the
			// engine, wherever the player clicked, so the value box that configures
			// it is never on a crankcase that has no say in anything.
			return controller.installControlModule();
		if (controlModuleInstalled)
			return false;
		controlModuleInstalled = true;
		setChanged();
		sync();
		return true;
	}

	/**
	 * @return false when there was nothing to remove.
	 *
	 *         <p>The selected mode is deliberately kept in NBT: a module put back
	 *         in is the same module, configured as it was. It has no effect while
	 *         absent, because {@link #getControlMode()} answers MANUAL.
	 */
	public boolean removeControlModule() {
		CrankshaftBlockEntity controller = getEngineController();
		if (controller != this)
			return controller.removeControlModule();
		if (!controlModuleInstalled)
			return false;
		controlModuleInstalled = false;
		// Whatever redstone was doing stops mattering this instant, and the stored
		// signal has to go with it or the overlay would keep printing it.
		redstoneSignal = 0;
		setChanged();
		sync();
		return true;
	}

	// --- simulation inputs ---------------------------------------------------

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
	 * Plays out the two events the server counted, on the client that can see
	 * them.
	 *
	 * <p>This is the whole of the ignition feedback, and it hangs off nothing but
	 * {@link EngineState#getSparkEventId(int)} and
	 * {@link EngineState#getCombustionEventId(int)} moving, per cylinder. A spark is the coil
	 * firing - it happens with or without fuel. A combustion is a charge that was
	 * actually paid for and burned, so the chamber flash and the firing sound
	 * cannot appear for a revolution that produced no torque, and cannot land on a
	 * different tick from each other: they are two reactions to one number.
	 *
	 * <p>The first update after the block entity appears only <i>adopts</i> the
	 * counters. Without that, every chunk load would fire a spark and a flash for
	 * events that happened before the player arrived.
	 *
	 * <p>Only a difference is tested, never a magnitude: if two events arrive in
	 * one client tick - a badly lagging connection, or an engine at full throttle
	 * on a slow client - the player gets one flash and one puff rather than a
	 * double bang. Reproducing the missed one would be worse, not better.
	 */
	private void dispatchCombustionEvents(int[] sparkEventsBefore, int[] combustionEventsBefore) {
		if (!(level instanceof ServerLevel serverLevel))
			return;

		int sparkMask = 0;
		int combustionMask = 0;
		for (int cylinder = 0; cylinder < engine.getCylinderCount(); cylinder++) {
			if (engine.getSparkEventId(cylinder) != sparkEventsBefore[cylinder])
				sparkMask |= 1 << cylinder;
			if (engine.getCombustionEventId(cylinder) != combustionEventsBefore[cylinder])
				combustionMask |= 1 << cylinder;
		}
		if ((sparkMask | combustionMask) == 0)
			return;

		// Every cylinder that did anything this tick, in one packet. An inline-4 at
		// full throttle therefore costs exactly what an inline-1 does: at most one
		// packet per tick, whatever is happening inside it.
		//
		// Addressed to the players tracking the CONTROLLER's chunk. That is the block
		// entity that owns the engine and the position the payload names, so it is
		// also the chunk a client must have in order to resolve the engine at all.
		PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(worldPosition),
			new EngineCombustionEventsPayload(worldPosition, (byte) sparkMask, (byte) combustionMask));
	}

	/**
	 * Plays out one tick's events on the client, one bit per cylinder.
	 *
	 * <p>Called from {@code ClientEngineEvents} when a payload arrives, and nowhere
	 * else. There is deliberately no client-side prediction anywhere near this: the
	 * client cannot know whether the server's fuel draw succeeded, so it is told
	 * rather than left to guess, and the flash and the bang are two reactions to one
	 * bit instead of two mechanisms that could land a tick apart.
	 *
	 * @param sparkMask      bit {@code i} set when cylinder {@code i}'s coil fired
	 * @param combustionMask bit {@code i} set when cylinder {@code i} burned a charge
	 */
	@OnlyIn(Dist.CLIENT)
	public void playCombustionEvents(byte sparkMask, byte combustionMask) {
		if (level == null)
			return;
		for (int cylinder = 0; cylinder < engine.getCylinderCount(); cylinder++) {
			boolean sparked = (sparkMask & (1 << cylinder)) != 0;
			boolean burned = (combustionMask & (1 << cylinder)) != 0;
			if (!sparked && !burned)
				continue;

			// Which cylinder fired decides where every one of these happens: the spark
			// at that plug's electrode, the flash in that bore, the bang from that
			// chamber. An inline-4 firing in sequence is four effects walking down the
			// engine, which is exactly what it should look and sound like.
			BlockPos cylinderPos = cylinderPosition(cylinder);
			if (sparked)
				emitSpark(cylinderPos);
			if (burned) {
				engine.triggerCombustionFlash(cylinder);
				combustionAudio.onCombustion(level, cylinderPos, engine);
			}
		}
	}

	/** Where cylinder {@code i} of this engine is, by arithmetic along the crank axis. */
	private BlockPos cylinderPosition(int cylinder) {
		return EngineComponents.cylinderPos(
			worldPosition.relative(Direction.get(Direction.AxisDirection.POSITIVE, getAxis()), cylinder));
	}

	/**
	 * The spark itself: one particle at the electrode gap, and a tiny electrical
	 * tick.
	 *
	 * <p>The tick is deliberately silent while the engine is running. It is the
	 * sound of a coil discharging, which in a running engine is completely masked
	 * by the engine; what it is <i>for</i> is the case where nothing else is
	 * happening - ignition on, no fuel, the engine turning over - where hearing
	 * and seeing the plug fire while the engine refuses to catch is the whole
	 * diagnosis.
	 */
	@OnlyIn(Dist.CLIENT)
	private void emitSpark(BlockPos cylinderPos) {
		if (level == null)
			return;
		double x = cylinderPos.getX() + SPARK_PLUG_ELECTRODE.x;
		double y = cylinderPos.getY() + SPARK_PLUG_ELECTRODE.y;
		double z = cylinderPos.getZ() + SPARK_PLUG_ELECTRODE.z;

		level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, 0.0D, 0.0D, 0.0D);

		if (engine.getPhase() != EnginePhase.RUNNING)
			level.playLocalSound(x, y, z, ECSounds.ENGINE_SPARK.get(), SoundSource.BLOCKS,
				EngineTuning.SOUND_SPARK_VOLUME, 1.0F, false);
	}

	// --- audio --------------------------------------------------------------

	/**
	 * Plays the one-shot sounds that belong to a state <i>transition</i> rather
	 * than to a firing event: the catch, the stall and the shutdown.
	 *
	 * <p>Server side, and always through {@code Level#playSound} with a null
	 * player, which broadcasts to everyone in range. The firing cough is not here
	 * - it is an event, not a transition, and it is played from the combustion
	 * counter on the client so that it cannot land on a different tick from the
	 * flash that describes the same charge.
	 */
	private void playTransitionSounds(EnginePhase phaseBefore) {
		if (level == null)
			return;
		EnginePhase phase = engine.getPhase();

		// The catch. Only on the transition, so it can never repeat while running.
		if (phaseBefore == EnginePhase.STARTING && phase == EnginePhase.RUNNING)
			playSound(ECSounds.ENGINE_START.get(), EngineTuning.SOUND_START_VOLUME, 1.0F);

		// Only an engine that actually ran can stop; a start attempt that is simply
		// abandoned stays silent, and an engine loading in stopped never transitions
		// at all. Note this is the *phase* question, not the generation question:
		// an engine that has lost combustion and spun all the way down still
		// deserves to be heard doing it.
		if (phaseBefore.hasCaught() && phase == EnginePhase.STOPPED) {
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
		boolean ignition = engine.isIgnitionEnabled();
		// Every crankcase section of the engine, not only this one. The engine has
		// one ignition, and a four-cylinder engine whose tell-tales disagreed with
		// each other would be reporting something that does not exist.
		for (EngineComponents.Cylinder cylinder : engineComponents().cylinders())
			setIgnitionIndicatorAt(cylinder.crankshaftPos(), ignition);
	}

	private void setIgnitionIndicatorAt(BlockPos pos, boolean ignition) {
		if (level == null || !level.isLoaded(pos))
			return;
		BlockState state = level.getBlockState(pos);
		if (!state.hasProperty(CrankshaftBlock.LIT) || state.getValue(CrankshaftBlock.LIT) == ignition)
			return;
		level.setBlock(pos, state.setValue(CrankshaftBlock.LIT, ignition),
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
	 *
	 * <p>The firing rate is the one figure that is neither synchronised nor
	 * derived from speed: it is <i>measured</i>, from the intervals between the
	 * combustion events the server actually sent. That is deliberate - it is the
	 * only way the audio can be certain it is following combustion rather than an
	 * assumption about combustion.
	 */
	@OnlyIn(Dist.CLIENT)
	private void tickEngineAudio() {
		if (!(level instanceof ClientLevel clientLevel))
			return;
		combustionAudio.tick(clientLevel.getGameTime());
		EngineSoundManager.tick(clientLevel, worldPosition, engine, combustionAudio.getEventRateHz());
	}

	// --- mechanical coupling ------------------------------------------------

	/**
	 * The flywheel beyond either end of this <i>engine's whole crank run</i>, and
	 * independent of whether the engine is structurally complete.
	 *
	 * <p>The run, not this section: an inline-4's flywheel is three blocks away
	 * from its controller, and every section of that engine has to name the same
	 * one or the four of them would disagree about who generates.
	 *
	 * <p>Null when there is a flywheel at <i>both</i> ends. That is what makes the
	 * unsupported twin-flywheel build inert rather than arbitrary: with no coupling,
	 * {@link #getGeneratedRpmFor(BlockPos)} answers 0 to both of them, so neither
	 * becomes a source and no capacity is duplicated.
	 */
	@Nullable
	public EngineFlywheelBlockEntity getFlywheel() {
		if (cachedFlywheel != null && !cachedFlywheel.isRemoved())
			return cachedFlywheel;
		cachedFlywheel = null;
		flywheelPos = null;
		if (level == null)
			return null;

		// Block states only, so this stays cheap enough to be asked on every client
		// frame: where the run begins and ends, then the two candidate positions.
		EngineComponents.Placement placement = EngineComponents.locate(level, worldPosition, getAxis());
		// An over-long run has no engine to couple, and a run the scan could not see
		// the ends of has no established one. Naming a flywheel for either would give
		// a build that is not an engine a generator that could be asked for capacity.
		if (!placement.isComplete())
			return null;
		Direction positive = Direction.get(Direction.AxisDirection.POSITIVE, getAxis());
		BlockPos lastSection = placement.controllerPos()
			.relative(positive, placement.count() - 1);
		BlockPos candidate = EngineComponents.findFlywheel(level, placement.controllerPos(), lastSection, getAxis())
			.pos();
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
		return level == null ? EngineComponents.detached(worldPosition, getAxis())
			: EngineComponents.resolve(level, worldPosition, getAxis());
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
	 * crankshaft is coupled to - and for <i>both</i> of them when a flywheel is
	 * bolted to each end, because then there is no coupling at all.
	 *
	 * <p>The value is <i>latched</i>: it only changes when the simulation decides
	 * to publish a new one. Create calls this from validation and propagation at
	 * arbitrary times and must never see a value that drifts every tick.
	 */
	public float getGeneratedRpmFor(BlockPos queryingFlywheelPos) {
		if (!drivesFlywheelAt(queryingFlywheelPos))
			return 0.0F;
		return getEngineState().getPublishedRpm();
	}

	/**
	 * <b>The</b> engine this section belongs to.
	 *
	 * <p>A follower has an {@code EngineState} field of its own - every block
	 * entity does - but it is never ticked and never read: this hands out the
	 * controller's, so an inline-4's four sections, its four cylinders, its
	 * overlays and its renderers are all looking at one simulation. That is what
	 * makes "one engine, four cylinders" true rather than merely intended.
	 */
	public EngineState getEngineState() {
		return getEngineController().engine;
	}

	/**
	 * How often this engine's combustion events are actually arriving, in events
	 * per second, as measured on this client.
	 *
	 * <p>Measured rather than derived from RPM on purpose. A rate computed from
	 * speed would be an assumption about firing; this is the firing.
	 */
	public float getCombustionEventRateHz() {
		return combustionAudio.getEventRateHz();
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
		// so the phase and the engine's own momentum are restored too. Structure
		// validity and the effective ignition are re-derived from the world on the
		// next server tick.
		engine.setPhase(EnginePhase.byId(tag.getString(KEY_PHASE)));
		// THE authoritative rotational state, and the only one that is persisted:
		// signed angular velocity. Everything else about how fast this engine is
		// turning - what Create is told it generates, what the network is running at
		// - is a representation of this number and is rebuilt from it below.
		engine.setSimulatedRpm(tag.getFloat(KEY_SIMULATED_RPM));

		// The engine's shape. Restored rather than re-derived only so that the first
		// tick back has something to compare its own derivation against: a reload
		// that found the default single cylinder where an inline-4 stood would read
		// as the player having just rebuilt the engine, and would stop it.
		cylinderIndex = Math.min(Math.max(tag.getInt(KEY_CYLINDER_INDEX), 0), EngineTuning.MAX_CYLINDERS - 1);
		cylinderCount = Math.min(Math.max(tag.getInt(KEY_CYLINDER_COUNT), 1), EngineTuning.MAX_CYLINDERS);
		sparkPlugMask = tag.getInt(KEY_SPARK_PLUG_MASK);
		// Restored rather than re-derived for the same reason the index and count are:
		// so that an over-long run does not present itself as a valid engine for the
		// tick or two before the first placement scan runs. The world decides on that
		// tick, as always.
		oversized = tag.getBoolean(KEY_OVERSIZED);
		engine.setLayout(cylinderCount, sparkPlugMask);

		if (clientPacket) {
			// The client is shown what the server decided, never a second opinion:
			// the published speed for the diagnostics, and the one authoritative
			// answer to "is this engine producing power", which its overlays, its
			// audio and its rotation rule all read.
			engine.setPublishedRpm(tag.getFloat(KEY_PUBLISHED_RPM));
			engine.setActivelyGenerating(tag.getBoolean(KEY_GENERATING));
		} else {
			// Off disk. How long ago a charge last burned is simulation state, not
			// bookkeeping: it is the condition an external source cannot fake, and
			// dropping it used to make a saved running engine disown its own kinetic
			// network for a tick before claiming it back.
			int[] combustionAges = tag.getIntArray(KEY_COMBUSTION_AGE);
			if (combustionAges.length > 0)
				engine.setTicksSinceCombustion(combustionAges);
			else
				// A save from before this engine had cylinders to count separately.
				engine.setTicksSinceCombustion(tag.getInt(KEY_COMBUSTION_AGE));
			// The published speed is deliberately NOT restored - it is a cached
			// derivative of the momentum above, so it is reconstructed from that
			// momentum instead, and the first reconciled server tick then replaces
			// even the reconstruction with a freshly derived value. What this refuses
			// to do is let a number Create happened to be holding at save time outlive
			// the physical state it was supposed to describe.
			engine.restoreAfterLoad(tag.getBoolean(KEY_GENERATING));
			needsPostLoadReconcile = true;
			postLoadWaitTicks = 0;
		}
		engine.setIgnitionEnabled(tag.getBoolean(KEY_IGNITION));
		engine.setStructureValid(tag.getBoolean(KEY_STRUCTURE_VALID));
		// The ignition switch is a physical switch on the crankcase: it stays where
		// the player left it across a save, a chunk unload and a server restart,
		// exactly as the throttle lever and the fuel in the float bowl do. Nothing
		// is *started* by that - loading restores the engine's phase too, and the
		// start sounds are emitted from phase transitions computed inside a tick, so
		// an engine that was already running resumes running rather than announcing
		// a fresh start, and one that was stopped stays stopped until it is cranked.
		//
		// Read only when the key is actually present. write() always emits it, so
		// any engine that has ever been saved or synchronised carries its own
		// answer and gets it back verbatim - including an engine the player
		// deliberately switched off. A tag without the key is not an engine with the
		// ignition off; it is not an engine's saved state at all, and the field's
		// initialiser - on - is the right answer for a fresh one. Reading
		// unconditionally is what would quietly turn "new engines start switched on"
		// into "every engine loads switched off".
		if (tag.contains(KEY_MANUAL_IGNITION))
			manualIgnition = tag.getBoolean(KEY_MANUAL_IGNITION);
		controlModuleInstalled = tag.getBoolean(KEY_CONTROL_MODULE);
		redstoneSignal = tag.getInt(KEY_REDSTONE_SIGNAL);
		engine.setStartAttempt(tag.getInt(KEY_START_PROGRESS), tag.getInt(KEY_START_REQUIRED));
		engine.setFuelAvailable(tag.getBoolean(KEY_FUEL_AVAILABLE));
		// Carried for the client's benefit only - the overlay explains a dead engine
		// with it, and the cylinder that owns the truth may be a block the client
		// has not been told about yet. The server overwrites it from the world on
		// the very next tick.
		engine.setSparkPlugInstalled(tag.getBoolean(KEY_SPARK_PLUG));
		engine.setLubrication(LubricationState.byId(tag.getString(KEY_LUBRICATION)));
		// Persisted so a chunk reload does not hand the player free oil by
		// discarding the revolutions already banked towards the next draw.
		engine.setCombustionEventsSinceOilDraw(tag.getInt(KEY_OIL_WEAR));
		// The engine's own record of how many times each cylinder has sparked and
		// burned. No longer the event channel - EngineCombustionEventsPayload is -
		// but still real state: it is what the server diffs each tick to decide which
		// bits to set, so it has to survive a reload rather than restart from zero.
		engine.setEventIds(tag.getIntArray(KEY_SPARK_EVENT), tag.getIntArray(KEY_COMBUSTION_EVENT));
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.putFloat(KEY_CRANK_ANGLE, engine.getCrankAngleDegrees());
		tag.putString(KEY_PHASE, engine.getPhase()
			.getId());
		tag.putFloat(KEY_SIMULATED_RPM, engine.getSimulatedRpm());
		// Client only. On disk this would be a second, competing copy of a speed the
		// simulated RPM above already determines - and it is exactly the copy that
		// used to come back stale and stay stale. The client still needs it: the
		// goggle diagnostics print what Create is really being told, and only the
		// server knows that.
		if (clientPacket)
			tag.putFloat(KEY_PUBLISHED_RPM, engine.getPublishedRpm());
		else
			tag.putIntArray(KEY_COMBUSTION_AGE, engine.copyOfTicksSinceCombustion());
		tag.putBoolean(KEY_GENERATING, engine.isActivelyGenerating());
		tag.putBoolean(KEY_IGNITION, engine.isIgnitionEnabled());
		tag.putBoolean(KEY_STRUCTURE_VALID, engine.isStructureValid());
		tag.putBoolean(KEY_MANUAL_IGNITION, manualIgnition);
		tag.putBoolean(KEY_CONTROL_MODULE, controlModuleInstalled);
		tag.putInt(KEY_REDSTONE_SIGNAL, redstoneSignal);
		tag.putInt(KEY_START_PROGRESS, engine.getStartProgress());
		tag.putInt(KEY_START_REQUIRED, engine.getRequiredStartCycles());
		tag.putBoolean(KEY_FUEL_AVAILABLE, engine.isFuelAvailable());
		tag.putBoolean(KEY_SPARK_PLUG, engine.isSparkPlugInstalled());
		tag.putString(KEY_LUBRICATION, engine.getLubrication()
			.getId());
		tag.putInt(KEY_OIL_WEAR, engine.getCombustionEventsSinceOilDraw());
		// One counter per cylinder, because a spark and a bang happen at a PLACE. They
		// are the server's running tally, not the wire format: the live events reach
		// the client through EngineCombustionEventsPayload, and these are what the
		// server diffs each tick to work out which of its bits to set. Saved so that
		// diff has something to compare against after a reload; carried in the client
		// packet too, for the goggle diagnostics.
		tag.putIntArray(KEY_SPARK_EVENT, engine.copyOfSparkEventIds());
		tag.putIntArray(KEY_COMBUSTION_EVENT, engine.copyOfCombustionEventIds());
		tag.putInt(KEY_CYLINDER_INDEX, cylinderIndex);
		tag.putInt(KEY_CYLINDER_COUNT, cylinderCount);
		tag.putInt(KEY_SPARK_PLUG_MASK, sparkPlugMask);
		// Synchronised as well as saved: the renderers, the goggle overlay and the
		// simulation all have to agree that an over-long run is unsupported, and the
		// client cannot see far enough along the run to work that out for itself.
		tag.putBoolean(KEY_OVERSIZED, oversized);
	}

	private void sync() {
		notifyUpdate();
	}

	/**
	 * Syncs, and restarts the resync timer from the interval that fits what the
	 * engine is doing now.
	 *
	 * <p>Rearming on <i>every</i> update, not only on the timed ones, is what makes
	 * the short coast interval actually apply. A spin-down begins with an
	 * event-driven update - the engine stopped generating - and if the timer kept
	 * counting down from wherever the previous interval had left it, the first
	 * confirmation of a freewheeling engine's speed could easily arrive after the
	 * whole coast was over.
	 */
	private void syncAndRearmResync() {
		sync();
		// A freewheeling engine is the one state whose rotation the client integrates
		// for itself, so it is the one state worth confirming often: about eight
		// updates over a whole spin-down, against one every ten seconds for an engine
		// whose speed Create is already synchronising.
		resyncCountdown = engine.isFreeRotating() ? COAST_RESYNC_INTERVAL : RESYNC_INTERVAL;
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
		// The engine, not this block: on a follower crankcase every reading below
		// belongs to the controller three blocks away, and the player looking at an
		// inline-4's second cylinder is asking about the engine it is part of.
		EngineState state = getEngineState();
		EnginePhase phase = state.getPhase();

		ECLang.translate("gui.engine")
			.style(ChatFormatting.WHITE)
			.forGoggles(tooltip);

		// A coasting engine that has run dry reads as "Stalling" - same phase, but
		// the player cares about the reason, not the internal name.
		String phaseKey = phase == EnginePhase.COASTING && !state.isFuelAvailable()
			? "gui.phase.stalling"
			: phase.translationKey();
		ECLang.translate("gui.state", ECLang.translate(phaseKey)
			.style(phaseColor(phase))
			.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		// One line, and only on an engine that has more than one cylinder: a single
		// saying "Cylinders: 1" is noise on the overlay of every engine ever built.
		int cylinders = state.getCylinderCount();
		if (cylinders > 1)
			ECLang.translate("gui.cylinders", ECLang.number(cylinders)
				.style(ChatFormatting.AQUA)
				.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);

		ECLang.translate("gui.speed", ECLang.number(state.getMechanicalRpm())
			.style(ChatFormatting.AQUA)
			.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		// The line that answers "is this engine paying its way", and the one to read
		// when checking a multi-engine network: every engine on a shared shaft turns
		// at the same speed, so speed alone cannot tell a fuelled engine from a dead
		// one being spun by its neighbour. This can.
		boolean generating = state.isActivelyGenerating();
		ECLang.translate("gui.generation",
			ECLang.translate(generating ? "gui.value.active" : "gui.value.inactive")
				.style(generating ? ChatFormatting.GREEN : ChatFormatting.RED)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		// Only worth a line when it is not simply this engine: an engine generating
		// its own rotation is the ordinary case and says so on the line above.
		RotationSource rotationSource = state.getRotationSource();
		if (rotationSource != RotationSource.ENGINE && rotationSource != RotationSource.NONE)
			ECLang.translate("gui.rotation_source", ECLang.translate(rotationSource.translationKey())
				.style(ChatFormatting.WHITE)
				.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);

		boolean ignition = state.isIgnitionEnabled();
		ECLang.translate("gui.ignition",
			ECLang.translate(ignition ? "gui.value.enabled" : "gui.value.disabled")
				.style(ignition ? ChatFormatting.GREEN : ChatFormatting.RED)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		// One resolution of each for the whole overlay, and the same two calls the
		// simulation makes. If combustion can draw from a Carburetor, these lines
		// describe that same Carburetor; if the engine is running on a commanded
		// throttle, they print that same number - they cannot disagree.
		EngineComponents components = engineComponents();
		EngineControlState control = resolveControlState();
		addThrottleLine(tooltip, components.carburetor(), control);
		addControlLines(tooltip, control);
		addFlywheelWarning(tooltip, components);
		addSparkPlugWarning(tooltip, components);
		addFuelLines(tooltip, components.carburetor());
		addLubricationLines(tooltip, components.oilSump());

		if (phase == EnginePhase.STARTING)
			ECLang.translate("gui.start_progress",
				ECLang.number(state.getStartProgress())
					.style(ChatFormatting.GOLD)
					.component(),
				ECLang.number(state.getRequiredStartCycles())
					.style(ChatFormatting.DARK_GRAY)
					.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);

		addControlModuleBlock(tooltip);

		if (isPlayerSneaking)
			addDiagnostics(tooltip);
		return true;
	}

	/**
	 * The installed module, as its own little block at the end of the overlay -
	 * the same shape Create gives a part that has settings of its own.
	 *
	 * <p>Absent entirely on an engine without one, which is the common case and the
	 * one the HUD should stay quiet about.
	 */
	private void addControlModuleBlock(List<Component> tooltip) {
		if (!engineHasControlModule())
			return;

		ECLang.translate("gui.control_module")
			.style(ChatFormatting.WHITE)
			.forGoggles(tooltip);

		ECLang.translate("gui.mode", Component.translatable(getControlMode().getTranslationKey()))
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
	}

	/**
	 * Throttle the engine is <i>actually</i> running on, which is the Carburetor's
	 * lever unless redstone automation is commanding one instead. Skipped entirely
	 * when there is no Carburetor - a throttle reading for a control that is not
	 * installed would be noise.
	 */
	private void addThrottleLine(List<Component> tooltip, @Nullable CarburetorBlockEntity carburetor,
		EngineControlState control) {
		if (carburetor == null)
			return;
		ECLang.translate("gui.throttle", ECLang.number(control.throttlePercent())
			.style(ChatFormatting.AQUA)
			.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		// The player's own setting, shown only while something else is overriding it
		// - otherwise it is the same number twice. This is the line that says the
		// manual throttle is being borrowed, not overwritten.
		if (control.mode()
			.controlsThrottle() && control.throttlePercent() != getEngineController().manualThrottlePercent())
			ECLang.translate("gui.manual_throttle", ECLang.number(getEngineController().manualThrottlePercent())
				.style(ChatFormatting.DARK_GRAY)
				.component())
				.style(ChatFormatting.DARK_GRAY)
				.forGoggles(tooltip, 1);
	}

	/**
	 * Who is holding the controls, and - only when redstone actually is - what it
	 * is being told.
	 *
	 * <p>The signal line is deliberately absent in every manual configuration. An
	 * engine that ignores redstone must not print a redstone reading, or the HUD
	 * would suggest an input that has no effect.
	 */
	private void addControlLines(List<Component> tooltip, EngineControlState control) {
		ControlMode mode = control.mode();

		ECLang.translate("gui.control", (mode.usesRedstone()
			? ECLang.translate("gui.control.redstone_mode", Component.translatable(mode.getTranslationKey()))
				.style(ChatFormatting.RED)
			: ECLang.translate("gui.control.manual")
				.style(ChatFormatting.WHITE)).component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		if (!mode.usesRedstone())
			return;

		ECLang.translate("gui.signal", ECLang.number(control.redstoneSignal())
			.style(control.redstoneSignal() > 0 ? ChatFormatting.RED : ChatFormatting.DARK_GRAY)
			.component(),
			ECLang.number(ControlMode.MAX_SIGNAL)
				.style(ChatFormatting.DARK_GRAY)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
	}

	/**
	 * The one structural fault worth explaining rather than merely reporting.
	 *
	 * <p>"Invalid" is unhelpful for a build that looks finished, and a flywheel at
	 * each end looks extremely finished. Everything else that can be missing - the
	 * piston, the flywheel, the carburetor - already has its own line.
	 */
	private void addFlywheelWarning(List<Component> tooltip, EngineComponents components) {
		if (!components.hasFlywheelConflict())
			return;
		ECLang.translate("gui.flywheel_conflict")
			.style(ChatFormatting.RED)
			.forGoggles(tooltip, 1);
	}

	/**
	 * The missing plug, and <b>only</b> when it is missing.
	 *
	 * <p>A fitted plug is the normal state of every working engine, so a line
	 * saying so on every running engine's overlay would be pure noise - which is
	 * why the full inventory of installed parts lives on the <i>Cylinder</i>'s
	 * overlay, where a player goes to ask exactly that question. What belongs
	 * here is the fault, because this is the overlay a player reads when the
	 * engine will not start.
	 *
	 * <p>Skipped on a cylinder that is missing altogether: the structure lines
	 * already say the engine is incomplete, and "no spark plug" about an engine
	 * with no cylinder is the less useful of the two facts.
	 */
	private void addSparkPlugWarning(List<Component> tooltip, EngineComponents components) {
		if (components.hasSparkPlug())
			return;
		// How many are missing, not merely that one is: on an inline-4 "3 / 4" tells
		// the player the engine will run, roughly, and which fault to look for.
		int fitted = components.sparkPlugCount();
		int total = components.cylinderCount();
		ECLang.translate("gui.spark_plug", (total > 1 ? ECLang.translate("gui.value.fraction",
			ECLang.number(fitted)
				.component(),
			ECLang.number(total)
				.component())
			: ECLang.translate("gui.value.missing")).style(ChatFormatting.RED)
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
		EngineState state = getEngineState();
		ECLang.translate("gui.diagnostics")
			.style(ChatFormatting.DARK_GRAY)
			.forGoggles(tooltip);

		// Resolved from the world rather than read from the synced simulation flag,
		// so these lines answer "is the engine assembled correctly right now" using
		// the same rule the server uses to decide whether it may run.
		EngineComponents components = engineComponents();
		boolean valid = components.isMechanicallyValid();
		diagnostic(tooltip, "structure", ECLang
			.translate(valid ? "gui.value.valid" : "gui.value.invalid")
			.style(valid ? ChatFormatting.GREEN : ChatFormatting.RED));
		// What the player built, named: Inline-1 through Inline-4. Resolved from the
		// world, so it is the layout the engine will run as on the next tick.
		diagnostic(tooltip, "engine_layout", ECLang.translate("gui.layout.inline",
			ECLang.number(components.cylinderCount())
				.component())
			.style(components.oversized() ? ChatFormatting.RED : ChatFormatting.WHITE));
		// How many of them are genuinely burning fuel right now. THE line for a
		// multi-cylinder engine: capacity is scaled by this, so an inline-4 reading
		// "3 / 4" is an engine down a cylinder and down a quarter of its power.
		int firing = state.getFiringCylinderCount();
		diagnostic(tooltip, "active_cylinders", ECLang.translate("gui.value.fraction",
			ECLang.number(firing)
				.component(),
			ECLang.number(components.cylinderCount())
				.component())
			.style(firing == components.cylinderCount() ? ChatFormatting.GREEN
				: firing == 0 ? ChatFormatting.DARK_GRAY : ChatFormatting.GOLD));
		if (components.oversized())
			ECLang.translate("gui.too_many_cylinders", ECLang.number(EngineTuning.MAX_CYLINDERS)
				.component())
				.style(ChatFormatting.RED)
				.forGoggles(tooltip, 1);
		diagnostic(tooltip, "rotation_source", ECLang.translate(state.getRotationSource()
			.translationKey())
			.style(ChatFormatting.WHITE));
		// Which end of the crank axis the flywheel is on. Purely informational - both
		// ends are equally valid - but it is the fastest way to confirm that the
		// resolver found the one the player actually built.
		diagnostic(tooltip, "flywheel_side", ECLang.translate(flywheelSideKey(components.flywheelPlacement()))
			.style(components.hasFlywheelConflict() ? ChatFormatting.RED : ChatFormatting.WHITE));
		// Resolved from the world like the structure line above, not from the
		// synchronised simulation flag, so it is the same answer the server will
		// use on its next tick rather than the one it used on its last.
		boolean sparkPlug = components.hasSparkPlug();
		diagnostic(tooltip, "spark_plug", (sparkPlug ? ECLang.translate("gui.value.installed")
			.style(ChatFormatting.GREEN)
			: ECLang.translate("gui.value.fraction", ECLang.number(components.sparkPlugCount())
				.component(),
				ECLang.number(components.cylinderCount())
					.component())
				.style(ChatFormatting.RED)));
		boolean module = engineHasControlModule();
		diagnostic(tooltip, "control_module", ECLang
			.translate(module ? "gui.value.installed" : "gui.value.missing")
			.style(module ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
		// The switch position, which is not the same thing as the live ignition while
		// a redstone ignition mode is driving the engine.
		boolean switchOn = getEngineController().manualIgnition;
		diagnostic(tooltip, "ignition_switch", ECLang
			.translate(switchOn ? "gui.value.enabled" : "gui.value.disabled")
			.style(switchOn ? ChatFormatting.GREEN : ChatFormatting.RED));
		diagnostic(tooltip, "crank_angle", ECLang.number(state.getCrankAngleDegrees())
			.style(ChatFormatting.AQUA));
		diagnostic(tooltip, "simulated_rpm", ECLang.number(state.getSimulatedRpm())
			.style(ChatFormatting.AQUA));
		// Derived from the resolved control state rather than from the simulation's
		// own copy of the throttle: that copy is only ever written on the server, so
		// reading it here - the overlay is client-side - would always have printed
		// idle. Everything the resolution needs is synchronised, so this is the same
		// number the engine is actually using, whether it came from the Carburetor's
		// lever or from a redstone signal.
		diagnostic(tooltip, "target_rpm", ECLang.number(EngineTuning.targetRpmForThrottle(resolveControlState()
			.throttle()))
			.style(ChatFormatting.AQUA));
		diagnostic(tooltip, "generated_rpm", ECLang.number(state.getPublishedRpm())
			.style(ChatFormatting.AQUA));
		// Zero on any engine that is not actively generating, however fast the
		// network is spinning it. Together with Generated RPM this is the whole
		// diagnosis of a multi-engine network: capacity comes from combustion, never
		// from rotation.
		diagnostic(tooltip, "generated_capacity",
			ECLang.number(state.isActivelyGenerating()
				? EngineTuning.STRESS_CAPACITY_PER_RPM * state.getPublishedRpm() * firing
				: 0.0F)
				.style(state.isActivelyGenerating() ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY));
		// Network load is deliberately absent. Create already reports stress on the
		// Flywheel, which is this engine's generator, and repeating it here would be
		// the HUD clutter this overlay keeps out of the way behind sneak.
	}

	private static void diagnostic(List<Component> tooltip, String key, LangBuilder value) {
		ECLang.translate("gui." + key, value.component())
			.style(ChatFormatting.DARK_GRAY)
			.forGoggles(tooltip, 1);
	}

	/** Which end of the crank axis carries the flywheel, in words. */
	private static String flywheelSideKey(EngineComponents.FlywheelPlacement placement) {
		return switch (placement) {
			case POSITIVE -> "gui.side.positive";
			case NEGATIVE -> "gui.side.negative";
			case AMBIGUOUS -> "gui.side.both";
			case NONE -> "gui.side.none";
		};
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

		EngineState state = getEngineState();

		ECLang.translate("gui.engine")
			.style(ChatFormatting.WHITE)
			.forGoggles(tooltip);

		EnginePhase phase = state.getPhase();
		ECLang.translate(observedStateKey(phase))
			.style(phaseColor(phase))
			.forGoggles(tooltip, 1);

		boolean ignition = state.isIgnitionEnabled();
		ECLang.translate("gui.ignition",
			ECLang.translate(ignition ? "gui.value.enabled" : "gui.value.disabled")
				.style(ignition ? ChatFormatting.GREEN : ChatFormatting.RED)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		// The one goggle-less component reading, and only when it is both missing
		// and the reason nothing is happening. Someone who has switched the
		// ignition on is trying to run the engine; without this they would be told
		// the ignition is On, hear it turn over, and have nothing at all to explain
		// why it never fires. With the ignition off, an absent plug is not yet the
		// player's problem, and saying so would be the clutter this overlay avoids.
		if (ignition && !engineComponents().hasSparkPlug())
			ECLang.translate("gui.spark_plug", ECLang.translate("gui.value.missing")
				.style(ChatFormatting.RED)
				.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);

		// Only when automation is actually holding the controls, and only that much:
		// which mode it is and how strong the signal is are goggle readings. Without
		// this line a player would have no way to tell why a switch they can see is
		// not the thing deciding whether the engine runs.
		EngineControlState control = resolveControlState();
		if (control.isRedstoneControlled())
			ECLang.translate("gui.control", ECLang.translate("gui.control.redstone")
				.style(ChatFormatting.RED)
				.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);

		CarburetorBlockEntity carburetor = engineComponents().carburetor();
		if (carburetor != null)
			ECLang.translate("gui.throttle_state",
				ECLang.translate(observedThrottleKey(control.throttlePercent()))
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
		EngineState state = getEngineState();
		return switch (phase) {
			case RUNNING -> state.getLubrication() == LubricationState.NORMAL
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
