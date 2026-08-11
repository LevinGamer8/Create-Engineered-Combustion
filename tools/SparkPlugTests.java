import dev.engineeredcombustion.content.engine.*;

/**
 * Drives the real {@code EngineState} through the Spark Plug ignition tests.
 *
 * <p>This is not a Minecraft test and needs no Minecraft. {@code EngineState}
 * and everything it touches are deliberately free of Minecraft, NeoForge and
 * Create types, and this file is the payoff for that discipline: the ignition
 * rules can be compiled and <i>executed</i> with nothing but a JDK.
 *
 * <pre>
 *   javac -d /tmp/ec-sim $(ls src/main/java/dev/engineeredcombustion/content/engine/*.java \
 *                           | grep -v EngineComponents | grep -v CombustionAudio)
 *   javac -cp /tmp/ec-sim -d /tmp/ec-sim tools/SparkPlugTests.java
 *   java  -cp /tmp/ec-sim SparkPlugTests
 * </pre>
 *
 * <p>{@code EngineComponents} and {@code CombustionAudio} are excluded because
 * they are the two classes in that package that do touch Minecraft - one resolves
 * block entities out of a level, the other plays sounds into one. Everything the
 * ignition logic actually decides is in the classes that remain.
 *
 * <p>Exits non-zero on any failure.
 */
public class SparkPlugTests {

	static int failures = 0;

	/** A carburetor with a chosen amount of fuel in it. */
	static class Tank implements FuelSupply {
		int mb;
		Tank(int mb) { this.mb = mb; }
		public boolean hasFuel() { return mb >= EngineTuning.FUEL_PER_COMBUSTION_MB; }
		public boolean consume(int amount) {
			if (mb < amount) return false;
			mb -= amount; return true;
		}
	}

	/** A full oil sump, so lubrication never confounds a result. */
	static final OilSupply OIL = new OilSupply() {
		public LubricationState lubrication() { return LubricationState.NORMAL; }
		public boolean consume(int mb) { return true; }
	};

	record Run(int sparks, int combustions, EnginePhase phase, float crankAngle, float simulatedRpm,
		double revolutions) {}

	/**
	 * Cranks the engine by hand for `ticks` ticks and reports what happened.
	 * `crankRpm` is what an external source is turning the crankshaft at - a hand
	 * crank, a water wheel, anything.
	 *
	 * <p>The network runs at whichever of the two is faster, exactly as Create's
	 * source handoff does, and the engine counts as externally driven only while
	 * the crank is genuinely out-turning what the engine generates. With both at
	 * zero the shaft is not driven at all and the engine freewheels on its own
	 * momentum, which is what a released hand crank leaves behind.
	 */
	static Run crank(EngineState engine, boolean sparkPlug, boolean ignition, FuelSupply fuel,
		float crankRpm, int ticks) {
		int sparks0 = engine.getSparkEventId(0);
		int combustions0 = engine.getCombustionEventId(0);
		java.util.Random random = new java.util.Random(1234);
		double degrees = 0.0;
		for (int i = 0; i < ticks; i++) {
			float generated = engine.getPublishedRpm();
			boolean crankWins = Math.abs(crankRpm) > Math.abs(generated);
			float shaftRpm = crankWins ? crankRpm : generated;
			engine.tickRotation(shaftRpm, shaftRpm != 0.0F, crankWins);
			degrees += EngineTuning.degreesPerTick(engine.getMechanicalRpm());
			engine.tickSimulation(new EngineInputs(true, ignition, sparkPlug,
				1.0F, 0.0F, EngineTuning.MAX_RPM), fuel, OIL, random);
		}
		return new Run(engine.getSparkEventId(0) - sparks0,
			engine.getCombustionEventId(0) - combustions0, engine.getPhase(),
			engine.getCrankAngleDegrees(), engine.getSimulatedRpm(), degrees / 360.0);
	}

	static void check(String name, boolean ok, String detail) {
		System.out.printf("%s %-58s %s%n", ok ? "PASS" : "FAIL", name, detail);
		if (!ok) failures++;
	}

	public static void main(String[] args) {
		float crankRpm = 48.0F;   // twice START_RPM: a firm hand crank
		int ticks = 400;

		// TEST 1 - no spark plug, ignition on, plenty of fuel.
		{
			EngineState e = new EngineState();
			Tank tank = new Tank(1000);
			Run r = crank(e, false, true, tank, crankRpm, ticks);
			check("TEST 1  no plug: crank turns", r.crankAngle() != 0.0F || crankRpm == 0,
				"crank angle " + r.crankAngle());
			check("TEST 1  no plug: NO spark", r.sparks() == 0, "sparks=" + r.sparks());
			check("TEST 1  no plug: NO combustion", r.combustions() == 0,
				"combustions=" + r.combustions());
			check("TEST 1  no plug: never starts", r.phase() != EnginePhase.RUNNING,
				"phase=" + r.phase());
			check("TEST 1  no plug: no fuel drawn", tank.mb == 1000, "fuel left " + tank.mb + " mB");
		}

		// TEST 2 - plug fitted, ignition on, tank empty.
		{
			EngineState e = new EngineState();
			Run r = crank(e, true, true, new Tank(0), crankRpm, ticks);
			check("TEST 2  plug, no fuel: sparks happen", r.sparks() > 0, "sparks=" + r.sparks());
			check("TEST 2  plug, no fuel: NO combustion", r.combustions() == 0,
				"combustions=" + r.combustions());
			check("TEST 2  plug, no fuel: never starts", r.phase() != EnginePhase.RUNNING,
				"phase=" + r.phase());
		}

		// TEST 3 - plug, fuel, ignition: the engine must actually start.
		{
			EngineState e = new EngineState();
			Tank tank = new Tank(1000);
			Run r = crank(e, true, true, tank, crankRpm, ticks);
			check("TEST 3  plug+fuel: sparks happen", r.sparks() > 0, "sparks=" + r.sparks());
			check("TEST 3  plug+fuel: combustion happens", r.combustions() > 0,
				"combustions=" + r.combustions());
			check("TEST 3  plug+fuel: reaches RUNNING", r.phase() == EnginePhase.RUNNING,
				"phase=" + r.phase() + ", " + r.simulatedRpm() + " rpm");
			check("TEST 3  plug+fuel: fuel consumed", tank.mb < 1000, "fuel left " + tank.mb + " mB");
		}

		// Ignition off with a plug fitted: still no spark. The plug is a second
		// gate, not a replacement for the first.
		{
			EngineState e = new EngineState();
			Run r = crank(e, true, false, new Tank(1000), crankRpm, ticks);
			check("EXTRA   plug, ignition off: no spark", r.sparks() == 0, "sparks=" + r.sparks());
		}

		// Pulling the plug out of a running engine must stop combustion and let it
		// coast down - not leave it running, and not stop the crank dead.
		{
			EngineState e = new EngineState();
			Tank tank = new Tank(4000);
			crank(e, true, true, tank, crankRpm, ticks);
			boolean wasRunning = e.getPhase() == EnginePhase.RUNNING;
			Run after = crank(e, false, true, tank, 0.0F, 60);
			check("EXTRA   plug pulled while running: stops combusting",
				wasRunning && after.combustions() == 0,
				"was RUNNING=" + wasRunning + ", combustions after=" + after.combustions());
			check("EXTRA   plug pulled while running: leaves RUNNING",
				after.phase() != EnginePhase.RUNNING, "phase=" + after.phase());
		}

		// Cranked backwards: no spark at any speed, plug or not.
		{
			EngineState e = new EngineState();
			Run r = crank(e, true, true, new Tank(1000), -crankRpm, ticks);
			check("EXTRA   cranked backwards: no spark", r.sparks() == 0, "sparks=" + r.sparks());
		}

		// The ordering the brief insists on: fuel must never be what decides
		// whether the plug sparks.
		//
		// Measured per REVOLUTION, not per tick. The coil fires once per
		// revolution, so a fuelled engine that has caught and accelerated
		// naturally sparks more often per second than a dry one being cranked by
		// hand - that is the engine turning faster, not fuel enabling the spark.
		// One spark per revolution in both cases is the invariant that says the
		// two gates are still in the right order.
		{
			Run dry = crank(new EngineState(), true, true, new Tank(0), crankRpm, 400);
			Run wet = crank(new EngineState(), true, true, new Tank(4000), crankRpm, 400);
			double dryPer = dry.sparks() / dry.revolutions();
			double wetPer = wet.sparks() / wet.revolutions();
			check("EXTRA   fuel does not gate the spark (sparks per revolution)",
				Math.abs(dryPer - 1.0) < 0.05 && Math.abs(wetPer - 1.0) < 0.05,
				String.format("dry %.3f/rev over %.1f rev, fuelled %.3f/rev over %.1f rev",
					dryPer, dry.revolutions(), wetPer, wet.revolutions()));
		}

		System.out.println(failures == 0 ? "\nall checks passed" : "\n" + failures + " FAILURES");
		System.exit(failures == 0 ? 0 : 1);
	}
}
