import dev.engineeredcombustion.content.engine.*;

/**
 * Measures the engine's coast-down and re-checks that shortening it did not move
 * anything else.
 *
 * <p>A pure simulation test: {@code EngineState} and everything it touches are
 * free of Minecraft, NeoForge and Create types, so this runs on a bare JDK.
 *
 * <h2>What this is guarding</h2>
 * The obvious way to shorten a spin-down is to lower
 * {@link EngineTuning#FLYWHEEL_INERTIA}, and it is the wrong way: the same number
 * decides how much a single cylinder's combustion ripples the crank speed within a
 * revolution, how long the engine takes to spin up, and how much smoother an
 * inline-4 is than an inline-1. So the inertia is untouched and the extra loss is
 * added only to an engine that is <i>free-running without firing</i> - see
 * {@link EngineTuning#coastDragTorqueAt}.
 *
 * <p>That split is what this file tests. The coast-down times are asserted against
 * the milestone's target ranges, and the equilibria either side of them are
 * asserted to be exactly where they always were.
 *
 * <p>Exits non-zero on any failure.
 */
public class EngineCoastDownTests {

	static int failures = 0;

	// ---------------------------------------------------------------- fixtures

	static class Tank implements FuelSupply {
		int mb;

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

	/** One engine, with the knobs the tests need to turn. */
	static class Engine {
		final EngineState state = new EngineState();
		final Tank tank;
		final Sump sump;
		final java.util.Random random;
		int cylinders = 1;
		boolean ignition = true;
		float throttle = 0.0F;

		Engine(int fuel, int oil, long seed) {
			tank = new Tank(fuel);
			sump = new Sump(oil);
			random = new java.util.Random(seed);
		}

		int sparkPlugMask() {
			return (1 << cylinders) - 1;
		}

		EngineInputs inputs() {
			return new EngineInputs(true, ignition, cylinders, sparkPlugMask(), throttle, 0.0F,
				EngineTuning.MAX_RPM);
		}

		/** One tick with nothing on the kinetic network driving the shaft. */
		void tickFree() {
			state.tickRotation(0.0F, false, false);
			state.tickSimulation(inputs(), tank, sump, random);
		}

		/** One tick with Create holding the shaft at the given speed. */
		void tickDriven(float rpm) {
			state.tickRotation(rpm, true, true);
			state.tickSimulation(inputs(), tank, sump, random);
		}
	}

	/**
	 * Runs a fuelled engine up from a hand crank until it settles, then frees it.
	 *
	 * <p>Cranks until the engine <i>catches</i> rather than for a fixed number of
	 * ticks. A four-stroke needs twice the crank travel a two-stroke did - each
	 * cylinder gets one firing opportunity per 720 degrees rather than per 360 - so a
	 * fixed budget tuned to the old model simply never started the engine, and every
	 * measurement below it silently became a measurement of a stopped one.
	 */
	static Engine runningEngine(int cylinders, float throttle, long seed) {
		Engine engine = new Engine(1000000, EngineTuning.OIL_CAPACITY_MB, seed);
		engine.cylinders = cylinders;
		engine.throttle = throttle;
		crankUntilRunning(engine);
		for (int tick = 0; tick < 1200; tick++)
			engine.tickFree();
		return engine;
	}

	/** Turns the engine over at hand-crank speed until it catches. */
	static void crankUntilRunning(Engine engine) {
		for (int tick = 0; tick < 2000 && engine.state.getPhase() != EnginePhase.RUNNING; tick++)
			engine.tickDriven(32.0F);
	}

	// ------------------------------------------------------------ measurement

	/**
	 * Ticks a free-running engine with no fuel and no ignition until it is below
	 * {@link EngineTuning#REST_RPM}, and reports how long that took in seconds.
	 *
	 * <p>The engine is set up so nothing but drag is acting: no charge can be lit,
	 * so the only torques are compression - which integrates to zero over a
	 * revolution and so cannot change the answer - and friction plus coast drag.
	 */
	static float secondsToRest(int cylinders, float fromRpm, LubricationState lubrication) {
		Engine engine = new Engine(0, lubrication == LubricationState.NORMAL ? EngineTuning.OIL_CAPACITY_MB
			: lubrication == LubricationState.LOW ? 50 : 0, 1L);
		engine.cylinders = cylinders;
		engine.ignition = false;
		// Spun up by something else, then let go on the very next tick, so the whole
		// measured interval is free rotation.
		engine.tickDriven(fromRpm);
		engine.state.setSimulatedRpm(fromRpm);

		int ticks = 0;
		while (Math.abs(engine.state.getSimulatedRpm()) >= EngineTuning.REST_RPM && ticks < 20 * 60) {
			engine.tickFree();
			ticks++;
		}
		return ticks / 20.0F;
	}

	// ---------------------------------------------------------------- the tests

	public static void main(String[] args) {
		coastDownTimes();
		coastDownIsMonotonic();
		equilibriaUnmoved();
		startingStillWorks();
		noCoastDragOnADrivenEngine();
		multiCylinderStillSmoother();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	/**
	 * THE MILESTONE TARGETS. Idle and hand-crank speeds have to reach rest inside
	 * the ranges the milestone specified; full throttle is allowed to take longer,
	 * but not disproportionately so.
	 */
	static void coastDownTimes() {
		section("COAST-DOWN TIMES (normal lubrication, no external load)");

		float fromIdle = secondsToRest(1, EngineTuning.IDLE_RPM, LubricationState.NORMAL);
		check("64 RPM reaches rest in 2.0 - 3.5 s", fromIdle >= 2.0F && fromIdle <= 3.5F,
			String.format("%.2f s", fromIdle));

		float fromCrank = secondsToRest(1, 32.0F, LubricationState.NORMAL);
		check("32 RPM hand-crank momentum in 1.0 - 2.5 s", fromCrank >= 1.0F && fromCrank <= 2.5F,
			String.format("%.2f s", fromCrank));

		float fromFull = secondsToRest(1, EngineTuning.FULL_THROTTLE_RPM, LubricationState.NORMAL);
		check("192 RPM takes longer than idle", fromFull > fromIdle, String.format("%.2f s", fromFull));
		// "Not disproportionate" made concrete: three times the speed must not mean
		// anything like three times the old ten-second spin-down.
		check("192 RPM still stops inside 8 s", fromFull <= 8.0F, String.format("%.2f s", fromFull));

		float fromIdleR4 = secondsToRest(4, EngineTuning.IDLE_RPM, LubricationState.NORMAL);
		check("an inline-4 coasts down in the same range", fromIdleR4 >= 2.0F && fromIdleR4 <= 3.5F,
			String.format("%.2f s", fromIdleR4));

		// Lubrication still matters, and in the direction it should: a dry engine has
		// more friction to fight, so it stops sooner.
		float dry = secondsToRest(1, EngineTuning.IDLE_RPM, LubricationState.DRY);
		check("a dry engine coasts down faster than a lubricated one", dry < fromIdle,
			String.format("%.2f s dry against %.2f s", dry, fromIdle));
	}

	/**
	 * A shorter coast must still be a coast. Friction may never drag the crank
	 * through zero into reverse, and the engine must not oscillate around rest -
	 * both of which would also feed Create's flicker score.
	 */
	static void coastDownIsMonotonic() {
		section("COAST-DOWN SHAPE");

		Engine engine = new Engine(0, EngineTuning.OIL_CAPACITY_MB, 2L);
		engine.ignition = false;
		engine.tickDriven(EngineTuning.FULL_THROTTLE_RPM);
		engine.state.setSimulatedRpm(EngineTuning.FULL_THROTTLE_RPM);

		float previous = engine.state.getSimulatedRpm();
		boolean monotonic = true;
		boolean everNegative = false;
		for (int tick = 0; tick < 400; tick++) {
			engine.tickFree();
			float now = engine.state.getSimulatedRpm();
			if (now > previous + 0.001F)
				monotonic = false;
			if (now < 0.0F)
				everNegative = true;
			previous = now;
		}

		check("speed never increases while coasting", monotonic, String.format("ended at %.2f RPM", previous));
		check("friction never drags the crank into reverse", !everNegative, String.format("%.2f RPM", previous));
		check("it lands exactly on zero", engine.state.getSimulatedRpm() == 0.0F,
			String.format("%.4f RPM", engine.state.getSimulatedRpm()));
		check("and the phase says stopped", engine.state.getPhase() == EnginePhase.STOPPED,
			engine.state.getPhase()
				.toString());

		// The same from a backwards spin: the sign of the momentum is what decides
		// which way friction pushes, so a reversed engine must land on zero from
		// below rather than being flung forwards.
		Engine reversed = new Engine(0, EngineTuning.OIL_CAPACITY_MB, 3L);
		reversed.ignition = false;
		reversed.tickDriven(-EngineTuning.IDLE_RPM);
		reversed.state.setSimulatedRpm(-EngineTuning.IDLE_RPM);
		boolean everPositive = false;
		for (int tick = 0; tick < 400; tick++) {
			reversed.tickFree();
			if (reversed.state.getSimulatedRpm() > 0.0F)
				everPositive = true;
		}
		check("a backwards engine coasts up to zero, never through it", !everPositive,
			String.format("%.2f RPM", reversed.state.getSimulatedRpm()));
	}

	/**
	 * The whole point of putting the loss on coast rather than on the inertia: a
	 * running engine must settle exactly where it always did.
	 */
	static void equilibriaUnmoved() {
		section("RUNNING EQUILIBRIA ARE UNCHANGED");

		// MEAN speed, not an instantaneous sample. A four-stroke single genuinely
		// swings about 15 RPM peak to peak at idle - one bang per two revolutions is
		// what makes a single sound like a single - so which tick the sample lands on
		// moves the reading by more than the tolerance being checked. Averaging over
		// several cycles asks the question this test is actually about: does the
		// engine settle where it always settled.
		float idle = meanSpeed(runningEngine(1, 0.0F, 4L));
		check("idle is still about 64 RPM", near(idle, EngineTuning.IDLE_RPM, 4.0F),
			String.format("%.1f RPM", idle));

		float half = meanSpeed(runningEngine(1, 0.5F, 5L));
		check("half throttle is still about 128 RPM", near(half, 128.0F, 5.0F), String.format("%.1f RPM", half));

		float full = meanSpeed(runningEngine(1, 1.0F, 6L));
		check("full throttle is still about 192 RPM", near(full, EngineTuning.FULL_THROTTLE_RPM, 6.0F),
			String.format("%.1f RPM", full));

		float idleR4 = meanSpeed(runningEngine(4, 0.0F, 7L));
		check("an inline-4 idles at the same speed as a single", near(idleR4, EngineTuning.IDLE_RPM, 4.0F),
			String.format("%.1f RPM", idleR4));

		float fullR4 = meanSpeed(runningEngine(4, 1.0F, 8L));
		check("and holds the same full-throttle target", near(fullR4, EngineTuning.FULL_THROTTLE_RPM, 6.0F),
			String.format("%.1f RPM", fullR4));
	}

	/** Mean free-running speed over 400 ticks: several cycles at any throttle. */
	static float meanSpeed(Engine engine) {
		float total = 0.0F;
		for (int tick = 0; tick < 400; tick++) {
			engine.tickFree();
			total += engine.state.getSimulatedRpm();
		}
		return total / 400.0F;
	}

	/**
	 * A start attempt spends most of its ticks between firing kicks. If coast drag
	 * applied in those gaps it would smother the attempt, so a hand crank must still
	 * be able to start the engine.
	 */
	static void startingStillWorks() {
		section("A HAND CRANK STILL STARTS THE ENGINE");

		Engine engine = new Engine(1000000, EngineTuning.OIL_CAPACITY_MB, 9L);
		int ticks = 0;
		// Thirty seconds of budget rather than fifteen. A four-stroke single gets one
		// firing opportunity per 720 degrees, so it takes twice the crank travel to
		// collect the same number of successful kicks - which is what a four-stroke
		// does, and what this test now has to allow for rather than fail on.
		while (engine.state.getPhase() != EnginePhase.RUNNING && ticks < 20 * 30) {
			engine.tickDriven(32.0F);
			ticks++;
		}
		check("it catches on a 32 RPM hand crank", engine.state.getPhase() == EnginePhase.RUNNING,
			engine.state.getPhase() + " after " + ticks + " ticks");

		for (int tick = 0; tick < 600; tick++)
			engine.tickFree();
		check("and then runs on its own at idle", near(engine.state.getSimulatedRpm(), EngineTuning.IDLE_RPM, 5.0F),
			String.format("%.1f RPM", engine.state.getSimulatedRpm()));
	}

	/**
	 * Coast drag is for an engine nothing is driving. An engine Create is holding at
	 * a speed takes that speed on, and charging it drag as well would both corrupt
	 * that number and bill the same losses twice - the network is already charged
	 * for motoring a dead engine through
	 * {@link EngineTuning#PASSIVE_DRAG_STRESS_PER_RPM}.
	 */
	static void noCoastDragOnADrivenEngine() {
		section("NO DOUBLE-CHARGING A DRIVEN ENGINE");

		Engine dead = new Engine(0, EngineTuning.OIL_CAPACITY_MB, 10L);
		dead.ignition = false;
		for (int tick = 0; tick < 60; tick++)
			dead.tickDriven(200.0F);
		check("a motored dead engine reads the speed it is being turned at",
			near(dead.state.getSimulatedRpm(), 200.0F, 1.5F), String.format("%.2f RPM", dead.state.getSimulatedRpm()));
		check("and still generates nothing", !dead.state.isActivelyGenerating(),
			"firing cylinders " + dead.state.getFiringCylinderCount());

		Engine slow = new Engine(0, EngineTuning.OIL_CAPACITY_MB, 11L);
		slow.ignition = false;
		for (int tick = 0; tick < 60; tick++)
			slow.tickDriven(32.0F);
		check("the same at hand-crank speed", near(slow.state.getSimulatedRpm(), 32.0F, 1.5F),
			String.format("%.2f RPM", slow.state.getSimulatedRpm()));
	}

	/**
	 * Multi-cylinder smoothness comes from adding up compression curves 90 degrees
	 * apart, and nothing about the coast-down change should touch it.
	 */
	static void multiCylinderStillSmoother() {
		section("AN INLINE-4 IS STILL SMOOTHER THAN AN INLINE-1");

		float rippleR1 = rippleOf(1);
		float rippleR2 = rippleOf(2);
		float rippleR3 = rippleOf(3);
		float rippleR4 = rippleOf(4);
		check("an inline-4 ripples less than an inline-1", rippleR4 < rippleR1,
			String.format("%.3f RPM against %.3f", rippleR4, rippleR1));
		// THE FROZEN SMOOTHNESS LADDER, and the reason it is checked as a chain rather
		// than as one comparison: Milestone 15B asks for the character to fall out of
		// firing TIMING, with no per-layout smoothness multiplier anywhere. Monotone
		// across all four is the observable form of that claim - and the uneven twin
		// sitting correctly between the single and the triple is the part of it that a
		// wrong sign or a wrong crank would break first.
		check("the smoothness ladder is monotone R1 > R2 > R3 > R4",
			rippleR1 > rippleR2 && rippleR2 > rippleR3 && rippleR3 > rippleR4,
			String.format("R1 %.2f > R2 %.2f > R3 %.2f > R4 %.2f", rippleR1, rippleR2, rippleR3, rippleR4));
	}

	/** Peak-to-peak speed variation of a settled engine over 200 ticks. */
	static float rippleOf(int cylinders) {
		Engine engine = runningEngine(cylinders, 0.0F, 12L + cylinders);
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		for (int tick = 0; tick < 200; tick++) {
			engine.tickFree();
			float rpm = engine.state.getSimulatedRpm();
			min = Math.min(min, rpm);
			max = Math.max(max, rpm);
		}
		return max - min;
	}

	// ---------------------------------------------------------------- harness

	static boolean near(float value, float target, float tolerance) {
		return Math.abs(value - target) <= tolerance;
	}

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
