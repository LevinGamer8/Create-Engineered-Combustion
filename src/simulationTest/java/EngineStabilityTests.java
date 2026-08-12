import dev.engineeredcombustion.content.engine.*;

/**
 * Drives the real {@code EngineState} through the engine-stability milestone's
 * regression matrix: the multi-engine free-power exploit, and the RPM snap when
 * an external kinetic source is removed.
 *
 * <p>This is not a Minecraft test and needs no Minecraft. {@code EngineState} and
 * everything it touches are deliberately free of Minecraft, NeoForge and Create
 * types, and this file is the payoff for that discipline: the invariant that
 * matters most - <b>an engine that is not burning fuel contributes zero Stress
 * Capacity, however fast something else spins it</b> - can be compiled and
 * <i>executed</i> with nothing but a JDK.
 *
 * <pre>
 *   javac -d /tmp/ec-sim $(ls src/main/java/dev/engineeredcombustion/content/engine/*.java \
 *                           | grep -v EngineComponents | grep -v CombustionAudio)
 *   javac -cp /tmp/ec-sim -d /tmp/ec-sim tools/EngineStabilityTests.java
 *   java  -cp /tmp/ec-sim EngineStabilityTests
 * </pre>
 *
 * <h2>Why this is not a mock of Create</h2>
 * {@link Network} below reproduces two pieces of arithmetic taken verbatim from
 * Create 6.0.10, and nothing else:
 * <ul>
 * <li>{@code KineticNetwork#getActualCapacityOf} is
 * {@code sources.get(be) * |be.getGeneratedSpeed()|} - capacity per RPM times the
 * source's own <i>generated</i> speed;</li>
 * <li>{@code GeneratingKineticBlockEntity#applyNewSpeed} hands the network to
 * whichever generator is fastest, and every other member follows it.</li>
 * </ul>
 * That is the whole of what the exploit depended on, so testing against it tests
 * the real thing. Mocking any more of Create would be testing the mock.
 *
 * <p>Exits non-zero on any failure.
 */
public class EngineStabilityTests {

	static int failures = 0;

	/** Throttle every test engine runs at: closed, i.e. a 64 RPM idle. */
	static final float IDLE_THROTTLE = 0.0F;

	// ---------------------------------------------------------------- fixtures

	/** A carburetor with a chosen amount of fuel in it. */
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

	/** A full oil sump, so lubrication never confounds a result. */
	static final OilSupply OIL = new OilSupply() {
		public LubricationState lubrication() {
			return LubricationState.NORMAL;
		}

		public boolean consume(int mb) {
			return true;
		}
	};

	/** One engine on the shared shaft: its simulation, its tank, and its ignition switch. */
	static class Engine {
		final EngineState state = new EngineState();
		final Tank tank;
		final java.util.Random random;
		boolean ignition = true;
		boolean sparkPlug = true;
		boolean structure = true;
		float throttle = IDLE_THROTTLE;

		Engine(int fuelMb, long seed) {
			tank = new Tank(fuelMb);
			random = new java.util.Random(seed);
		}

		/**
		 * Capacity this engine contributes to its network, in SU, computed exactly as
		 * {@code KineticNetwork#getActualCapacityOf} does: the registered capacity per
		 * RPM times the magnitude of the speed this engine <i>generates</i>.
		 *
		 * <p>The mod gates that in two independent places - generated speed, and
		 * {@code calculateAddedStressCapacity} - and either one alone is enough to
		 * make this zero. This models the first; a dead engine that slipped past it
		 * would still be caught by the second at runtime.
		 */
		double capacitySu() {
			return EngineTuning.STRESS_CAPACITY_PER_RPM * Math.abs(state.getPublishedRpm());
		}
	}

	/**
	 * A Create kinetic network with several engines and, optionally, one external
	 * source (a motor, a water wheel, another mod's generator - anything).
	 *
	 * <p>The network speed is whichever source is turning fastest, which is
	 * Create's own rule; every other member is dragged along at that speed and
	 * reports a kinetic source of its own, which is what {@code externallyDriven}
	 * means to the simulation.
	 */
	static class Network {
		final Engine[] engines;
		float externalRpm;

		Network(Engine... engines) {
			this.engines = engines;
		}

		float speed() {
			float fastest = externalRpm;
			for (Engine engine : engines)
				if (Math.abs(engine.state.getPublishedRpm()) > Math.abs(fastest))
					fastest = engine.state.getPublishedRpm();
			return fastest;
		}

		double capacitySu() {
			double total = 0.0;
			for (Engine engine : engines)
				total += engine.capacitySu();
			return total;
		}

		void tick() {
			float networkRpm = speed();
			for (Engine engine : engines) {
				// Create hands the source to the fastest generator; everyone else has a
				// source pointer, which is precisely "something else is turning me".
				boolean isSource = networkRpm != 0.0F && engine.state.getPublishedRpm() == networkRpm;
				engine.state.tickRotation(networkRpm, networkRpm != 0.0F, !isSource);
				engine.state.tickSimulation(
					new EngineInputs(engine.structure, engine.ignition, engine.sparkPlug, engine.throttle, 0.0F,
						EngineTuning.MAX_RPM),
					engine.tank, OIL, engine.random);
			}
		}

		void run(int ticks) {
			for (int i = 0; i < ticks; i++)
				tick();
		}
	}

	/** Runs one engine with an explicitly imposed shaft speed, the way a fast neighbouring network would. */
	static void driveExternally(Engine engine, float shaftRpm, int ticks) {
		for (int i = 0; i < ticks; i++) {
			engine.state.tickRotation(shaftRpm, shaftRpm != 0.0F, true);
			engine.state.tickSimulation(new EngineInputs(engine.structure, engine.ignition, engine.sparkPlug,
				engine.throttle, 0.0F, EngineTuning.MAX_RPM), engine.tank, OIL, engine.random);
		}
	}

	/** Runs one engine with nothing at all attached to it, so it freewheels. */
	static void runDetached(Engine engine, int ticks) {
		for (int i = 0; i < ticks; i++) {
			engine.state.tickRotation(0.0F, false, false);
			engine.state.tickSimulation(new EngineInputs(engine.structure, engine.ignition, engine.sparkPlug,
				engine.throttle, 0.0F, EngineTuning.MAX_RPM), engine.tank, OIL, engine.random);
		}
	}

	static void check(String name, boolean ok, String detail) {
		System.out.printf("%s %-62s %s%n", ok ? "PASS" : "FAIL", name, detail);
		if (!ok)
			failures++;
	}

	/** Starts an engine the way a player does: crank it until it catches. */
	static void start(Engine engine) {
		Network net = new Network(engine);
		net.externalRpm = 48.0F;
		net.run(200);
		net.externalRpm = 0.0F;
		net.run(200);
	}

	// -------------------------------------------------------------------- tests

	public static void main(String[] args) {
		multiEngineCapacity();
		momentumContinuity();
		System.out.println(failures == 0 ? "\nall checks passed" : "\n" + failures + " FAILURES");
		System.exit(failures == 0 ? 0 : 1);
	}

	/**
	 * PART 1 - the free-power exploit.
	 *
	 * <p>Every one of these used to fail. A dead engine kept in
	 * {@code EnginePhase#COASTING} by a neighbour spinning the shared shaft had its
	 * simulated speed overwritten with the network's speed every tick, published
	 * that as its own generated speed, and so contributed a full engine's Stress
	 * Capacity forever on no fuel at all.
	 */
	static void multiEngineCapacity() {
		System.out.println("PART 1 - multi-engine Stress Capacity\n");

		// TEST A - one fuelled engine alone.
		Engine a = new Engine(4000, 1);
		start(a);
		Network solo = new Network(a);
		solo.run(200);
		double soloCapacity = solo.capacitySu();
		float soloSpeed = solo.speed();
		check("TEST A  one fuelled engine generates", a.state.isActivelyGenerating() && soloCapacity > 0.0,
			String.format("%.0f RPM, %.0f su", soloSpeed, soloCapacity));

		// TEST B - a second, fully assembled but EMPTY engine on the same network.
		Engine b = new Engine(0, 2);
		Network pair = new Network(a, b);
		pair.run(200);
		check("TEST B  empty engine is rotated by the network", Math.abs(b.state.getMechanicalRpm()) > 1.0F,
			String.format("%.0f RPM at the empty engine", b.state.getMechanicalRpm()));
		check("TEST B  empty engine generates NOTHING", !b.state.isActivelyGenerating() && b.capacitySu() == 0.0,
			String.format("generating=%s, %.0f su", b.state.isActivelyGenerating(), b.capacitySu()));
		check("TEST B  total capacity is still ONE engine",
			near(pair.capacitySu(), soloCapacity, soloCapacity * 0.15),
			String.format("%.0f su vs %.0f su alone", pair.capacitySu(), soloCapacity));

		// TEST C - fuel the second engine too. Both genuinely run; both may generate.
		b.tank.mb = 4000;
		pair.run(400);
		check("TEST C  both fuelled engines generate",
			a.state.isActivelyGenerating() && b.state.isActivelyGenerating(),
			"A=" + a.state.getPhase() + " B=" + b.state.getPhase());
		check("TEST C  total capacity reflects TWO running engines",
			pair.capacitySu() > soloCapacity * 1.7,
			String.format("%.0f su vs %.0f su for one", pair.capacitySu(), soloCapacity));

		// TEST D - let engine B run dry while both are running.
		b.tank.mb = 0;
		pair.run(20);
		check("TEST D  starved engine loses capacity at once", b.capacitySu() == 0.0,
			String.format("B: %s, %.0f su", b.state.getPhase(), b.capacitySu()));
		check("TEST D  the fuelled engine carries on", a.state.isActivelyGenerating() && a.capacitySu() > 0.0,
			String.format("A: %s, %.0f su", a.state.getPhase(), a.capacitySu()));
		pair.run(200);
		check("TEST D  starved engine is still turned by the network",
			Math.abs(b.state.getMechanicalRpm()) > 1.0F && b.capacitySu() == 0.0,
			String.format("B: %.0f RPM, %.0f su", b.state.getMechanicalRpm(), b.capacitySu()));
		check("TEST D  total capacity has fallen back to ONE engine",
			near(pair.capacitySu(), soloCapacity, soloCapacity * 0.15),
			String.format("%.0f su", pair.capacitySu()));

		// TEST E - ten engines, exactly one of them fuelled.
		Engine[] ten = new Engine[10];
		for (int i = 0; i < ten.length; i++)
			ten[i] = new Engine(i == 0 ? 20000 : 0, 100 + i);
		start(ten[0]);
		Network farm = new Network(ten);
		farm.run(600);
		int generating = 0;
		for (Engine engine : ten)
			if (engine.state.isActivelyGenerating())
				generating++;
		check("TEST E  ten engines, one fuelled: only one generates", generating == 1,
			generating + " of 10 generating");
		check("TEST E  NO ten-times power exploit",
			farm.capacitySu() < soloCapacity * 1.25,
			String.format("%.0f su, against %.0f su for one engine and %.0f su for the exploit",
				farm.capacitySu(), soloCapacity, soloCapacity * 10.0));
		int spun = 0;
		for (int i = 1; i < ten.length; i++)
			if (Math.abs(ten[i].state.getMechanicalRpm()) > 1.0F)
				spun++;
		check("TEST E  the other nine are still mechanically rotated", spun == 9, spun + " of 9 turning");

		// STARVATION - generation ends with the LAST PAID CHARGE, not with the last
		// millibucket.
		//
		// This assertion used to demand that generation stop on the very next tick
		// after the tank read empty, and that was half a tick too eager. A charge is
		// paid for when it is lit and then pushes for the following half revolution;
		// the tank can easily empty in the middle of that stroke. Cutting generation
		// there told Create the engine produced nothing while it was demonstrably
		// still accelerating the crankshaft - see EngineState#stillMakingCombustionTorque.
		//
		// So the invariant is now stated the way the machine actually works: the
		// engine keeps generating for at most the remainder of one power stroke, and
		// then stops for good. The upper bound is what still closes the exploit - a
		// dry engine must not go on claiming capacity for revolutions on end.
		Engine dying = new Engine(4000, 7);
		start(dying);
		Network single = new Network(dying);
		single.run(100);
		boolean wasGenerating = dying.state.isActivelyGenerating();
		dying.tank.mb = 0;

		// Half a revolution at idle is about 9 ticks; allow one whole revolution.
		int ticksStillGenerating = 0;
		while (dying.state.isActivelyGenerating() && ticksStillGenerating < 200) {
			single.tick();
			ticksStillGenerating++;
		}
		int oneRevolutionAtIdle = Math.round(1200.0F / EngineTuning.IDLE_RPM) + 2;
		check("EXTRA   fuel starvation ends generation within one revolution",
			wasGenerating && !dying.state.isActivelyGenerating() && ticksStillGenerating <= oneRevolutionAtIdle,
			String.format("was=%s, stopped generating after %d tick(s), budget %d", wasGenerating,
				ticksStillGenerating, oneRevolutionAtIdle));
		check("EXTRA   and its capacity is zero the instant it does",
			dying.capacitySu() == 0.0, String.format("%.0f su", dying.capacitySu()));

		// IGNITION OFF - the same invariant reached a different way.
		Engine switchedOff = new Engine(4000, 8);
		start(switchedOff);
		Network one = new Network(switchedOff);
		one.run(100);
		switchedOff.ignition = false;
		one.tick();
		check("EXTRA   ignition off ends generation within one tick",
			!switchedOff.state.isActivelyGenerating() && switchedOff.capacitySu() == 0.0,
			String.format("%s, %.0f su", switchedOff.state.getPhase(), switchedOff.capacitySu()));

		// OVERSPEED - motoring a running engine faster than its own throttle could
		// ever drive it must not multiply the capacity it hands out.
		Engine oversped = new Engine(20000, 9);
		start(oversped);
		double idleCapacity = oversped.capacitySu();
		driveExternally(oversped, 200.0F, 100);
		check("EXTRA   overspeed does not inflate generated capacity",
			oversped.capacitySu() < idleCapacity * 1.6,
			String.format("%.0f su at 200 RPM against %.0f su idling", oversped.capacitySu(), idleCapacity));
		System.out.println();
	}

	/**
	 * PART 2 - momentum across a change of kinetic source.
	 *
	 * <p>The engine used to keep its own idea of its speed while an external
	 * network span it, so disconnecting a fast source revealed that stale number
	 * and the engine appeared to teleport from 200 RPM back to its idle.
	 */
	static void momentumContinuity() {
		System.out.println("PART 2 - momentum across kinetic source changes\n");

		// TEST 5 - a running engine driven at 200 RPM, then disconnected.
		Engine fast = new Engine(20000, 11);
		start(fast);
		driveExternally(fast, 200.0F, 60);
		check("TEST 5  external drive is absorbed into the engine's momentum",
			near(fast.state.getSimulatedRpm(), 200.0F, 1.0F),
			String.format("internal %.1f RPM against 200 imposed", fast.state.getSimulatedRpm()));

		float atDisconnect = fast.state.getSimulatedRpm();
		runDetached(fast, 1);
		check("TEST 5  no snap on the tick the source is removed",
			Math.abs(fast.state.getMechanicalRpm() - atDisconnect) < 5.0F,
			String.format("%.1f -> %.1f RPM", atDisconnect, fast.state.getMechanicalRpm()));

		float[] curve = new float[6];
		for (int i = 0; i < curve.length; i++) {
			runDetached(fast, 20);
			curve[i] = fast.state.getMechanicalRpm();
		}
		check("TEST 5  it coasts down gradually instead of stepping",
			descendsSmoothly(atDisconnect, curve, 40.0F),
			String.format("%.0f -> %s", atDisconnect, format(curve)));
		runDetached(fast, 400);
		check("TEST 5  and settles back on its throttle target",
			near(fast.state.getSimulatedRpm(), EngineTuning.IDLE_RPM, 12.0F),
			String.format("%.1f RPM against an idle of %.0f", fast.state.getSimulatedRpm(),
				EngineTuning.IDLE_RPM));

		// TEST 6 - ignition off, driven fast, then disconnected: coast to a stop.
		Engine dead = new Engine(0, 12);
		dead.ignition = false;
		driveExternally(dead, 200.0F, 40);
		check("TEST 6  an unlit engine still takes on the imposed speed",
			near(dead.state.getSimulatedRpm(), 200.0F, 1.0F),
			String.format("%.1f RPM", dead.state.getSimulatedRpm()));
		float deadStart = dead.state.getSimulatedRpm();
		float[] deadCurve = new float[6];
		for (int i = 0; i < deadCurve.length; i++) {
			runDetached(dead, 20);
			deadCurve[i] = dead.state.getMechanicalRpm();
		}
		// The per-second step allowance is larger than TEST 5's because this engine
		// starts from 200 RPM rather than from idle, and coast drag scales with speed.
		// It is still an order of magnitude below "jumped to zero", which is the thing
		// this check exists to catch. The coast-down TIMES are asserted properly in
		// EngineCoastDownTests; this only asserts the shape.
		check("TEST 6  it coasts down rather than jumping to zero",
			descendsSmoothly(deadStart, deadCurve, 60.0F),
			String.format("%.0f -> %s", deadStart, format(deadCurve)));
		runDetached(dead, 600);
		check("TEST 6  and comes to a complete stop",
			dead.state.getPhase() == EnginePhase.STOPPED && dead.state.getMechanicalRpm() == 0.0F,
			dead.state.getPhase() + " at " + dead.state.getMechanicalRpm() + " RPM");

		// ROTATION DIRECTION - momentum has a sign, and it survives the disconnect.
		Engine reversed = new Engine(0, 13);
		reversed.ignition = false;
		driveExternally(reversed, -120.0F, 40);
		check("EXTRA   backwards drive gives backwards momentum",
			near(reversed.state.getSimulatedRpm(), -120.0F, 1.0F),
			String.format("%.1f RPM", reversed.state.getSimulatedRpm()));
		runDetached(reversed, 40);
		check("EXTRA   it coasts down in the direction it was turning",
			reversed.state.getMechanicalRpm() < 0.0F
				&& reversed.state.getMechanicalRpm() > -120.0F,
			String.format("%.1f RPM", reversed.state.getMechanicalRpm()));
		runDetached(reversed, 600);
		check("EXTRA   and stops without ever rotating forwards",
			reversed.state.getMechanicalRpm() == 0.0F,
			String.format("%.1f RPM", reversed.state.getMechanicalRpm()));

		// A hand crank must still be able to out-drive a stopped engine and then let
		// the engine out-run it once it catches - the case the absorb rule must not
		// break.
		Engine cranked = new Engine(20000, 14);
		Network hand = new Network(cranked);
		hand.externalRpm = 32.0F;
		hand.run(400);
		check("EXTRA   a hand crank still starts the engine and is then out-run",
			cranked.state.isActivelyGenerating() && cranked.state.getSimulatedRpm() > 40.0F,
			String.format("%s at %.1f RPM on a 32 RPM crank", cranked.state.getPhase(),
				cranked.state.getSimulatedRpm()));
		System.out.println();
	}

	// ------------------------------------------------------------------ helpers

	static boolean near(double value, double expected, double tolerance) {
		return Math.abs(value - expected) <= tolerance;
	}

	/**
	 * Whether a speed trace falls monotonically from its starting value without any
	 * single sample dropping by more than {@code maxStep}.
	 *
	 * <p>This is the actual assertion behind "no snap": a snap is a large step, and
	 * a coast is a sequence of small ones. Testing the first sample alone would
	 * pass an engine that snapped one tick later.
	 */
	static boolean descendsSmoothly(float from, float[] samples, float maxStep) {
		float previous = from;
		for (float sample : samples) {
			float drop = Math.abs(previous) - Math.abs(sample);
			if (drop < 0.0F || drop > maxStep)
				return false;
			previous = sample;
		}
		return Math.abs(previous) < Math.abs(from);
	}

	static String format(float[] samples) {
		StringBuilder out = new StringBuilder();
		for (float sample : samples)
			out.append(out.length() == 0 ? "" : " -> ")
				.append(String.format("%.0f", sample));
		return out.toString();
	}
}
