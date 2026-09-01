import dev.engineeredcombustion.prototype.fourstroke.*;
import dev.engineeredcombustion.content.engine.EngineTuning;

/**
 * Holds the frozen cycle-state representation to its contract: the counter and the
 * angle, the event identity built on them, and what survives a crank that is
 * rocked, reversed, driven absurdly fast or saved mid-stroke.
 *
 * <p>A pure test of prototype code, reachable from nothing in the mod.
 *
 * <p>Exits non-zero on any failure.
 */
public class FourStrokeStateTests {

	static int failures = 0;

	public static void main(String[] args) {
		unwrappedFloatPositionRotsWithUptime();
		countedPositionNeverRots();
		positionInvariants();
		crossingCycleIndexNamesTheRightCycle();
		eventIdentityIsUniquePerCycle();
		oscillationTorture();
		reverseThenForwardIsNotSuppressed();
		highRpmCrossing();
		saveReloadAtEveryBoundary();
		saveReloadWhileReversing();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	// ------------------------------------------------------ representation

	/**
	 * Why representation B - one unwrapped position that grows for ever - is not the
	 * answer, measured rather than asserted.
	 *
	 * <p>A float's resolution is proportional to its magnitude, so an ever-growing
	 * position loses the ability to represent its own increment. This is not a slow
	 * degradation into imprecision: past about 2^30 degrees the addition becomes a
	 * no-op and <b>the engine stops turning altogether</b>.
	 */
	static void unwrappedFloatPositionRotsWithUptime() {
		section("REPRESENTATION B IN FLOAT DIES OF UPTIME");

		float perTick = EngineTuning.degreesPerTick(EngineTuning.FULL_THROTTLE_RPM);
		float position = 0.0F;
		float advanceAfterOneHour = 0.0F;
		float advanceAfterOneMonth = 0.0F;
		long hourTicks = 20L * 3600L;
		long monthTicks = 20L * 86400L * 30L;

		for (long tick = 1; tick <= monthTicks; tick++) {
			float before = position;
			position += perTick;
			if (tick == hourTicks)
				advanceAfterOneHour = position - before;
			if (tick == monthTicks)
				advanceAfterOneMonth = position - before;
		}

		System.out.printf("     true advance %.3f deg/tick; after 1 h it is %.3f; after 30 d it is %.3f%n",
			perTick, advanceAfterOneHour, advanceAfterOneMonth);
		check("after an hour a float position already advances wrongly",
			Math.abs(advanceAfterOneHour - perTick) > 0.01F, advanceAfterOneHour + " vs " + perTick);
		check("after a month it does not advance at all - the engine freezes",
			advanceAfterOneMonth == 0.0F, advanceAfterOneMonth + " deg/tick");
	}

	/** The frozen representation has the opposite property: precision independent of uptime. */
	static void countedPositionNeverRots() {
		section("REPRESENTATION C IS IMMUNE TO UPTIME");

		float perTick = EngineTuning.degreesPerTick(EngineTuning.FULL_THROTTLE_RPM);
		CyclePosition fresh = new CyclePosition();
		fresh.advance(perTick);
		float freshAngle = fresh.angle();

		// A month of continuous full throttle, then the same single step again.
		CyclePosition aged = new CyclePosition();
		long monthTicks = 20L * 86400L * 30L;
		for (long tick = 0; tick < monthTicks; tick++)
			aged.advance(perTick);
		float angleBefore = aged.angle();
		long indexBefore = aged.cycleIndex();
		aged.advance(perTick);

		check("the angle still advances by exactly the same amount after a month",
			near(aged.angle() - angleBefore, freshAngle), (aged.angle() - angleBefore) + " vs " + freshAngle);
		check("and the cycle counter has been keeping count all along",
			indexBefore > 1_000_000L, indexBefore + " cycles");
		check("the angle is still in range", aged.angle() >= 0.0F && aged.angle() < 720.0F,
			aged.angle() + "");
	}

	/** The two fields, and the guarantees that make them one position. */
	static void positionInvariants() {
		section("POSITION INVARIANTS");

		CyclePosition position = new CyclePosition();
		boolean inRange = true;
		java.util.Random random = new java.util.Random(151L);
		for (int i = 0; i < 200000; i++) {
			position.advance((random.nextFloat() - 0.5F) * 4000.0F);
			if (!(position.angle() >= 0.0F && position.angle() < 720.0F))
				inRange = false;
		}
		check("the angle stays in [0, 720) under 200k wild steps", inRange, position.toString());

		// A forward wrap and a backward wrap must cancel exactly.
		CyclePosition rocked = new CyclePosition(0L, 700.0F);
		for (int i = 0; i < 500; i++) {
			rocked.advance(50.0F);
			rocked.advance(-50.0F);
		}
		check("rocking across the wrap accumulates no cycles", rocked.cycleIndex() == 0L,
			"index " + rocked.cycleIndex());
		check("and returns to the same angle", near(rocked.angle(), 700.0F), rocked.angle() + "");

		// Exact boundaries.
		check("720 lands on cycle 1, angle 0", exactly(720.0F, 1L, 0.0F), describe(720.0F));
		check("-1 lands on cycle -1, angle 719", exactly(-1.0F, -1L, 719.0F), describe(-1.0F));
		check("-720 lands on cycle -1, angle 0", exactly(-720.0F, -1L, 0.0F), describe(-720.0F));
		check("1441 lands on cycle 2, angle 1", exactly(1441.0F, 2L, 1.0F), describe(1441.0F));

		// The physical angle is derived, and is the same for both dead centres.
		CyclePosition compressionTdc = new CyclePosition(0L, 180.0F);
		CyclePosition exhaustTdc = new CyclePosition(0L, 540.0F);
		check("both top dead centres give the same physical angle",
			near(compressionTdc.physicalAngle(), exhaustTdc.physicalAngle()),
			compressionTdc.physicalAngle() + " and " + exhaustTdc.physicalAngle());
		check("but different strokes", compressionTdc.phase() != exhaustTdc.phase(),
			compressionTdc.phase() + " vs " + exhaustTdc.phase());
	}

	/**
	 * The event identity's foundation: a crossing that happens in the same step as a
	 * wrap must be attributed to the cycle it actually happened in.
	 */
	static void crossingCycleIndexNamesTheRightCycle() {
		section("A CROSSING IS NAMED BY THE CYCLE IT HAPPENED IN");

		// Ordinary: cross 180 without wrapping.
		CyclePosition ordinary = new CyclePosition(5L, 100.0F);
		ordinary.advance(200.0F);
		check("a plain crossing belongs to the current cycle",
			ordinary.crossedForward(180.0F) && ordinary.crossingCycleIndex(180.0F) == 5L,
			"cycle " + ordinary.crossingCycleIndex(180.0F));

		// Crossed 180 in cycle 5, then wrapped into 6 in the SAME step.
		CyclePosition wrappedAfter = new CyclePosition(5L, 170.0F);
		wrappedAfter.advance(600.0F);
		check("a crossing before a same-step wrap belongs to the OLD cycle",
			wrappedAfter.crossedForward(180.0F) && wrappedAfter.crossingCycleIndex(180.0F) == 5L,
			"index now " + wrappedAfter.cycleIndex() + ", crossing in " + wrappedAfter.crossingCycleIndex(180.0F));

		// Wrapped into 6 and THEN crossed 180 in the same step.
		CyclePosition wrappedBefore = new CyclePosition(5L, 600.0F);
		wrappedBefore.advance(300.0F);
		check("a crossing after a same-step wrap belongs to the NEW cycle",
			wrappedBefore.crossedForward(180.0F) && wrappedBefore.crossingCycleIndex(180.0F) == 6L,
			"index now " + wrappedBefore.cycleIndex() + ", crossing in "
				+ wrappedBefore.crossingCycleIndex(180.0F));
	}

	/**
	 * Every ignition the engine ever produces carries a distinct
	 * {@code (cylinder, cycle)} key. That is the property duplicate prevention rests
	 * on, and it is checked here over a long, violently shaken run.
	 */
	static void eventIdentityIsUniquePerCycle() {
		section("EVENT IDENTITY IS UNIQUE PER CYLINDER PER CYCLE");

		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		java.util.Random random = new java.util.Random(1842L);
		java.util.Set<Long> seen = new java.util.HashSet<>();
		boolean everRepeated = false;
		int events = 0;
		for (int i = 0; i < 120000; i++) {
			int mask = engine.step((random.nextFloat() - 0.45F) * 80.0F, true);
			for (int cylinder = 0; cylinder < engine.cylinderCount(); cylinder++) {
				if ((mask & (1 << cylinder)) == 0)
					continue;
				events++;
				// The key, packed into a primitive rather than allocated - the same
				// thing production would compare against a long field.
				long key = engine.cylinder(cylinder).lastFiredCycle() * 8L + cylinder;
				if (!seen.add(key))
					everRepeated = true;
			}
		}
		check("no (cylinder, cycle) key is ever issued twice", !everRepeated,
			events + " ignitions, " + seen.size() + " distinct keys");
		check("and the run actually produced plenty of them", events > 200, events + " ignitions");
	}

	// ------------------------------------------------------------- torture

	/** The milestone's named rocking sequences, played exactly. */
	static void oscillationTorture() {
		section("OSCILLATION TORTURE");

		// 179 / 181 / 179 / 181 ... straddling the ignition point by a degree.
		check("179-181-179-181 across ignition yields one bang, not thousands",
			rockAndCount(180.0F, new float[] { -1.0F, 1.0F }, 4000) == 0, "after the first");

		// 175 / 190 / 170 / 200 ... a wider, ragged straddle.
		check("175-190-170-200 ragged straddle yields nothing further",
			rockAndCount(180.0F, new float[] { 10.0F, -20.0F, 30.0F, -20.0F }, 4000) == 0, "after the first");

		// Straddling the ARMING point instead, which must also not manufacture bangs.
		check("rocking across the arming point yields nothing",
			rockAndCount(540.0F, new float[] { -1.0F, 1.0F }, 4000) == 0, "after the first");

		// Forward through ignition, reverse an entire revolution, forward again.
		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R1);
		engine.armAsIfRested();
		while (engine.totalIgnitions() == 0)
			engine.step(1.0F, true);
		int after = engine.totalIgnitions();
		for (int cycle = 0; cycle < 20; cycle++) {
			for (int i = 0; i < 360; i++)
				engine.step(-1.0F, true);
			for (int i = 0; i < 360; i++)
				engine.step(1.0F, true);
		}
		check("20 whole-revolution rocks after a bang add nothing", engine.totalIgnitions() == after,
			(engine.totalIgnitions() - after) + " extra ignitions in 14400 degrees of rocking");
	}

	/**
	 * Runs an R1 up to its first bang, then rocks it around {@code about} with the
	 * given repeating step pattern, and returns how many <i>further</i> ignitions
	 * that produced.
	 */
	static int rockAndCount(float about, float[] pattern, int steps) {
		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R1);
		engine.armAsIfRested();
		while (engine.totalIgnitions() == 0)
			engine.step(1.0F, true);
		// Walk to just past the angle we mean to rock around.
		while (engine.cycleAngle() < about || engine.cycleAngle() > about + 2.0F)
			engine.step(1.0F, true);
		int before = engine.totalIgnitions();
		for (int i = 0; i < steps; i++)
			engine.step(pattern[i % pattern.length], true);
		return engine.totalIgnitions() - before;
	}

	/**
	 * The other half of the invariant, and the one that is easy to lose: a crank
	 * wound back into an earlier cycle and then run forwards properly must be able
	 * to fire again. Duplicate prevention that also suppresses legitimate events is
	 * not a fix.
	 */
	static void reverseThenForwardIsNotSuppressed() {
		section("A LEGITIMATE LATER EVENT IS NEVER SUPPRESSED");

		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R1);
		engine.armAsIfRested();
		while (engine.totalIgnitions() == 0)
			engine.step(1.0F, true);
		int after = engine.totalIgnitions();

		// Wind back through the whole of the previous cycle.
		for (int i = 0; i < 1500; i++)
			engine.step(-1.0F, true);
		check("winding back 1500 degrees produces no ignition", engine.totalIgnitions() == after,
			engine.totalIgnitions() + " total");

		// Now turn it forwards properly and demand that it catches again.
		int degrees = 0;
		while (engine.totalIgnitions() == after && degrees < 4000) {
			engine.step(1.0F, true);
			degrees++;
		}
		check("and it fires again once turned forwards", engine.totalIgnitions() > after,
			"after " + degrees + " degrees of forward travel");
		check("having cost at least a full cycle of forward travel", degrees >= 720,
			degrees + " degrees");
	}

	// ----------------------------------------------------------- high speed

	/**
	 * A tick may cross one firing point, several cylinders' firing points, or - under
	 * an absurd external speed - more than a whole cycle.
	 *
	 * <p>The frozen rule is <b>fail closed</b>: at most one ignition per cylinder per
	 * tick, so an impossible speed loses events rather than manufacturing fuel and
	 * torque. Reaching it needs more than 2400 RPM, against the engine's own ceiling
	 * of 208 and Create's default of 256.
	 */
	static void highRpmCrossing() {
		section("HIGH-RPM CROSSING FAILS CLOSED");

		check("the engine's own ceiling is far below one cycle per tick",
			EngineTuning.degreesPerTick(EngineTuning.MAX_RPM) < 720.0F,
			EngineTuning.degreesPerTick(EngineTuning.MAX_RPM) + " deg/tick at MAX_RPM");
		check("so is Create's default network maximum of 256 RPM",
			EngineTuning.degreesPerTick(256.0F) < 720.0F,
			EngineTuning.degreesPerTick(256.0F) + " deg/tick");

		// Several cylinders crossing in one tick is normal and must ALL be processed.
		FourStrokeEngine wide = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		wide.armAsIfRested();
		for (int i = 0; i < 4; i++)
			wide.step(180.0F, true);
		int mask = wide.step(400.0F, true);
		check("a 400-degree step can ignite more than one cylinder at once",
			Integer.bitCount(mask) >= 2, "mask " + Integer.toBinaryString(mask));

		// Beyond a cycle per tick, events are lost rather than duplicated.
		for (float step : new float[] { 800.0F, 1500.0F, 5000.0F }) {
			FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R1);
			engine.armAsIfRested();
			int steps = 200;
			for (int i = 0; i < steps; i++)
				engine.step(step, true);
			float travelled = step * steps;
			int deserved = (int) (travelled / 720.0F);
			check(step + " deg/tick: at most one bang per tick", engine.totalIgnitions() <= steps,
				engine.totalIgnitions() + " ignitions in " + steps + " ticks");
			check(step + " deg/tick: never more than the travel earns",
				engine.totalIgnitions() <= deserved + 1,
				engine.totalIgnitions() + " ignitions, travel earns " + deserved);
			check(step + " deg/tick: events are LOST, not duplicated",
				engine.totalIgnitions() < deserved, engine.totalIgnitions() + " < " + deserved);
		}
	}

	// ------------------------------------------------------- save / reload

	/**
	 * Save and reload at every stroke boundary and either side of it, and demand the
	 * engine come back on the same stroke and behave identically afterwards.
	 */
	static void saveReloadAtEveryBoundary() {
		section("SAVE/RELOAD AT EVERY BOUNDARY");

		float[] boundaries = { 0.0F, 179.9F, 180.1F, 359.9F, 360.1F, 539.9F, 540.1F, 719.9F };
		for (float angle : boundaries)
			checkRoundTrip("at " + angle, angle, 0);

		// And after a great many cycles, so the counter is carrying a real number.
		checkRoundTrip("after 5000 cycles", 473.0F, 5000);
	}

	static void checkRoundTrip(String name, float angle, int extraCycles) {
		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		engine.armAsIfRested();
		for (int i = 0; i < extraCycles; i++)
			engine.step(720.0F, true);
		engine.step(angle, true);

		FourStrokeEngine reloaded = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		reloaded.restore(engine.save());

		boolean same = near(reloaded.cycleAngle(), engine.cycleAngle())
			&& reloaded.cycleIndex() == engine.cycleIndex()
			&& near(reloaded.physicalAngle(), engine.physicalAngle());
		for (int i = 0; i < engine.cylinderCount(); i++)
			same &= reloaded.cylinder(i).phase() == engine.cylinder(i).phase()
				&& reloaded.cylinder(i).isArmed() == engine.cylinder(i).isArmed()
				&& near(reloaded.cylinder(i).physicalAngle(), engine.cylinder(i).physicalAngle());
		check(name + ": comes back on the same stroke and position", same, engine.position().toString());

		// The real test: identical firing for four whole cycles afterwards.
		StringBuilder before = new StringBuilder();
		StringBuilder after = new StringBuilder();
		for (int i = 0; i < 2880; i++) {
			before.append(engine.step(1.0F, true)).append(',');
			after.append(reloaded.step(1.0F, true)).append(',');
		}
		check(name + ": fires identically for four cycles afterwards",
			before.toString().equals(after.toString()), "2880 steps compared");
	}

	/** Reverse rotation is representable, so it must survive a save too. */
	static void saveReloadWhileReversing() {
		section("SAVE/RELOAD WHILE REVERSING");

		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R3);
		engine.armAsIfRested();
		for (int i = 0; i < 900; i++)
			engine.step(1.0F, true);
		for (int i = 0; i < 400; i++)
			engine.step(-3.0F, true);

		check("the counter has gone backwards", engine.cycleIndex() < 1L, "cycle " + engine.cycleIndex());

		FourStrokeEngine reloaded = new FourStrokeEngine(FourStrokeFiringOrder.R3);
		reloaded.restore(engine.save());
		check("a reversed engine reloads onto the same cycle and angle",
			reloaded.cycleIndex() == engine.cycleIndex() && near(reloaded.cycleAngle(), engine.cycleAngle()),
			reloaded.position().toString());

		StringBuilder before = new StringBuilder();
		StringBuilder after = new StringBuilder();
		for (int i = 0; i < 2160; i++) {
			before.append(engine.step(2.0F, true)).append(',');
			after.append(reloaded.step(2.0F, true)).append(',');
		}
		check("and then fires identically once turned forwards again",
			before.toString().equals(after.toString()), "2160 steps compared");
	}

	// ---------------------------------------------------------------- harness

	static boolean exactly(float advanceBy, long expectedIndex, float expectedAngle) {
		CyclePosition position = new CyclePosition();
		position.advance(advanceBy);
		return position.cycleIndex() == expectedIndex && near(position.angle(), expectedAngle);
	}

	static String describe(float advanceBy) {
		CyclePosition position = new CyclePosition();
		position.advance(advanceBy);
		return position.toString();
	}

	static boolean near(float actual, float expected) {
		return Math.abs(actual - expected) < 1.0E-2F;
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
