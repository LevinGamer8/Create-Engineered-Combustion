import dev.engineeredcombustion.content.engine.*;

/**
 * Drives the real {@code EngineState} through the Stress Capacity invariants, with
 * a deliberate focus on <b>capacity changing while the published speed does not</b>.
 *
 * <p>A pure simulation test: no Minecraft, no NeoForge, no Create.
 *
 * <h2>What this is guarding, and why a mock of Create is not needed</h2>
 * Create caches one capacity figure per source in {@code KineticNetwork#sources}
 * and multiplies it by the source's generated speed on demand
 * ({@code KineticNetwork#getActualCapacityOf} is
 * {@code sources.get(be) * |be.getGeneratedSpeed()|}). It only refreshes that cache
 * when something tells it to - and the only thing that did was the engine
 * republishing its <i>speed</i>.
 *
 * <p>So the bug was never in the arithmetic; it was that the two events are not the
 * same event. What this file therefore tests is the engine-side signal:
 * <b>the number Create would be told</b> - firing cylinder count and the generation
 * flag - <b>and whether it changes at moments when the published RPM does not</b>.
 * If those move while the published speed is pinned, the block entity must issue a
 * capacity refresh, and {@code EngineFlywheelBlockEntity#onEngineCapacityChanged}
 * is what carries it.
 *
 * <p>Exits non-zero on any failure.
 */
public class EngineCapacityTests {

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

	/**
	 * One engine, plus the exact two pieces of Create arithmetic that decide what a
	 * source contributes. Nothing else about Create is reproduced, because nothing
	 * else about Create is involved.
	 */
	static class Engine {
		final EngineState state = new EngineState();
		final Tank tank;
		final Sump sump;
		final java.util.Random random = new java.util.Random(1234L);
		int cylinders;
		/** Bit i set when cylinder i has a Spark Plug. */
		int sparkPlugMask;
		boolean ignition = true;
		boolean structureValid = true;
		float throttle = 0.0F;

		Engine(int cylinders, int fuel) {
			this.cylinders = cylinders;
			this.sparkPlugMask = (1 << cylinders) - 1;
			this.tank = new Tank(fuel);
			this.sump = new Sump(EngineTuning.OIL_CAPACITY_MB);
		}

		EngineInputs inputs() {
			return new EngineInputs(structureValid, ignition, cylinders, sparkPlugMask, throttle, 0.0F,
				EngineTuning.MAX_RPM);
		}

		void tickFree() {
			state.tickRotation(0.0F, false, false);
			state.tickSimulation(inputs(), tank, sump, random);
		}

		/** One tick with another Create source holding the shaft at a fixed speed. */
		void tickHeldAt(float rpm) {
			state.tickRotation(rpm, true, true);
			state.tickSimulation(inputs(), tank, sump, random);
		}

		/**
		 * What Create's network would compute for this engine, in SU.
		 *
		 * <p>{@code registeredCapacityPerRpm * |generatedSpeed|}, where the registered
		 * figure is Create's base capacity scaled by the number of cylinders genuinely
		 * firing - which is exactly what
		 * {@code EngineFlywheelBlockEntity#calculateAddedStressCapacity} returns.
		 */
		double capacitySu() {
			if (!state.isActivelyGenerating())
				return 0.0;
			return EngineTuning.STRESS_CAPACITY_PER_RPM * state.getFiringCylinderCount()
				* Math.abs(state.getPublishedRpm());
		}

		/** The multiplier Create caches per source. This is the thing that went stale. */
		int capacityBasis() {
			return state.isActivelyGenerating() ? state.getFiringCylinderCount() : 0;
		}
	}

	/** Cranks a fuelled engine until it catches, then lets it settle at idle. */
	static void start(Engine engine) {
		for (int tick = 0; tick < 20 * 30 && engine.state.getPhase() != EnginePhase.RUNNING; tick++)
			engine.tickHeldAt(32.0F);
		for (int tick = 0; tick < 400; tick++)
			engine.tickFree();
	}

	// ---------------------------------------------------------------- the tests

	public static void main(String[] args) {
		capacityFollowsFiringCylinders();
		capacityChangesWhileSpeedIsPinned();
		dryMotoredEngineHasNoCapacity();
		reloadDoesNotResurrectCapacity();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	/** R4 -&gt; R3 when a plug is pulled, and back to R4 once it fires again. */
	static void capacityFollowsFiringCylinders() {
		section("CAPACITY FOLLOWS THE CYLINDERS THAT ACTUALLY FIRE");

		Engine r4 = new Engine(4, 100000);
		start(r4);
		check("an inline-4 counts four firing cylinders", r4.capacityBasis() == 4,
			r4.capacityBasis() + " firing");
		double fourCylinderSu = r4.capacitySu();
		check("and its capacity is four times a single cylinder's",
			near(fourCylinderSu,
				EngineTuning.STRESS_CAPACITY_PER_RPM * 4 * r4.state.getPublishedRpm(), 1.0),
			String.format("%.0f su", fourCylinderSu));

		// Pull the Spark Plug out of cylinder 3.
		r4.sparkPlugMask = 0b0111;
		// Its last charge has to age out of the firing window before it stops counting;
		// that is the same allowance a single-cylinder engine has always had.
		int allowance = EngineTuning.generationCombustionAllowanceTicks(r4.state.getSimulatedRpm());
		for (int tick = 0; tick <= allowance + 2; tick++)
			r4.tickFree();

		check("pulling one plug drops it to three firing cylinders", r4.capacityBasis() == 3,
			r4.capacityBasis() + " firing");
		check("and takes exactly a quarter of the capacity away",
			near(r4.capacitySu(), EngineTuning.STRESS_CAPACITY_PER_RPM * 3 * r4.state.getPublishedRpm(), 1.0),
			String.format("%.0f su against %.0f su on four", r4.capacitySu(), fourCylinderSu));

		// Two plugs out.
		r4.sparkPlugMask = 0b0011;
		for (int tick = 0; tick <= allowance + 2; tick++)
			r4.tickFree();
		check("pulling a second plug drops it to two", r4.capacityBasis() == 2, r4.capacityBasis() + " firing");

		// Put them both back. Capacity may only return once the cylinders have
		// genuinely burned a charge again - never merely because a plug is present.
		r4.sparkPlugMask = 0b1111;
		check("refitting the plugs does not restore capacity by itself", r4.capacityBasis() == 2,
			r4.capacityBasis() + " firing on the tick the plugs went back in");
		for (int tick = 0; tick < 60; tick++)
			r4.tickFree();
		check("but it returns once those cylinders fire again", r4.capacityBasis() == 4,
			r4.capacityBasis() + " firing");
	}

	/**
	 * <b>THE REGRESSION.</b> An engine held at a constant speed by another source on
	 * the network loses a cylinder. Its published RPM does not move - so nothing
	 * republished its speed - but its capacity basis must change anyway.
	 */
	static void capacityChangesWhileSpeedIsPinned() {
		section("CAPACITY CHANGES EVEN WHEN THE PUBLISHED SPEED DOES NOT");

		Engine r4 = new Engine(4, 100000);
		start(r4);

		// Another source now holds the whole network at a steady speed. The engine is
		// still burning fuel in all four cylinders.
		float held = EngineTuning.IDLE_RPM;
		for (int tick = 0; tick < 100; tick++)
			r4.tickHeldAt(held);

		float publishedBefore = r4.state.getPublishedRpm();
		int basisBefore = r4.capacityBasis();
		check("it is generating on four cylinders while externally held", basisBefore == 4,
			basisBefore + " firing at a published " + publishedBefore + " RPM");

		// Pull a plug, keeping the external source at exactly the same speed.
		r4.sparkPlugMask = 0b0111;
		int allowance = EngineTuning.generationCombustionAllowanceTicks(r4.state.getSimulatedRpm());
		for (int tick = 0; tick <= allowance + 2; tick++)
			r4.tickHeldAt(held);

		float publishedAfter = r4.state.getPublishedRpm();
		int basisAfter = r4.capacityBasis();

		check("the published speed did not move", publishedBefore == publishedAfter,
			String.format("%.1f -> %.1f RPM", publishedBefore, publishedAfter));
		check("but the capacity basis did - 4 -> 3", basisAfter == 3,
			basisBefore + " -> " + basisAfter + " firing cylinders");
		// This is the assertion that would have failed before the fix: the two figures
		// Create needs are no longer welded to a single event.
		check("so a speed-only refresh would have missed it", publishedBefore == publishedAfter && basisAfter != basisBefore,
			"speed unchanged, basis changed");
	}

	/** A dry engine motored at any speed contributes nothing, however many cylinders. */
	static void dryMotoredEngineHasNoCapacity() {
		section("A MOTORED DRY ENGINE HAS NO CAPACITY");

		Engine empty = new Engine(4, 0);
		for (int tick = 0; tick < 200; tick++)
			empty.tickHeldAt(EngineTuning.FULL_THROTTLE_RPM);
		check("an empty inline-4 spun at 192 RPM generates nothing", !empty.state.isActivelyGenerating(),
			"generating=" + empty.state.isActivelyGenerating());
		check("its capacity basis is zero", empty.capacityBasis() == 0, empty.capacityBasis() + " firing");
		check("and so is its capacity", empty.capacitySu() == 0.0, String.format("%.0f su", empty.capacitySu()));

		// The same engine, unlit rather than unfuelled.
		Engine unlit = new Engine(4, 100000);
		unlit.ignition = false;
		for (int tick = 0; tick < 200; tick++)
			unlit.tickHeldAt(EngineTuning.FULL_THROTTLE_RPM);
		check("an unlit inline-4 spun at 192 RPM also generates nothing", unlit.capacitySu() == 0.0,
			String.format("%.0f su", unlit.capacitySu()));
	}

	/**
	 * A save must not bring back a capacity the engine can no longer justify. The
	 * engine's own restore path refuses to reconstruct generation upwards, and the
	 * first simulated tick then re-derives the cylinder count from the world.
	 */
	static void reloadDoesNotResurrectCapacity() {
		section("A RELOAD DOES NOT RESURRECT AN OLD CYLINDER COUNT");

		Engine before = new Engine(4, 100000);
		start(before);
		check("the engine was generating on four cylinders before the save", before.capacityBasis() == 4,
			before.capacityBasis() + " firing");

		// Save, then reload into a world where two of its cylinders lost their plugs
		// while the chunk was away.
		float savedRpm = before.state.getSimulatedRpm();
		float savedAngle = before.state.getCrankAngleDegrees();
		int[] savedAges = before.state.copyOfTicksSinceCombustion();

		Engine after = new Engine(4, 100000);
		after.sparkPlugMask = 0b0011;
		after.state.setSimulatedRpm(savedRpm);
		after.state.setCrankAngleDegrees(savedAngle);
		after.state.setPhase(EnginePhase.RUNNING);
		after.state.setTicksSinceCombustion(savedAges);
		after.state.setLayout(4, 0b0011);
		after.state.restoreAfterLoad(true);

		// The first reconciled tick re-derives everything from the world.
		int allowance = EngineTuning.generationCombustionAllowanceTicks(savedRpm);
		for (int tick = 0; tick <= allowance + 2; tick++)
			after.tickFree();

		check("after the reload only the two plugged cylinders count", after.capacityBasis() == 2,
			after.capacityBasis() + " firing");
		check("so no capacity survived that the engine cannot justify",
			after.capacitySu() < before.capacitySu(),
			String.format("%.0f su against %.0f su before", after.capacitySu(), before.capacitySu()));

		// And an engine that came back with nothing to burn claims nothing at all.
		Engine dead = new Engine(4, 0);
		dead.state.setSimulatedRpm(savedRpm);
		dead.state.setPhase(EnginePhase.RUNNING);
		dead.state.setTicksSinceCombustion(savedAges);
		dead.state.setLayout(4, 0b1111);
		dead.state.restoreAfterLoad(true);
		dead.tickFree();
		check("a reloaded engine with an empty tank claims no capacity", dead.capacitySu() == 0.0,
			String.format("%.0f su", dead.capacitySu()));
	}

	// ---------------------------------------------------------------- harness

	static boolean near(double value, double target, double tolerance) {
		return Math.abs(value - target) <= tolerance;
	}

	static void section(String title) {
		System.out.println();
		System.out.println("-- " + title);
	}

	static void check(String name, boolean passed, String detail) {
		if (!passed)
			failures++;
		System.out.printf("%-4s %-58s %s%n", passed ? "PASS" : "FAIL", name, detail);
	}
}
