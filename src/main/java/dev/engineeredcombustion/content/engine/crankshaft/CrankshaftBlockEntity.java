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

import dev.engineeredcombustion.advancement.EngineInteractionMemory;
import dev.engineeredcombustion.client.sound.EngineSoundManager;
import dev.engineeredcombustion.content.engine.CombustionAudio;
import dev.engineeredcombustion.content.engine.EngineAssemblyStatus;
import dev.engineeredcombustion.content.engine.EngineComponents;
import dev.engineeredcombustion.content.engine.EngineEventRecord;
import dev.engineeredcombustion.content.engine.EngineEventTracker;
import dev.engineeredcombustion.content.engine.EngineInputs;
import dev.engineeredcombustion.content.engine.EnginePhase;
import dev.engineeredcombustion.content.engine.EngineState;
import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.EngineWearInputs;
import dev.engineeredcombustion.content.engine.EngineWearMath;
import dev.engineeredcombustion.content.engine.FuelSupply;
import dev.engineeredcombustion.content.engine.LubricationState;
import dev.engineeredcombustion.content.engine.OilSupply;
import dev.engineeredcombustion.content.engine.RotationSource;
import dev.engineeredcombustion.content.engine.WearCondition;
import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlockEntity;
import dev.engineeredcombustion.content.engine.control.ControlMode;
import dev.engineeredcombustion.content.engine.control.EngineControlState;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlockEntity;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;
import dev.engineeredcombustion.content.engine.fourstroke.EngineSchema;
import dev.engineeredcombustion.content.engine.sump.OilSumpBlockEntity;
import dev.engineeredcombustion.foundation.ECLang;
import dev.engineeredcombustion.foundation.EngineCasting;
import dev.engineeredcombustion.foundation.EngineConditionText;
import dev.engineeredcombustion.network.EngineTickPayload;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECCriteriaTriggers;
import dev.engineeredcombustion.registry.ECDataComponents;
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
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
	 *
	 * <p>Authored for an engine running along X, like the model. On a Z engine the
	 * Cylinder's baked model and its Spark Plug are both turned a quarter turn, so
	 * this point has to turn with them - see {@link #sparkPlugGap}.
	 */
	private static final Vec3 SPARK_PLUG_ELECTRODE = new Vec3(11.90D / 16.0D, 13.79D / 16.0D, 8.0D / 16.0D);

	/**
	 * The version-1 crank angle, in {@code [0, 360)}. <b>Read only.</b>
	 *
	 * <p>No longer written: since Milestone 15B the authoritative position is the
	 * cycle index and cycle angle, and the physical crank angle is a fold of them.
	 * Writing it as well would put one fact on disk twice, which is how the copies come
	 * back disagreeing. The key survives so a version-1 world can still be migrated.
	 */
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
	private static final String KEY_LUBRICATION = "Lubrication";
	private static final String KEY_OIL_WEAR = "OilWear";
	private static final String KEY_SPARK_EVENT = "SparkEvent";
	private static final String KEY_COMBUSTION_EVENT = "CombustionEvent";
	private static final String KEY_GENERATING = "Generating";
	private static final String KEY_COMBUSTION_AGE = "TicksSinceCombustion";
	private static final String KEY_ACTIVE_CYLINDERS = "ActiveCylinders";
	private static final String KEY_CYLINDER_INDEX = "CylinderIndex";
	private static final String KEY_CYLINDER_COUNT = "CylinderCount";
	private static final String KEY_SPARK_PLUG_MASK = "SparkPlugMask";
	private static final String KEY_OVERSIZED = "Oversized";
	private static final String KEY_BEARING_WEAR = "BearingWear";
	private static final String KEY_CAPACITY_FACTOR = "CapacityFactor";
	private static final String KEY_ENGINE_BEARING_WEAR = "EngineBearingWear";

	/**
	 * The engine state schema this tag was written by.
	 *
	 * <p>Explicit rather than inferred from a missing key: "the cycle index is absent,
	 * so this must be a 360-degree save" works exactly once, and by the migration after
	 * this one it is ambiguous. An absent tag reads 0, which {@code EngineSchema} maps
	 * to version 1 - the saves written before versioning existed.
	 */
	private static final String KEY_SCHEMA_VERSION = "EngineVersion";

	private static final String KEY_CYCLE_INDEX = "CycleIndex";
	private static final String KEY_CYCLE_ANGLE = "CycleAngle";
	private static final String KEY_ARMED = "ArmedCylinders";
	private static final String KEY_LAST_FIRED = "LastFiredCycle";
	private static final String KEY_CAMSHAFT = "Camshaft";

	private final EngineState engine = new EngineState();

	/**
	 * What this engine has been seen to do, so that advancements can be awarded for
	 * transitions rather than for states.
	 *
	 * <p>Deliberately NOT persisted. A tracker that has never seen this engine
	 * primes itself to whatever the world was already in the middle of and reports
	 * none of it, which is precisely what stops a chunk load from re-awarding
	 * "It Really Started!" to whoever walked past - see {@link EngineEventTracker}.
	 * Saving it would have been the bug.
	 */
	private final EngineEventTracker eventTracker = new EngineEventTracker();

	/** Who was last messing with this engine. See {@link EngineInteractionMemory}. */
	private final EngineInteractionMemory interactions = new EngineInteractionMemory();

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
	 * Whether <i>this section</i> carries the engine's one Camshaft.
	 *
	 * <p>Local flag, engine-wide read - exactly the Redstone Control Module's model,
	 * and for the same reason. "Does this engine have a Camshaft" resolves through the
	 * controller; "should this block drop one" reads this field. An engine-wide answer
	 * in the drop path would have every section of an inline-4 drop a Camshaft the
	 * player only ever crafted one of.
	 *
	 * <p>Controller handover <b>moves</b> it rather than copying it - see
	 * {@link #migrateControllerConfigurationTo} - so the count of installed flags plus
	 * loose item stacks is invariant across every structural change there is.
	 */
	private boolean camshaftInstalled;

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
	 * Ticks until this engine states where it is in its cycle, whether or not
	 * anything fired.
	 *
	 * <p>Reset by every send, including one carrying real events, so a firing engine
	 * is anchored by its own bangs and never pays for a second packet - see
	 * {@link #dispatchCombustionEvents}.
	 */
	private int phaseAnchorCountdown = EngineTuning.PHASE_ANCHOR_INTERVAL_TICKS;

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
	 * Whether this section has already brought its own cosmetic block states, and
	 * those of the components stacked on it, in line with the engine around it.
	 *
	 * <p>Deliberately NOT saved. It asks "has this been done since the block
	 * entity loaded", and the honest answer after a restart is no - which is the
	 * whole point, because the thing being repaired is an engine saved by a version
	 * that did not have the casting it is now missing.
	 */
	private boolean castingsKnitted;

	/**
	 * <b>This section's own</b> bearing wear, {@code [0, 1]}.
	 *
	 * <p>Per section rather than per engine, and that is the whole architecture of
	 * this milestone in one field. A crankcase is a physical part: it carries its
	 * journal, it wears, and when it is mined it takes that wear with it on the item
	 * and brings it back when it is placed again. Nothing about which block happens
	 * to be running the engine has any bearing on it, so extending an inline-3 at
	 * the negative end - which moves the controller to a brand-new block - cannot
	 * move, reset or duplicate anybody's wear.
	 *
	 * <p>Every section keeps its own, followers included. The controller reads all
	 * of them through {@link EngineComponents} and hands the simulation an average
	 * for friction and the worst for the diagnostics; an inline-4 is therefore not
	 * four times as worn as an inline-1 merely for having four sections.
	 *
	 * <p>Absent from a world saved before this milestone, and {@code getFloat}
	 * answers 0 for a missing key, so every existing engine loads pristine.
	 */
	private float bearingWear;

	/**
	 * The last bearing figure the client was told, quantised.
	 *
	 * <p>Wear moves by about a millionth per revolution; the client needs it only to
	 * name a condition band and to trace the same coast-down curve. So the exact
	 * value stays here and a hundredth goes on the wire, which turns a whole
	 * section's service life into a hundred updates rather than one per tick.
	 */
	private float syncedBearingWear;

	/**
	 * The engine-wide average bearing wear, as last sent to the client.
	 *
	 * <p>Controller-only, and the one piece of condition the client cannot work out
	 * for itself in time: it integrates a freewheeling engine's spin-down locally,
	 * and worn bearings multiply the friction that spin-down fights. Without it the
	 * two sides would trace different curves.
	 */
	private float syncedEngineBearingWear;

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

		if (!level.isClientSide && !castingsKnitted) {
			castingsKnitted = true;
			knitCastings();
		}

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

		// Before anything this tick reads how many cylinders this engine has. The
		// client cannot run the simulation that would tell it, so it takes the shape
		// straight off the world - see adoptResolvedLayout.
		if (level.isClientSide)
			adoptResolvedLayout();

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
		boolean cannotVerifyAssembly = !tickComponents.chunksLoaded();
		setAssemblySuspended(cannotVerifyAssembly);
		if (cannotVerifyAssembly) {
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
		// The whole mask, not merely "are they all in". A four-cylinder engine that
		// loses its second plug having already lost its third changes nothing about
		// the all-or-nothing answer, and the client would have gone on drawing a plug
		// in a head that no longer has one.
		int sparkPlugMaskBefore = engine.getSparkPlugMask();
		LubricationState lubricationBefore = engine.getLubrication();
		// THE capacity basis, as one number. Compared as a mask rather than as a count
		// so that a swap - cylinder 3 dying on the tick cylinder 2 comes back - is a
		// change rather than a coincidence of equal counts.
		int activeMaskBefore = engine.getActiveCylinderMask();
		int[] sparkEventsBefore = engine.copyOfSparkEventIds();
		int[] combustionEventsBefore = engine.copyOfCombustionEventIds();

		boolean generatingBefore = engine.isActivelyGenerating();

		// The condition of the actual parts, read from the same snapshot everything
		// else this tick reads - see EngineComponents#resolveWear. Read BEFORE the
		// physics runs and written back after it, so nothing this tick wears can
		// retroactively change a power stroke that already happened.
		int[] combustionEventsBeforeWear = engine.copyOfCombustionEventIds();

		EngineInputs inputs = new EngineInputs(tickComponents.isMechanicallyValid(), control.ignitionEnabled(),
			tickComponents.cylinderCount(), sparkPlugMask, control.throttle(), readLoadFactor(), speedLimit(),
			tickComponents.resolveWear(), camshaftInstalled);
		boolean generatedSpeedChanged = engine.tickSimulation(inputs, fuelSupply, oilSupply, random);

		// Step 6 of the tick: the physics is done, so the work it represents can be
		// charged to the parts that did it. Deliberately after tickSimulation and
		// before anything downstream reads a condition.
		boolean wornThisTick = accumulateWear(tickComponents, combustionEventsBeforeWear);

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
		// `activeMaskBefore` or `generatingBefore` moves, and either can move while the
		// published speed does not: an engine held at a steady speed by another source
		// that loses a Spark Plug changes its capacity basis and nothing else.
		// The mask says WHICH cylinders are working and the capacity factor says HOW
		// STRONG they are, and either can move without the other: a plug pulled moves
		// the mask, and a piston slowly losing compression moves only the factor. Both
		// have to refresh Create's cached capacity, and the factor is already quantised
		// so that slow wear cannot do it more than a few dozen times in a part's life.
		boolean capacityBasisChanged = activeMaskBefore != engine.getActiveCylinderMask()
			|| generatingBefore != engine.isActivelyGenerating() || engine.hasCapacityFactorChanged();

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
		dispatchEngineEvents(tickComponents, wornThisTick);

		// This tick's sparks and combustions, as one small packet rather than as a
		// full block entity synchronisation per event - see
		// EngineTickPayload. The counters themselves are untouched and
		// still persisted: they are the engine's own record of what happened, and the
		// goggle diagnostics and the post-load comparison still read them. What they
		// no longer do is force the whole engine onto the wire eight times a second.
		dispatchCombustionEvents(sparkEventsBefore, combustionEventsBefore);

		// Anything the client displays has to trigger a block update, not just the
		// things that change the engine's rotation. Toggling redstone on a stopped
		// engine changes no speed and no phase, so without this the client would
		// keep showing the ignition state it was last told about.
		//
		// The capacity mask is in this list, and that is the whole of how it reaches
		// the client: on a CHANGE, never on a firing. A healthy inline-4 holds
		// 0b1111 from the moment it catches to the moment something breaks, so it
		// costs exactly nothing between those two events - which is the property that
		// keeps the combustion payload's saving intact.
		if (generatedSpeedChanged || reconciled || signalBefore != redstoneSignal
			|| phaseBefore != engine.getPhase() || structureValidBefore != engine.isStructureValid()
			|| startProgressBefore != engine.getStartProgress() || fuelBefore != engine.isFuelAvailable()
			|| sparkPlugMaskBefore != engine.getSparkPlugMask()
			|| lubricationBefore != engine.getLubrication()
			|| activeMaskBefore != engine.getActiveCylinderMask()
			|| generatingBefore != engine.isActivelyGenerating() || engine.hasCapacityFactorChanged()
			// The client integrates a freewheeling engine's spin-down itself, and worn
			// bearings multiply the friction it fights, so it needs this figure to trace
			// the server's curve. Compared against the quantised value it was last told
			// rather than against the live one, so real wear - a millionth per
			// revolution - puts nothing at all on the wire between condition steps.
			|| bearingWearMoved()) {
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
	 * How many <i>healthy cylinders' worth</i> of output this engine is providing,
	 * for the flywheel asking. Zero for any flywheel this engine does not drive.
	 *
	 * <p>What Stress Capacity is scaled by. An inline-4 supplies four times what a
	 * single does; an inline-4 with a dead plug three quarters of that; and an
	 * inline-4 whose third bore has lost its compression supplies 3.65 cylinders'
	 * worth, because a worn cylinder is still a firing cylinder and simply pushes
	 * less hard.
	 *
	 * <p>Latched and quantised by the simulation rather than computed here - see
	 * {@link EngineState#getPublishedCapacityFactor()} - because Create asks for
	 * capacity at arbitrary times and must never be handed a figure that drifts
	 * every tick.
	 */
	public float getCapacityFactorFor(BlockPos queryingFlywheelPos) {
		if (!drivesFlywheelAt(queryingFlywheelPos))
			return 0.0F;
		return getEngineState().getPublishedCapacityFactor();
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
	 * Gives the client's copy of the simulation the layout the world actually
	 * shows.
	 *
	 * <p><b>Not a second opinion.</b> {@link EngineComponents#locate} reads nothing
	 * but block states, and block states are synchronised, so this derives the very
	 * answer the server derived - the same scan, in the same class, from the same
	 * data. It is the mechanism the renderers already rely on to know which crank
	 * throw they are drawing.
	 *
	 * <p><b>Why it is needed at all.</b> The simulation's cylinder count used to
	 * reach the client only inside the block entity's data, and the client's own
	 * per-tick derivation was removed when the layout scan was unified. That was
	 * survivable while a spark or a bang forced a full block entity synchronisation
	 * several times a second; once combustion moved to its own compact payload,
	 * those updates stopped, and any moment where the count had not arrived - a
	 * layout packet still in flight behind an event packet, a section placed on the
	 * tick a chunk was sent - left the client simulating an inline-1 inside an
	 * inline-4. Every per-cylinder loop on the client is bounded by that number.
	 *
	 * <p>Fail-closed and idempotent: a run whose ends could not both be seen is not
	 * adopted, and a layout that already agrees does no work.
	 */
	private void adoptResolvedLayout() {
		if (level == null)
			return;
		EngineComponents.Placement placement = EngineComponents.locate(level, worldPosition, getAxis());
		if (!placement.isComplete())
			return;
		if (placement.count() == engine.getCylinderCount())
			return;
		// The plug mask stays the server's - a plug is an item inside a block entity,
		// not a block state, so it is the one part of the layout a block-state scan
		// genuinely cannot see.
		engine.setLayout(placement.count(), sparkPlugMask);
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
	 */
	private void setAssemblySuspended(boolean suspended) {
		if (suspended == assemblySuspended)
			return;
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
	 * <li><b>the Control Module and the Camshaft</b>, as ownership transfers rather
	 * than copies: the new controller has them and this one does not, so mining either
	 * section afterwards drops exactly one of each. Where <i>both</i> engines already
	 * had one - two equipped engines pushed together - the duplicate is ejected as a
	 * real item rather than stranded on a follower or destroyed. See
	 * {@link #handOverOneOf};</li>
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
		// Both engine-wide parts move by the same rule, through the same method, so
		// there is one place a one-per-engine component can be got wrong rather than
		// two that have to agree.
		successor.controlModuleInstalled =
			handOverOneOf(controlModuleInstalled, successor.controlModuleInstalled,
				ECItems.REDSTONE_CONTROL_MODULE.get(), newControllerPos);
		successor.camshaftInstalled = handOverOneOf(camshaftInstalled, successor.camshaftInstalled,
			ECItems.CAMSHAFT.get(), newControllerPos);
		// The behaviour owns this value, its persistence and its packet, so it is set
		// through the behaviour. setValue also marks the successor changed and sends
		// its data, which is what carries the new box to the client.
		if (controlMode != null && successor.controlMode != null)
			successor.controlMode.setValue(controlMode.getValue());

		// One of each, one owner. Clearing them here is what makes the transfers moves
		// rather than duplications, and it is what CrankshaftBlock#onRemove reads when
		// it decides whether to drop the items.
		controlModuleInstalled = false;
		camshaftInstalled = false;
		// The follower has no controls left to be commanded through, and a stale
		// number here would still be printed by the overlay.
		redstoneSignal = 0;

		successor.setChanged();
		successor.sync();
	}

	/**
	 * Hands one engine-wide component to the engine's new controller, conserving it.
	 *
	 * <p>Three cases, and the third is the one this method exists for:
	 * <ul>
	 * <li>only the old controller had one - it moves;</li>
	 * <li>only the new one had one - it stays where it is;</li>
	 * <li><b>both had one</b> - two equipped engines have just been pushed together.
	 * The successor keeps its own and the loser is <i>ejected as a real item</i>, next
	 * to the section it is leaving.</li>
	 * </ul>
	 *
	 * <p>That third case used to strand the loser's flag on a follower, where it was
	 * conserved but invisible: the player got the part back only if they happened to
	 * mine that particular block. Conservative, and poor - so it now comes out where
	 * the player can see it. The ledger is unchanged either way, which is the property
	 * that matters: one flag becomes one item, never zero and never two.
	 *
	 * @return whether the successor should hold the component afterwards
	 */
	private boolean handOverOneOf(boolean mine, boolean successors, net.minecraft.world.item.Item item,
		BlockPos successorPos) {
		if (!mine)
			return successors;
		if (!successors)
			return true;
		if (level != null)
			Block.popResource(level, successorPos, new ItemStack(item));
		return true;
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
		// Working the ignition is messing with the engine, so whatever it does over
		// the next few seconds is this player's doing.
		rememberInteraction(player);
		boolean on = toggleManualIgnition();
		playSound(SoundEvents.LEVER_CLICK, 0.4F, on ? 0.72F : 0.58F);
		ECLang.translate("gui.ignition", ECLang.translate(on ? "gui.value.enabled" : "gui.value.disabled")
			.style(on ? ChatFormatting.GREEN : ChatFormatting.RED)
			.component())
			.style(ChatFormatting.WHITE)
			.sendStatus(player);
		if (on)
			reportStartBlocker(player);
	}

	/**
	 * Says, once, why this engine is not going to start.
	 *
	 * <p>Sent only when a player has just switched the ignition ON and the engine
	 * is not running - which is the exact moment someone is trying to start it and
	 * wondering why nothing happened. Never from the tick, so there is no
	 * possibility of it nagging.
	 *
	 * <h2>Only one line, and only true ones</h2>
	 * The first blocker found wins, because a player fixes one thing at a time and
	 * a list of four faults is a worse answer than the first one. And every line
	 * here is a genuine blocker: <b>low or missing oil is deliberately absent</b>,
	 * because the simulation really does let an engine run dry, and saying "Needs
	 * Oil" would be the tutorial telling a lie the game does not back up. The
	 * goggles carry that warning instead, where it belongs - as a danger rather
	 * than as a refusal.
	 *
	 * <p>The last line is not a fault at all. An engine with everything it needs
	 * still will not start on its own, and "crank it" is the single most useful
	 * thing this method can say to somebody who has just built their first one.
	 */
	private void reportStartBlocker(Player player) {
		if (engine.getPhase() == EnginePhase.RUNNING)
			return;
		EngineComponents components = engineComponents();
		if (!components.isMechanicallyValid())
			// The structure itself is wrong. The assembly status already has its own
			// far more detailed readout, so this stays quiet rather than duplicating it.
			return;

		String key;
		// The valvetrain comes first, and deliberately before the plugs: an engine with
		// no Camshaft cannot draw a charge at all, so telling the player about a missing
		// plug would send them to fix the second thing wrong with it.
		if (!engineHasCamshaft())
			key = "gui.start_no_camshaft";
		else if (!components.hasSparkPlug())
			key = "gui.start_no_spark_plug";
		else if (!hasEveryPiston(components))
			key = "gui.start_no_piston";
		else if (components.carburetor() == null || !components.carburetor()
			.holdsValidFuel())
			key = "gui.start_no_gasoline";
		else
			key = "gui.start_needs_cranking";

		ECLang.translate(key)
			.style(key.equals("gui.start_needs_cranking") ? ChatFormatting.WHITE : ChatFormatting.RED)
			.sendStatus(player);
	}

	private static boolean hasEveryPiston(EngineComponents components) {
		for (EngineComponents.Cylinder cylinder : components.cylinders())
			if (!cylinder.hasPiston())
				return false;
		return true;
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

	// --- camshaft ------------------------------------------------------------
	//
	// One Camshaft per engine, installed through any Crankshaft section, owned by the
	// controller and moved on handover. Exactly the Redstone Control Module's model -
	// see hasControlModule/installControlModule above - because it is exactly the same
	// problem, and two engine-wide parts that behaved differently would be two chances
	// to get item conservation wrong.

	/** Whether <i>this section</i> carries the Camshaft. What the drop path reads. */
	public boolean hasCamshaft() {
		return camshaftInstalled;
	}

	/**
	 * Whether the engine this section belongs to has a Camshaft, wherever it is
	 * installed. What the simulation, the diagnostics and the renderers read.
	 */
	public boolean engineHasCamshaft() {
		return getEngineController().camshaftInstalled;
	}

	/**
	 * Fits the engine's Camshaft.
	 *
	 * @return false when the engine already has one
	 */
	public boolean installCamshaft() {
		CrankshaftBlockEntity controller = getEngineController();
		if (controller != this)
			// One engine, one camshaft: it always goes into the section that runs the
			// engine, wherever the player clicked.
			return controller.installCamshaft();
		if (camshaftInstalled)
			return false;
		camshaftInstalled = true;
		setChanged();
		sync();
		return true;
	}

	/**
	 * Takes the Camshaft back out.
	 *
	 * @return false when there was nothing to remove
	 */
	public boolean removeCamshaft() {
		CrankshaftBlockEntity controller = getEngineController();
		if (controller != this)
			return controller.removeCamshaft();
		if (!camshaftInstalled)
			return false;
		camshaftInstalled = false;
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

		// Every counter the engine has, not merely the ones the current layout uses.
		// The diff is what it is: a counter that moved describes an event that really
		// happened, and bounding the comparison by a number that could be smaller than
		// the array is how an event gets silently dropped rather than sent.
		int sparkMask = 0;
		int combustionMask = 0;
		for (int cylinder = 0; cylinder < EngineTuning.MAX_CYLINDERS; cylinder++) {
			if (engine.getSparkEventId(cylinder) != sparkEventsBefore[cylinder])
				sparkMask |= 1 << cylinder;
			if (engine.getCombustionEventId(cylinder) != combustionEventsBefore[cylinder])
				combustionMask |= 1 << cylinder;
		}
		boolean events = (sparkMask | combustionMask) != 0;

		// THE PHASE ANCHOR, and why it is not sent every tick.
		//
		// A running engine anchors on every bang it makes, because the packet was
		// going anyway - so an inline-4 at full throttle corrects its clients four
		// times a revolution for nothing. What has no bangs is an engine being MOTORED
		// by another Create source, and that is exactly the case where the client's own
		// integration can quietly walk a whole revolution out of phase and draw a
		// convincing engine with its valves on the wrong stroke. Those get an anchor on
		// a timer instead, and only while the crank is actually turning: a stopped
		// engine cannot drift.
		//
		// The countdown is reset by ANY send, so a firing engine never also pays for a
		// timed one.
		boolean turning = engine.getMechanicalRpm() != 0.0F;
		boolean anchorDue = turning && --phaseAnchorCountdown <= 0;
		if (!events && !anchorDue)
			return;
		phaseAnchorCountdown = EngineTuning.PHASE_ANCHOR_INTERVAL_TICKS;

		// Every cylinder that did anything this tick, in one packet. An inline-4 at
		// full throttle therefore costs exactly what an inline-1 does: at most one
		// packet per tick, whatever is happening inside it.
		//
		// Addressed to the players tracking the CONTROLLER's chunk. That is the block
		// entity that owns the engine and the position the payload names, so it is
		// also the chunk a client must have in order to resolve the engine at all.
		PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(worldPosition),
			new EngineTickPayload(worldPosition, (byte) sparkMask, (byte) combustionMask,
				engine.getCycleAngleDegrees(), (byte) engine.getArmedMask()));
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
	 * <p><b>The payload decides which cylinders fired; this side only decides where
	 * they are.</b> The bound below is therefore the engine resolved from the world
	 * - the blocks the player can see, capped at
	 * {@link EngineTuning#MAX_CYLINDERS} - and never the client simulation's own
	 * cylinder count. Bounding it by the simulation meant that a client which had
	 * not yet been told the engine's shape discarded every bit above its stale
	 * count: on an inline-4 believed to be an inline-1, cylinders 2, 3 and 4 fired
	 * in silence and darkness, and the firing rhythm the audio measures was a
	 * quarter of the truth.
	 *
	 * @param sparkMask      bit {@code i} set when cylinder {@code i}'s coil fired
	 * @param combustionMask bit {@code i} set when cylinder {@code i} burned a charge
	 */
	@OnlyIn(Dist.CLIENT)
	public void playCombustionEvents(byte sparkMask, byte combustionMask) {
		if (level == null)
			return;
		int resolvedCylinders = Math.min(engineComponents().cylinderCount(), EngineTuning.MAX_CYLINDERS);
		for (int cylinder = 0; cylinder < resolvedCylinders; cylinder++) {
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
	 * Brings this section's stack of cosmetic block states back in line with the
	 * engine around it, once, on the first tick after it loads.
	 *
	 * <p>The crankcase's seams, the shared intake manifold and the way the Oil Sump
	 * and Carburetor are turned all live in block state properties maintained by
	 * {@code updateShape}, which vanilla only calls when a neighbour changes. An
	 * engine that was built before one of those properties existed therefore comes
	 * back out of a save with it unset, and would keep its old, disjointed look
	 * until the player happened to break a block next to it.
	 *
	 * <p>Done from here rather than from each block because the crankshaft is the
	 * one part of the stack that ticks, and because it already knows where the rest
	 * of the stack is. Four positions, once per section per load, each of which
	 * writes nothing at all when the state is already right.
	 */
	private void knitCastings() {
		if (level == null)
			return;
		EngineCasting.refresh(level, worldPosition);
		EngineCasting.refresh(level, EngineComponents.cylinderPos(worldPosition));
		EngineCasting.refresh(level, EngineComponents.carburetorPos(worldPosition));
		EngineCasting.refresh(level, EngineComponents.oilSumpPos(worldPosition));
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
		Vec3 gap = sparkPlugGap();
		double x = cylinderPos.getX() + gap.x;
		double y = cylinderPos.getY() + gap.y;
		double z = cylinderPos.getZ() + gap.z;

		level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, 0.0D, 0.0D, 0.0D);

		if (engine.getPhase() != EnginePhase.RUNNING)
			level.playLocalSound(x, y, z, ECSounds.ENGINE_SPARK.get(), SoundSource.BLOCKS,
				EngineTuning.SOUND_SPARK_VOLUME, 1.0F, false);
	}

	/**
	 * The spark gap in this engine's own orientation.
	 *
	 * <p>The quarter turn a Z engine's Cylinder is drawn with maps the block's
	 * (x, z) to (1 - z, x), which is what the blockstate's {@code "y": 90} does to
	 * the baked model and what {@code rotate_y90} does to the Spark Plug's partial.
	 * The particle has to make the same turn or it would fire beside the plug
	 * instead of at it.
	 */
	private Vec3 sparkPlugGap() {
		if (getAxis() != Axis.Z)
			return SPARK_PLUG_ELECTRODE;
		return new Vec3(1.0D - SPARK_PLUG_ELECTRODE.z, SPARK_PLUG_ELECTRODE.y, SPARK_PLUG_ELECTRODE.x);
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

	// --- bearing wear --------------------------------------------------------

	/**
	 * Charges this tick's mechanical work to the parts that did it.
	 *
	 * <p>Step 6 of the tick, and the only place any wear is written. It runs after
	 * the simulation, on the server, in the controller, against the same component
	 * snapshot the simulation was handed - so the wear a part accumulates for this
	 * tick can never reach back and change the power stroke that produced it.
	 *
	 * <h2>What is charged, and to whom</h2>
	 * <ul>
	 * <li><b>every crankshaft section</b> gets bearing wear for the revolutions the
	 * crank actually turned, whoever turned it. Being motored by another Create
	 * source is real mechanical work on real bearings, and a dry engine being
	 * motored is destroying them just as fast as a dry engine running;</li>
	 * <li><b>every cylinder with a Piston Assembly in it</b> gets ring and bore wear
	 * for the same revolutions - the piston is being dragged up and down whether or
	 * not anything burns above it;</li>
	 * <li><b>only cylinders that actually burned a charge this tick</b> additionally
	 * get combustion wear. That is the half a motored engine must never accumulate,
	 * and it is charged from the very counter that consumed the fuel and delivered
	 * the torque, so it cannot be claimed by combustion that did not happen.</li>
	 * </ul>
	 *
	 * <p>Everything is quoted per revolution rather than per tick, so wear follows
	 * the work the machine did rather than how long the server ran, and a server
	 * below 20 TPS wears its engines at the same rate per revolution as one at full
	 * speed.
	 *
	 * @param combustionEventsBefore each cylinder's combustion counter as it stood
	 *                               before the simulation ran; a counter that moved
	 *                               is a charge that really burned
	 */
	private boolean accumulateWear(EngineComponents components, int[] combustionEventsBefore) {
		float revolutions = engine.getRevolutionsThisTick();
		if (revolutions <= 0.0F)
			return false;

		float rpm = engine.getMechanicalRpm();
		float load = engine.getLoadFactor();
		LubricationState lubrication = engine.getLubrication();
		boolean filtered = components.hasAirFilter();
		// 1 in any normal game. See EngineTuning#WEAR_MULTIPLIER_PROPERTY - it exists
		// so that a whole engine's service life fits inside a manual test.
		float rate = EngineTuning.wearRateMultiplier() * revolutions;

		float bearing = EngineWearMath.bearingWearPerRevolution(lubrication, rpm, load) * rate;
		float motion = EngineWearMath.cylinderWearPerRevolution(lubrication, rpm, load, filtered) * rate;
		float perCombustion = EngineWearMath.cylinderWearPerCombustion(lubrication, rpm, load, filtered)
			* EngineTuning.wearRateMultiplier();

		for (EngineComponents.Cylinder cylinder : components.cylinders()) {
			CrankshaftBlockEntity section = cylinder.crankshaft();
			if (section != null)
				section.addBearingWear(bearing);

			CylinderBlockEntity bore = cylinder.blockEntity();
			if (bore == null || !bore.hasPistonAssembly())
				continue;
			float wear = motion;
			int index = cylinder.index();
			if (index < combustionEventsBefore.length && engine.getCombustionEventId(index) != combustionEventsBefore[index])
				wear += perCombustion;
			bore.addPistonWear(wear);
		}
		// The engine genuinely wore this tick. That is what gates every condition
		// advancement - see EngineEventTracker - so that wear which arrived any other
		// way cannot award one.
		return true;
	}

	/**
	 * Offers everything this engine just did to whoever deserves the credit.
	 *
	 * <p>Server-side, controller-only, and the single place advancement progress
	 * originates. There is no scan anywhere: the tracker compares this tick against
	 * the last one and almost always has nothing to say, so the common cost of this
	 * method is one virtual call and an empty list.
	 *
	 * <p>Events are split by whether the mod can <b>know</b> who did it.
	 *
	 * <p>Fitting a part or completing a repair arrives through an interaction with
	 * a player attached, so those are attributed exactly and go to nobody else.
	 * Everything else uses the nearby path, which prefers the recent interactor
	 * when there is one and otherwise credits whoever is close enough to be
	 * watching.
	 *
	 * <p>That includes <b>cranking and starting</b>, and deliberately so. An engine
	 * is turned over with Create's Hand Crank, which is Create's block and gives
	 * this mod no callback naming the player - so there is no interaction to
	 * attribute to. What there is instead is a guarantee that is just as good: the
	 * Hand Crank must be held down, adjacent to the engine, for the whole of the
	 * cranking, so anybody who cranked an engine is by construction standing next
	 * to it. Insisting on exact attribution here would mean never awarding the
	 * mod's two most important advancements to the person who earned them.
	 */
	private void dispatchEngineEvents(EngineComponents components, boolean wornThisTick) {
		if (!(level instanceof ServerLevel serverLevel))
			return;

		EngineWearInputs wear = engine.getWear();
		int cylinders = components.cylinderCount();
		List<EngineEventRecord> events = eventTracker.tick(engine.getPhase(), engine.isActivelyGenerating(),
			engine.isStructureValid(), cylinders, Integer.bitCount(engine.getActiveCylinderMask()),
			engine.getLubrication(), engine.getMechanicalRpm(), engine.getLoadFactor(), wornThisTick,
			wear.mechanicalCondition(), worstCompressionCondition(wear, cylinders),
			wear.overallCondition(cylinders));
		if (events.isEmpty())
			return;

		for (EngineEventRecord record : events)
			switch (record.event()) {
				// Arrived through an interaction that named a player. Nobody else may
				// have these.
				case ASSEMBLED, MAINTENANCE_COMPLETED -> interactions.fireAttributed(serverLevel, record);
				// Everything else, including cranking and starting. See the note above.
				default -> interactions.fireNearby(serverLevel, worldPosition.getX() + 0.5D,
					worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D, record);
			}
	}

	/** The worst compression of any of this engine's cylinders. */
	private static WearCondition worstCompressionCondition(EngineWearInputs wear, int cylinderCount) {
		WearCondition worst = WearCondition.PRISTINE;
		for (int cylinder = 0; cylinder < cylinderCount; cylinder++)
			worst = WearCondition.worst(worst, wear.compressionCondition(cylinder));
		return worst;
	}

	/**
	 * Records that a player just did something to this engine, so that whatever it
	 * does over the next few seconds is credited to them.
	 *
	 * <p>Called from the interactions that constitute "messing with an engine":
	 * cranking it, switching its ignition, changing its throttle, and fitting or
	 * removing any of its parts. Routed to the controller because attribution
	 * belongs to the engine rather than to the section that happened to be clicked.
	 */
	public void rememberInteraction(Player player) {
		if (level instanceof ServerLevel serverLevel)
			getEngineController().interactions.remember(player, serverLevel);
	}

	/**
	 * Looks at what this engine has just become and, if it is something the mod
	 * does not support, tells the player who built it.
	 *
	 * <p>Called from block placement rather than from the tick, because it is about
	 * a thing a player just did and because an unsupported layout is a stable state
	 * - polling it every tick would say the same thing forever.
	 *
	 * <p>Reads the layout fresh rather than trusting a cached one: the block that
	 * triggered this was placed moments ago and the engine may not have ticked
	 * since.
	 */
	public void reportLayoutIfRefused(Player player) {
		if (level == null || level.isClientSide)
			return;
		EngineComponents components = resolveComponents();
		if (components.status() == EngineAssemblyStatus.OVERSIZED)
			reportInvalidLayout(player, EngineEventRecord.InvalidLayout.TOO_MANY_CYLINDERS);
		else if (components.hasFlywheelConflict())
			reportInvalidLayout(player, EngineEventRecord.InvalidLayout.SECOND_FLYWHEEL);
	}

	/**
	 * Reports that a player tried to build something this engine does not support.
	 *
	 * <p>Fired at the moment of refusal - the layout stays invalid, and these are
	 * jokes about finding an edge rather than a way past one.
	 */
	public void reportInvalidLayout(Player player, EngineEventRecord.InvalidLayout reason) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer))
			return;
		rememberInteraction(player);
		ECCriteriaTriggers.ENGINE_EVENT.get()
			.fire(serverPlayer, EngineEventRecord.invalidLayout(reason));
	}

	/**
	 * Reports that maintenance improved this engine.
	 *
	 * <p>Takes both ends of the repair because only the pair is meaningful: pulling
	 * a worn part out and pushing the same one back changes nothing, and must not
	 * read as a repair. The tracker rejects any pair that is not an improvement.
	 */
	public void reportMaintenance(Player player, WearCondition before, WearCondition after) {
		if (!(level instanceof ServerLevel serverLevel))
			return;
		CrankshaftBlockEntity controller = getEngineController();
		controller.interactions.remember(player, serverLevel);
		int cylinders = controller.engineComponents()
			.cylinderCount();
		EngineEventRecord record = controller.eventTracker.maintenance(before, after, cylinders,
			Integer.bitCount(controller.engine.getActiveCylinderMask()));
		if (record != null)
			controller.interactions.fireAttributed(serverLevel, record);
	}

	/**
	 * Whether the engine's average bearing wear has moved far enough since the
	 * client was last told for it to need telling again.
	 *
	 * <p>A pure question - {@link #syncAndRearmResync()} records the answer when an
	 * update actually goes out, so this can be short-circuited past without leaving
	 * that record stale.
	 *
	 * <p>Quantised, because the client needs this figure only to name a condition
	 * band and to trace the same coast-down curve the server does. Comparing the
	 * live value would put a block entity update on the wire every single tick a
	 * worn engine turns, which is exactly the traffic this milestone must not add.
	 */
	private boolean bearingWearMoved() {
		return quantisedEngineBearingWear() != syncedEngineBearingWear;
	}

	private float quantisedEngineBearingWear() {
		return EngineWearMath.quantiseWear(engine.getWear()
			.averageBearingWear());
	}

	/**
	 * The engine's condition as one word: the worse of its mechanical condition and
	 * any one cylinder's compression.
	 *
	 * <p>Deliberately the worst rather than an average - an inline-4 with three
	 * perfect bores and one at the service limit is an engine that needs a piston,
	 * and averaging would call it lightly used.
	 *
	 * <p>Resolved from the blocks rather than from the simulation, so it is valid on
	 * both sides: the client is told each Cylinder's and each section's own wear as
	 * ordinary block entity state, which is also why per-cylinder compression never
	 * has to travel with the engine.
	 */
	public WearCondition getEngineCondition() {
		return conditionOf(engineComponents());
	}

	/** The same rule, for a caller that has already resolved the engine. */
	private static WearCondition conditionOf(EngineComponents components) {
		WearCondition condition = WearCondition.of(components.resolveWear()
			.averageBearingWear());
		for (EngineComponents.Cylinder cylinder : components.cylinders())
			if (cylinder.hasPiston())
				condition = WearCondition.worst(condition, WearCondition.of(cylinder.pistonWear()));
		return condition;
	}

	/**
	 * The engine's condition, its parts' exact wear and every factor currently
	 * driving it, as one line of text.
	 *
	 * <p>Development diagnostics: this is the only place in the mod that puts raw
	 * wear floats anywhere, and nothing player-facing calls it. It is here rather
	 * than in a debug command so that it costs a dedicated server nothing and can be
	 * reached from a breakpoint or a temporary log line while tuning.
	 */
	public String describeWear() {
		EngineComponents components = engineComponents();
		EngineWearInputs wear = components.resolveWear();
		float rpm = engine.getMechanicalRpm();
		float load = engine.getLoadFactor();
		StringBuilder text = new StringBuilder("Engine wear at ").append(getControllerPos())
			.append(String.format(": average bearings %.4f, worst bearing %.4f", wear.averageBearingWear(),
				wear.worstBearingWear()));
		for (EngineComponents.Cylinder cylinder : components.cylinders())
			text.append(String.format(", C%d piston %.4f", cylinder.index() + 1, cylinder.pistonWear()));
		return text.append(String.format(
			", oil factor %.1f, filter factor %.1f, rpm factor %.3f, load factor %.3f, friction %.3f, effective capacity %.3f cylinders",
			EngineWearMath.lubricationWearMultiplier(engine.getLubrication()),
			EngineWearMath.filtrationWearMultiplier(components.hasAirFilter()), EngineWearMath.rpmWearFactor(rpm),
			EngineWearMath.bearingLoadWearFactor(load), wear.bearingFrictionMultiplier(),
			engine.getPublishedCapacityFactor()))
			.toString();
	}

	/**
	 * This section's own bearing wear. Server-exact; the client's copy is quantised
	 * to a hundredth, which is finer than any condition band it names.
	 */
	public float getBearingWear() {
		return bearingWear;
	}

	/** This section's bearing condition, in the words the player is shown. */
	public WearCondition getBearingCondition() {
		return WearCondition.of(bearingWear);
	}

	/**
	 * Fits this section with a crankcase that has already done {@code wear} worth of
	 * work.
	 *
	 * <p>Called when the block is placed, from the item's data - see
	 * {@link #applyImplicitComponents}. A freshly crafted Crankshaft carries none;
	 * one that has been in an engine before is exactly as tired as it was when it
	 * was mined.
	 */
	public void setBearingWear(float wear) {
		bearingWear = EngineWearMath.clampWear(wear);
		syncedBearingWear = EngineWearMath.quantiseWear(bearingWear);
	}

	/**
	 * Wears this section's bearings by one tick's worth of work.
	 *
	 * <p>Called from the engine controller on the server, once per tick, with the
	 * increment the pure wear model computed - see {@link #accumulateWear}. Nothing
	 * is decided here; this only keeps the number.
	 *
	 * <p>Deliberately silent most of the time. The exact value lives in this field
	 * and is written to disk whenever the block entity is saved; the client and the
	 * chunk's dirty flag are only involved when the quantised figure actually moves.
	 */
	public void addBearingWear(float delta) {
		if (delta <= 0.0F || level == null || level.isClientSide)
			return;
		float updated = EngineWearMath.clampWear(bearingWear + delta);
		if (updated == bearingWear)
			return;
		bearingWear = updated;

		float quantised = EngineWearMath.quantiseWear(bearingWear);
		if (quantised == syncedBearingWear)
			return;
		syncedBearingWear = quantised;
		setChanged();
		sync();
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
		// WHICH SCHEMA WROTE THIS. Read first, because what every rotational key below
		// means depends on the answer - see EngineSchema.
		int schemaVersion = EngineSchema.versionOf(tag.getInt(KEY_SCHEMA_VERSION));
		boolean legacySave = EngineSchema.needsMigration(schemaVersion);

		if (legacySave) {
			// A version-1 save holds one crank angle in [0, 360) and no cycle at all.
			// One physical angle is two cycle positions on two different strokes, and
			// nothing on disk distinguishes them - so this does not guess. It keeps the
			// piston exactly where the player left it, on the compression/power half,
			// and makes that choice safe by refusing to carry anything that could turn
			// into free power: no arming latch, no firing key, no burning charge.
			engine.setCrankAngleDegrees(tag.getFloat(KEY_CRANK_ANGLE));
			engine.setArmedMask(0);
			engine.setLastFiredCycles(new long[0]);
		} else {
			// The authoritative pair, restored together. Persisted deliberately: two
			// floats and a long, and keeping them means a chunk reload does not snap the
			// piston to a new position OR put the valvetrain a stroke out of phase.
			engine.setCyclePosition(tag.getLong(KEY_CYCLE_INDEX), tag.getFloat(KEY_CYCLE_ANGLE));
			engine.setArmedMask(tag.getInt(KEY_ARMED));
			engine.setLastFiredCycles(tag.getLongArray(KEY_LAST_FIRED));
		}
		// A running engine should survive a chunk reload rather than silently dying,
		// so the phase and the engine's own momentum are restored too. Structure
		// validity and the effective ignition are re-derived from the world on the
		// next server tick.
		engine.setPhase(legacySave ? EngineSchema.migratedPhase(EnginePhase.byId(tag.getString(KEY_PHASE)))
			: EnginePhase.byId(tag.getString(KEY_PHASE)));
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
		// This section's own bearings. Absent from a world saved before this
		// milestone, and getFloat answers 0 for a missing key, so every existing
		// crankcase comes back pristine - which is the only honest answer for a part
		// that has never worn anything.
		setBearingWear(tag.getFloat(KEY_BEARING_WEAR));

		if (clientPacket) {
			// The client is shown what the server decided, never a second opinion:
			// the published speed for the diagnostics, and the one authoritative
			// answer to "is this engine producing power", which its overlays, its
			// audio and its rotation rule all read.
			engine.setPublishedRpm(tag.getFloat(KEY_PUBLISHED_RPM));
			engine.setActivelyGenerating(tag.getBoolean(KEY_GENERATING));
			// And HOW MUCH of it is producing power. The combustion ages this is
			// derived from are server-only simulation state, so before this arrived
			// the client's own derivation answered "no cylinders" on every engine that
			// ever ran - which is precisely what an inline-4 at full throttle
			// reporting "Active Cylinders: 0 / 4" was.
			engine.setActiveCylinderMask(tag.getInt(KEY_ACTIVE_CYLINDERS));
			// And HOW STRONG those cylinders are, which is a different question the
			// client also cannot answer for itself: the per-cylinder compression behind
			// it is resolved from blocks the client may not have been told about yet.
			engine.setPublishedCapacityFactor(tag.getFloat(KEY_CAPACITY_FACTOR));
			// The engine's average bearing wear. The one piece of condition the client's
			// own physics needs: it integrates a freewheeling engine's spin-down itself,
			// and worn bearings multiply the friction that spin-down fights.
			engine.setWear(EngineWearInputs.ofBearings(tag.getFloat(KEY_ENGINE_BEARING_WEAR)));
		} else {
			// Off disk. How long ago a charge last burned is simulation state, not
			// bookkeeping: it is the condition an external source cannot fake, and
			// dropping it used to make a saved running engine disown its own kinetic
			// network for a tick before claiming it back.
			if (legacySave) {
				// The ages are a reading of combustion measured against a firing interval
				// that has just doubled, so every entry in them is now the wrong unit.
				// Cleared rather than converted: rebuilding the mask from genuine
				// four-stroke combustion costs a fraction of a second of HUD continuity,
				// and cannot hand Create capacity for an engine that has not yet burned
				// anything under the new rules - which it cannot have, since it has no
				// Camshaft.
				engine.setTicksSinceCombustion(-1);
			} else {
				int[] combustionAges = tag.getIntArray(KEY_COMBUSTION_AGE);
				if (combustionAges.length > 0)
					engine.setTicksSinceCombustion(combustionAges);
				else
					// A save from before this engine had cylinders to count separately.
					engine.setTicksSinceCombustion(tag.getInt(KEY_COMBUSTION_AGE));
			}
			// The published speed is deliberately NOT restored - it is a cached
			// derivative of the momentum above, so it is reconstructed from that
			// momentum instead, and the first reconciled server tick then replaces
			// even the reconstruction with a freshly derived value. What this refuses
			// to do is let a number Create happened to be holding at save time outlive
			// the physical state it was supposed to describe.
			// A migrated engine never comes back generating. It has no valvetrain, so it
			// cannot legitimately be combusting, and claiming otherwise would publish
			// Stress Capacity for power it has no way to produce. Its momentum is kept -
			// EnginePhase.COASTING is precisely "stopped burning, still turning" - so
			// nothing snaps to a halt and it reaches rest through the ordinary spin-down
			// the player can watch.
			engine.restoreAfterLoad(!legacySave && tag.getBoolean(KEY_GENERATING));
			if (legacySave)
				// Its progress counts firing events that no longer happen at that rate.
				engine.setStartAttempt(0, 0);
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
		// Absent from every version-1 world, and getBoolean answers false - which is
		// the correct and deliberate answer: existing engines get NO free Camshaft.
		// There is no legacy compatibility flag and no virtual valvetrain; the player
		// crafts one and fits it, exactly as a new engine's owner does.
		camshaftInstalled = tag.getBoolean(KEY_CAMSHAFT);
		engine.setCamshaftInstalled(camshaftInstalled);
		redstoneSignal = tag.getInt(KEY_REDSTONE_SIGNAL);
		if (!legacySave)
			engine.setStartAttempt(tag.getInt(KEY_START_PROGRESS), tag.getInt(KEY_START_REQUIRED));
		engine.setFuelAvailable(tag.getBoolean(KEY_FUEL_AVAILABLE));
		// There is no second, all-or-nothing spark plug flag to read back here any
		// more. SparkPlugMask above is the whole answer, per cylinder, and reading a
		// boolean after it - which is what this line used to do - overwrote that mask
		// with "all of them" or "none of them": on the client an inline-4 missing one
		// plug became an inline-4 with no plugs at all.
		engine.setLubrication(LubricationState.byId(tag.getString(KEY_LUBRICATION)));
		// Persisted so a chunk reload does not hand the player free oil by
		// discarding the revolutions already banked towards the next draw.
		engine.setCombustionEventsSinceOilDraw(tag.getInt(KEY_OIL_WEAR));
		// The engine's own record of how many times each cylinder has sparked and
		// burned. No longer the event channel - EngineTickPayload is -
		// but still real state: it is what the server diffs each tick to decide which
		// bits to set, so it has to survive a reload rather than restart from zero.
		engine.setEventIds(tag.getIntArray(KEY_SPARK_EVENT), tag.getIntArray(KEY_COMBUSTION_EVENT));
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		// What schema this tag is in. Written on both paths, so a client packet is as
		// self-describing as a save file and neither has to be inferred.
		tag.putInt(KEY_SCHEMA_VERSION, EngineSchema.CURRENT_VERSION);
		// THE AUTHORITATIVE POSITION: which cycle, and where in it. The physical crank
		// angle is deliberately NOT saved beside them - it is a fold of the cycle angle,
		// and a saved representation beside the thing it represents is how the two come
		// back disagreeing.
		tag.putLong(KEY_CYCLE_INDEX, engine.getCycleIndex());
		tag.putFloat(KEY_CYCLE_ANGLE, engine.getCycleAngleDegrees());
		// Which cylinders are holding a charge they have not burned. Persisted because
		// it is physical state - a cylinder really has inhaled - and synchronised
		// because the client needs it to know whether a bang is coming.
		tag.putInt(KEY_ARMED, engine.getArmedMask());
		tag.putString(KEY_PHASE, engine.getPhase()
			.getId());
		tag.putFloat(KEY_SIMULATED_RPM, engine.getSimulatedRpm());
		// Client only. On disk this would be a second, competing copy of a speed the
		// simulated RPM above already determines - and it is exactly the copy that
		// used to come back stale and stay stale. The client still needs it: the
		// goggle diagnostics print what Create is really being told, and only the
		// server knows that.
		if (clientPacket) {
			tag.putFloat(KEY_PUBLISHED_RPM, engine.getPublishedRpm());
			// WHICH cylinders are carrying the engine, one bit each - and the client's
			// only possible source for it, because the combustion ages it is derived
			// from are server-side simulation state that stays here.
			//
			// Client packet only, exactly like the published speed above and for the
			// same reason: on disk this would be a second copy of something the ages
			// below already determine, and a saved representation beside the thing it
			// represents is how the two come back disagreeing. Loading re-derives it.
			tag.putInt(KEY_ACTIVE_CYLINDERS, engine.getActiveCylinderMask());
			// Client only, and derived like the published speed above: on disk this
			// would be a second copy of something the parts' own wear already
			// determines, and a saved representation beside the thing it represents is
			// how the two come back disagreeing. Loading rebuilds both.
			tag.putFloat(KEY_CAPACITY_FACTOR, engine.getPublishedCapacityFactor());
			tag.putFloat(KEY_ENGINE_BEARING_WEAR, engine.getWear()
				.averageBearingWear());
		} else {
			tag.putIntArray(KEY_COMBUSTION_AGE, engine.copyOfTicksSinceCombustion());
			// Which firing opportunity each cylinder last took. Disk only: it is what
			// makes a duplicate combustion detectable, and a save taken between a
			// cylinder's ignition and the end of its power stroke would otherwise come
			// back able to light the very same opportunity a second time. The client
			// never asks the question, so it is never sent one.
			tag.putLongArray(KEY_LAST_FIRED, engine.copyOfLastFiredCycles());
		}
		tag.putBoolean(KEY_GENERATING, engine.isActivelyGenerating());
		tag.putBoolean(KEY_IGNITION, engine.isIgnitionEnabled());
		tag.putBoolean(KEY_STRUCTURE_VALID, engine.isStructureValid());
		tag.putBoolean(KEY_MANUAL_IGNITION, manualIgnition);
		tag.putBoolean(KEY_CONTROL_MODULE, controlModuleInstalled);
		// THIS SECTION'S OWN flag, like the module's. Written to disk because it is the
		// authoritative record of where a real item lives, and to the client because the
		// valvetrain has to be drawn and a missing one has to be diagnosed.
		tag.putBoolean(KEY_CAMSHAFT, camshaftInstalled);
		tag.putInt(KEY_REDSTONE_SIGNAL, redstoneSignal);
		tag.putInt(KEY_START_PROGRESS, engine.getStartProgress());
		tag.putInt(KEY_START_REQUIRED, engine.getRequiredStartCycles());
		tag.putBoolean(KEY_FUEL_AVAILABLE, engine.isFuelAvailable());
		tag.putString(KEY_LUBRICATION, engine.getLubrication()
			.getId());
		tag.putInt(KEY_OIL_WEAR, engine.getCombustionEventsSinceOilDraw());
		// One counter per cylinder, because a spark and a bang happen at a PLACE. They
		// are the server's running tally, not the wire format: the live events reach
		// the client through EngineTickPayload, and these are what the
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
		// THIS SECTION'S OWN bearings, and the one number here that belongs to the
		// block rather than to the engine. Written on both paths: to disk because it
		// is the authoritative record of a physical part, and to the client because
		// the sneak diagnostics report the condition of the section being looked at.
		tag.putFloat(KEY_BEARING_WEAR, bearingWear);
	}

	// --- item data ------------------------------------------------------------
	//
	// A crankcase that is mined has to keep its bearings' condition, and get it back
	// when it is placed again, or breaking and replacing a section would be a free
	// rebuild. That data has nowhere to live except on the item, so these two
	// methods are the bridge - 1.21's own mechanism for exactly this, and the same
	// pair Create's Toolbox and Backtank use.
	//
	// The item end of the round trip is split between them:
	//   collectImplicitComponents  puts the wear into this block entity's component
	//                              map, which the loot table's copy_components
	//                              function copies onto the dropped stack;
	//   applyImplicitComponents    takes it back off the stack the block was placed
	//                              from.
	//
	// A stack with no component reads as pristine, which is correct for a freshly
	// crafted part, a creative stack, an item from a command, and every crankshaft
	// in a world saved before this milestone.

	@Override
	protected void applyImplicitComponents(DataComponentInput componentInput) {
		super.applyImplicitComponents(componentInput);
		setBearingWear(componentInput.getOrDefault(ECDataComponents.CRANKSHAFT_BEARING_WEAR, 0.0F));
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);
		// Only when there is something to say. A pristine section produces a stack
		// with no component at all, so it is byte-identical to a freshly crafted one -
		// it stacks with its siblings and shows no tooltip line.
		if (bearingWear > 0.0F)
			components.set(ECDataComponents.CRANKSHAFT_BEARING_WEAR, bearingWear);
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
		// Every update carries the engine's condition with it, so this is the moment
		// the client learns it - recorded here rather than where the need is detected,
		// so an update sent for some other reason still counts and the next quantum
		// crossing is not sent twice.
		syncedEngineBearingWear = quantisedEngineBearingWear();
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
		addLayoutWarning(tooltip, components);
		addFlywheelWarning(tooltip, components);
		addSparkPlugWarning(tooltip, components);
		addConditionLine(tooltip, components);
		addFuelLines(tooltip, components.carburetor());
		addLubricationLines(tooltip, components.oilSump());
		addWearRiskLines(tooltip, state, components);

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
	 * The engine's condition, in one word.
	 *
	 * <p>One line on the main overlay, because condition is now something a player
	 * has to keep an eye on - and exactly one line, because the interesting part is
	 * <i>which part</i> is worn, and that belongs behind sneak with the rest of the
	 * diagnostics.
	 *
	 * <p>The <b>worst</b> of the engine's mechanical condition and any one
	 * cylinder's compression, never an average: an inline-4 with three perfect bores
	 * and one at the service limit is an engine that needs a piston, and averaging
	 * would call it lightly used.
	 */
	private void addConditionLine(List<Component> tooltip, EngineComponents components) {
		ECLang.translate("gui.condition", EngineConditionText.name(conditionOf(components)))
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
	}

	/**
	 * What is wearing this engine out, right now.
	 *
	 * <p>The condition line says an engine is tired; these say <i>why</i> it is
	 * getting tired, which is the difference between a player who replaces a piston
	 * every few hours and one who fits an Air Filter and stops having to. Every line
	 * here corresponds to a real multiplier in the wear model - there is no warning
	 * for something that does not actually cost the engine anything.
	 *
	 * <p>Shown only while the crank is genuinely turning, because none of them costs
	 * a stationary engine anything, and only when they apply. A well-kept engine
	 * doing easy work prints nothing at all, which is what keeps the presence of a
	 * line meaningful.
	 *
	 * <p>Being motored by another Create source counts as operating, and that is
	 * deliberate: an engine geared up past its rated speed by a stronger network is
	 * being destroyed whether or not it is burning anything, and this is where a
	 * player finds that out.
	 *
	 * <h2>Root causes, not cascades</h2>
	 * A warning is only worth printing if the player can act on it and if acting on
	 * it would help. Two of these are therefore conditional on more than their own
	 * multiplier:
	 * <ul>
	 * <li>The Air Filter warning is suppressed while there is no Carburetor at all.
	 * The filter mounts on the Carburetor, so an engine missing one cannot be given
	 * a filter, and printing
	 * <pre>
	 * Fuel:      No Carburetor
	 * Wear Risk: No Air Filter
	 * </pre>
	 * asks the player to fix the second thing when the first is what is wrong. The
	 * warning becomes true and useful the moment a Carburetor exists.</li>
	 * <li>Heavy load is not shown on its own at all. After the 13.1 rebalance a full
	 * load is 1.6x on the bearings against a baseline measured in thousands of
	 * hours - it is work, not abuse, and an engine earning its keep must not be made
	 * to look broken for doing so. It is only worth a line when it is <i>compounding</i>
	 * something that genuinely is abuse, because that combination is where the wear
	 * model actually bites.</li>
	 * </ul>
	 */
	private void addWearRiskLines(List<Component> tooltip, EngineState state, EngineComponents components) {
		if (state.isAtRest())
			return;

		LubricationState lubrication = state.getLubrication();
		boolean poorlyLubricated = lubrication != LubricationState.NORMAL;
		if (lubrication == LubricationState.DRY)
			addWearRisk(tooltip, "no_oil", ChatFormatting.RED);
		else if (lubrication == LubricationState.LOW)
			addWearRisk(tooltip, "low_oil", ChatFormatting.GOLD);

		// Only once the part it mounts on exists - see the class note above.
		if (components.hasCarburetor() && !components.hasAirFilter())
			addWearRisk(tooltip, "no_air_filter", ChatFormatting.GOLD);

		// Mechanical speed, not generated: the whole point of the overspeed term is
		// the engine that something else is turning faster than it could turn itself.
		boolean oversped = EngineWearMath.isOverspeed(state.getMechanicalRpm());
		if (oversped)
			addWearRisk(tooltip, "overspeed", ChatFormatting.RED);

		// Resolved here rather than read from the simulation's own copy, which is only
		// ever written on the server: Create synchronises the network's stress and
		// capacity to clients for its own overlays, so this is the same figure the
		// engine is actually wearing against.
		if ((poorlyLubricated || oversped) && EngineWearMath.isHeavyLoad(readLoadFactor()))
			addWearRisk(tooltip, "heavy_load", ChatFormatting.GOLD);
	}

	private static void addWearRisk(List<Component> tooltip, String key, ChatFormatting color) {
		ECLang.translate("gui.wear_risk", ECLang.translate("gui.wear_risk." + key)
			.style(color)
			.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
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
	/**
	 * Why a build that looks finished is not running, when the reason is its shape.
	 *
	 * <p>Both cases here are ones a player cannot diagnose by looking, which is
	 * exactly when the overlay has to speak up:
	 * <ul>
	 * <li><b>too long</b> - a fifth crankcase makes the whole run unsupported rather
	 * than making a bigger engine, and nothing about the blocks shows that. The limit
	 * is named in the message rather than left to be guessed;</li>
	 * <li><b>suspended</b> - part of the engine is in a chunk that is not loaded, so
	 * it has been stopped rather than re-derived from the visible fraction. Worth
	 * saying plainly, because otherwise a player at the edge of the loaded area sees
	 * an engine that stops for no visible reason.</li>
	 * </ul>
	 *
	 * <p>On the main overlay rather than behind sneak: the sneak diagnostics also
	 * report the layout, but a player whose engine will not run should not have to
	 * know to look there.
	 */
	private void addLayoutWarning(List<Component> tooltip, EngineComponents components) {
		if (components.oversized()) {
			ECLang.translate("gui.unsupported_layout")
				.style(ChatFormatting.RED)
				.forGoggles(tooltip, 1);
			ECLang.translate("gui.unsupported_layout_hint", ECLang.number(EngineTuning.MAX_CYLINDERS)
				.component())
				.style(ChatFormatting.DARK_GRAY)
				.forGoggles(tooltip, 1);
			return;
		}
		if (!components.isLayoutComplete() || !components.chunksLoaded())
			ECLang.translate("gui.assembly_suspended")
				.style(ChatFormatting.GOLD)
				.forGoggles(tooltip, 1);
	}

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
		//
		// Read from the server's capacity mask, which is the SAME number the Flywheel
		// hands Create - not a client-side re-derivation from combustion ages the
		// client is never sent, which is what made this read "0 / 4" on every engine
		// that ever ran.
		int firing = state.getFiringCylinderCount();
		diagnostic(tooltip, "active_cylinders", ECLang.translate("gui.value.fraction",
			ECLang.number(firing)
				.component(),
			ECLang.number(components.cylinderCount())
				.component())
			.style(firing == components.cylinderCount() ? ChatFormatting.GREEN
				: firing == 0 ? ChatFormatting.DARK_GRAY : ChatFormatting.GOLD));
		addCylinderStatusLine(tooltip, state);
		addConditionDiagnostics(tooltip, components);
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
		// What THIS engine is contributing, in SU - not what its network has in
		// total, which Create already reports on the Flywheel. Asked of the Flywheel
		// so that it is literally the figure Create is working from, rather than the
		// overlay's own multiplication of a tuning constant that a datapack may have
		// retuned underneath it. Zero on any engine that is not actively generating,
		// however fast the network is spinning it: capacity comes from combustion,
		// never from rotation.
		EngineFlywheelBlockEntity generator = components.flywheel();
		float generatedCapacity = generator == null ? 0.0F : generator.getEngineGeneratedCapacity();
		diagnostic(tooltip, "generated_capacity", ECLang.number(generatedCapacity)
			.style(generatedCapacity > 0.0F ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY));
		// Network load is deliberately absent. Create already reports stress on the
		// Flywheel, which is this engine's generator, and repeating it here would be
		// the HUD clutter this overlay keeps out of the way behind sneak.
	}

	/**
	 * The engine's condition, taken apart into the parts it is made of.
	 *
	 * <p>The main overlay says "Worn"; this says which piston and which section, so
	 * a player can go and replace the one part that needs it rather than rebuilding
	 * an engine. That is the whole of what makes this a maintenance system rather
	 * than a durability bar - the answer to "what is wrong with it" has to name a
	 * component.
	 *
	 * <p>Three different scopes, deliberately, and they are not interchangeable:
	 * <ul>
	 * <li><b>Mechanical Condition</b> - the whole engine's bearings, averaged. This
	 * is the figure its friction is derived from;</li>
	 * <li><b>Cylinder <i>n</i> Compression</b> - one line per bore, each from that
	 * bore's own Piston Assembly, so a single bad cylinder is visible rather than
	 * averaged away;</li>
	 * <li><b>Bearing Condition</b> - <i>this section's</i> own journal, and only
	 * this one. A player walking along an inline-4 with the goggles on gets a
	 * different reading at each crankcase, which is exactly what points at the
	 * section to replace.</li>
	 * </ul>
	 *
	 * <p>No numbers anywhere. The exact floats exist, and {@link #describeWear()}
	 * will print them, but they are a development tool rather than a readout.
	 */
	private void addConditionDiagnostics(List<Component> tooltip, EngineComponents components) {
		diagnostic(tooltip, "mechanical_condition",
			EngineConditionText.name(components.resolveWear()
				.mechanicalCondition()));

		boolean multiCylinder = components.cylinderCount() > 1;
		for (EngineComponents.Cylinder cylinder : components.cylinders()) {
			// A bore with no Piston Assembly in it has no compression to report, and
			// the Cylinder's own overlay already says the part is missing.
			Component value = cylinder.hasPiston()
				? EngineConditionText.name(WearCondition.of(cylinder.pistonWear()))
				: ECLang.translate("gui.value.unavailable")
					.style(ChatFormatting.DARK_GRAY)
					.component();
			if (multiCylinder)
				ECLang.translate("gui.cylinder_compression", ECLang.number(cylinder.index() + 1)
					.component(), value)
					.style(ChatFormatting.DARK_GRAY)
					.forGoggles(tooltip, 1);
			else
				diagnostic(tooltip, "compression", value);
		}

		// This block, not the engine. The player is looking at one crankcase.
		diagnostic(tooltip, "bearing_condition", EngineConditionText.name(getBearingCondition()));
	}

	/**
	 * Which cylinders are working, at a glance: a filled circle per firing cylinder
	 * and a hollow one per dead cylinder, in crank-axis order.
	 *
	 * <p>The reason the capacity basis is a mask rather than a count. "3 / 4" says
	 * the engine is down a cylinder; this says <i>which</i> one, so the player knows
	 * which head to look in - and it costs one line and no extra state.
	 *
	 * <p>Absent on a single-cylinder engine, where the fraction above has already
	 * said everything there is to say.
	 */
	private static void addCylinderStatusLine(List<Component> tooltip, EngineState state) {
		int cylinders = state.getCylinderCount();
		if (cylinders <= 1)
			return;
		MutableComponent status = Component.empty();
		for (int cylinder = 0; cylinder < cylinders; cylinder++) {
			if (cylinder > 0)
				status.append(" ");
			boolean active = state.isCylinderActive(cylinder);
			status.append(Component.literal(active ? "●" : "○")
				.withStyle(active ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
		}
		diagnostic(tooltip, "cylinder_status", status);
	}

	private static void diagnostic(List<Component> tooltip, String key, LangBuilder value) {
		diagnostic(tooltip, key, value.component());
	}

	private static void diagnostic(List<Component> tooltip, String key, Component value) {
		ECLang.translate("gui." + key, value)
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

		// A run that is too long is the one structural fault a player cannot see and
		// cannot guess at - the blocks look exactly like a working engine, only more
		// of them - so it is worth a line even without goggles.
		if (isOversized()) {
			ECLang.translate("gui.unsupported_layout")
				.style(ChatFormatting.RED)
				.forGoggles(tooltip, 1);
			ECLang.translate("gui.unsupported_layout_hint", ECLang.number(EngineTuning.MAX_CYLINDERS)
				.component())
				.style(ChatFormatting.DARK_GRAY)
				.forGoggles(tooltip, 1);
			return true;
		}

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
	 * running engine is described as rough exactly when it <i>is</i> rough - poor
	 * lubrication, or bearings and bores worn past
	 * {@link WearCondition#WORN} - which is also exactly when it sounds rough, and
	 * as stalling exactly when it has lost combustion and is coasting down.
	 *
	 * <p>Wear reaching this overlay is deliberate. The exact condition stays a
	 * goggle reading, but "that engine does not sound well" is something anyone
	 * standing next to a machine can tell, and it is the nudge that sends a player
	 * to fetch the goggles.
	 */
	private String observedStateKey(EnginePhase phase) {
		EngineState state = getEngineState();
		return switch (phase) {
			case RUNNING -> state.getLubrication() == LubricationState.NORMAL
				&& !getEngineCondition().isWarning()
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
