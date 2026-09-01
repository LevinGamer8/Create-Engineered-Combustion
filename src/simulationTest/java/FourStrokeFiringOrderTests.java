import dev.engineeredcombustion.prototype.fourstroke.*;

/**
 * Holds the prototype firing schedules to their contract: one bang per cylinder per
 * cycle, the right order, the right spacing, geometry that stays independent of
 * firing, and a rocked crank that cannot be made to produce free power.
 *
 * <p>A pure test of prototype code, reachable from nothing in the mod.
 *
 * <p>Exits non-zero on any failure.
 */
public class FourStrokeFiringOrderTests {

	static int failures = 0;

	public static void main(String[] args) {
		everyConfigurationFiresOncePerCylinderPerCycle();
		r4FiresInTheChosenOrder();
		r4EventsAre180Apart();
		noCylinderFiresTwiceInOneCycle();
		pistonPhaseIsIndependentOfFiringPhase();
		reverseOscillationCannotDuplicateEvents();
		cyclePhaseSurvivesSaveAndReload();
		smoothnessRisesWithCylinderCount();
		activeCylindersDoNotFlickerBetweenFirings();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	/** D and E. R1 fires once per 720; R4 fires four times; and so does everything between. */
	static void everyConfigurationFiresOncePerCylinderPerCycle() {
		section("D/E  ONE IGNITION PER CYLINDER PER 720 DEGREES");

		for (FourStrokeFiringOrder configuration : FourStrokeFiringOrder.values()) {
			FourStrokeEngine engine = new FourStrokeEngine(configuration);
			// One cycle of settling first: every cylinder starts cold and disarmed, so
			// its first bang costs an extra induction. That is the design working, and
			// it is measured separately below rather than smoothed over here.
			for (int i = 0; i < 720; i++)
				engine.step(1.0F, true);
			int[] before = new int[engine.cylinderCount()];
			for (int i = 0; i < before.length; i++)
				before[i] = engine.ignitionCount(i);

			// Four whole cycles at a tick-sized step.
			for (int i = 0; i < 4 * 720; i++)
				engine.step(1.0F, true);

			boolean everyCylinderFiredFourTimes = true;
			int total = 0;
			StringBuilder counts = new StringBuilder();
			for (int i = 0; i < engine.cylinderCount(); i++) {
				int fired = engine.ignitionCount(i) - before[i];
				total += fired;
				counts.append(fired).append(' ');
				if (fired != 4)
					everyCylinderFiredFourTimes = false;
			}
			check(configuration + ": every cylinder fired exactly 4 times in 4 cycles",
				everyCylinderFiredFourTimes, "per cylinder: " + counts.toString().trim());
			check(configuration + ": " + configuration.cylinderCount() + " events per cycle",
				total == 4 * configuration.cylinderCount(), total + " in 4 cycles");
		}

		// Stated the way the milestone states it: half the event rate of the engine
		// this replaces, for every configuration, so the R1-R4 balance is untouched.
		FourStrokeEngine single = new FourStrokeEngine(FourStrokeFiringOrder.R1);
		for (int i = 0; i < 720; i++)
			single.step(1.0F, true);
		int settled = single.totalIgnitions();
		for (int i = 0; i < 720; i++)
			single.step(1.0F, true);
		check("R1 fires once per 720 degrees, i.e. 0.5 per revolution",
			single.totalIgnitions() - settled == 1,
			(single.totalIgnitions() - settled) + " in 720 degrees");
	}

	/** F. The inline-4 fires 1-3-4-2, and the order the simulation runs says so. */
	static void r4FiresInTheChosenOrder() {
		section("F  R4 FIRES 1-3-4-2");

		int[] declared = FourStrokeFiringOrder.R4.firingOrder();
		check("the declared order is 1-3-4-2", java.util.Arrays.equals(declared, new int[] { 1, 3, 4, 2 }),
			java.util.Arrays.toString(declared));

		// Observed: turn the engine and write down who fires, in the order they do.
		// Settled through one cycle first so this is a running engine's order rather
		// than the order four cold cylinders happen to inhale in.
		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		for (int i = 0; i < 720; i++)
			engine.step(1.0F, true);
		java.util.List<Integer> observed = new java.util.ArrayList<>();
		for (int i = 0; i < 720 && observed.size() < 4; i++) {
			int mask = engine.step(1.0F, true);
			for (int cylinder = 0; cylinder < 4; cylinder++)
				if ((mask & (1 << cylinder)) != 0)
					observed.add(cylinder + 1);
		}
		check("the engine actually fires in that order", observed.equals(java.util.List.of(1, 3, 4, 2)),
			observed.toString());

		check("R3 fires 1-2-3", java.util.Arrays.equals(FourStrokeFiringOrder.R3.firingOrder(),
			new int[] { 1, 2, 3 }), java.util.Arrays.toString(FourStrokeFiringOrder.R3.firingOrder()));
	}

	/** G. R4's four events are evenly spaced every 180 degrees. */
	static void r4EventsAre180Apart() {
		section("G  R4 EVENTS ARE 180 DEGREES APART");

		float[] intervals = FourStrokeFiringOrder.R4.ignitionIntervalsDegrees();
		boolean allEven = true;
		for (float interval : intervals)
			if (Math.abs(interval - 180.0F) > 1.0E-3F)
				allEven = false;
		check("declared intervals are all 180", allEven, java.util.Arrays.toString(intervals));

		// Observed, by recording the travel at each ignition on a settled engine.
		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		for (int i = 0; i < 720; i++)
			engine.step(1.0F, true);
		java.util.List<Float> firedAt = new java.util.ArrayList<>();
		float travelled = 0.0F;
		while (travelled < 720.0F) {
			int mask = engine.step(1.0F, true);
			travelled += 1.0F;
			if (mask != 0)
				firedAt.add(travelled);
		}
		boolean observedEven = firedAt.size() == 4;
		for (int i = 1; i < firedAt.size() && observedEven; i++)
			if (Math.abs(firedAt.get(i) - firedAt.get(i - 1) - 180.0F) > 1.5F)
				observedEven = false;
		check("observed ignitions are 180 apart", observedEven, firedAt.toString());

		check("R3 intervals are all 240",
			FourStrokeFiringOrder.R3.evenFire(),
			java.util.Arrays.toString(FourStrokeFiringOrder.R3.ignitionIntervalsDegrees()));
		check("R2_EVEN intervals are all 360", FourStrokeFiringOrder.R2_EVEN.evenFire(),
			java.util.Arrays.toString(FourStrokeFiringOrder.R2_EVEN.ignitionIntervalsDegrees()));
		check("R2_UNEVEN is correctly reported as uneven", !FourStrokeFiringOrder.R2_UNEVEN.evenFire(),
			java.util.Arrays.toString(FourStrokeFiringOrder.R2_UNEVEN.ignitionIntervalsDegrees()));
	}

	/** H. No cylinder may fire twice within one cycle, at any step size. */
	static void noCylinderFiresTwiceInOneCycle() {
		section("H  NO CYLINDER FIRES TWICE IN ONE CYCLE");

		boolean allSingle = true;
		String detail = "";
		for (FourStrokeFiringOrder configuration : FourStrokeFiringOrder.values())
			for (float step : new float[] { 0.25F, 3.0F, 17.0F, 62.0F, 119.0F }) {
				FourStrokeEngine engine = new FourStrokeEngine(configuration);
				int[] perCycle = new int[engine.cylinderCount()];
				float travelled = 0.0F;
				while (travelled < 720.0F) {
					int mask = engine.step(step, true);
					travelled += step;
					for (int i = 0; i < engine.cylinderCount(); i++)
						if ((mask & (1 << i)) != 0)
							perCycle[i]++;
				}
				for (int count : perCycle)
					if (count > 1) {
						allSingle = false;
						detail += configuration + "@" + step + " fired " + count + "x; ";
					}
			}
		check("no cylinder of any configuration fires twice in 720 degrees", allSingle,
			allSingle ? "5 configurations x 5 step sizes" : detail);
	}

	/**
	 * N. Piston phase and firing phase are separate representations, and the crank an
	 * inline-4 actually has - 1 and 4 paired against 2 and 3 - falls out of them.
	 */
	static void pistonPhaseIsIndependentOfFiringPhase() {
		section("N  PISTON PHASE AND FIRING PHASE ARE INDEPENDENT");

		FourStrokeFiringOrder r4 = FourStrokeFiringOrder.R4;

		check("cylinders 1 and 4 share a crank throw",
			near(r4.geometricOffsetDegrees(0), r4.geometricOffsetDegrees(3)),
			r4.geometricOffsetDegrees(0) + " and " + r4.geometricOffsetDegrees(3));
		check("cylinders 2 and 3 share the other",
			near(r4.geometricOffsetDegrees(1), r4.geometricOffsetDegrees(2)),
			r4.geometricOffsetDegrees(1) + " and " + r4.geometricOffsetDegrees(2));
		check("the two pairs are 180 apart",
			near(Math.abs(r4.geometricOffsetDegrees(0) - r4.geometricOffsetDegrees(1)), 180.0F),
			"0/180");
		check("yet 1 and 4 fire a full revolution apart",
			near(Math.abs(r4.ignitionOffsetDegrees(0) - r4.ignitionOffsetDegrees(3)), 360.0F),
			r4.ignitionOffsetDegrees(0) + " and " + r4.ignitionOffsetDegrees(3));
		check("which one number per cylinder could not express",
			!near(r4.geometricOffsetDegrees(3), r4.ignitionOffsetDegrees(3)),
			"geometry 0, ignition 360");

		// And the derivation invariant: geometry is the cycle offset folded into one
		// revolution, never a second stored table.
		boolean derived = true;
		for (FourStrokeFiringOrder configuration : FourStrokeFiringOrder.values())
			for (int i = 0; i < configuration.cylinderCount(); i++)
				if (!near(configuration.geometricOffsetDegrees(i),
					FourStrokeCycle.normalizeRevolution(configuration.cyclePhaseOffsetDegrees(i))))
					derived = false;
		check("geometry is always the cycle offset mod 360", derived, "every cylinder, every config");

		// R3's crank is unchanged from the engine that ships today.
		check("R3's throws are still 0 / 120 / 240 - the crank the mod already has",
			near(FourStrokeFiringOrder.R3.geometricOffsetDegrees(0), 0.0F)
				&& near(FourStrokeFiringOrder.R3.geometricOffsetDegrees(1), 120.0F)
				&& near(FourStrokeFiringOrder.R3.geometricOffsetDegrees(2), 240.0F),
			"0 / " + FourStrokeFiringOrder.R3.geometricOffsetDegrees(1) + " / "
				+ FourStrokeFiringOrder.R3.geometricOffsetDegrees(2));

		// And a cylinder's piston position is the same whether taken from its physical
		// angle or folded out of its cycle angle.
		FourStrokeEngine engine = new FourStrokeEngine(r4);
		boolean consistent = true;
		for (int i = 0; i < 1440; i++) {
			engine.step(0.5F, true);
			for (int cylinder = 0; cylinder < 4; cylinder++) {
				FourStrokeCylinderTiming timing = engine.cylinder(cylinder);
				float expected = FourStrokeCycle.normalizeRevolution(
					engine.physicalAngle() + r4.geometricOffsetDegrees(cylinder));
				if (!near(timing.physicalAngle(), expected))
					consistent = false;
			}
		}
		check("piston angle from the cycle equals piston angle from the geometric offset",
			consistent, "4 cylinders x 1440 steps");
	}

	/**
	 * L. The one that matters most. A player rocking the crank across the ignition
	 * point must not be able to manufacture combustion events.
	 */
	static void reverseOscillationCannotDuplicateEvents() {
		section("L  ROCKING THE CRANK CANNOT DUPLICATE IGNITIONS");

		// Walk an R1 up to just past its first ignition - 900 degrees from cold, since
		// it has to inhale before it can burn - and then oscillate hard.
		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R1);
		for (int i = 0; i < 901; i++)
			engine.step(1.0F, true);
		check("the cylinder has fired once on the way up", engine.totalIgnitions() == 1,
			engine.totalIgnitions() + " ignitions");

		for (int i = 0; i < 2000; i++) {
			engine.step(-1.0F, true);
			engine.step(1.0F, true);
		}
		check("2000 rocks across the ignition point add nothing", engine.totalIgnitions() == 1,
			engine.totalIgnitions() + " ignitions after 4000 steps");

		// Wider oscillation: a whole revolution back and forth, still short of a cycle.
		for (int rock = 0; rock < 50; rock++) {
			for (int i = 0; i < 359; i++)
				engine.step(-1.0F, true);
			for (int i = 0; i < 359; i++)
				engine.step(1.0F, true);
		}
		check("50 full-revolution rocks add nothing either", engine.totalIgnitions() == 1,
			engine.totalIgnitions() + " ignitions");

		// And the engine is not merely jammed: turning it forward properly still fires.
		for (int i = 0; i < 720; i++)
			engine.step(1.0F, true);
		check("but a genuine forward cycle still fires it", engine.totalIgnitions() == 2,
			engine.totalIgnitions() + " ignitions");

		// THE EXACT ATTACK, played deliberately rather than hoped for. Arm the cylinder
		// by running forward to the start of intake, wind back through compression top
		// dead centre - which does not fire - and nudge forward across it again. If a
		// charge survived that, this loop is an infinite bang generator with a net
		// travel of two degrees per bang.
		FourStrokeEngine attacked = new FourStrokeEngine(FourStrokeFiringOrder.R1);
		for (int i = 0; i < 541; i++)
			attacked.step(1.0F, true);
		check("the cylinder is armed at the start of its intake stroke",
			attacked.cylinder(0).isArmed(), "armed=" + attacked.cylinder(0).isArmed());
		int attackedBefore = attacked.totalIgnitions();
		for (int attempt = 0; attempt < 500; attempt++) {
			for (int i = 0; i < 361; i++)
				attacked.step(-1.0F, true);
			for (int i = 0; i < 361; i++)
				attacked.step(1.0F, true);
		}
		// 500 attempts, each 361 degrees back and 361 forward: 361000 degrees of travel
		// but only 0 net, so an honest engine yields nothing at all.
		check("500 wind-back-and-nudge attacks yield no ignitions",
			attacked.totalIgnitions() == attackedBefore,
			(attacked.totalIgnitions() - attackedBefore) + " ignitions for 0 degrees net");

		// THE GENERAL INVARIANT, on an engine shaken hard enough to traverse the cycle
		// many times in both directions.
		//
		// The bound is on PATH LENGTH - how far the crank is actually turned, adding up
		// both directions - and not on net displacement, because net displacement is
		// the wrong question. A crank wound a long way backwards and then run forwards
		// through a whole cycle lower down has genuinely inhaled, compressed and fired;
		// its bang is real, and it can land at almost the same absolute position as the
		// last one. What must be impossible is getting a bang CHEAPLY, and the cost is
		// measured in crank movement, because crank movement is what the player has to
		// supply.
		//
		// The floor is exactly 720, and it is provable from the two legs. Fire to arm:
		// the cylinder must forward-cross 540, which from 180 is 360 degrees forward,
		// or 361 back and 1 forward - 360 of path either way. Arm to fire: it must
		// forward-cross 180 without backward-crossing 540, which confines it to a
		// 360-degree forward run. So rocking the crank costs 740 degrees of turning per
		// bang against 720 for simply running it forwards: strictly worse, which is the
		// property that makes the exploit not worth having.
		FourStrokeEngine four = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		java.util.Random random = new java.util.Random(15L);
		float maximumStep = 30.0F;
		float[] pathAtLastIgnition = new float[four.cylinderCount()];
		java.util.Arrays.fill(pathAtLastIgnition, Float.NaN);
		float pathLength = 0.0F;
		float cheapestBang = Float.MAX_VALUE;
		int intervals = 0;
		boolean everTooCheap = false;
		for (int i = 0; i < 200000; i++) {
			float delta = (random.nextFloat() - 0.5F) * 2.0F * maximumStep;
			int mask = four.step(delta, true);
			pathLength += Math.abs(delta);
			for (int cylinder = 0; cylinder < four.cylinderCount(); cylinder++) {
				if ((mask & (1 << cylinder)) == 0)
					continue;
				if (!Float.isNaN(pathAtLastIgnition[cylinder])) {
					float cost = pathLength - pathAtLastIgnition[cylinder];
					intervals++;
					cheapestBang = Math.min(cheapestBang, cost);
					// A crossing is only noticed at the end of the step that made it, so
					// the measured cost can be short by up to one step at each end.
					if (cost < 720.0F - 2.0F * maximumStep)
						everTooCheap = true;
				}
				pathAtLastIgnition[cylinder] = pathLength;
			}
		}
		check("no bang on a hard-shaken R4 ever costs less than 720 degrees of turning",
			!everTooCheap, intervals + " intervals, cheapest " + Math.round(cheapestBang)
				+ " degrees of crank travel");
	}

	/** M. Saving mid-stroke and reloading must land on the same stroke, not merely the same piston position. */
	static void cyclePhaseSurvivesSaveAndReload() {
		section("M  CYCLE PHASE SURVIVES SAVE AND RELOAD");

		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		// Stop at 473 degrees - the milestone's own example, deep in the exhaust stroke.
		for (int i = 0; i < 473; i++)
			engine.step(1.0F, true);
		check("stopped at cycle angle 473", near(engine.cycleAngle(), 473.0F),
			engine.cycleAngle() + "");
		check("cylinder 1 is on EXHAUST", engine.cylinder(0).phase() == FourStrokePhase.EXHAUST,
			engine.cylinder(0).phase().toString());

		FourStrokeEngine.Save save = engine.save();
		FourStrokeEngine reloaded = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		reloaded.restore(save);

		check("the cycle angle comes back as 473, not 113",
			near(reloaded.cycleAngle(), 473.0F), reloaded.cycleAngle() + "");
		boolean strokesMatch = true;
		boolean armingMatches = true;
		for (int i = 0; i < 4; i++) {
			if (reloaded.cylinder(i).phase() != engine.cylinder(i).phase())
				strokesMatch = false;
			if (reloaded.cylinder(i).isArmed() != engine.cylinder(i).isArmed())
				armingMatches = false;
		}
		check("every cylinder comes back on the same stroke", strokesMatch, "4 cylinders");
		check("and with the same charge inducted or not", armingMatches, "4 cylinders");

		// The real test: run both on from here and demand identical firing.
		StringBuilder before = new StringBuilder();
		StringBuilder after = new StringBuilder();
		for (int i = 0; i < 1440; i++) {
			before.append(engine.step(1.0F, true)).append(',');
			after.append(reloaded.step(1.0F, true)).append(',');
		}
		check("and fires identically for two whole cycles afterwards",
			before.toString().equals(after.toString()), "1440 steps compared");

		// A save that carried only the physical angle would be ambiguous - which is
		// exactly what the current engine persists.
		check("113 (the physical angle) would have been a different stroke",
			FourStrokePhase.at(113.0F) != FourStrokePhase.at(473.0F),
			FourStrokePhase.at(113.0F) + " vs " + FourStrokePhase.at(473.0F));
	}

	/**
	 * The claim the whole milestone rests on: an inline-4 is smoother than a single
	 * because of its firing spacing, not because anything says so.
	 */
	static void smoothnessRisesWithCylinderCount() {
		section("   SMOOTHNESS EMERGES FROM THE FIRING SPACING");

		float single = torqueRipple(FourStrokeFiringOrder.R1);
		float twinEven = torqueRipple(FourStrokeFiringOrder.R2_EVEN);
		float twinUneven = torqueRipple(FourStrokeFiringOrder.R2_UNEVEN);
		float triple = torqueRipple(FourStrokeFiringOrder.R3);
		float four = torqueRipple(FourStrokeFiringOrder.R4);

		System.out.printf("     ripple (std dev of net torque, equal average power)%n");
		System.out.printf("     R1 %.3f   R2even %.3f   R2uneven %.3f   R3 %.3f   R4 %.3f%n",
			single, twinEven, twinUneven, triple, four);

		check("R2 (even) is smoother than R1", twinEven < single, twinEven + " < " + single);
		check("R3 is smoother than R2 (even)", triple < twinEven, triple + " < " + twinEven);
		check("R4 is smoother than R3", four < triple, four + " < " + triple);
		check("R4 is dramatically smoother than R1", four < single * 0.5F,
			four + " < " + (single * 0.5F));
		check("both twins sit between R1 and R3 on the ladder",
			twinUneven < single && twinUneven > triple, twinUneven + " in (" + triple + ", " + single + ")");

		// A measured result rather than an assumed one, and it goes the other way from
		// the obvious guess: the UNEVEN twin has the lower torque ripple, because its
		// opposed throws make the two gas springs cancel, while the even-fire twin
		// compresses both cylinders at once and gets one lump of twice the size. Even
		// firing buys an even RHYTHM, not a smoother crank. Which matters more is a
		// gameplay decision - see the design document.
		System.out.printf("     the even-fire twin has the EVEN RHYTHM; the uneven twin has%n");
		System.out.printf("     the lower ripple (%.3f vs %.3f), because its throws oppose%n",
			twinUneven, twinEven);
	}

	/**
	 * Standard deviation of net crank torque over one whole cycle, with every
	 * configuration at the same average power.
	 */
	static float torqueRipple(FourStrokeFiringOrder configuration) {
		FourStrokeEngine engine = new FourStrokeEngine(configuration);
		// Two cycles: the first settles the latches, the second is measured.
		for (int i = 0; i < 1440; i++)
			engine.step(1.0F, true);

		double sum = 0.0D;
		double sumSquares = 0.0D;
		int samples = 720;
		for (int i = 0; i < samples; i++) {
			engine.step(1.0F, true);
			// Peak combustion 24, peak compression 6 - the production
			// PEAK_COMBUSTION_TORQUE at idle and COMPRESSION_PEAK_TORQUE. No pumping,
			// which is what the design document recommends shipping first.
			float torque = engine.netTorque(24.0F, 6.0F, 0.0F);
			sum += torque;
			sumSquares += (double) torque * torque;
		}
		double mean = sum / samples;
		return (float) Math.sqrt(sumSquares / samples - mean * mean);
	}

	/**
	 * A healthy four-stroke waits up to 720 degrees between bangs, and the
	 * active-cylinder rule must not read that as a dead cylinder.
	 */
	static void activeCylindersDoNotFlickerBetweenFirings() {
		section("   ACTIVE CYLINDERS DO NOT FLICKER BETWEEN FIRINGS");

		// 64 RPM, the production idle: 18.75 ticks a revolution, 12 degrees a tick.
		float rpm = 64.0F;
		float degreesPerTick = rpm * 360.0F / 1200.0F;
		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R1);

		// The allowance measured in REVOLUTIONS, as the engine does today, is too short
		// once a cylinder only fires every other revolution.
		int revolutionAllowance = Math.round(2.5F * 1200.0F / rpm) + 2;
		int cycleAllowance = FourStrokeEngine.generationAllowanceTicks(rpm, 2.5F);
		int firingIntervalTicks = Math.round(2.0F * 1200.0F / rpm);
		System.out.printf("     at %.0f RPM: firing interval %d ticks, "
			+ "revolution-based allowance %d, firing-interval-based allowance %d%n",
			rpm, firingIntervalTicks, revolutionAllowance, cycleAllowance);

		int settleTicks = 0;
		while (engine.totalIgnitions() == 0 && settleTicks < 500) {
			engine.step(degreesPerTick, true);
			settleTicks++;
		}

		boolean everBlank = false;
		for (int i = 0; i < 400; i++) {
			engine.step(degreesPerTick, true);
			if (engine.activeCylinderMask(rpm, 2.5F) == 0)
				everBlank = true;
		}
		check("an idling R1's cylinder never drops out of the mask", !everBlank,
			"400 ticks at 64 RPM");

		// And at the bottom of the running range, where the production hard ceiling of
		// 60 ticks is shorter than the firing interval.
		float slow = 24.0F;
		check("at 24 RPM the firing interval (" + Math.round(2.0F * 1200.0F / slow)
			+ " ticks) exceeds the production 60-tick ceiling",
			2.0F * 1200.0F / slow > 60.0F, "the ceiling must be raised - see the design doc");
		check("the firing-interval allowance covers it",
			FourStrokeEngine.generationAllowanceTicks(slow, 2.5F) > 2.0F * 1200.0F / slow,
			FourStrokeEngine.generationAllowanceTicks(slow, 2.5F) + " ticks");
	}

	// ---------------------------------------------------------------- harness

	static boolean near(float actual, float expected) {
		return Math.abs(actual - expected) < 1.0E-3F;
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
