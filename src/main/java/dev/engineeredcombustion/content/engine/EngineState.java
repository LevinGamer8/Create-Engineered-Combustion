package dev.engineeredcombustion.content.engine;

import dev.engineeredcombustion.content.engine.fourstroke.CyclePosition;
import dev.engineeredcombustion.content.engine.fourstroke.CylinderCycleState;
import dev.engineeredcombustion.content.engine.fourstroke.FourStrokeCycle;
import dev.engineeredcombustion.content.engine.fourstroke.FourStrokeFiringOrder;
import dev.engineeredcombustion.content.engine.fourstroke.FourStrokePhase;

/**
 * The authoritative mechanical state of a single engine.
 *
 * <p>Free of any Minecraft, NeoForge or Create type: pure simulation. The Create
 * kinetic network is fed from this state by a separate adapter
 * ({@code EngineFlywheelBlockEntity}), never the other way around.
 *
 * <h2>One momentum, two readings of it</h2>
 * <dl>
 * <dt>{@link #getSimulatedRpm() simulated RPM}</dt>
 * <dd><b>The engine's angular velocity, always.</b> There is one crankshaft and
 * it has one speed, so this is never allowed to mean something different from
 * what the shaft is physically doing. When Create holds the shaft at a speed -
 * because another source on the network is turning it - this <i>absorbs</i> that
 * speed rather than keeping a stale opinion beside it. When nothing is holding
 * the shaft, this free-runs on friction and inertia. That single rule is what
 * makes an engine spun to 200 RPM by a fast network coast down from 200 when the
 * network is taken away, instead of snapping back to whatever it was doing
 * before it was connected.</dd>
 * <dt>{@link #getMechanicalRpm() mechanical RPM}</dt>
 * <dd>The speed the crank angle actually advances by this tick: Create's speed
 * while Create is driving the shaft, the engine's own momentum while it is not.
 * The only input to the crank angle, which is why the crankshaft, the piston,
 * the flywheel disc and every attached Create shaft - on either end - can never
 * visually disagree.</dd>
 * <dt>{@link #getPublishedRpm() published RPM}</dt>
 * <dd>What Create is told this engine <i>generates</i>. Non-zero only while
 * {@link #isActivelyGenerating()}, low-pass filtered and quantised, and capped at
 * what the engine's own combustion could sustain. <b>Derived, never
 * authoritative:</b> it is rebuilt from the simulation on a world load rather
 * than restored beside it - see {@link #restoreAfterLoad(boolean)}.</dd>
 * </dl>
 *
 * <h2>Generation is one predicate</h2>
 * {@link #isActivelyGenerating()} is the single authority on whether this engine
 * is producing power. Generated speed, stress capacity, passive drag, the HUD and
 * the audio all read it; none of them re-derives its own version. Turning is not
 * generating: an engine that is out of fuel, unlit, mid-start or merely being
 * spun by a neighbour is turning, and contributes nothing.
 *
 * <h2>Capacity is one mask and one strength</h2>
 * {@link #getActiveCylinderMask()} is the single authority on <i>which</i>
 * cylinders are producing that power: one bit per cylinder, derived on the server
 * from the combustion ages once per tick and synchronised as ordinary state. The
 * goggle diagnostics and the cache-refresh trigger read that one number, so there
 * is no arrangement in which the HUD can report nothing while the flywheel
 * reports four.
 *
 * <p>{@link #getPublishedCapacityFactor()} is the single authority on <i>how
 * strong</i> they are: the same cylinders summed at their own compression, so an
 * inline-4 with one tired bore supplies 3.6 cylinders' worth rather than four.
 * Create's Stress Capacity multiplier and the generated-capacity readout are both
 * that one number. A worn cylinder is emphatically still an active cylinder -
 * these two are different questions and the player is shown both.
 *
 * <h2>Throttle</h2>
 * The throttle never writes a speed. It scales the torque a combustion event is
 * worth and moves the governor band with it, so the engine has to accelerate to
 * its new equilibrium through the same inertia it always had - see
 * {@code integrate()} and {@link EngineTuning#peakCombustionTorqueFor(float)}.
 *
 * <h2>Cycle position, and the two angles that come out of it</h2>
 * The authoritative rotational position is one {@link CyclePosition}: a cycle
 * index and an angle in {@code [0, 720)}. Everything else is read off it.
 * <dl>
 * <dt>{@link #getCrankAngleDegrees()}, {@code [0, 360)}</dt>
 * <dd>Where the crank pin and therefore the piston <i>is</i>. What every renderer
 * draws, and a plain {@code % 360} of the cycle angle. Unchanged by Milestone 15B -
 * that is the whole reason the cycle convention was chosen the way it was.</dd>
 * <dt>{@link #getCycleAngleDegrees()}, {@code [0, 720)}</dt>
 * <dd>Where each cylinder is in its four-stroke cycle. What decides the stroke, the
 * valves, the camshaft and combustion timing.</dd>
 * </dl>
 * The relation is one-way: a cycle angle always yields a physical angle, and a
 * physical angle never yields a cycle angle, because two different strokes share it.
 * Conflating the two is the bug this design exists to avoid, and it is why there is
 * still deliberately no separate animation timer anywhere in the codebase.
 *
 * <h2>What survives a world save</h2>
 * The signed simulated RPM, the crank angle, the phase, how long ago a charge
 * burned, and the counters and flags the client needs. <b>Not</b> the published
 * RPM, and not the filtered output behind it: those are representations of the
 * simulated RPM, and persisting a representation beside the thing it represents
 * is how the two came back from a reload disagreeing. They are rebuilt instead -
 * see {@link #restoreAfterLoad(boolean)}.
 */
public final class EngineState {

	// --- rotation -----------------------------------------------------------

	/**
	 * <b>The</b> authoritative rotational position: which four-stroke cycle, and
	 * where in it.
	 *
	 * <p>One object for the whole engine. Every cylinder is a <i>view</i> of this
	 * shifted by its own phase offset - see {@link #localCycleAngleDegrees} - which
	 * is what makes four cylinders mechanically synchronised by construction rather
	 * than by four counters happening to agree.
	 *
	 * <p>Not an ever-growing angle. The cycle index is an exact integer and the angle
	 * is bounded, so the resolution of a tick's increment is fixed for ever rather
	 * than decaying with uptime, and an event can be <i>named</i> - "cylinder 3, cycle
	 * 1842" - which is what makes a duplicate combustion detectable rather than merely
	 * improbable.
	 */
	private final CyclePosition position = new CyclePosition();

	/** Scratch for one cylinder's shifted view. Reused so the per-tick loop allocates nothing. */
	private final CyclePosition localPosition = new CyclePosition();

	private float mechanicalRpm;
	private float lastAngleDeltaDegrees;

	// --- simulation ---------------------------------------------------------
	private float simulatedRpm;
	private float publishedRpm;

	/**
	 * The engine's output as Create should see it: {@link #simulatedRpm} with the
	 * within-revolution combustion ripple filtered out.
	 *
	 * <p>A single cylinder firing once per revolution really does make the
	 * crankshaft speed oscillate, and the piston, the crank angle and the sound all
	 * need that ripple. The kinetic network does not: every speed a source
	 * publishes costs Create a full network re-propagation. So the ripple is
	 * removed here, once, by a low-pass filter - and <i>only</i> here. Nothing
	 * downstream of the simulation filters anything a second time, and the
	 * instantaneous speed is never touched.
	 *
	 * <p>Derived state: seeded from {@link #simulatedRpm} on a world load, and
	 * never persisted.
	 */
	private float outputRpm;

	/**
	 * Set when the next evaluation must publish whatever the engine's output
	 * actually is, ignoring the rate limits that normally keep small corrections
	 * off the network.
	 *
	 * <p>Raised for the discontinuities those limits must not apply to: the
	 * post-load reconciliation, and a change of who is turning the shaft.
	 */
	private boolean forceGeneratedRepublish;

	/**
	 * Whether the crankshaft is turning on nothing but its own momentum, because
	 * Create is not holding it at any speed.
	 *
	 * <p>Computed identically on both sides by {@link #tickRotation}, and it is
	 * what lets a disconnected engine keep visibly spinning down: while it is true
	 * the crank angle advances from {@link #simulatedRpm} rather than from Create.
	 */
	private boolean freeRotation;

	/**
	 * The latched answer to {@link #isActivelyGenerating()}.
	 *
	 * <p>Evaluated once per server tick, from {@link #evaluateActiveGeneration()},
	 * and synchronised - so the client's overlays, audio and rotation rule get the
	 * server's answer rather than an approximation of it. This is deliberately a
	 * stored bit rather than a live predicate: the conditions live in exactly one
	 * method, and every consumer on either side reads exactly one field.
	 */
	private boolean activelyGenerating;

	// --- layout -------------------------------------------------------------

	/**
	 * How many cylinders this one engine has, 1 to
	 * {@link EngineTuning#MAX_CYLINDERS}.
	 *
	 * <p><b>One engine, several cylinders</b> - never several engines. There is one
	 * crankshaft, one master crank angle, one throttle, one momentum and one
	 * kinetic source however many cylinders are bolted to it; the cylinders differ
	 * only in the phase at which they take their turn. Re-read from the resolved
	 * assembly every tick, so adding or removing a section is picked up without
	 * anything here caching a layout.
	 */
	private int cylinderCount = 1;

	/**
	 * The crank and firing schedule this engine runs, derived from
	 * {@link #cylinderCount} and cached beside it.
	 *
	 * <p>Held rather than looked up per cylinder per tick, and re-derived in exactly
	 * one place - {@link #setCylinderCount} - so an engine can never be running one
	 * layout's throws against another's ignition order.
	 */
	private FourStrokeFiringOrder configuration = FourStrokeFiringOrder.R1;

	/**
	 * Whether this engine has a Camshaft fitted.
	 *
	 * <p><b>An engine-wide input, and the whole valvetrain.</b> Without it the engine
	 * is still perfectly valid mechanically - it turns, it can be hand cranked, its
	 * pistons move and its compression still resists - but no cylinder can draw a
	 * charge and none can burn one, because nothing is opening the valves. That is a
	 * missing part, not a broken engine, and the difference is exactly what the
	 * diagnostics have to be able to say.
	 *
	 * <p>Re-read from the world every tick like every other component, so fitting or
	 * pulling one takes effect immediately rather than at some revalidation interval.
	 */
	private boolean camshaftInstalled;

	/**
	 * Bit {@code i} set when cylinder {@code i} has a Spark Plug in its head.
	 *
	 * <p>A bitmask rather than a boolean, because a plug is a <i>per-cylinder</i>
	 * component: an inline-4 with one plug missing is a real machine that runs on
	 * three cylinders, down on power and lumpy, and telling the player that by
	 * simply letting the dead cylinder not fire is far better than declaring the
	 * whole engine broken.
	 *
	 * <p>The coil has somewhere to discharge only where this bit is set. It is what
	 * separates "the ignition is switched on" from "a spark can happen", and it is
	 * deliberately independent of {@link #structureValid}: an engine with no plugs
	 * at all turns over perfectly.
	 */
	private int sparkPlugMask;

	// --- conditions ---------------------------------------------------------
	private boolean structureValid;
	private boolean ignitionEnabled;
	private boolean externallyDriven;

	/** Main throttle opening, {@code [0, 1]}. Re-read from the carburetor each tick. */
	private float throttle;
	/** Network stress over capacity on the last simulated tick, {@code [0, 1]}. */
	private float loadFactor;
	/** Highest speed this engine may reach, reconciled with Create's config. */
	private float speedLimitRpm = EngineTuning.MAX_RPM;
	/**
	 * Speed the throttle is asking for, already capped by {@link #speedLimitRpm}.
	 * Held rather than recomputed so the governor and the clamp cannot disagree on
	 * a server that has lowered Create's {@code maxRotationSpeed} below the
	 * engine's full-throttle target.
	 */
	private float targetRpm = EngineTuning.IDLE_RPM;

	// --- combustion, per cylinder -------------------------------------------
	//
	// Every array here is MAX_CYLINDERS long and indexed by cylinder, so nothing
	// allocates when the engine's layout changes and an engine with fewer
	// cylinders simply leaves the tail alone.

	/** Whether a paid-for charge is currently burning in each cylinder. */
	private final boolean[] chargeBurning = new boolean[EngineTuning.MAX_CYLINDERS];
	private final boolean[] powerStrokeActive = new boolean[EngineTuning.MAX_CYLINDERS];

	/**
	 * Whether cylinder {@code i} has inducted a charge and not yet burned it.
	 *
	 * <h2>The anti-oscillation mechanism, and it is physics rather than a guard</h2>
	 * A cylinder cannot burn a charge it has not drawn in. The latch is set when the
	 * cylinder forward-crosses the start of its intake stroke and cleared at
	 * compression top dead centre whether or not it lights, so a misfire costs a whole
	 * cycle - and rocking the crank back and forth across the ignition point cannot
	 * produce a second bang, because re-arming needs 540 degrees of forward travel and
	 * firing needs another 180.
	 *
	 * <p>Persisted and synchronised as one integer - see {@link #getArmedMask()} -
	 * because "which cylinders are charged" is exactly the shape the save and the
	 * diagnostics want. Kept as an array here because that is the shape the hot path
	 * wants, and one derivation is cheaper than four bit twiddles a tick.
	 */
	private final boolean[] armed = new boolean[EngineTuning.MAX_CYLINDERS];

	/**
	 * The cycle index of the last firing opportunity each cylinder actually took, or
	 * {@link CylinderCycleState#NO_EVENT}.
	 *
	 * <p><b>The event identity.</b> {@code (cylinder, cycleIndex)} names one firing
	 * opportunity uniquely and for ever, and a cylinder may take each one once. The
	 * arming latch above already closes the rocking exploit; this is the independent
	 * check behind it, and it is the one that holds when a crank is reversed, saved
	 * mid-stroke, or driven at a speed that steps over several cycles in a tick.
	 */
	private final long[] lastFiredCycle = newFiringKeys();

	/**
	 * The torque multiplier each cylinder's <i>currently burning</i> charge was
	 * bought at: 1 for a charge lit in a running engine, a fraction of that for a
	 * pre-start kick.
	 *
	 * <p><b>Per cylinder and latched at ignition</b>, not a single engine-wide value
	 * recomputed every tick from the current phase. A charge is paid for and lit at
	 * one instant, and what it is worth was decided then; letting the phase change
	 * underneath it meant a kick lit during a start attempt silently became a full
	 * power stroke the moment the engine caught, and a charge lit while running lost
	 * two thirds of its torque if the engine dropped out of RUNNING behind it.
	 *
	 * <p>Zero when that cylinder has no charge burning.
	 */
	private final float[] powerStrokeStrength = new float[EngineTuning.MAX_CYLINDERS];

	/**
	 * Whether any cylinder was still being pushed by a paid-for charge on the last
	 * simulated tick.
	 *
	 * <p>Read by {@link #evaluateActiveGeneration()} and by {@link #advancePhase},
	 * and it is what closes the gap between "the tank is empty" and "the engine has
	 * stopped producing torque". Those are not the same instant: the charge already
	 * in the cylinder goes on pushing until the crank reaches the end of its stroke,
	 * and Create must not be told the engine generates nothing while it demonstrably
	 * still does.
	 */
	private boolean powerStrokeInProgress;
	/** Ticks since each cylinder last burned a charge, or -1 if it never has. */
	private final int[] ticksSinceCombustion = newAges();

	/**
	 * Bit {@code i} set when cylinder {@code i} burned a charge recently enough to
	 * be part of this engine's output right now.
	 *
	 * <h2>One capacity basis, and this is it</h2>
	 * <b>Every</b> answer to "how much of this engine is working" is this field:
	 * the Stress Capacity multiplier Create is handed, the capacity-change
	 * detection that refreshes Create's cache, the goggle diagnostic, and the
	 * generated-capacity figure the overlay prints. There is deliberately no
	 * second derivation anywhere - {@link #getFiringCylinderCount()} is
	 * {@code Integer.bitCount} of this and nothing else - so the HUD cannot say 0
	 * while the flywheel says 4.
	 *
	 * <h2>Why a mask rather than a count</h2>
	 * A count says three of four cylinders are working. A mask says <i>which</i>
	 * one is not, which is the difference between "your engine is down on power"
	 * and "cylinder 3 is dead" - and it is what lets the diagnostics point at the
	 * bore to look in. It costs the same four bits either way.
	 *
	 * <h2>Server-authoritative, and stable</h2>
	 * Derived on the server, once per simulated tick, from
	 * {@link #ticksSinceCombustion} and the same speed-scaled allowance the
	 * capacity has always used - so it <i>is</i> the old rule, named and stored
	 * rather than recomputed at each call site. The client is told this value as
	 * ordinary block entity state, and only when it changes.
	 *
	 * <p>That last part is what keeps it cheap. A healthy inline-4 holds
	 * {@code 0b1111} for as long as it runs, so it puts nothing at all on the wire
	 * between the moment it catches and the moment something changes: a plug pulled
	 * ({@code 0b1111 -> 0b1011}), that cylinder genuinely firing again
	 * ({@code 0b1011 -> 0b1111}), or the tank running dry
	 * ({@code 0b1111 -> 0b0000}). It is emphatically <b>not</b> a per-combustion
	 * event channel - those are {@code EngineCombustionEventsPayload}'s business,
	 * and they stay there.
	 *
	 * <p>Nothing an external source does can set a bit. Being spun is not burning,
	 * so a motored engine holds {@code 0b0000} however fast its pistons move.
	 */
	private int activeCylinderMask;

	/**
	 * The physical condition of the parts this engine is made of, as read from the
	 * world at the top of the current tick.
	 *
	 * <p><b>An input, never state.</b> Bearing wear belongs to each Crankshaft
	 * section and compression wear to each installed Piston Assembly - see
	 * {@link EngineWearInputs} - so this is refreshed from {@link EngineInputs}
	 * every tick and is never written to from inside the simulation. The wear a
	 * tick <i>produces</i> is applied to those parts afterwards, by the block
	 * entity that owns them.
	 *
	 * <p>Valid on both sides, with different detail. The server resolves the whole
	 * thing; the client is told the average bearing wear only, which is all its
	 * half of the physics needs - see {@link #setWear(EngineWearInputs)}.
	 */
	private EngineWearInputs wear = EngineWearInputs.PRISTINE;

	/**
	 * How many <i>healthy cylinders' worth</i> of output this engine is currently
	 * providing, quantised, and latched.
	 *
	 * <h2>Two different questions, and this is the second one</h2>
	 * {@link #activeCylinderMask} answers "which cylinders are firing". This
	 * answers "how strong are they". A worn cylinder is still an active cylinder -
	 * it burns its charge and it appears in the mask - it simply contributes less
	 * than one cylinder's worth of power, and Create must be told that or a
	 * four-cylinder engine with a dead cylinder's compression would still be
	 * advertising four cylinders of Stress Capacity.
	 *
	 * <pre>
	 * healthy inline-4         1.0 + 1.0 + 1.0 + 1.0 = 4.00
	 * one cylinder at 60 %     1.0 + 1.0 + 0.6 + 1.0 = 3.60
	 * one cylinder not firing  1.0 + 1.0 + 0.0 + 1.0 = 3.00
	 * </pre>
	 *
	 * <h2>Why it is latched and quantised</h2>
	 * Wear moves by about a millionth per revolution, and every capacity figure
	 * Create is handed costs it a re-registration and a network stress recompute.
	 * Publishing the raw sum would rebuild that bookkeeping several times a second
	 * for a change no player could see. So this only moves when the sum crosses a
	 * {@link EngineTuning#CAPACITY_QUANTUM} boundary - at most a few dozen times
	 * over a cylinder's whole life - while everything that is <i>not</i> slow drift
	 * still publishes on the tick it happens: the mask changing, a Piston Assembly
	 * swapped, the engine catching or stalling, a forced republish.
	 *
	 * <p>Derived state: rebuilt from the simulation after a world load, and
	 * synchronised to the client rather than recomputed there, so the flywheel and
	 * the overlay can never disagree about it.
	 */
	private float publishedCapacityFactor;

	/** Set when the next evaluation must publish the capacity whatever the quantum says. */
	private boolean forceCapacityRepublish;

	/**
	 * Whether the last simulated tick moved {@link #publishedCapacityFactor}.
	 *
	 * <p>Read by the block entity to decide whether Create's cached Stress Capacity
	 * has to be refreshed. Kept as a flag rather than returned, because
	 * {@link #tickSimulation} already returns the <i>speed</i> question and the two
	 * are genuinely separate events - an engine held at a steady speed by another
	 * source can change its capacity without its published speed moving by a single
	 * quantum, which is the whole reason the capacity refresh exists.
	 */
	private boolean capacityFactorChanged;

	private boolean fuelAvailable;

	/**
	 * Counts ignition coil firings, and counts charges that actually burned, for
	 * each cylinder separately.
	 *
	 * <p><b>The server's running tally of what really happened.</b> Both are
	 * incremented at exactly the point the thing they name occurs, so a counter can
	 * never disagree with the event it counts.
	 *
	 * <p>They are the <i>source</i> of the engine's event channel rather than the
	 * channel itself. {@code CrankshaftBlockEntity} diffs them across each simulated
	 * tick and sends the difference as two bitmasks in an
	 * {@code EngineCombustionEventsPayload}; that packet is what the client reacts
	 * to. Sending the counters themselves meant a full block entity synchronisation
	 * for every spark and every bang, which an inline-4 produces four times as often
	 * as an inline-1.
	 *
	 * <p>The client still never re-derives an event from the crank angle. It cannot
	 * know whether the server's fuel draw succeeded, so a dry engine would go on
	 * flashing for a revolution, and the flash and the firing sound would come from
	 * two different mechanisms that could land a tick or two apart.
	 *
	 * <p>Per cylinder rather than per engine, because the spark, the flash and the
	 * bang all happen at a <i>place</i>: cylinder 3 firing has to light cylinder 3
	 * and be heard from cylinder 3.
	 *
	 * <p>Wrapping is fine and overflow is irrelevant: only inequality is ever
	 * tested, so any change means "this happened since the previous tick".
	 */
	private final int[] sparkEventIds = new int[EngineTuning.MAX_CYLINDERS];
	private final int[] combustionEventIds = new int[EngineTuning.MAX_CYLINDERS];

	/**
	 * Ticks left on the visible flash inside each combustion chamber. Client-side
	 * bookkeeping, started by {@link #triggerCombustionFlash(int)} when that
	 * cylinder's combustion counter moves and run down by
	 * {@link #updateClientVisuals()}.
	 */
	private final int[] combustionFlashTicks = new int[EngineTuning.MAX_CYLINDERS];

	// --- lubrication --------------------------------------------------------
	private LubricationState lubrication = LubricationState.DRY;
	/**
	 * Running combustion events banked towards the next oil draw. Counted rather
	 * than timed, so oil use follows how hard the engine has actually worked.
	 */
	private int combustionEventsSinceOilDraw;

	// --- start attempt ------------------------------------------------------
	private int startProgress;
	private int requiredStartCycles;
	private int ticksSinceStartActivity;

	/**
	 * Crank degrees travelled since this start attempt last had a charge catch.
	 *
	 * <p>The physical half of the abandon rule, and the half Milestone 15B adds.
	 * {@link #ticksSinceStartActivity} answers "is anything happening at all" - it
	 * only advances on ticks where no cylinder could have fired - so it expires an
	 * engine that stopped, ran dry or lost its ignition, and never a slow one that is
	 * still being cranked. This answers the other question: the crank is turning,
	 * opportunities keep coming round, and none of them catches.
	 *
	 * <p>Measured in travel rather than in wall-clock ticks so a hand-cranked engine
	 * and one spun by a fast Create network get the same number of chances, because
	 * they get the same number of firing opportunities.
	 */
	private float degreesSinceStartActivity;

	private EnginePhase phase = EnginePhase.STOPPED;
	private int ticksSincePublish;

	// ------------------------------------------------------------------------
	// Step 1 - runs on BOTH sides
	// ------------------------------------------------------------------------

	/**
	 * One tick of rotation: reconciles the engine's momentum with whatever Create
	 * is doing to the shaft, then advances the crank angle.
	 *
	 * <p><b>Run on both sides, from the same inputs.</b> Every value it reads -
	 * Create's kinetic speed, whether this block has a kinetic source, the latched
	 * generation flag - is synchronised, so client and server derive the same crank
	 * angle and the same momentum without this mod sending a packet per tick.
	 *
	 * @param shaftSpeed       what Create says this crankshaft is doing
	 * @param shaftDriven      whether Create is <i>holding</i> the shaft at that
	 *                         speed. True even at zero when the network is
	 *                         overstressed - a jammed network stops the engine, it
	 *                         does not release it to freewheel
	 * @param externallyDriven whether the rotation on this shaft originates
	 *                         somewhere other than this engine
	 */
	public void tickRotation(float shaftSpeed, boolean shaftDriven, boolean externallyDriven) {
		this.externallyDriven = externallyDriven;
		this.freeRotation = !shaftDriven;
		if (shaftDriven)
			absorbImposedSpeed(shaftSpeed);
		advanceCrankAngle(freeRotation ? simulatedRpm : shaftSpeed);
	}

	/**
	 * Takes on the speed Create is imposing on the shaft, because there is only one
	 * crankshaft and it can only be doing one thing.
	 *
	 * <p>This is the whole of the fix for the RPM snap. The engine used to keep its
	 * own idea of its speed while an external network span it, so disconnecting a
	 * fast source revealed a stale number underneath and the engine appeared to
	 * teleport from 200 RPM back to 64. Now there is no second number to reveal.
	 *
	 * <p>Two cases must <i>not</i> absorb, and both are about the speed already
	 * being this engine's own work:
	 * <ul>
	 * <li>the engine is the network's source - then Create's speed came <i>from</i>
	 * the engine, and absorbing it back would pin the engine to its own published
	 * value and quietly cancel the load sag that makes it respond to work;</li>
	 * <li>the engine's combustion has already carried it past the speed it is being
	 * turned at - a firing kick during a start, or a spin-up. Absorbing then would
	 * let a hand crank hold a running engine down at cranking speed forever.</li>
	 * </ul>
	 *
	 * <p>Sign is carried through untouched, so an engine driven backwards holds
	 * backwards momentum and coasts down in the direction it was actually turning.
	 */
	private void absorbImposedSpeed(float shaftSpeed) {
		if (!externallyDriven && shaftSpeed != 0.0F)
			return;
		if (shaftSpeed != 0.0F && (activelyGenerating || phase.isFiring())
			&& Math.abs(simulatedRpm) >= Math.abs(shaftSpeed))
			return;
		simulatedRpm = shaftSpeed;
	}

	/**
	 * Advances the crank angle by exactly one tick of the given speed.
	 *
	 * <p>Negative speed turns the crank backwards.
	 */
	public void advanceCrankAngle(float mechanicalRpm) {
		this.mechanicalRpm = mechanicalRpm;
		lastAngleDeltaDegrees = EngineTuning.degreesPerTick(mechanicalRpm);
		// Advanced even at zero, so the position's own record of the last step agrees
		// with this tick rather than with whichever tick last moved. Every crossing
		// query reads that record, so a stale one would let a stopped engine keep
		// answering "yes, I just passed my ignition angle".
		position.advance(lastAngleDeltaDegrees);
	}

	/**
	 * Runs the purely visual side of the engine on the client.
	 *
	 * <p>The power stroke is re-derived from the crank angle, which is exact on
	 * both sides. The combustion flash is <i>not</i> derived here: it is started
	 * by {@link #triggerCombustionFlash()} when the server's combustion counter
	 * moves, and this only counts it down. Predicting it was the bug - the client
	 * cannot know whether the server's fuel draw succeeded, so an engine whose
	 * tank had just run dry went on flashing for a revolution, and the flash and
	 * the firing sound were produced by two different mechanisms that could land a
	 * tick or two apart.
	 */
	public void updateClientVisuals() {
		boolean firing = phase.isFiring();
		for (int cylinder = 0; cylinder < cylinderCount; cylinder++)
			powerStrokeActive[cylinder] =
				firing && FourStrokeCycle.withinPowerStroke(localCycleAngleDegrees(cylinder));

		for (int cylinder = 0; cylinder < combustionFlashTicks.length; cylinder++)
			if (combustionFlashTicks[cylinder] > 0)
				combustionFlashTicks[cylinder]--;
	}

	/**
	 * Spins the flywheel down on the client while nothing is driving it.
	 *
	 * <p>The client has to do this itself, because a coasting engine generates
	 * nothing: Create's speed for it is zero, so there is no synchronised number
	 * left to animate from. What makes that safe is that the coast is
	 * <i>deterministic</i> - it runs the very same integration the server runs,
	 * with no combustion and no network load (a freewheeling engine is by
	 * definition on no network), from a starting speed the server synchronised at
	 * the moment it stopped generating. Both sides therefore trace the same curve,
	 * and the periodic resync only ever confirms it.
	 */
	public void tickClientCoast() {
		if (!freeRotation || simulatedRpm == 0.0F)
			return;
		// No combustion and no load - a freewheeling engine is by definition on no
		// network - but compression is very much still there, and it is the same sum
		// the server computes: every input to it (the crank angle, the cylinder
		// count, whether the engine is assembled) is synchronised, so both sides
		// trace the same curve, right down to a single-cylinder engine's last few
		// revolutions visibly labouring over each compression.
		integrate(0.0F, compressionTorqueSum(), 0.0F);
	}

	/**
	 * Lights the chamber for {@link EngineTuning#COMBUSTION_FLASH_TICKS} ticks.
	 *
	 * <p>Called on the client when {@link #getCombustionEventId()} changes, i.e.
	 * exactly once per charge that really burned - the same event that consumed
	 * the fuel, delivered the torque and advanced the start attempt.
	 */
	public void triggerCombustionFlash(int cylinder) {
		if (cylinder >= 0 && cylinder < combustionFlashTicks.length)
			combustionFlashTicks[cylinder] = EngineTuning.COMBUSTION_FLASH_TICKS;
	}

	// ------------------------------------------------------------------------
	// Step 2 - server only
	// ------------------------------------------------------------------------

	/**
	 * Runs combustion, inertia and friction for one tick and decides what Create
	 * should be told.
	 *
	 * @return true when {@link #getPublishedRpm()} changed and Create's generated
	 *         rotation therefore has to be updated
	 */
	public boolean tickSimulation(EngineInputs inputs, FuelSupply fuel, OilSupply oil, java.util.Random random) {
		this.structureValid = inputs.structureValid();
		this.ignitionEnabled = inputs.ignitionEnabled();
		setCylinderCount(inputs.cylinderCount());
		this.sparkPlugMask = inputs.sparkPlugMask();
		// The valvetrain, read from the world with everything else. A Camshaft pulled
		// out of a running engine stops it drawing charges on this very tick.
		this.camshaftInstalled = inputs.camshaftInstalled();
		this.throttle = inputs.throttle();
		this.loadFactor = inputs.loadFactor();
		this.speedLimitRpm = inputs.speedLimitRpm();
		this.targetRpm = inputs.targetRpm();
		// The condition of the actual parts, resolved from the world before any of
		// this tick's physics runs. Nothing below writes to it.
		this.wear = inputs.wear();
		this.fuelAvailable = fuel.hasFuel();
		// Read every tick: the sump can be filled or drained by a pipe at any time,
		// and lubrication has to take effect immediately rather than at some
		// revalidation interval.
		this.lubrication = oil.lubrication();

		if (ticksSincePublish < Integer.MAX_VALUE)
			ticksSincePublish++;

		// The engine's speed is no longer reconciled with Create here. tickRotation
		// did that, before the crank angle was advanced and on both sides, so by the
		// time the simulation runs there is exactly one momentum to integrate.

		// Forward rotation only, and fast enough to carry a charge to the next one.
		// Cranking the engine backwards never ignites it.
		float requiredRpm = phase == EnginePhase.RUNNING ? EngineTuning.STALL_RPM : EngineTuning.START_RPM;
		boolean turningForwards = lastAngleDeltaDegrees > 0.0F && simulatedRpm >= requiredRpm;
		// What a charge lit on THIS tick is worth. Latched into powerStrokeStrength[i]
		// at the moment that cylinder's charge is paid for, so a later phase change
		// cannot retroactively revalue a charge that is already burning.
		float strengthForNewCharges =
			phase == EnginePhase.RUNNING ? 1.0F : EngineTuning.START_KICK_TORQUE_FACTOR;

		// ONE PASS OVER THE CYLINDERS, and the whole of what makes this a
		// multi-cylinder engine rather than several engines on a shared shaft.
		// Every cylinder is offered its own firing opportunity, at its own crank
		// phase, and pays for its own charge; what they all feed is the single
		// crankshaft integrated once at the bottom.
		int activeMaskBefore = activeCylinderMask;
		boolean generatingBefore = activelyGenerating;

		boolean anyChargeIgnitable = false;
		boolean anyPowerStrokeActive = false;
		boolean ignitedThisTick = false;
		boolean armedMaskChanged = false;
		float combustionTorque = 0.0F;

		for (int cylinder = 0; cylinder < cylinderCount; cylinder++) {
			// This cylinder's own view of the ONE engine position, shifted by its own
			// cycle phase offset. Written into a reused scratch object, so a four
			// cylinder engine allocates nothing per tick.
			position.shiftedBy(configuration.cyclePhaseOffsetDegrees(cylinder), localPosition);
			float localCycleAngle = localPosition.angle();

			// THE GATES, in the order the machine imposes them.
			//
			// A spark needs an assembled engine, a live ignition and somewhere for
			// the coil to discharge - a Spark Plug in THIS cylinder. Fuel has nothing
			// to do with it: the coil is wired to the crank, not to the fuel system,
			// so a plug fires whether or not there is gasoline to light. That is the
			// mechanically honest model and it is the useful one, because it makes
			// the two failures distinguishable by looking: a plug that visibly sparks
			// while the engine refuses to catch says the problem is fuel, a plug that
			// stays dark says it is ignition, and no plug at all says so on the
			// overlay.
			//
			// A CHARGE needs a camshaft, because nothing else opens the intake valve.
			// That gate is deliberately upstream of everything: an engine with no
			// Camshaft never arms, so it never has anything to light, and no amount of
			// spark or fuel can make it fire. It still turns, still compresses and
			// still animates - it is an engine missing a part, not a broken one.
			//
			// Combustion needs all three: a spark, a camshaft-drawn charge, and fuel
			// to have paid for it. Nothing may reorder them - fuel must never be what
			// decides whether the plug sparks.
			//
			// THE NAME MATTERS. canIgniteNewCharge is permission to light a NEW charge,
			// and nothing else. It is deliberately never asked about a charge that is
			// already burning: fuel decides whether the next bang can happen, never
			// whether the one already in the cylinder is allowed to finish pushing.
			boolean sparkPossible = structureValid && ignitionEnabled && hasSparkPlug(cylinder);
			boolean canIgniteNewCharge = sparkPossible && camshaftInstalled && fuelAvailable;
			anyChargeIgnitable |= canIgniteNewCharge;

			if (ticksSinceCombustion[cylinder] >= 0 && ticksSinceCombustion[cylinder] < Integer.MAX_VALUE)
				ticksSinceCombustion[cylinder]++;

			// A cylinder with no valvetrain never inhales, so its latch must not merely
			// go unused - it must not be set in the first place, or fitting a Camshaft
			// to a spinning engine would hand it a free bang from a charge it never drew.
			boolean armedBefore = armed[cylinder];
			CylinderCycleState.Event event;
			if (camshaftInstalled) {
				event = CylinderCycleState.advance(localPosition, armed, lastFiredCycle, cylinder,
					canIgniteNewCharge && turningForwards);
			} else {
				armed[cylinder] = false;
				// A cylinder that reaches its firing point with nothing inducted has
				// misfired, whatever the reason - and saying so here rather than
				// silently is what lets the sound, the flash and the diagnostics treat
				// a missing Camshaft like every other reason a charge did not light.
				event = localPosition.crossedForward(EngineTuning.FIRING_ANGLE_DEGREES)
					? CylinderCycleState.Event.MISFIRED
					: CylinderCycleState.Event.NONE;
			}

			// The spark is a property of the coil and the crank, so it happens at the
			// firing opportunity whether or not anything comes of it - including on an
			// engine with no Camshaft, whose plug visibly sparks into a cylinder that
			// never drew a charge. That is the diagnosis the player needs.
			if (sparkPossible && turningForwards && localPosition.crossedForward(EngineTuning.FIRING_ANGLE_DEGREES))
				sparkEventIds[cylinder]++;

			if (event == CylinderCycleState.Event.IGNITED) {
				// Fuel is drawn per firing event, never per tick, and only if the whole
				// charge is actually available - a partial draw must not produce power.
				// Two millibuckets now, for one event per 720 degrees rather than one per
				// 360, so the gasoline a revolution costs has not moved.
				if (fuel.consume(EngineTuning.FUEL_PER_COMBUSTION_MB)) {
					chargeBurning[cylinder] = true;
					// Bought and paid for at this tick's price. Nothing may revalue it
					// afterwards - see powerStrokeStrength.
					//
					// THIS is where a worn cylinder becomes a weak cylinder, and it is
					// deliberately the only place: latching compression into the charge
					// covers the running power stroke and the pre-start firing kick with
					// one multiplication, so a tired engine is both down on power and
					// harder to start without either being written down separately.
					powerStrokeStrength[cylinder] = strengthForNewCharges * wear.compressionEfficiency(cylinder);
					ignitedThisTick = true;
					ticksSinceCombustion[cylinder] = 0;
					ticksSinceStartActivity = 0;
					degreesSinceStartActivity = 0.0F;
					// One increment, here, at the single point where a charge is paid
					// for and burns in this cylinder. Everything downstream of it -
					// the torque, the start cycle, the oil wear, and on the client the
					// chamber flash and the firing sound at this cylinder's own
					// position - is therefore describing this same event.
					combustionEventIds[cylinder]++;
					if (phase != EnginePhase.RUNNING)
						registerStartCycle(random);
					else
						// Only a running engine wears oil. Start attempts are
						// deliberately free, so a hard-to-start engine is not also an
						// oil sink.
						drawOilForCombustion(oil);
				} else {
					// The draw failed between the permission check and here - the tank
					// went dry on this very tick. Give the opportunity back rather than
					// leaving the cylinder holding a key it never spent, so it can light
					// on its next cycle without waiting an extra one.
					lastFiredCycle[cylinder] = CylinderCycleState.NO_EVENT;
					chargeBurning[cylinder] = false;
					powerStrokeStrength[cylinder] = 0.0F;
				}
			} else if (event == CylinderCycleState.Event.MISFIRED) {
				// This cylinder's firing opportunity came round and produced nothing,
				// so whatever it was burning is done with.
				chargeBurning[cylinder] = false;
				powerStrokeStrength[cylinder] = 0.0F;
			}

			// THE POWER STROKE OF A CHARGE THAT HAS ALREADY BEEN PAID FOR.
			//
			// Fuel is deliberately absent from this condition. The charge is in the
			// cylinder, the millibuckets have been drawn and the crank is being pushed;
			// an empty tank cannot reach back into the bore and put the fire out.
			// Testing fuel here meant the last charge of a run truncated its stroke the
			// moment the tank hit zero, so the engine lost torque it had already bought.
			//
			// The Camshaft is absent from it for the same reason, and it is the same
			// rule: pulling the valvetrain out stops the NEXT charge, not the one
			// already burning above the piston.
			//
			// What DOES still end a stroke immediately, and must:
			//   structureValid          - the engine was taken apart under it;
			//   lastAngleDeltaDegrees   - the crank is not turning forwards, which
			//                             covers a stall and an overstressed network
			//                             (Create reports speed 0 for both). Without it
			//                             a stalled crank would stay latched and deliver
			//                             free torque every tick for ever;
			//   withinPowerStroke       - the crank has reached the end of the stroke.
			//                             Measured on the CYCLE angle, so the piston's
			//                             second pass down the bore - the intake stroke,
			//                             at the same physical angle - is not a second
			//                             power stroke.
			powerStrokeActive[cylinder] = chargeBurning[cylinder] && structureValid
				&& lastAngleDeltaDegrees > 0.0F && FourStrokeCycle.withinPowerStroke(localCycleAngle);
			if (powerStrokeActive[cylinder]) {
				combustionTorque += EngineTuning.combustionTorqueAt(simulatedRpm, targetRpm, cylinderCount)
					* powerStrokeStrength[cylinder];
				anyPowerStrokeActive = true;
			}

			if (armedBefore != armed[cylinder])
				armedMaskChanged = true;
		}

		powerStrokeInProgress = anyPowerStrokeActive;

		integrate(combustionTorque, compressionTorqueSum(), EngineTuning.loadDragTorque(loadFactor));

		expireStaleStartAttempt(anyChargeIgnitable && turningForwards);
		advancePhase(anyChargeIgnitable, anyPowerStrokeActive, ignitedThisTick);

		// THE capacity basis, derived once, here, and after the phase has settled so
		// that an engine which just stopped has already forgotten its combustion.
		// Everything downstream reads the stored mask rather than repeating this sum:
		// Create's Stress Capacity multiplier, the capacity-change detection, the
		// synchronised value the client's HUD prints, and the generation predicate
		// immediately below.
		activeCylinderMask = deriveActiveCylinderMask();

		// Deliberately last, and deliberately after the phase has settled: this is
		// the one evaluation of "is this engine producing power", and everything
		// downstream - the speed Create is told, the capacity the network gets, the
		// drag it does not get, the HUD, the audio - is a consequence of it.
		activelyGenerating = evaluateActiveGeneration();

		// And HOW MUCH of it, in cylinder-equivalents. Derived from the mask above
		// and this tick's compression, published under a quantum so that microscopic
		// wear cannot churn Create's kinetic bookkeeping - see
		// publishedCapacityFactor.
		capacityFactorChanged = updatePublishedCapacity(activeMaskBefore != activeCylinderMask
			|| generatingBefore != activelyGenerating);

		return updatePublishedRpm();
	}

	/**
	 * Counts one successful pre-start firing opportunity, rolling the number of
	 * cycles this attempt needs the first time.
	 *
	 * <p>The required count is chosen once per attempt and then held - re-rolling
	 * it per revolution would make starting feel arbitrary, which is exactly what
	 * this is meant to avoid.
	 */
	private void registerStartCycle(java.util.Random random) {
		if (requiredStartCycles <= 0) {
			int rolled = EngineTuning.MIN_START_CYCLES
				+ random.nextInt(EngineTuning.MAX_START_CYCLES - EngineTuning.MIN_START_CYCLES + 1);
			// Scaled by the cylinder count, because progress counts engine-wide
			// firing events and an inline-4 produces four of those per revolution.
			// Sub-linearly, so more cylinders still catch sooner - which is true of
			// real engines - without a four-cylinder engine starting the instant it
			// is touched.
			// Scaled by the engine's compression as well, so a tired engine has to
			// catch on more of its weaker kicks. See EngineTuning#requiredStartCycles.
			requiredStartCycles = EngineTuning.requiredStartCycles(rolled, cylinderCount,
				wear.averageCompressionEfficiency(cylinderCount));
		}
		startProgress++;
	}

	/**
	 * Counts one running combustion event towards oil wear, and draws from the
	 * sump once enough have accumulated.
	 *
	 * <p>Counting events rather than ticks is what keeps consumption honest at any
	 * speed: one revolution costs the same whether the engine is idling or flat
	 * out. The counter only resets on a draw that actually succeeded, so an engine
	 * running dry does not silently forfeit the progress it made - and because the
	 * supply refuses partial draws, the tank can never go negative.
	 */
	private void drawOilForCombustion(OilSupply oil) {
		if (combustionEventsSinceOilDraw < Integer.MAX_VALUE)
			combustionEventsSinceOilDraw++;
		if (combustionEventsSinceOilDraw < EngineTuning.COMBUSTION_EVENTS_PER_OIL_MB)
			return;
		if (oil.consume(EngineTuning.OIL_PER_CONSUMPTION_MB))
			combustionEventsSinceOilDraw = 0;
	}

	/**
	 * Abandons a start attempt that has gone quiet - the engine stopped turning,
	 * ran out of fuel, or ignition was switched off - so a nearly-complete start
	 * is not remembered indefinitely.
	 */
	private void expireStaleStartAttempt(boolean mayIgnite) {
		if (mayIgnite)
			ticksSinceStartActivity = 0;
		else if (ticksSinceStartActivity < Integer.MAX_VALUE)
			ticksSinceStartActivity++;

		// The physical half. Forward travel only, because winding an engine backwards
		// is not an attempt to start it and must neither advance nor abandon one.
		// Reset at the moment a charge catches, so what this measures is genuinely
		// "how far has the crank gone since anything last fired".
		if (lastAngleDeltaDegrees > 0.0F)
			degreesSinceStartActivity += lastAngleDeltaDegrees;

		if (startProgress <= 0)
			return;
		if (ticksSinceStartActivity > EngineTuning.START_ATTEMPT_TIMEOUT_TICKS
			|| degreesSinceStartActivity > EngineTuning.START_ATTEMPT_TRAVEL_DEGREES)
			resetStartAttempt();
	}

	private void resetStartAttempt() {
		startProgress = 0;
		requiredStartCycles = 0;
		degreesSinceStartActivity = 0.0F;
		java.util.Arrays.fill(chargeBurning, false);
		java.util.Arrays.fill(powerStrokeActive, false);
		// No charge is burning any more, so none of them is worth anything. Leaving a
		// stale strength behind would let the next latched stroke start at the wrong
		// price.
		java.util.Arrays.fill(powerStrokeStrength, 0.0F);
		powerStrokeInProgress = false;
	}

	/**
	 * netTorque -&gt; angular acceleration -&gt; angular velocity.
	 *
	 * <p>The throttle appears here and nowhere else. It does not set a speed: it
	 * chooses how much torque a combustion event is worth
	 * ({@link EngineTuning#peakCombustionTorqueFor}) and where the governor
	 * starts taking that torque away again. Everything the player sees - spinning
	 * up over seconds, overshooting slightly, sagging under load, coasting back
	 * down when the throttle closes - falls out of integrating that torque
	 * against friction and flywheel inertia, exactly as it did before the
	 * throttle existed.
	 */
	private void integrate(float combustionTorque, float compressionTorque, float loadDragTorque) {
		// Friction always opposes the current direction of rotation, and is exactly
		// zero at rest so it can never push a stationary engine into motion. The
		// kinetic load Create has hung on the engine is drag of the same kind.
		//
		// Compression is not drag and is deliberately not added to it: it has a sign
		// of its own, resisting on the way up to top dead centre and pushing on the
		// way back down, and it takes out over a revolution exactly what it puts in.
		// That is why it can make a single-cylinder engine lumpy and an inline-4
		// smooth without moving either one's equilibrium speed.
		//
		// Worn bearings appear here and nowhere else: they multiply the friction the
		// engine already fights, so a tired engine loses reserve torque, sags further
		// under load, coasts down sooner and burns more fuel holding a speed - all of
		// it emerging from the same equilibrium a healthy engine settles into, and not
		// one line of it subtracting RPM. It scales the coast drag too, which is what
		// makes a worn engine's spin-down visibly shorter.
		//
		// Written with the multiplier applied to each drag term separately rather than
		// to their sum, so that a pristine engine - multiplier exactly 1 - integrates
		// bit for bit the arithmetic it always did. Float addition is not associative,
		// and an engine's stall behaviour at low speed is close enough to the edge for
		// one ULP to move a tick.
		float bearingFriction = wear.bearingFrictionMultiplier();
		float drag = EngineTuning.frictionTorqueAt(simulatedRpm, lubrication) * bearingFriction + loadDragTorque
			+ coastDragTorque() * bearingFriction;
		float netTorque = combustionTorque + compressionTorque - Math.signum(simulatedRpm) * drag;

		float next = simulatedRpm + netTorque / EngineTuning.FLYWHEEL_INERTIA;

		// Friction alone must never drag the engine through zero into reverse. It
		// lands exactly on zero instead, which is what lets a coast-down actually
		// finish rather than creeping at a fraction of an RPM forever.
		if (combustionTorque == 0.0F && simulatedRpm != 0.0F
			&& Math.signum(next) != Math.signum(simulatedRpm))
			next = 0.0F;

		// The ceiling never *reduces* an existing speed: an engine that a fast
		// external network has spun past its own limit has to be allowed to coast
		// back down through friction, because clamping it would be exactly the
		// instantaneous snap this milestone exists to remove. The engine's own
		// combustion still cannot climb past the limit - the governor takes its
		// torque away well below it.
		float ceiling = Math.max(speedLimitRpm, Math.abs(simulatedRpm));
		simulatedRpm = clamp(next, -ceiling, ceiling);
	}

	/**
	 * The extra drag of an engine that is turning without firing - pumping losses
	 * and the heavier friction of a motored engine. Zero for a firing one.
	 *
	 * <p>Three gates, and each of them is a way this drag would be wrong:
	 * <ul>
	 * <li><b>only while free-running.</b> An engine Create is holding at a speed does
	 * not integrate its own momentum - it takes that speed on - so subtracting drag
	 * from it would corrupt the one number that is supposed to equal the shaft's, and
	 * it would be charging the same losses twice: motoring a dead engine is already
	 * billed to the network through
	 * {@link EngineTuning#PASSIVE_DRAG_STRESS_PER_RPM}. This drag is for an engine
	 * nothing is driving;</li>
	 * <li><b>not while RUNNING</b>, so every equilibrium the governor solved for is
	 * exactly where it was: idle stays 64 RPM and full throttle stays 192;</li>
	 * <li><b>not while STARTING</b>, which is what keeps a hand crank able to start
	 * the engine - a start attempt spends most of its ticks between firing kicks, and
	 * charging it coast drag in those gaps would smother the attempt - and not while
	 * a paid-for charge is still pushing, so the last stroke of a run finishes
	 * against running friction rather than against the drag of an engine that has
	 * already stopped burning.</li>
	 * </ul>
	 *
	 * <p>Identical on both sides. Every input is either synchronised (the phase) or
	 * derived identically by {@link #tickRotation} (free rotation) or provably false
	 * while coasting (a power stroke - RUNNING cannot be left while one is live), so
	 * the client's spin-down traces the server's curve exactly.
	 */
	/**
	 * The multiplier this engine's own drag is currently carrying: worn bearings times
	 * poor lubrication.
	 *
	 * <p>Exactly the two factors {@link #integrate} multiplies its friction by, read
	 * out in one place so that anything asking "how hard is this engine to turn" gets
	 * the same answer the physics uses.
	 */
	private float dragScale() {
		return wear.bearingFrictionMultiplier() * lubrication.frictionMultiplier();
	}

	private float coastDragTorque() {
		if (!freeRotation || phase.isFiring() || powerStrokeInProgress)
			return 0.0F;
		return EngineTuning.coastDragTorqueAt(simulatedRpm, lubrication);
	}

	/**
	 * @param canIgniteNewCharge  any cylinder could light a fresh charge this tick
	 * @param powerStrokeActive   any cylinder is still being pushed by a charge that
	 *                            was already paid for. Keeps a running engine in
	 *                            RUNNING until its last bought charge has finished
	 *                            working, rather than dropping it to COASTING the
	 *                            instant the tank reads empty
	 * @param ignitedThisTick     a charge actually burned on this tick
	 */
	private void advancePhase(boolean canIgniteNewCharge, boolean powerStrokeActive, boolean ignitedThisTick) {
		switch (phase) {
			case STOPPED -> {
				if (mechanicalRpm != 0.0F)
					phase = EnginePhase.CRANKING;
			}
			case CRANKING -> {
				// The first successful firing opens a start attempt; it does not start
				// the engine. That now takes several cycles. This deliberately tests
				// "ignited on this tick" rather than the latched chargeBurning,
				// which would otherwise bounce the phase back and forth once an
				// abandoned attempt drops us out of STARTING.
				if (ignitedThisTick)
					phase = EnginePhase.STARTING;
				else if (hasComeToRest())
					// stop() rather than a bare phase change, so the simulated speed is
					// zeroed too and the readout does not show a stopped engine still
					// bleeding off RPM.
					stop();
			}
			case STARTING -> {
				// TWO conditions, and the second is what four-stroke adds. Enough charges
				// have caught AND the flywheel can now carry the engine the three
				// non-power strokes to its next one. Without the speed test a single
				// declared itself RUNNING at hand-crank speed and then bled out and
				// stopped, having told the player it had started. The bar is the gap
				// THIS engine actually has - 720 degrees on a single, 180 on an inline-4
				// - against the friction THIS engine is actually carrying, so a worn or
				// dry engine has to be spun faster before it counts as running. See
				// EngineTuning#carriesToNextCombustion.
				if (requiredStartCycles > 0 && startProgress >= requiredStartCycles
					&& EngineTuning.carriesToNextCombustion(simulatedRpm, cylinderCount, dragScale())) {
					phase = EnginePhase.RUNNING;
					resetStartAttempt();
				} else if (hasComeToRest()) {
					stop();
				} else if (startProgress == 0) {
					// expireStaleStartAttempt cleared it - the attempt went cold.
					phase = EnginePhase.CRANKING;
				}
			}
			case RUNNING -> {
				// Two conditions, and the second is what stops the engine from being
				// declared "coasting" while it is demonstrably still making torque. The
				// last charge of a run is bought a tick or two before the tank reads
				// empty and goes on pushing for the rest of its stroke; leaving RUNNING
				// then would have taken its Stress Capacity away mid-push.
				if (!canIgniteNewCharge && !powerStrokeActive)
					phase = EnginePhase.COASTING;
				else if (simulatedRpm < EngineTuning.STALL_RPM)
					stop();
			}
			case COASTING -> {
				// Tested against rest rather than against stall speed. Stalling is
				// about combustion - below 10 RPM no charge can carry the engine to
				// the next one - and this engine has already stopped burning. What is
				// left is a flywheel with momentum in it, and calling that stopped
				// while it is still visibly turning is what used to snap away the last
				// of a coast-down.
				if (hasComeToRest())
					stop();
				else if (canIgniteNewCharge && simulatedRpm >= EngineTuning.START_RPM)
					phase = EnginePhase.RUNNING;
			}
		}
	}

	/**
	 * Whether the crankshaft has genuinely come to a standstill: nothing is turning
	 * it and its own momentum has run out.
	 *
	 * <p>Both halves matter. Testing only the mechanical speed would declare a
	 * freewheeling engine stopped the instant its network let go of it, because a
	 * disconnected engine <i>has</i> no Create speed - and {@link #stop()} would
	 * then zero the momentum that the spin-down is made of.
	 */
	private boolean hasComeToRest() {
		return mechanicalRpm == 0.0F && Math.abs(simulatedRpm) < EngineTuning.REST_RPM;
	}

	private void stop() {
		phase = EnginePhase.STOPPED;
		simulatedRpm = 0.0F;
		activelyGenerating = false;
		// Every cylinder forgets that it ever fired, so a stopped engine's firing
		// count - and therefore its Stress Capacity - is zero from this instant.
		java.util.Arrays.fill(ticksSinceCombustion, -1);
		activeCylinderMask = 0;
		// Every charge the engine was holding goes with it. A stopped engine that kept
		// its arming latches would fire the instant it was nudged, on charges drawn
		// before it came to rest - which is exactly the free combustion the latch
		// exists to prevent.
		java.util.Arrays.fill(armed, false);
		// The capacity is deliberately NOT zeroed here. updatePublishedCapacity runs a
		// few lines later in the same tick and will take it to zero itself, which keeps
		// hasCapacityFactorChanged() an honest report of whether the figure moved -
		// and it is that flag the block entity refreshes Create's cache from.
		resetStartAttempt();
	}

	// ------------------------------------------------------------------------
	// Combustion timing
	// ------------------------------------------------------------------------

	/**
	 * The crank angle <i>this cylinder</i> sees.
	 *
	 * <pre>
	 * localAngle = normalize(masterCrankAngle + phaseOffset(index))
	 * </pre>
	 *
	 * <p>There is exactly one crank angle in this engine and it is
	 * {@link #position}. A cylinder does not have an angle of its own that
	 * could drift from the others - it has an <b>offset</b>, fixed by where its
	 * throw sits on the shaft, and every question about that cylinder (has it
	 * crossed its firing angle, is it on its power stroke, where is its piston,
	 * where is its crank pin) is asked of this function. That is what makes four
	 * pistons mechanically synchronised by construction rather than by four
	 * counters happening to agree.
	 */
	public float localCrankAngleDegrees(int cylinder) {
		return FourStrokeCycle.physicalAngle(localCycleAngleDegrees(cylinder));
	}

	/**
	 * The cycle angle <i>this cylinder</i> sees, in {@code [0, 720)}.
	 *
	 * <pre>
	 * localCycleAngle = normalizeCycle(masterCycleAngle + cyclePhaseOffset(index))
	 * </pre>
	 *
	 * <p>The answer to "which stroke is this cylinder on", and the input to every
	 * question about its valves, its compression and its firing. Distinct from
	 * {@link #localCrankAngleDegrees} in exactly the way that matters: two cylinders
	 * of an inline-4 share a piston position and do not share a stroke.
	 */
	public float localCycleAngleDegrees(int cylinder) {
		return FourStrokeCycle.normalizeCycle(
			position.angle() + configuration.cyclePhaseOffsetDegrees(cylinder));
	}

	/** Which of the four strokes a cylinder is on right now. */
	public FourStrokePhase getCylinderPhase(int cylinder) {
		return FourStrokePhase.at(localCycleAngleDegrees(cylinder));
	}

	/** The same physical angle interpolated into the current frame, for renderers. */
	public float getLocalRenderCrankAngleDegrees(int cylinder, float partialTicks) {
		return FourStrokeCycle.physicalAngle(getLocalRenderCycleAngleDegrees(cylinder, partialTicks));
	}

	/**
	 * The cycle angle a cylinder is at part way through the current frame.
	 *
	 * <p>What the valvetrain renders from. Interpolated exactly as the crank angle is,
	 * off the same one position and the same one tick delta, so a valve can never be
	 * drawn at a different instant from the piston beneath it.
	 */
	public float getLocalRenderCycleAngleDegrees(int cylinder, float partialTicks) {
		return FourStrokeCycle.normalizeCycle(position.angle() + lastAngleDeltaDegrees * partialTicks
			+ configuration.cyclePhaseOffsetDegrees(cylinder));
	}

	/**
	 * The engine's total compression torque right now: every cylinder's own gas
	 * spring, at its own phase, added up.
	 *
	 * <p>Compression is a property of a bore with a piston in it, not of
	 * combustion: a cylinder with no plug and no fuel still has to be pushed up to
	 * top dead centre and still hands the energy back afterwards. So this is
	 * summed for every cylinder the engine has, whether or not any of them are
	 * firing - which is what makes motoring a dead engine feel like turning an
	 * engine over.
	 *
	 * <p>Two suppressions, both narrow. An engine with no pistons has nothing to
	 * compress with; and a crank that has genuinely stopped must not be nudged off
	 * its rest position by a spring with nothing left to push against.
	 *
	 * <p><b>Once per cycle, not once per revolution.</b> The piston reaches top dead
	 * centre twice per 720 degrees, but only one of those is a compression: on the
	 * other the exhaust valve is open and there is nothing to squeeze. So the waveform
	 * is gated to the sealed strokes - see
	 * {@code FourStrokeCycle#gasSpringShape} - which is precisely what makes motoring
	 * a four-stroke feel different from motoring a two-stroke. It still integrates to
	 * exactly zero over the cycle, so it remains a spring rather than a second
	 * friction and moves no equilibrium speed.
	 *
	 * <p><b>Where multi-cylinder smoothness comes from.</b> Each term is the same
	 * curve shifted by that cylinder's own <i>cycle</i> phase, so on an inline-1 the
	 * sum is one lump per two revolutions and on an inline-4 it is four lumps 180
	 * degrees apart that very nearly cancel. Nothing anywhere says "four cylinders
	 * are smoother" - it falls out of adding them up.
	 */
	private float compressionTorqueSum() {
		if (!structureValid || Math.abs(simulatedRpm) < EngineTuning.REST_RPM)
			return 0.0F;
		float total = 0.0F;
		for (int cylinder = 0; cylinder < cylinderCount; cylinder++)
			total += EngineTuning.compressionTorqueAt(localCycleAngleDegrees(cylinder));
		return total;
	}

	// ------------------------------------------------------------------------
	// Create output
	// ------------------------------------------------------------------------

	/**
	 * <b>The</b> definition of this engine producing power, and the only one.
	 *
	 * <p>Every condition is here, in the order the machine imposes them, and each
	 * of them is a way an engine can be turning without generating anything:
	 * <ol>
	 * <li><b>caught</b> - {@link EnginePhase#mayGenerate()}. Cranking, starting and
	 * coasting are all rotation without self-sustaining combustion;</li>
	 * <li><b>assembled</b> - a cylinder with a piston in it and exactly one
	 * flywheel;</li>
	 * <li><b>lit</b> - the effective ignition, which is the player's switch unless
	 * a Redstone Control Module is holding it;</li>
	 * <li><b>able to spark</b> - a Spark Plug in the head;</li>
	 * <li><b>fuelled</b> - the carburetor can still pay for a charge. This is what
	 * makes fuel starvation instant: the tick after the last usable millibucket is
	 * drawn, the engine is not generating, whatever it is still doing
	 * mechanically;</li>
	 * <li><b>above stall</b>, and turning forwards;</li>
	 * <li><b>actually burning</b> - a charge really did fire within the last few
	 * revolutions. This is the condition that cannot be faked by an external
	 * source: a dead engine spun at 200 RPM by its neighbour satisfies every
	 * mechanical test and fails this one.</li>
	 * </ol>
	 *
	 * <p>Nothing here asks whether the engine is externally driven, and that is
	 * deliberate: two fuelled engines on one shaft are both genuinely burning fuel,
	 * and only one of them can be Create's source. Being spun by a neighbour is not
	 * disqualifying - producing no combustion is.
	 */
	private boolean evaluateActiveGeneration() {
		return phase.mayGenerate() && structureValid && ignitionEnabled && stillMakingCombustionTorque()
			&& simulatedRpm >= EngineTuning.STALL_RPM && activeCylinderMask != 0;
	}

	/**
	 * Whether this engine's combustion is still worth anything to the network:
	 * either it can pay for the next charge, or a charge it already paid for is
	 * still pushing.
	 *
	 * <p>Fuel alone used to be the test, and it left a real gap. The tank goes empty
	 * on the tick the last charge is drawn, but that charge burns for the following
	 * half revolution and genuinely accelerates the crankshaft; declaring the engine
	 * non-generating for those ticks handed Create a generated speed of zero while
	 * the engine was still making torque, and took the network's capacity away a
	 * fraction of a second early.
	 *
	 * <p>It closes rather than widens the honesty gap in the other direction too:
	 * once that last stroke ends, both halves are false on the very next tick, so
	 * generation stops immediately instead of coasting on an old
	 * {@code ticksSinceCombustion} for another couple of revolutions.
	 */
	private boolean stillMakingCombustionTorque() {
		return fuelAvailable || powerStrokeInProgress;
	}

	/**
	 * Works out, from the combustion ages, which cylinders are currently part of
	 * this engine's output.
	 *
	 * <p><b>The only place the capacity basis is decided.</b> Called once per
	 * simulated tick, on the server; every consumer then reads
	 * {@link #activeCylinderMask} rather than repeating this sum, which is what
	 * makes one answer impossible to disagree with another.
	 *
	 * <p><b>"Genuinely participating in combustion", never "currently on its power
	 * stroke".</b> That distinction is the whole of what four-stroke changes here: a
	 * healthy cylinder spends three quarters of its cycle not pushing, and an engine
	 * whose capacity blinked out for those strokes would hand Create a Stress Capacity
	 * that pulsed four times a second.
	 *
	 * <p>"Recently enough" is scaled by speed, because the firing interval is: see
	 * {@link EngineTuning#generationCombustionAllowanceTicks}. Each cylinder fires once
	 * per <i>cycle</i>, so the allowance is 2.5 cycles - comfortably longer than the
	 * interval on every layout, so a healthy cylinder's bit never blinks between its
	 * own opportunities however slowly the engine idles.
	 */
	private int deriveActiveCylinderMask() {
		int allowance = EngineTuning.generationCombustionAllowanceTicks(simulatedRpm);
		int mask = 0;
		for (int cylinder = 0; cylinder < cylinderCount; cylinder++)
			if (ticksSinceCombustion[cylinder] >= 0 && ticksSinceCombustion[cylinder] <= allowance)
				mask |= 1 << cylinder;
		return mask;
	}

	/**
	 * How many of this engine's cylinders burned a charge recently enough to count
	 * as genuinely firing right now.
	 *
	 * <p><b>The engine's real output, counted one cylinder at a time.</b> An
	 * inline-4 with a dead Spark Plug is an engine down a cylinder, and this is the
	 * count that says so. Capacity therefore follows combustion rather than
	 * cylinder count, which is what stops a wall of cylinders from being free power.
	 *
	 * <p>It is a <i>count</i>, not the capacity itself. Since wear exists, Stress
	 * Capacity is scaled by {@link #getPublishedCapacityFactor()} - the same
	 * cylinders, each weighted by its own compression - because four firing
	 * cylinders are not necessarily four cylinders' worth of power. This remains
	 * the honest answer to "how many of them are working", which is a different
	 * and equally useful question.
	 *
	 * <p>Nothing an external source does can raise it. Being spun fast is not
	 * burning fuel, so a motored engine counts zero however quickly its pistons are
	 * moving - the same rule that closed the multi-engine exploit, now per cylinder.
	 *
	 * <p><b>Valid on both sides.</b> It is a read of
	 * {@link #getActiveCylinderMask()}, which the server derives and synchronises,
	 * so the client's diagnostics report the server's answer rather than a
	 * reconstruction of it. It used to be recomputed here from
	 * {@link #ticksSinceCombustion}, which the client is never told - so every
	 * client-side caller of this got 0, for every engine, always.
	 */
	public int getFiringCylinderCount() {
		return Integer.bitCount(activeCylinderMask);
	}

	/**
	 * Which cylinders are currently contributing, one bit each: {@code 0b1111} for
	 * a healthy inline-4, {@code 0b1011} for one with a dead third cylinder,
	 * {@code 0b0000} for an engine that is not burning anything.
	 *
	 * <p>The engine's single capacity basis - see {@link #activeCylinderMask}.
	 */
	public int getActiveCylinderMask() {
		return activeCylinderMask;
	}

	/** Whether cylinder {@code i} is currently contributing to this engine's output. */
	public boolean isCylinderActive(int cylinder) {
		return cylinder >= 0 && cylinder < cylinderCount && (activeCylinderMask & (1 << cylinder)) != 0;
	}

	/**
	 * <b>The</b> engine's output, in healthy cylinders' worth: every firing
	 * cylinder counted at its own compression.
	 *
	 * <pre>
	 * sum over cylinders of  active(i) ? compressionEfficiency(i) : 0
	 * </pre>
	 *
	 * <p>The one place this sum exists. Create's Stress Capacity multiplier, the
	 * capacity-refresh trigger and the generated-capacity readout are all the
	 * quantised form of this number ({@link #getPublishedCapacityFactor()}), so
	 * there is no arrangement in which the HUD can report a figure the flywheel is
	 * not using.
	 *
	 * <p>Note what it is <i>not</i>: a redefinition of "active". A worn cylinder is
	 * still active and still in the mask - it contributes 0.6 of a cylinder rather
	 * than 0 - and an inactive cylinder contributes nothing however healthy it is.
	 * Those are two different diagnostics and the player is shown both.
	 */
	public float getEffectiveCylinderCapacity() {
		float total = 0.0F;
		for (int cylinder = 0; cylinder < cylinderCount; cylinder++)
			if ((activeCylinderMask & (1 << cylinder)) != 0)
				total += wear.compressionEfficiency(cylinder);
		return total;
	}

	/**
	 * The latched, quantised figure Create is actually told to multiply its
	 * registered Stress Capacity by. Zero on any engine that is not generating.
	 *
	 * @see #publishedCapacityFactor
	 */
	public float getPublishedCapacityFactor() {
		return publishedCapacityFactor;
	}

	/** Whether the last simulated tick moved that figure. */
	public boolean hasCapacityFactorChanged() {
		return capacityFactorChanged;
	}

	/**
	 * Decides whether Create's capacity multiplier needs to change.
	 *
	 * <p>The rule is deliberately asymmetric, because the two things that move this
	 * number are nothing alike:
	 * <ul>
	 * <li><b>events</b> - a cylinder starting or stopping firing, the engine
	 * catching or stalling, a Piston Assembly swapped, a forced republish. Real,
	 * instantaneous, and published on the tick they happen;</li>
	 * <li><b>wear</b> - about a millionth of a cylinder per revolution. Published
	 * only when the sum crosses a {@link EngineTuning#CAPACITY_QUANTUM} boundary,
	 * which over a cylinder's entire service life is a few dozen updates rather
	 * than several a second.</li>
	 * </ul>
	 *
	 * <p>There is no dithering to worry about: wear only ever increases, and
	 * compression is a pure function of it, so the raw sum moves monotonically
	 * between events and cannot oscillate across a boundary.
	 *
	 * @param immediate the capacity basis changed for a reason that is not wear
	 * @return whether the published figure moved
	 */
	private boolean updatePublishedCapacity(boolean immediate) {
		boolean force = forceCapacityRepublish || immediate;
		forceCapacityRepublish = false;

		float raw = activelyGenerating ? getEffectiveCylinderCapacity() : 0.0F;
		float quantised = EngineWearMath.quantiseCapacity(raw);
		if (quantised == publishedCapacityFactor)
			return false;
		if (!force && Math.abs(raw - publishedCapacityFactor) < EngineTuning.CAPACITY_QUANTUM)
			return false;
		publishedCapacityFactor = quantised;
		return true;
	}

	/**
	 * The fastest rotation this engine may claim to be generating.
	 *
	 * <p>Its own speed, but never more than its own combustion could hold: the
	 * governor's torque reaches zero at {@code target + GOVERNOR_RANGE / 2}, so
	 * that is the engine's honest ceiling at the current throttle. The cap never
	 * binds during normal running - an engine sits on its target, with a couple of
	 * RPM of ripple and a small overshoot on the way up - and only bites when
	 * something else on the network is spinning the engine faster than it could
	 * ever drive itself. Without it, motoring an idling engine at 200 RPM would
	 * have tripled the capacity it contributes for no extra fuel.
	 */
	private float generationCeiling() {
		return Math.min(simulatedRpm, targetRpm + EngineTuning.GOVERNOR_RANGE_RPM / 2.0F);
	}

	/**
	 * Tracks {@link #outputRpm} towards the engine's honest instantaneous output.
	 *
	 * <p>A first-order low pass, with one deliberate exception: a step larger than
	 * {@link EngineTuning#OUTPUT_FILTER_SNAP_RPM} is adopted immediately. The
	 * filter exists to remove combustion ripple, not to blur events - catching,
	 * stalling, a throttle swung open or a load dropped are all real, and lagging
	 * behind them would be its own bug.
	 *
	 * <p>While the engine is not generating the filter simply follows the truth
	 * rather than decaying towards zero. Nothing is published from it then - the
	 * gate below sees to that - but it means an engine that re-catches starts from
	 * the speed it is actually turning at instead of ramping up from a stale value.
	 */
	private void updateOutputFilter() {
		float raw = generationCeiling();
		if (!activelyGenerating || Math.abs(raw - outputRpm) >= EngineTuning.OUTPUT_FILTER_SNAP_RPM)
			outputRpm = raw;
		else
			outputRpm += (raw - outputRpm) * EngineTuning.OUTPUT_FILTER_ALPHA;
	}

	/**
	 * Decides whether Create's generated speed needs to change.
	 *
	 * <p>The value offered to Create is the <i>filtered</i> output, so this rule no
	 * longer has to protect the network from combustion ripple and is therefore
	 * free to be a rate limit rather than a dead zone:
	 * <ul>
	 * <li>a difference of {@link EngineTuning#NETWORK_RPM_MAJOR_DELTA} or more -
	 * a throttle change, a load change, catching or stalling - is published as soon
	 * as {@link EngineTuning#NETWORK_MIN_UPDATE_INTERVAL_TICKS} allow;</li>
	 * <li>anything smaller, down to {@link EngineTuning#NETWORK_RPM_FINE_DELTA}, is
	 * published once {@link EngineTuning#NETWORK_RECONCILE_INTERVAL_TICKS} have
	 * passed - one second - so a small error can persist for a moment but never
	 * for ever;</li>
	 * <li>below the fine delta the published value is already within one quantum of
	 * the truth, and moving it would be churn with nothing to show for it.</li>
	 * </ul>
	 *
	 * <p><b>Every error above the fine delta is eventually published.</b> That is
	 * the property the old deadband lacked: it refused any correction smaller than
	 * itself, so wherever the published value happened to be parked - by a
	 * transient, or by a world reload restoring one - it stayed, for as long as the
	 * engine ran.
	 *
	 * <p>Transitions to and from zero bypass the interval entirely so the engine
	 * engages and disengages promptly; the large START/STALL gap is what guarantees
	 * those cannot repeat quickly enough to trip Create's flicker protection.
	 */
	private boolean updatePublishedRpm() {
		boolean force = forceGeneratedRepublish;
		forceGeneratedRepublish = false;

		updateOutputFilter();

		// The single gate. An engine that is not actively generating publishes
		// nothing, so Create's KineticNetwork#getActualCapacityOf - which multiplies
		// the registered capacity by |getGeneratedSpeed()| - hands it a capacity of
		// exactly zero, however fast the network is spinning it.
		float target = activelyGenerating ? outputRpm : 0.0F;

		if (target < EngineTuning.STALL_RPM) {
			if (publishedRpm == 0.0F)
				return false;
			publishedRpm = 0.0F;
			ticksSincePublish = 0;
			return true;
		}

		float quantised = quantiseForNetwork(target);
		if (quantised == publishedRpm)
			return false;
		if (!force && publishedRpm != 0.0F && !mayPublish(Math.abs(target - publishedRpm)))
			return false;

		publishedRpm = quantised;
		ticksSincePublish = 0;
		return true;
	}

	/** Whether an error of this size has waited long enough to be worth a network update. */
	private boolean mayPublish(float error) {
		if (error < EngineTuning.NETWORK_RPM_FINE_DELTA)
			return false;
		if (ticksSincePublish < EngineTuning.NETWORK_MIN_UPDATE_INTERVAL_TICKS)
			return false;
		return error >= EngineTuning.NETWORK_RPM_MAJOR_DELTA
			|| ticksSincePublish >= EngineTuning.NETWORK_RECONCILE_INTERVAL_TICKS;
	}

	/**
	 * Rounds a speed to the step Create is allowed to see it in.
	 *
	 * <p>The upper bound is the <i>runtime</i> limit, not the tuning constant:
	 * Create's {@code maxRotationSpeed} is a server config and going past it makes
	 * {@code RotationPropagator} destroy the block rather than merely refuse the
	 * speed.
	 */
	private float quantiseForNetwork(float rpm) {
		float quantised = Math.round(rpm / EngineTuning.NETWORK_RPM_QUANTUM) * EngineTuning.NETWORK_RPM_QUANTUM;
		return clamp(quantised, EngineTuning.NETWORK_RPM_QUANTUM, speedLimitRpm);
	}

	/**
	 * Demands that the next simulated tick publish the engine's real output,
	 * whatever the rate limits would otherwise have allowed.
	 *
	 * <p>For discontinuities, not for drift: the post-load reconciliation, and a
	 * change in who is driving the shaft. Both are moments where the value Create
	 * is holding may bear no relation to what the engine is doing, and waiting out
	 * an interval before saying so would leave a visible lie on the network.
	 */
	public void requestGeneratedRepublish() {
		forceGeneratedRepublish = true;
		// The capacity is the other half of what Create holds for this engine, and it
		// goes stale in exactly the same moments. Forcing one without the other is how
		// a reload could leave the right speed beside the wrong multiplier.
		forceCapacityRepublish = true;
	}

	// ------------------------------------------------------------------------
	// Accessors
	// ------------------------------------------------------------------------

	/**
	 * Where the crank pin is, in {@code [0, 360)}. What the renderers draw.
	 *
	 * <p>Derived from {@link #position} rather than stored beside it. A piston at top
	 * dead centre is at cycle angle 180 or 540 and this cannot tell those apart -
	 * which is the entire reason the cycle angle exists, and the entire reason this is
	 * a read rather than a field.
	 */
	public float getCrankAngleDegrees() {
		return position.physicalAngle();
	}

	/** Crank angle interpolated into the current frame, for renderers. */
	public float getRenderCrankAngleDegrees(float partialTicks) {
		return FourStrokeCycle.physicalAngle(getRenderCycleAngleDegrees(partialTicks));
	}

	/**
	 * Where the engine is in its four-stroke cycle, in {@code [0, 720)}.
	 *
	 * <p>Cylinder 1's own angle, and the master every other cylinder is offset from.
	 */
	public float getCycleAngleDegrees() {
		return position.angle();
	}

	/** The same, interpolated into the current frame. */
	public float getRenderCycleAngleDegrees(float partialTicks) {
		return FourStrokeCycle.normalizeCycle(position.angle() + lastAngleDeltaDegrees * partialTicks);
	}

	/** Which cycle this engine is in. Signed, and decremented by a backward wrap. */
	public long getCycleIndex() {
		return position.cycleIndex();
	}

	/** The engine's stroke, i.e. cylinder 1's. */
	public FourStrokePhase getEnginePhaseOfCycle() {
		return position.phase();
	}

	/** The camshaft's angle, in {@code [0, 360)}: half the cycle angle, always. */
	public float getCamshaftAngleDegrees() {
		return dev.engineeredcombustion.content.engine.fourstroke.CamshaftTiming.camAngle(position.angle());
	}

	/** The same, interpolated into the current frame. */
	public float getRenderCamshaftAngleDegrees(float partialTicks) {
		return dev.engineeredcombustion.content.engine.fourstroke.CamshaftTiming
			.camAngle(getRenderCycleAngleDegrees(partialTicks));
	}

	/** Whether this engine has a Camshaft, and therefore a valvetrain at all. */
	public boolean hasCamshaft() {
		return camshaftInstalled;
	}

	/** The crank and firing schedule this engine is running. */
	public FourStrokeFiringOrder getConfiguration() {
		return configuration;
	}

	/** Whether cylinder {@code i} is holding an inducted charge it has not yet burned. */
	public boolean isArmed(int cylinder) {
		return cylinder >= 0 && cylinder < armed.length && armed[cylinder];
	}

	/** The arming latches as one integer, for persistence and synchronisation. */
	public int getArmedMask() {
		int mask = 0;
		for (int cylinder = 0; cylinder < cylinderCount; cylinder++)
			if (armed[cylinder])
				mask |= 1 << cylinder;
		return mask;
	}

	/** The speed the crank is really turning at this tick. Drives all animation. */
	public float getMechanicalRpm() {
		return mechanicalRpm;
	}

	/** The engine's angular velocity: the one momentum, whatever is causing it. */
	public float getSimulatedRpm() {
		return simulatedRpm;
	}

	/**
	 * The latched value Create sees as this engine's generated speed: the engine's
	 * own speed, filtered and quantised. Derived from the simulation, never the
	 * other way round.
	 */
	public float getPublishedRpm() {
		return publishedRpm;
	}

	/**
	 * The engine's output with the combustion ripple filtered out - what the
	 * published speed is quantised from. Diagnostic; the simulation reads
	 * {@link #getSimulatedRpm()}.
	 */
	public float getOutputRpm() {
		return outputRpm;
	}

	/**
	 * Whether this engine is producing power right now, and therefore whether it
	 * may contribute generated rotation and Stress Capacity to Create.
	 *
	 * <p>Valid on both sides: evaluated on the server by
	 * {@link #evaluateActiveGeneration()} and synchronised. Ask this - never a
	 * combination of phase, fuel and speed assembled at the call site.
	 */
	public boolean isActivelyGenerating() {
		return activelyGenerating;
	}

	/**
	 * Whether the crankshaft is turning on stored momentum alone, with nothing on
	 * the kinetic network driving it.
	 */
	public boolean isFreeRotating() {
		return freeRotation;
	}

	/** Whether something other than this engine is turning the shaft. */
	public boolean isExternallyDriven() {
		return externallyDriven;
	}

	public EnginePhase getPhase() {
		return phase;
	}

	/** Whether any cylinder is being pushed by a burning charge right now. */
	public boolean isPowerStrokeActive() {
		for (int cylinder = 0; cylinder < cylinderCount; cylinder++)
			if (powerStrokeActive[cylinder])
				return true;
		return false;
	}

	public boolean isPowerStrokeActive(int cylinder) {
		return cylinder >= 0 && cylinder < cylinderCount && powerStrokeActive[cylinder];
	}

	/** How many cylinders this engine has, 1 to {@link EngineTuning#MAX_CYLINDERS}. */
	public int getCylinderCount() {
		return cylinderCount;
	}

	/** Whether cylinder {@code index} has a Spark Plug in its head. */
	public boolean hasSparkPlug(int cylinder) {
		return cylinder >= 0 && cylinder < cylinderCount && (sparkPlugMask & (1 << cylinder)) != 0;
	}

	/** How many of this engine's cylinders have a Spark Plug fitted. */
	public int getSparkPlugCount() {
		return Integer.bitCount(sparkPlugMask & ((1 << cylinderCount) - 1));
	}

	/** Which cylinders have a Spark Plug, one bit each. Set through {@link #setLayout}. */
	public int getSparkPlugMask() {
		return sparkPlugMask;
	}

	/** This cylinder's crank phase, in degrees - 0, 90, 180, 270 for an inline-4. */
	public float getPhaseOffsetDegrees(int cylinder) {
		return EngineTuning.cylinderPhaseOffsetDegrees(cylinder, cylinderCount);
	}

	/** Main throttle opening on the last simulated tick, {@code [0, 1]}. */
	public float getThrottle() {
		return throttle;
	}

	/** Speed the current throttle setting is asking the engine to hold. */
	public float getTargetRpm() {
		return targetRpm;
	}

	/** Network stress over capacity on the last simulated tick, {@code [0, 1]}. */
	public float getLoadFactor() {
		return loadFactor;
	}

	/**
	 * Ignition firings in one cylinder so far. Compare against a remembered value
	 * to detect that the coil fired; never interpret the number itself.
	 */
	public int getSparkEventId(int cylinder) {
		return cylinder >= 0 && cylinder < sparkEventIds.length ? sparkEventIds[cylinder] : 0;
	}

	/** Charges burned in one cylinder. Same contract as {@link #getSparkEventId(int)}. */
	public int getCombustionEventId(int cylinder) {
		return cylinder >= 0 && cylinder < combustionEventIds.length ? combustionEventIds[cylinder] : 0;
	}

	/** Whether this cylinder's combustion chamber should be drawn lit this frame. */
	public boolean isCombustionFlashActive(int cylinder) {
		return cylinder >= 0 && cylinder < combustionFlashTicks.length && combustionFlashTicks[cylinder] > 0;
	}

	/**
	 * Flash brightness in one cylinder, 1 on the tick it fired and fading to 0.
	 *
	 * @param partialTicks interpolation into the current frame, so the fade is
	 *                     smooth rather than stepping once per tick
	 */
	public float getCombustionFlashIntensity(int cylinder, float partialTicks) {
		if (!isCombustionFlashActive(cylinder))
			return 0.0F;
		float remaining = combustionFlashTicks[cylinder] - partialTicks;
		if (remaining <= 0.0F)
			return 0.0F;
		return remaining / EngineTuning.COMBUSTION_FLASH_TICKS;
	}

	/** Firing opportunities banked so far in the current start attempt. */
	public int getStartProgress() {
		return startProgress;
	}

	/** How many this attempt needs, or 0 when no attempt is in progress. */
	public int getRequiredStartCycles() {
		return requiredStartCycles;
	}

	/** Whether the fuel supply reported usable fuel on the last simulated tick. */
	public boolean isFuelAvailable() {
		return fuelAvailable;
	}

	public boolean isIgnitionEnabled() {
		return ignitionEnabled;
	}

	/**
	 * Whether <i>every</i> cylinder had a Spark Plug on the last simulated tick.
	 *
	 * <p>Derived from the per-cylinder mask, which is what is synchronised - there
	 * is deliberately no separate all-or-nothing flag on the wire, because one used
	 * to arrive after the mask and flatten it. False on an inline-4 missing one
	 * plug; how many are missing, and which, is {@link #getSparkPlugCount()} and
	 * {@link #hasSparkPlug(int)}.
	 */
	public boolean isSparkPlugInstalled() {
		return getSparkPlugCount() == cylinderCount;
	}

	/** How well lubricated the engine was on the last simulated tick. */
	public LubricationState getLubrication() {
		return lubrication;
	}

	/**
	 * The condition of the parts this engine is made of, as of the last tick.
	 *
	 * <p>On the server this is the whole picture. On the client it carries the
	 * average bearing wear and nothing else - see {@link #setWear(EngineWearInputs)}
	 * - so per-cylinder compression is read from the Cylinder block entities that
	 * own it rather than from here.
	 */
	public EngineWearInputs getWear() {
		return wear;
	}

	/**
	 * How much the crank turned on the last tick, in revolutions. Always positive.
	 *
	 * <p>The clock every wear rate is quoted against. Using the angle the crank
	 * actually advanced by - rather than a tick count or a nominal speed - is what
	 * makes wear follow the work the machine did: an engine held at 220 RPM by
	 * another Create source wears its bearings for 220 RPM, and a server running
	 * below 20 TPS wears its engines no faster per revolution than one running at
	 * full speed.
	 */
	public float getRevolutionsThisTick() {
		return Math.abs(lastAngleDeltaDegrees) / 360.0F;
	}

	/**
	 * Whether the crankshaft has genuinely stopped - nothing is turning it and it
	 * has no momentum left.
	 *
	 * <p>The condition internal service is gated on: a Piston Assembly may not be
	 * pulled out of a bore whose piston is still moving, whether the engine is
	 * running under its own power, coasting down, or being motored by a neighbour.
	 */
	public boolean isAtRest() {
		return Math.abs(mechanicalRpm) < EngineTuning.REST_RPM
			&& Math.abs(simulatedRpm) < EngineTuning.REST_RPM;
	}

	/** Running combustion events banked towards the next millibucket of oil. */
	public int getCombustionEventsSinceOilDraw() {
		return combustionEventsSinceOilDraw;
	}

	public boolean isStructureValid() {
		return structureValid;
	}

	/**
	 * Ticks since <i>any</i> cylinder last burned a charge, or -1 if none ever has.
	 */
	public int getTicksSinceCombustion() {
		int youngest = -1;
		for (int cylinder = 0; cylinder < cylinderCount; cylinder++) {
			int age = ticksSinceCombustion[cylinder];
			if (age >= 0 && (youngest < 0 || age < youngest))
				youngest = age;
		}
		return youngest;
	}

	/** Ticks since one cylinder burned a charge, or -1 if it never has. */
	public int getTicksSinceCombustion(int cylinder) {
		return cylinder >= 0 && cylinder < ticksSinceCombustion.length ? ticksSinceCombustion[cylinder] : -1;
	}

	/**
	 * Where the rotation on this crankshaft is coming from.
	 *
	 * <p>Ordered so the answer is the <i>cause</i> rather than a symptom: an engine
	 * that is burning fuel is its own source even while a neighbour also drives the
	 * network, and an engine that is merely being spun says so plainly - which is
	 * the line to read when checking that a multi-engine network is honest.
	 */
	public RotationSource getRotationSource() {
		if (activelyGenerating)
			return RotationSource.ENGINE;
		if (externallyDriven && mechanicalRpm != 0.0F)
			return RotationSource.EXTERNAL;
		if (mechanicalRpm != 0.0F)
			return RotationSource.MOMENTUM;
		return RotationSource.NONE;
	}

	public float getPistonPosition() {
		return CrankMath.pistonPosition(position.physicalAngle());
	}

	// ------------------------------------------------------------------------
	// Persistence / synchronisation support
	// ------------------------------------------------------------------------

	/**
	 * Places the engine at a physical crank angle, leaving it on the first half of the
	 * cycle.
	 *
	 * <p><b>For legacy saves and for tests, not for ordinary restore.</b> A physical
	 * angle does not determine a cycle position, so this is the migration rule of
	 * {@code EngineSchema} in method form: keep the piston exactly where it was and
	 * choose the compression/power half, which is the half whose mechanical meaning
	 * matches what the old 360-degree engine was doing. Safety does not rest on that
	 * choice - it rests on the arming latches being empty, which the caller must also
	 * ensure.
	 */
	public void setCrankAngleDegrees(float crankAngleDegrees) {
		position.set(0L, FourStrokeCycle.normalizeRevolution(crankAngleDegrees));
	}

	/** Restores the authoritative position outright. */
	public void setCyclePosition(long cycleIndex, float cycleAngleDegrees) {
		position.set(cycleIndex, cycleAngleDegrees);
	}

	/**
	 * Adopts a cycle position sent by the server, and the arming state that goes with
	 * it. <b>Client side.</b>
	 *
	 * <p>The client cannot derive which stroke a cylinder is on: the piston looks
	 * identical half a cycle apart, so a client left to integrate from a physical
	 * angle would sit exactly one stroke out of phase, with the valves of a
	 * compressing cylinder wide open. This is the correction that cannot happen, and
	 * it is deliberately a whole-position assignment rather than a nudge - a phase
	 * error is never small.
	 */
	public void adoptCyclePhase(long cycleIndex, float cycleAngleDegrees, int armedMask) {
		position.set(cycleIndex, cycleAngleDegrees);
		setArmedMask(armedMask);
	}

	/** Restores the arming latches from their persisted or synchronised integer. */
	public void setArmedMask(int armedMask) {
		for (int cylinder = 0; cylinder < armed.length; cylinder++)
			armed[cylinder] = cylinder < cylinderCount && (armedMask & (1 << cylinder)) != 0;
	}

	/** The per-cylinder last-taken firing opportunities, as a copy safe to hand to NBT. */
	public long[] copyOfLastFiredCycles() {
		return lastFiredCycle.clone();
	}

	/**
	 * Restores which firing opportunity each cylinder last took.
	 *
	 * <p>Persisted because it is what makes a duplicate combustion detectable, and a
	 * save taken between a cylinder's ignition and the end of its power stroke would
	 * otherwise come back able to light the very same opportunity a second time.
	 */
	public void setLastFiredCycles(long[] cycles) {
		for (int cylinder = 0; cylinder < lastFiredCycle.length; cylinder++)
			lastFiredCycle[cylinder] =
				cylinder < cycles.length ? cycles[cylinder] : CylinderCycleState.NO_EVENT;
	}

	public void setPhase(EnginePhase phase) {
		this.phase = phase;
	}

	/**
	 * Restores the engine's momentum, bounded only against corrupt data.
	 *
	 * <p>Not clamped to the engine's own {@code MAX_RPM}: an engine that a fast
	 * external network is spinning genuinely holds more momentum than it could ever
	 * make for itself, and that momentum is what its coast-down is made of. Clamping
	 * it here would have put the RPM snap back on the one path a chunk reload still
	 * went through.
	 */
	public void setSimulatedRpm(float simulatedRpm) {
		this.simulatedRpm = clamp(simulatedRpm, -EngineTuning.ABSOLUTE_MAX_RPM, EngineTuning.ABSOLUTE_MAX_RPM);
	}

	/**
	 * Adopts the server's published speed. <b>Client side only.</b>
	 *
	 * <p>On the server this value is never restored, only computed: see
	 * {@link #restoreAfterLoad(boolean)}. It exists on the client so the goggle
	 * diagnostics can print what Create is really being told rather than an
	 * approximation of it.
	 */
	public void setPublishedRpm(float publishedRpm) {
		this.publishedRpm = publishedRpm;
	}

	/**
	 * Restores the derived half of the engine after a world load, from the
	 * authoritative half.
	 *
	 * <p>Call once, on the server, after the persisted simulation state - the
	 * signed simulated RPM above all - has been read back. What it rebuilds is
	 * everything that is merely a <i>representation</i> of that state:
	 * <ul>
	 * <li>the output filter, seeded from the engine's own momentum so the first
	 * tick does not ramp up from zero;</li>
	 * <li>the published speed, reconstructed from that same momentum rather than
	 * restored from a saved copy of itself. Reconstructing it is what stops a value
	 * Create happened to be holding when the world was saved from outliving the
	 * physical state it was supposed to describe;</li>
	 * <li>a demand that the next tick publish the result, bypassing the rate
	 * limits.</li>
	 * </ul>
	 *
	 * <p>The reconstruction here is deliberately provisional - it exists so that
	 * Create's own restored network speed has something coherent to agree with for
	 * the tick or two before the engine's components are resolvable. The
	 * <i>authoritative</i> answer comes from the first reconciled simulation tick,
	 * which re-derives generation from the world - structure, plug, fuel, oil - and
	 * force-publishes whatever it finds, including zero.
	 *
	 * @param wasGenerating the engine's own saved answer to
	 *                      {@link #isActivelyGenerating()}. Trusted only as far as
	 *                      the next tick, and never upwards: an engine that was not
	 *                      generating reconstructs no generated speed at all, so a
	 *                      dead engine cannot come back from a save with capacity.
	 */
	public void restoreAfterLoad(boolean wasGenerating) {
		activelyGenerating = wasGenerating;
		// A charge that was mid-stroke when the world closed is not carried across. The
		// arming latch and the firing key ARE persisted - they are what stop a reloaded
		// engine re-taking an opportunity it already took - but the burning charge
		// itself is transient, and reconstructing one would be inventing torque that
		// nothing paid for. At most one impulse is lost, on a reload, once.
		java.util.Arrays.fill(chargeBurning, false);
		java.util.Arrays.fill(powerStrokeActive, false);
		java.util.Arrays.fill(powerStrokeStrength, 0.0F);
		powerStrokeInProgress = false;
		// Rebuilt from the restored combustion ages, exactly as the running simulation
		// would, so the first tick back already knows which cylinders were working -
		// and a client joining a running engine is told at once rather than having to
		// wait for each cylinder to fire before its HUD discovers it.
		activeCylinderMask = deriveActiveCylinderMask();
		outputRpm = simulatedRpm;
		publishedRpm = wasGenerating && simulatedRpm >= EngineTuning.STALL_RPM
			? quantiseForNetwork(simulatedRpm)
			: 0.0F;
		// No artificial wait before the first correction: the reconciliation is
		// forced anyway, and this keeps any later correction on the same footing as
		// an engine that never unloaded.
		ticksSincePublish = EngineTuning.NETWORK_RECONCILE_INTERVAL_TICKS;
		forceGeneratedRepublish = true;
		// Derived like everything else here, and NOT restored from disk beside the
		// wear it comes from. The parts carry their own condition across a save, so
		// rebuilding the multiplier from them is the only way it cannot come back
		// disagreeing with the engine it describes. The first reconciled tick then
		// replaces even this with a freshly resolved value.
		publishedCapacityFactor = activelyGenerating ? EngineWearMath.quantiseCapacity(getEffectiveCylinderCapacity())
			: 0.0F;
		forceCapacityRepublish = true;
	}

	/**
	 * Restores how long ago the last charge burned.
	 *
	 * <p>Persisted because it is genuinely part of the engine's physical state:
	 * {@link #combustionIsCurrent()} is what separates an engine running on
	 * combustion from one merely being turned, and it is the condition an external
	 * source cannot fake. Without it a saved running engine declared itself
	 * non-generating on its first tick back - which tore its kinetic network down
	 * and rebuilt it a moment later, for no reason other than a counter having been
	 * dropped.
	 *
	 * <p>Time spent unloaded does not count against it. The engine was not turning
	 * while the world was closed, so no firing opportunities were missed.
	 */
	public void setTicksSinceCombustion(int[] ticks) {
		copyInto(ticks, ticksSinceCombustion);
		for (int cylinder = 0; cylinder < ticksSinceCombustion.length; cylinder++)
			ticksSinceCombustion[cylinder] = Math.max(-1, ticksSinceCombustion[cylinder]);
		// The ages are the physical state; the mask is a reading of them. Re-deriving
		// it here rather than restoring a saved copy beside them is the same rule the
		// published RPM follows, and for the same reason: two persisted copies of one
		// fact come back disagreeing.
		activeCylinderMask = deriveActiveCylinderMask();
	}

	/** The same, for every cylinder at once. */
	public void setTicksSinceCombustion(int ticks) {
		java.util.Arrays.fill(ticksSinceCombustion, Math.max(-1, ticks));
		activeCylinderMask = deriveActiveCylinderMask();
	}

	/**
	 * Adopts the server's capacity basis. <b>Client side.</b>
	 *
	 * <p>The client is told which cylinders are working rather than deriving it,
	 * because it cannot derive it: {@link #ticksSinceCombustion} is simulation
	 * state that never leaves the server, so a client-side derivation answered
	 * "none of them" for every engine that ever ran.
	 *
	 * <p>On the server this is never called - {@code tickSimulation} owns the
	 * field - and bits past the engine's cylinder count are dropped, so a mask that
	 * arrives before a shrink is synchronised cannot claim a cylinder that is no
	 * longer there.
	 */
	public void setActiveCylinderMask(int activeCylinderMask) {
		this.activeCylinderMask = activeCylinderMask & ((1 << cylinderCount) - 1);
	}

	/**
	 * Restores the engine's layout, so the very first tick back after a world load
	 * already knows how many cylinders it is reconciling.
	 *
	 * <p>Overwritten from the resolved assembly on that tick - the world, not the
	 * tag, decides how many cylinders an engine has - but having it right for one
	 * tick keeps the reconstructed generated speed and the phase offsets the
	 * renderers ask for from being briefly wrong on a four-cylinder engine.
	 */
	public void setLayout(int cylinderCount, int sparkPlugMask) {
		setCylinderCount(cylinderCount);
		this.sparkPlugMask = sparkPlugMask & ((1 << this.cylinderCount) - 1);
		// An engine that just lost a section cannot still be firing on it. Trimming
		// here means no reading taken between this and the next simulated tick can
		// count a cylinder the engine no longer has.
		this.activeCylinderMask &= (1 << this.cylinderCount) - 1;
	}

	/**
	 * Adopts a cylinder count, and with it the crank and firing schedule that count
	 * implies.
	 *
	 * <p><b>The one place a layout becomes a configuration.</b> Nothing else assigns
	 * {@link #configuration}, so an engine can never be running one layout's throws
	 * against another's ignition order - which on an inline-3 would silently play the
	 * firing order backwards.
	 *
	 * <p>A section added or removed re-shapes the engine, and the cylinders it still
	 * has now sit at different cycle phases. Their arming latches and firing keys
	 * describe the old shape, so they are dropped: the alternative is a cylinder
	 * holding a charge it drew on a stroke it is no longer on.
	 */
	private void setCylinderCount(int cylinderCount) {
		int clamped = Math.min(Math.max(cylinderCount, 1), EngineTuning.MAX_CYLINDERS);
		if (clamped == this.cylinderCount)
			return;
		this.cylinderCount = clamped;
		this.configuration = FourStrokeFiringOrder.forCylinderCount(clamped);
		java.util.Arrays.fill(armed, false);
		java.util.Arrays.fill(lastFiredCycle, CylinderCycleState.NO_EVENT);
	}

	/** Copies as much of {@code from} as fits into {@code into}, leaving the rest. */
	private static void copyInto(int[] from, int[] into) {
		System.arraycopy(from, 0, into, 0, Math.min(from.length, into.length));
	}

	private static int[] newAges() {
		int[] ages = new int[EngineTuning.MAX_CYLINDERS];
		java.util.Arrays.fill(ages, -1);
		return ages;
	}

	private static long[] newFiringKeys() {
		long[] keys = new long[EngineTuning.MAX_CYLINDERS];
		java.util.Arrays.fill(keys, CylinderCycleState.NO_EVENT);
		return keys;
	}

	/**
	 * Adopts the server's answer to {@link #isActivelyGenerating()}.
	 *
	 * <p>Synchronised rather than recomputed, so no client-side approximation of
	 * the predicate can ever exist to disagree with the server's.
	 */
	/**
	 * Forces the generation flag from outside the simulation.
	 *
	 * <p>Used when the world takes the engine apart under it, and on the client,
	 * which is told the server's answer rather than deriving one. An engine that is
	 * not generating supplies no capacity by definition, so the multiplier goes with
	 * it - otherwise a section mined out of a running inline-4 would leave its
	 * flywheel holding a figure nobody would ever revise.
	 */
	public void setActivelyGenerating(boolean activelyGenerating) {
		if (!activelyGenerating)
			publishedCapacityFactor = 0.0F;
		this.activelyGenerating = activelyGenerating;
	}

	public void setStartAttempt(int startProgress, int requiredStartCycles) {
		this.startProgress = Math.max(0, startProgress);
		this.requiredStartCycles = Math.max(0, requiredStartCycles);
	}

	public void setFuelAvailable(boolean fuelAvailable) {
		this.fuelAvailable = fuelAvailable;
	}

	/**
	 * Adopts the server's per-cylinder event counters. Client side, from the
	 * synchronised block entity data; the values are never interpreted, only
	 * compared.
	 *
	 * <p>Either array may be shorter than {@link EngineTuning#MAX_CYLINDERS} - an
	 * engine that grew a cylinder since the tag was written, or a tag from before
	 * multi-cylinder engines existed - and the missing entries simply stay as they
	 * are.
	 */
	public void setEventIds(int[] sparkEventIds, int[] combustionEventIds) {
		copyInto(sparkEventIds, this.sparkEventIds);
		copyInto(combustionEventIds, this.combustionEventIds);
	}

	/** The per-cylinder spark counters, as a copy safe to hand to NBT. */
	public int[] copyOfSparkEventIds() {
		return sparkEventIds.clone();
	}

	/** The per-cylinder combustion counters, as a copy safe to hand to NBT. */
	public int[] copyOfCombustionEventIds() {
		return combustionEventIds.clone();
	}

	/** The per-cylinder combustion ages, as a copy safe to hand to NBT. */
	public int[] copyOfTicksSinceCombustion() {
		return ticksSinceCombustion.clone();
	}

	/**
	 * Gives the client the part of the engine's condition its own half of the
	 * physics needs.
	 *
	 * <p>Which is the average bearing wear, and only that. The client integrates a
	 * freewheeling engine's spin-down itself - a coasting engine generates nothing,
	 * so Create has no speed left to synchronise - and that integration fights the
	 * engine's friction, which worn bearings multiply. Without this the two sides
	 * would trace different curves and the periodic resync would visibly correct
	 * the client every second.
	 *
	 * <p>Everything else the client shows about condition comes from the blocks
	 * themselves: per-cylinder compression from the Cylinder block entities, each
	 * section's bearing condition from that section. The one figure that could not
	 * work that way is the capacity multiplier, which is derived from the combustion
	 * ages the client is never sent - so that is synchronised outright, as
	 * {@link #setPublishedCapacityFactor(float)}.
	 */
	public void setWear(EngineWearInputs wear) {
		this.wear = wear == null ? EngineWearInputs.PRISTINE : wear;
	}

	/**
	 * The capacity multiplier the server decided on. Client only - on the server
	 * this is derived once per tick and never assigned from outside.
	 */
	public void setPublishedCapacityFactor(float publishedCapacityFactor) {
		this.publishedCapacityFactor = Math.max(0.0F, publishedCapacityFactor);
	}

	public void setLubrication(LubricationState lubrication) {
		this.lubrication = lubrication;
	}

	/** Restores the wear counter so a chunk reload does not reset oil progress. */
	public void setCombustionEventsSinceOilDraw(int events) {
		this.combustionEventsSinceOilDraw = Math.max(0, events);
	}

	public void setIgnitionEnabled(boolean ignitionEnabled) {
		this.ignitionEnabled = ignitionEnabled;
	}

	/**
	 * Adopts whether the engine has a Camshaft.
	 *
	 * <p>On the server this is overwritten from the world on the next simulated tick;
	 * it is set here so a client - which cannot resolve the assembly - can draw the
	 * valvetrain and diagnose a missing one.
	 */
	public void setCamshaftInstalled(boolean camshaftInstalled) {
		this.camshaftInstalled = camshaftInstalled;
	}

	// There is deliberately no setSparkPlugInstalled(boolean). It existed for a tag
	// that carried only "are all the plugs in", and because read() applied it AFTER
	// setLayout it overwrote the real per-cylinder mask with an all-or-nothing one:
	// on the client an inline-4 missing a single plug came out as an engine with no
	// plugs at all. The per-cylinder mask is the only representation now, on both
	// sides, and it arrives through setLayout.

	public void setStructureValid(boolean structureValid) {
		this.structureValid = structureValid;
	}
	// externallyDriven has no setter on purpose: tickRotation is the one place it
	// is written, on both sides, from Create's own synchronised source pointer.

	// ------------------------------------------------------------------------

	private static float clamp(float value, float min, float max) {
		return value < min ? min : Math.min(value, max);
	}

	private static float normalizeDegrees(float degrees) {
		float wrapped = degrees % 360.0F;
		return wrapped < 0.0F ? wrapped + 360.0F : wrapped;
	}
}
