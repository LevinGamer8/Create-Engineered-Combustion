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
import dev.engineeredcombustion.content.engine.EngineComponents;
import dev.engineeredcombustion.content.engine.EngineInputs;
import dev.engineeredcombustion.content.engine.EnginePhase;
import dev.engineeredcombustion.content.engine.EngineState;
import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.FuelSupply;
import dev.engineeredcombustion.content.engine.LubricationState;
import dev.engineeredcombustion.content.engine.OilSupply;
import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlockEntity;
import dev.engineeredcombustion.content.engine.control.ControlMode;
import dev.engineeredcombustion.content.engine.control.EngineControlState;
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
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
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
 * <li>resolve the engine's components and, in one place, its control inputs -
 * see {@link #resolveControlState()} - plus the network's load;</li>
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
	 * The spark gap, in the Cylinder block's own coordinates: between the centre
	 * electrode's tip and the ground strap under it, inside the combustion
	 * chamber. Must match {@code SPARK_PLUG_ELECTRODE} in
	 * {@code tools/generate_engine_models.py} - it is the point that model leaves
	 * the gap at, and a spark that misses it would be worse than no spark at all.
	 */
	private static final Vec3 SPARK_PLUG_ELECTRODE = new Vec3(11.95D / 16.0D, 13.79D / 16.0D, 8.0D / 16.0D);

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
	 */
	private boolean manualIgnition;

	/** Whether a Redstone Control Module is plugged into the engine's controls. */
	private boolean controlModuleInstalled;

	/**
	 * The event counters this client has already played out, and whether it has
	 * seen any at all yet.
	 *
	 * <p>Client-side only, and never written to NBT: they are this client's memory
	 * of what it has already shown, not part of the engine. The flag is what stops
	 * a freshly loaded chunk from firing a spark and a flash for events that
	 * happened while the player was somewhere else.
	 */
	private boolean clientEventsAdopted;
	private int lastSparkEventId;
	private int lastCombustionEventId;

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

		// The crankshaft's *own* kinetic speed, now that it is a real member of the
		// network. Identical to the flywheel's while the two are coupled - they are
		// a 1:1 axis connection - but this also stays correct when the engine is
		// driven from a Shaft on the crankshaft's far side, and when there is no
		// flywheel at all.
		float mechanicalRpm = getSpeed();
		engine.advanceCrankAngle(mechanicalRpm);

		if (level.isClientSide) {
			// Run the flash timer down first, then look for new events: a flash that
			// starts this tick must not be aged on the tick it started.
			engine.updateClientVisuals();
			playSyncedEvents();
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
		LubricationState lubricationBefore = engine.getLubrication();
		int sparkEventBefore = engine.getSparkEventId();
		int combustionEventBefore = engine.getCombustionEventId();

		EngineInputs inputs = new EngineInputs(tickComponents.isMechanicallyValid(), control.ignitionEnabled(),
			flywheel != null && flywheel.hasSource(), control.throttle(), readLoadFactor(), speedLimit());
		boolean generatedSpeedChanged = engine.tickSimulation(inputs, fuelSupply, oilSupply, random);

		if (generatedSpeedChanged && flywheel != null)
			// The one and only place engine state crosses into Create's world.
			flywheel.onEngineOutputChanged();

		playTransitionSounds(phaseBefore);
		updateIgnitionIndicator();

		// A spark or a combustion this tick has to reach the client on this tick:
		// the spark, the chamber flash and the firing sound are all triggered there
		// by the counters moving, so a delayed update would be a delayed effect.
		// This is at most one update per revolution - about three a second at full
		// throttle - and it is the only per-event traffic the engine generates.
		boolean eventFired = sparkEventBefore != engine.getSparkEventId()
			|| combustionEventBefore != engine.getCombustionEventId();

		// Anything the client displays has to trigger a block update, not just the
		// things that change the engine's rotation. Toggling redstone on a stopped
		// engine changes no speed and no phase, so without this the client would
		// keep showing the ignition state it was last told about.
		if (generatedSpeedChanged || eventFired || signalBefore != redstoneSignal
			|| phaseBefore != engine.getPhase() || structureValidBefore != engine.isStructureValid()
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
		return controlModuleInstalled && controlMode != null ? ControlMode.byOrdinal(controlMode.getValue())
			: ControlMode.MANUAL;
	}

	public boolean hasControlModule() {
		return controlModuleInstalled;
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
	 * {@link EngineState#getSparkEventId()} and
	 * {@link EngineState#getCombustionEventId()} moving. A spark is the coil
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
	@OnlyIn(Dist.CLIENT)
	private void playSyncedEvents() {
		int spark = engine.getSparkEventId();
		int combustion = engine.getCombustionEventId();

		if (!clientEventsAdopted) {
			clientEventsAdopted = true;
			lastSparkEventId = spark;
			lastCombustionEventId = combustion;
			return;
		}

		if (spark != lastSparkEventId) {
			lastSparkEventId = spark;
			emitSpark();
		}

		if (combustion != lastCombustionEventId) {
			lastCombustionEventId = combustion;
			engine.triggerCombustionFlash();
			playCombustionSound();
		}
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
	private void emitSpark() {
		if (level == null)
			return;
		BlockPos cylinderPos = EngineComponents.cylinderPos(worldPosition);
		double x = cylinderPos.getX() + SPARK_PLUG_ELECTRODE.x;
		double y = cylinderPos.getY() + SPARK_PLUG_ELECTRODE.y;
		double z = cylinderPos.getZ() + SPARK_PLUG_ELECTRODE.z;

		level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, 0.0D, 0.0D, 0.0D);

		if (engine.getPhase() != EnginePhase.RUNNING)
			level.playLocalSound(x, y, z, ECSounds.ENGINE_SPARK.get(), SoundSource.BLOCKS,
				EngineTuning.SOUND_SPARK_VOLUME, 1.0F, false);
	}

	/**
	 * The cough of a charge burning while the engine is not yet running.
	 *
	 * <p>Silent once the engine is RUNNING, and that includes the charge that
	 * catches it: a one-shot per revolution on top of the running loop is the
	 * overlapping mush this deliberately avoids, and the catch has its own sound.
	 * So a start reads as "puff, puff, BRUMM", with a flash on every one of those
	 * events including the last.
	 */
	@OnlyIn(Dist.CLIENT)
	private void playCombustionSound() {
		if (level == null || engine.getPhase() == EnginePhase.RUNNING)
			return;
		level.playLocalSound(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D,
			ECSounds.ENGINE_FIRE_ATTEMPT.get(), SoundSource.BLOCKS, EngineTuning.SOUND_FIRE_ATTEMPT_VOLUME,
			EngineTuning.mapMechanicalRpmToCrankingPitch(engine.getMechanicalRpm()), false);
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
	 * The adjacent flywheel along the crankshaft's axis - at either end - and
	 * independent of whether the engine is structurally complete.
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

		BlockPos candidate = EngineComponents.findFlywheel(level, worldPosition, getAxis())
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
		BlockPos pos = worldPosition;
		return level == null
			? new EngineComponents(pos, getAxis(), EngineComponents.cylinderPos(pos), null,
				EngineComponents.FlywheelPlacement.NONE, null, null, EngineComponents.carburetorPos(pos), null,
				EngineComponents.oilSumpPos(pos), null)
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
	 * crankshaft is coupled to - and for <i>both</i> of them when a flywheel is
	 * bolted to each end, because then there is no coupling at all.
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
		// effective ignition are re-derived from the world on the next server tick.
		engine.setPhase(EnginePhase.byId(tag.getString(KEY_PHASE)));
		engine.setSimulatedRpm(tag.getFloat(KEY_SIMULATED_RPM));
		engine.setPublishedRpm(tag.getFloat(KEY_PUBLISHED_RPM));
		engine.setIgnitionEnabled(tag.getBoolean(KEY_IGNITION));
		engine.setStructureValid(tag.getBoolean(KEY_STRUCTURE_VALID));
		// The ignition switch is a physical switch on the crankcase: it stays where
		// the player left it across a save, a chunk unload and a server restart,
		// exactly as the throttle lever and the fuel in the float bowl do. Nothing
		// is *started* by that - loading restores the engine's phase too, and the
		// start sounds are emitted from phase transitions computed inside a tick, so
		// an engine that was already running resumes running rather than announcing
		// a fresh start, and one that was stopped stays stopped until it is cranked.
		manualIgnition = tag.getBoolean(KEY_MANUAL_IGNITION);
		controlModuleInstalled = tag.getBoolean(KEY_CONTROL_MODULE);
		redstoneSignal = tag.getInt(KEY_REDSTONE_SIGNAL);
		engine.setStartAttempt(tag.getInt(KEY_START_PROGRESS), tag.getInt(KEY_START_REQUIRED));
		engine.setFuelAvailable(tag.getBoolean(KEY_FUEL_AVAILABLE));
		engine.setLubrication(LubricationState.byId(tag.getString(KEY_LUBRICATION)));
		// Persisted so a chunk reload does not hand the player free oil by
		// discarding the revolutions already banked towards the next draw.
		engine.setCombustionEventsSinceOilDraw(tag.getInt(KEY_OIL_WEAR));
		// The event channel. Carried in the same block entity data as everything
		// else, so a spark or a combustion arrives together with the phase and the
		// speed that describe it - see playSyncedEvents.
		engine.setEventIds(tag.getInt(KEY_SPARK_EVENT), tag.getInt(KEY_COMBUSTION_EVENT));
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
		tag.putBoolean(KEY_MANUAL_IGNITION, manualIgnition);
		tag.putBoolean(KEY_CONTROL_MODULE, controlModuleInstalled);
		tag.putInt(KEY_REDSTONE_SIGNAL, redstoneSignal);
		tag.putInt(KEY_START_PROGRESS, engine.getStartProgress());
		tag.putInt(KEY_START_REQUIRED, engine.getRequiredStartCycles());
		tag.putBoolean(KEY_FUEL_AVAILABLE, engine.isFuelAvailable());
		tag.putString(KEY_LUBRICATION, engine.getLubrication()
			.getId());
		tag.putInt(KEY_OIL_WEAR, engine.getCombustionEventsSinceOilDraw());
		tag.putInt(KEY_SPARK_EVENT, engine.getSparkEventId());
		tag.putInt(KEY_COMBUSTION_EVENT, engine.getCombustionEventId());
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

		// One resolution of each for the whole overlay, and the same two calls the
		// simulation makes. If combustion can draw from a Carburetor, these lines
		// describe that same Carburetor; if the engine is running on a commanded
		// throttle, they print that same number - they cannot disagree.
		EngineComponents components = engineComponents();
		EngineControlState control = resolveControlState();
		addThrottleLine(tooltip, components.carburetor(), control);
		addControlLines(tooltip, control);
		addFlywheelWarning(tooltip, components);
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
		if (!controlModuleInstalled)
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
			.controlsThrottle() && control.throttlePercent() != manualThrottlePercent())
			ECLang.translate("gui.manual_throttle", ECLang.number(manualThrottlePercent())
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
		// so these lines answer "is the engine assembled correctly right now" using
		// the same rule the server uses to decide whether it may run.
		EngineComponents components = engineComponents();
		boolean valid = components.isMechanicallyValid();
		diagnostic(tooltip, "structure", ECLang
			.translate(valid ? "gui.value.valid" : "gui.value.invalid")
			.style(valid ? ChatFormatting.GREEN : ChatFormatting.RED));
		diagnostic(tooltip, "rotation_source", ECLang.translate(engine.getRotationSource()
			.translationKey())
			.style(ChatFormatting.WHITE));
		// Which end of the crank axis the flywheel is on. Purely informational - both
		// ends are equally valid - but it is the fastest way to confirm that the
		// resolver found the one the player actually built.
		diagnostic(tooltip, "flywheel_side", ECLang.translate(flywheelSideKey(components.flywheelPlacement()))
			.style(components.hasFlywheelConflict() ? ChatFormatting.RED : ChatFormatting.WHITE));
		diagnostic(tooltip, "control_module", ECLang
			.translate(controlModuleInstalled ? "gui.value.installed" : "gui.value.missing")
			.style(controlModuleInstalled ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
		// The switch position, which is not the same thing as the live ignition while
		// a redstone ignition mode is driving the engine.
		diagnostic(tooltip, "ignition_switch", ECLang
			.translate(manualIgnition ? "gui.value.enabled" : "gui.value.disabled")
			.style(manualIgnition ? ChatFormatting.GREEN : ChatFormatting.RED));
		diagnostic(tooltip, "crank_angle", ECLang.number(engine.getCrankAngleDegrees())
			.style(ChatFormatting.AQUA));
		diagnostic(tooltip, "simulated_rpm", ECLang.number(engine.getSimulatedRpm())
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
