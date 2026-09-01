import dev.engineeredcombustion.prototype.fourstroke.*;

/**
 * Holds the prototype 720-degree cycle to its contract: the angle algebra, the
 * separation of piston geometry from stroke, the compression waveform, and the
 * ignition crossing.
 *
 * <p>A pure test of prototype code. Neither this file nor the classes it drives are
 * reachable from the mod - the prototype lives in {@code src/prototype/java}, which
 * only this source set compiles - so nothing here can affect the playable engine.
 *
 * <p>Exits non-zero on any failure.
 */
public class FourStrokeCycleTests {

	static int failures = 0;

	public static void main(String[] args) {
		cycleWrapsAt720();
		pistonGeometryRepeatsEvery360();
		strokeDoesNotRepeatEvery360();
		exhaustTdcIsNotCompressionTdc();
		compressionActsOnOneStrokeOnly();
		gasSpringIntegratesToZero();
		pumpingIsAlwaysALoss();
		crossingSurvivesLargeTimesteps();
		crossingIsForwardOnly();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	/** A. The cycle is 720 degrees wide and wraps exactly there. */
	static void cycleWrapsAt720() {
		section("A  THE CYCLE WRAPS AT EXACTLY 720");

		check("719.9 stays put", near(FourStrokeCycle.normalizeCycle(719.9F), 719.9F),
			FourStrokeCycle.normalizeCycle(719.9F) + "");
		check("720 folds to 0", near(FourStrokeCycle.normalizeCycle(720.0F), 0.0F),
			FourStrokeCycle.normalizeCycle(720.0F) + "");
		check("721 folds to 1", near(FourStrokeCycle.normalizeCycle(721.0F), 1.0F),
			FourStrokeCycle.normalizeCycle(721.0F) + "");
		check("-1 folds to 719", near(FourStrokeCycle.normalizeCycle(-1.0F), 719.0F),
			FourStrokeCycle.normalizeCycle(-1.0F) + "");
		check("-721 folds to 719", near(FourStrokeCycle.normalizeCycle(-721.0F), 719.0F),
			FourStrokeCycle.normalizeCycle(-721.0F) + "");

		// Turning the crank through a whole cycle returns every cylinder to the angle,
		// the stroke AND the arm state it started on.
		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		for (int i = 0; i < 72; i++)
			engine.step(10.0F, false);
		check("720 degrees of stepping returns the master angle to 0",
			near(engine.cycleAngle(), 0.0F), engine.cycleAngle() + "");
	}

	/**
	 * B. Piston geometry - the thing the renderers draw - repeats every revolution,
	 * because a crank pin does.
	 */
	static void pistonGeometryRepeatsEvery360() {
		section("B  PISTON GEOMETRY REPEATS EVERY 360");

		boolean allMatch = true;
		for (float angle = 0.0F; angle < 360.0F; angle += 0.5F) {
			float first = FourStrokeCycle.physicalAngle(angle);
			float second = FourStrokeCycle.physicalAngle(angle + 360.0F);
			if (!near(first, second))
				allMatch = false;
		}
		check("physical angle at x and x+360 is identical, all x", allMatch, "720 samples");
		check("cycle 180 and cycle 540 are both top dead centre",
			near(FourStrokeCycle.physicalAngle(180.0F), FourStrokeCycle.physicalAngle(540.0F)),
			"both " + FourStrokeCycle.physicalAngle(540.0F));
		check("cycle 0 and cycle 360 are both bottom dead centre",
			near(FourStrokeCycle.physicalAngle(0.0F), FourStrokeCycle.physicalAngle(360.0F)),
			"both " + FourStrokeCycle.physicalAngle(360.0F));
	}

	/** C. The stroke, unlike the piston, does NOT repeat after 360 degrees. */
	static void strokeDoesNotRepeatEvery360() {
		section("C  THE STROKE DOES NOT REPEAT AFTER 360");

		boolean everMatched = false;
		for (float angle = 0.0F; angle < 360.0F; angle += 0.5F)
			if (FourStrokePhase.at(angle) == FourStrokePhase.at(angle + 360.0F))
				everMatched = true;
		check("no angle has the same stroke 360 degrees later", !everMatched, "720 samples");

		check("cycle   0 is COMPRESSION", FourStrokePhase.at(0.0F) == FourStrokePhase.COMPRESSION,
			FourStrokePhase.at(0.0F).toString());
		check("cycle 179 is COMPRESSION", FourStrokePhase.at(179.0F) == FourStrokePhase.COMPRESSION,
			FourStrokePhase.at(179.0F).toString());
		check("cycle 180 is POWER", FourStrokePhase.at(180.0F) == FourStrokePhase.POWER,
			FourStrokePhase.at(180.0F).toString());
		check("cycle 360 is EXHAUST", FourStrokePhase.at(360.0F) == FourStrokePhase.EXHAUST,
			FourStrokePhase.at(360.0F).toString());
		check("cycle 540 is INTAKE", FourStrokePhase.at(540.0F) == FourStrokePhase.INTAKE,
			FourStrokePhase.at(540.0F).toString());
		check("cycle 719 is INTAKE", FourStrokePhase.at(719.0F) == FourStrokePhase.INTAKE,
			FourStrokePhase.at(719.0F).toString());
	}

	/**
	 * J. The two top dead centres are the same place and different events, which is
	 * the single fact the whole 720-degree representation exists to express.
	 */
	static void exhaustTdcIsNotCompressionTdc() {
		section("J  EXHAUST TDC IS NOT COMPRESSION TDC");

		check("both are geometrically top dead centre",
			near(FourStrokeCycle.physicalAngle(180.0F), FourStrokeCycle.physicalAngle(540.0F)),
			"physical angle " + FourStrokeCycle.physicalAngle(540.0F) + " for both");
		check("but 180 ends COMPRESSION", FourStrokePhase.at(179.9F) == FourStrokePhase.COMPRESSION,
			FourStrokePhase.at(179.9F).toString());
		check("and 540 ends EXHAUST", FourStrokePhase.at(539.9F) == FourStrokePhase.EXHAUST,
			FourStrokePhase.at(539.9F).toString());
		check("the gas spring resists approaching 180",
			FourStrokeCycle.gasSpringTorque(179.0F) < 0.0F,
			FourStrokeCycle.gasSpringTorque(179.0F) + "");
		check("and does NOT resist approaching 540",
			FourStrokeCycle.gasSpringTorque(539.0F) == 0.0F,
			FourStrokeCycle.gasSpringTorque(539.0F) + "");
	}

	/** I. Compression is felt on the compression stroke and nowhere else. */
	static void compressionActsOnOneStrokeOnly() {
		section("I  COMPRESSION ACTS ON THE COMPRESSION STROKE ONLY");

		boolean resistsThroughout = true;
		for (float angle = 1.0F; angle < 180.0F; angle += 1.0F)
			if (FourStrokeCycle.gasSpringTorque(angle) >= 0.0F)
				resistsThroughout = false;
		check("resists everywhere strictly inside COMPRESSION", resistsThroughout, "179 samples");

		boolean assistsThroughout = true;
		for (float angle = 181.0F; angle < 360.0F; angle += 1.0F)
			if (FourStrokeCycle.gasSpringTorque(angle) <= 0.0F)
				assistsThroughout = false;
		check("assists everywhere strictly inside POWER (the spring returns)", assistsThroughout,
			"179 samples");

		boolean silentOnPumping = true;
		for (float angle = 360.0F; angle < 720.0F; angle += 1.0F)
			if (FourStrokeCycle.gasSpringTorque(angle) != 0.0F)
				silentOnPumping = false;
		check("is exactly zero across EXHAUST and INTAKE", silentOnPumping, "360 samples");

		check("is zero at both dead centres of the sealed half",
			FourStrokeCycle.gasSpringTorque(0.0F) == 0.0F
				&& near(FourStrokeCycle.gasSpringTorque(180.0F), 0.0F),
			"0 and 180");
	}

	/**
	 * The gas spring must take out over a cycle exactly what it puts in, or it is a
	 * second friction and every equilibrium speed the governor solved for moves.
	 */
	static void gasSpringIntegratesToZero() {
		section("   THE GAS SPRING IS A SPRING, NOT A FRICTION");

		double integral = 0.0D;
		double stepSize = 0.01D;
		for (double angle = 0.0D; angle < 720.0D; angle += stepSize)
			integral += FourStrokeCycle.gasSpringTorque((float) angle) * stepSize;
		check("integrates to zero over the whole 720-degree cycle", Math.abs(integral) < 1.0E-2D,
			String.format("%.6f", integral));
	}

	/** Pumping is a loss on every sample: it never hands energy back. */
	static void pumpingIsAlwaysALoss() {
		section("   PUMPING IS ALWAYS A LOSS");

		boolean neverAssists = true;
		for (float angle = 0.0F; angle < 720.0F; angle += 0.5F)
			if (FourStrokeCycle.pumpingTorque(angle) > 0.0F)
				neverAssists = false;
		check("never positive anywhere in the cycle", neverAssists, "1440 samples");

		boolean silentWhenSealed = true;
		for (float angle = 0.0F; angle < 360.0F; angle += 1.0F)
			if (FourStrokeCycle.pumpingTorque(angle) != 0.0F)
				silentWhenSealed = false;
		check("zero while the cylinder is sealed", silentWhenSealed, "360 samples");

		// The mean loss the equilibrium solution has to absorb. The design document
		// gives it as -(intakePeak + exhaustPeak)/8; this waveform carries a peak of 1
		// on both strokes, so the figure to expect here is -1/4. Measured rather than
		// asserted, because it is the number the combustion torque has to make up.
		double integral = 0.0D;
		double stepSize = 0.01D;
		for (double angle = 0.0D; angle < 720.0D; angle += stepSize)
			integral += FourStrokeCycle.pumpingTorque((float) angle) * stepSize;
		double mean = integral / 720.0D;
		check("mean over the cycle is -(intake + exhaust)/8, i.e. -1/4 at unit peaks",
			Math.abs(mean + 0.25D) < 1.0E-3D, String.format("%.6f", mean));
	}

	/**
	 * K. A tick advances the crank by a finite jump and routinely steps straight over
	 * the ignition angle. The crossing must still catch it.
	 */
	static void crossingSurvivesLargeTimesteps() {
		section("K  EVENT CROSSING SURVIVES A TIMESTEP THAT SKIPS THE ANGLE");

		// A step from 170 to 250 never lands on 180 and must still count.
		check("a step straight over 180 is caught",
			FourStrokeCycle.crossedForward(250.0F, 80.0F, 180.0F), "170 -> 250");
		check("a step that stops just short is not",
			!FourStrokeCycle.crossedForward(179.0F, 80.0F, 180.0F), "99 -> 179");
		check("landing exactly on the angle counts",
			FourStrokeCycle.crossedForward(180.0F, 80.0F, 180.0F), "100 -> 180");
		check("and the very next step does not count it again",
			!FourStrokeCycle.crossedForward(260.0F, 80.0F, 180.0F), "180 -> 260");
		check("a step wrapping through 720 onto the angle is caught",
			FourStrokeCycle.crossedForward(200.0F, 300.0F, 180.0F), "620 -> 200 (wrapped)");
		check("a step longer than the whole cycle always counts",
			FourStrokeCycle.crossedForward(0.0F, 800.0F, 180.0F), "delta 800");

		// Exhaustive: at every step size an engine could plausibly see, a single
		// cylinder must fire exactly once per 720 degrees of travel and never twice.
		// The engine is turned through one whole cycle first so that it is running
		// rather than starting - a cold cylinder is disarmed and has to inhale before
		// its first bang, which the next check measures on purpose.
		// The interval is measured rather than the count, because a step size that does
		// not divide the travel makes any fixed-window count off by one at the edges
		// for reasons that have nothing to do with the engine. The gap between
		// consecutive ignitions is exact: 720 degrees, detected up to one step late
		// because a crossing is only noticed at the end of the step that made it.
		boolean allExact = true;
		String detail = "";
		for (float degreesPerStep : new float[] { 0.5F, 1.0F, 7.0F, 31.0F, 62.0F, 77.0F, 179.0F, 359.0F }) {
			FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R1);
			float travelled = 0.0F;
			float previousIgnition = Float.NaN;
			int intervals = 0;
			while (travelled < 8000.0F) {
				boolean fired = engine.step(degreesPerStep, true) != 0;
				travelled += degreesPerStep;
				if (!fired)
					continue;
				if (!Float.isNaN(previousIgnition)) {
					float gap = travelled - previousIgnition;
					intervals++;
					if (Math.abs(gap - 720.0F) > degreesPerStep) {
						allExact = false;
						detail += degreesPerStep + " deg/step gave a " + gap + " degree gap; ";
					}
				}
				previousIgnition = travelled;
			}
			if (intervals < 9) {
				allExact = false;
				detail += degreesPerStep + " deg/step fired only " + (intervals + 1) + " times; ";
			}
		}
		check("consecutive ignitions are 720 degrees apart at every step size", allExact,
			allExact ? "0.5 to 359 deg/step, 9+ intervals each" : detail);

		// A cold engine is one bang short over the same travel, and that is the design
		// working rather than failing: it starts with an empty cylinder, so it must
		// reach the start of intake (540) and only then compression TDC (180 of the
		// next cycle) - 900 degrees to the first bang. This is the starting behaviour
		// section 15 of the milestone asks about, measured.
		FourStrokeEngine cold = new FourStrokeEngine(FourStrokeFiringOrder.R1);
		int degreesToFirstBang = 0;
		while (cold.totalIgnitions() == 0 && degreesToFirstBang < 2000) {
			cold.step(1.0F, true);
			degreesToFirstBang++;
		}
		check("a cold R1 at cycle angle 0 needs 900 degrees to its first bang",
			degreesToFirstBang == 900, degreesToFirstBang + " degrees");
	}

	/** Turning the engine backwards never fires it. */
	static void crossingIsForwardOnly() {
		section("   BACKWARDS ROTATION NEVER CROSSES");

		check("a negative step never crosses", !FourStrokeCycle.crossedForward(100.0F, -80.0F, 180.0F),
			"180 -> 100");
		check("a zero step never crosses", !FourStrokeCycle.crossedForward(180.0F, 0.0F, 180.0F),
			"stationary at 180");

		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		for (int i = 0; i < 400; i++)
			engine.step(-7.0F, true);
		check("an R4 turned backwards through four cycles never fires", engine.totalIgnitions() == 0,
			engine.totalIgnitions() + " ignitions in -2800 degrees");
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
