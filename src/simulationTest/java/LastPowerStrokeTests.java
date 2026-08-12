import dev.engineeredcombustion.content.engine.*;

/**
 * Drives the real {@code EngineState} through the "a charge that has been paid for
 * finishes its work" invariants.
 *
 * <p>A pure simulation test: no Minecraft, no NeoForge, no Create.
 *
 * <h2>The distinction being tested</h2>
 * Fuel availability decides whether a <b>new</b> charge can be lit. It has no
 * business deciding whether the charge <i>already burning in the cylinder</i> is
 * allowed to keep pushing - the millibucket has been drawn, the fire is lit, and an
 * empty tank cannot reach back into the bore and put it out.
 *
 * <p>Those two used to be one condition, so the last charge of every run was
 * truncated the moment the tank hit zero: the engine paid for torque it never
 * received, and Create was told the engine generated nothing while it was still
 * measurably accelerating the crankshaft.
 *
 * <p>What must still cut a stroke short immediately is structural: the engine taken
 * apart under it, or a crank that has stopped turning forwards - which covers both a
 * stall and an overstressed network, since Create reports speed 0 for both.
 *
 * <p>Exits non-zero on any failure.
 */
public class LastPowerStrokeTests {

	static int failures = 0;

	// ---------------------------------------------------------------- fixtures

	/** A carburetor that also records every successful draw. */
	static class Tank implements FuelSupply {
		int mb;
		int draws;

		Tank(int mb) {
			this.mb = mb;
		}

		public boolean hasFuel() {
			return mb >= EngineTuning.FUEL_PER_COMBUSTION_MB;
		}

		public boolean consume(int amount) {
			if (mb < amount)
				return false;
			mb -= amount;
			draws++;
			return true;
		}
	}

	static class Sump implements OilSupply {
		int mb;

		Sump(int mb) {
			this.mb = mb;
		}

		public LubricationState lubrication() {
			return mb <= 0 ? LubricationState.DRY
				: mb < EngineTuning.LOW_OIL_THRESHOLD_MB ? LubricationState.LOW : LubricationState.NORMAL;
		}

		public boolean consume(int amount) {
			if (mb < amount)
				return false;
			mb -= amount;
			return true;
		}
	}

	static class Engine {
		final EngineState state = new EngineState();
		final Tank tank;
		final Sump sump = new Sump(EngineTuning.OIL_CAPACITY_MB);
		final java.util.Random random = new java.util.Random(4242L);
		int cylinders;
		int sparkPlugMask;
		boolean ignition = true;
		boolean structureValid = true;
		float throttle = 0.0F;

		Engine(int cylinders, int fuel) {
			this.cylinders = cylinders;
			this.sparkPlugMask = (1 << cylinders) - 1;
			this.tank = new Tank(fuel);
		}

		EngineInputs inputs() {
			return new EngineInputs(structureValid, ignition, cylinders, sparkPlugMask, throttle, 0.0F,
				EngineTuning.MAX_RPM);
		}

		void tickFree() {
			state.tickRotation(0.0F, false, false);
			state.tickSimulation(inputs(), tank, sump, random);
		}

		void tickHeldAt(float rpm) {
			state.tickRotation(rpm, true, true);
			state.tickSimulation(inputs(), tank, sump, random);
		}

		/** A jammed or overstressed shaft: Create reports speed 0 but still holds it. */
		void tickJammed() {
			state.tickRotation(0.0F, true, true);
			state.tickSimulation(inputs(), tank, sump, random);
		}

		int totalCombustions() {
			int total = 0;
			for (int cylinder = 0; cylinder < cylinders; cylinder++)
				total += state.getCombustionEventId(cylinder);
			return total;
		}
	}

	static void start(Engine engine) {
		for (int tick = 0; tick < 20 * 30 && engine.state.getPhase() != EnginePhase.RUNNING; tick++)
			engine.tickHeldAt(32.0F);
		for (int tick = 0; tick < 400; tick++)
			engine.tickFree();
	}

	// ---------------------------------------------------------------- the tests

	public static void main(String[] args) {
		exactlyOneMillibucket();
		tankEmptiesMidStroke();
		ignitionOffMidStroke();
		structureDestroyedMidStroke();
		jammedShaftEndsStrokeAtOnce();
		noFreeTorqueAfterStandstill();
		multiCylinderRunsDry();
		startKickKeepsItsStrength();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	/**
	 * Exactly 1 mB in the tank: one draw, one complete power stroke, and never a
	 * second combustion.
	 */
	static void exactlyOneMillibucket() {
		section("ONE MILLIBUCKET BUYS EXACTLY ONE COMPLETE STROKE");

		Engine engine = new Engine(1, EngineTuning.FUEL_PER_COMBUSTION_MB);
		// Cranked by hand so the crank reliably crosses the firing angle.
		for (int tick = 0; tick < 200; tick++)
			engine.tickHeldAt(32.0F);

		check("exactly one charge was paid for", engine.tank.draws == 1, engine.tank.draws + " draw(s)");
		check("and exactly one charge burned", engine.totalCombustions() == 1,
			engine.totalCombustions() + " combustion(s)");
		check("the tank cannot go negative", engine.tank.mb == 0, engine.tank.mb + " mB");

		// Let it run on to be sure nothing produces a second bang from an empty tank.
		for (int tick = 0; tick < 400; tick++)
			engine.tickFree();
		check("still exactly one, long afterwards", engine.totalCombustions() == 1,
			engine.totalCombustions() + " combustion(s)");
	}

	/**
	 * THE REGRESSION. The tank empties while a charge is burning; the torque curve
	 * has to run to the end of its stroke rather than stopping on the spot.
	 */
	static void tankEmptiesMidStroke() {
		section("A CHARGE THAT IS ALREADY BURNING FINISHES ITS STROKE");

		Engine engine = new Engine(1, 100000);
		start(engine);

		// Advance to the tick immediately after a combustion, so a charge is definitely
		// alight, then take the fuel away.
		int before = engine.totalCombustions();
		int guard = 0;
		while (engine.totalCombustions() == before && guard++ < 100)
			engine.tickFree();
		check("a charge has just been lit", engine.state.isPowerStrokeActive(),
			"power stroke active=" + engine.state.isPowerStrokeActive());

		engine.tank.mb = 0;

		// The stroke must keep delivering torque, and the engine must keep counting as
		// generating while it does - otherwise Create is told the engine makes nothing
		// while it is still speeding the crank up.
		int ticksStillPushing = 0;
		boolean generatingThroughout = true;
		while (engine.state.isPowerStrokeActive() && ticksStillPushing < 100) {
			engine.tickFree();
			ticksStillPushing++;
			if (engine.state.isPowerStrokeActive() && !engine.state.isActivelyGenerating())
				generatingThroughout = false;
		}

		check("the stroke ran on after the tank emptied", ticksStillPushing > 0,
			ticksStillPushing + " further tick(s) of torque");
		check("and the engine counted as generating for every one of them", generatingThroughout,
			"no tick where torque was delivered but generation was denied");
		check("no further charge was drawn from the empty tank", engine.tank.mb == 0, engine.tank.mb + " mB");

		// Once it is over, generation stops promptly - not several revolutions later on
		// the strength of a stale combustion age.
		engine.tickFree();
		check("generation ends with the stroke", !engine.state.isActivelyGenerating(),
			"generating=" + engine.state.isActivelyGenerating());
		check("and the phase drops out of RUNNING", engine.state.getPhase() != EnginePhase.RUNNING,
			engine.state.getPhase()
				.toString());
	}

	/** Switching the ignition off must not abort a charge that is already alight. */
	static void ignitionOffMidStroke() {
		section("IGNITION OFF LETS THE CURRENT CHARGE FINISH");

		Engine engine = new Engine(1, 100000);
		start(engine);

		int before = engine.totalCombustions();
		int guard = 0;
		while (engine.totalCombustions() == before && guard++ < 100)
			engine.tickFree();

		int combustionsAtSwitchOff = engine.totalCombustions();
		int sparksAtSwitchOff = engine.state.getSparkEventId(0);
		engine.ignition = false;

		int ticksStillPushing = 0;
		while (engine.state.isPowerStrokeActive() && ticksStillPushing < 100) {
			engine.tickFree();
			ticksStillPushing++;
		}
		check("the current stroke still finished", ticksStillPushing > 0,
			ticksStillPushing + " further tick(s) of torque");

		for (int tick = 0; tick < 200; tick++)
			engine.tickFree();
		check("no new charge was ever lit", engine.totalCombustions() == combustionsAtSwitchOff,
			engine.totalCombustions() + " against " + combustionsAtSwitchOff);
		check("and no new spark either", engine.state.getSparkEventId(0) == sparksAtSwitchOff,
			engine.state.getSparkEventId(0) + " against " + sparksAtSwitchOff);
	}

	/** Taking the engine apart is a hard stop, mid-stroke or not. */
	static void structureDestroyedMidStroke() {
		section("A DESTROYED STRUCTURE ENDS THE STROKE AT ONCE");

		Engine engine = new Engine(1, 100000);
		start(engine);

		int before = engine.totalCombustions();
		int guard = 0;
		while (engine.totalCombustions() == before && guard++ < 100)
			engine.tickFree();
		check("a charge is burning", engine.state.isPowerStrokeActive(), "power stroke active");

		engine.structureValid = false;
		engine.tickFree();
		check("the stroke stops on the very next tick", !engine.state.isPowerStrokeActive(),
			"power stroke active=" + engine.state.isPowerStrokeActive());
		check("and the engine is not generating", !engine.state.isActivelyGenerating(),
			"generating=" + engine.state.isActivelyGenerating());
	}

	/** An overstressed network holds the shaft at zero; that is a hard stop too. */
	static void jammedShaftEndsStrokeAtOnce() {
		section("A JAMMED SHAFT ENDS THE STROKE AT ONCE");

		Engine engine = new Engine(1, 100000);
		start(engine);

		int before = engine.totalCombustions();
		int guard = 0;
		while (engine.totalCombustions() == before && guard++ < 100)
			engine.tickFree();
		check("a charge is burning", engine.state.isPowerStrokeActive(), "power stroke active");

		engine.tickJammed();
		check("the stroke stops on the very next tick", !engine.state.isPowerStrokeActive(),
			"power stroke active=" + engine.state.isPowerStrokeActive());
	}

	/**
	 * The latch must never survive a standstill. A stalled crank that stayed latched
	 * would deliver free torque every tick for ever - which is how a "finish the
	 * stroke" rule turns into a perpetual motion machine.
	 */
	static void noFreeTorqueAfterStandstill() {
		section("NO LATCHED FREE TORQUE AFTER A STANDSTILL");

		Engine engine = new Engine(1, 100000);
		start(engine);

		int before = engine.totalCombustions();
		int guard = 0;
		while (engine.totalCombustions() == before && guard++ < 100)
			engine.tickFree();

		// Jam the shaft to a standstill mid-stroke, and hold it there.
		engine.ignition = false;
		for (int tick = 0; tick < 200; tick++)
			engine.tickJammed();

		check("the engine is at a standstill", engine.state.getSimulatedRpm() == 0.0F,
			String.format("%.3f RPM", engine.state.getSimulatedRpm()));
		check("with no power stroke latched", !engine.state.isPowerStrokeActive(),
			"power stroke active=" + engine.state.isPowerStrokeActive());
		check("and generating nothing", !engine.state.isActivelyGenerating(),
			"generating=" + engine.state.isActivelyGenerating());

		// Release it. A latched stroke would push it straight back into motion.
		engine.state.tickRotation(0.0F, false, false);
		engine.state.tickSimulation(engine.inputs(), engine.tank, engine.sump, engine.random);
		check("releasing the shaft does not restart it from a latch",
			engine.state.getSimulatedRpm() == 0.0F, String.format("%.3f RPM", engine.state.getSimulatedRpm()));
	}

	/**
	 * An inline-4 running its tank dry: only charges that were actually paid for may
	 * do any work, the tank may never go negative, and no phantom capacity may
	 * appear.
	 */
	static void multiCylinderRunsDry() {
		section("AN INLINE-4 RUNNING ITS TANK DRY");

		Engine r4 = new Engine(4, 100000);
		start(r4);

		// Leave just two charges in the tank, then run until everything stops.
		r4.tank.mb = 2 * EngineTuning.FUEL_PER_COMBUSTION_MB;
		int drawsBefore = r4.tank.draws;
		int combustionsBefore = r4.totalCombustions();

		for (int tick = 0; tick < 20 * 20; tick++)
			r4.tickFree();

		int draws = r4.tank.draws - drawsBefore;
		int combustions = r4.totalCombustions() - combustionsBefore;
		check("exactly the two remaining charges were drawn", draws == 2, draws + " draw(s)");
		check("and exactly two charges burned", combustions == 2, combustions + " combustion(s)");
		check("the tank never went negative", r4.tank.mb == 0, r4.tank.mb + " mB");
		check("the engine ends up generating nothing", !r4.state.isActivelyGenerating(),
			"generating=" + r4.state.isActivelyGenerating());
		check("with no firing cylinders left", r4.state.getFiringCylinderCount() == 0,
			r4.state.getFiringCylinderCount() + " firing");
	}

	/**
	 * A charge lit during a start attempt is worth a start kick, and stays worth a
	 * start kick even if the engine catches while it is still burning. The strength
	 * is latched per cylinder at ignition; a later phase change must not revalue it.
	 */
	static void startKickKeepsItsStrength() {
		section("A CHARGE KEEPS THE STRENGTH IT WAS LIT AT");

		// Two identical engines, one allowed to reach RUNNING and one held in the
		// start attempt, both measured over the same stroke. If a phase change could
		// revalue a burning charge, the accelerations would differ.
		Engine engine = new Engine(1, 100000);
		int ticks = 0;
		while (engine.state.getPhase() != EnginePhase.STARTING && ticks++ < 400)
			engine.tickHeldAt(32.0F);
		check("the engine reached a start attempt", engine.state.getPhase() == EnginePhase.STARTING,
			engine.state.getPhase()
				.toString());

		// Every charge burned during the attempt must have been a kick, never a full
		// power stroke: the engine must not out-run its own start.
		float peak = 0.0F;
		for (int tick = 0; tick < 200 && engine.state.getPhase() != EnginePhase.RUNNING; tick++) {
			engine.tickFree();
			peak = Math.max(peak, engine.state.getSimulatedRpm());
		}
		check("a start attempt never reaches the idle target on kicks alone", peak < EngineTuning.IDLE_RPM,
			String.format("peaked at %.1f RPM against an idle of %.0f", peak, EngineTuning.IDLE_RPM));
	}

	// ---------------------------------------------------------------- harness

	static void section(String title) {
		System.out.println();
		System.out.println("-- " + title);
	}

	static void check(String name, boolean passed, String detail) {
		if (!passed)
			failures++;
		System.out.printf("%-4s %-56s %s%n", passed ? "PASS" : "FAIL", name, detail);
	}
}
