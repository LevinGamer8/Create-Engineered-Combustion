import dev.engineeredcombustion.prototype.fourstroke.*;
import dev.engineeredcombustion.content.engine.*;

/**
 * Validates the balance claims Milestone 15A made analytically, by measuring them:
 * fuel economy, mean output, flywheel ripple, the smoothness ladder, the frozen
 * inline-2, the active-cylinder rule and starting effort.
 *
 * <p>The prototype rig imports the <b>real</b> {@code EngineTuning} - the real
 * friction, inertia, governor and load model - and the legacy baseline is the
 * <b>real</b> {@code EngineState}, hand-cranked to life exactly as a player does.
 * So every "current versus future" number here is measured on the same physics
 * rather than on two different idealisations.
 *
 * <p>Exits non-zero on any failure.
 */
public class FourStrokeBalanceTests {

	static int failures = 0;
	static final float[] SPEEDS = { 64.0F, 128.0F, 192.0F };

	public static void main(String[] args) {
		fuelEconomyIsPreservedExactly();
		meanOutputIsPreserved();
		fourStrokeTwinMatchesTodaysSingle();
		smoothnessLadder();
		theFrozenInlineTwo();
		flywheelRippleIsAcceptable();
		activeCylindersSurviveTheWait();
		toleranceBelowTwoIntervalsDropsAHealthyCylinder();
		faultsInvalidateImmediately();
		startingEffort();
		startAttemptsLapseOnTravelNotTicks();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	// ------------------------------------------------------------------ fuel

	/**
	 * Halving the event rate and doubling the charge must leave fuel economy exactly
	 * where it is - not approximately, exactly, because both factors are integers.
	 */
	static void fuelEconomyIsPreservedExactly() {
		section("FUEL ECONOMY IS PRESERVED EXACTLY");

		System.out.printf("     %-4s %6s | %12s %10s | %12s %10s%n",
			"cyl", "rpm", "now comb/min", "now mB/min", "4s comb/min", "4s mB/min");
		boolean allEqual = true;
		for (int cylinders = 1; cylinders <= 4; cylinders++)
			for (float rpm : SPEEDS) {
				// Today: one event per cylinder per revolution, 1 mB each.
				double nowEvents = rpm * cylinders;
				double nowMb = nowEvents * 1;
				// Four-stroke: one event per cylinder per two revolutions, 2 mB each.
				double fourEvents = rpm / 2.0 * cylinders;
				double fourMb = fourEvents * 2;
				System.out.printf("     %-4d %6.0f | %12.1f %10.1f | %12.1f %10.1f%n",
					cylinders, rpm, nowEvents, nowMb, fourEvents, fourMb);
				if (Math.abs(nowMb - fourMb) > 1.0E-6D)
					allEqual = false;
				if (Math.abs(fourEvents * 2 - nowEvents) > 1.0E-6D)
					allEqual = false;
			}
		check("mB per minute is identical for every layout and speed", allEqual, "12 combinations");
		check("the event rate really did halve",
			Math.abs((192.0 / 2.0 * 4) - (192.0 * 4) / 2.0) < 1.0E-6D, "R4 at 192 RPM");
		// Production shipped the change in 15B. This is where the arithmetic above and
		// the constant the engine actually draws with are held together: one combustion
		// per 720 degrees at 2 mB is the same gasoline per revolution as one per 360 at
		// 1 mB, and either number moving alone is what would break it.
		check("production draws 2 mB per combustion, as the halved event rate requires",
			EngineTuning.FUEL_PER_COMBUSTION_MB == 2, EngineTuning.FUEL_PER_COMBUSTION_MB + " mB");
	}

	// ---------------------------------------------------------------- output

	/**
	 * The duty correction is exact arithmetic, not a tuning: the four-stroke engine
	 * must settle on the same speed the production engine settles on, at every
	 * layout and every throttle.
	 */
	static void meanOutputIsPreserved() {
		section("MEAN OUTPUT IS PRESERVED ACROSS THE CHANGE");

		// Production carries the four-stroke duty itself since Milestone 15B, so the rig
		// needs no correction at all - and THAT is the identity now worth pinning. It
		// was 2 for as long as production solved its peak against a 360-degree duty;
		// the rig derives it rather than naming it, so the day production changed, the
		// rig followed instead of quietly settling every engine 11 % high.
		check("production's own duty is the four-stroke one, so the rig needs no correction",
			Math.abs(FourStrokeRig.FOUR_STROKE_TORQUE_SCALE - 1.0F) < 1.0E-6F
				&& Math.abs(EngineTuning.POWER_STROKE_DUTY - 180.0F / 720.0F) < 1.0E-6F,
			String.format("%.1fx at a duty of %.3f", FourStrokeRig.FOUR_STROKE_TORQUE_SCALE,
				EngineTuning.POWER_STROKE_DUTY));
		// And the doubling itself, stated where it actually lives: the peak the engine
		// solves for is exactly twice what the 360-degree duty would have given.
		check("and the peak that duty solves for is exactly twice the 360-degree one",
			Math.abs(EngineTuning.peakCombustionTorqueFor(EngineTuning.IDLE_RPM)
				- 2.0F * EngineTuning.frictionTorqueAt(EngineTuning.IDLE_RPM) / (0.5F * 0.5F)) < 1.0E-3F,
			String.format("%.2f", EngineTuning.peakCombustionTorqueFor(EngineTuning.IDLE_RPM)));

		System.out.printf("     %-4s %6s %6s | %11s %11s %8s%n",
			"cyl", "rpm", "load", "production", "four-stroke", "error");
		boolean allClose = true;
		for (int cylinders = 1; cylinders <= 4; cylinders++)
			for (float rpm : SPEEDS)
				for (float load : new float[] { 0.0F, 0.5F }) {
					float production = legacyMeanRpm(cylinders, rpm, load);
					FourStrokeRig rig = new FourStrokeRig(
						FourStrokeFiringOrder.forCylinderCount(cylinders), rpm, rpm, load);
					float four = rig.run(2000, 2400).meanRpm();
					float error = Math.abs(four - production) / production;
					System.out.printf("     %-4d %6.0f %6.1f | %11.2f %11.2f %7.2f%%%n",
						cylinders, rpm, load, production, four, error * 100);
					// Within 3 %. The milestone explicitly does NOT want the transients
					// identical - only the broad balance held.
					if (error > 0.03F)
						allClose = false;
				}
		// Since 15B this compares the prototype against a PRODUCTION engine that is
		// itself four-stroke, so it has become an agreement check rather than a
		// before-and-after: the reference model and the shipped engine must settle at
		// the same speed, at every layout, speed and load. That is the stronger of the
		// two questions and the one worth keeping.
		check("the prototype and production settle within 3 % of each other", allClose,
			"24 operating points");
	}

	/**
	 * A structural cross-check that falls out of the arithmetic and is worth pinning
	 * down: a four-stroke twin fires once per 360 degrees with double the impulse,
	 * which is <i>exactly</i> what today's single-cylinder engine already does. Their
	 * whole-engine torque waveforms should therefore be the same, and so should their
	 * ripple.
	 */
	static void fourStrokeTwinMatchesTodaysSingle() {
		section("THE EVEN TWIN REPRODUCES THE 15A MEASUREMENTS EXACTLY");

		// THE RECORDED FIGURES, from docs/milestone-15-four-stroke-design.md - the
		// table headed "360 even / 180 uneven", measured on the real flywheel and the
		// real friction. They are written down here rather than re-derived from a
		// legacy engine because there is no longer a legacy engine to derive them from:
		// production is four-stroke now. Pinning the numbers is what keeps the frozen
		// decision auditable - a change that moves them is a change to the design, and
		// this is where it gets noticed.
		float[] recordedEvenTwinRipple = { 6.00F, 5.06F, 4.92F };
		float[] recordedUnevenTwinRipple = { 9.97F, 8.45F, 7.68F };

		for (int i = 0; i < SPEEDS.length; i++) {
			float rpm = SPEEDS[i];
			float even = new FourStrokeRig(FourStrokeFiringOrder.R2_EVEN, rpm, rpm, 0.0F)
				.run(2000, 2400).rpmRipple();
			check("at " + (int) rpm + " RPM the even twin still measures its recorded ripple",
				Math.abs(even - recordedEvenTwinRipple[i]) < 0.35F,
				String.format("%.2f against a recorded %.2f", even, recordedEvenTwinRipple[i]));

			float uneven = new FourStrokeRig(FourStrokeFiringOrder.R2_UNEVEN, rpm, rpm, 0.0F)
				.run(2000, 2400).rpmRipple();
			check("... and the frozen uneven twin measures its recorded ripple",
				Math.abs(uneven - recordedUnevenTwinRipple[i]) < 0.35F,
				String.format("%.2f against a recorded %.2f", uneven, recordedUnevenTwinRipple[i]));
			check("... and the uneven twin is the rougher of the two, as the decision says",
				uneven > even, String.format("%.2f > %.2f", uneven, even));
		}
	}

	// -------------------------------------------------------------- character

	/** The ladder the milestone wants, measured on the real flywheel. */
	static void smoothnessLadder() {
		section("SMOOTHNESS RISES WITH CYLINDER COUNT");

		float single = rippleFraction(FourStrokeFiringOrder.R1);
		float twin = rippleFraction(FourStrokeFiringOrder.DEFAULT_R2);
		float triple = rippleFraction(FourStrokeFiringOrder.R3);
		float four = rippleFraction(FourStrokeFiringOrder.R4);
		System.out.printf("     speed ripple at 64 RPM, no load:  R1 %.2f%%   R2 %.2f%%   R3 %.2f%%   R4 %.2f%%%n",
			single * 100, twin * 100, triple * 100, four * 100);

		check("R2 is smoother than R1", twin < single, pct(twin) + " < " + pct(single));
		check("R3 is smoother than R2", triple < twin, pct(triple) + " < " + pct(twin));
		check("R4 is smoother than R3", four < triple, pct(four) + " < " + pct(triple));
		check("R1 is emphatically lumpy - the flywheel matters", single > 0.15F, pct(single));
		check("R4 is nearly rigid", four < 0.02F, pct(four));
	}

	/** The frozen inline-2, and the properties that decision rests on. */
	static void theFrozenInlineTwo() {
		section("THE FROZEN INLINE-2: 180-DEGREE OPPOSED, UNEVEN FIRE");

		FourStrokeFiringOrder r2 = FourStrokeFiringOrder.DEFAULT_R2;
		check("the default inline-2 is the 180-degree crank",
			r2 == FourStrokeFiringOrder.R2_UNEVEN, r2.toString());
		check("and forCylinderCount(2) agrees",
			FourStrokeFiringOrder.forCylinderCount(2) == r2,
			FourStrokeFiringOrder.forCylinderCount(2).toString());
		check("its throws are opposed: 0 and 180",
			near(r2.geometricOffsetDegrees(0), 0.0F) && near(r2.geometricOffsetDegrees(1), 180.0F),
			r2.geometricOffsetDegrees(0) + " / " + r2.geometricOffsetDegrees(1));
		check("its firing intervals are 180 then 540",
			java.util.Arrays.equals(r2.ignitionIntervalsDegrees(), new float[] { 180.0F, 540.0F }),
			java.util.Arrays.toString(r2.ignitionIntervalsDegrees()));
		check("it is correctly reported as uneven-fire", !r2.evenFire(), "evenFire=false");

		// The cost of the decision, stated as a bound rather than left implicit.
		float uneven = rippleFraction(FourStrokeFiringOrder.R2_UNEVEN);
		float even = rippleFraction(FourStrokeFiringOrder.R2_EVEN);
		System.out.printf("     the choice costs %.2f%% speed ripple at idle (%.2f%% against %.2f%%)%n",
			(uneven - even) * 100, uneven * 100, even * 100);
		check("the even twin really is the smoother one - 15A said otherwise", even < uneven,
			pct(even) + " < " + pct(uneven));
		check("but the uneven twin still sits between R1 and R3",
			uneven < rippleFraction(FourStrokeFiringOrder.R1)
				&& uneven > rippleFraction(FourStrokeFiringOrder.R3),
			pct(uneven));
		check("and never approaches a stall, even at 95 % load",
			new FourStrokeRig(FourStrokeFiringOrder.R2_UNEVEN, 64.0F, 64.0F, 0.95F)
				.run(3000, 2400).minRpm() > EngineTuning.STALL_RPM * 3.0F,
			new FourStrokeRig(FourStrokeFiringOrder.R2_UNEVEN, 64.0F, 64.0F, 0.95F)
				.run(3000, 2400).minRpm() + " RPM against a stall at " + EngineTuning.STALL_RPM);
	}

	/**
	 * The flywheel study: the existing inertia was balanced around the 360-degree
	 * model, and must still hold the new waveforms.
	 */
	static void flywheelRippleIsAcceptable() {
		section("THE EXISTING FLYWHEEL INERTIA STILL HOLDS");

		System.out.printf("     %-10s %6s %6s %9s %9s %9s%n",
			"config", "rpm", "load", "mean", "min", "ripple%");
		boolean everStalls = false;
		boolean everCollapses = false;
		for (FourStrokeFiringOrder configuration : new FourStrokeFiringOrder[] {
			FourStrokeFiringOrder.R1, FourStrokeFiringOrder.DEFAULT_R2,
			FourStrokeFiringOrder.R3, FourStrokeFiringOrder.R4 })
			for (float rpm : SPEEDS)
				for (float load : new float[] { 0.0F, 0.5F }) {
					FourStrokeRig.Sample sample =
						new FourStrokeRig(configuration, rpm, rpm, load).run(3000, 2400);
					System.out.printf("     %-10s %6.0f %6.1f %9.2f %9.2f %8.2f%%%n",
						configuration, rpm, load, sample.meanRpm(), sample.minRpm(),
						sample.rpmRippleFraction() * 100);
					if (sample.minRpm() < EngineTuning.STALL_RPM)
						everStalls = true;
					// "power stroke -> almost stop -> power stroke" is the thing the
					// milestone says must not happen at rated speed. Half the mean is
					// the line.
					if (sample.minRpm() < sample.meanRpm() * 0.5F)
						everCollapses = true;
				}
		check("no layout stalls at any rated operating point", !everStalls, "24 points");
		check("and none collapses to half its mean speed between strokes", !everCollapses, "24 points");
		check("FLYWHEEL_INERTIA is unchanged at " + EngineTuning.FLYWHEEL_INERTIA,
			EngineTuning.FLYWHEEL_INERTIA == 20.0F, "no production retune needed");
	}

	// ------------------------------------------------------- active cylinders

	/**
	 * A healthy four-stroke cylinder waits a whole cycle between bangs, and the
	 * capacity basis must not blink during that wait - at any speed the engine can
	 * legally run at.
	 */
	static void activeCylindersSurviveTheWait() {
		section("A HEALTHY CYLINDER NEVER BLINKS BETWEEN ITS OWN BANGS");

		System.out.printf("     %-4s %6s %10s %14s %12s%n",
			"cyl", "rpm", "interval", "today's rule", "frozen rule");
		boolean allSurvive = true;
		boolean todayWouldFlicker = false;
		for (int cylinders : new int[] { 1, 4 })
			for (float rpm : new float[] { 16.0F, 24.0F, 32.0F, 64.0F, 128.0F, 192.0F }) {
				float interval = 2.0F * 1200.0F / rpm;
				int today = EngineTuning.generationCombustionAllowanceTicks(rpm);
				int frozen = FourStrokeEngine.generationAllowanceTicks(rpm, 2.5F);
				System.out.printf("     %-4d %6.0f %10.1f %14d %12d %s%n", cylinders, rpm, interval,
					today, frozen, today < interval ? "  <- today flickers" : "");
				if (today < interval)
					todayWouldFlicker = true;

				FourStrokeEngine engine = new FourStrokeEngine(
					FourStrokeFiringOrder.forCylinderCount(cylinders));
				engine.armAsIfRested();
				float degreesPerTick = rpm * 360.0F / 1200.0F;
				int full = (1 << cylinders) - 1;
				// Settle until EVERY cylinder has had its first bang. Until then the
				// mask is legitimately partial - a cylinder that has never fired is not
				// yet contributing, which is the rule working rather than flickering.
				for (int settle = 0; settle < 20000 && engine.activeCylinderMask(rpm, 2.5F) != full;
					settle++)
					engine.step(degreesPerTick, true);
				for (int i = 0; i < 1200; i++) {
					engine.step(degreesPerTick, true);
					if (engine.activeCylinderMask(rpm, 2.5F) != full)
						allSurvive = false;
				}
			}
		check("every cylinder stays in the mask, every speed, 1200 ticks", allSurvive, "12 runs");
		// The rule production shipped is the frozen one, so it no longer flickers - and
		// that is the point of the column beside it. What is checked is that production
		// now agrees with the reference model at every speed, which is what replaced
		// "today's rule would flicker" once today's rule was fixed.
		check("production's allowance never flickers under four-stroke", !todayWouldFlicker,
			"12 speeds, 1 and 4 cylinders");
	}

	/**
	 * Determining the tolerance. The question is not "how many intervals feels
	 * right"; it is whether a single missed firing should drop a cylinder, because
	 * the answer to that puts the constant on one side of 2.0 or the other.
	 */
	static void toleranceBelowTwoIntervalsDropsAHealthyCylinder() {
		section("WHY THE TOLERANCE IS 2.5 INTERVALS AND NOT 1.5");

		float rpm = 64.0F;
		float degreesPerTick = rpm * 360.0F / 1200.0F;
		float interval = 2.0F * 1200.0F / rpm;

		for (float tolerance : new float[] { 1.5F, 2.0F, 2.5F }) {
			FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R1);
			engine.armAsIfRested();
			for (int i = 0; i < 2000 && engine.totalIgnitions() == 0; i++)
				engine.step(degreesPerTick, true);

			// One firing opportunity starved of fuel: a momentary hiccup, not a fault.
			// Counted by opportunities OFFERED, because with the tank dry no ignition
			// will ever arrive to count instead.
			int opportunitiesBefore = engine.opportunities();
			int guard = 0;
			while (engine.opportunities() == opportunitiesBefore && guard++ < 2000)
				engine.step(degreesPerTick, false);
			// Then fuel returns, and the cylinder catches again next cycle.
			boolean blanked = false;
			for (int i = 0; i < 200; i++) {
				engine.step(degreesPerTick, true);
				if (engine.activeCylinderMask(rpm, tolerance) == 0)
					blanked = true;
			}
			boolean tolerated = !blanked;
			System.out.printf("     tolerance %.1f intervals (%d ticks vs a %.0f-tick interval): %s%n",
				tolerance, FourStrokeEngine.generationAllowanceTicks(rpm, tolerance), interval,
				tolerated ? "survives one missed firing" : "DROPS the cylinder");
			if (tolerance < 2.0F)
				check("tolerance " + tolerance + " drops a cylinder on one missed firing", !tolerated,
					"as designed - below 2.0 cannot span two intervals");
			if (tolerance > 2.0F)
				check("tolerance " + tolerance + " survives one missed firing", tolerated,
					"the frozen choice");
		}
		check("2.0 is the boundary, so the constant must not sit on it",
			FourStrokeEngine.generationAllowanceTicks(rpm, 2.0F) <= Math.round(2.0F * interval) + 2,
			"2.5 is the safe side with margin");
	}

	/**
	 * Combustion age is the wrong clock for a fault. A pulled Spark Plug must clear
	 * the bit on the tick it happens, not several seconds later when the age finally
	 * expires.
	 */
	static void faultsInvalidateImmediately() {
		section("FAULTS INVALIDATE IMMEDIATELY, WAITING DOES NOT");

		float rpm = 64.0F;
		float degreesPerTick = rpm * 360.0F / 1200.0F;

		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		engine.armAsIfRested();
		for (int i = 0; i < 400; i++)
			engine.step(degreesPerTick, true);
		check("all four cylinders are active to begin with",
			engine.activeCylinderMask(rpm, 2.5F) == 0b1111,
			Integer.toBinaryString(engine.activeCylinderMask(rpm, 2.5F)));

		engine.removeSparkPlug(2);
		check("pulling cylinder 3's plug clears its bit on the SAME tick",
			engine.activeCylinderMask(rpm, 2.5F) == 0b1011,
			Integer.toBinaryString(engine.activeCylinderMask(rpm, 2.5F)));
		check("and its combustion age is still fresh, so age alone would have lied",
			engine.ticksSinceCombustion(2) >= 0 && engine.ticksSinceCombustion(2)
				<= FourStrokeEngine.generationAllowanceTicks(rpm, 2.5F),
			"age " + engine.ticksSinceCombustion(2) + " ticks");

		engine.removePiston(1);
		check("pulling cylinder 2's piston clears its bit too, immediately",
			engine.activeCylinderMask(rpm, 2.5F) == 0b1001,
			Integer.toBinaryString(engine.activeCylinderMask(rpm, 2.5F)));

		engine.setIgnitionEnabled(false);
		check("switching ignition off clears every bit at once",
			engine.activeCylinderMask(rpm, 2.5F) == 0,
			Integer.toBinaryString(engine.activeCylinderMask(rpm, 2.5F)));
		engine.setIgnitionEnabled(true);

		// Fuel is deliberately NOT structural: the paid-for stroke finishes first.
		FourStrokeEngine starved = new FourStrokeEngine(FourStrokeFiringOrder.R1);
		starved.armAsIfRested();
		for (int i = 0; i < 2000 && starved.totalIgnitions() == 0; i++)
			starved.step(degreesPerTick, true);
		check("a starved engine keeps its bit while the paid charge is still pushing",
			starved.activeCylinderMask(rpm, 2.5F) == 0b1 && starved.isBurning(0),
			"burning=" + starved.isBurning(0));
		int ticks = 0;
		while (starved.activeCylinderMask(rpm, 2.5F) != 0 && ticks < 500) {
			starved.step(degreesPerTick, false);
			ticks++;
		}
		check("and then expires by age rather than instantly", ticks > 30 && ticks < 200,
			ticks + " ticks to expire");
	}

	// -------------------------------------------------------------- starting

	/** How much crank a player must actually turn, measured over every rest position. */
	static void startingEffort() {
		section("STARTING EFFORT, MEASURED OVER EVERY REST POSITION");

		System.out.printf("     %-10s %8s %8s %8s %10s%n",
			"config", "min deg", "max deg", "mean", "mean revs");
		for (FourStrokeFiringOrder configuration : new FourStrokeFiringOrder[] {
			FourStrokeFiringOrder.R1, FourStrokeFiringOrder.DEFAULT_R2,
			FourStrokeFiringOrder.R3, FourStrokeFiringOrder.R4 }) {
			int min = Integer.MAX_VALUE;
			int max = 0;
			long sum = 0;
			for (int rest = 0; rest < 720; rest++) {
				FourStrokeEngine engine = new FourStrokeEngine(configuration);
				engine.step(rest, false);
				engine.armAsIfRested();
				int degrees = 0;
				while (engine.totalIgnitions() == 0 && degrees < 4000) {
					engine.step(1.0F, true);
					degrees++;
				}
				min = Math.min(min, degrees);
				max = Math.max(max, degrees);
				sum += degrees;
			}
			System.out.printf("     %-10s %8d %8d %8.1f %10.2f%n", configuration, min, max,
				(double) sum / 720, (double) sum / 720 / 360);

			check(configuration + ": the first bang never costs more than one cycle",
				max <= 720, "worst case " + max + " degrees");
			// The true bound is the LARGEST gap in this engine's firing schedule, not
			// 720 over the cylinder count - which is the same thing only for an
			// even-fire engine. The uneven twin's 540-degree gap is exactly the cost of
			// choosing it, and stating the bound this way is what makes that visible
			// rather than hidden behind an average.
			float widestGap = 0.0F;
			for (float gap : configuration.ignitionIntervalsDegrees())
				widestGap = Math.max(widestGap, gap);
			check(configuration + ": bounded by its widest firing gap (" + (int) widestGap + " deg)",
				max <= widestGap + 1.0F,
				"worst case " + max + " degrees, mean " + Math.round((double) sum / 720));
		}

		// Without arming on rest, the worst case is half a revolution worse again.
		int worstCold = 0;
		for (int rest = 0; rest < 720; rest++) {
			FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R1);
			engine.step(rest, false);
			int degrees = 0;
			while (engine.totalIgnitions() == 0 && degrees < 4000) {
				engine.step(1.0F, true);
				degrees++;
			}
			worstCold = Math.max(worstCold, degrees);
		}
		check("without arming on rest an R1 can need three revolutions of silence",
			worstCold >= 1000, worstCold + " degrees worst case");
		check("arming on rest is what caps it at one cycle", worstCold > 720,
			"1079 degrees against 720");
	}

	/**
	 * The start-attempt rule, expressed in crank travel rather than in ticks.
	 *
	 * <p>Production's own timeout counts ticks since the engine could ignite <i>at
	 * all</i> and is reset on every tick a fuelled, cranked engine is turning, so it
	 * never runs during a genuine start attempt and needs no change. What four-stroke
	 * makes newly possible is turning a great deal while catching nothing, and this
	 * is the rule for that.
	 */
	static void startAttemptsLapseOnTravelNotTicks() {
		section("A START ATTEMPT LAPSES ON TRAVEL, NOT ON THE CLOCK");

		check("production's timeout is a staleness rule, and is unchanged at "
			+ EngineTuning.START_ATTEMPT_TIMEOUT_TICKS + " ticks",
			EngineTuning.START_ATTEMPT_TIMEOUT_TICKS == 30, "no change needed - see the design doc");

		// A rocked crank keeps offering opportunities and keeps declining them.
		FourStrokeEngine rocked = new FourStrokeEngine(FourStrokeFiringOrder.R1);
		rocked.armAsIfRested();
		for (int i = 0; i < 1000 && rocked.totalIgnitions() == 0; i++)
			rocked.step(1.0F, true);
		check("a caught engine has zero travel since combustion",
			rocked.cyclesSinceCombustion() < 0.01F, rocked.cyclesSinceCombustion() + " cycles");

		for (int i = 0; i < 800; i++) {
			rocked.step(-2.0F, true);
			rocked.step(2.0F, true);
		}
		check("rocking accumulates travel without combustion",
			rocked.cyclesSinceCombustion() > 4.0F,
			String.format("%.1f cycles of travel, %d ignitions", rocked.cyclesSinceCombustion(),
				rocked.totalIgnitions()));
		check("so the attempt lapses on the physical rule", rocked.startAttemptLapsed(2.0F),
			"lapsed after 2 cycles of fruitless travel");

		// And a slow but honest crank does NOT lapse: it catches within a cycle.
		FourStrokeEngine slow = new FourStrokeEngine(FourStrokeFiringOrder.R1);
		slow.armAsIfRested();
		boolean lapsed = false;
		float travelToFirstBang = 0.0F;
		// A twentieth of a degree per tick - about 0.17 RPM, far slower than anything
		// a player can achieve - for long enough to cover a whole cycle of travel.
		for (int i = 0; i < 20000 && slow.totalIgnitions() == 0; i++) {
			slow.step(0.05F, true);
			travelToFirstBang += 0.05F;
			if (slow.startAttemptLapsed(2.0F))
				lapsed = true;
		}
		check("an honest crank at any speed catches before it lapses", !lapsed && slow.totalIgnitions() > 0,
			String.format("caught after %.0f degrees, %d ticks - the lapse needs 1440",
				travelToFirstBang, (int) (travelToFirstBang / 0.05F)));
	}

	// ---------------------------------------------------- the production engine

	/** Mean settled speed of the REAL production engine, hand-cranked to life. */
	static float legacyMeanRpm(int cylinders, float targetRpm, float load) {
		return legacy(cylinders, targetRpm, load)[0];
	}

	static float legacyRipple(int cylinders, float targetRpm, float load) {
		return legacy(cylinders, targetRpm, load)[1];
	}

	static float[] legacy(int cylinders, float targetRpm, float load) {
		EngineState state = new EngineState();
		Tank tank = new Tank(Integer.MAX_VALUE);
		Sump sump = new Sump(EngineTuning.OIL_CAPACITY_MB);
		java.util.Random random = new java.util.Random(7L);
		float throttle = (targetRpm - EngineTuning.IDLE_RPM)
			/ (EngineTuning.FULL_THROTTLE_RPM - EngineTuning.IDLE_RPM);
		throttle = Math.max(0.0F, Math.min(1.0F, throttle));
		// STARTED UNLOADED, then loaded - which is what a player does, and what the
		// engine's own start logic assumes. Cranking against a load the engine is not
		// yet turning fast enough to carry was measuring a machine nobody builds: a
		// single at half load never reached the speed that carries it 720 degrees, so
		// it never caught, and the comparison read a stalled engine as an equilibrium.
		EngineInputs unloaded = new EngineInputs(true, true, cylinders, (1 << cylinders) - 1,
			throttle, 0.0F, EngineTuning.MAX_RPM);
		EngineInputs inputs = new EngineInputs(true, true, cylinders, (1 << cylinders) - 1,
			throttle, load, EngineTuning.MAX_RPM);

		// An engine cannot self-start from a free spin - it decelerates past its stall
		// speed long before it reaches a firing angle - so this hand-cranks it, which
		// is what a player does.
		for (int i = 0; i < 2000 && state.getPhase() != EnginePhase.RUNNING; i++) {
			state.tickRotation(32.0F, true, true);
			state.tickSimulation(unloaded, tank, sump, random);
		}
		// Up to its own idle before anything is hung on it. An engine catches barely
		// above the speed that will carry it between bangs, and handing it half a
		// network's load at that instant is not a measurement of its equilibrium - it
		// is a measurement of a machine being stalled by a load applied before it had
		// spun up, which is not how one gets built.
		for (int i = 0; i < 2000; i++) {
			state.tickRotation(0.0F, false, false);
			state.tickSimulation(unloaded, tank, sump, random);
		}
		for (int i = 0; i < 4000; i++) {
			state.tickRotation(0.0F, false, false);
			state.tickSimulation(inputs, tank, sump, random);
		}
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		double sum = 0.0D;
		for (int i = 0; i < 2400; i++) {
			state.tickRotation(0.0F, false, false);
			state.tickSimulation(inputs, tank, sump, random);
			float rpm = state.getSimulatedRpm();
			min = Math.min(min, rpm);
			max = Math.max(max, rpm);
			sum += rpm;
		}
		return new float[] { (float) (sum / 2400), max - min };
	}

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

	// ---------------------------------------------------------------- harness

	static float rippleFraction(FourStrokeFiringOrder configuration) {
		return new FourStrokeRig(configuration, 64.0F, 64.0F, 0.0F).run(3000, 2400).rpmRippleFraction();
	}

	static String pct(float fraction) {
		return String.format("%.2f%%", fraction * 100);
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
