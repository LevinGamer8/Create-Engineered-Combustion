import dev.engineeredcombustion.content.engine.*;

/**
 * Drives the real {@code EngineState} as an inline-1, -2, -3 and -4 and asserts
 * the properties that make a multi-cylinder engine <b>one engine</b> rather than
 * several sharing a shaft.
 *
 * <p>Needs no Minecraft:
 *
 * <pre>
 *   javac -d /tmp/ec-sim $(ls src/main/java/dev/engineeredcombustion/content/engine/*.java \
 *                           | grep -v EngineComponents | grep -v CombustionAudio)
 *   javac -cp /tmp/ec-sim -d /tmp/ec-sim tools/MultiCylinderTests.java
 *   java  -cp /tmp/ec-sim MultiCylinderTests
 * </pre>
 *
 * <p>The invariant that matters most is the last one: <b>an inline-4 that is not
 * burning fuel contributes zero Stress Capacity, however fast something else
 * spins its four pistons.</b> Four cylinders must never be four engines' worth of
 * free power.
 *
 * <p>Exits non-zero on any failure.
 */
public class MultiCylinderTests {

	static int failures = 0;

	// ---------------------------------------------------------------- fixtures

	static class Tank implements FuelSupply {
		int mb;
		int drawn;

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
			drawn += amount;
			return true;
		}
	}

	static final OilSupply OIL = new OilSupply() {
		public LubricationState lubrication() {
			return LubricationState.NORMAL;
		}

		public boolean consume(int mb) {
			return true;
		}
	};

	/**
	 * One engine of a chosen layout, on its own kinetic network.
	 *
	 * <p>The network model is the one from {@code EngineReloadTests}: it runs at
	 * its fastest source's speed, that speed is held until a source publishes, and
	 * an engine that is not the fastest reports a kinetic source of its own.
	 */
	static class Engine {
		final EngineState state = new EngineState();
		final Tank tank;
		final java.util.Random random;
		final int cylinders;

		int sparkPlugMask;
		boolean ignition = true;
		boolean structure = true;
		float throttle;
		float loadFactor;
		float externalRpm;
		float networkSpeed;

		Engine(int cylinders, int fuelMb, long seed) {
			this.cylinders = cylinders;
			this.tank = new Tank(fuelMb);
			this.random = new java.util.Random(seed);
			this.sparkPlugMask = (1 << cylinders) - 1;
		}

		void tick() {
			boolean drivenByOthers = networkSpeed != 0.0F
				&& Math.abs(state.getPublishedRpm()) < Math.abs(networkSpeed);
			state.tickRotation(networkSpeed, networkSpeed != 0.0F, drivenByOthers);
			state.tickSimulation(new EngineInputs(structure, ignition, cylinders, sparkPlugMask, throttle,
				loadFactor, EngineTuning.MAX_RPM), tank, OIL, random);
			republish();
		}

		void republish() {
			networkSpeed = Math.abs(state.getPublishedRpm()) > Math.abs(externalRpm) ? state.getPublishedRpm()
				: externalRpm;
		}

		void run(int ticks) {
			for (int i = 0; i < ticks; i++)
				tick();
		}

		/** Exactly {@code KineticNetwork#getActualCapacityOf}, with this mod's per-cylinder scaling. */
		double capacitySu() {
			return state.isActivelyGenerating()
				? EngineTuning.STRESS_CAPACITY_PER_RPM * Math.abs(state.getPublishedRpm())
					* state.getFiringCylinderCount()
				: 0.0;
		}
	}

	/** Cranks the engine until it catches, the way a player does. */
	static Engine started(int cylinders, float throttle) {
		Engine engine = new Engine(cylinders, 4000000, 900 + cylinders);
		engine.throttle = throttle;
		engine.externalRpm = 48.0F;
		engine.republish();
		engine.run(400);
		engine.externalRpm = 0.0F;
		engine.republish();
		engine.run(600);
		return engine;
	}

	static void check(String name, boolean ok, String detail) {
		System.out.printf("%s %-56s %s%n", ok ? "PASS" : "FAIL", name, detail);
		if (!ok)
			failures++;
	}

	static boolean near(double value, double expected, double tolerance) {
		return Math.abs(value - expected) <= tolerance;
	}

	/** Peak-to-peak swing of the engine's instantaneous speed over a window. */
	static float ripple(Engine engine, int ticks) {
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		for (int i = 0; i < ticks; i++) {
			engine.tick();
			min = Math.min(min, engine.state.getSimulatedRpm());
			max = Math.max(max, engine.state.getSimulatedRpm());
		}
		return max - min;
	}

	// -------------------------------------------------------------------- tests

	public static void main(String[] args) {
		phaseOffsets();
		oneEngineNotSeveral();
		everyLayoutHoldsItsTarget();
		smoothness();
		fuelScaling();
		capacityScaling();
		deadSparkPlug();
		freePowerRegression();
		starting();
		System.out.println(failures == 0 ? "\nall checks passed" : "\n" + failures + " FAILURES");
		System.exit(failures == 0 ? 0 : 1);
	}

	/** TEST M1-M4 - the crank phases, and that they come from one master angle. */
	static void phaseOffsets() {
		System.out.println("PART 1 - crank phases\n");

		// THE FOUR-STROKE CRANKS, and the inline-4 is the visible change: cylinders 1
		// and 4 share a throw and so do 2 and 3, the flat-plane crank every inline-4
		// four-stroke has. They no longer say when a cylinder FIRES - that is the
		// ignition schedule, 720 degrees wide, checked in ProductionFourStrokeTests -
		// and separating the two is the whole of what Milestone 15B corrects.
		float[][] expected = { { 0 }, { 0, 180 }, { 0, 120, 240 }, { 0, 180, 180, 0 } };
		for (int count = 1; count <= EngineTuning.MAX_CYLINDERS; count++) {
			StringBuilder actual = new StringBuilder();
			boolean ok = true;
			for (int i = 0; i < count; i++) {
				float offset = EngineTuning.cylinderPhaseOffsetDegrees(i, count);
				ok &= near(offset, expected[count - 1][i], 0.01);
				actual.append(i == 0 ? "" : ", ")
					.append(String.format("%.0f", offset));
			}
			check("inline-" + count + "  phase offsets", ok, actual.toString());
		}

		// Every cylinder's angle is the master angle plus its offset, so they cannot
		// drift: turning the crank moves all of them by exactly the same amount.
		Engine engine = new Engine(4, 1000, 1);
		engine.state.setLayout(4, 0b1111);
		engine.state.setCrankAngleDegrees(30.0F);
		float[] throws4 = { 0.0F, 180.0F, 180.0F, 0.0F };
		boolean derived = true;
		for (int i = 0; i < 4; i++)
			derived &= near(engine.state.localCrankAngleDegrees(i), (30.0F + throws4[i]) % 360.0F, 0.01);
		engine.state.advanceCrankAngle(64.0F);
		float moved = engine.state.getCrankAngleDegrees();
		for (int i = 0; i < 4; i++)
			derived &= near(engine.state.localCrankAngleDegrees(i), (moved + throws4[i]) % 360.0F, 0.01);
		check("inline-4  every angle derives from ONE master angle", derived,
			String.format("master %.2f -> %.2f, %.2f, %.2f, %.2f", moved, engine.state.localCrankAngleDegrees(0),
				engine.state.localCrankAngleDegrees(1), engine.state.localCrankAngleDegrees(2),
				engine.state.localCrankAngleDegrees(3)));
		System.out.println();
	}

	/** The core architectural claim: one momentum, one throttle, one published speed. */
	static void oneEngineNotSeveral() {
		System.out.println("PART 2 - one engine, several cylinders\n");
		Engine engine = started(4, 0.0F);
		engine.run(600);

		// Four cylinders firing at four different phases, one speed.
		check("inline-4  runs on one shared crankshaft speed",
			engine.state.getPhase() == EnginePhase.RUNNING
				&& near(engine.state.getMechanicalRpm(), engine.state.getPublishedRpm(), 4.0F),
			String.format("%s, mechanical %.1f, published %.0f", engine.state.getPhase(),
				engine.state.getMechanicalRpm(), engine.state.getPublishedRpm()));

		// All four are genuinely firing, at four distinct phases.
		check("inline-4  all four cylinders fire", engine.state.getFiringCylinderCount() == 4,
			engine.state.getFiringCylinderCount() + " of 4 firing");
		System.out.println();
	}

	/**
	 * The throttle is a governor setpoint, and it has to mean the same thing for
	 * every layout or the readout stops making sense.
	 */
	static void everyLayoutHoldsItsTarget() {
		System.out.println("PART 3 - every layout holds its throttle target\n");
		for (int count = 1; count <= EngineTuning.MAX_CYLINDERS; count++)
			for (float throttle : new float[] { 0.0F, 1.0F }) {
				Engine engine = started(count, throttle);
				engine.run(1200);
				float target = engine.state.getTargetRpm();
				check(String.format("inline-%d at %3.0f%%  settles on target", count, throttle * 100.0F),
					near(engine.state.getPublishedRpm(), target, 4.0F),
					String.format("published %.0f, output %.1f, target %.0f", engine.state.getPublishedRpm(),
						engine.state.getOutputRpm(), target));
			}
		System.out.println();
	}

	/**
	 * Phase-shifted compression and combustion should make more cylinders turn
	 * more smoothly, with nothing in the code saying so.
	 */
	static void smoothness() {
		System.out.println("PART 4 - more cylinders run smoother\n");
		float[] swing = new float[EngineTuning.MAX_CYLINDERS + 1];
		StringBuilder trace = new StringBuilder();
		for (int count = 1; count <= EngineTuning.MAX_CYLINDERS; count++) {
			Engine engine = started(count, 0.0F);
			engine.run(600);
			swing[count] = ripple(engine, 400);
			trace.append(count == 1 ? "" : " -> ")
				.append(String.format("R%d %.1f", count, swing[count]));
		}

		// The single is lumpiest, and every extra cylinder is smoother than the last.
		// The uneven twin is deliberately only a little smoother than the single -
		// 15.6 % speed ripple against 23.8 % at idle - which is the measured cost of
		// choosing it for character, so it is checked as a monotone chain rather than
		// against a fixed fraction of the single.
		boolean monotone = true;
		for (int count = 2; count <= EngineTuning.MAX_CYLINDERS; count++)
			monotone &= swing[count] < swing[count - 1];
		check("a single is lumpier than a twin, and so on up the run", monotone,
			trace + " RPM peak-to-peak");

		// Not asserted as a strictly falling sequence, and deliberately: with one
		// power event per 360 degrees, an inline-2 and an inline-4 have their
		// compression terms cancel exactly while an inline-3's cancel only in part,
		// so R3 sits fractionally above R2. That is the arithmetic of this
		// simplified cycle, not a defect - and it is one of the things a real
		// 720-degree four-stroke model will change.
		check("an inline-4 is the smoothest of them", swing[4] <= swing[2] + 0.01F && swing[4] <= swing[3] + 0.01F,
			String.format("R4 %.2f, R3 %.2f, R2 %.2f", swing[4], swing[3], swing[2]));
		System.out.println();
	}

	/** TEST M5 - four cylinders fire four times per revolution and cost four times as much. */
	static void fuelScaling() {
		System.out.println("PART 5 - fuel scales with cylinder count\n");
		int[] drawn = new int[EngineTuning.MAX_CYLINDERS + 1];
		double[] revolutions = new double[EngineTuning.MAX_CYLINDERS + 1];
		for (int count = 1; count <= EngineTuning.MAX_CYLINDERS; count++) {
			Engine engine = started(count, 0.0F);
			engine.run(600);
			engine.tank.drawn = 0;
			double degrees = 0.0;
			for (int i = 0; i < 2400; i++) {
				engine.tick();
				degrees += EngineTuning.degreesPerTick(engine.state.getMechanicalRpm());
			}
			drawn[count] = engine.tank.drawn;
			revolutions[count] = degrees / 360.0;
		}
		for (int count = 1; count <= EngineTuning.MAX_CYLINDERS; count++) {
			double perRevolution = drawn[count] / revolutions[count];
			check(String.format("inline-%d  burns %d charges per revolution", count, count),
				near(perRevolution, count, 0.15),
				String.format("%.2f charges/rev, %d mB over %.1f revolutions", perRevolution, drawn[count],
					revolutions[count]));
		}
		check("inline-4 burns about four times an inline-1", near((double) drawn[4] / drawn[1], 4.0, 0.5),
			String.format("%d mB against %d mB at the same speed", drawn[4], drawn[1]));
		System.out.println();
	}

	/** TEST M6 - capacity comes from firing cylinders, so it scales with them. */
	static void capacityScaling() {
		System.out.println("PART 6 - Stress Capacity scales with FIRING cylinders\n");
		double single = 0.0;
		for (int count = 1; count <= EngineTuning.MAX_CYLINDERS; count++) {
			Engine engine = started(count, 0.0F);
			engine.run(600);
			double capacity = engine.capacitySu();
			if (count == 1)
				single = capacity;
			check(String.format("inline-%d  supplies about %dx a single", count, count),
				near(capacity, single * count, single * 0.15),
				String.format("%.0f su against %.0f su for an inline-1", capacity, single));
		}
		System.out.println();
	}

	/**
	 * An inline-4 with one plug missing is a real machine: it runs on three
	 * cylinders, down on power and below its target. That is diagnosis, not
	 * breakage.
	 */
	static void deadSparkPlug() {
		System.out.println("PART 7 - an inline-4 with a dead Spark Plug\n");
		Engine healthy = started(4, 0.0F);
		healthy.run(600);
		double healthyCapacity = healthy.capacitySu();
		float healthySpeed = healthy.state.getPublishedRpm();

		Engine misfiring = started(4, 0.0F);
		misfiring.sparkPlugMask = 0b1011; // cylinder 2 has no plug
		misfiring.run(1200);

		check("it keeps running on three cylinders",
			misfiring.state.getPhase() == EnginePhase.RUNNING && misfiring.state.isActivelyGenerating(),
			misfiring.state.getPhase() + ", generating=" + misfiring.state.isActivelyGenerating());
		check("only three cylinders count as firing", misfiring.state.getFiringCylinderCount() == 3,
			misfiring.state.getFiringCylinderCount() + " of 4 firing");
		check("it runs below its target, down on power",
			misfiring.state.getPublishedRpm() < healthySpeed - 2.0F,
			String.format("%.0f RPM against %.0f healthy, target %.0f", misfiring.state.getPublishedRpm(),
				healthySpeed, misfiring.state.getTargetRpm()));
		check("and supplies about three quarters of the capacity",
			near(misfiring.capacitySu(), healthyCapacity * 0.75, healthyCapacity * 0.12),
			String.format("%.0f su against %.0f su healthy", misfiring.capacitySu(), healthyCapacity));
		System.out.println();
	}

	/**
	 * TEST M7 - the free-power exploit, at four times the scale. An inline-4 with
	 * no gasoline, spun by another Create source, moves all four pistons and
	 * supplies nothing.
	 */
	static void freePowerRegression() {
		System.out.println("PART 8 - a motored, dry inline-4\n");
		Engine dead = new Engine(4, 0, 77);
		dead.externalRpm = 160.0F;
		dead.republish();
		dead.run(400);

		check("all four cylinders are mechanically turning",
			Math.abs(dead.state.getMechanicalRpm()) > 100.0F && dead.state.getCylinderCount() == 4,
			String.format("%.0f RPM, %d cylinders", dead.state.getMechanicalRpm(), dead.state.getCylinderCount()));
		check("NO cylinder counts as firing", dead.state.getFiringCylinderCount() == 0,
			dead.state.getFiringCylinderCount() + " firing");
		check("generated speed is ZERO", dead.state.getPublishedRpm() == 0.0F,
			String.format("%.0f RPM generated", dead.state.getPublishedRpm()));
		check("Stress Capacity is ZERO - no four-engine exploit",
			!dead.state.isActivelyGenerating() && dead.capacitySu() == 0.0,
			String.format("generating=%s, %.0f su", dead.state.isActivelyGenerating(), dead.capacitySu()));

		// The same engine, fuelled, is allowed to take over - four cylinders of
		// genuine combustion, not four cylinders of being spun.
		dead.tank.mb = 400000;
		dead.run(600);
		check("fuelled, the very same engine does generate",
			dead.state.isActivelyGenerating() && dead.capacitySu() > 0.0,
			String.format("%s, %d firing, %.0f su", dead.state.getPhase(), dead.state.getFiringCylinderCount(),
				dead.capacitySu()));
		System.out.println();
	}

	/**
	 * More cylinders mean more firing opportunities, so a bigger engine should
	 * catch sooner - but never instantly, and never without being cranked.
	 */
	static void starting() {
		System.out.println("PART 9 - starting\n");
		// Averaged over several attempts, because the number of firing cycles an
		// attempt needs is rolled once per attempt: one sample would be measuring
		// the dice rather than the engine.
		int attempts = 12;
		double[] averageTicks = new double[EngineTuning.MAX_CYLINDERS + 1];
		for (int count = 1; count <= EngineTuning.MAX_CYLINDERS; count++) {
			int total = 0;
			int fastest = Integer.MAX_VALUE;
			boolean allCaught = true;
			for (int attempt = 0; attempt < attempts; attempt++) {
				// Seeds spread far apart on purpose: java.util.Random returns the same
				// first value for seeds a few apart, so closely-spaced seeds would
				// roll an identical start length every time and quietly test nothing.
				// The game seeds without an argument and has no such problem.
				Engine engine = new Engine(count, 400000, 4242L + attempt * 1000003L + count);
				engine.externalRpm = 48.0F;
				engine.republish();
				int ticks = 0;
				while (ticks < 900 && engine.state.getPhase() != EnginePhase.RUNNING) {
					engine.tick();
					ticks++;
				}
				allCaught &= engine.state.getPhase() == EnginePhase.RUNNING;
				total += ticks;
				fastest = Math.min(fastest, ticks);
			}
			averageTicks[count] = (double) total / attempts;
			check(String.format("inline-%d  catches when cranked, but not at once", count),
				allCaught && fastest > 10,
				String.format("%.0f ticks on average, quickest %d", averageTicks[count], fastest));
		}
		check("an inline-4 catches sooner than an inline-1", averageTicks[4] < averageTicks[1],
			String.format("%.0f ticks against %.0f", averageTicks[4], averageTicks[1]));
		check("but not trivially sooner - it still has to be cranked",
			averageTicks[4] > averageTicks[1] * 0.35,
			String.format("%.0f ticks against %.0f, i.e. %.0f%% of a single's", averageTicks[4], averageTicks[1],
				100.0 * averageTicks[4] / averageTicks[1]));

		// And no engine may start itself: unlit, unfuelled or simply untouched, it
		// stays stopped however long it is left alone.
		Engine untouched = new Engine(4, 400000, 11);
		untouched.run(400);
		check("an inline-4 never starts itself", untouched.state.getPhase() == EnginePhase.STOPPED
			&& untouched.capacitySu() == 0.0, untouched.state.getPhase() + ", " + untouched.capacitySu() + " su");
		System.out.println();
	}
}
