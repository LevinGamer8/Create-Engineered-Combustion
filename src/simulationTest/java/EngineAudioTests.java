import dev.engineeredcombustion.content.engine.*;
import dev.engineeredcombustion.content.engine.fourstroke.*;

/**
 * The audio contract, checked against the engine rather than against a recording.
 *
 * <p>None of the sound code can be tested here - {@code CombustionAudio} and the
 * loop layers touch {@code Level}, {@code BlockPos} and the sound registry, so they
 * are outside this source set by construction. What <i>can</i> be pinned here is
 * everything the sound is derived from, and after the Milestone 15B play-test that
 * turned out to be the part worth pinning:
 *
 * <ul>
 * <li><b>Every bang is a paid combustion.</b> The pulse is played from the
 * combustion counter, so if that counter ticks once per 720 degrees then so does
 * the sound. Measured per layout, at every rated speed.</li>
 * <li><b>Nothing else in the engine ticks at a rate that could be mistaken for
 * one.</b> Spark and combustion are the same event on a healthy cylinder, so a
 * running engine has exactly one audible event per firing and not two.</li>
 * <li><b>No layout reaches the aggregation threshold</b>, so no pulse is ever
 * thinned out or replaced by a loop. Every bang stays one real event.</li>
 * <li><b>The sparse-pulse gain scales pulses and never adds them</b>, and lands
 * where it is meant to for each layout.</li>
 * </ul>
 *
 * <p>The bug this suite exists to prevent: an audio layer that carries a rhythm of
 * its own. The mechanical loop used to hold one compression swell and one knock per
 * crank <i>revolution</i>, which on a four-stroke put a percussive event squarely
 * between two real bangs, and was heard as an engine firing twice as often as it
 * did. Nothing here can catch a bad .ogg - but everything here catches the day the
 * engine's own event rate stops being what the audio assumes.
 *
 * <p>Exits non-zero on any failure.
 */
public class EngineAudioTests {

	static int failures = 0;

	/** The speeds the engine is balanced at, and the ones a player hears. */
	static final float[] THROTTLES = { 0.0F, 0.5F, 1.0F };
	static final float[] SPEEDS = { EngineTuning.IDLE_RPM, 128.0F, EngineTuning.FULL_THROTTLE_RPM };

	public static void main(String[] args) {
		everyBangIsOnePaidCombustion();
		theFiringRateIsTheFourStrokeRate();
		nothingIsEverAggregatedOrThinned();
		theSparseGainScalesAndNeverAdds();
		aDeadEngineIsSilentEvenWhileItTurns();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	// ------------------------------------------------------------ the events

	/**
	 * A. One combustion counter tick per cylinder per cycle, and one spark with it.
	 *
	 * <p>The flash, the bang and the fuel draw are all the same increment, so this is
	 * the whole of "the sound is synchronised with the visible combustion": there is
	 * one number, and everything reads it.
	 */
	static void everyBangIsOnePaidCombustion() {
		section("A  EVERY BANG IS ONE PAID COMBUSTION");

		for (int cylinders = 1; cylinders <= EngineTuning.MAX_CYLINDERS; cylinders++) {
			Rig rig = Rig.running(cylinders, 1.0F);
			int combustionsBefore = rig.totalCombustions();
			int sparksBefore = rig.totalSparks();
			float revolutions = rig.runMeasuringRevolutions(20 * 60);
			int combustions = rig.totalCombustions() - combustionsBefore;
			int sparks = rig.totalSparks() - sparksBefore;

			// Half a firing per cylinder per revolution: that IS the four-stroke.
			float perCylinderPerRevolution = combustions / revolutions / cylinders;
			check("R" + cylinders + " fires each cylinder once per two revolutions",
				Math.abs(perCylinderPerRevolution - 0.5F) < 0.02F,
				String.format("%.3f firings per cylinder per revolution", perCylinderPerRevolution));

			// If these ever diverge on a healthy engine, one of the two is happening
			// on a stroke the other is not, and the player gets an effect on the
			// non-firing top dead centre.
			check("R" + cylinders + " sparks exactly as often as it fires",
				Math.abs(sparks - combustions) <= cylinders,
				sparks + " spark(s), " + combustions + " combustion(s)");
		}
	}

	/**
	 * B. The rate the audio measures, in events per second, at every rated speed.
	 *
	 * <p>These are the numbers the whole sound design is answering. An inline-1 at
	 * idle fires roughly every other second; an inline-4 at full throttle is more
	 * than twelve times denser. One pulse design cannot be right for both without a
	 * term that knows which it is looking at.
	 */
	static void theFiringRateIsTheFourStrokeRate() {
		section("B  THE FIRING RATE, IN EVENTS PER SECOND");
		System.out.println("     cyl     rpm   events/s   expected   sparse gain");

		for (int cylinders = 1; cylinders <= EngineTuning.MAX_CYLINDERS; cylinders++)
			for (int i = 0; i < THROTTLES.length; i++) {
				Rig rig = Rig.running(cylinders, THROTTLES[i]);
				float rateHz = rig.firingRateHz(20 * 60);
				// One firing per cylinder per 720 degrees, and 720 degrees at r RPM
				// takes 120/r seconds.
				float expected = cylinders * SPEEDS[i] / 120.0F;
				System.out.printf("     %3d   %5.0f   %8.3f   %8.3f   %11.3f%n", cylinders, SPEEDS[i], rateHz,
					expected, EngineTuning.combustionSparseGain(rateHz));

				check("R" + cylinders + " at " + (int) SPEEDS[i] + " RPM fires at the four-stroke rate",
					Math.abs(rateHz - expected) < 0.08F, String.format("%.3f Hz against %.3f", rateHz, expected));
			}
	}

	/**
	 * C. Nothing this engine can do reaches the rate where pulses stop being played
	 * one for one.
	 *
	 * <p>Which is the guarantee that matters most: below the threshold every bang the
	 * player hears is one charge that really burned, in the position it burned in,
	 * with nothing aggregated, decimated or looped. The threshold exists for an
	 * engine that does not exist yet.
	 */
	static void nothingIsEverAggregatedOrThinned() {
		section("C  EVERY PULSE IS STILL ONE REAL EVENT");

		float fastest = 0.0F;
		for (int cylinders = 1; cylinders <= EngineTuning.MAX_CYLINDERS; cylinders++) {
			Rig rig = Rig.running(cylinders, 1.0F);
			fastest = Math.max(fastest, rig.firingRateHz(20 * 60));
		}

		check("the densest layout is the inline-4 at full throttle",
			Math.abs(fastest - 4.0F * EngineTuning.FULL_THROTTLE_RPM / 120.0F) < 0.08F,
			String.format("%.2f Hz", fastest));
		check("and it is still below the individual-pulse threshold",
			fastest < EngineTuning.SOUND_COMBUSTION_PULSE_MAX_RATE_HZ,
			String.format("%.2f Hz against %.0f", fastest, EngineTuning.SOUND_COMBUSTION_PULSE_MAX_RATE_HZ));
		check("so the continuous combustion layer never fades in at all",
			EngineTuning.combustionLoopBlend(fastest) == 0.0F,
			String.format("blend %.3f", EngineTuning.combustionLoopBlend(fastest)));
	}

	/**
	 * D. The sparse-pulse gain, which is the one place a pulse's loudness depends on
	 * anything other than the engine's state.
	 *
	 * <p>It is a mix term and it must stay one: bounded, monotone, and worth exactly
	 * nothing at the rates where the pulses already carry each other. It may never
	 * become a way of implying a firing rate the engine does not have.
	 */
	static void theSparseGainScalesAndNeverAdds() {
		section("D  THE SPARSE GAIN IS A GAIN");

		float previous = Float.MAX_VALUE;
		boolean monotone = true;
		boolean bounded = true;
		for (float rate = 0.0F; rate <= 20.0F; rate += 0.05F) {
			float gain = EngineTuning.combustionSparseGain(rate);
			monotone &= gain <= previous + 1.0E-6F;
			bounded &= gain >= 1.0F && gain <= EngineTuning.SOUND_COMBUSTION_SPARSE_GAIN;
			previous = gain;
		}
		check("never rises with the firing rate", monotone, "over 0 to 20 Hz");
		check("never leaves [1, " + EngineTuning.SOUND_COMBUSTION_SPARSE_GAIN + "]", bounded, "over 0 to 20 Hz");

		check("a silent engine's next bang gets the full weight",
			EngineTuning.combustionSparseGain(0.0F) == EngineTuning.SOUND_COMBUSTION_SPARSE_GAIN,
			String.format("%.3f", EngineTuning.combustionSparseGain(0.0F)));

		// The single at idle is the case the whole term exists for: one bang, then
		// most of two seconds of nothing.
		float singleIdle = 1.0F * EngineTuning.IDLE_RPM / 120.0F;
		check("an inline-1 at idle gets the full weight",
			EngineTuning.combustionSparseGain(singleIdle) == EngineTuning.SOUND_COMBUSTION_SPARSE_GAIN,
			String.format("%.2f Hz", singleIdle));

		// The four at full throttle is the case it must not touch.
		float fourFull = 4.0F * EngineTuning.FULL_THROTTLE_RPM / 120.0F;
		check("an inline-4 at full throttle is left exactly alone",
			EngineTuning.combustionSparseGain(fourFull) == 1.0F, String.format("%.2f Hz", fourFull));

		check("and the ramp between them is strictly inside the two ends",
			EngineTuning.combustionSparseGain(4.0F) > 1.0F
				&& EngineTuning.combustionSparseGain(4.0F) < EngineTuning.SOUND_COMBUSTION_SPARSE_GAIN,
			String.format("%.3f at 4 Hz", EngineTuning.combustionSparseGain(4.0F)));
	}

	/**
	 * E. An engine that is turning but not burning makes no combustion sound, because
	 * it produces no combustion events to make one from.
	 *
	 * <p>The case that proves the sound follows the events and not the rotation: a
	 * motored engine with no Camshaft turns at full speed, sparks, compresses, and
	 * has nothing for the pulse layer to play.
	 */
	static void aDeadEngineIsSilentEvenWhileItTurns() {
		section("E  ROTATION IS NOT COMBUSTION");

		Rig rig = new Rig(1, 1.0F, 4000000);
		rig.camshaft = false;
		for (int tick = 0; tick < 20 * 60; tick++)
			rig.tickDriven(EngineTuning.FULL_THROTTLE_RPM);

		check("a motored engine with no Camshaft turns at full speed",
			Math.abs(rig.state.getMechanicalRpm() - EngineTuning.FULL_THROTTLE_RPM) < 1.0F,
			String.format("%.0f RPM", rig.state.getMechanicalRpm()));
		check("and produces no combustion event to sound", rig.totalCombustions() == 0,
			rig.totalCombustions() + " combustion(s)");
		check("while its coil still sparks, which is the diagnosis and not the voice",
			rig.totalSparks() > 0, rig.totalSparks() + " spark(s)");
	}

	// ------------------------------------------------------------------- rig

	static class Rig {
		final EngineState state = new EngineState();
		final Tank tank;
		final Sump sump = new Sump();
		final java.util.Random random = new java.util.Random(29L);
		final int cylinders;
		float throttle;
		int sparkPlugMask;
		boolean camshaft = true;

		Rig(int cylinders, float throttle, int fuel) {
			this.cylinders = cylinders;
			this.throttle = throttle;
			this.sparkPlugMask = (1 << cylinders) - 1;
			this.tank = new Tank(fuel);
		}

		static Rig running(int cylinders, float throttle) {
			Rig rig = new Rig(cylinders, throttle, 40000000);
			for (int tick = 0; tick < 20 * 90 && rig.state.getPhase() != EnginePhase.RUNNING; tick++)
				rig.tickDriven(96.0F);
			for (int tick = 0; tick < 20 * 60; tick++)
				rig.tickFree();
			return rig;
		}

		EngineInputs inputs() {
			return new EngineInputs(true, true, cylinders, sparkPlugMask, throttle, 0.0F, EngineTuning.MAX_RPM,
				EngineWearInputs.PRISTINE, camshaft);
		}

		void tickFree() {
			state.tickRotation(0.0F, false, false);
			state.tickSimulation(inputs(), tank, sump, random);
		}

		void tickDriven(float rpm) {
			state.tickRotation(rpm, true, true);
			state.tickSimulation(inputs(), tank, sump, random);
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

		float runMeasuringRevolutions(int ticks) {
			float revolutions = 0.0F;
			for (int tick = 0; tick < ticks; tick++) {
				tickFree();
				revolutions += state.getRevolutionsThisTick();
			}
			return revolutions;
		}

		/** Combustion events per second, exactly as the audio would measure them. */
		float firingRateHz(int ticks) {
			int before = totalCombustions();
			for (int tick = 0; tick < ticks; tick++)
				tickFree();
			return (totalCombustions() - before) * 20.0F / ticks;
		}
	}

	// --------------------------------------------------------------- support

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
		public LubricationState lubrication() {
			return LubricationState.NORMAL;
		}

		public boolean consume(int amount) {
			return true;
		}
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
