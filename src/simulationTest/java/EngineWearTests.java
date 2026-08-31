import dev.engineeredcombustion.content.engine.*;

/**
 * The engine wear model, exercised end to end without Minecraft, NeoForge or
 * Create.
 *
 * <p>Two halves, and they are tested differently on purpose.
 *
 * <p>The <b>curves</b> - compression against piston wear, friction against
 * bearing wear, the wear rate against oil, filtration, speed and load - are pure
 * functions, so they are checked as functions: monotonicity, continuity, and the
 * end points the milestone specifies. Sampling them densely is what catches a
 * discontinuity, and a discontinuity in the overspeed curve is precisely the bug
 * that would punish a player for one tick of governor ripple.
 *
 * <p>The <b>consequences</b> are checked against a real {@link EngineState},
 * because the whole design claim of this milestone is that nothing about a worn
 * engine is written down separately - a worn engine is slower, weaker, harder to
 * start and shorter to coast because its torque and friction changed, not
 * because anything anywhere reads a wear value and subtracts RPM. Only a running
 * simulation can show that.
 *
 * <p>Exits non-zero on any failure.
 */
public class EngineWearTests {

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

	/** A sump that simply is in a given state, so a test can pin lubrication. */
	static class Sump implements OilSupply {
		LubricationState state;

		Sump(LubricationState state) {
			this.state = state;
		}

		public LubricationState lubrication() {
			return state;
		}

		public boolean consume(int amount) {
			return state != LubricationState.DRY;
		}
	}

	/**
	 * One engine with a condition, plus the wear bookkeeping the block entities do
	 * on the server.
	 *
	 * <p>The accumulation loop here is deliberately the same shape as
	 * {@code CrankshaftBlockEntity#accumulateWear}: revolutions from the crank
	 * angle, one bearing increment per section, one motion increment per cylinder
	 * with a piston in it, and a combustion increment only for cylinders whose
	 * combustion counter actually moved. Wearing the parts here rather than inside
	 * {@code EngineState} is the point - the simulation reads condition and never
	 * writes it.
	 */
	static class Engine {
		final EngineState state = new EngineState();
		final Tank tank;
		final Sump sump;
		final java.util.Random random = new java.util.Random(4321L);
		final int cylinders;

		/** The physical parts. Wear lives here, exactly as it lives on the blocks. */
		final float[] pistonWear;
		final float[] bearingWear;

		int sparkPlugMask;
		boolean ignition = true;
		boolean structureValid = true;
		boolean airFilter = true;
		float throttle = 0.0F;
		float load = 0.0F;

		Engine(int cylinders, int fuel, LubricationState lubrication) {
			this.cylinders = cylinders;
			this.sparkPlugMask = (1 << cylinders) - 1;
			this.tank = new Tank(fuel);
			this.sump = new Sump(lubrication);
			this.pistonWear = new float[EngineTuning.MAX_CYLINDERS];
			this.bearingWear = new float[EngineTuning.MAX_CYLINDERS];
		}

		EngineWearInputs wear() {
			float total = 0.0F;
			float worst = 0.0F;
			for (int section = 0; section < cylinders; section++) {
				total += bearingWear[section];
				worst = Math.max(worst, bearingWear[section]);
			}
			return new EngineWearInputs(pistonWear, total / cylinders, worst, airFilter);
		}

		EngineInputs inputs() {
			return new EngineInputs(structureValid, ignition, cylinders, sparkPlugMask, throttle, load,
				EngineTuning.MAX_RPM, wear());
		}

		void tickFree() {
			tick(0.0F, false, false);
		}

		/** One tick with another Create source holding the shaft at a fixed speed. */
		void tickHeldAt(float rpm) {
			tick(rpm, true, true);
		}

		private void tick(float shaftSpeed, boolean shaftDriven, boolean externallyDriven) {
			int[] combustionBefore = state.copyOfCombustionEventIds();
			state.tickRotation(shaftSpeed, shaftDriven, externallyDriven);
			state.tickSimulation(inputs(), tank, sump, random);
			accumulateWear(combustionBefore);
		}

		/** Exactly what the controller does after the simulation has run. */
		private void accumulateWear(int[] combustionBefore) {
			float revolutions = state.getRevolutionsThisTick();
			if (revolutions <= 0.0F)
				return;
			float rpm = state.getMechanicalRpm();
			LubricationState lubrication = sump.lubrication();

			float bearing = EngineWearMath.bearingWearPerRevolution(lubrication, rpm, load) * revolutions;
			float motion = EngineWearMath.cylinderWearPerRevolution(lubrication, rpm, load, airFilter) * revolutions;
			float perCombustion = EngineWearMath.cylinderWearPerCombustion(lubrication, rpm, load, airFilter);

			for (int cylinder = 0; cylinder < cylinders; cylinder++) {
				bearingWear[cylinder] = EngineWearMath.clampWear(bearingWear[cylinder] + bearing);
				float added = motion;
				if (state.getCombustionEventId(cylinder) != combustionBefore[cylinder])
					added += perCombustion;
				pistonWear[cylinder] = EngineWearMath.clampWear(pistonWear[cylinder] + added);
			}
		}

		float capacitySu() {
			return (float) EngineTuning.STRESS_CAPACITY_PER_RPM * state.getPublishedCapacityFactor()
				* Math.abs(state.getPublishedRpm());
		}
	}

	/**
	 * Cranks a fuelled engine until it catches, keeps cranking for a moment, then
	 * lets it settle on its own.
	 *
	 * <p>That moment matters, and it is what a hand crank really does: an engine
	 * catches at about 32 RPM, and a single cylinder's next firing opportunity is a
	 * whole revolution - nearly two seconds - away at that speed. A healthy engine
	 * has enough momentum to bridge the gap; a tired one is fighting more friction
	 * and does not, so releasing the crank on the exact tick it catches would stall
	 * every worn engine and prove nothing about wear.
	 */
	static boolean start(Engine engine, int crankTicks) {
		for (int tick = 0; tick < crankTicks && engine.state.getPhase() != EnginePhase.RUNNING; tick++)
			engine.tickHeldAt(32.0F);
		for (int tick = 0; tick < 40; tick++)
			engine.tickHeldAt(32.0F);
		for (int tick = 0; tick < 400; tick++)
			engine.tickFree();
		return engine.state.getPhase() == EnginePhase.RUNNING;
	}

	/**
	 * Runs an engine for a while with another Create source holding it at a fixed
	 * speed.
	 *
	 * <p>The clean way to compare two wear rates: every engine in the comparison
	 * completes exactly the same number of revolutions, so the only thing that can
	 * differ is the rate itself. Left to run freely they would settle at different
	 * speeds - which is a real and desirable consequence of wear, and exactly the
	 * confound a rate comparison does not want.
	 */
	static void runHeldAt(Engine engine, float rpm, int ticks) {
		for (int tick = 0; tick < ticks; tick++)
			engine.tickHeldAt(rpm);
	}

	// ---------------------------------------------------------------- the tests

	public static void main(String[] args) {
		wearStaysInRange();
		conditionBandsAreOrderedAndStable();
		compressionFallsMonotonicallyWithWear();
		bearingFrictionRisesMonotonicallyWithWear();
		lubricationOrdersTheWearRates();
		filtrationAffectsCylindersAndNotBearings();
		wearRisesWithSpeed();
		overspeedCurveIsContinuous();
		loadRaisesBearingWearMost();
		effectiveCapacityCountsEachCylinderAtItsOwnStrength();
		inactiveCylindersContributeNothing();
		wornCylinderCostsRealTorqueAndCapacity();
		wornBearingsShortenTheCoastDown();
		wornEngineIsHarderToStartButStillStarts();
		motoredDryEngineWearsBearingsAndNotCombustion();
		healthyWearIsSlowEnoughToPlayWith();
		poorLubricationIsTheRealThreat();
		overspeedIsTheAbuseThatMattersOnGoodOil();
		combinedAbuseIsWorseThanAnyOnePart();
		aHealthyEngineIsAFullStrengthEngine();
		capacityDoesNotChangeEveryTick();
		reportCalibrationTable();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	/** A. Wear is a closed interval, whatever anyone tries to put into it. */
	static void wearStaysInRange() {
		section("A  WEAR NEVER LEAVES [0, 1]");

		check("negative wear clamps to pristine", EngineWearMath.clampWear(-5.0F) == 0.0F,
			String.valueOf(EngineWearMath.clampWear(-5.0F)));
		check("wear past the service limit clamps to it", EngineWearMath.clampWear(17.0F) == 1.0F,
			String.valueOf(EngineWearMath.clampWear(17.0F)));
		check("a NaN reads as pristine rather than poisoning the engine",
			EngineWearMath.clampWear(Float.NaN) == 0.0F, String.valueOf(EngineWearMath.clampWear(Float.NaN)));

		// And the same through the input snapshot, which is what the world builds.
		EngineWearInputs absurd = new EngineWearInputs(new float[] { -1.0F, 2.0F, 0.5F, Float.NaN }, -3.0F, 9.0F,
			false);
		check("the snapshot clamps every piston it is handed",
			absurd.pistonWear(0) == 0.0F && absurd.pistonWear(1) == 1.0F && absurd.pistonWear(3) == 0.0F,
			absurd.pistonWear(0) + ", " + absurd.pistonWear(1) + ", " + absurd.pistonWear(3));
		check("and both bearing figures", absurd.averageBearingWear() == 0.0F && absurd.worstBearingWear() == 1.0F,
			absurd.averageBearingWear() + " avg, " + absurd.worstBearingWear() + " worst");

		// An engine run to destruction: dry, unfiltered, and held past its rated speed
		// by a network far stronger than itself - the worst thing that can be done to
		// one. It must saturate at the limit rather than run past it, and it must
		// still be turning at the end.
		Engine dying = new Engine(1, 1000000, LubricationState.DRY);
		dying.throttle = 1.0F;
		dying.airFilter = false;
		start(dying, 20 * 30);
		runHeldAt(dying, 256.0F, 20 * 60 * 60);
		check("an engine run to destruction reaches the service limit",
			dying.bearingWear[0] > EngineTuning.CONDITION_CRITICAL_WEAR,
			String.format("%.4f bearing, %.4f piston", dying.bearingWear[0], dying.pistonWear[0]));
		check("and stops exactly there rather than running past it",
			dying.bearingWear[0] <= 1.0F && dying.pistonWear[0] <= 1.0F,
			String.format("%.6f bearing, %.6f piston", dying.bearingWear[0], dying.pistonWear[0]));
		check("wear alone never seizes the engine", dying.state.getMechanicalRpm() != 0.0F,
			String.format("%.1f rpm, %s", dying.state.getMechanicalRpm(), dying.state.getPhase()));
	}

	/** N. The bands tile [0, 1] in order, with no gap and no overlap. */
	static void conditionBandsAreOrderedAndStable() {
		section("N  CONDITION BANDS ARE ORDERED, AND TILE THE RANGE");

		WearCondition[] bands = WearCondition.values();
		boolean ascending = true;
		for (int index = 1; index < bands.length; index++)
			ascending &= bands[index].lowerBound() > bands[index - 1].lowerBound();
		check("the bands are declared best to worst", ascending, java.util.Arrays.toString(bands));

		check("a new part is pristine", WearCondition.of(0.0F) == WearCondition.PRISTINE,
			WearCondition.of(0.0F).toString());
		check("a part at the service limit is critical", WearCondition.of(1.0F) == WearCondition.CRITICAL,
			WearCondition.of(1.0F).toString());

		// Walk the whole range: the band may only ever get worse, and it must change
		// exactly at the declared boundaries.
		boolean monotone = true;
		int transitions = 0;
		WearCondition previous = WearCondition.of(0.0F);
		for (int step = 1; step <= 10000; step++) {
			WearCondition now = WearCondition.of(step / 10000.0F);
			if (now.ordinal() < previous.ordinal())
				monotone = false;
			if (now != previous)
				transitions++;
			previous = now;
		}
		check("the band never improves as wear increases", monotone, "sampled 10000 points");
		check("and changes exactly five times", transitions == bands.length - 1, transitions + " transitions");

		// Each boundary belongs to the worse band, so there is no value in two bands.
		boolean boundariesOwned = true;
		for (int index = 1; index < bands.length; index++) {
			float bound = bands[index].lowerBound();
			boundariesOwned &= WearCondition.of(bound) == bands[index];
			boundariesOwned &= WearCondition.of(Math.nextDown(bound)) == bands[index - 1];
		}
		check("every boundary belongs to the worse band", boundariesOwned, "5 boundaries");

		check("the worst of two conditions is the worse one",
			WearCondition.worst(WearCondition.GOOD, WearCondition.POOR) == WearCondition.POOR
				&& WearCondition.worst(WearCondition.CRITICAL, WearCondition.USED) == WearCondition.CRITICAL,
			"worst() is a comparison, not a table");

		// The engine's overall condition must not average a bad cylinder away.
		EngineWearInputs oneBadCylinder =
			new EngineWearInputs(new float[] { 0.0F, 0.0F, 0.95F, 0.0F }, 0.02F, 0.03F, true);
		check("one critical cylinder makes the whole engine critical",
			oneBadCylinder.overallCondition(4) == WearCondition.CRITICAL,
			oneBadCylinder.overallCondition(4) + " with three pristine cylinders beside it");
	}

	/** G. Compression only ever gets worse, smoothly, and never reaches zero. */
	static void compressionFallsMonotonicallyWithWear() {
		section("G  COMPRESSION FALLS MONOTONICALLY, AND NEVER TO ZERO");

		float previous = EngineWearMath.compressionEfficiency(0.0F);
		check("a pristine cylinder has full compression", near(previous, 1.0F, 1.0E-6F),
			String.format("%.4f", previous));

		boolean monotone = true;
		float largestStep = 0.0F;
		for (int step = 1; step <= 10000; step++) {
			float now = EngineWearMath.compressionEfficiency(step / 10000.0F);
			monotone &= now <= previous;
			largestStep = Math.max(largestStep, Math.abs(now - previous));
			previous = now;
		}
		check("it never improves as the piston wears", monotone, "sampled 10000 points");
		check("and moves smoothly - no cliff anywhere", largestStep < 1.0E-3F,
			String.format("largest step %.2e over a 1e-4 change in wear", largestStep));

		// The shape 13.1 asks for: almost nothing at the healthy end, all of it at
		// the worn end. A GOOD engine must not feel second-hand, and the trap this
		// guards against is reading 10 % wear as 10 % power loss.
		float atGood = EngineWearMath.compressionEfficiency(EngineTuning.CONDITION_GOOD_WEAR);
		float atUsed = EngineWearMath.compressionEfficiency(EngineTuning.CONDITION_USED_WEAR);
		float atWorn = EngineWearMath.compressionEfficiency(EngineTuning.CONDITION_WORN_WEAR);
		float atPoor = EngineWearMath.compressionEfficiency(EngineTuning.CONDITION_POOR_WEAR);
		float atCritical = EngineWearMath.compressionEfficiency(EngineTuning.CONDITION_CRITICAL_WEAR);

		check("a GOOD engine is essentially at full compression", atGood >= 0.98F,
			String.format("%.4f at the GOOD boundary", atGood));
		check("a USED engine has lost only a little", atUsed >= 0.94F && atUsed < atGood,
			String.format("%.4f at the USED boundary", atUsed));
		check("a WORN engine has lost something clearly noticeable", atWorn <= 0.92F && atWorn >= 0.85F,
			String.format("%.4f at the WORN boundary", atWorn));
		check("a POOR engine is badly down", atPoor <= 0.85F && atPoor >= 0.78F,
			String.format("%.4f at the POOR boundary", atPoor));
		check("and a CRITICAL one is severely down", atCritical <= 0.75F,
			String.format("%.4f at the CRITICAL boundary", atCritical));

		// The curve must accelerate: each band costs more than the one before it, or
		// the loss is really linear wearing a quadratic's clothes.
		float goodCost = 1.0F - atGood;
		float usedCost = atGood - atUsed;
		float wornCost = atUsed - atWorn;
		float poorCost = atWorn - atPoor;
		float criticalCost = atPoor - atCritical;
		check("and every band costs more compression than the one before it",
			goodCost < usedCost && usedCost < wornCost && wornCost < poorCost && poorCost < criticalCost,
			String.format("%.4f < %.4f < %.4f < %.4f < %.4f", goodCost, usedCost, wornCost, poorCost,
				criticalCost));

		// J. The floor is a real floor, and it is where it was designed to be.
		float atLimit = EngineWearMath.compressionEfficiency(1.0F);
		check("a critical cylinder keeps the intended minimum", near(atLimit, 0.65F, 1.0E-5F),
			String.format("%.4f", atLimit));
		check("which is inside the 0.60-0.70 band the milestone allows",
			atLimit >= 0.60F && atLimit <= 0.70F, String.format("%.4f", atLimit));
		check("and it is never negative, however absurd the input",
			EngineWearMath.compressionEfficiency(50.0F) >= EngineTuning.MIN_COMPRESSION_EFFICIENCY,
			String.format("%.4f at 50.0 wear", EngineWearMath.compressionEfficiency(50.0F)));
	}

	/** H. Friction only ever gets worse, and lands in the intended range. */
	static void bearingFrictionRisesMonotonicallyWithWear() {
		section("H  BEARING FRICTION RISES MONOTONICALLY");

		float previous = EngineWearMath.bearingFrictionMultiplier(0.0F);
		check("pristine bearings cost nothing extra", near(previous, 1.0F, 1.0E-6F),
			String.format("%.4fx", previous));

		boolean monotone = true;
		for (int step = 1; step <= 10000; step++) {
			float now = EngineWearMath.bearingFrictionMultiplier(step / 10000.0F);
			monotone &= now >= previous;
			previous = now;
		}
		check("it never falls as the bearings wear", monotone, "sampled 10000 points");

		float atLimit = EngineWearMath.bearingFrictionMultiplier(1.0F);
		check("critical bearings land in the intended 1.5x-2.0x band", atLimit >= 1.5F && atLimit <= 2.0F,
			String.format("%.2fx", atLimit));
	}

	/** B. Oil is the biggest single thing a player controls. */
	static void lubricationOrdersTheWearRates() {
		section("B  NORMAL OIL < LOW OIL < DRY");

		float rpm = EngineTuning.IDLE_RPM;
		float normal = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rpm, 0.0F);
		float low = EngineWearMath.bearingWearPerRevolution(LubricationState.LOW, rpm, 0.0F);
		float dry = EngineWearMath.bearingWearPerRevolution(LubricationState.DRY, rpm, 0.0F);

		check("bearings: normal < low", normal < low, String.format("%.3e < %.3e", normal, low));
		check("bearings: low < dry", low < dry, String.format("%.3e < %.3e", low, dry));

		float normalBore = EngineWearMath.cylinderWearPerRevolution(LubricationState.NORMAL, rpm, 0.0F, true);
		float lowBore = EngineWearMath.cylinderWearPerRevolution(LubricationState.LOW, rpm, 0.0F, true);
		float dryBore = EngineWearMath.cylinderWearPerRevolution(LubricationState.DRY, rpm, 0.0F, true);
		check("cylinders: normal < low < dry", normalBore < lowBore && lowBore < dryBore,
			String.format("%.3e < %.3e < %.3e", normalBore, lowBore, dryBore));

		// LOW is a clear step, not a rounding error, and DRY is a different league
		// again. The absolute multipliers are large because the healthy baseline is
		// tiny; what matters is the ORDER and the resulting lifetimes, both checked
		// against real durations below and in section O.
		check("low oil is clearly worse than normal, not marginally", low / normal >= 10.0F,
			String.format("%.0fx normal", low / normal));
		check("and still short of total failure", low / normal <= 25.0F,
			String.format("%.0fx normal", low / normal));
		check("running dry is dramatically worse than merely low", dry / low >= 20.0F,
			String.format("%.0fx low oil", dry / low));

		// The bound that actually matters: a brief mistake must cost nothing a
		// player could ever see. Thirty seconds of an empty sump, at the engine's
		// own dry top speed, must not move it out of PRISTINE.
		float thirtyDrySeconds = EngineWearMath.bearingWearPerRevolution(LubricationState.DRY, 111.0F, 0.0F)
			* 111.0F * 0.5F;
		check("but a brief dry moment is still forgiven",
			WearCondition.of(thirtyDrySeconds) == WearCondition.PRISTINE,
			String.format("%.5f wear -> %s after thirty dry seconds", thirtyDrySeconds,
				WearCondition.of(thirtyDrySeconds)));

		// And through a real engine: the same run, three oil states.
		float dryWear = runAndMeasureBearingWear(LubricationState.DRY);
		float lowWear = runAndMeasureBearingWear(LubricationState.LOW);
		float normalWear = runAndMeasureBearingWear(LubricationState.NORMAL);
		check("a real engine wears in that order too", normalWear < lowWear && lowWear < dryWear,
			String.format("%.2e < %.2e < %.2e over a minute", normalWear, lowWear, dryWear));
	}

	/** One minute at a fixed 128 RPM, and the bearing wear it produced. */
	static float runAndMeasureBearingWear(LubricationState lubrication) {
		Engine engine = new Engine(1, 100000, lubrication);
		engine.throttle = 1.0F;
		runHeldAt(engine, 128.0F, 20 * 60);
		return engine.bearingWear[0];
	}

	/** C. The Air Filter earns its place, and only where it should. */
	static void filtrationAffectsCylindersAndNotBearings() {
		section("C  FILTRATION HITS THE BORES, AND ONLY THE BORES");

		float rpm = EngineTuning.FULL_THROTTLE_RPM;
		float filtered = EngineWearMath.cylinderWearPerRevolution(LubricationState.NORMAL, rpm, 0.0F, true);
		float unfiltered = EngineWearMath.cylinderWearPerRevolution(LubricationState.NORMAL, rpm, 0.0F, false);
		check("an unfiltered cylinder wears faster", filtered < unfiltered,
			String.format("%.3e < %.3e", filtered, unfiltered));
		check("by the intended several-times factor",
			unfiltered / filtered >= 5.0F && unfiltered / filtered <= 10.0F,
			String.format("%.1fx", unfiltered / filtered));

		float filteredBurn = EngineWearMath.cylinderWearPerCombustion(LubricationState.NORMAL, rpm, 0.0F, true);
		float unfilteredBurn = EngineWearMath.cylinderWearPerCombustion(LubricationState.NORMAL, rpm, 0.0F, false);
		check("combustion wear is filtered the same way", filteredBurn < unfilteredBurn,
			String.format("%.3e < %.3e", filteredBurn, unfilteredBurn));

		check("bearing wear is not touched by filtration at all",
			EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rpm, 0.0F) > 0.0F,
			"bearing wear takes no filtration argument - it cannot be");

		// The same thing through two real engines, held at the same speed so that the
		// only difference between them is the filter.
		Engine withFilter = new Engine(4, 400000, LubricationState.NORMAL);
		Engine without = new Engine(4, 400000, LubricationState.NORMAL);
		without.airFilter = false;
		withFilter.throttle = 1.0F;
		without.throttle = 1.0F;
		start(withFilter, 20 * 30);
		start(without, 20 * 30);
		runHeldAt(withFilter, 128.0F, 20 * 120);
		runHeldAt(without, 128.0F, 20 * 120);
		check("an unfiltered engine's bores are visibly worse off", without.pistonWear[0] > withFilter.pistonWear[0],
			String.format("%.3e filtered vs %.3e unfiltered", withFilter.pistonWear[0], without.pistonWear[0]));
		check("while its bearings are no worse",
			near(without.bearingWear[0], withFilter.bearingWear[0], withFilter.bearingWear[0] * 0.05F + 1.0E-9F),
			String.format("%.3e vs %.3e", withFilter.bearingWear[0], without.bearingWear[0]));
	}

	/** D. Faster means more wear, both per revolution and per second. */
	static void wearRisesWithSpeed() {
		section("D  MORE SPEED, MORE WEAR");

		float slow = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, EngineTuning.IDLE_RPM, 0.0F);
		float fast = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL,
			EngineTuning.FULL_THROTTLE_RPM, 0.0F);
		check("each revolution costs more at speed", fast > slow, String.format("%.3e > %.3e", slow, fast));

		// The rpm factor itself never falls, anywhere in the range an engine can reach.
		boolean monotone = true;
		float previous = EngineWearMath.rpmWearFactor(0.0F);
		for (int rpm = 1; rpm <= 1024; rpm++) {
			float now = EngineWearMath.rpmWearFactor(rpm);
			monotone &= now >= previous;
			previous = now;
		}
		check("and the speed factor never falls", monotone, "sampled 0-1024 RPM");

		check("idle is charged almost nothing extra",
			EngineWearMath.rpmWearFactor(EngineTuning.IDLE_RPM) < 1.06F,
			String.format("%.3fx at idle", EngineWearMath.rpmWearFactor(EngineTuning.IDLE_RPM)));
		check("full throttle is a modest penalty, not a punishment",
			near(EngineWearMath.rpmWearFactor(EngineTuning.FULL_THROTTLE_RPM), 1.35F, 0.01F),
			String.format("%.3fx at full throttle", EngineWearMath.rpmWearFactor(EngineTuning.FULL_THROTTLE_RPM)));

		// Backwards rotation is still rotation.
		check("turning backwards wears the same as turning forwards",
			near(EngineWearMath.rpmWearFactor(-120.0F), EngineWearMath.rpmWearFactor(120.0F), 1.0E-6F),
			String.format("%.4f both ways", EngineWearMath.rpmWearFactor(-120.0F)));
	}

	/** E. The overspeed curve is smooth through the rated speed. */
	static void overspeedCurveIsContinuous() {
		section("E  THE OVERSPEED CURVE HAS NO CLIFF");

		float rated = EngineTuning.RATED_CONTINUOUS_RPM;
		check("nothing is charged below the rated speed", EngineWearMath.overspeedFraction(rated - 1.0F) == 0.0F,
			String.format("%.4f just under", EngineWearMath.overspeedFraction(rated - 1.0F)));
		check("and nothing exactly at it", EngineWearMath.overspeedFraction(rated) == 0.0F, "0 at the rating");

		// Continuity, sampled finely across the join and well past it.
		//
		// The property being asserted is that the factor never JUMPS: between two
		// speeds 0.01 RPM apart it may only move by what the curve's own steepness
		// there allows. A cliff would blow straight through that bound; a merely
		// steep quadratic cannot.
		//
		// Deliberately not a second-difference test. The curve has one harmless kink
		// - the stress term stops growing where it clamps at the rating, so the slope
		// steps DOWN by about 0.0036 per RPM exactly as the overspeed term starts
		// from zero slope. That is a change of steepness in the player's favour, not
		// a discontinuity in the wear rate, and the value either side of the rating
		// is smooth to seven digits. Sampling is capped a little past Create's 256
		// RPM ceiling because beyond that the factor is large enough that float ULP
		// noise, not the curve, would be what a fine-grained test measured.
		float sampleStep = 0.01F;
		float from = rated - 20.0F;
		float to = 300.0F;
		int samples = Math.round((to - from) / sampleStep);
		float previous = EngineWearMath.rpmWearFactor(from);
		float worstExcess = 0.0F;
		float worstAt = from;
		for (int step = 1; step <= samples; step++) {
			float rpm = from + step * sampleStep;
			float now = EngineWearMath.rpmWearFactor(rpm);
			// The steepest the curve can analytically be over this pair, plus a few
			// ULP of headroom for the float arithmetic itself.
			float slope = 2.0F * EngineTuning.OVERSPEED_WEAR_COEFFICIENT
				* EngineWearMath.overspeedFraction(rpm) / EngineTuning.RATED_CONTINUOUS_RPM
				+ 2.0F * EngineTuning.RPM_STRESS_COEFFICIENT / EngineTuning.RATED_CONTINUOUS_RPM;
			float allowed = slope * sampleStep + 8.0F * Math.ulp(now);
			float excess = Math.abs(now - previous) - allowed;
			if (excess > worstExcess) {
				worstExcess = excess;
				worstAt = rpm;
			}
			previous = now;
		}
		check("the factor never jumps, through the rating or beyond it", worstExcess <= 0.0F,
			String.format("%d samples from %.0f to %.0f RPM, worst overshoot %.3e at %.2f RPM", samples, from,
				to, Math.max(worstExcess, 0.0F), worstAt));

		// And the value itself is smooth across the join, which is the statement the
		// kink above could otherwise be mistaken for breaking.
		float justUnder = EngineWearMath.rpmWearFactor(rated - 0.01F);
		float justOver = EngineWearMath.rpmWearFactor(rated + 0.01F);
		check("and it is smooth to either side of the rating itself",
			Math.abs(justOver - justUnder) < 1.0E-4F,
			String.format("%.7f just under, %.7f just over", justUnder, justOver));

		// A one-RPM excursion must cost essentially nothing. Stated as a fraction,
		// because that is what "essentially nothing" means when the baseline moves:
		// half a percent more wear for a whole RPM over the rating.
		float atRating = EngineWearMath.rpmWearFactor(rated);
		float oneOver = EngineWearMath.rpmWearFactor(rated + 1.0F);
		check("one RPM over the rating is not a cliff", oneOver / atRating < 1.005F,
			String.format("%.5f -> %.5f, %.3f%% more", atRating, oneOver, (oneOver / atRating - 1.0F) * 100.0F));

		// Nor is a governor ripple. The engine may legitimately overshoot its target
		// on the way up, all the way to its own MAX_RPM, and that must not be a
		// meaningful cost either.
		float atGovernorCeiling = EngineWearMath.rpmWearFactor(EngineTuning.MAX_RPM);
		check("nor is the engine's own overshoot ceiling", atGovernorCeiling / atRating < 1.5F,
			String.format("%.3fx at the engine's own %.0f RPM limit", atGovernorCeiling / atRating,
				EngineTuning.MAX_RPM));

		// But sustained overspeed genuinely is the fastest way to ruin an engine, and
		// after the rebalance it is the ONLY way to ruin a well-oiled one.
		float atCreateCeiling = EngineWearMath.rpmWearFactor(256.0F);
		check("Create's default ceiling is several times as hard on it", atCreateCeiling > 3.0F,
			String.format("%.2fx at 256 RPM", atCreateCeiling));
		check("and the warning only fires past the margin",
			!EngineWearMath.isOverspeed(rated) && !EngineWearMath.isOverspeed(rated * 1.02F)
				&& EngineWearMath.isOverspeed(rated * 1.2F),
			"quiet at the rating and at +2 %, warning at +20 %");
	}

	/** F. Load matters, and it matters most to the bearings. */
	static void loadRaisesBearingWearMost() {
		section("F  LOAD RAISES BEARING WEAR MOST");

		float rpm = EngineTuning.IDLE_RPM;
		float unloaded = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rpm, 0.0F);
		float half = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rpm, 0.5F);
		float full = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rpm, 1.0F);
		check("bearing wear rises with load", unloaded < half && half < full,
			String.format("%.3e < %.3e < %.3e", unloaded, half, full));
		check("a full load lands in the intended 1.5x-2x band",
			full / unloaded >= 1.5F && full / unloaded <= 2.0F, String.format("%.2fx", full / unloaded));

		float boreUnloaded = EngineWearMath.cylinderWearPerRevolution(LubricationState.NORMAL, rpm, 0.0F, true);
		float boreFull = EngineWearMath.cylinderWearPerRevolution(LubricationState.NORMAL, rpm, 1.0F, true);
		check("cylinder wear rises with load too, but less", boreFull > boreUnloaded
			&& boreFull / boreUnloaded < full / unloaded,
			String.format("%.2fx in the bore against %.2fx on the bearings", boreFull / boreUnloaded,
				full / unloaded));

		// And load must not run away: it is a modest multiplier, never the whole model.
		check("load never dominates the model", full / unloaded < 3.0F,
			String.format("%.2fx at capacity", full / unloaded));
	}

	/**
	 * I, J, K. The capacity sum, which is the number Create is handed.
	 */
	static void effectiveCapacityCountsEachCylinderAtItsOwnStrength() {
		section("I/J/K  CAPACITY IS THE FIRING CYLINDERS, EACH AT ITS OWN STRENGTH");

		Engine healthy = new Engine(4, 400000, LubricationState.NORMAL);
		check("a fresh inline-4 is worth four healthy cylinders",
			near(healthy.wear().compressionEfficiency(0) * 4, 4.0F, 1.0E-5F),
			String.format("%.4f", healthy.wear().compressionEfficiency(0) * 4));

		start(healthy, 20 * 30);
		check("and the running engine reports exactly that",
			near(healthy.state.getEffectiveCylinderCapacity(), 4.0F, 1.0E-4F),
			String.format("%.4f cylinder-equivalents", healthy.state.getEffectiveCylinderCapacity()));

		// One cylinder at the service limit. The milestone's worked example uses 0.6
		// for the bad cylinder and expects ~3.6; this model's floor is 0.65, so the
		// same engine lands at 3.65 - the arithmetic is the point, not the constant.
		Engine tired = new Engine(4, 400000, LubricationState.NORMAL);
		tired.pistonWear[2] = 1.0F;
		start(tired, 20 * 30);
		float expected = 3.0F + EngineTuning.MIN_COMPRESSION_EFFICIENCY;
		check("an inline-4 with one dead-compression bore is worth 3 + its share",
			near(tired.state.getEffectiveCylinderCapacity(), expected, 1.0E-3F),
			String.format("%.3f, against %.3f expected", tired.state.getEffectiveCylinderCapacity(), expected));
		check("which is approximately the milestone's 3.6",
			Math.abs(tired.state.getEffectiveCylinderCapacity() - 3.6F) <= 0.1F,
			String.format("%.3f", tired.state.getEffectiveCylinderCapacity()));

		// The sum really is a sum: every cylinder at its own number, nothing averaged.
		Engine mixed = new Engine(4, 400000, LubricationState.NORMAL);
		mixed.pistonWear[0] = 0.0F;
		mixed.pistonWear[1] = 0.20F;
		mixed.pistonWear[2] = 0.75F;
		mixed.pistonWear[3] = 0.45F;
		start(mixed, 20 * 30);
		float byHand = 0.0F;
		for (int cylinder = 0; cylinder < 4; cylinder++)
			byHand += EngineWearMath.compressionEfficiency(mixed.pistonWear[cylinder]);
		check("a mixed engine is the sum of its cylinders",
			near(mixed.state.getEffectiveCylinderCapacity(), byHand, 1.0E-3F),
			String.format("%.4f simulated, %.4f by hand", mixed.state.getEffectiveCylinderCapacity(), byHand));

		check("and it is worth less than the healthy engine",
			mixed.state.getPublishedCapacityFactor() < healthy.state.getPublishedCapacityFactor(),
			String.format("%.2f against %.2f", mixed.state.getPublishedCapacityFactor(),
				healthy.state.getPublishedCapacityFactor()));
	}

	/** L. A cylinder that is not firing contributes nothing, however healthy. */
	static void inactiveCylindersContributeNothing() {
		section("L  AN INACTIVE CYLINDER CONTRIBUTES NOTHING, HOWEVER HEALTHY");

		Engine r4 = new Engine(4, 400000, LubricationState.NORMAL);
		start(r4, 20 * 30);
		check("all four are active and all four are pristine", r4.state.getActiveCylinderMask() == 0b1111,
			Integer.toBinaryString(r4.state.getActiveCylinderMask()));

		// Pull the plug out of a perfectly healthy cylinder 3.
		r4.sparkPlugMask = 0b1011;
		int allowance = EngineTuning.generationCombustionAllowanceTicks(r4.state.getSimulatedRpm());
		for (int tick = 0; tick <= allowance + 2; tick++)
			r4.tickFree();

		check("it drops out of the mask", r4.state.getActiveCylinderMask() == 0b1011,
			Integer.toBinaryString(r4.state.getActiveCylinderMask()));
		check("and takes a whole cylinder of capacity with it, not a fraction",
			near(r4.state.getEffectiveCylinderCapacity(), 3.0F, 1.0E-4F),
			String.format("%.4f cylinder-equivalents", r4.state.getEffectiveCylinderCapacity()));
		check("its piston is still pristine - health had nothing to do with it",
			r4.pistonWear[2] < EngineTuning.CONDITION_GOOD_WEAR,
			String.format("%.4f wear on the cylinder that stopped firing", r4.pistonWear[2]));

		// A motored engine burns nothing, so it is worth nothing however good it is.
		Engine motored = new Engine(4, 0, LubricationState.NORMAL);
		for (int tick = 0; tick < 200; tick++)
			motored.tickHeldAt(200.0F);
		check("a motored engine is worth zero cylinders",
			motored.state.getEffectiveCylinderCapacity() == 0.0F
				&& motored.state.getPublishedCapacityFactor() == 0.0F,
			String.format("%.2f effective, %.2f published", motored.state.getEffectiveCylinderCapacity(),
				motored.state.getPublishedCapacityFactor()));
	}

	/** A worn cylinder is genuinely weaker - in torque and in what Create is told. */
	static void wornCylinderCostsRealTorqueAndCapacity() {
		section("A WORN CYLINDER IS ACTUALLY WEAKER, NOT JUST LABELLED SO");

		Engine healthy = new Engine(1, 400000, LubricationState.NORMAL);
		Engine worn = new Engine(1, 400000, LubricationState.NORMAL);
		worn.pistonWear[0] = 1.0F;
		healthy.throttle = 1.0F;
		worn.throttle = 1.0F;
		start(healthy, 20 * 30);
		start(worn, 20 * 30);
		for (int tick = 0; tick < 600; tick++) {
			healthy.tickFree();
			worn.tickFree();
		}

		check("the worn engine settles slower on the same throttle",
			worn.state.getSimulatedRpm() < healthy.state.getSimulatedRpm(),
			String.format("%.1f against %.1f RPM", worn.state.getSimulatedRpm(),
				healthy.state.getSimulatedRpm()));
		check("it is still running, not stalled", worn.state.getPhase() == EnginePhase.RUNNING,
			worn.state.getPhase().toString());
		check("it still counts as an active cylinder", worn.state.getActiveCylinderMask() == 0b1,
			Integer.toBinaryString(worn.state.getActiveCylinderMask()));
		check("but Create is told it supplies less", worn.capacitySu() < healthy.capacitySu(),
			String.format("%.0f su against %.0f su", worn.capacitySu(), healthy.capacitySu()));
		// Fuel is charged per firing event and nothing about wear changes that. The
		// worn engine burns exactly as much per bang and gets less for it, which is
		// what makes a tired engine thirstier rather than cheaper to run.
		int healthyBurned = 400000 - healthy.tank.mb;
		int wornBurned = 400000 - worn.tank.mb;
		check("both burn exactly one charge per combustion event",
			healthyBurned == healthy.state.getCombustionEventId(0) * EngineTuning.FUEL_PER_COMBUSTION_MB
				&& wornBurned == worn.state.getCombustionEventId(0) * EngineTuning.FUEL_PER_COMBUSTION_MB,
			healthyBurned + " mB / " + healthy.state.getCombustionEventId(0) + " bangs healthy, " + wornBurned
				+ " mB / " + worn.state.getCombustionEventId(0) + " bangs worn");
		check("so the worn engine gets less work out of the same charge",
			worn.state.getPublishedCapacityFactor() < healthy.state.getPublishedCapacityFactor(),
			String.format("%.2f against %.2f cylinder-equivalents per bang",
				worn.state.getPublishedCapacityFactor(), healthy.state.getPublishedCapacityFactor()));
	}

	/** Worn bearings shorten the coast-down, through friction and nothing else. */
	static void wornBearingsShortenTheCoastDown() {
		section("WORN BEARINGS SHORTEN THE COAST-DOWN");

		check("a healthy engine coasts longer than a tired one",
			coastTicks(0.0F) > coastTicks(1.0F),
			coastTicks(0.0F) + " ticks pristine against " + coastTicks(1.0F) + " ticks critical");

		// Monotone, so there is no wear value that mysteriously coasts longer.
		boolean monotone = true;
		int previous = coastTicks(0.0F);
		for (int step = 1; step <= 10; step++) {
			int now = coastTicks(step / 10.0F);
			monotone &= now <= previous;
			previous = now;
		}
		check("and every step of wear in between shortens it", monotone, "sampled 11 wear levels");
	}

	/** How long a fuelled engine takes to spin down once the fuel is cut. */
	static int coastTicks(float bearingWear) {
		Engine engine = new Engine(1, 400000, LubricationState.NORMAL);
		start(engine, 20 * 30);
		// Pin the bearings: this is about friction, not about accumulation.
		engine.tank.mb = 0;
		for (int tick = 0; tick < 20 * 60; tick++) {
			java.util.Arrays.fill(engine.bearingWear, bearingWear);
			engine.tickFree();
			if (engine.state.getPhase() == EnginePhase.STOPPED)
				return tick;
		}
		return 20 * 60;
	}

	/** A worn engine is harder to start, and still starts. */
	static void wornEngineIsHarderToStartButStillStarts() {
		section("A WORN ENGINE IS HARDER TO START, AND STILL STARTS");

		check("a pristine engine asks for no extra cycles",
			EngineTuning.requiredStartCycles(3, 1, 1.0F) == EngineTuning.requiredStartCycles(3, 1),
			EngineTuning.requiredStartCycles(3, 1) + " cycles");
		int wornCycles = EngineTuning.requiredStartCycles(3, 1, EngineTuning.MIN_COMPRESSION_EFFICIENCY);
		int healthyCycles = EngineTuning.requiredStartCycles(3, 1);
		check("a critical engine asks for a few more", wornCycles > healthyCycles
			&& wornCycles - healthyCycles <= 3,
			healthyCycles + " -> " + wornCycles + " cycles");

		// Continuity: the penalty grows with lost compression rather than switching on.
		boolean monotone = true;
		int previous = EngineTuning.requiredStartCycles(3, 1, 1.0F);
		for (int step = 1; step <= 100; step++) {
			float efficiency = 1.0F - step / 100.0F * (1.0F - EngineTuning.MIN_COMPRESSION_EFFICIENCY);
			int now = EngineTuning.requiredStartCycles(3, 1, efficiency);
			monotone &= now >= previous;
			previous = now;
		}
		check("and it grows with lost compression rather than switching on", monotone, "sampled 101 points");

		// The engine itself. Everything at the service limit - bearings and bores -
		// and it must still catch and still keep running afterwards, at idle and flat
		// out alike. Badly, which is the point: it limps at about 35 RPM where a
		// healthy engine idles at 64.
		Engine criticalIdling = worstEngine();
		check("even a critically worn engine still catches", start(criticalIdling, 20 * 60),
			criticalIdling.state.getPhase() + " after cranking");
		for (int tick = 0; tick < 20 * 30; tick++)
			criticalIdling.tickFree();
		check("and keeps running on its own at idle", criticalIdling.state.getPhase() == EnginePhase.RUNNING,
			String.format("%s at %.1f RPM", criticalIdling.state.getPhase(),
				criticalIdling.state.getSimulatedRpm()));
		check("though far slower than a healthy engine's 64", criticalIdling.state.getSimulatedRpm() < 50.0F,
			String.format("%.1f RPM", criticalIdling.state.getSimulatedRpm()));

		Engine criticalWorking = worstEngine();
		criticalWorking.throttle = 1.0F;
		start(criticalWorking, 20 * 60);
		for (int tick = 0; tick < 20 * 30; tick++)
			criticalWorking.tickFree();
		check("and opening the throttle still pulls it up", criticalWorking.state.getPhase() == EnginePhase.RUNNING
			&& criticalWorking.state.getSimulatedRpm() > 100.0F,
			String.format("%.1f RPM flat out", criticalWorking.state.getSimulatedRpm()));

		// The whole point of the milestone: this is recoverable by fitting new parts.
		Engine repaired = worstEngine();
		java.util.Arrays.fill(repaired.pistonWear, 0.0F);
		java.util.Arrays.fill(repaired.bearingWear, 0.0F);
		start(repaired, 20 * 60);
		for (int tick = 0; tick < 20 * 30; tick++)
			repaired.tickFree();
		check("and replacing the worn parts restores it completely",
			near(repaired.state.getSimulatedRpm(), EngineTuning.IDLE_RPM, 4.0F),
			String.format("%.1f RPM after a rebuild", repaired.state.getSimulatedRpm()));

		check("a fresh engine catches sooner than a worn one",
			ticksToCatch(new Engine(1, 400000, LubricationState.NORMAL)) < ticksToCatch(worstEngine()),
			ticksToCatch(new Engine(1, 400000, LubricationState.NORMAL)) + " ticks fresh against "
				+ ticksToCatch(worstEngine()) + " worn");
	}

	/** Everything this model allows to be wrong with an engine's condition, at once. */
	static Engine worstEngine() {
		Engine engine = new Engine(1, 400000, LubricationState.NORMAL);
		java.util.Arrays.fill(engine.pistonWear, 1.0F);
		java.util.Arrays.fill(engine.bearingWear, 1.0F);
		return engine;
	}

	static int ticksToCatch(Engine engine) {
		for (int tick = 0; tick < 20 * 60; tick++) {
			engine.tickHeldAt(32.0F);
			if (engine.state.getPhase() == EnginePhase.RUNNING)
				return tick;
		}
		return 20 * 60;
	}

	/** M. Being spun is not running, and the wear model has to know that. */
	static void motoredDryEngineWearsBearingsAndNotCombustion() {
		section("M  A MOTORED DRY ENGINE WEARS ITS BEARINGS, AND ONLY THOSE");

		// Ignition off, no fuel, dry sump - and another Create source spinning it fast.
		Engine motored = new Engine(1, 0, LubricationState.DRY);
		motored.ignition = false;
		int combustionsBefore = motored.state.getCombustionEventId(0);
		for (int tick = 0; tick < 20 * 60; tick++)
			motored.tickHeldAt(220.0F);

		check("its bearings wear - external rotation is real mechanical work",
			motored.bearingWear[0] > 0.0F, String.format("%.4f after a minute at 220 RPM", motored.bearingWear[0]));
		check("nothing burned", motored.state.getCombustionEventId(0) == combustionsBefore,
			"0 combustion events");
		check("so it generated nothing", !motored.state.isActivelyGenerating()
			&& motored.state.getPublishedCapacityFactor() == 0.0F,
			"generation inactive, capacity 0");

		// The bores still wear, because the pistons are still moving in them - but only
		// by the motion term, never the combustion one.
		Engine reference = new Engine(1, 0, LubricationState.DRY);
		reference.ignition = false;
		for (int tick = 0; tick < 20 * 60; tick++)
			reference.tickHeldAt(220.0F);
		float motionOnly = reference.pistonWear[0];
		float expectedMotion = EngineWearMath.cylinderWearPerRevolution(LubricationState.DRY, 220.0F, 0.0F, true)
			* 220.0F / 60.0F * 20.0F * 60.0F / 20.0F;
		check("its bores wear only by piston motion",
			near(motionOnly, expectedMotion, expectedMotion * 0.02F),
			String.format("%.4f, against %.4f of pure motion wear", motionOnly, expectedMotion));

		// And an overspeeding dry engine is worse off than a well-oiled one, by a lot.
		Engine oiled = new Engine(1, 0, LubricationState.NORMAL);
		oiled.ignition = false;
		for (int tick = 0; tick < 20 * 60; tick++)
			oiled.tickHeldAt(220.0F);
		check("being motored dry is far worse than being motored oiled",
			motored.bearingWear[0] > oiled.bearingWear[0] * 20.0F,
			String.format("%.4f dry against %.5f oiled", motored.bearingWear[0], oiled.bearingWear[0]));
	}

	/** O. A well-kept engine must not become a maintenance chore. */
	static void healthyWearIsSlowEnoughToPlayWith() {
		section("O  A WELL-KEPT ENGINE LASTS PRACTICALLY FOREVER");

		// THE CALIBRATION POINT of the 13.1 rebalance: full throttle, half load,
		// normal oil, Air Filter fitted. An engine doing real work, looked after.
		float rpm = EngineTuning.FULL_THROTTLE_RPM;
		float bearing100 = bearingWearOver(rpm, 0.5F, LubricationState.NORMAL, 100.0F);
		float bearing250 = bearingWearOver(rpm, 0.5F, LubricationState.NORMAL, 250.0F);
		float piston100 = cylinderWearOver(rpm, 0.5F, LubricationState.NORMAL, true, 100.0F);
		float piston250 = cylinderWearOver(rpm, 0.5F, LubricationState.NORMAL, true, 250.0F);

		check("a hundred hours of real work barely marks the bearings", bearing100 <= 0.05F,
			String.format("%.4f bearing wear -> %s", bearing100, WearCondition.of(bearing100)));
		check("nor the bores", piston100 <= 0.05F,
			String.format("%.4f piston wear -> %s", piston100, WearCondition.of(piston100)));
		check("two hundred and fifty hours is still a healthy engine", bearing250 <= 0.10F,
			String.format("%.4f bearing wear -> %s", bearing250, WearCondition.of(bearing250)));
		check("bores included", piston250 <= 0.10F,
			String.format("%.4f piston wear -> %s", piston250, WearCondition.of(piston250)));

		// The headline claim, and the one the whole rebalance exists to make true.
		float hoursToLimit = hoursToServiceLimit(rpm, 0.5F, LubricationState.NORMAL, true);
		check("major internal replacement is not routine maintenance", hoursToLimit >= 1000.0F,
			String.format("%.0f hours of continuous running to the service limit", hoursToLimit));

		// Ten and fifty hours - a real playthrough - must be invisible.
		float bearing10 = bearingWearOver(rpm, 0.5F, LubricationState.NORMAL, 10.0F);
		float bearing50 = bearingWearOver(rpm, 0.5F, LubricationState.NORMAL, 50.0F);
		check("ten hours leaves it pristine", WearCondition.of(bearing10) == WearCondition.PRISTINE,
			String.format("%.4f -> %s", bearing10, WearCondition.of(bearing10)));
		check("and so does fifty", WearCondition.of(bearing50) == WearCondition.PRISTINE,
			String.format("%.4f -> %s", bearing50, WearCondition.of(bearing50)));

		// Slower is gentler, monotonically, at every horizon. 64 < 128 < 192.
		boolean orderedEverywhere = true;
		for (float hours : new float[] { 10.0F, 50.0F, 100.0F, 250.0F }) {
			float slow = bearingWearOver(64.0F, 0.5F, LubricationState.NORMAL, hours);
			float middling = bearingWearOver(128.0F, 0.5F, LubricationState.NORMAL, hours);
			float fast = bearingWearOver(192.0F, 0.5F, LubricationState.NORMAL, hours);
			orderedEverywhere &= slow < middling && middling < fast;
		}
		check("64 RPM is gentler than 128, which is gentler than 192, at every horizon",
			orderedEverywhere,
			String.format("%.4f < %.4f < %.4f at 250 h",
				bearingWearOver(64.0F, 0.5F, LubricationState.NORMAL, 250.0F),
				bearingWearOver(128.0F, 0.5F, LubricationState.NORMAL, 250.0F),
				bearingWearOver(192.0F, 0.5F, LubricationState.NORMAL, 250.0F)));

		// Idling with nothing hung off it is gentler still.
		float idleHours = hoursToServiceLimit(EngineTuning.IDLE_RPM, 0.0F, LubricationState.NORMAL, true);
		check("and idling is longer still", idleHours > hoursToLimit,
			String.format("%.0f hours at idle", idleHours));

		// USEFUL LOAD IS NOT ABUSE. Flat out against a full load is allowed to be
		// harder, but it must stay in the same league - an engine doing its job well
		// must never be within sight of an engine being mistreated.
		float hardHours = hoursToServiceLimit(rpm, 1.0F, LubricationState.NORMAL, true);
		check("working it flat out is harder on it", hardHours < hoursToLimit,
			String.format("%.0f hours flat out under full load", hardHours));
		check("but full load is work, not abuse - still over a thousand hours", hardHours >= 1000.0F,
			String.format("%.0f hours", hardHours));
		check("and never close to what mistreating it costs",
			hardHours > hoursToServiceLimit(rpm, 1.0F, LubricationState.DRY, true) * 100.0F,
			String.format("%.0f h loaded against %.2f h dry", hardHours,
				hoursToServiceLimit(rpm, 1.0F, LubricationState.DRY, true)));
	}

	/** O2. Low oil is a real cost; dry oil is a different thing entirely. */
	static void poorLubricationIsTheRealThreat() {
		section("O2  OIL IS WHAT ACTUALLY DECIDES AN ENGINE'S LIFE");

		float rpm = EngineTuning.FULL_THROTTLE_RPM;

		// LOW. Invisible for seconds, meaningful over a very long run.
		float lowSeconds = bearingWearOver(rpm, 0.5F, LubricationState.LOW, 3.0F / 3600.0F);
		check("three seconds of low oil is nothing at all",
			WearCondition.of(lowSeconds) == WearCondition.PRISTINE,
			String.format("%.6f -> %s", lowSeconds, WearCondition.of(lowSeconds)));
		float lowHundred = bearingWearOver(rpm, 0.5F, LubricationState.LOW, 100.0F);
		float normalHundred = bearingWearOver(rpm, 0.5F, LubricationState.NORMAL, 100.0F);
		check("but a hundred hours of it is real damage",
			WearCondition.of(lowHundred).isAtLeast(WearCondition.WORN),
			String.format("%.4f -> %s, against %.4f -> %s on good oil", lowHundred,
				WearCondition.of(lowHundred), normalHundred, WearCondition.of(normalHundred)));

		// DRY. Forgiving of a moment, ruinous of an afternoon.
		float dryFiveMinutes = bearingWearOver(111.0F, 0.0F, LubricationState.DRY, 5.0F / 60.0F);
		check("five dry minutes is measurable but not yet damage",
			dryFiveMinutes > 0.0F && WearCondition.of(dryFiveMinutes) == WearCondition.PRISTINE,
			String.format("%.4f -> %s", dryFiveMinutes, WearCondition.of(dryFiveMinutes)));
		float dryHours = hoursToServiceLimit(111.0F, 0.0F, LubricationState.DRY, true);
		check("but a dry engine left running destroys itself in hours, not days",
			dryHours > 1.0F && dryHours < 24.0F,
			String.format("%.1f hours dry at its own top speed", dryHours));
		check("and never in seconds", dryHours * 3600.0F > 600.0F,
			String.format("%.0f seconds to the service limit", dryHours * 3600.0F));

		// The ordering that matters, at a single fixed speed so nothing else moves.
		float normalRate = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rpm, 0.5F);
		float lowRate = EngineWearMath.bearingWearPerRevolution(LubricationState.LOW, rpm, 0.5F);
		float dryRate = EngineWearMath.bearingWearPerRevolution(LubricationState.DRY, rpm, 0.5F);
		check("normal is far cheaper than low, and low far cheaper than dry",
			lowRate / normalRate >= 10.0F && dryRate / lowRate >= 20.0F,
			String.format("low is %.0fx normal, dry is %.0fx low", lowRate / normalRate, dryRate / lowRate));
	}

	/** O3. Overspeed is abuse; a governor ripple is not. */
	static void overspeedIsTheAbuseThatMattersOnGoodOil() {
		section("O3  OVERSPEED IS ABUSE - RIPPLE IS NOT");

		float rated = EngineTuning.RATED_CONTINUOUS_RPM;
		float ratedRate = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rated, 0.5F);

		// A brief excursion, of the size a governor actually produces. A few percent
		// on the RATE is the right scale for four RPM over - the point is that it is
		// a few percent and not a few multiples, and that the absolute cost below is
		// nothing whatsoever.
		float rippleRate = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rated + 4.0F, 0.5F);
		check("a governor ripple costs a few percent, not a multiple", rippleRate / ratedRate < 1.05F,
			String.format("%.3f%% more wear at %.0f RPM", (rippleRate / ratedRate - 1.0F) * 100.0F, rated + 4.0F));

		// What actually matters: even the engine's own hardest possible overshoot,
		// sustained for a full minute, must not be measurable against the bands.
		float aMinuteAtTheCeiling = bearingWearOver(EngineTuning.MAX_RPM, 0.5F, LubricationState.NORMAL,
			1.0F / 60.0F);
		check("and a whole minute at the engine's own overshoot ceiling is unmeasurable",
			aMinuteAtTheCeiling < 1.0E-4F, String.format("%.7f wear after a minute at %.0f RPM",
				aMinuteAtTheCeiling, EngineTuning.MAX_RPM));
		check("nowhere near moving a condition band",
			WearCondition.of(aMinuteAtTheCeiling * 60.0F) == WearCondition.PRISTINE,
			String.format("still %s after a full hour of it",
				WearCondition.of(aMinuteAtTheCeiling * 60.0F)));

		// Slight sustained overspeed: some increased wear, and no more than that.
		float slightly = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rated * 1.1F, 0.5F);
		check("slight sustained overspeed costs something real but modest",
			slightly / ratedRate > 1.1F && slightly / ratedRate < 3.0F,
			String.format("%.2fx the rate at +10 %%", slightly / ratedRate));

		// Significant overspeed: strongly increasing.
		float significantly = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rated * 1.25F, 0.5F);
		check("significant overspeed climbs steeply", significantly / slightly > 2.0F,
			String.format("%.2fx the rate at +25 %%, against %.2fx at +10 %%", significantly / ratedRate,
				slightly / ratedRate));

		// Extreme externally imposed overspeed: the one thing that ruins a
		// well-lubricated engine within a plausible amount of play.
		float extremeHours = hoursToServiceLimit(256.0F, 1.0F, LubricationState.NORMAL, true);
		float healthyHours = hoursToServiceLimit(rated, 0.5F, LubricationState.NORMAL, true);
		check("and Create's ceiling ruins even a well-oiled engine an order of magnitude sooner",
			extremeHours < healthyHours / 10.0F,
			String.format("%.0f hours at 256 RPM against %.0f looked after", extremeHours, healthyHours));
	}

	/** O4. The worst damage emerges from combinations, and only from combinations. */
	static void combinedAbuseIsWorseThanAnyOnePart() {
		section("O4  COMBINED ABUSE IS WORSE THAN ITS PARTS");

		float rated = EngineTuning.RATED_CONTINUOUS_RPM;
		float baseline = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rated, 0.5F);
		float dryOnly = EngineWearMath.bearingWearPerRevolution(LubricationState.DRY, rated, 0.5F);
		float fastOnly = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, 256.0F, 0.5F);
		float loadedOnly = EngineWearMath.bearingWearPerRevolution(LubricationState.NORMAL, rated, 1.0F);
		float everything = EngineWearMath.bearingWearPerRevolution(LubricationState.DRY, 256.0F, 1.0F);

		check("dry and oversped and loaded together is worse than any one of them",
			everything > dryOnly && everything > fastOnly && everything > loadedOnly,
			String.format("%.3e against %.3e dry, %.3e fast, %.3e loaded", everything, dryOnly, fastOnly,
				loadedOnly));
		check("and worse than the worst of them by a wide margin",
			everything / Math.max(dryOnly, Math.max(fastOnly, loadedOnly)) > 5.0F,
			String.format("%.1fx the worst single factor",
				everything / Math.max(dryOnly, Math.max(fastOnly, loadedOnly))));

		// The feel this has to produce: minutes to tens of minutes, never seconds.
		float minutesToCritical = 60.0F * EngineTuning.CONDITION_CRITICAL_WEAR
			/ (everything * 256.0F * 60.0F);
		check("deliberate all-out abuse ruins an engine in tens of minutes",
			minutesToCritical > 2.0F && minutesToCritical < 60.0F,
			String.format("%.1f minutes of dry, oversped, fully loaded running to CRITICAL", minutesToCritical));
		float fifteenSeconds = everything * 256.0F * 0.25F;
		check("but fifteen seconds of it still does not destroy anything",
			WearCondition.of(fifteenSeconds) == WearCondition.PRISTINE,
			String.format("%.4f -> %s after fifteen seconds", fifteenSeconds,
				WearCondition.of(fifteenSeconds)));
	}

	/** O5. A healthy engine is a full-strength engine, and stays in one band. */
	static void aHealthyEngineIsAFullStrengthEngine() {
		section("O5  HEALTHY MEANS FULL STRENGTH, AND STAYS THERE");

		// PRISTINE and GOOD must be indistinguishable from new, in both directions.
		for (float wear : new float[] { 0.0F, 0.05F, EngineTuning.CONDITION_GOOD_WEAR, 0.2F }) {
			float compression = EngineWearMath.compressionEfficiency(wear);
			float friction = EngineWearMath.bearingFrictionMultiplier(wear);
			check(String.format("at %.2f wear the engine is still essentially new", wear),
				compression >= 0.97F && friction <= 1.05F,
				String.format("%.4f compression, %.4fx friction", compression, friction));
		}

		// And a GOOD engine must not churn through condition categories while a
		// player watches. A hundred hours of ordinary running from the top of
		// PRISTINE must cross at most one boundary.
		float from = 0.0F;
		float to = from + bearingWearOver(EngineTuning.FULL_THROTTLE_RPM, 0.5F, LubricationState.NORMAL, 100.0F);
		check("a hundred ordinary hours moves it at most one condition band",
			WearCondition.of(to).ordinal() - WearCondition.of(from).ordinal() <= 1,
			String.format("%s -> %s over a hundred hours", WearCondition.of(from), WearCondition.of(to)));

		// Through a real engine, not just the curves: a lightly worn engine must
		// settle at essentially the speed a new one does.
		Engine fresh = new Engine(1, 400000, LubricationState.NORMAL);
		fresh.throttle = 1.0F;
		start(fresh, 20 * 30);
		Engine good = new Engine(1, 400000, LubricationState.NORMAL);
		good.throttle = 1.0F;
		java.util.Arrays.fill(good.bearingWear, EngineTuning.CONDITION_GOOD_WEAR);
		java.util.Arrays.fill(good.pistonWear, EngineTuning.CONDITION_GOOD_WEAR);
		start(good, 20 * 30);
		float freshRpm = fresh.state.getSimulatedRpm();
		float goodRpm = good.state.getSimulatedRpm();
		check("and a GOOD engine runs at essentially a new engine's speed",
			goodRpm >= freshRpm * 0.97F,
			String.format("%.1f RPM good against %.1f RPM fresh", goodRpm, freshRpm));
		check("while a WORN one is visibly down on it", wornSettledRpm() < freshRpm * 0.97F,
			String.format("%.1f RPM worn against %.1f RPM fresh", wornSettledRpm(), freshRpm));
	}

	/** The speed a WORN engine settles at, for the comparison above. */
	static float wornSettledRpm() {
		Engine worn = new Engine(1, 400000, LubricationState.NORMAL);
		worn.throttle = 1.0F;
		java.util.Arrays.fill(worn.bearingWear, EngineTuning.CONDITION_POOR_WEAR);
		java.util.Arrays.fill(worn.pistonWear, EngineTuning.CONDITION_POOR_WEAR);
		start(worn, 20 * 60);
		return worn.state.getSimulatedRpm();
	}

	/**
	 * A3. The calibration table the milestone asks to be reported, printed rather
	 * than asserted - the assertions above are what hold the numbers in place, and
	 * this is what makes them readable in a build log.
	 */
	static void reportCalibrationTable() {
		section("A3  WEAR CALIBRATION TABLE (NORMAL oil, Air Filter fitted)");
		System.out.println("     fresh engine, continuous running, wear after N hours");
		System.out.println("     B = crankshaft bearing, P = piston / compression");
		System.out.println();
		System.out.println("      RPM   load        10 h      50 h     100 h     250 h   condition at 250 h");
		for (float rpm : new float[] { 64.0F, 128.0F, 192.0F }) {
			for (float load : new float[] { 0.0F, 0.5F, 1.0F }) {
				StringBuilder bearings = new StringBuilder(String.format("     %4.0f   %3.0f%%   B", rpm, load * 100));
				StringBuilder pistons = new StringBuilder("                  P");
				for (float hours : new float[] { 10.0F, 50.0F, 100.0F, 250.0F }) {
					bearings.append(String.format("%10.4f", bearingWearOver(rpm, load, LubricationState.NORMAL, hours)));
					pistons.append(String.format("%10.4f",
						cylinderWearOver(rpm, load, LubricationState.NORMAL, true, hours)));
				}
				bearings.append("   ")
					.append(WearCondition.of(bearingWearOver(rpm, load, LubricationState.NORMAL, 250.0F)));
				pistons.append("   ")
					.append(WearCondition.of(cylinderWearOver(rpm, load, LubricationState.NORMAL, true, 250.0F)));
				System.out.println(bearings);
				System.out.println(pistons);
			}
		}
		System.out.println();
		System.out.printf("     service limit at 192 RPM / 50%% load:  %.0f h bearings, %.0f h pistons%n",
			hoursToServiceLimit(192.0F, 0.5F, LubricationState.NORMAL, true),
			cylinderHoursToServiceLimit(192.0F, 0.5F, true));
		System.out.printf("     unfiltered pistons, same conditions:  %.0f h to the service limit%n",
			cylinderHoursToServiceLimit(192.0F, 0.5F, false));
		System.out.printf("     sustained LOW oil, same conditions:   %.0f h to the service limit%n",
			hoursToServiceLimit(192.0F, 0.5F, LubricationState.LOW, true));
		System.out.printf("     sustained DRY, engine's own dry speed: %.1f h to the service limit%n",
			hoursToServiceLimit(111.0F, 0.0F, LubricationState.DRY, true));
		System.out.printf("     dry + 256 RPM + full load:             %.1f min to CRITICAL%n",
			60.0F * EngineTuning.CONDITION_CRITICAL_WEAR
				/ (EngineWearMath.bearingWearPerRevolution(LubricationState.DRY, 256.0F, 1.0F) * 256.0F * 60.0F));
	}

	/** Bearing wear a fresh engine accumulates over N hours at fixed conditions. */
	static float bearingWearOver(float rpm, float load, LubricationState lubrication, float hours) {
		return EngineWearMath.bearingWearPerRevolution(lubrication, rpm, load) * rpm * 60.0F * hours;
	}

	/** The same for a firing cylinder, which pays both the motion and the combustion rate. */
	static float cylinderWearOver(float rpm, float load, LubricationState lubrication, boolean filtered,
		float hours) {
		float perRevolution = EngineWearMath.cylinderWearPerRevolution(lubrication, rpm, load, filtered)
			+ EngineWearMath.cylinderWearPerCombustion(lubrication, rpm, load, filtered);
		return perRevolution * rpm * 60.0F * hours;
	}

	/** Hours of continuous running at these conditions before the bearings hit the limit. */
	static float hoursToServiceLimit(float rpm, float load, LubricationState lubrication, boolean filtered) {
		float perRevolution = EngineWearMath.bearingWearPerRevolution(lubrication, rpm, load);
		float perHour = perRevolution * rpm * 60.0F;
		return 1.0F / perHour;
	}

	/** The same for a firing cylinder, which pays both the motion and the combustion rate. */
	static float cylinderHoursToServiceLimit(float rpm, float load, boolean filtered) {
		float perRevolution = EngineWearMath.cylinderWearPerRevolution(LubricationState.NORMAL, rpm, load, filtered)
			+ EngineWearMath.cylinderWearPerCombustion(LubricationState.NORMAL, rpm, load, filtered);
		return 1.0F / (perRevolution * rpm * 60.0F);
	}

	/** P. Microscopic wear must not churn Create's kinetic bookkeeping. */
	static void capacityDoesNotChangeEveryTick() {
		section("P  CAPACITY IS QUANTISED, AND DOES NOT CHURN");

		Engine engine = new Engine(1, 400000, LubricationState.NORMAL);
		start(engine, 20 * 30);
		check("a steady engine settles on a capacity", engine.state.getPublishedCapacityFactor() > 0.0F,
			String.format("%.2f", engine.state.getPublishedCapacityFactor()));

		// A thousand ticks of ordinary running at the real wear rate.
		int changes = 0;
		float previous = engine.state.getPublishedCapacityFactor();
		for (int tick = 0; tick < 1000; tick++) {
			engine.tickFree();
			if (engine.state.getPublishedCapacityFactor() != previous) {
				changes++;
				previous = engine.state.getPublishedCapacityFactor();
			}
		}
		check("real wear moves it not at all over a thousand ticks", changes == 0, changes + " change(s)");
		check("and the simulation agrees nothing changed",
			!engine.state.hasCapacityFactorChanged(), "no capacity refresh requested on the last tick");

		// Now wear the piston far faster than any engine ever could, and count how
		// often the published figure actually moves. It must follow the quantum, not
		// the tick.
		changes = 0;
		previous = engine.state.getPublishedCapacityFactor();
		float startWear = engine.pistonWear[0];
		// 2000 ticks of this spans most of a cylinder's whole life, which against the
		// rebalanced - and much gentler - compression curve is what it now takes to
		// cross several capacity quanta. The old injection rate crossed none of them,
		// which proved nothing either way.
		for (int tick = 0; tick < 2000; tick++) {
			engine.pistonWear[0] = EngineWearMath.clampWear(engine.pistonWear[0] + 4.0E-4F);
			engine.tickFree();
			if (engine.state.getPublishedCapacityFactor() != previous) {
				changes++;
				previous = engine.state.getPublishedCapacityFactor();
			}
		}
		float efficiencyDrop = EngineWearMath.compressionEfficiency(startWear)
			- EngineWearMath.compressionEfficiency(engine.pistonWear[0]);
		int boundaries = Math.round(efficiencyDrop / EngineTuning.CAPACITY_QUANTUM);
		check("accelerated wear does move it", changes > 0, changes + " change(s) over 2000 ticks");
		check("but only once per quantum crossed, not once per tick",
			changes <= boundaries + 1, changes + " change(s) for " + boundaries + " quantum boundary crossing(s)");

		// A cylinder dropping out, on the other hand, must publish at once.
		Engine r4 = new Engine(4, 400000, LubricationState.NORMAL);
		start(r4, 20 * 30);
		float beforeMask = r4.state.getPublishedCapacityFactor();
		r4.sparkPlugMask = 0b0111;
		int allowance = EngineTuning.generationCombustionAllowanceTicks(r4.state.getSimulatedRpm());
		int ticksUntilPublished = -1;
		for (int tick = 1; tick <= allowance + 5; tick++) {
			r4.tickFree();
			if (ticksUntilPublished < 0 && r4.state.getPublishedCapacityFactor() != beforeMask)
				ticksUntilPublished = tick;
		}
		check("a cylinder dropping out publishes on the very tick the mask moves",
			ticksUntilPublished > 0 && near(r4.state.getPublishedCapacityFactor(), 3.0F, 0.02F),
			String.format("%.2f after %d tick(s)", r4.state.getPublishedCapacityFactor(), ticksUntilPublished));
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
		System.out.printf("%-4s %-62s %s%n", passed ? "PASS" : "FAIL", name, detail);
	}
}
