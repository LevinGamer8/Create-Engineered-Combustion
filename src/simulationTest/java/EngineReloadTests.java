import dev.engineeredcombustion.content.engine.*;

/**
 * Drives the real {@code EngineState} through a save and a reload, and asserts
 * the one invariant the reconciliation milestone exists for:
 *
 * <blockquote><b>a world reload must never leave the speed Create is running on
 * permanently stale relative to the engine's own physical state.</b></blockquote>
 *
 * <p>Like {@code EngineStabilityTests} this needs no Minecraft. {@code EngineState}
 * is free of Minecraft, NeoForge and Create types, so the whole reload can be
 * played out with nothing but a JDK:
 *
 * <pre>
 *   javac -d /tmp/ec-sim $(ls src/main/java/dev/engineeredcombustion/content/engine/*.java \
 *                           | grep -v EngineComponents | grep -v CombustionAudio)
 *   javac -cp /tmp/ec-sim -d /tmp/ec-sim tools/EngineReloadTests.java
 *   java  -cp /tmp/ec-sim EngineReloadTests
 * </pre>
 *
 * <h2>What is modelled, and what is taken from Create verbatim</h2>
 * {@link Network} reproduces three pieces of Create 6.0.10 and nothing else:
 * <ul>
 * <li>a kinetic network runs at the speed of its fastest source, and every other
 * member follows it and reports a source of its own - which is what
 * {@code hasSource()}, and therefore "externally driven", means to the
 * simulation;</li>
 * <li>that speed is a <i>held</i> value. It changes only when a source publishes,
 * exactly as {@code GeneratingKineticBlockEntity#applyNewSpeed} does, and never
 * because the engine's internal speed drifted;</li>
 * <li>{@code KineticBlockEntity#write} persists that speed, so a reloaded world
 * starts with Create holding whatever it was holding when the world was saved.
 * <b>That is the whole shape of the bug</b>: Create's copy and the engine's copy
 * are two state systems, and the reload has to reconcile them.</li>
 * </ul>
 *
 * <p>{@link Save} is the set of NBT keys {@code CrankshaftBlockEntity} actually
 * writes to disk. Note what is <i>not</i> in it: the published RPM. It is a
 * derivative of the simulated RPM and is reconstructed through
 * {@link EngineState#restoreAfterLoad(boolean)} rather than restored beside it.
 *
 * <p>Exits non-zero on any failure.
 */
public class EngineReloadTests {

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

	static class Sump implements OilSupply {
		LubricationState state = LubricationState.NORMAL;

		public LubricationState lubrication() {
			return state;
		}

		public boolean consume(int mb) {
			return true;
		}
	}

	/**
	 * Everything {@code CrankshaftBlockEntity#write} puts on disk that describes
	 * rotation, plus Create's own persisted network speed.
	 */
	static class Save {
		// --- the engine's own state, ours to persist
		float crankAngle;
		EnginePhase phase;
		float simulatedRpm;
		boolean generating;
		int ticksSinceCombustion;
		// --- Create's state, persisted by KineticBlockEntity
		float networkSpeed;
	}

	/** One engine: its simulation, its supplies and its post-load bookkeeping. */
	static class Engine {
		final EngineState state = new EngineState();
		final Tank tank;
		final Sump sump = new Sump();
		final java.util.Random random;

		boolean ignition = true;
		boolean sparkPlug = true;
		boolean structure = true;
		float throttle;
		float loadFactor;

		/** Mirrors CrankshaftBlockEntity's two fields of the same names. */
		boolean needsPostLoadReconcile;
		boolean wasExternallyDriven;

		/** How many times Create has been told a new generated speed. */
		int networkUpdates;

		Engine(int fuelMb, long seed) {
			tank = new Tank(fuelMb);
			random = new java.util.Random(seed);
		}

		/** Capacity this engine hands its network, per {@code KineticNetwork#getActualCapacityOf}. */
		double capacitySu() {
			return state.isActivelyGenerating()
				? EngineTuning.STRESS_CAPACITY_PER_RPM * Math.abs(state.getPublishedRpm())
				: 0.0;
		}

		/** One server tick, in the order {@code CrankshaftBlockEntity#tick} runs it. */
		void tick(Network net) {
			float shaftSpeed = net.speed;
			state.tickRotation(shaftSpeed, shaftSpeed != 0.0F, net.isDrivenByOthers(this));

			if (wasExternallyDriven != state.isExternallyDriven()) {
				wasExternallyDriven = state.isExternallyDriven();
				state.requestGeneratedRepublish();
			}

			boolean changed = state.tickSimulation(
				new EngineInputs(structure, ignition, sparkPlug, throttle, loadFactor, EngineTuning.MAX_RPM),
				tank, sump, random);

			// The reconciliation: the simulation has just re-derived generation from
			// the world, so Create is told the result whether or not the latched value
			// happened to change.
			boolean reconciling = needsPostLoadReconcile;
			needsPostLoadReconcile = false;
			if (reconciling || changed) {
				networkUpdates++;
				net.republish();
			}
		}

		Save save() {
			Save save = new Save();
			save.crankAngle = state.getCrankAngleDegrees();
			save.phase = state.getPhase();
			save.simulatedRpm = state.getSimulatedRpm();
			save.generating = state.isActivelyGenerating();
			save.ticksSinceCombustion = state.getTicksSinceCombustion();
			return save;
		}

		/** {@code CrankshaftBlockEntity#read} with {@code clientPacket == false}. */
		void load(Save save) {
			state.setCrankAngleDegrees(save.crankAngle);
			state.setPhase(save.phase);
			state.setSimulatedRpm(save.simulatedRpm);
			state.setTicksSinceCombustion(save.ticksSinceCombustion);
			state.restoreAfterLoad(save.generating);
			needsPostLoadReconcile = true;
			wasExternallyDriven = false;
		}
	}

	/**
	 * One Create kinetic network. Its speed is <i>held</i>: it changes only when a
	 * source publishes, which is what makes a stale value able to outlive the state
	 * it described.
	 */
	static class Network {
		final Engine[] engines;
		float externalRpm;
		float speed;

		/**
		 * The engine Create has as this network's root, or null when the root is the
		 * external source or there is none.
		 *
		 * <p>Create's {@code KineticBlockEntity#source} is null for exactly one block
		 * per network - the one driving it - and persisted for every other. That
		 * pointer, not the speed, is what {@code hasSource()} answers and therefore
		 * what the simulation reads as "somebody else is turning me". It survives a
		 * save, which is why a reloaded engine can be the root of a network running at
		 * a speed it no longer generates: the exact situation this file exists for.
		 */
		Engine source;

		Network(Engine... engines) {
			this.engines = engines;
		}

		/** The fastest source wins, and everything else on the network follows it. */
		void republish() {
			float fastest = externalRpm;
			Engine root = null;
			for (Engine engine : engines) {
				float published = engine.state.getPublishedRpm();
				if (Math.abs(published) > Math.abs(fastest)) {
					fastest = published;
					root = engine;
				}
			}
			speed = fastest;
			source = root;
		}

		/** Whether something other than this engine is what the network is running on. */
		boolean isDrivenByOthers(Engine engine) {
			return speed != 0.0F && source != engine;
		}

		double capacitySu() {
			double total = 0.0;
			for (Engine engine : engines)
				total += engine.capacitySu();
			return total;
		}

		void run(int ticks) {
			for (int i = 0; i < ticks; i++)
				for (Engine engine : engines)
					engine.tick(this);
		}
	}

	// ------------------------------------------------------------------ helpers

	/** Starts an engine the way a player does: crank it until it catches. */
	static Network started(Engine engine) {
		Network net = new Network(engine);
		net.externalRpm = 48.0F;
		net.republish();
		net.run(200);
		net.externalRpm = 0.0F;
		net.republish();
		net.run(400);
		return net;
	}

	/**
	 * Saves every engine on a network, throws the whole thing away, and loads it
	 * back - Create's held speed included, because Create persists that too.
	 */
	static Network saveAndReload(Network net, Engine... reloaded) {
		Save[] saves = new Save[net.engines.length];
		for (int i = 0; i < saves.length; i++) {
			saves[i] = net.engines[i].save();
			saves[i].networkSpeed = net.speed;
		}

		Network loaded = new Network(reloaded);
		loaded.externalRpm = net.externalRpm;
		// Create restores the speed it was holding, and the source pointers that go
		// with it. Nothing has reconciled either yet.
		loaded.speed = net.speed;
		for (int i = 0; i < net.engines.length; i++)
			if (net.source == net.engines[i])
				loaded.source = reloaded[i];
		for (int i = 0; i < saves.length; i++) {
			reloaded[i].load(saves[i]);
			reloaded[i].throttle = net.engines[i].throttle;
			reloaded[i].loadFactor = net.engines[i].loadFactor;
			reloaded[i].ignition = net.engines[i].ignition;
			reloaded[i].sparkPlug = net.engines[i].sparkPlug;
			reloaded[i].structure = net.engines[i].structure;
			reloaded[i].sump.state = net.engines[i].sump.state;
			reloaded[i].tank.mb = net.engines[i].tank.mb;
		}
		return loaded;
	}

	static void check(String name, boolean ok, String detail) {
		System.out.printf("%s %-58s %s%n", ok ? "PASS" : "FAIL", name, detail);
		if (!ok)
			failures++;
	}

	static boolean near(double value, double expected, double tolerance) {
		return Math.abs(value - expected) <= tolerance;
	}

	// -------------------------------------------------------------------- tests

	public static void main(String[] args) {
		steadyStateConvergence();
		reloadAtEveryThrottle();
		loadedEngineReload();
		stoppedEngineReload();
		externalDriveReload();
		multiEngineReload();
		repeatedReloads();
		networkChurn();
		System.out.println(failures == 0 ? "\nall checks passed" : "\n" + failures + " FAILURES");
		System.exit(failures == 0 ? 0 : 1);
	}

	/**
	 * The property the old deadband did not have: a running engine's published
	 * speed converges on its actual speed, from either side, rather than parking
	 * wherever the last threshold crossing happened to leave it.
	 */
	static void steadyStateConvergence() {
		System.out.println("PART 1 - the published speed converges\n");
		for (float throttle : new float[] { 0.0F, 0.25F, 0.5F, 0.75F, 1.0F }) {
			Engine engine = new Engine(2000000, 21);
			engine.throttle = throttle;
			Network net = started(engine);
			net.run(1200);
			// Compared against the filtered output rather than the instantaneous
			// speed, because that is what the publishing rule is answerable for: one
			// cylinder firing once per revolution really does swing the crankshaft a
			// few RPM either side of its equilibrium, and reproducing that swing on
			// the kinetic network is precisely what must not happen.
			check(String.format("throttle %3.0f%%  published tracks the engine", throttle * 100.0F),
				near(engine.state.getPublishedRpm(), engine.state.getOutputRpm(),
					EngineTuning.NETWORK_RPM_FINE_DELTA)
					&& near(engine.state.getPublishedRpm(), engine.state.getTargetRpm(), 4.0F),
				String.format("published %.0f, filtered output %.1f, instantaneous %.1f, target %.0f",
					engine.state.getPublishedRpm(), engine.state.getOutputRpm(), engine.state.getSimulatedRpm(),
					engine.state.getTargetRpm()));
		}
		System.out.println();
	}

	/** TESTS 1, 2 and 3 - idle, full and intermediate throttle across a reload. */
	static void reloadAtEveryThrottle() {
		System.out.println("PART 2 - save and rejoin at each throttle\n");
		for (float throttle : new float[] { 0.0F, 0.5F, 1.0F }) {
			Engine engine = new Engine(2000000, 22);
			engine.throttle = throttle;
			Network net = started(engine);
			net.run(1200);

			float targetRpm = engine.state.getTargetRpm();
			float beforeSpeed = net.speed;

			// The save is deliberately taken with Create parked well off the engine's
			// real speed - the reported symptom, and what a transient at save time
			// leaves behind.
			Engine reloaded = new Engine(engine.tank.mb, 23);
			Network back = saveAndReload(net, reloaded);
			back.speed = targetRpm > 100.0F ? 184.0F : 68.0F;

			back.run(1);
			// The property is that the engine PUBLISHED its own output rather than
			// leaving Create holding whatever it happened to be holding - not that the
			// number changed. Those were the same test while a firing engine's speed
			// was smooth; a four-stroke single ripples about 15 RPM peak to peak at
			// idle, so the parked value is now sometimes a value the engine would
			// legitimately publish, and "it differs" started failing on a coincidence.
			check(String.format("throttle %3.0f%%  reconciles on the first tick", throttle * 100.0F),
				reloaded.state.isActivelyGenerating()
					&& near(back.speed, reloaded.state.getPublishedRpm(), 0.01F),
				String.format("Create was holding %.0f, now %.0f (engine publishes %.0f)",
					targetRpm > 100.0F ? 184.0F : 68.0F, back.speed, reloaded.state.getPublishedRpm()));

			back.run(400);
			check(String.format("throttle %3.0f%%  settles back on its operating point", throttle * 100.0F),
				near(back.speed, targetRpm, 4.0F) && near(reloaded.state.getOutputRpm(), targetRpm, 6.0F),
				String.format("%.0f RPM on the network, %.1f engine output, target %.0f (was %.0f before the save)",
					back.speed, reloaded.state.getOutputRpm(), targetRpm, beforeSpeed));

			check(String.format("throttle %3.0f%%  and no permanent stale offset", throttle * 100.0F),
				near(reloaded.state.getPublishedRpm(), reloaded.state.getOutputRpm(),
					EngineTuning.NETWORK_RPM_FINE_DELTA),
				String.format("published %.0f vs an engine output of %.1f", reloaded.state.getPublishedRpm(),
					reloaded.state.getOutputRpm()));
		}
		System.out.println();
	}

	/**
	 * TEST 4 - a loaded engine. It legitimately sags below its target, and the
	 * reload must reproduce the loaded equilibrium rather than snapping the engine
	 * to the throttle's target.
	 */
	static void loadedEngineReload() {
		System.out.println("PART 3 - a loaded engine\n");
		Engine engine = new Engine(2000000, 24);
		engine.throttle = 0.0F;
		Network net = started(engine);
		engine.loadFactor = 1.0F;
		net.run(1200);
		// Averaged over several cycles. A loaded four-stroke single is the roughest
		// operating point the mod has - one bang per two revolutions against a full
		// load - so a single sample of the published speed lands anywhere in a couple
		// of quanta, and the equilibrium this test is about is the mean of that.
		float loadedRpm = meanNetworkSpeed(net, 400);

		check("loaded engine sags below its target", loadedRpm < EngineTuning.IDLE_RPM - 2.0F,
			String.format("%.1f RPM on the network against a target of %.0f", loadedRpm,
				engine.state.getTargetRpm()));

		Engine reloaded = new Engine(engine.tank.mb, 25);
		Network back = saveAndReload(net, reloaded);
		back.run(600);
		float reloadedRpm = meanNetworkSpeed(back, 400);
		check("reload restores the loaded equilibrium, not the target",
			near(reloadedRpm, loadedRpm, 2.0F) && reloadedRpm < EngineTuning.IDLE_RPM - 2.0F,
			String.format("%.1f RPM on the network, was %.1f before the save; target is still %.0f", reloadedRpm,
				loadedRpm, reloaded.state.getTargetRpm()));
		check("and the engine is genuinely sitting below its target",
			reloaded.state.getOutputRpm() < EngineTuning.IDLE_RPM - 2.0F
				&& near(reloaded.state.getPublishedRpm(), reloaded.state.getOutputRpm(),
					EngineTuning.NETWORK_RPM_QUANTUM + EngineTuning.NETWORK_RPM_FINE_DELTA),
			String.format("engine output %.1f, published %.0f", reloaded.state.getOutputRpm(),
				reloaded.state.getPublishedRpm()));
		System.out.println();
	}

	/** The mean speed Create is held at over a run: several four-stroke cycles of it. */
	static float meanNetworkSpeed(Network net, int ticks) {
		float total = 0.0F;
		for (int tick = 0; tick < ticks; tick++) {
			net.run(1);
			total += net.speed;
		}
		return total / ticks;
	}

	/** TEST 5 and TEST 8 - a stopped engine, and one with the ignition off. */
	static void stoppedEngineReload() {
		System.out.println("PART 4 - engines that must stay stopped\n");

		// Stopped, ignition ON, fuelled, valid structure.
		Engine stopped = new Engine(2000000, 26);
		Network net = new Network(stopped);
		net.run(100);
		check("a fuelled, lit, stopped engine does not start itself",
			stopped.state.getPhase() == EnginePhase.STOPPED, stopped.state.getPhase()
				.toString());

		Engine reloaded = new Engine(stopped.tank.mb, 27);
		Network back = saveAndReload(net, reloaded);
		back.run(400);
		check("and still does not after a reload",
			reloaded.state.getPhase() == EnginePhase.STOPPED && !reloaded.state.isActivelyGenerating()
				&& back.speed == 0.0F && reloaded.capacitySu() == 0.0,
			String.format("%s, %.0f RPM, %.0f su", reloaded.state.getPhase(), back.speed, reloaded.capacitySu()));

		// Running, then saved with the ignition switched off.
		Engine unlit = new Engine(2000000, 28);
		Network running = started(unlit);
		running.run(200);
		unlit.ignition = false;
		running.run(400);
		Engine unlitBack = new Engine(unlit.tank.mb, 29);
		Network unlitNet = saveAndReload(running, unlitBack);
		unlitNet.run(600);
		check("an engine saved with the ignition off stays off",
			!unlitBack.state.isActivelyGenerating() && unlitBack.capacitySu() == 0.0 && unlitNet.speed == 0.0F,
			String.format("%s, %.0f su, %.0f RPM", unlitBack.state.getPhase(), unlitBack.capacitySu(),
				unlitNet.speed));
		System.out.println();
	}

	/**
	 * TEST 6 - an externally driven engine. The real network speed stays the
	 * mechanical authority; the engine synchronises its momentum to it and does not
	 * force its own saved RPM onto it.
	 */
	static void externalDriveReload() {
		System.out.println("PART 5 - an externally driven engine\n");
		Engine engine = new Engine(0, 30);
		engine.ignition = false;
		Network net = new Network(engine);
		net.externalRpm = 160.0F;
		net.republish();
		net.run(200);
		check("the engine takes on the imposed speed", near(engine.state.getSimulatedRpm(), 160.0F, 2.0F),
			String.format("%.1f RPM against 160 imposed", engine.state.getSimulatedRpm()));

		Engine reloaded = new Engine(0, 31);
		Network back = saveAndReload(net, reloaded);
		back.run(200);
		check("the external source is still the mechanical authority",
			near(back.speed, 160.0F, 0.01F) && near(reloaded.state.getSimulatedRpm(), 160.0F, 3.0F),
			String.format("network %.0f RPM, engine %.1f RPM", back.speed, reloaded.state.getSimulatedRpm()));
		check("and the motored engine generates nothing",
			!reloaded.state.isActivelyGenerating() && reloaded.state.getPublishedRpm() == 0.0F
				&& reloaded.capacitySu() == 0.0,
			String.format("generating=%s, %.0f su", reloaded.state.isActivelyGenerating(), reloaded.capacitySu()));
		System.out.println();
	}

	/**
	 * TEST 7 - the free-power exploit, reached through a save this time. One
	 * fuelled engine and one empty one on a shared shaft: the reload must not
	 * resurrect the empty engine's capacity.
	 */
	static void multiEngineReload() {
		System.out.println("PART 6 - two engines, one tank of fuel\n");
		Engine fuelled = new Engine(2000000, 32);
		Network solo = started(fuelled);
		solo.run(400);
		double soloCapacity = fuelled.capacitySu();

		Engine empty = new Engine(0, 33);
		Network pair = new Network(fuelled, empty);
		pair.republish();
		pair.run(400);
		check("before the save, only the fuelled engine has capacity",
			empty.capacitySu() == 0.0 && near(pair.capacitySu(), soloCapacity, soloCapacity * 0.15),
			String.format("%.0f su total, %.0f su from the empty engine", pair.capacitySu(), empty.capacitySu()));

		Engine fuelledBack = new Engine(fuelled.tank.mb, 34);
		Engine emptyBack = new Engine(0, 35);
		Network back = saveAndReload(pair, fuelledBack, emptyBack);
		back.run(1);
		check("the empty engine has no capacity on the very first tick back",
			emptyBack.capacitySu() == 0.0 && emptyBack.state.getPublishedRpm() == 0.0F,
			String.format("%.0f su, generated %.0f RPM", emptyBack.capacitySu(), emptyBack.state.getPublishedRpm()));

		back.run(400);
		check("the fuelled engine carries the network again",
			fuelledBack.state.isActivelyGenerating() && back.speed > 1.0F,
			String.format("%s, %.0f RPM", fuelledBack.state.getPhase(), back.speed));
		check("the empty engine is turned but never generates",
			Math.abs(emptyBack.state.getMechanicalRpm()) > 1.0F && emptyBack.capacitySu() == 0.0,
			String.format("%.0f RPM, %.0f su", emptyBack.state.getMechanicalRpm(), emptyBack.capacitySu()));
		check("total capacity is still ONE engine after the reload",
			near(back.capacitySu(), soloCapacity, soloCapacity * 0.15),
			String.format("%.0f su against %.0f su for one engine", back.capacitySu(), soloCapacity));
		System.out.println();
	}

	/** TEST 9 - reloading the same engine over and over must not accumulate drift. */
	static void repeatedReloads() {
		System.out.println("PART 7 - repeated reloads\n");
		Engine engine = new Engine(2000000, 36);
		engine.throttle = 1.0F;
		Network net = started(engine);
		net.run(800);
		float target = engine.state.getTargetRpm();
		float first = net.speed;

		float worst = 0.0F;
		for (int reload = 1; reload <= 8; reload++) {
			Engine reloaded = new Engine(engine.tank.mb, 40 + reload);
			net = saveAndReload(net, reloaded);
			// Create comes back holding a value that drifted while the world was shut.
			net.speed = 184.0F;
			net.run(800);
			engine = reloaded;
			worst = Math.max(worst, Math.abs(net.speed - target));
		}
		check("eight reloads do not walk the engine away from its target", worst <= 4.0F,
			String.format("worst deviation %.1f RPM; %.0f RPM now, %.0f before the first save, target %.0f", worst,
				net.speed, first, target));
		System.out.println();
	}

	/**
	 * The other half of the bargain: convergence must not have been bought with a
	 * kinetic network update per combustion pulse.
	 */
	static void networkChurn() {
		System.out.println("PART 8 - network churn\n");
		Engine engine = new Engine(2000000, 50);
		engine.throttle = 0.0F;
		Network net = started(engine);
		net.run(400);

		int before = engine.networkUpdates;
		net.run(2400);
		int updates = engine.networkUpdates - before;
		// 2400 ticks is two minutes, and about 128 combustion events at idle.
		check("a settled engine hardly re-propagates its network", updates <= 12,
			updates + " generated-speed updates in 2400 ticks (~128 combustion events)");

		before = engine.networkUpdates;
		engine.throttle = 1.0F;
		net.run(200);
		check("but a throttle swung wide open is followed at once",
			near(net.speed, EngineTuning.FULL_THROTTLE_RPM, 6.0F) && engine.networkUpdates - before <= 40,
			String.format("%.0f RPM after 200 ticks, in %d updates", net.speed, engine.networkUpdates - before));
		System.out.println();
	}
}
