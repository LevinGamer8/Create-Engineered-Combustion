package dev.engineeredcombustion.prototype.fourstroke;

/**
 * The one-time conversion of a version-1 (360-degree) engine into a version-2
 * (four-stroke) one.
 *
 * <p><b>Prototype. Nothing in the mod depends on this.</b>
 *
 * <h2>The information that is not there</h2>
 * A version-1 save contains one crank angle in {@code [0, 360)}. A four-stroke
 * engine needs a position in {@code [0, 720)}, and <b>one physical crank angle
 * corresponds to two cycle positions on two different strokes</b>. A crank at 137
 * degrees was either at cycle 137 (compressing) or cycle 497 (exhausting), and
 * nothing in the old save distinguishes them, because the old engine had no such
 * distinction to record.
 *
 * <p>So this does not attempt a reconstruction. It makes a <b>deterministic
 * choice</b>, documents it, and makes that choice safe by refusing to carry any
 * state that could turn into free power.
 *
 * <h2>The rule</h2>
 * <pre>
 * cycleIndex   = 0
 * cycleAngle   = legacy crank angle          (the FIRST half: COMPRESSION / POWER)
 * armedMask    = 0                           (no charge, in any cylinder, ever)
 * lastFired    = NO_EVENT                    (no opportunity has been taken)
 * phase        = STOPPED, or COASTING if it was turning under its own power
 * activeMask   = 0                           (rebuilt from real four-stroke combustion)
 * </pre>
 *
 * <p><b>Why the first half.</b> The physical angle is preserved exactly and for
 * free, because {@code cycleAngle % 360} is the identity on {@code [0, 360)}: a
 * migrated piston is where the player left it, to the last float bit. The old
 * engine's own firing angle was 180 and its power stroke {@code [180, 360)}, which
 * under the frozen convention <i>is</i> the first half - so the half chosen is also
 * the one whose mechanical meaning matches what the old engine was doing.
 *
 * <p><b>Why an empty arming latch is what makes it safe.</b> The choice of half
 * would otherwise matter a great deal: an engine migrated to cycle 179 sits one
 * degree before the new ignition angle, and a player nudging the crank would be
 * handed a combustion event that was never paid for. With {@code armedMask = 0} no
 * cylinder can fire until it has forward-crossed the intake opening at 540 and then
 * reached 180 again - a full 720 degrees of honest crank travel, exactly as for any
 * other cylinder. <b>The safety therefore does not depend on which half was
 * chosen</b>, which is what makes the choice free to be made on other grounds.
 */
public final class EngineStateMigration {

	/** The simplified engine that fires once per 360 degrees. */
	public static final int VERSION_LEGACY = 1;

	/** The four-stroke engine that fires once per 720. */
	public static final int VERSION_FOUR_STROKE = 2;

	/** What a freshly written save carries. */
	public static final int CURRENT_VERSION = VERSION_FOUR_STROKE;

	private EngineStateMigration() {
	}

	/**
	 * The version of a save, from its own version tag.
	 *
	 * <p>Explicit rather than inferred. "The cycle key is missing, so it must be
	 * old" works exactly once - for the migration after this one it is ambiguous
	 * between a version-1 save and a version-3 save that dropped the field, and by
	 * then the code that could tell them apart is gone. A stored integer costs one
	 * tag and never becomes ambiguous.
	 *
	 * <p>An absent tag reads as 0 from {@code getInt}, which is the signature of a
	 * save written before versioning existed - that is, version 1.
	 */
	public static int versionOf(int storedVersion) {
		return storedVersion <= 0 ? VERSION_LEGACY : storedVersion;
	}

	/** Whether a save needs the one-time conversion below. */
	public static boolean needsMigration(int storedVersion) {
		return versionOf(storedVersion) < VERSION_FOUR_STROKE;
	}

	/**
	 * What a migrated engine comes back as.
	 *
	 * @param save             the four-stroke position and per-cylinder latches, in
	 *                         exactly the shape version 2 persists
	 * @param simulatedRpm     carried across untouched - it is the authoritative
	 *                         rotational state in both schemas and means the same
	 *                         thing in each
	 * @param phaseId          the migrated {@code EnginePhase} id
	 * @param activeCylinderMask always 0 - see {@link #migrate}
	 * @param camshaftInstalled always false - the item did not exist
	 */
	public record Migrated(FourStrokeEngine.Save save, float simulatedRpm, String phaseId,
		int activeCylinderMask, boolean camshaftInstalled) {
	}

	/**
	 * Converts one version-1 engine. Deterministic: the same legacy state always
	 * produces the same result, with no randomness and no dependence on when it runs.
	 *
	 * <p>Everything this <i>refuses</i> to carry is refused for one reason, and it is
	 * the second migration priority: <b>no duplicated fuel or power</b>.
	 * <ul>
	 * <li><b>The paid power stroke is discarded.</b> Version 1's
	 * {@code powerStrokeStrength} and {@code firedThisRevolution} are transient
	 * fields describing a charge bought under the old fuel accounting - one
	 * millibucket for a 360-degree event. Mapping that onto a two-millibucket
	 * 720-degree event means inventing a conversion for energy that was already
	 * spent. Losing at most one impulse - under a fifth of a second of torque, once,
	 * during a version change - is strictly better than any rule that might create
	 * some.</li>
	 * <li><b>The active-cylinder mask is reset to 0.</b> It is a reading of combustion
	 * ages measured against a firing interval that has just doubled, so every entry
	 * in it is now the wrong unit. Rebuilding it from genuine four-stroke combustion
	 * costs the player a fraction of a second of HUD continuity and cannot hand
	 * Create capacity for an engine that has not yet burned anything.</li>
	 * <li><b>The start attempt is discarded.</b> Its progress counts firing events
	 * that no longer exist at that rate.</li>
	 * </ul>
	 */
	public static Migrated migrate(LegacyEngineState legacy) {
		// The identity on [0, 360), so the piston does not move by so much as a float
		// bit. Normalised anyway, because a legacy save is input and input is checked.
		float cycleAngle = FourStrokeCycle.normalizeCycle(
			FourStrokeCycle.normalizeRevolution(legacy.crankAngleDegrees()));

		int cylinders = Math.max(1, legacy.cylinderCount());
		long[] lastFired = new long[cylinders];
		java.util.Arrays.fill(lastFired, FourStrokeCylinderTiming.NO_EVENT);

		return new Migrated(new FourStrokeEngine.Save(0L, cycleAngle, 0, lastFired),
			legacy.simulatedRpm(), migratePhase(legacy.phaseId()), 0, false);
	}

	/**
	 * What each version-1 run state becomes.
	 *
	 * <pre>
	 * stopped  -> stopped
	 * cranking -> stopped     the attempt was transient and is gone
	 * starting -> stopped     the same
	 * running  -> coasting    it kept its momentum but it is not burning any more
	 * coasting -> coasting
	 * </pre>
	 *
	 * <p><b>Why a running engine becomes a coasting one rather than staying
	 * running.</b> Version 2 requires a Camshaft, and no version-1 world contains
	 * one, so a migrated engine cannot legitimately be combusting: leaving it
	 * RUNNING would be a claim that it is producing power it has no valvetrain to
	 * produce, and RUNNING is the only phase that may generate.
	 *
	 * <p>COASTING is not a compromise here - it is the exactly correct word.
	 * {@code EnginePhase} already defines it as an engine that has stopped burning
	 * but is still turning on stored momentum, which is precisely what a migrated
	 * engine is. It keeps the flywheel's speed, so nothing snaps to a halt; it
	 * generates nothing, so there is no ghost capacity; and it reaches STOPPED by
	 * itself through the ordinary spin-down the player can watch.
	 */
	public static String migratePhase(String legacyPhaseId) {
		return switch (legacyPhaseId) {
			case "running", "coasting" -> "coasting";
			default -> "stopped";
		};
	}

	/**
	 * Builds a prototype engine from a migrated save, for driving in tests.
	 *
	 * <p>Not a production path - production restores into the real
	 * {@code EngineState} - but it is the same {@code Save} record, so what the tests
	 * drive is the object the migration actually produces.
	 */
	public static FourStrokeEngine engineFrom(Migrated migrated, FourStrokeFiringOrder configuration) {
		FourStrokeEngine engine = new FourStrokeEngine(configuration);
		engine.restore(migrated.save());
		return engine;
	}
}
