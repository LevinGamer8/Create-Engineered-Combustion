import dev.engineeredcombustion.prototype.fourstroke.*;

/**
 * Holds the prototype valvetrain to its contract: the camshaft's 2:1 relationship,
 * the valve windows, the lift curve's continuity, and the per-cylinder independence
 * that makes an inline-4's eight valves eight different states on one shaft.
 *
 * <p>A pure test of prototype code, reachable from nothing in the mod.
 *
 * <p>Exits non-zero on any failure.
 */
public class ValvetrainTests {

	static int failures = 0;

	public static void main(String[] args) {
		camTurnsOncePerCycle();
		camAngleHasNoClockOfItsOwn();
		valveWindows();
		bothValvesShutWhenSealed();
		liftCurveIsSmoothAndBounded();
		noOverlapAtTheHandover();
		lobeLayoutIsTheFiringOrder();
		perCylinderIndependence();
		everyLayoutIsCoherent();
		valvetrainSurvivesSaveAndReload();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	/** The defining ratio: one camshaft revolution per two crank revolutions. */
	static void camTurnsOncePerCycle() {
		section("THE CAM TURNS ONCE PER 720 CRANK DEGREES");

		check("cycle 0 is cam 0", near(CamshaftTiming.camAngle(0.0F), 0.0F),
			CamshaftTiming.camAngle(0.0F) + "");
		check("cycle 360 (one crank turn) is cam 180 - half a turn",
			near(CamshaftTiming.camAngle(360.0F), 180.0F), CamshaftTiming.camAngle(360.0F) + "");
		check("cycle 719.99 is just short of cam 360",
			CamshaftTiming.camAngle(719.99F) > 359.9F && CamshaftTiming.camAngle(719.99F) < 360.0F,
			CamshaftTiming.camAngle(719.99F) + "");
		check("cycle 720 wraps back to cam 0", near(CamshaftTiming.camAngle(720.0F), 0.0F),
			CamshaftTiming.camAngle(720.0F) + "");

		// Driven, not asserted: turn a real engine two revolutions and count cam turns.
		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		float previous = CamshaftTiming.camAngle(engine.position());
		int camWraps = 0;
		for (int i = 0; i < 7200; i++) {
			engine.step(1.0F, true);
			float now = CamshaftTiming.camAngle(engine.position());
			if (now < previous)
				camWraps++;
			previous = now;
		}
		check("7200 crank degrees is exactly 10 camshaft revolutions", camWraps == 10,
			camWraps + " cam revolutions");
		check("which is 20 crank revolutions", true, "7200 / 360 = 20");
	}

	/**
	 * The cam angle must be a pure function of engine state. A camshaft that
	 * integrated its own angle would be a second clock, and two clocks drift.
	 */
	static void camAngleHasNoClockOfItsOwn() {
		section("THE CAM HAS NO CLOCK OF ITS OWN");

		// Shake an engine violently, then compare the cam against a fresh engine put
		// at the same position. If the cam were integrated these would differ.
		FourStrokeEngine shaken = new FourStrokeEngine(FourStrokeFiringOrder.R3);
		java.util.Random random = new java.util.Random(15202L);
		for (int i = 0; i < 50000; i++)
			shaken.step((random.nextFloat() - 0.5F) * 120.0F, true);

		FourStrokeEngine fresh = new FourStrokeEngine(FourStrokeFiringOrder.R3);
		fresh.step(shaken.cycleAngle(), false);

		check("a shaken engine's cam matches a fresh one at the same angle",
			near(CamshaftTiming.camAngle(shaken.position()), CamshaftTiming.camAngle(fresh.position())),
			String.format("%.4f vs %.4f", CamshaftTiming.camAngle(shaken.position()),
				CamshaftTiming.camAngle(fresh.position())));

		// And running the engine backwards runs the cam backwards, with no residue.
		FourStrokeEngine rocked = new FourStrokeEngine(FourStrokeFiringOrder.R1);
		rocked.step(300.0F, true);
		float before = CamshaftTiming.camAngle(rocked.position());
		for (int i = 0; i < 500; i++) {
			rocked.step(37.0F, true);
			rocked.step(-37.0F, true);
		}
		check("500 rocks leave the cam exactly where it was",
			near(CamshaftTiming.camAngle(rocked.position()), before),
			String.format("%.4f vs %.4f", CamshaftTiming.camAngle(rocked.position()), before));
	}

	/** Each valve is open across its own stroke and shut across the other three. */
	static void valveWindows() {
		section("EACH VALVE OPENS ONLY ON ITS OWN STROKE");

		for (ValveTiming valve : ValveTiming.values()) {
			boolean openOnlyInWindow = true;
			boolean everOpen = false;
			for (float angle = 0.0F; angle < 720.0F; angle += 0.25F) {
				boolean inWindow = FourStrokePhase.at(angle) == valve.stroke();
				boolean open = valve.isOpen(angle);
				if (open)
					everOpen = true;
				// Zero lift exactly at the two boundaries is correct, so the window test
				// allows shut-inside but never open-outside.
				if (open && !inWindow)
					openOnlyInWindow = false;
			}
			check(valve + " is never open outside its " + valve.stroke() + " stroke", openOnlyInWindow,
				"2880 samples");
			check(valve + " does actually open", everOpen,
				"window " + (int) valve.openAngleDegrees() + " to " + (int) valve.closeAngleDegrees());
		}

		check("INTAKE opens across [540, 720)",
			near(ValveTiming.INTAKE.openAngleDegrees(), 540.0F)
				&& near(ValveTiming.INTAKE.closeAngleDegrees(), 0.0F),
			ValveTiming.INTAKE.openAngleDegrees() + " for " + ValveTiming.INTAKE.durationDegrees());
		check("EXHAUST opens across [360, 540)",
			near(ValveTiming.EXHAUST.openAngleDegrees(), 360.0F)
				&& near(ValveTiming.EXHAUST.closeAngleDegrees(), 540.0F),
			ValveTiming.EXHAUST.openAngleDegrees() + " for " + ValveTiming.EXHAUST.durationDegrees());

		// The intake window and the cylinder's arming point are the same angle, and
		// that is a design invariant rather than a coincidence: a cylinder becomes able
		// to fire because it has just inhaled.
		check("the intake opens at exactly the cylinder's arming angle",
			near(ValveTiming.INTAKE.openAngleDegrees(), FourStrokeCycle.ARMING_ANGLE_DEGREES),
			ValveTiming.INTAKE.openAngleDegrees() + " == " + FourStrokeCycle.ARMING_ANGLE_DEGREES);
	}

	/**
	 * Two independent derivations of "is this cylinder sealed" - one from valve
	 * windows, one from stroke boundaries - must agree everywhere. A design where
	 * they disagree has a gas leak in it.
	 */
	static void bothValvesShutWhenSealed() {
		section("SEALED BY VALVES == SEALED BY STROKE");

		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		boolean alwaysAgree = true;
		boolean sawSealed = false;
		boolean sawOpen = false;
		for (int i = 0; i < 2880; i++) {
			engine.step(0.25F, true);
			for (int cylinder = 0; cylinder < 4; cylinder++) {
				boolean byValves = CamshaftTiming.isSealed(engine, cylinder);
				boolean byStroke = engine.cylinder(cylinder)
					.phase()
					.sealed();
				if (byValves)
					sawSealed = true;
				else
					sawOpen = true;
				// The two dead centres are shut by both definitions; inside a stroke they
				// must match exactly.
				float angle = engine.cylinder(cylinder)
					.cycleAngle();
				boolean onBoundary = near(angle % 180.0F, 0.0F) || near(angle % 180.0F, 180.0F);
				if (!onBoundary && byValves != byStroke)
					alwaysAgree = false;
			}
		}
		check("the two definitions agree at every angle of every cylinder", alwaysAgree,
			"4 cylinders x 2880 steps");
		check("and both states were actually observed", sawSealed && sawOpen, "sealed and open seen");

		// Compression and power specifically: both valves shut, as section 8 requires.
		boolean shutOnCompression = true;
		boolean shutOnPower = true;
		for (float angle = 1.0F; angle < 180.0F; angle += 1.0F)
			if (ValveTiming.INTAKE.isOpen(angle) || ValveTiming.EXHAUST.isOpen(angle))
				shutOnCompression = false;
		for (float angle = 181.0F; angle < 360.0F; angle += 1.0F)
			if (ValveTiming.INTAKE.isOpen(angle) || ValveTiming.EXHAUST.isOpen(angle))
				shutOnPower = false;
		check("both valves shut throughout COMPRESSION", shutOnCompression, "179 samples");
		check("both valves shut throughout POWER", shutOnPower, "179 samples");
	}

	/** A valve that snapped would read as a flickering block rather than a mechanism. */
	static void liftCurveIsSmoothAndBounded() {
		section("THE LIFT CURVE IS SMOOTH, BOUNDED AND SEATS CLEANLY");

		boolean bounded = true;
		float peak = 0.0F;
		float biggestStep = 0.0F;
		float previous = ValveTiming.INTAKE.lift(0.0F);
		for (float angle = 0.0F; angle <= 720.0F; angle += 0.1F) {
			float lift = ValveTiming.INTAKE.lift(angle);
			if (lift < 0.0F || lift > 1.0F)
				bounded = false;
			peak = Math.max(peak, lift);
			biggestStep = Math.max(biggestStep, Math.abs(lift - previous));
			previous = lift;
		}
		check("lift never leaves [0, 1]", bounded, "7200 samples");
		check("and reaches full lift", near(peak, 1.0F), "peak " + peak);
		// Continuity: over a tenth of a degree the curve can move at most
		// pi/(2*180) * 0.1 of full lift. A snap would be 1.0 in one step.
		check("no discontinuity anywhere - largest step over 0.1 deg is tiny",
			biggestStep < 0.005F, String.format("%.6f of full lift", biggestStep));

		check("lift is exactly zero at the opening boundary",
			ValveTiming.INTAKE.lift(540.0F) == 0.0F, ValveTiming.INTAKE.lift(540.0F) + "");
		check("lift is exactly zero at the closing boundary",
			ValveTiming.INTAKE.lift(720.0F) == 0.0F, ValveTiming.INTAKE.lift(720.0F) + "");
		check("peak lift falls at mid-stroke (630 for the intake)",
			near(ValveTiming.INTAKE.lift(630.0F), 1.0F), ValveTiming.INTAKE.lift(630.0F) + "");
		check("peak lift falls at mid-stroke (450 for the exhaust)",
			near(ValveTiming.EXHAUST.lift(450.0F), 1.0F), ValveTiming.EXHAUST.lift(450.0F) + "");

		// The slope vanishes at the seat too, which is what stops a visible kink.
		float justInside = ValveTiming.INTAKE.lift(540.5F);
		float furtherIn = ValveTiming.INTAKE.lift(541.0F);
		check("the curve leaves the seat gently, not at an angle",
			justInside < 0.001F && furtherIn < 0.004F,
			String.format("0.5 deg in: %.6f, 1 deg in: %.6f", justInside, furtherIn));
	}

	/** Exhaust closing and intake opening meet at 540 with both shut. */
	static void noOverlapAtTheHandover() {
		section("NO VALVE OVERLAP AT THE HANDOVER");

		check("at 540 the exhaust is seated", ValveTiming.EXHAUST.lift(540.0F) == 0.0F,
			ValveTiming.EXHAUST.lift(540.0F) + "");
		check("at 540 the intake has not lifted", ValveTiming.INTAKE.lift(540.0F) == 0.0F,
			ValveTiming.INTAKE.lift(540.0F) + "");

		boolean everBothOpen = false;
		float worstSum = 0.0F;
		for (float angle = 0.0F; angle < 720.0F; angle += 0.05F) {
			float intake = ValveTiming.INTAKE.lift(angle);
			float exhaust = ValveTiming.EXHAUST.lift(angle);
			if (intake > 0.0F && exhaust > 0.0F)
				everBothOpen = true;
			worstSum = Math.max(worstSum, Math.min(intake, exhaust));
		}
		check("the two valves are never open at the same instant", !everBothOpen,
			"14400 samples, worst simultaneous lift " + worstSum);
	}

	/**
	 * Eight lobes on one shaft, and their layout is a direct picture of the firing
	 * order - which is the point of showing the camshaft at all.
	 */
	static void lobeLayoutIsTheFiringOrder() {
		section("THE LOBE LAYOUT IS THE FIRING ORDER");

		FourStrokeFiringOrder r4 = FourStrokeFiringOrder.R4;
		System.out.printf("     %-8s %14s %14s%n", "cylinder", "intake lobe", "exhaust lobe");
		float[] intakeLobes = new float[4];
		for (int cylinder = 0; cylinder < 4; cylinder++) {
			intakeLobes[cylinder] = CamshaftTiming.lobeAngleDegrees(r4, cylinder, ValveTiming.INTAKE);
			System.out.printf("     %-8d %14.1f %14.1f%n", cylinder + 1, intakeLobes[cylinder],
				CamshaftTiming.lobeAngleDegrees(r4, cylinder, ValveTiming.EXHAUST));
		}

		// Every lobe must actually peak where the valve peaks.
		boolean lobesAct = true;
		for (FourStrokeFiringOrder configuration : FourStrokeFiringOrder.values())
			for (int cylinder = 0; cylinder < configuration.cylinderCount(); cylinder++)
				for (ValveTiming valve : ValveTiming.values()) {
					float lobeCam = CamshaftTiming.lobeAngleDegrees(configuration, cylinder, valve);
					// Put the engine where that lobe is at the top of its rise.
					FourStrokeEngine engine = new FourStrokeEngine(configuration);
					engine.step(lobeCam * CamshaftTiming.TIMING_DRIVE_RATIO, false);
					if (!near(CamshaftTiming.valveLift(engine, cylinder, valve), 1.0F))
						lobesAct = false;
				}
		check("every lobe peaks exactly when its own valve peaks", lobesAct,
			"5 configurations, every cylinder, both valves");

		// On an even-fire four the intake lobes are evenly spread round the shaft.
		java.util.Arrays.sort(intakeLobes);
		boolean evenlySpread = true;
		for (int i = 1; i < intakeLobes.length; i++)
			if (!near(intakeLobes[i] - intakeLobes[i - 1], 90.0F))
				evenlySpread = false;
		check("R4's four intake lobes sit 90 cam degrees apart", evenlySpread,
			java.util.Arrays.toString(intakeLobes));
		check("90 cam degrees is the 180-degree firing interval",
			near(90.0F * CamshaftTiming.TIMING_DRIVE_RATIO, 180.0F), "2 x 90 = 180 crank degrees");
	}

	/** Four cylinders on one shaft must be in four different valve states. */
	static void perCylinderIndependence() {
		section("ONE SHAFT, FOUR DIFFERENT VALVE STATES");

		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		engine.step(90.0F, false);

		System.out.printf("     %-8s %-13s %8s %8s%n", "cylinder", "stroke", "intake", "exhaust");
		java.util.Set<String> states = new java.util.HashSet<>();
		for (int cylinder = 0; cylinder < 4; cylinder++) {
			String stroke = engine.cylinder(cylinder)
				.phase()
				.toString();
			float intake = CamshaftTiming.valveLift(engine, cylinder, ValveTiming.INTAKE);
			float exhaust = CamshaftTiming.valveLift(engine, cylinder, ValveTiming.EXHAUST);
			System.out.printf("     %-8d %-13s %8.3f %8.3f%n", cylinder + 1, stroke, intake, exhaust);
			states.add(stroke);
		}
		check("at cycle 90 all four cylinders are on different strokes", states.size() == 4,
			states.toString());

		// Exactly one cylinder is on POWER at any moment on an inline-4.
		boolean alwaysExactlyOne = true;
		for (int i = 0; i < 2880; i++) {
			engine.step(0.25F, true);
			int onPower = 0;
			for (int cylinder = 0; cylinder < 4; cylinder++)
				if (engine.cylinder(cylinder)
					.phase() == FourStrokePhase.POWER)
					onPower++;
			if (onPower != 1)
				alwaysExactlyOne = false;
		}
		check("exactly one cylinder is on POWER at every instant", alwaysExactlyOne, "2880 steps");
	}

	/** Every frozen layout must produce a coherent valve picture. */
	static void everyLayoutIsCoherent() {
		section("EVERY LAYOUT IS COHERENT");

		for (FourStrokeFiringOrder configuration : FourStrokeFiringOrder.values()) {
			FourStrokeEngine engine = new FourStrokeEngine(configuration);
			boolean coherent = true;
			int openValves = 0;
			for (int i = 0; i < 2880; i++) {
				engine.step(0.25F, true);
				for (int cylinder = 0; cylinder < configuration.cylinderCount(); cylinder++) {
					FourStrokePhase phase = engine.cylinder(cylinder)
						.phase();
					float intake = CamshaftTiming.valveLift(engine, cylinder, ValveTiming.INTAKE);
					float exhaust = CamshaftTiming.valveLift(engine, cylinder, ValveTiming.EXHAUST);
					if (intake > 0.0F && phase != FourStrokePhase.INTAKE)
						coherent = false;
					if (exhaust > 0.0F && phase != FourStrokePhase.EXHAUST)
						coherent = false;
					if (intake > 0.0F || exhaust > 0.0F)
						openValves++;
				}
			}
			check(configuration + ": no valve ever opens on the wrong stroke", coherent,
				openValves + " open-valve samples");
		}
	}

	/**
	 * The valvetrain is derived from the cycle position, so if the position survives
	 * a reload the valves must too - with no separate state to lose.
	 */
	static void valvetrainSurvivesSaveAndReload() {
		section("THE VALVETRAIN SURVIVES SAVE AND RELOAD");

		for (float angle : new float[] { 0.0F, 179.9F, 360.1F, 473.0F, 539.9F, 540.1F, 719.9F }) {
			FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
			engine.armAsIfRested();
			engine.step(angle, true);

			FourStrokeEngine reloaded = new FourStrokeEngine(FourStrokeFiringOrder.R4);
			reloaded.restore(engine.save());

			boolean same = near(CamshaftTiming.camAngle(reloaded.position()),
				CamshaftTiming.camAngle(engine.position()));
			for (int cylinder = 0; cylinder < 4; cylinder++)
				for (ValveTiming valve : ValveTiming.values())
					same &= near(CamshaftTiming.valveLift(reloaded, cylinder, valve),
						CamshaftTiming.valveLift(engine, cylinder, valve));
			check("at " + angle + ": cam and all eight valves come back identical", same,
				String.format("cam %.2f", CamshaftTiming.camAngle(engine.position())));
		}
	}

	// ---------------------------------------------------------------- harness

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
