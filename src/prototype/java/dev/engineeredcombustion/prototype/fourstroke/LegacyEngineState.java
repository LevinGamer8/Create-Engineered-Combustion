package dev.engineeredcombustion.prototype.fourstroke;

/**
 * Exactly what a version-1 (360-degree) engine wrote to disk, and nothing else.
 *
 * <p><b>Prototype. Nothing in the mod depends on this.</b> It exists so the
 * migration can be <i>run</i> on realistic legacy input rather than reasoned about,
 * and so the version-1 schema is written down in one place before it stops
 * existing.
 *
 * <p>The field names and types mirror the production NBT keys one for one, so a
 * reader can check this against {@code CrankshaftBlockEntity}'s {@code read} and
 * see nothing missing. What it deliberately does <b>not</b> model is anything
 * version 1 did not persist: the published RPM, the capacity factor and the active
 * mask are client-packet-only or re-derived on load, and a migration that consumed
 * them would be reading fields that are not there.
 *
 * @param crankAngleDegrees   {@code CrankAngle} - the whole of the phase information
 *                            a version-1 save contains, in {@code [0, 360)}
 * @param simulatedRpm        {@code SimulatedRpm} - signed, and the authoritative
 *                            rotational state in both schemas
 * @param phaseId             {@code Phase} - the {@code EnginePhase} id string
 * @param ticksSinceCombustion {@code TicksSinceCombustion} - per cylinder, -1 for never
 * @param ignitionEnabled     {@code Ignition}
 * @param generating          {@code Generating} - the engine's own saved answer to
 *                            "was I producing power"
 * @param startProgress       {@code StartProgress}
 * @param startRequired       {@code StartRequired}
 * @param cylinderCount       {@code CylinderCount}
 * @param sparkPlugMask       {@code SparkPlugMask}
 * @param fuelAvailable       {@code FuelAvailable}
 */
public record LegacyEngineState(float crankAngleDegrees, float simulatedRpm, String phaseId,
	int[] ticksSinceCombustion, boolean ignitionEnabled, boolean generating, int startProgress,
	int startRequired, int cylinderCount, int sparkPlugMask, boolean fuelAvailable) {

	/** A stopped, pristine engine of the given size, parked at a given crank angle. */
	public static LegacyEngineState stopped(int cylinderCount, float crankAngleDegrees) {
		int[] ages = new int[cylinderCount];
		java.util.Arrays.fill(ages, -1);
		return new LegacyEngineState(crankAngleDegrees, 0.0F, "stopped", ages, true, false, 0, 0,
			cylinderCount, (1 << cylinderCount) - 1, true);
	}

	/** An engine saved mid-run, generating, with every cylinder recently fired. */
	public static LegacyEngineState running(int cylinderCount, float crankAngleDegrees, float rpm) {
		int[] ages = new int[cylinderCount];
		java.util.Arrays.fill(ages, 3);
		return new LegacyEngineState(crankAngleDegrees, rpm, "running", ages, true, true, 0, 0,
			cylinderCount, (1 << cylinderCount) - 1, true);
	}
}
