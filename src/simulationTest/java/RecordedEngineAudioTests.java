import java.util.Arrays;
import java.util.List;

import dev.engineeredcombustion.content.engine.EnginePhase;
import dev.engineeredcombustion.content.engine.EngineInputs;
import dev.engineeredcombustion.content.engine.EngineState;
import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.EngineWearInputs;
import dev.engineeredcombustion.content.engine.FuelSupply;
import dev.engineeredcombustion.content.engine.LubricationState;
import dev.engineeredcombustion.content.engine.OilSupply;
import dev.engineeredcombustion.content.engine.RecordedEngineAudio;
import dev.engineeredcombustion.content.engine.RecordedEngineAudio.Band;

/**
 * The audio policy of the first recorded inline-4 pass: which engines get the
 * real recordings, when, how loud each band is and at what pitch - and that
 * none of it reaches the simulation.
 *
 * <p>Driven by real simulated engines wherever a phase matters, rather than by
 * hand-set phases, so "cranking", "coasting" and "motored without fuel" are the
 * states the engine genuinely reaches. The client sound classes themselves need
 * Minecraft and are not compiled here; everything they decide with is.
 *
 * <p>Exits non-zero on any failure.
 */
public class RecordedEngineAudioTests {

	static int failures = 0;

	// ---------------------------------------------------------------- fixtures

	static class Tank implements FuelSupply {
		int mb;
		int drawn;

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
			drawn += amount;
			return true;
		}
	}

	static final OilSupply OIL = new OilSupply() {
		public LubricationState lubrication() {
			return LubricationState.NORMAL;
		}

		public boolean consume(int mb) {
			return true;
		}
	};

	/** One engine on its own network, as in {@code MultiCylinderTests}. */
	static class Engine {
		final EngineState state = new EngineState();
		final Tank tank;
		final java.util.Random random;
		final int cylinders;

		boolean ignition = true;
		boolean camshaft = true;
		float throttle;
		float externalRpm;
		float networkSpeed;
		/** Whether to consult the audio policy every tick, as the client does. */
		boolean listening;
		int recordedTicks;

		Engine(int cylinders, int fuelMb, long seed) {
			this.cylinders = cylinders;
			this.tank = new Tank(fuelMb);
			this.random = new java.util.Random(seed);
		}

		void tick() {
			boolean drivenByOthers = networkSpeed != 0.0F
				&& Math.abs(state.getPublishedRpm()) < Math.abs(networkSpeed);
			state.tickRotation(networkSpeed, networkSpeed != 0.0F, drivenByOthers);
			state.tickSimulation(new EngineInputs(true, ignition, cylinders, (1 << cylinders) - 1, throttle, 0.0F,
				EngineTuning.MAX_RPM, EngineWearInputs.PRISTINE, camshaft), tank, OIL, random);
			networkSpeed = Math.abs(state.getPublishedRpm()) > Math.abs(externalRpm) ? state.getPublishedRpm()
				: externalRpm;
			if (listening) {
				// Everything the client reads, every tick.
				if (RecordedEngineAudio.isActive(state))
					recordedTicks++;
				RecordedEngineAudio.syntheticVoiceAudible(state);
				for (int band = 0; band < RecordedEngineAudio.R4_BANDS.size(); band++) {
					RecordedEngineAudio.r4Volume(state, band);
					RecordedEngineAudio.r4Pitch(state, band);
				}
			}
		}

		void setExternal(float rpm) {
			externalRpm = rpm;
			networkSpeed = Math.abs(state.getPublishedRpm()) > Math.abs(externalRpm) ? state.getPublishedRpm()
				: externalRpm;
		}

		/** Ticks, and says whether the recorded mode was active on any of them. */
		boolean everActive(int ticks) {
			boolean any = false;
			for (int i = 0; i < ticks; i++) {
				tick();
				any |= RecordedEngineAudio.isActive(state);
			}
			return any;
		}
	}

	/** Cranks the engine until it catches, the way a player does. */
	static Engine started(int cylinders, float throttle) {
		Engine engine = new Engine(cylinders, 4000000, 900 + cylinders);
		engine.throttle = throttle;
		engine.setExternal(48.0F);
		for (int i = 0; i < 400; i++)
			engine.tick();
		engine.setExternal(0.0F);
		for (int i = 0; i < 600; i++)
			engine.tick();
		return engine;
	}

	static void check(String name, boolean ok, String detail) {
		System.out.printf("%s %-60s %s%n", ok ? "PASS" : "FAIL", name, detail);
		if (!ok)
			failures++;
	}

	static float totalPower(List<Band> bands, float sampleRpm) {
		float sum = 0.0F;
		for (int i = 0; i < bands.size(); i++) {
			float w = RecordedEngineAudio.bandWeight(bands, i, sampleRpm);
			sum += w * w;
		}
		return sum;
	}

	// -------------------------------------------------------------------- tests

	public static void main(String[] args) {
		activationTable();
		onlyTheInlineFourRecords();
		notWhileCrankingOrStarting();
		notWhileCoasting();
		notWhenMotoredWithoutCombustion();
		suppressionIsAudioOnly();
		rpmMapping();
		crossfade();
		pitch();
		moreBands();
		System.out.println(failures == 0 ? "\nall checks passed" : "\n" + failures + " FAILURES");
		System.exit(failures == 0 ? 0 : 1);
	}

	/** TEST A1 - the exact condition, over every layout, phase and generation flag. */
	static void activationTable() {
		System.out.println("PART 1 - the activation condition\n");
		boolean exact = true;
		StringBuilder active = new StringBuilder();
		for (int cylinders = 1; cylinders <= EngineTuning.MAX_CYLINDERS; cylinders++)
			for (EnginePhase phase : EnginePhase.values())
				for (boolean generating : new boolean[] { false, true }) {
					boolean is = RecordedEngineAudio.isActive(cylinders, phase, generating);
					boolean expected = cylinders == 4 && phase == EnginePhase.RUNNING && generating;
					exact &= is == expected;
					if (is)
						active.append("R").append(cylinders).append(' ').append(phase).append(" generating");
				}
		check("only R4 + RUNNING + actively generating selects it", exact, "active for: " + active);
	}

	/** TEST A2-A5 - R1 to R3 never select the recording; a running R4 does. */
	static void onlyTheInlineFourRecords() {
		System.out.println("\nPART 2 - only the inline-4 is voiced by the recording\n");
		for (int cylinders = 1; cylinders <= 3; cylinders++) {
			Engine engine = started(cylinders, 0.0F);
			engine.listening = true;
			boolean running = engine.state.getPhase() == EnginePhase.RUNNING && engine.state.isActivelyGenerating();
			boolean ever = engine.everActive(1200);
			engine.throttle = 1.0F;
			ever |= engine.everActive(1200);
			check("R" + cylinders + " never selects the recorded layers", running && !ever
				&& RecordedEngineAudio.syntheticVoiceAudible(engine.state),
				String.format("running %s, %.0f RPM at full throttle, recorded %d ticks", running,
					engine.state.getMechanicalRpm(), engine.recordedTicks));
		}

		Engine r4 = started(4, 0.0F);
		boolean running = r4.state.getPhase() == EnginePhase.RUNNING && r4.state.isActivelyGenerating();
		int active = 0;
		for (int i = 0; i < 1200; i++) {
			r4.tick();
			if (RecordedEngineAudio.isActive(r4.state))
				active++;
		}
		check("R4 RUNNING selects the recorded layers, continuously", running && active == 1200,
			String.format("%s, active %d / 1200 ticks at %.1f RPM", r4.state.getPhase(), active,
				r4.state.getMechanicalRpm()));
		float idle = RecordedEngineAudio.r4Volume(r4.state, 0);
		float mid = RecordedEngineAudio.r4Volume(r4.state, 1);
		check("  and at idle the 1000 RPM band carries it", idle > 0.9F * RecordedEngineAudio.VOLUME
			&& mid < 0.2F * RecordedEngineAudio.VOLUME, String.format("idle %.3f, 1500 %.3f", idle, mid));
		check("  and the synthetic voice is silenced", !RecordedEngineAudio.syntheticVoiceAudible(r4.state), "");

		r4.throttle = 1.0F;
		for (int i = 0; i < 1200; i++)
			r4.tick();
		idle = RecordedEngineAudio.r4Volume(r4.state, 0);
		mid = RecordedEngineAudio.r4Volume(r4.state, 1);
		check("  and at full throttle the 1500 RPM band carries it", RecordedEngineAudio.isActive(r4.state)
			&& mid > 0.9F * RecordedEngineAudio.VOLUME && idle < 0.2F * RecordedEngineAudio.VOLUME,
			String.format("%.1f RPM, idle %.3f, 1500 %.3f", r4.state.getMechanicalRpm(), idle, mid));
	}

	/** TEST A6 - an R4 being cranked, or firing but not yet caught, is not a running car. */
	static void notWhileCrankingOrStarting() {
		System.out.println("\nPART 3 - cranking and starting keep the mechanical voice\n");

		// Cranked with the ignition off: CRANKING throughout, never firing.
		Engine dead = new Engine(4, 4000000, 41);
		dead.ignition = false;
		dead.setExternal(48.0F);
		boolean sawCranking = false;
		boolean ever = false;
		for (int i = 0; i < 600; i++) {
			dead.tick();
			sawCranking |= dead.state.getPhase() == EnginePhase.CRANKING;
			ever |= RecordedEngineAudio.isActive(dead.state);
		}
		check("R4 CRANKING (ignition off) never selects it", sawCranking && !ever,
			String.format("%s at %.1f RPM", dead.state.getPhase(), dead.state.getMechanicalRpm()));

		// A real start, tick by tick: the recording may only appear once RUNNING.
		Engine engine = new Engine(4, 4000000, 42);
		engine.setExternal(48.0F);
		boolean early = false;
		boolean sawStarting = false;
		int firstActive = -1;
		int firstRunning = -1;
		for (int i = 0; i < 400; i++) {
			engine.tick();
			EnginePhase phase = engine.state.getPhase();
			sawStarting |= phase == EnginePhase.STARTING;
			boolean active = RecordedEngineAudio.isActive(engine.state);
			if (active && phase != EnginePhase.RUNNING)
				early = true;
			if (phase == EnginePhase.RUNNING && firstRunning < 0)
				firstRunning = i;
			if (active && firstActive < 0)
				firstActive = i;
		}
		check("R4 CRANKING/STARTING never selects it", sawStarting && !early && firstActive >= firstRunning,
			String.format("first RUNNING tick %d, first recorded tick %d", firstRunning, firstActive));
	}

	/** TEST A7 - ignition off: the recording goes, the coast-down is mechanical. */
	static void notWhileCoasting() {
		System.out.println("\nPART 4 - coasting keeps the mechanical voice\n");
		Engine engine = started(4, 1.0F);
		boolean wasActive = RecordedEngineAudio.isActive(engine.state);
		engine.ignition = false;
		boolean sawCoasting = false;
		boolean everWhileCoasting = false;
		for (int i = 0; i < 600; i++) {
			engine.tick();
			if (engine.state.getPhase() == EnginePhase.COASTING) {
				sawCoasting = true;
				everWhileCoasting |= RecordedEngineAudio.isActive(engine.state);
			}
		}
		check("R4 COASTING never selects it", wasActive && sawCoasting && !everWhileCoasting,
			String.format("active before %s, coasted %s", wasActive, sawCoasting));
	}

	/** TEST A8 - a dead inline-4 spun by the network must never sound like a running car. */
	static void notWhenMotoredWithoutCombustion() {
		System.out.println("\nPART 5 - motored but not combusting\n");

		Engine dry = new Engine(4, 0, 51);
		dry.setExternal(128.0F);
		boolean ever = dry.everActive(800);
		check("R4 with no fuel, driven at 128 RPM, never selects it", !ever,
			String.format("%s at %.1f RPM, generating %s", dry.state.getPhase(), dry.state.getMechanicalRpm(),
				dry.state.isActivelyGenerating()));

		// Fuelled and ignited, but no Camshaft: the four-stroke cannot breathe, so
		// it cannot combust, however hard the network spins it.
		Engine noCam = new Engine(4, 4000000, 52);
		noCam.camshaft = false;
		noCam.setExternal(128.0F);
		boolean everNoCam = noCam.everActive(800);
		check("R4 with no Camshaft, driven at 128 RPM, never selects it", !everNoCam,
			String.format("%s at %.1f RPM, generating %s", noCam.state.getPhase(), noCam.state.getMechanicalRpm(),
				noCam.state.isActivelyGenerating()));

		// Running, then the tank runs dry while the network keeps the shaft turning.
		Engine starved = started(4, 0.0F);
		boolean wasActive = RecordedEngineAudio.isActive(starved.state);
		starved.tank.mb = 0;
		starved.setExternal(128.0F);
		for (int i = 0; i < 100; i++)
			starved.tick();
		boolean after = starved.everActive(600);
		check("R4 that runs dry while motored drops the recording", wasActive && !after,
			String.format("%s at %.1f RPM", starved.state.getPhase(), starved.state.getMechanicalRpm()));
	}

	/** TEST A9-A10 - suppression is audio only: the simulation cannot tell it is being listened to. */
	static void suppressionIsAudioOnly() {
		System.out.println("\nPART 6 - the simulation is untouched\n");

		Engine quiet = started(4, 0.6F);
		Engine heard = started(4, 0.6F);
		heard.listening = true;
		for (int i = 0; i < 2400; i++) {
			if (i == 1600) {
				quiet.ignition = false;
				heard.ignition = false;
			}
			quiet.tick();
			heard.tick();
		}
		boolean same = quiet.tank.drawn == heard.tank.drawn
			&& Float.floatToIntBits(quiet.state.getSimulatedRpm()) == Float.floatToIntBits(heard.state.getSimulatedRpm())
			&& quiet.state.getPhase() == heard.state.getPhase()
			&& Arrays.equals(quiet.state.copyOfCombustionEventIds(), heard.state.copyOfCombustionEventIds())
			&& Arrays.equals(quiet.state.copyOfTicksSinceCombustion(), heard.state.copyOfTicksSinceCombustion());
		check("consulting the policy every tick changes nothing", same && heard.recordedTicks > 0,
			String.format("fuel %d / %d mB, events %s, recorded for %d ticks", quiet.tank.drawn,
				heard.tank.drawn, Arrays.toString(heard.state.copyOfCombustionEventIds()), heard.recordedTicks));

		Engine r4 = started(4, 0.0F);
		int[] before = r4.state.copyOfCombustionEventIds();
		int drawnBefore = r4.tank.drawn;
		for (int i = 0; i < 400; i++)
			r4.tick();
		int[] after = r4.state.copyOfCombustionEventIds();
		boolean everyCylinderFired = true;
		for (int c = 0; c < 4; c++)
			everyCylinderFired &= after[c] != before[c];
		check("a recorded R4 still fires every cylinder and burns fuel", RecordedEngineAudio.isActive(r4.state)
			&& everyCylinderFired && r4.tank.drawn > drawnBefore,
			String.format("events %s -> %s, %d mB burned", Arrays.toString(before), Arrays.toString(after),
				r4.tank.drawn - drawnBefore));

		boolean onlyR4 = true;
		for (int cylinders = 1; cylinders <= 3; cylinders++)
			onlyR4 &= RecordedEngineAudio.syntheticVoiceAudible(started(cylinders, 0.0F).state);
		check("ENGINE_FIRE is suppressed for a running R4 only", onlyR4
			&& !RecordedEngineAudio.syntheticVoiceAudible(r4.state), "R1-R3 keep their pulses");
	}

	/** TEST A11 - the prototype map, endpoints, monotonicity, clamps. */
	static void rpmMapping() {
		System.out.println("\nPART 7 - mechanical RPM to recorded RPM\n");
		check("idle (64) -> 1000", RecordedEngineAudio.sampleRpm(EngineTuning.IDLE_RPM) == 1000.0F,
			String.format("%.1f", RecordedEngineAudio.sampleRpm(EngineTuning.IDLE_RPM)));
		check("full throttle (192) -> 1500",
			RecordedEngineAudio.sampleRpm(EngineTuning.FULL_THROTTLE_RPM) == 1500.0F,
			String.format("%.1f", RecordedEngineAudio.sampleRpm(EngineTuning.FULL_THROTTLE_RPM)));
		check("128 -> 1250", Math.abs(RecordedEngineAudio.sampleRpm(128.0F) - 1250.0F) < 0.01F,
			String.format("%.2f", RecordedEngineAudio.sampleRpm(128.0F)));
		check("clamped below idle and above full throttle", RecordedEngineAudio.sampleRpm(10.0F) == 1000.0F
			&& RecordedEngineAudio.sampleRpm(400.0F) == 1500.0F, "");
		check("direction ignored", RecordedEngineAudio.sampleRpm(-150.0F) == RecordedEngineAudio.sampleRpm(150.0F),
			"");
		boolean monotone = true;
		float last = -1.0F;
		for (float rpm = 0.0F; rpm <= 256.0F; rpm += 0.25F) {
			float s = RecordedEngineAudio.sampleRpm(rpm);
			monotone &= s >= last;
			last = s;
		}
		check("monotone over 0-256 RPM", monotone, "");
	}

	/** TEST A12-A13 - weights sum sensibly and nothing jumps at the cross-over. */
	static void crossfade() {
		System.out.println("\nPART 8 - the cross-fade\n");
		List<Band> bands = RecordedEngineAudio.R4_BANDS;

		check("at 1000 the idle band alone", RecordedEngineAudio.bandWeight(bands, 0, 1000.0F) == 1.0F
			&& RecordedEngineAudio.bandWeight(bands, 1, 1000.0F) == 0.0F, "");
		check("at 1500 the 1500 band alone", RecordedEngineAudio.bandWeight(bands, 0, 1500.0F) < 1e-6F
			&& RecordedEngineAudio.bandWeight(bands, 1, 1500.0F) == 1.0F, "");
		float a = RecordedEngineAudio.bandWeight(bands, 0, 1250.0F);
		float b = RecordedEngineAudio.bandWeight(bands, 1, 1250.0F);
		check("at 1250 both, equally", Math.abs(a - b) < 1e-5F && Math.abs(a - 0.7071F) < 1e-3F,
			String.format("%.4f / %.4f", a, b));

		float minPower = Float.MAX_VALUE;
		float maxPower = 0.0F;
		float minAmplitude = Float.MAX_VALUE;
		float maxStep = 0.0F;
		boolean inRange = true;
		float prevA = RecordedEngineAudio.bandWeight(bands, 0, 900.0F);
		float prevB = RecordedEngineAudio.bandWeight(bands, 1, 900.0F);
		for (float s = 900.0F; s <= 1600.0F; s += 0.5F) {
			float wa = RecordedEngineAudio.bandWeight(bands, 0, s);
			float wb = RecordedEngineAudio.bandWeight(bands, 1, s);
			inRange &= wa >= 0.0F && wa <= 1.0F && wb >= 0.0F && wb <= 1.0F;
			float power = wa * wa + wb * wb;
			minPower = Math.min(minPower, power);
			maxPower = Math.max(maxPower, power);
			minAmplitude = Math.min(minAmplitude, wa + wb);
			maxStep = Math.max(maxStep, Math.max(Math.abs(wa - prevA), Math.abs(wb - prevB)));
			prevA = wa;
			prevB = wb;
		}
		check("every weight within 0..1", inRange, "");
		check("equal power: squares sum to 1 everywhere", minPower > 0.9999F && maxPower < 1.0001F,
			String.format("%.5f .. %.5f", minPower, maxPower));
		check("no dip: summed amplitude never below 1", minAmplitude >= 0.9999F,
			String.format("min %.4f", minAmplitude));
		// The steepest an equal-power curve over a 500 RPM gap can be is pi/2/500 per RPM.
		check("no discontinuity: max change per 0.5 RPM is tiny", maxStep < 0.002F,
			String.format("max step %.5f", maxStep));

		// The same, as the client actually sees it: mechanical RPM through to volume.
		float maxVolumeStep = 0.0F;
		float prevIdle = -1.0F;
		float prevMid = -1.0F;
		Engine r4 = started(4, 0.0F);
		boolean active = RecordedEngineAudio.isActive(r4.state);
		for (float rpm = EngineTuning.IDLE_RPM - 8.0F; rpm <= EngineTuning.FULL_THROTTLE_RPM + 8.0F; rpm += 0.1F) {
			float s = RecordedEngineAudio.sampleRpm(rpm);
			float idle = RecordedEngineAudio.VOLUME * RecordedEngineAudio.bandWeight(bands, 0, s);
			float mid = RecordedEngineAudio.VOLUME * RecordedEngineAudio.bandWeight(bands, 1, s);
			if (prevIdle >= 0.0F)
				maxVolumeStep = Math.max(maxVolumeStep, Math.max(Math.abs(idle - prevIdle), Math.abs(mid - prevMid)));
			prevIdle = idle;
			prevMid = mid;
		}
		check("volume continuous across the whole running range", active && maxVolumeStep < 0.002F,
			String.format("max step %.5f per 0.1 RPM", maxVolumeStep));
	}

	/** TEST A14 - small corrections only; no chipmunks. */
	static void pitch() {
		System.out.println("\nPART 9 - pitch\n");
		List<Band> bands = RecordedEngineAudio.R4_BANDS;
		check("each band at its own speed plays unshifted",
			RecordedEngineAudio.bandPitch(bands, 0, 1000.0F) == 1.0F
				&& RecordedEngineAudio.bandPitch(bands, 1, 1500.0F) == 1.0F, "");
		float min = Float.MAX_VALUE;
		float max = 0.0F;
		for (float rpm = 0.0F; rpm <= 400.0F; rpm += 0.5F)
			for (int band = 0; band < bands.size(); band++) {
				float p = RecordedEngineAudio.bandPitch(bands, band, RecordedEngineAudio.sampleRpm(rpm));
				min = Math.min(min, p);
				max = Math.max(max, p);
			}
		check("pitch never leaves 0.87 .. 1.15", min >= RecordedEngineAudio.PITCH_MIN - 1e-6F
			&& max <= RecordedEngineAudio.PITCH_MAX + 1e-6F, String.format("%.3f .. %.3f", min, max));
		check("the idle loop is never driven to 1.5x", RecordedEngineAudio.bandPitch(bands, 0, 1500.0F) <= 1.15F,
			String.format("%.3f at 1500", RecordedEngineAudio.bandPitch(bands, 0, 1500.0F)));
		check("pitch rises with speed within a band",
			RecordedEngineAudio.bandPitch(bands, 0, 1100.0F) > RecordedEngineAudio.bandPitch(bands, 0, 1000.0F)
				&& RecordedEngineAudio.bandPitch(bands, 1, 1400.0F) < RecordedEngineAudio.bandPitch(bands, 1, 1500.0F),
			"");
	}

	/** TEST A15 - the cross-fade is not hard-wired to two recordings. */
	static void moreBands() {
		System.out.println("\nPART 10 - future recordings\n");
		List<Band> bands = List.of(new Band("1000", 1000.0F), new Band("1500", 1500.0F), new Band("2000", 2000.0F),
			new Band("2500", 2500.0F), new Band("3000", 3000.0F));
		boolean neighboursOnly = true;
		float minPower = Float.MAX_VALUE;
		float maxPower = 0.0F;
		for (float s = 900.0F; s <= 3100.0F; s += 1.0F) {
			int nonZero = 0;
			for (int i = 0; i < bands.size(); i++)
				if (RecordedEngineAudio.bandWeight(bands, i, s) > 1e-6F)
					nonZero++;
			neighboursOnly &= nonZero >= 1 && nonZero <= 2;
			float power = totalPower(bands, s);
			minPower = Math.min(minPower, power);
			maxPower = Math.max(maxPower, power);
		}
		check("five bands: only neighbours ever play together", neighboursOnly, "");
		check("five bands: equal power throughout", minPower > 0.9999F && maxPower < 1.0001F,
			String.format("%.5f .. %.5f", minPower, maxPower));
		check("five bands: 1750 is 1500 and 2000, half each",
			RecordedEngineAudio.bandWeight(bands, 0, 1750.0F) == 0.0F
				&& Math.abs(RecordedEngineAudio.bandWeight(bands, 1, 1750.0F) - 0.7071F) < 1e-3F
				&& Math.abs(RecordedEngineAudio.bandWeight(bands, 2, 1750.0F) - 0.7071F) < 1e-3F
				&& RecordedEngineAudio.bandWeight(bands, 3, 1750.0F) == 0.0F, "");
	}
}
