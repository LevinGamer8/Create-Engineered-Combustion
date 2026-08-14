import java.util.Locale;
import java.util.Random;

import dev.engineeredcombustion.content.engine.*;

/** Plain-JDK regression checks for coast-down, compression and RPM handoff. */
public class EngineDynamicsTests {

	static final float EPS = 0.0001F;
	static int failures;
	static final EngineInputs DEAD =
		new EngineInputs(true, false, false, 0.0F, 0.0F, EngineTuning.MAX_RPM);
	static final FuelSupply NO_FUEL = new FuelSupply() {
		public boolean hasFuel() { return false; }
		public boolean consume(int amount) { return false; }
	};
	static final FuelSupply FUEL = new FuelSupply() {
		public boolean hasFuel() { return true; }
		public boolean consume(int amount) { return true; }
	};

	public static void main(String[] args) {
		Locale.setDefault(Locale.ROOT);
		compressionCurve();
		coastTargets();
		lubricationInteraction();
		externalHandoff();
		residualDeadband();
		publicationStability();
		System.out.println(failures == 0 ? "\nall dynamics checks passed" : "\n" + failures + " FAILURES");
		System.exit(failures == 0 ? 0 : 1);
	}

	static void compressionCurve() {
		System.out.println("PART 1 - compression curve\n");
		check("zero at 90 degrees", near(factor(90), 0), value(90));
		check("smooth shoulders",
			near(factor(120), 0.25F) && near(factor(150), 0.75F), value(120) + ", " + value(150));
		check("peak at 180-degree TDC",
			near(factor(180), 1) && near(EngineTuning.compressionTorqueAt(180),
				EngineTuning.COMPRESSION_PEAK_TORQUE), value(180));
		check("symmetric after TDC",
			near(factor(150), factor(210)) && near(factor(120), factor(240)) && near(factor(90), factor(270)),
			"150/210, 120/240 and 90/270 match");

		float d0 = oneTickLoss(32, 0);
		float d120 = oneTickLoss(32, 120);
		float d150 = oneTickLoss(32, 150);
		float d180 = oneTickLoss(32, 180);
		check("low-speed resistance grows towards TDC", d0 < d120 && d120 < d150 && d150 < d180,
			String.format("%.3f < %.3f < %.3f < %.3f RPM/tick", d0, d120, d150, d180));
		float reverse = oneTickLoss(-32, 180);
		check("forward and reverse resistance match", near(d180, reverse),
			String.format("forward %.4f, reverse %.4f", d180, reverse));
		EngineState rest = state(0, 180);
		step(rest, LubricationState.NORMAL);
		check("compression creates no motion from rest", rest.getSimulatedRpm() == 0 && rest.getMechanicalRpm() == 0,
			String.format("sim %.3f, mechanical %.3f", rest.getSimulatedRpm(), rest.getMechanicalRpm()));
		System.out.println();
	}

	static void coastTargets() {
		System.out.println("PART 2 - normal-oil coast targets\n");
		Stats hand = stats(32, LubricationState.NORMAL);
		Stats idle = stats(64, LubricationState.NORMAL);
		Stats high = stats(192, LubricationState.NORMAL);
		print(hand); print(idle); print(high);
		check("32 RPM loses 90 percent promptly", hand.maxTen <= 34,
			String.format("worst %.2f s", hand.maxTen / 20.0F));
		check("32 RPM stops without freewheeling", hand.min >= 10 && hand.max <= 40 && hand.maxRev < 1,
			String.format("%.2f-%.2f s, max %.2f rev", hand.min / 20.0F, hand.max / 20.0F, hand.maxRev));
		check("64 RPM meets the 1.5-3 second target", idle.min >= 30 && idle.max <= 60,
			String.format("%.2f-%.2f s", idle.min / 20.0F, idle.max / 20.0F));
		check("192 RPM meets the 3-5 second target", high.min >= 60 && high.max <= 100,
			String.format("%.2f-%.2f s", high.min / 20.0F, high.max / 20.0F));
		check("coast duration grows with starting speed", hand.mean < idle.mean && idle.mean < high.mean,
			String.format("%.1f < %.1f < %.1f ticks", hand.mean, idle.mean, high.mean));
		check("all coast traces decay without reversal", hand.clean && idle.clean && high.clean,
			"72 crank-angle/speed traces");
		System.out.println();
	}

	static void lubricationInteraction() {
		System.out.println("PART 3 - lubrication interaction\n");
		Stats n64 = stats(64, LubricationState.NORMAL), l64 = stats(64, LubricationState.LOW),
			d64 = stats(64, LubricationState.DRY);
		Stats n192 = stats(192, LubricationState.NORMAL), l192 = stats(192, LubricationState.LOW),
			d192 = stats(192, LubricationState.DRY);
		print(n64); print(l64); print(d64);
		check("low oil stops noticeably sooner", l64.mean <= n64.mean * 0.9 && l192.mean <= n192.mean * 0.9,
			String.format("64: %.1f -> %.1f; 192: %.1f -> %.1f ticks", n64.mean, l64.mean, n192.mean, l192.mean));
		check("dry stops much sooner than low oil", d64.mean <= l64.mean * 0.8 && d192.mean <= l192.mean * 0.8,
			String.format("64: %.1f -> %.1f; 192: %.1f -> %.1f ticks", l64.mean, d64.mean, l192.mean, d192.mean));
		check("dry friction is not an instant brake", d64.mean >= 8,
			String.format("64 RPM dry mean %.2f s", d64.mean / 20.0));
		System.out.println();
	}

	static void externalHandoff() {
		System.out.println("PART 4 - imposed-speed handoff\n");
		boolean held = true, released = true, decayed = true;
		for (LubricationState oil : new LubricationState[] { LubricationState.NORMAL, LubricationState.DRY }) {
			for (float rpm : new float[] { 32, -32, 192, -192 }) {
				EngineState engine = state(0, 0);
				for (int tick = 0; tick < 80; tick++) {
					engine.tickRotation(rpm, true, true);
					engine.tickSimulation(DEAD, NO_FUEL, oil(oil), new Random(7));
					held &= near(engine.getMechanicalRpm(), rpm) && near(engine.getSimulatedRpm(), rpm);
				}
				engine.tickRotation(0, false, false);
				released &= near(engine.getMechanicalRpm(), rpm);
				float previous = Math.abs(engine.getSimulatedRpm());
				for (int tick = 0; tick < 160 && engine.getSimulatedRpm() != 0; tick++) {
					engine.tickSimulation(DEAD, NO_FUEL, oil(oil), new Random(8));
					float current = Math.abs(engine.getSimulatedRpm());
					decayed &= current <= previous + EPS;
					if (engine.getSimulatedRpm() != 0)
						decayed &= Math.signum(engine.getSimulatedRpm()) == Math.signum(rpm);
					previous = current;
					engine.tickRotation(0, false, false);
				}
				decayed &= engine.getSimulatedRpm() == 0;
			}
		}
		check("external source holds exact actual RPM", held, "normal/dry at +/-32 and +/-192 RPM");
		check("release inherits exact imposed momentum", released, "first free mechanical tick matches source");
		check("released momentum decays without reversal", decayed, "all eight traces reach zero monotonically");
		System.out.println();
	}

	static void residualDeadband() {
		System.out.println("PART 5 - rest deadband\n");
		boolean settled = true;
		for (float rpm : new float[] { 0.02F, -0.02F }) {
			EngineState engine = state(rpm, 180);
			step(engine, LubricationState.NORMAL);
			settled &= engine.getSimulatedRpm() == 0;
			step(engine, LubricationState.NORMAL);
			settled &= engine.getMechanicalRpm() == 0 && engine.getPhase() == EnginePhase.STOPPED;
		}
		check("+/-0.02 RPM settles without oscillation", settled, "simulated zero, then STOPPED");
		System.out.println();
	}

	static void publicationStability() {
		System.out.println("PART 6 - generated-speed smoothing\n");
		Publication idle = publication(0, EngineTuning.IDLE_RPM);
		Publication full = publication(1, EngineTuning.FULL_THROTTLE_RPM);
		check("idle ripple does not churn Create RPM", idle.changes <= 1 && idle.publishedSpan() <= 4, idle.toString());
		check("full-throttle ripple does not churn Create RPM", full.changes <= 1 && full.publishedSpan() <= 4,
			full.toString());
		System.out.println();
	}

	static Coast coast(float rpm, float angle, LubricationState oil) {
		EngineState engine = state(rpm, angle);
		float previous = Math.abs(rpm), rev = 0;
		int ticks = 0, ten = 0;
		boolean clean = true;
		while (engine.getSimulatedRpm() != 0 && ticks < 300) {
			engine.tickRotation(0, false, false);
			rev += Math.abs(EngineTuning.degreesPerTick(engine.getMechanicalRpm())) / 360.0F;
			engine.tickSimulation(DEAD, NO_FUEL, oil(oil), new Random(11));
			ticks++;
			float current = Math.abs(engine.getSimulatedRpm());
			clean &= current <= previous + EPS;
			if (engine.getSimulatedRpm() != 0)
				clean &= Math.signum(engine.getSimulatedRpm()) == Math.signum(rpm);
			if (ten == 0 && current <= Math.abs(rpm) * 0.1F) ten = ticks;
			previous = current;
		}
		step(engine, oil);
		clean &= engine.getSimulatedRpm() == 0 && engine.getMechanicalRpm() == 0
			&& engine.getPhase() == EnginePhase.STOPPED;
		return new Coast(ticks, ten, rev, clean);
	}

	static Stats stats(float rpm, LubricationState oil) {
		int min = Integer.MAX_VALUE, max = 0, maxTen = 0;
		double sum = 0, rev = 0;
		float maxRev = 0;
		boolean clean = true;
		for (int angle = 0; angle < 360; angle += 15) {
			Coast c = coast(rpm, angle, oil);
			min = Math.min(min, c.ticks); max = Math.max(max, c.ticks); maxTen = Math.max(maxTen, c.ten);
			sum += c.ticks; rev += c.revolutions; maxRev = Math.max(maxRev, c.revolutions); clean &= c.clean;
		}
		return new Stats(rpm, oil, min, max, maxTen, sum / 24, rev / 24, maxRev, clean);
	}

	static Publication publication(float throttle, float target) {
		EngineState engine = new EngineState();
		engine.setPhase(EnginePhase.RUNNING);
		engine.setSimulatedRpm(target);
		engine.setPublishedRpm(target);
		engine.setCrankAngleDegrees(normalize(180 - EngineTuning.degreesPerTick(target) * 0.5F));
		EngineInputs inputs = new EngineInputs(true, true, true, throttle, 0, EngineTuning.MAX_RPM);
		int changes = 0;
		float minPub = Float.POSITIVE_INFINITY, maxPub = Float.NEGATIVE_INFINITY;
		float minSim = Float.POSITIVE_INFINITY, maxSim = Float.NEGATIVE_INFINITY;
		for (int tick = 0; tick < 3000; tick++) {
			float shaft = engine.getPublishedRpm();
			engine.tickRotation(shaft, shaft != 0, false);
			boolean changed = engine.tickSimulation(inputs, FUEL, oil(LubricationState.NORMAL), new Random(12));
			if (tick < 2000) continue;
			if (changed && engine.getPublishedRpm() != 0) changes++;
			minPub = Math.min(minPub, engine.getPublishedRpm()); maxPub = Math.max(maxPub, engine.getPublishedRpm());
			minSim = Math.min(minSim, engine.getSimulatedRpm()); maxSim = Math.max(maxSim, engine.getSimulatedRpm());
		}
		return new Publication(changes, minPub, maxPub, minSim, maxSim);
	}

	static float oneTickLoss(float rpm, float postAngle) {
		EngineState engine = state(rpm, normalize(postAngle - EngineTuning.degreesPerTick(rpm)));
		step(engine, LubricationState.NORMAL);
		return Math.abs(rpm) - Math.abs(engine.getSimulatedRpm());
	}

	static EngineState state(float rpm, float angle) {
		EngineState engine = new EngineState();
		engine.setPhase(EnginePhase.COASTING);
		engine.setSimulatedRpm(rpm);
		engine.setCrankAngleDegrees(angle);
		return engine;
	}

	static void step(EngineState engine, LubricationState lubrication) {
		engine.tickRotation(0, false, false);
		engine.tickSimulation(DEAD, NO_FUEL, oil(lubrication), new Random(13));
	}

	static OilSupply oil(LubricationState lubrication) {
		return new OilSupply() {
			public LubricationState lubrication() { return lubrication; }
			public boolean consume(int amount) { return true; }
		};
	}

	static float factor(float angle) { return EngineTuning.compressionFactorAt(angle); }
	static boolean near(float a, float b) { return Math.abs(a - b) <= EPS; }
	static float normalize(float a) { a %= 360; return a < 0 ? a + 360 : a; }
	static String value(float a) { return String.format("%.0f degrees -> %.3f", a, factor(a)); }
	static void print(Stats s) {
		System.out.printf("      %3.0f RPM %-6s %.2f-%.2f s, mean %.2f s / %.2f rev%n",
			s.rpm, s.oil, s.min / 20.0F, s.max / 20.0F, s.mean / 20.0, s.meanRev);
	}
	static void check(String name, boolean ok, String detail) {
		System.out.printf("%s %-58s %s%n", ok ? "PASS" : "FAIL", name, detail);
		if (!ok) failures++;
	}

	record Coast(int ticks, int ten, float revolutions, boolean clean) {}
	record Stats(float rpm, LubricationState oil, int min, int max, int maxTen,
		double mean, double meanRev, float maxRev, boolean clean) {}
	record Publication(int changes, float minPub, float maxPub, float minSim, float maxSim) {
		float publishedSpan() { return maxPub - minPub; }
		public String toString() {
			return String.format("%d changes, published %.0f..%.0f, simulated %.1f..%.1f RPM",
				changes, minPub, maxPub, minSim, maxSim);
		}
	}
}
