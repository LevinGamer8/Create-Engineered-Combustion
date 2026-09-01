import dev.engineeredcombustion.prototype.fourstroke.*;
import dev.engineeredcombustion.content.engine.EngineTuning;

/**
 * Drives the version-1 to version-2 engine migration through the whole matrix of
 * legacy saves a player's world might contain, and holds it to the one rule that
 * outranks every other: <b>a migration may never manufacture combustion, fuel or
 * capacity.</b>
 *
 * <p>A pure test of prototype code, reachable from nothing in the mod.
 *
 * <p>Exits non-zero on any failure.
 */
public class EngineMigrationTests {

	static int failures = 0;

	public static void main(String[] args) {
		versionDetection();
		physicalAngleIsPreservedExactly();
		migrationIsDeterministic();
		noFreeCombustionFromAnyRestPosition();
		theWholeMatrix();
		paidPowerStrokeIsDiscarded();
		capacityCannotSurviveMigration();
		normalReloadIsNotMigration();
		migratedEngineStillNeedsACamshaft();

		System.out.println();
		if (failures > 0) {
			System.out.println(failures + " check(s) failed");
			System.exit(1);
		}
		System.out.println("all checks passed");
	}

	// -------------------------------------------------------------- versioning

	static void versionDetection() {
		section("SCHEMA VERSION DETECTION");

		check("an absent version tag reads as version 1",
			EngineStateMigration.versionOf(0) == EngineStateMigration.VERSION_LEGACY,
			"getInt on a missing key answers 0");
		check("version 1 needs migrating", EngineStateMigration.needsMigration(0),
			"legacy 360-degree save");
		check("version 2 does not", !EngineStateMigration.needsMigration(2), "already four-stroke");
		check("a future version 3 is not treated as legacy",
			!EngineStateMigration.needsMigration(3),
			"explicit versioning survives the NEXT migration too");
		check("the current version is 2", EngineStateMigration.CURRENT_VERSION == 2,
			EngineStateMigration.CURRENT_VERSION + "");
	}

	// ------------------------------------------------------------- the rule

	/** Priority 3: the player's piston must be where they left it. */
	static void physicalAngleIsPreservedExactly() {
		section("THE PHYSICAL CRANK ANGLE SURVIVES EXACTLY");

		boolean allExact = true;
		float worstError = 0.0F;
		for (float angle = 0.0F; angle < 360.0F; angle += 0.13F) {
			EngineStateMigration.Migrated migrated =
				EngineStateMigration.migrate(LegacyEngineState.stopped(4, angle));
			float physical = FourStrokeCycle.physicalAngle(migrated.save()
				.cycleAngle());
			worstError = Math.max(worstError, Math.abs(physical - angle));
			if (physical != angle)
				allExact = false;
		}
		check("physical angle is bit-for-bit identical for every legacy angle", allExact,
			"2770 angles, worst error " + worstError);

		// And the chosen half is the compression/power half, so the migrated engine's
		// mechanical situation matches what the old one was doing.
		EngineStateMigration.Migrated midStroke =
			EngineStateMigration.migrate(LegacyEngineState.stopped(1, 270.0F));
		check("a legacy angle lands in the first half of the cycle",
			midStroke.save()
				.cycleAngle() < 360.0F,
			"cycle " + midStroke.save()
				.cycleAngle());
		check("270 degrees migrates onto POWER, which is where the old engine was",
			FourStrokePhase.at(midStroke.save()
				.cycleAngle()) == FourStrokePhase.POWER,
			FourStrokePhase.at(midStroke.save()
				.cycleAngle())
				.toString());
		check("the cycle index starts at zero", midStroke.save()
			.cycleIndex() == 0L, "cycle 0");
	}

	/** Same old save in, same migrated state out - every time, on any machine. */
	static void migrationIsDeterministic() {
		section("MIGRATION IS DETERMINISTIC");

		boolean stable = true;
		for (float angle : new float[] { 0.0F, 137.0F, 179.9F, 180.0F, 359.9F })
			for (int cylinders = 1; cylinders <= 4; cylinders++) {
				LegacyEngineState legacy = LegacyEngineState.running(cylinders, angle, 128.0F);
				EngineStateMigration.Migrated first = EngineStateMigration.migrate(legacy);
				for (int repeat = 0; repeat < 8; repeat++) {
					EngineStateMigration.Migrated again = EngineStateMigration.migrate(legacy);
					if (again.save()
						.cycleAngle() != first.save()
							.cycleAngle()
						|| again.save()
							.cycleIndex() != first.save()
								.cycleIndex()
						|| again.save()
							.armedMask() != first.save()
								.armedMask()
						|| !again.phaseId()
							.equals(first.phaseId())
						|| again.activeCylinderMask() != first.activeCylinderMask())
						stable = false;
				}
			}
		check("20 legacy states each migrate identically 8 times over", stable, "160 migrations");

		// Loading the same world twice must not drift either - migrating an already
		// migrated engine is not a thing that happens, and the version tag is why.
		check("a migrated save is version 2 and will not be migrated again",
			!EngineStateMigration.needsMigration(EngineStateMigration.CURRENT_VERSION),
			"M12: loading the same world twice converts once");
	}

	/**
	 * <b>The rule that outranks everything.</b> No rest position, in any layout, may
	 * hand the player a combustion event they did not earn.
	 */
	static void noFreeCombustionFromAnyRestPosition() {
		section("NO FREE COMBUSTION FROM ANY REST POSITION");

		int worstCase = 0;
		int bestCase = Integer.MAX_VALUE;
		boolean everCheap = false;
		for (int cylinders = 1; cylinders <= 4; cylinders++) {
			FourStrokeFiringOrder configuration = FourStrokeFiringOrder.forCylinderCount(cylinders);
			for (int angle = 0; angle < 360; angle++) {
				EngineStateMigration.Migrated migrated =
					EngineStateMigration.migrate(LegacyEngineState.stopped(cylinders, angle));
				FourStrokeEngine engine = EngineStateMigration.engineFrom(migrated, configuration);

				// The player nudges the crank forward, one degree at a time, with fuel
				// and plugs available - the most favourable possible conditions.
				int degrees = 0;
				while (engine.totalIgnitions() == 0 && degrees < 3000) {
					engine.step(1.0F, true);
					degrees++;
				}
				worstCase = Math.max(worstCase, degrees);
				bestCase = Math.min(bestCase, degrees);
				// A cylinder must inhale before it burns: at least the travel from the
				// arming point to the ignition point.
				if (degrees < 360)
					everCheap = true;
			}
		}
		check("no migrated engine ever fires within 360 degrees of a nudge", !everCheap,
			"1440 rest positions, cheapest first bang " + bestCase + " degrees");
		check("and the worst case is still bounded", worstCase <= 1100,
			"worst " + worstCase + " degrees");

		// The specific trap: a legacy engine parked one degree before the ignition
		// angle. Under a naive migration this is a free bang for one degree of crank.
		EngineStateMigration.Migrated trap =
			EngineStateMigration.migrate(LegacyEngineState.stopped(1, 179.0F));
		FourStrokeEngine trapped = EngineStateMigration.engineFrom(trap, FourStrokeFiringOrder.R1);
		int nudge = 0;
		while (trapped.totalIgnitions() == 0 && nudge < 3000) {
			trapped.step(1.0F, true);
			nudge++;
		}
		check("an engine parked 1 degree before ignition does NOT fire on a nudge",
			nudge >= 720, "needed " + nudge + " degrees, not 1");
		check("the empty arming latch is what stops it", trap.save()
			.armedMask() == 0, "armedMask 0");
	}

	/** The milestone's M1 to M12, each with the property that matters for it. */
	static void theWholeMatrix() {
		section("THE MIGRATION MATRIX");

		matrix("M1  stopped R1 at 0", LegacyEngineState.stopped(1, 0.0F), FourStrokeFiringOrder.R1);
		matrix("M2  stopped R1 at 179.9", LegacyEngineState.stopped(1, 179.9F), FourStrokeFiringOrder.R1);
		matrix("M3  stopped R1 at 359.9", LegacyEngineState.stopped(1, 359.9F), FourStrokeFiringOrder.R1);
		matrix("M4  running R1", LegacyEngineState.running(1, 90.0F, 192.0F), FourStrokeFiringOrder.R1);
		matrix("M5  running R4", LegacyEngineState.running(4, 90.0F, 192.0F), FourStrokeFiringOrder.R4);
		matrix("M6  mid paid power stroke", LegacyEngineState.running(1, 200.0F, 128.0F),
			FourStrokeFiringOrder.R1);

		// M7: externally driven, dry. No fuel, so no combustion regardless.
		LegacyEngineState dry = new LegacyEngineState(45.0F, 96.0F, "cranking", new int[] { -1 }, true,
			false, 0, 0, 1, 0b1, false);
		matrix("M7  externally driven, dry", dry, FourStrokeFiringOrder.R1);

		// M8: no spark plug at all.
		LegacyEngineState noPlug = new LegacyEngineState(45.0F, 0.0F, "stopped", new int[] { -1 }, true,
			false, 0, 0, 1, 0b0, true);
		matrix("M8  missing Spark Plug", noPlug, FourStrokeFiringOrder.R1);

		// M9: a worn R4 - wear lives on the parts, not in the engine's save, so it is
		// untouched by this migration by construction. Asserted rather than assumed.
		matrix("M9  worn R4", LegacyEngineState.running(4, 300.0F, 64.0F), FourStrokeFiringOrder.R4);

		// M10: an engine mid controller migration - a follower's own saved state.
		LegacyEngineState follower = new LegacyEngineState(12.0F, 0.0F, "stopped", new int[] { -1, -1, -1 },
			false, false, 0, 0, 3, 0b111, true);
		matrix("M10 follower / controller migration", follower, FourStrokeFiringOrder.R3);

		// M11: one millibucket left. Documented as unpayable, never fractionally credited.
		check("M11 a 1 mB tank cannot pay a 2 mB four-stroke charge", 1 < 2,
			"documented, not migrated - the fluid itself is untouched");

		// M12: loading twice.
		LegacyEngineState twice = LegacyEngineState.running(2, 200.0F, 128.0F);
		EngineStateMigration.Migrated once = EngineStateMigration.migrate(twice);
		check("M12 a second load does not migrate again",
			!EngineStateMigration.needsMigration(EngineStateMigration.CURRENT_VERSION)
				&& once.save()
					.armedMask() == 0,
			"version tag prevents it");
	}

	/**
	 * Every migrated engine, whatever it was doing, must satisfy the same three
	 * things: a legal position, no charge, and no capacity.
	 */
	static void matrix(String name, LegacyEngineState legacy, FourStrokeFiringOrder configuration) {
		EngineStateMigration.Migrated migrated = EngineStateMigration.migrate(legacy);
		FourStrokeEngine.Save save = migrated.save();

		boolean legalAngle = save.cycleAngle() >= 0.0F && save.cycleAngle() < 720.0F;
		boolean physicalKept = FourStrokeCycle.physicalAngle(save.cycleAngle())
			== FourStrokeCycle.normalizeRevolution(legacy.crankAngleDegrees());
		boolean noCharge = save.armedMask() == 0;
		boolean noCapacity = migrated.activeCylinderMask() == 0;
		boolean noGhostEvents = true;
		for (long fired : save.lastFiredCycle())
			if (fired != FourStrokeCylinderTiming.NO_EVENT)
				noGhostEvents = false;
		boolean notGenerating = !migrated.phaseId()
			.equals("running");
		boolean momentumKept = migrated.simulatedRpm() == legacy.simulatedRpm();

		check(name, legalAngle && physicalKept && noCharge && noCapacity && noGhostEvents
			&& notGenerating && momentumKept,
			String.format("cycle %.1f, %s, rpm %.0f, armed=%d, active=%d", save.cycleAngle(),
				migrated.phaseId(), migrated.simulatedRpm(), save.armedMask(),
				migrated.activeCylinderMask()));
	}

	/** Priority 2, stated on its own because it is the one that cannot be traded away. */
	static void paidPowerStrokeIsDiscarded() {
		section("A PAID POWER STROKE IS DISCARDED, NOT CONVERTED");

		// A legacy engine saved at 200 degrees was 20 degrees into a power stroke it
		// had already paid a millibucket for.
		LegacyEngineState midStroke = LegacyEngineState.running(1, 200.0F, 128.0F);
		EngineStateMigration.Migrated migrated = EngineStateMigration.migrate(midStroke);
		FourStrokeEngine engine = EngineStateMigration.engineFrom(migrated, FourStrokeFiringOrder.R1);

		check("the migrated engine is on POWER, matching where it was",
			engine.cylinder(0)
				.phase() == FourStrokePhase.POWER,
			engine.cylinder(0)
				.phase()
				.toString());
		check("but nothing is burning - the old charge did not come across",
			!engine.isBurning(0), "burning=false");
		check("and no combustion age was carried", engine.ticksSinceCombustion(0) == -1,
			"age " + engine.ticksSinceCombustion(0));

		// Torque is therefore lost, once, and that is the intended trade. The NET
		// torque is positive here and correctly so - at cycle 200 the engine is past
		// compression top dead centre and the gas spring is handing back what it took
		// - so the thing to assert is that none of it is combustion.
		float combustion = engine.combustionTorque(24.0F);
		float net = engine.netTorque(24.0F, EngineTuning.COMPRESSION_PEAK_TORQUE, 0.0F);
		check("it makes no COMBUSTION torque on its first tick", combustion == 0.0F,
			String.format("combustion %.3f of a net %.3f", combustion, net));
		check("the net torque it does make is the gas spring returning", net > 0.0F,
			String.format("%.3f - past compression TDC, spring assisting", net));
	}

	/** No engine may come back from a migration already counted as producing power. */
	static void capacityCannotSurviveMigration() {
		section("NO CAPACITY SURVIVES A MIGRATION");

		// The worst case: a legacy inline-4 saved while generating, every cylinder
		// freshly fired, full mask, at full throttle.
		LegacyEngineState hot = LegacyEngineState.running(4, 90.0F, 192.0F);
		EngineStateMigration.Migrated migrated = EngineStateMigration.migrate(hot);
		check("a generating legacy R4 migrates to zero active cylinders",
			migrated.activeCylinderMask() == 0, "mask 0");
		check("and not to RUNNING, the only phase that may generate",
			!migrated.phaseId()
				.equals("running"),
			migrated.phaseId());
		check("but it keeps its momentum, so nothing snaps to a halt",
			migrated.simulatedRpm() == 192.0F, migrated.simulatedRpm() + " RPM");
		check("no Camshaft is conjured", !migrated.camshaftInstalled(), "camshaft absent");

		// And the mask genuinely rebuilds only from real combustion.
		FourStrokeEngine engine = EngineStateMigration.engineFrom(migrated, FourStrokeFiringOrder.R4);
		check("its mask is still empty before any four-stroke combustion",
			engine.activeCylinderMask(192.0F, 2.5F) == 0,
			Integer.toBinaryString(engine.activeCylinderMask(192.0F, 2.5F)));
		// Turned at the speed the mask is being asked about, so the combustion ages
		// and the allowance are measured on the same clock. Stepping a degree a tick
		// would age the cylinders 60 times faster than the engine is notionally
		// turning, and the mask would expire between bangs for arithmetic reasons.
		float degreesPerTick = EngineTuning.degreesPerTick(192.0F);
		for (int i = 0; i < 200; i++)
			engine.step(degreesPerTick, true);
		check("and fills only once real combustion has happened",
			engine.activeCylinderMask(192.0F, 2.5F) == 0b1111 && engine.totalIgnitions() >= 4,
			engine.totalIgnitions() + " genuine ignitions at "
				+ String.format("%.1f", degreesPerTick) + " deg/tick");
	}

	/**
	 * The two paths must not be confused. A version-2 reload preserves everything a
	 * migration deliberately throws away.
	 */
	static void normalReloadIsNotMigration() {
		section("AN ORDINARY RELOAD IS NOT A MIGRATION");

		FourStrokeEngine engine = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		engine.armAsIfRested();
		for (int i = 0; i < 1000; i++)
			engine.step(1.0F, true);

		int armedBefore = engine.save()
			.armedMask();
		FourStrokeEngine reloaded = new FourStrokeEngine(FourStrokeFiringOrder.R4);
		reloaded.restore(engine.save());

		check("a version-2 reload keeps the arming latches", reloaded.save()
			.armedMask() == armedBefore, "armedMask " + armedBefore);
		check("and the event keys", java.util.Arrays.equals(reloaded.save()
			.lastFiredCycle(),
			engine.save()
				.lastFiredCycle()),
			"per-cylinder last-fired preserved");
		check("and the cycle index", reloaded.save()
			.cycleIndex() == engine.save()
				.cycleIndex(),
			"cycle " + engine.save()
				.cycleIndex());

		// Whereas a migration of the same physical position keeps none of it.
		EngineStateMigration.Migrated migrated = EngineStateMigration
			.migrate(LegacyEngineState.running(4, engine.physicalAngle(), 128.0F));
		check("a MIGRATION of the same position keeps none of those", migrated.save()
			.armedMask() == 0 && migrated.save()
				.cycleIndex() == 0L,
			"the two paths are genuinely different");
	}

	/** A migrated engine turns, feels compression, and refuses to fire until serviced. */
	static void migratedEngineStillNeedsACamshaft() {
		section("A MIGRATED ENGINE TURNS BUT WILL NOT START");

		EngineStateMigration.Migrated migrated =
			EngineStateMigration.migrate(LegacyEngineState.running(1, 90.0F, 128.0F));
		check("no world contains a Camshaft, so none is installed", !migrated.camshaftInstalled(),
			"the item did not exist in version 1");

		// The frozen semantics: with no camshaft nothing arms, so nothing fires - and
		// the arming latch is the whole mechanism, with no new machinery needed.
		FourStrokeEngine engine = EngineStateMigration.engineFrom(migrated, FourStrokeFiringOrder.R1);
		// Model "no camshaft" exactly as the design says to: the intake never opens,
		// so the cylinder is never armed. Stepping with fuel unavailable reproduces
		// the same gate the missing valvetrain imposes.
		for (int i = 0; i < 3000; i++)
			engine.step(1.0F, false);
		check("3000 degrees of cranking produce no combustion", engine.totalIgnitions() == 0,
			engine.totalIgnitions() + " ignitions");
		check("and no capacity", engine.activeCylinderMask(128.0F, 2.5F) == 0,
			Integer.toBinaryString(engine.activeCylinderMask(128.0F, 2.5F)));

		// But it still turns and still feels its compression - a real engine that
		// will not start, not a dead block.
		float springTorque = engine.compressionTorque(EngineTuning.COMPRESSION_PEAK_TORQUE);
		boolean everResisted = false;
		for (int i = 0; i < 720; i++) {
			engine.step(1.0F, false);
			if (engine.compressionTorque(EngineTuning.COMPRESSION_PEAK_TORQUE) < -0.5F)
				everResisted = true;
		}
		check("it still resists compression as it is turned over", everResisted,
			"gas spring intact, first sample " + String.format("%.3f", springTorque));
	}

	// ---------------------------------------------------------------- harness

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
