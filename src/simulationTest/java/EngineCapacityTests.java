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
			return EngineTuning.STRESS_CAPACITY_PER_RPM * state.getPublishedCapacityFactor()
				* Math.abs(state.getPublishedRpm());
		}

		/**
		 * The multiplier Create caches per source. This is the thing that went stale.
		 *
		 * <p>Since wear exists it is the <i>effective</i> cylinder count - the firing
		 * cylinders, each weighted by its own compression - rather than a plain count.
		 * Every engine in this file is built from pristine parts, so the two are equal
		 * here by construction, and {@link #capacityBasis()} below asserts exactly
		 * that rather than assuming it.
		 */
		float capacityFactor() {
			return state.isActivelyGenerating() ? state.getPublishedCapacityFactor() : 0.0F;
		}

		/**
		 * How many cylinders are firing, which on a pristine engine is also what
		 * Create's multiplier comes to.
		 */
		int capacityBasis() {
			return state.isActivelyGenerating() ? state.getFiringCylinderCount() : 0;
		}

		/** The mask as a player would read it: {@code 1011} for a dead third cylinder. */
		String maskBits() {
			String bits = Integer.toBinaryString(state.getActiveCylinderMask());
			return "0".repeat(Math.max(0, cylinders - bits.length())) + bits;
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
		activeCylinderMaskIsTheCapacityBasis();
		activeCylinderMaskOnlyMovesOnRealEvents();

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
		// The engines in this file are built from new parts, so the multiplier Create
		// is handed is exactly the count. Asserted rather than assumed, because it is
		// the assumption every figure below rests on.
		check("and on pristine parts the multiplier is exactly that count",
			near(r4.capacityFactor(), r4.capacityBasis(), 1.0E-4),
			String.format("%.4f against %d", r4.capacityFactor(), r4.capacityBasis()));
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

	/**
	 * The mask is not a second opinion about the capacity: it <b>is</b> the capacity
	 * basis, and every other reading is a view of it.
	 *
	 * <p>What this pins down is the property the goggle overlay and Create's cached
	 * multiplier now share by construction: {@code getFiringCylinderCount()} is
	 * {@code Integer.bitCount(getActiveCylinderMask())} and nothing else, so there is
	 * no arrangement of state in which the two can differ.
	 */
	static void activeCylinderMaskIsTheCapacityBasis() {
		section("ONE CAPACITY BASIS: THE ACTIVE CYLINDER MASK");

		Engine r4 = new Engine(4, 1000000);
		start(r4);
		check("a healthy inline-4 reads 1111", r4.state.getActiveCylinderMask() == 0b1111, r4.maskBits());
		check("the firing count is exactly its bit count",
			r4.state.getFiringCylinderCount() == Integer.bitCount(r4.state.getActiveCylinderMask()),
			r4.state.getFiringCylinderCount() + " == bitCount(" + r4.maskBits() + ")");
		check("and every one of its cylinders reports itself active",
			r4.state.isCylinderActive(0) && r4.state.isCylinderActive(1) && r4.state.isCylinderActive(2)
				&& r4.state.isCylinderActive(3),
			r4.maskBits());

		// Pull the plug out of cylinder 3 - bit 2, the third bore along the shaft.
		r4.sparkPlugMask = 0b1011;
		int allowance = EngineTuning.generationCombustionAllowanceTicks(r4.state.getSimulatedRpm());
		for (int tick = 0; tick <= allowance + 2; tick++)
			r4.tickFree();

		check("pulling cylinder 3's plug reads 1011", r4.state.getActiveCylinderMask() == 0b1011, r4.maskBits());
		check("and it is cylinder 3 specifically that went dark", !r4.state.isCylinderActive(2)
			&& r4.state.isCylinderActive(0) && r4.state.isCylinderActive(1) && r4.state.isCylinderActive(3),
			r4.maskBits());
		check("capacity fell to three cylinders' worth", r4.capacityBasis() == 3,
			r4.capacityBasis() + " firing, mask " + r4.maskBits());

		// An engine that has run out of fuel loses every cylinder, not merely its
		// generation flag - and it does so only once the last paid-for stroke is done.
		Engine starved = new Engine(4, 1000000);
		start(starved);
		check("a fuelled inline-4 reads 1111 before starvation", starved.state.getActiveCylinderMask() == 0b1111,
			starved.maskBits());
		// The tank runs dry while it is running, which is the case that matters: the
		// charges already bought go on pushing, and the mask may only empty once the
		// last of them has finished its stroke and aged out.
		starved.tank.mb = 0;
		for (int tick = 0; tick < 20 * 60 && starved.state.getActiveCylinderMask() != 0; tick++)
			starved.tickFree();
		check("an inline-4 that has run dry reads 0000", starved.state.getActiveCylinderMask() == 0,
			starved.maskBits());
		check("and contributes nothing at all", starved.capacitySu() == 0.0,
			String.format("%.0f su", starved.capacitySu()));

		// The one an external source must never be able to fake.
		Engine motored = new Engine(4, 0);
		for (int tick = 0; tick < 200; tick++)
			motored.tickHeldAt(EngineTuning.FULL_THROTTLE_RPM);
		check("a dry inline-4 spun at 192 RPM still reads 0000", motored.state.getActiveCylinderMask() == 0,
			motored.maskBits() + " at " + Math.round(motored.state.getMechanicalRpm()) + " RPM");
	}

	/**
	 * <b>The property that keeps the mask cheap to synchronise.</b>
	 *
	 * <p>The block entity only puts the mask on the wire when it changes, which is
	 * only worth doing if a healthy engine's mask is genuinely stable - if it
	 * flickered between firing opportunities it would be a per-combustion packet
	 * wearing a different name, and it would undo exactly the saving
	 * {@code EngineCombustionEventsPayload} was written for.
	 *
	 * <p>It also pins the direction of the repair: refitting a plug is not what
	 * makes a cylinder count again. Burning a charge in it is.
	 */
	static void activeCylinderMaskOnlyMovesOnRealEvents() {
		section("THE MASK IS STABLE STATE, NOT AN EVENT CHANNEL");

		Engine r4 = new Engine(4, 1000000);
		start(r4);

		int changes = countMaskChanges(r4, 400);
		check("a healthy inline-4 does not change its mask for 400 ticks", changes == 0,
			changes + " change(s) over 20 seconds at " + Math.round(r4.state.getSimulatedRpm()) + " RPM");

		// Down a cylinder, and then steady again on three.
		r4.sparkPlugMask = 0b1011;
		int allowance = EngineTuning.generationCombustionAllowanceTicks(r4.state.getSimulatedRpm());
		for (int tick = 0; tick <= allowance + 2; tick++)
			r4.tickFree();
		check("losing a plug moved it once, to 1011", r4.state.getActiveCylinderMask() == 0b1011, r4.maskBits());

		changes = countMaskChanges(r4, 400);
		check("and 1011 is then just as stable as 1111 was", changes == 0,
			changes + " change(s) over 20 seconds on three cylinders");

		// Refit the plug. The cylinder is capable again, but it has not burned
		// anything yet, and capacity may not be paid for capability.
		r4.sparkPlugMask = 0b1111;
		check("refitting the plug does not restore the bit by itself",
			r4.state.getActiveCylinderMask() == 0b1011, r4.maskBits() + " on the tick the plug went back in");

		int combustionsBefore = r4.state.getCombustionEventId(2);
		int ticksUntilMaskReturned = -1;
		int ticksUntilCylinderFired = -1;
		for (int tick = 1; tick <= 200; tick++) {
			r4.tickFree();
			if (ticksUntilCylinderFired < 0 && r4.state.getCombustionEventId(2) != combustionsBefore)
				ticksUntilCylinderFired = tick;
			if (ticksUntilMaskReturned < 0 && r4.state.getActiveCylinderMask() == 0b1111)
				ticksUntilMaskReturned = tick;
		}

		check("cylinder 3 eventually burned a charge again", ticksUntilCylinderFired > 0,
			"first combustion after " + ticksUntilCylinderFired + " tick(s)");
		check("the mask returned to 1111", r4.state.getActiveCylinderMask() == 0b1111, r4.maskBits());
		check("and not before that cylinder actually fired",
			ticksUntilMaskReturned > 0 && ticksUntilMaskReturned >= ticksUntilCylinderFired,
			"fired on tick " + ticksUntilCylinderFired + ", mask returned on tick " + ticksUntilMaskReturned);
	}

	/** How many times the mask changes over the next {@code ticks} ticks of free running. */
	static int countMaskChanges(Engine engine, int ticks) {
		int previous = engine.state.getActiveCylinderMask();
		int changes = 0;
		for (int tick = 0; tick < ticks; tick++) {
			engine.tickFree();
			int now = engine.state.getActiveCylinderMask();
			if (now != previous) {
				changes++;
				previous = now;
			}
		}
		return changes;
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
