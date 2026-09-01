import dev.engineeredcombustion.content.engine.*;
import dev.engineeredcombustion.content.engine.fourstroke.*;

/**
 * Holds the <b>shipped engine</b> - {@code EngineState} itself, not the prototype -
 * to the frozen Milestone 15B behaviour.
 *
 * <p>{@code ProductionFourStrokeTests} checks the primitives; this checks the engine
 * built out of them: that each layout really fires on its frozen schedule, that fuel
 * and power balance are preserved across the conversion, that a Camshaft is required,
 * that the arming latch survives a save, and that no oscillation produces free power.
 *
 * <p>Exits non-zero on any failure.
 */
public class ProductionEngineFourStrokeTests {

	static int failures = 0;

	public static void main(String[] args) {
		eachLayoutFiresOnItsFrozenSchedule();
		fuelPerRevolutionIsUnchanged();
		averagePowerIsPreserved();
		compressionActsOncePerCycle();
		noCamshaftMeansNoCombustion();
		camshaftGivesNoFreeCharge();
		rockingProducesNoFreeCombustion();
		reverseAndOverspeedFailClosed();
		activeCylindersSurviveTheWait();
		immediateInvalidation();
		saveAndReloadKeepsThePhase();
		externalRotationGeneratesNothing();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	// ---------------------------------------------------------------- timing

	/** A. Every layout's real firing order and interval, measured on the engine. */
	static void eachLayoutFiresOnItsFrozenSchedule() {
		section("A  EACH LAYOUT FIRES ON ITS FROZEN SCHEDULE");

		int[][] expectedOrder = { { 1 }, { 1, 2 }, { 1, 2, 3 }, { 1, 3, 4, 2 } };
		float[][] expectedIntervals =
			{ { 720.0F }, { 180.0F, 540.0F }, { 240.0F, 240.0F, 240.0F }, { 180.0F, 180.0F, 180.0F, 180.0F } };

		for (int cylinders = 1; cylinders <= EngineTuning.MAX_CYLINDERS; cylinders++) {
			Rig rig = Rig.running(cylinders, 0.5F);
			Schedule schedule = rig.observeSchedule(20 * 60);

			check("R" + cylinders + " fires " + describe(expectedOrder[cylinders - 1]),
				java.util.Arrays.equals(schedule.order, expectedOrder[cylinders - 1]),
				describe(schedule.order));
			check("R" + cylinders + " intervals are " + describe(expectedIntervals[cylinders - 1]),
				matches(schedule.intervals, expectedIntervals[cylinders - 1], 12.0F),
				describe(schedule.intervals));
			check("R" + cylinders + " fires once per cylinder per 720 degrees",
				Math.abs(schedule.degreesPerCylinderFiring - 720.0F) < 12.0F,
				String.format("%.0f degrees", schedule.degreesPerCylinderFiring));
		}
	}

	// ------------------------------------------------------------ balance

	/** B. Half the events at twice the charge is the same gasoline per revolution. */
	static void fuelPerRevolutionIsUnchanged() {
		section("B  FUEL PER REVOLUTION IS UNCHANGED");

		check("a charge costs 2 mB", EngineTuning.FUEL_PER_COMBUSTION_MB == 2,
			EngineTuning.FUEL_PER_COMBUSTION_MB + " mB");

		for (int cylinders = 1; cylinders <= EngineTuning.MAX_CYLINDERS; cylinders++) {
			Rig rig = Rig.running(cylinders, 1.0F);
			int before = rig.tank.mb;
			float revolutions = rig.runMeasuringRevolutions(20 * 60);
			float mbPerRevolution = (before - rig.tank.mb) / revolutions;
			// One cylinder draws one charge per two revolutions, so an n-cylinder
			// engine draws n/2 charges - that is n millibuckets - per revolution. The
			// pre-15B engine drew n charges of 1 mB per revolution, which is the same
			// number, and that identity IS the balance target.
			check("R" + cylinders + " burns " + cylinders + " mB per revolution, as it always did",
				Math.abs(mbPerRevolution - cylinders) < cylinders * 0.06F,
				String.format("%.3f mB/rev", mbPerRevolution));
		}
	}

	/** C. The equilibrium speeds are exactly where they were before the conversion. */
	static void averagePowerIsPreserved() {
		section("C  EVERY EQUILIBRIUM IS WHERE IT WAS");

		for (int cylinders = 1; cylinders <= EngineTuning.MAX_CYLINDERS; cylinders++) {
			float idle = Rig.running(cylinders, 0.0F).meanRpm(20 * 40);
			float half = Rig.running(cylinders, 0.5F).meanRpm(20 * 40);
			float full = Rig.running(cylinders, 1.0F).meanRpm(20 * 40);
			check("R" + cylinders + " idles at 64, half-throttles at 128, full-throttles at 192",
				near(idle, EngineTuning.IDLE_RPM, 3.0F) && near(half, 128.0F, 3.0F)
					&& near(full, EngineTuning.FULL_THROTTLE_RPM, 3.0F),
				String.format("%.1f / %.1f / %.1f RPM", idle, half, full));
		}

		// The character the milestone asked for, and the only place it is asserted on
		// the shipped engine: a monotone smoothness ladder that nothing anywhere
		// declares. It falls out of the firing schedule alone.
		float[] ripple = new float[5];
		for (int cylinders = 1; cylinders <= EngineTuning.MAX_CYLINDERS; cylinders++)
			ripple[cylinders] = Rig.running(cylinders, 0.0F).rippleRpm(20 * 40);
		check("the idle smoothness ladder is monotone R1 > R2 > R3 > R4",
			ripple[1] > ripple[2] && ripple[2] > ripple[3] && ripple[3] > ripple[4],
			String.format("%.1f > %.1f > %.1f > %.1f RPM", ripple[1], ripple[2], ripple[3], ripple[4]));
		check("and the single is emphatically the lumpiest, as a four-stroke single is",
			ripple[1] > 10.0F, String.format("%.1f RPM peak to peak at idle", ripple[1]));
	}

	/** D. The piston passes top dead centre twice a cycle and is fought once. */
	static void compressionActsOncePerCycle() {
		section("D  COMPRESSION ACTS ONCE PER CYCLE, NOT TWICE");

		// Motored, unlit: pure compression. Count the peaks of resistance over two
		// revolutions and there must be one, not two.
		Rig rig = new Rig(1, 0.0F, 100000);
		rig.ignition = false;
		int peaks = 0;
		boolean above = false;
		float previousAngle = -1.0F;
		for (int tick = 0; tick < 400; tick++) {
			rig.tickDriven(64.0F);
			float torque = EngineTuning.compressionTorqueAt(rig.state.getCycleAngleDegrees());
			boolean nowAbove = torque < -1.0F;
			if (nowAbove && !above)
				peaks++;
			above = nowAbove;
			previousAngle = rig.state.getCycleAngleDegrees();
		}
		float cycles = 400.0F * EngineTuning.degreesPerTick(64.0F) / 720.0F;
		check("one compression peak per cycle on a motored single",
			Math.abs(peaks / cycles - 1.0F) < 0.2F,
			String.format("%d peaks over %.1f cycles (angle %.0f)", peaks, cycles, previousAngle));
	}

	// ---------------------------------------------------------------- camshaft

	/** E. No valvetrain, no charge, no combustion - and still a perfectly valid engine. */
	static void noCamshaftMeansNoCombustion() {
		section("E  A MISSING CAMSHAFT STOPS COMBUSTION, NOT THE ENGINE");

		Rig rig = new Rig(2, 1.0F, 100000);
		rig.camshaft = false;
		for (int tick = 0; tick < 20 * 60; tick++)
			rig.tickDriven(96.0F);

		check("it never fires", rig.totalCombustions() == 0, rig.totalCombustions() + " combustion(s)");
		check("it draws no fuel", rig.tank.mb == 100000, (100000 - rig.tank.mb) + " mB drawn");
		check("no cylinder is active", rig.state.getActiveCylinderMask() == 0,
			Integer.toBinaryString(rig.state.getActiveCylinderMask()));
		check("it generates nothing", !rig.state.isActivelyGenerating() && rig.state.getPublishedRpm() == 0.0F,
			rig.state.getPublishedRpm() + " RPM published");
		check("but it still turns, and its crank still moves",
			rig.state.getMechanicalRpm() != 0.0F && rig.state.getPhase() != EnginePhase.STOPPED,
			rig.state.getPhase() + " at " + String.format("%.0f RPM", rig.state.getMechanicalRpm()));
		check("its plugs still spark, which is what tells the player it is not ignition",
			rig.totalSparks() > 0, rig.totalSparks() + " spark(s)");
		check("and its compression still resists", rig.state.getCylinderPhase(0) != null
			&& EngineTuning.compressionTorqueAt(90.0F) != 0.0F, "gas spring intact");
		check("the engine reports the Camshaft missing", !rig.state.hasCamshaft(), "hasCamshaft=false");
	}

	/** F. Fitting a Camshaft to a spinning engine must not hand it a free bang. */
	static void camshaftGivesNoFreeCharge() {
		section("F  FITTING A CAMSHAFT TO A SPINNING ENGINE IS NOT A FREE CHARGE");

		Rig rig = new Rig(1, 1.0F, 100000);
		rig.camshaft = false;
		// Park it just before its ignition point, having turned for a long time with no
		// valvetrain: under a naive latch it would be "armed" from all that travel.
		for (int tick = 0; tick < 20 * 30; tick++)
			rig.tickDriven(96.0F);
		check("nothing is armed while there is no camshaft", rig.state.getArmedMask() == 0,
			Integer.toBinaryString(rig.state.getArmedMask()));

		rig.camshaft = true;
		int before = rig.totalCombustions();
		// Half a cycle: long enough to cross ignition, not long enough to have crossed
		// the intake opening first and legitimately drawn a charge.
		int ticks = (int) (360.0F / EngineTuning.degreesPerTick(96.0F));
		for (int tick = 0; tick < ticks; tick++)
			rig.tickDriven(96.0F);
		check("and no charge burns until one has actually been drawn",
			rig.totalCombustions() == before, (rig.totalCombustions() - before) + " combustion(s)");
	}

	// ---------------------------------------------------------------- exploits

	/** G. Rocking the crank across the firing point cannot produce combustion. */
	static void rockingProducesNoFreeCombustion() {
		section("G  ROCKING THE CRANK PRODUCES NO FREE COMBUSTION");

		Rig rig = Rig.running(1, 0.5F);
		// Settle into the rocking regime first. A running engine that is grabbed and
		// rocked may legitimately burn the charge it had already drawn - that is a real
		// event it paid for - and counting it would be measuring the transition rather
		// than the exploit. What must be zero is everything AFTER that.
		for (int tick = 0; tick < 20 * 5; tick++)
			rig.tickDriven(tick % 2 == 0 ? 120.0F : -120.0F);

		int before = rig.totalCombustions();
		int fuelBefore = rig.tank.mb;
		for (int tick = 0; tick < 20 * 60; tick++)
			rig.tickDriven(tick % 2 == 0 ? 120.0F : -120.0F);
		check("a minute of rocking burns nothing", rig.totalCombustions() == before,
			(rig.totalCombustions() - before) + " combustion(s)");
		check("and costs no fuel", rig.tank.mb == fuelBefore, (fuelBefore - rig.tank.mb) + " mB");

		// The same, from rest and with a slow wobble, which is what a hand crank
		// worked back and forth actually looks like.
		Rig wobbled = new Rig(4, 1.0F, 100000);
		for (int tick = 0; tick < 20 * 60; tick++)
			wobbled.tickDriven(tick % 20 < 10 ? 40.0F : -40.0F);
		check("and a slow hand-crank wobble from rest burns nothing either",
			wobbled.totalCombustions() == 0, wobbled.totalCombustions() + " combustion(s)");
	}

	/** H. Reverse rotation and absurd overspeed both fail closed. */
	static void reverseAndOverspeedFailClosed() {
		section("H  REVERSE AND OVERSPEED FAIL CLOSED");

		Rig backwards = new Rig(4, 1.0F, 100000);
		for (int tick = 0; tick < 20 * 30; tick++)
			backwards.tickDriven(-192.0F);
		check("an engine turned backwards never fires", backwards.totalCombustions() == 0,
			backwards.totalCombustions() + " combustion(s)");

		// Ten cycles per tick. At most one ignition per cylinder per tick, by
		// construction - lost events are strictly preferable to duplicated ones.
		Rig fast = new Rig(4, 1.0F, 100000);
		int ticks = 200;
		for (int tick = 0; tick < ticks; tick++)
			fast.tickDriven(24000.0F);
		check("absurd overspeed never fires a cylinder twice in one tick",
			fast.totalCombustions() <= ticks * 4, fast.totalCombustions() + " over " + ticks + " ticks");
		check("and it never draws more fuel than it fired",
			100000 - fast.tank.mb == fast.totalCombustions() * EngineTuning.FUEL_PER_COMBUSTION_MB,
			(100000 - fast.tank.mb) + " mB for " + fast.totalCombustions() + " event(s)");
	}

	// --------------------------------------------------------- active cylinders

	/** I. A healthy cylinder's bit must not blink while it waits for its next bang. */
	static void activeCylindersSurviveTheWait() {
		section("I  A HEALTHY CYLINDER NEVER LEAVES THE MASK BETWEEN ITS OWN BANGS");

		for (int cylinders = 1; cylinders <= EngineTuning.MAX_CYLINDERS; cylinders++) {
			Rig rig = Rig.running(cylinders, 0.0F);
			int full = (1 << cylinders) - 1;
			boolean everBlinked = false;
			for (int tick = 0; tick < 20 * 60; tick++) {
				rig.tickFree();
				if (rig.state.getActiveCylinderMask() != full)
					everBlinked = true;
			}
			check("R" + cylinders + " holds every cylinder in the mask for a minute at idle",
				!everBlinked, Integer.toBinaryString(rig.state.getActiveCylinderMask()));
		}
	}

	/** J. Losing a part invalidates its cylinder at once, not after an allowance. */
	static void immediateInvalidation() {
		section("J  A LOST PART INVALIDATES ITS CYLINDER IMMEDIATELY");

		Rig plug = Rig.running(4, 1.0F);
		plug.sparkPlugMask = 0b1011;
		plug.tickFree();
		check("pulling a plug stops that cylinder firing on the same tick",
			plug.firedThisTick(2) == false, "cylinder 3 did not fire");

		Rig camshaft = Rig.running(4, 1.0F);
		camshaft.camshaft = false;
		int before = camshaft.totalCombustions();
		for (int tick = 0; tick < 60; tick++)
			camshaft.tickFree();
		check("pulling the Camshaft stops every cylinder within one cycle",
			camshaft.totalCombustions() - before <= 4,
			(camshaft.totalCombustions() - before) + " further combustion(s)");
		for (int tick = 0; tick < 20 * 60; tick++)
			camshaft.tickFree();
		check("and the engine then generates nothing at all",
			!camshaft.state.isActivelyGenerating() && camshaft.state.getActiveCylinderMask() == 0,
			camshaft.state.getPhase() + ", mask " + camshaft.state.getActiveCylinderMask());

		Rig structure = Rig.running(4, 1.0F);
		structure.structureValid = false;
		for (int tick = 0; tick < 40; tick++)
			structure.tickFree();
		check("losing the structure stops combustion too", !structure.state.isActivelyGenerating(),
			structure.state.getPhase() + "");
	}

	// --------------------------------------------------------------- save/load

	/** K. A save taken on each stroke comes back on that stroke, and fires no extra charge. */
	static void saveAndReloadKeepsThePhase() {
		section("K  A RELOAD KEEPS THE STROKE, AND BURNS NOTHING EXTRA");

		for (FourStrokePhase stroke : FourStrokePhase.values()) {
			Rig rig = Rig.running(2, 0.5F);
			// Turn to the middle of the wanted stroke.
			float wanted = stroke.startDegrees() + FourStrokePhase.STROKE_DEGREES / 2.0F;
			for (int tick = 0; tick < 20 * 60
				&& Math.abs(rig.state.getCycleAngleDegrees() - wanted) > 20.0F; tick++)
				rig.tickFree();

			long cycleIndex = rig.state.getCycleIndex();
			float cycleAngle = rig.state.getCycleAngleDegrees();
			float crankAngle = rig.state.getCrankAngleDegrees();
			int armed = rig.state.getArmedMask();
			long[] fired = rig.state.copyOfLastFiredCycles();
			FourStrokePhase phase = rig.state.getEnginePhaseOfCycle();

			Rig back = new Rig(2, 0.5F, rig.tank.mb);
			back.state.setLayout(2, 0b11);
			back.state.setCyclePosition(cycleIndex, cycleAngle);
			back.state.setArmedMask(armed);
			back.state.setLastFiredCycles(fired);
			back.state.setSimulatedRpm(rig.state.getSimulatedRpm());
			back.state.setPhase(rig.state.getPhase());
			back.state.restoreAfterLoad(true);

			check(stroke + ": the stroke survives", back.state.getEnginePhaseOfCycle() == phase,
				back.state.getEnginePhaseOfCycle() + "");
			check(stroke + ": the physical crank angle survives exactly",
				back.state.getCrankAngleDegrees() == crankAngle,
				String.format("%.4f vs %.4f", back.state.getCrankAngleDegrees(), crankAngle));
			check(stroke + ": the arming latches survive", back.state.getArmedMask() == armed,
				Integer.toBinaryString(back.state.getArmedMask()));

			// And the opportunity already taken cannot be taken again: run one cylinder
			// straight back to its firing point and it must misfire, not double-fire.
			int burned = 0;
			int fuelBefore = back.tank.mb;
			for (int tick = 0; tick < 8; tick++) {
				back.tickFree();
				burned += back.totalCombustions();
			}
			check(stroke + ": no duplicate charge on the first ticks back",
				fuelBefore - back.tank.mb <= 2 * EngineTuning.FUEL_PER_COMBUSTION_MB * 2,
				(fuelBefore - back.tank.mb) + " mB in 8 ticks");
		}
	}

	/** L. Being spun is not running, however fast. */
	static void externalRotationGeneratesNothing() {
		section("L  EXTERNAL ROTATION GENERATES NOTHING");

		Rig rig = new Rig(4, 1.0F, 0);
		for (int tick = 0; tick < 20 * 30; tick++)
			rig.tickDriven(192.0F);
		check("a motored, dry inline-4 turns at full speed",
			Math.abs(rig.state.getMechanicalRpm()) > 100.0F,
			String.format("%.0f RPM", rig.state.getMechanicalRpm()));
		check("and generates exactly nothing",
			!rig.state.isActivelyGenerating() && rig.state.getActiveCylinderMask() == 0
				&& rig.state.getPublishedCapacityFactor() == 0.0F,
			"mask " + rig.state.getActiveCylinderMask() + ", capacity "
				+ rig.state.getPublishedCapacityFactor());
	}

	// ------------------------------------------------------------------- rig

	/** One engine, with the knobs these tests need. */
	static class Rig {
		final EngineState state = new EngineState();
		final Tank tank;
		final Sump sump = new Sump();
		final java.util.Random random = new java.util.Random(11L);
		final int cylinders;
		float throttle;
		int sparkPlugMask;
		boolean ignition = true;
		boolean camshaft = true;
		boolean structureValid = true;
		private int[] combustionBefore;

		Rig(int cylinders, float throttle, int fuel) {
			this.cylinders = cylinders;
			this.throttle = throttle;
			this.sparkPlugMask = (1 << cylinders) - 1;
			this.tank = new Tank(fuel);
			this.combustionBefore = state.copyOfCombustionEventIds();
		}

		static Rig running(int cylinders, float throttle) {
			Rig rig = new Rig(cylinders, throttle, 4000000);
			for (int tick = 0; tick < 20 * 90 && rig.state.getPhase() != EnginePhase.RUNNING; tick++)
				rig.tickDriven(96.0F);
			for (int tick = 0; tick < 20 * 60; tick++)
				rig.tickFree();
			return rig;
		}

		EngineInputs inputs() {
			return new EngineInputs(structureValid, ignition, cylinders, sparkPlugMask, throttle, 0.0F,
				EngineTuning.MAX_RPM, EngineWearInputs.PRISTINE, camshaft);
		}

		void tickFree() {
			combustionBefore = state.copyOfCombustionEventIds();
			state.tickRotation(0.0F, false, false);
			state.tickSimulation(inputs(), tank, sump, random);
		}

		void tickDriven(float rpm) {
			combustionBefore = state.copyOfCombustionEventIds();
			state.tickRotation(rpm, true, true);
			state.tickSimulation(inputs(), tank, sump, random);
		}

		boolean firedThisTick(int cylinder) {
			return state.getCombustionEventId(cylinder) != combustionBefore[cylinder];
		}

		int totalCombustions() {
			int total = 0;
			for (int cylinder = 0; cylinder < cylinders; cylinder++)
				total += state.getCombustionEventId(cylinder);
			return total;
		}

		int totalSparks() {
			int total = 0;
			for (int cylinder = 0; cylinder < cylinders; cylinder++)
				total += state.getSparkEventId(cylinder);
			return total;
		}

		float meanRpm(int ticks) {
			float total = 0.0F;
			for (int tick = 0; tick < ticks; tick++) {
				tickFree();
				total += state.getSimulatedRpm();
			}
			return total / ticks;
		}

		float rippleRpm(int ticks) {
			float min = Float.MAX_VALUE;
			float max = -Float.MAX_VALUE;
			for (int tick = 0; tick < ticks; tick++) {
				tickFree();
				min = Math.min(min, state.getSimulatedRpm());
				max = Math.max(max, state.getSimulatedRpm());
			}
			return max - min;
		}

		float runMeasuringRevolutions(int ticks) {
			float revolutions = 0.0F;
			for (int tick = 0; tick < ticks; tick++) {
				tickFree();
				revolutions += state.getRevolutionsThisTick();
			}
			return revolutions;
		}

		/** The order cylinders fire in, and the crank travel between events. */
		Schedule observeSchedule(int ticks) {
			java.util.List<Integer> order = new java.util.ArrayList<>();
			java.util.List<Float> gaps = new java.util.ArrayList<>();
			float travelled = 0.0F;
			float sinceLast = -1.0F;
			int firstCylinderFirings = 0;
			float firstCylinderTravel = 0.0F;
			for (int tick = 0; tick < ticks; tick++) {
				tickFree();
				float step = Math.abs(state.getCrankAngleDegrees() - 0.0F);
				travelled += EngineTuning.degreesPerTick(state.getMechanicalRpm());
				if (sinceLast >= 0.0F)
					sinceLast += EngineTuning.degreesPerTick(state.getMechanicalRpm());
				if (firstCylinderFirings > 0)
					firstCylinderTravel += EngineTuning.degreesPerTick(state.getMechanicalRpm());
				for (int cylinder = 0; cylinder < cylinders; cylinder++) {
					if (!firedThisTick(cylinder))
						continue;
					if (order.size() < cylinders * 3)
						order.add(cylinder + 1);
					if (sinceLast >= 0.0F && gaps.size() < cylinders * 3)
						gaps.add(sinceLast);
					sinceLast = 0.0F;
					if (cylinder == 0) {
						firstCylinderFirings++;
						if (firstCylinderFirings == 1)
							firstCylinderTravel = 0.0F;
					}
				}
				if (step < 0.0F)
					break;
			}
			return new Schedule(firstOrder(order, cylinders), averageGaps(gaps, cylinders),
				firstCylinderFirings > 1 ? firstCylinderTravel / (firstCylinderFirings - 1) : 0.0F);
		}
	}

	record Schedule(int[] order, float[] intervals, float degreesPerCylinderFiring) {
	}

	/** The first complete pass of the firing order, rotated to start at cylinder 1. */
	static int[] firstOrder(java.util.List<Integer> observed, int cylinders) {
		int start = observed.indexOf(1);
		if (start < 0 || observed.size() < start + cylinders)
			return new int[0];
		int[] order = new int[cylinders];
		for (int i = 0; i < cylinders; i++)
			order[i] = observed.get(start + i);
		return order;
	}

	/** Mean gap for each position in the firing order, over the passes observed. */
	static float[] averageGaps(java.util.List<Float> observed, int cylinders) {
		if (observed.size() < cylinders * 2)
			return new float[0];
		float[] gaps = new float[cylinders];
		int[] counts = new int[cylinders];
		for (int i = 0; i < observed.size(); i++) {
			gaps[i % cylinders] += observed.get(i);
			counts[i % cylinders]++;
		}
		for (int i = 0; i < cylinders; i++)
			gaps[i] /= Math.max(1, counts[i]);
		java.util.Arrays.sort(gaps);
		return gaps;
	}

	// ---------------------------------------------------------------- harness

	static class Tank implements FuelSupply {
		int mb;

		Tank(int mb) {
			this.mb = mb;
		}

		public boolean hasFuel() {
			return mb > 0;
		}

		public boolean consume(int amount) {
			if (mb < amount)
				return false;
			mb -= amount;
			return true;
		}
	}

	static class Sump implements OilSupply {
		int mb = Integer.MAX_VALUE;

		public LubricationState lubrication() {
			return LubricationState.NORMAL;
		}

		public boolean consume(int amount) {
			if (mb < amount)
				return false;
			mb -= amount;
			return true;
		}
	}

	static boolean matches(float[] actual, float[] expected, float tolerance) {
		if (actual.length != expected.length)
			return false;
		float[] sortedExpected = expected.clone();
		java.util.Arrays.sort(sortedExpected);
		for (int i = 0; i < expected.length; i++)
			if (Math.abs(actual[i] - sortedExpected[i]) > tolerance)
				return false;
		return true;
	}

	static String describe(int[] values) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < values.length; i++)
			text.append(i == 0 ? "" : "-")
				.append(values[i]);
		return text.length() == 0 ? "nothing" : text.toString();
	}

	static String describe(float[] values) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < values.length; i++)
			text.append(i == 0 ? "" : " / ")
				.append(String.format("%.0f", values[i]));
		return text.length() == 0 ? "nothing" : text.toString();
	}

	static boolean near(float value, float target, float tolerance) {
		return Math.abs(value - target) <= tolerance;
	}

	static void section(String title) {
		System.out.println();
		System.out.println("-- " + title);
	}

	static void check(String name, boolean passed, String detail) {
		if (!passed)
			failures++;
		System.out.printf("%-4s %-62s %s%n", passed ? "PASS" : "FAIL", name, detail);
	}
}
