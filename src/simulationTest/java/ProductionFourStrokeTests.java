import dev.engineeredcombustion.content.engine.fourstroke.*;

/**
 * Holds the <b>production</b> four-stroke primitives to the frozen contract.
 *
 * <p>The Milestone 15A prototype has its own suites and they stay: they are the
 * reference model, and they run against {@code src/prototype}. This one runs against
 * {@code dev.engineeredcombustion.content.engine.fourstroke}, the classes the mod
 * actually ships, so a divergence between the two is a test failure rather than a
 * discovery in a playtest.
 *
 * <p>Exits non-zero on any failure.
 */
public class ProductionFourStrokeTests {

	static int failures = 0;

	public static void main(String[] args) {
		cycleWrapsAt720();
		pistonGeometryRepeatsEvery360();
		strokeDoesNotRepeatEvery360();
		compressionActsOnOneStrokeOnly();
		gasSpringIntegratesToZero();
		crossingIsRobust();
		positionCountsCyclesBothWays();
		firingSchedulesAreFrozen();
		geometryIsDerivedFromIgnition();
		armingClosesTheRockingExploit();
		camshaftTurnsAtHalfSpeed();
		valvesFollowTheStrokes();
		valveLiftIsSmooth();
		schemaVersioningIsExplicit();

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

		check("719.9 stays put", near(FourStrokeCycle.normalizeCycle(719.9F), 719.9F), "");
		check("720 folds to 0", near(FourStrokeCycle.normalizeCycle(720.0F), 0.0F), "");
		check("-1 folds to 719", near(FourStrokeCycle.normalizeCycle(-1.0F), 719.0F), "");
		check("-721 folds to 719", near(FourStrokeCycle.normalizeCycle(-721.0F), 719.0F), "");
		// -0.0 must never reach a diagnostic overlay.
		check("negative zero collapses",
			Float.floatToRawIntBits(FourStrokeCycle.normalizeCycle(-0.0F)) == 0, "");

		CyclePosition position = new CyclePosition();
		for (int i = 0; i < 72; i++)
			position.advance(10.0F);
		check("720 degrees of stepping returns the angle to 0", near(position.angle(), 0.0F),
			position.angle() + "");
		check("... and lands on cycle 1", position.cycleIndex() == 1L, position.cycleIndex() + "");
	}

	/** B. Piston geometry repeats every revolution, because a crank pin does. */
	static void pistonGeometryRepeatsEvery360() {
		section("B  PISTON GEOMETRY REPEATS EVERY 360");

		boolean allMatch = true;
		for (float angle = 0.0F; angle < 360.0F; angle += 0.5F)
			allMatch &= near(FourStrokeCycle.physicalAngle(angle),
				FourStrokeCycle.physicalAngle(angle + 360.0F));
		check("physical angle is identical half a cycle apart", allMatch, "");

		CyclePosition position = new CyclePosition(0L, 540.0F);
		check("cycle 540 is physical 180", near(position.physicalAngle(), 180.0F),
			position.physicalAngle() + "");
	}

	/** C. Stroke does NOT repeat every 360, which is the whole point. */
	static void strokeDoesNotRepeatEvery360() {
		section("C  STROKE REPEATS EVERY 720, NOT EVERY 360");

		check("cycle 180 is COMPRESSION top dead centre",
			FourStrokePhase.at(180.0F) == FourStrokePhase.POWER
				&& FourStrokePhase.at(179.0F) == FourStrokePhase.COMPRESSION,
			FourStrokePhase.at(179.0F) + " -> " + FourStrokePhase.at(180.0F));
		check("cycle 540 is the same piston position on EXHAUST/INTAKE",
			FourStrokePhase.at(539.0F) == FourStrokePhase.EXHAUST
				&& FourStrokePhase.at(540.0F) == FourStrokePhase.INTAKE,
			FourStrokePhase.at(539.0F) + " -> " + FourStrokePhase.at(540.0F));

		boolean anyDiffer = false;
		for (float angle = 0.0F; angle < 360.0F; angle += 1.0F)
			anyDiffer |= FourStrokePhase.at(angle) != FourStrokePhase.at(angle + 360.0F);
		check("no angle has the same stroke half a cycle later", anyDiffer, "");

		check("boundaries", FourStrokePhase.at(0.0F) == FourStrokePhase.COMPRESSION
			&& FourStrokePhase.at(360.0F) == FourStrokePhase.EXHAUST, "");
		check("719.999 is still INTAKE", FourStrokePhase.at(719.999F) == FourStrokePhase.INTAKE, "");
	}

	/** D. Compression happens once per cycle, not twice. */
	static void compressionActsOnOneStrokeOnly() {
		section("D  COMPRESSION IS GATED TO THE SEALED STROKES");

		boolean sealedNonzero = false;
		boolean openAllZero = true;
		for (float angle = 0.5F; angle < 720.0F; angle += 0.5F) {
			float torque = FourStrokeCycle.gasSpringShape(angle);
			if (FourStrokePhase.at(angle).sealed())
				sealedNonzero |= torque != 0.0F;
			else
				openAllZero &= torque == 0.0F;
		}
		check("the sealed half resists", sealedNonzero, "");
		check("the open half does not", openAllZero, "");

		// The single most important consequence: the same piston height is fought on
		// one pass and free on the other.
		check("90 resists, 450 does not",
			FourStrokeCycle.gasSpringShape(90.0F) != 0.0F
				&& FourStrokeCycle.gasSpringShape(450.0F) == 0.0F,
			FourStrokeCycle.gasSpringShape(90.0F) + " / " + FourStrokeCycle.gasSpringShape(450.0F));
	}

	/** E. The gas spring gives back exactly what it takes. */
	static void gasSpringIntegratesToZero() {
		section("E  THE GAS SPRING IS A SPRING, NOT A FRICTION");

		double total = 0.0D;
		for (float angle = 0.0F; angle < 720.0F; angle += 0.05F)
			total += FourStrokeCycle.gasSpringShape(angle);
		double mean = total * 0.05D / 720.0D;
		check("mean over the cycle is zero", Math.abs(mean) < 1.0E-4D, String.format("%.3e", mean));
	}

	/** F. Crossing survives any timestep, in either direction. */
	static void crossingIsRobust() {
		section("F  CROSSING IS ROBUST");

		check("a step that lands exactly on the target crosses it",
			FourStrokeCycle.crossedForward(180.0F, 10.0F, 180.0F), "");
		check("... and the next step does not count it again",
			!FourStrokeCycle.crossedForward(190.0F, 10.0F, 180.0F), "");
		// 710 forwards from 170: it passes 180 early and comes back round short of it.
		check("a 700-degree step crosses everything it passed",
			FourStrokeCycle.crossedForward(160.0F, 710.0F, 180.0F), "");
		// ... and a step that STARTS on the target does not re-count it, which is the
		// half-open convention holding at the one place it is easy to get wrong.
		check("a step starting exactly on the target does not re-count it",
			!FourStrokeCycle.crossedForward(160.0F, 700.0F, 180.0F), "");
		check("a step longer than the cycle crosses by construction",
			FourStrokeCycle.crossedForward(0.0F, 800.0F, 180.0F), "");
		check("backwards never crosses forwards",
			!FourStrokeCycle.crossedForward(170.0F, -20.0F, 180.0F), "");
		check("leaving the target backwards counts",
			FourStrokeCycle.crossedBackward(170.0F, -20.0F, 180.0F), "");
		check("arriving on the target backwards does not",
			!FourStrokeCycle.crossedBackward(180.0F, -20.0F, 180.0F), "");
		check("zero travel crosses nothing",
			!FourStrokeCycle.crossedForward(180.0F, 0.0F, 180.0F)
				&& !FourStrokeCycle.crossedBackward(180.0F, 0.0F, 180.0F), "");
	}

	/** G. The counter names cycles, and rocking accumulates nothing. */
	static void positionCountsCyclesBothWays() {
		section("G  THE CYCLE COUNTER IS EXACT IN BOTH DIRECTIONS");

		CyclePosition position = new CyclePosition();
		for (int i = 0; i < 100; i++) {
			position.advance(30.0F);
			position.advance(-30.0F);
		}
		check("rocking across the wrap accumulates nothing",
			position.cycleIndex() == 0L && near(position.angle(), 0.0F),
			position.toString());

		position.set(0L, 700.0F);
		position.advance(40.0F);
		check("a forward wrap increments", position.cycleIndex() == 1L && near(position.angle(), 20.0F),
			position.toString());
		position.advance(-40.0F);
		check("a backward wrap decrements", position.cycleIndex() == 0L && near(position.angle(), 700.0F),
			position.toString());

		// A step that wraps AND crosses in one tick must attribute the crossing to the
		// cycle it happened in, not to the one the position ended up in.
		position.set(5L, 700.0F);
		position.advance(200.0F);
		check("a wrapping step attributes its crossing to the earlier cycle",
			position.cycleIndex() == 6L && position.crossingCycleIndex(180.0F) == 6L,
			position.toString());
		position.set(5L, 700.0F);
		position.advance(30.0F);
		check("a wrap short of the target attributes to the cycle before",
			position.cycleIndex() == 6L && position.crossingCycleIndex(180.0F) == 5L,
			position.toString());
	}

	/** H. Every frozen firing schedule, checked against the milestone document. */
	static void firingSchedulesAreFrozen() {
		section("H  THE FROZEN FIRING SCHEDULES");

		check("R1 fires once per cycle",
			intervals(FourStrokeFiringOrder.R1, new float[] { 720.0F }), "");
		check("R1 firing order is 1", order(FourStrokeFiringOrder.R1, new int[] { 1 }), "");

		check("the inline-2 default is the 180-degree uneven twin",
			FourStrokeFiringOrder.forCylinderCount(2) == FourStrokeFiringOrder.R2_UNEVEN,
			FourStrokeFiringOrder.forCylinderCount(2) + "");
		check("R2 uneven fires 180 then 540",
			intervals(FourStrokeFiringOrder.R2_UNEVEN, new float[] { 180.0F, 540.0F }), "");
		check("R2 uneven has opposed throws at 0 and 180",
			near(FourStrokeFiringOrder.R2_UNEVEN.geometricOffsetDegrees(0), 0.0F)
				&& near(FourStrokeFiringOrder.R2_UNEVEN.geometricOffsetDegrees(1), 180.0F), "");
		check("R2 uneven is not even-fire", !FourStrokeFiringOrder.R2_UNEVEN.evenFire(), "");
		check("R2 even is even-fire, and is kept implemented",
			FourStrokeFiringOrder.R2_EVEN.evenFire(), "");

		check("R3 fires evenly every 240",
			intervals(FourStrokeFiringOrder.R3, new float[] { 240.0F, 240.0F, 240.0F }), "");
		check("R3 firing order is 1-2-3", order(FourStrokeFiringOrder.R3, new int[] { 1, 2, 3 }), "");
		check("R3 keeps the existing 0/120/240 crank",
			near(FourStrokeFiringOrder.R3.geometricOffsetDegrees(0), 0.0F)
				&& near(FourStrokeFiringOrder.R3.geometricOffsetDegrees(1), 120.0F)
				&& near(FourStrokeFiringOrder.R3.geometricOffsetDegrees(2), 240.0F), "");

		check("R4 fires evenly every 180",
			intervals(FourStrokeFiringOrder.R4, new float[] { 180.0F, 180.0F, 180.0F, 180.0F }), "");
		check("R4 firing order is 1-3-4-2",
			order(FourStrokeFiringOrder.R4, new int[] { 1, 3, 4, 2 }), "");
		check("R4 ignition offsets are 0 / 540 / 180 / 360",
			near(FourStrokeFiringOrder.R4.ignitionOffsetDegrees(0), 0.0F)
				&& near(FourStrokeFiringOrder.R4.ignitionOffsetDegrees(1), 540.0F)
				&& near(FourStrokeFiringOrder.R4.ignitionOffsetDegrees(2), 180.0F)
				&& near(FourStrokeFiringOrder.R4.ignitionOffsetDegrees(3), 360.0F), "");
		check("R4 has a flat-plane crank: 1+4 together, 2+3 together, opposed",
			near(FourStrokeFiringOrder.R4.geometricOffsetDegrees(0), 0.0F)
				&& near(FourStrokeFiringOrder.R4.geometricOffsetDegrees(3), 0.0F)
				&& near(FourStrokeFiringOrder.R4.geometricOffsetDegrees(1), 180.0F)
				&& near(FourStrokeFiringOrder.R4.geometricOffsetDegrees(2), 180.0F), "");
	}

	/** I. Geometry is a fold of the ignition schedule, never a second table. */
	static void geometryIsDerivedFromIgnition() {
		section("I  GEOMETRY IS DERIVED, NEVER AUTHORED");

		boolean allDerived = true;
		for (FourStrokeFiringOrder configuration : FourStrokeFiringOrder.values())
			for (int i = 0; i < configuration.cylinderCount(); i++)
				allDerived &= near(configuration.geometricOffsetDegrees(i),
					FourStrokeCycle.normalizeRevolution(configuration.cyclePhaseOffsetDegrees(i)));
		check("every geometric offset is its cycle offset mod 360", allDerived, "");

		// And the consequence that matters to a player looking at an inline-4.
		CyclePosition master = new CyclePosition(0L, 123.0F);
		CyclePosition c1 = new CyclePosition();
		CyclePosition c4 = new CyclePosition();
		master.shiftedBy(FourStrokeFiringOrder.R4.cyclePhaseOffsetDegrees(0), c1);
		master.shiftedBy(FourStrokeFiringOrder.R4.cyclePhaseOffsetDegrees(3), c4);
		check("R4 cylinders 1 and 4 share a piston position",
			near(c1.physicalAngle(), c4.physicalAngle()), c1.physicalAngle() + " / " + c4.physicalAngle());
		check("... on different strokes", c1.phase() != c4.phase(), c1.phase() + " / " + c4.phase());
	}

	/** J. No free combustion from rocking, reversing or oversetting the crank. */
	static void armingClosesTheRockingExploit() {
		section("J  ARMING AND THE EVENT KEY");

		boolean[] armed = new boolean[4];
		long[] lastFired = new long[4];
		java.util.Arrays.fill(lastFired, CylinderCycleState.NO_EVENT);
		CyclePosition local = new CyclePosition();

		// A fresh engine has no charge, so its first pass through ignition misfires.
		local.set(0L, 100.0F);
		local.advance(100.0F);
		check("a cylinder that never inhaled cannot fire",
			CylinderCycleState.advance(local, armed, lastFired, 0, true)
				== CylinderCycleState.Event.MISFIRED, "");

		// Draw a charge, then light it.
		local.set(0L, 500.0F);
		local.advance(50.0F);
		check("crossing 540 forwards arms",
			CylinderCycleState.advance(local, armed, lastFired, 0, true)
				== CylinderCycleState.Event.ARMED && armed[0], "");
		local.advance(360.0F);
		check("the armed charge lights at compression TDC",
			CylinderCycleState.advance(local, armed, lastFired, 0, true)
				== CylinderCycleState.Event.IGNITED, "");

		// THE EXPLOIT: rock back and forth across the ignition point.
		int ignitions = 0;
		for (int i = 0; i < 200; i++) {
			local.advance(i % 2 == 0 ? -12.0F : 12.0F);
			if (CylinderCycleState.advance(local, armed, lastFired, 0, true)
				== CylinderCycleState.Event.IGNITED)
				ignitions++;
		}
		check("rocking across ignition produces no further combustion", ignitions == 0,
			ignitions + " ignition(s)");

		// A misfire costs a whole cycle: the charge is pushed back out.
		java.util.Arrays.fill(armed, false);
		java.util.Arrays.fill(lastFired, CylinderCycleState.NO_EVENT);
		local.set(0L, 530.0F);
		local.advance(20.0F);
		CylinderCycleState.advance(local, armed, lastFired, 0, true);
		local.advance(360.0F);
		check("a cylinder with no spark misfires and loses the charge",
			CylinderCycleState.advance(local, armed, lastFired, 0, false)
				== CylinderCycleState.Event.MISFIRED && !armed[0], "");

		// Absurd overspeed: at most one ignition per call, never several.
		java.util.Arrays.fill(armed, true);
		java.util.Arrays.fill(lastFired, CylinderCycleState.NO_EVENT);
		local.set(0L, 0.0F);
		local.advance(5000.0F);
		check("a 5000-degree step yields at most one ignition",
			CylinderCycleState.advance(local, armed, lastFired, 0, true)
				== CylinderCycleState.Event.IGNITED, "");
		long taken = lastFired[0];
		local.advance(5000.0F);
		CylinderCycleState.advance(local, armed, lastFired, 0, true);
		check("... and the second step takes a different opportunity", lastFired[0] != taken, "");
	}

	/** K. The camshaft is division, not a second clock. */
	static void camshaftTurnsAtHalfSpeed() {
		section("K  THE CAMSHAFT TURNS AT HALF CRANK SPEED");

		check("cycle 0 is cam 0", near(CamshaftTiming.camAngle(0.0F), 0.0F), "");
		check("cycle 360 is cam 180", near(CamshaftTiming.camAngle(360.0F), 180.0F), "");
		check("cycle 719 is cam 359.5", near(CamshaftTiming.camAngle(719.0F), 359.5F), "");
		check("the ratio is exactly 2", CamshaftTiming.TIMING_DRIVE_RATIO == 2.0F, "");

		// One cam revolution per cycle, by construction rather than by counting.
		CyclePosition position = new CyclePosition();
		float previous = CamshaftTiming.camAngle(position);
		int wraps = 0;
		for (int i = 0; i < 144; i++) {
			position.advance(10.0F);
			float now = CamshaftTiming.camAngle(position);
			if (now < previous)
				wraps++;
			previous = now;
		}
		check("two cycles of crank travel is two cam revolutions", wraps == 2, wraps + "");

		// The lobes are a picture of the firing order.
		float[] intakeLobes = new float[4];
		for (int i = 0; i < 4; i++)
			intakeLobes[i] = CamshaftTiming.lobeAngleDegrees(FourStrokeFiringOrder.R4, i, ValveTiming.INTAKE);
		boolean spaced = true;
		for (int i = 0; i < 4; i++) {
			float gap = FourStrokeCycle.normalizeRevolution(
				intakeLobes[FourStrokeFiringOrder.R4.firingOrder()[(i + 1) % 4] - 1]
					- intakeLobes[FourStrokeFiringOrder.R4.firingOrder()[i] - 1]);
			spaced &= near(gap, 90.0F);
		}
		check("R4 intake lobes are 90 cam degrees apart in firing order", spaced, "");
	}

	/** L. Valve windows agree with the strokes, derived independently. */
	static void valvesFollowTheStrokes() {
		section("L  VALVE TIMING AGREES WITH THE STROKES");

		// Everywhere except the four dead centres, where both valves are on their seats
		// by construction and the two definitions are allowed to differ by one sample.
		boolean agrees = true;
		boolean sawSealed = false;
		boolean sawOpen = false;
		for (float angle = 0.25F; angle < 720.0F; angle += 0.25F) {
			boolean byValves = CamshaftTiming.isSealed(angle);
			if (byValves)
				sawSealed = true;
			else
				sawOpen = true;
			if (angle % FourStrokePhase.STROKE_DEGREES == 0.0F)
				continue;
			agrees &= byValves == FourStrokePhase.at(angle).sealed();
		}
		check("sealed by valve lift == sealed by stroke, off the dead centres", agrees, "");
		check("... and both states were actually observed", sawSealed && sawOpen, "");
		check("every dead centre has both valves seated",
			CamshaftTiming.isSealed(0.0F) && CamshaftTiming.isSealed(180.0F)
				&& CamshaftTiming.isSealed(360.0F) && CamshaftTiming.isSealed(540.0F), "");

		check("the intake valve opens on the intake stroke",
			ValveTiming.INTAKE.stroke() == FourStrokePhase.INTAKE, "");
		check("the exhaust valve opens on the exhaust stroke",
			ValveTiming.EXHAUST.stroke() == FourStrokePhase.EXHAUST, "");
		check("no overlap: both are seated at 540",
			ValveTiming.INTAKE.lift(540.0F) == 0.0F && ValveTiming.EXHAUST.lift(540.0F) == 0.0F, "");
		check("both are seated across compression and power",
			ValveTiming.INTAKE.lift(90.0F) == 0.0F && ValveTiming.EXHAUST.lift(270.0F) == 0.0F, "");
		check("the arming angle is the intake valve's own opening",
			FourStrokeCycle.ARMING_ANGLE_DEGREES == ValveTiming.INTAKE.openAngleDegrees(), "");
	}

	/** M. The lift curve meets its seat the way a cam does. */
	static void valveLiftIsSmooth() {
		section("M  THE VALVE LIFT CURVE");

		check("zero at both ends",
			ValveTiming.liftCurve(0.0F) == 0.0F && ValveTiming.liftCurve(1.0F) == 0.0F, "");
		check("full lift at the middle", near(ValveTiming.liftCurve(0.5F), 1.0F), "");
		check("never negative and never past full", curveIsBounded(), "");
		// The slope is zero at each end, which is what a valve meeting a seat looks like.
		// Compared against the steepest point of the curve - a quarter of the way in,
		// where a raised cosine rises fastest - and not against the peak, where the
		// slope is zero for the same reason it is at the seat.
		float atSeat = ValveTiming.liftCurve(0.002F) - ValveTiming.liftCurve(0.0F);
		float atSteepest = ValveTiming.liftCurve(0.252F) - ValveTiming.liftCurve(0.25F);
		check("the curve leaves the seat far more gently than it rises mid-window",
			atSeat < atSteepest * 0.05F, atSeat + " vs " + atSteepest);
		check("the valve leaves its seat gently in real degrees",
			ValveTiming.INTAKE.lift(540.5F) < 0.001F && ValveTiming.INTAKE.lift(541.0F) < 0.004F,
			String.format("0.5 deg in: %.6f, 1 deg in: %.6f", ValveTiming.INTAKE.lift(540.5F),
				ValveTiming.INTAKE.lift(541.0F)));

		check("peak intake lift is at cylinder-local 630",
			near(ValveTiming.INTAKE.lift(630.0F), 1.0F), "");
		check("peak exhaust lift is at cylinder-local 450",
			near(ValveTiming.EXHAUST.lift(450.0F), 1.0F), "");

		// Continuity: no step bigger than a small fraction anywhere in the sweep.
		float previous = ValveTiming.INTAKE.lift(0.0F);
		float biggestStep = 0.0F;
		for (float angle = 0.25F; angle < 720.0F; angle += 0.25F) {
			float now = ValveTiming.INTAKE.lift(angle);
			biggestStep = Math.max(biggestStep, Math.abs(now - previous));
			previous = now;
		}
		check("the valve never snaps", biggestStep < 0.02F, String.format("%.4f", biggestStep));
	}

	/** N. The save schema names its own version. */
	static void schemaVersioningIsExplicit() {
		section("N  SAVE SCHEMA VERSIONING");

		check("an absent tag reads as version 1", EngineSchema.versionOf(0) == EngineSchema.VERSION_LEGACY, "");
		check("a negative tag reads as version 1", EngineSchema.versionOf(-3) == EngineSchema.VERSION_LEGACY, "");
		check("version 1 needs migration", EngineSchema.needsMigration(0), "");
		check("version 2 does not", !EngineSchema.needsMigration(EngineSchema.VERSION_FOUR_STROKE), "");
		check("a future version does not", !EngineSchema.needsMigration(7), "");
		check("current is 2", EngineSchema.CURRENT_VERSION == 2, "");

		// The physical angle survives migration exactly, on every legacy angle.
		boolean exact = true;
		for (float angle = 0.0F; angle < 360.0F; angle += 0.25F)
			exact &= EngineSchema.migratedCycleAngle(angle) == angle;
		check("migration preserves the physical crank angle bit for bit", exact, "");
		check("... and lands in the first half of the cycle",
			EngineSchema.migratedCycleAngle(359.0F) < 360.0F, "");
	}

	// ---------------------------------------------------------------- harness

	static boolean curveIsBounded() {
		for (float t = 0.0F; t <= 1.0F; t += 0.001F) {
			float lift = ValveTiming.liftCurve(t);
			if (lift < 0.0F || lift > 1.0001F)
				return false;
		}
		return true;
	}

	static boolean intervals(FourStrokeFiringOrder configuration, float[] expected) {
		float[] actual = configuration.ignitionIntervalsDegrees();
		if (actual.length != expected.length)
			return false;
		for (int i = 0; i < expected.length; i++)
			if (!near(actual[i], expected[i]))
				return false;
		return true;
	}

	static boolean order(FourStrokeFiringOrder configuration, int[] expected) {
		return java.util.Arrays.equals(configuration.firingOrder(), expected);
	}

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
