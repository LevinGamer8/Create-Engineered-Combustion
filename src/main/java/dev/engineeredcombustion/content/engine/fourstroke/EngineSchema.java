package dev.engineeredcombustion.content.engine.fourstroke;

/**
 * The version of an engine's saved state, and the rule for migrating an old one.
 *
 * <h2>Why an explicit integer</h2>
 * "The cycle key is missing, so the save must be old" works exactly once. For the
 * migration <i>after</i> this one it is ambiguous between a version-1 save and a
 * version-3 save that dropped the field, and by then the code that could tell them
 * apart is gone. A stored integer costs one tag and never becomes ambiguous.
 *
 * <p>An absent tag reads as 0 from {@code CompoundTag#getInt}, which is the signature
 * of a save written before versioning existed - that is, version 1.
 *
 * <h2>The version-1 to version-2 rule</h2>
 * A version-1 save contains one crank angle in {@code [0, 360)}. A four-stroke engine
 * needs a position in {@code [0, 720)}, and <b>one physical crank angle corresponds to
 * two cycle positions on two different strokes</b>. A crank at 137 degrees was either
 * at cycle 137 (compressing) or cycle 497 (exhausting), and nothing in the old save
 * distinguishes them, because the old engine had no such distinction to record.
 *
 * <p>So no reconstruction is attempted. A <b>deterministic choice</b> is made, and made
 * safe by refusing to carry any state that could turn into free power:
 *
 * <pre>
 * cycleIndex   = 0
 * cycleAngle   = legacy crank angle          (the FIRST half: COMPRESSION / POWER)
 * armedMask    = 0                           (no charge, in any cylinder, ever)
 * lastFired    = NO_EVENT                    (no opportunity has been taken)
 * phase        = STOPPED, or COASTING if it was turning under its own power
 * activeMask   = 0                           (rebuilt from real four-stroke combustion)
 * camshaft     = absent                      (the item did not exist)
 * </pre>
 *
 * <p><b>Why the first half.</b> The physical angle is preserved exactly and for free,
 * because {@code cycleAngle % 360} is the identity on {@code [0, 360)}: a migrated
 * piston is where the player left it, to the last float bit. The old engine's own
 * firing angle was 180 and its power stroke {@code [180, 360)}, which under the frozen
 * convention <i>is</i> the first half - so the half chosen is also the one whose
 * mechanical meaning matches what the old engine was doing.
 *
 * <p><b>Why an empty arming latch is what makes it safe.</b> The choice of half would
 * otherwise matter a great deal: an engine migrated to cycle 179 sits one degree before
 * the new ignition angle, and a player nudging the crank would be handed a combustion
 * event that was never paid for. With {@code armedMask = 0} no cylinder can fire until
 * it has forward-crossed the intake opening at 540 and then reached 180 again - a full
 * 720 degrees of honest crank travel. <b>The safety therefore does not depend on which
 * half was chosen.</b>
 *
 * <p>Everything the migration <i>refuses</i> to carry is refused for one reason: no
 * duplicated fuel or power. The paid power stroke is discarded, because mapping a
 * charge bought at 1 mB per 360 degrees onto a 2 mB, 720-degree event means inventing a
 * conversion for energy that was already spent. The active-cylinder mask is reset,
 * because it is a reading of combustion ages measured against a firing interval that
 * has just doubled. The start attempt is discarded, because its progress counts firing
 * events that no longer exist at that rate. Losing at most one impulse - under a fifth
 * of a second of torque, once, during a version change - is strictly better than any
 * rule that might create some.
 */
public final class EngineSchema {

	/** The simplified engine that fired once per 360 degrees. */
	public static final int VERSION_LEGACY = 1;

	/** The four-stroke engine that fires once per 720. */
	public static final int VERSION_FOUR_STROKE = 2;

	/** What a freshly written save carries. */
	public static final int CURRENT_VERSION = VERSION_FOUR_STROKE;

	private EngineSchema() {
	}

	/** The version a stored tag names, treating an absent tag as version 1. */
	public static int versionOf(int storedVersion) {
		return storedVersion <= 0 ? VERSION_LEGACY : storedVersion;
	}

	/** Whether a save needs the one-time conversion described above. */
	public static boolean needsMigration(int storedVersion) {
		return versionOf(storedVersion) < VERSION_FOUR_STROKE;
	}

	/**
	 * The cycle angle a legacy crank angle becomes: the identity on {@code [0, 360)},
	 * so the piston does not move by so much as a float bit.
	 *
	 * <p>Normalised anyway, because a legacy save is input and input is checked.
	 */
	public static float migratedCycleAngle(float legacyCrankAngleDegrees) {
		return FourStrokeCycle.normalizeCycle(FourStrokeCycle.normalizeRevolution(legacyCrankAngleDegrees));
	}
}
