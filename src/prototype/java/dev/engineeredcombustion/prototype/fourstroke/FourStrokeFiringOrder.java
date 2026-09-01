package dev.engineeredcombustion.prototype.fourstroke;

/**
 * The crank and firing schedule of each inline engine the mod builds.
 *
 * <p><b>Prototype. Nothing in the mod depends on this.</b>
 *
 * <h2>The correction this class exists to make</h2>
 * The production engine has one number per cylinder,
 * {@code EngineTuning#cylinderPhaseOffsetDegrees}, and uses it for two different
 * jobs: where the crank throw is, and when the cylinder fires. On a two-stroke
 * those are the same question. On a four-stroke they are not - cylinders 1 and 4 of
 * an inline-4 sit on the <i>same</i> throw and their pistons move together, yet they
 * fire a full revolution apart - so this class carries both, separately:
 *
 * <dl>
 * <dt>{@link #ignitionOffsetDegrees}</dt>
 * <dd>Crank travel from cylinder 1's ignition to this cylinder's, in
 * {@code [0, 720)}. <b>The authoritative schedule</b>, and the human-readable one:
 * the firing order is just this array sorted.</dd>
 * <dt>{@link #geometricOffsetDegrees}</dt>
 * <dd>Where this cylinder's crank throw sits relative to cylinder 1's, in
 * {@code [0, 360)}. <b>Derived</b>, never authored, and never stored beside the
 * ignition offsets - see {@link #cyclePhaseOffsetDegrees}.</dd>
 * </dl>
 *
 * <h2>Numbering</h2>
 * Cylinder 1 is index 0 is the <b>negative-most crankshaft section</b>, which is the
 * controller: exactly the numbering {@code EngineComponents} already assigns, and
 * the only one available, because the Flywheel may legally sit at <i>either</i> end
 * of the run and so cannot define an end. Migration cost: zero.
 *
 * <h2>Sign convention</h2>
 * The production engine computes a cylinder's angle as
 * {@code master + phaseOffset(i)}, and this class keeps that idiom - so the offset
 * that is <i>added</i> is {@link #cyclePhaseOffsetDegrees}, which is the negation of
 * the ignition offset. The distinction is not cosmetic: with evenly spaced offsets
 * on an even cylinder count the sign is invisible, but on an inline-3 it reverses
 * the firing order, and the production engine's inline-3 fires 1-3-2 today purely
 * because of it. See {@code docs/milestone-15-four-stroke-design.md}.
 */
public enum FourStrokeFiringOrder {

	/**
	 * Inline-1. One bang every two revolutions, and nothing at all for the other
	 * three strokes: the configuration that makes the Flywheel matter.
	 */
	R1(new float[] { 0.0F }),

	/**
	 * Inline-2 on a <b>360-degree crank</b>: both throws together, firing alternately
	 * one revolution apart. Even-fire, and the runner-up.
	 *
	 * <p>Measurably the smoother of the two twins - 9.4 % speed ripple at idle
	 * against 15.6 % - and its engine-level torque waveform is <i>identical</i> to
	 * that of today's production inline-1, because one bang per 360 degrees at
	 * double the impulse is exactly what the current model already delivers. Passed
	 * over for character rather than for engineering: see {@link #R2_UNEVEN}.
	 *
	 * <p>Kept implemented, and one line from being the default, because it is the
	 * fallback if playtesting finds the uneven twin's idle too rough.
	 */
	R2_EVEN(new float[] { 0.0F, 360.0F }),

	/**
	 * Inline-2 on a <b>180-degree crank</b>: throws opposed, firing 180 and then 540
	 * degrees apart. <b>The frozen default.</b>
	 *
	 * <p>Uneven-fire, and chosen deliberately with the cost measured rather than
	 * guessed. It is the only engine in the mod's lineup that does not fire evenly,
	 * and that syncopation - {@code bang-bang..........bang-bang..........} - is the
	 * classic parallel twin, instantly distinguishable by ear from the inline-1's
	 * single thump and the inline-3's even beat. Its throws oppose, so its pistons
	 * alternate, which reads as a different machine from a single through the
	 * crankcase window.
	 *
	 * <p><b>What it costs, measured with the real flywheel and the real friction:</b>
	 * 15.6 % speed ripple at idle against the even twin's 9.4 %, and 4.0 % against
	 * 2.6 % at full throttle. That leaves it sitting correctly between the inline-1
	 * (23.8 %) and the inline-3 (3.7 %), so the smoothness ladder stays monotone, and
	 * it never approaches a stall - the worst case measured, 95 % load at idle, dips
	 * to 52.5 RPM against a stall threshold of 10.
	 *
	 * <p><b>Correcting Milestone 15A.</b> That milestone reported the uneven twin as
	 * having the <i>lower</i> ripple, and used it as an argument. That measurement
	 * was RMS torque with no inertia; under RMS torque the uneven twin does still
	 * win, because its opposed throws let one cylinder's compression hide under the
	 * other's power stroke. But a flywheel integrates torque, so what a player
	 * actually sees and hears is speed ripple - and by that measure the ranking
	 * reverses. The decision therefore rests on character, and the smoothness cost
	 * above is real and accepted.
	 */
	R2_UNEVEN(new float[] { 0.0F, 180.0F }),

	/**
	 * Inline-3, firing 1-2-3 evenly every 240 degrees.
	 *
	 * <p>Its throws come out at 0, 120 and 240 - <b>exactly the crank the production
	 * engine already has</b>. An inline-3 therefore looks identical after the switch
	 * to four-stroke; only its firing schedule spreads over two revolutions, and its
	 * order corrects itself from today's 1-3-2 to 1-2-3.
	 */
	R3(new float[] { 0.0F, 240.0F, 480.0F }),

	/**
	 * Inline-4, firing <b>1-3-4-2</b> evenly every 180 degrees.
	 *
	 * <p>Why this order rather than 1-2-4-3, the other conventional one: both are
	 * even-fire on the same flat-plane crank, and both are used in the real world.
	 * 1-3-4-2 is chosen because it is the order the overwhelming majority of inline-4
	 * road engines use, so it is the one a player is most likely to recognise, and
	 * because a reader who checks the mod's claim against any reference will find
	 * that order first. There is no simulation difference: the two are mirror images,
	 * and the torque profile of the engine is identical.
	 *
	 * <p>The throws come out at 0, 180, 180, 0 - cylinders 1 and 4 paired against 2
	 * and 3, which is the flat-plane crank every inline-4 four-stroke has. This is a
	 * visible change from today's 0/90/180/270 stagger.
	 */
	R4(new float[] { 0.0F, 540.0F, 180.0F, 360.0F });

	private final float[] ignitionOffsets;

	FourStrokeFiringOrder(float[] ignitionOffsets) {
		this.ignitionOffsets = ignitionOffsets;
	}

	/** How many cylinders this configuration describes. */
	public int cylinderCount() {
		return ignitionOffsets.length;
	}

	/**
	 * The default configuration for an engine of this many cylinders.
	 *
	 * <p>The one place a cylinder count becomes a crank, so that
	 * {@code EngineState} never has to know that R2 has two candidates.
	 */
	public static FourStrokeFiringOrder forCylinderCount(int cylinderCount) {
		return switch (cylinderCount) {
			case 1 -> R1;
			case 2 -> DEFAULT_R2;
			case 3 -> R3;
			default -> R4;
		};
	}

	/**
	 * The frozen inline-2 crank: <b>180 degrees, opposed, uneven-fire</b>.
	 *
	 * <p>Named rather than inlined into {@link #forCylinderCount} so that the one
	 * decision lives at one identifier, and so that reversing it - should
	 * playtesting want the smoother twin - is a single edit with a single test to
	 * update. See {@link #R2_UNEVEN} for what it was weighed against and what it
	 * costs.
	 */
	public static final FourStrokeFiringOrder DEFAULT_R2 = R2_UNEVEN;

	/**
	 * Crank travel from cylinder 1's ignition to cylinder {@code index}'s, in
	 * {@code [0, 720)}. Authoritative; everything else here is derived from it.
	 */
	public float ignitionOffsetDegrees(int index) {
		return ignitionOffsets[index];
	}

	/**
	 * The offset <i>added</i> to the master cycle angle to get this cylinder's own
	 * cycle angle, matching the production {@code master + offset} idiom.
	 *
	 * <pre>
	 * localCycleAngle(i) = normalizeCycle(masterCycleAngle + cyclePhaseOffsetDegrees(i))
	 * </pre>
	 *
	 * <p>The negation of the ignition offset, because a cylinder that fires
	 * <i>later</i> in the cycle is one whose own angle is <i>behind</i> the master's.
	 * Getting this sign wrong is not a rounding error - it plays the firing order
	 * backwards.
	 */
	public float cyclePhaseOffsetDegrees(int index) {
		return FourStrokeCycle.normalizeCycle(-ignitionOffsets[index]);
	}

	/**
	 * Where this cylinder's crank throw sits, in {@code [0, 360)}, relative to
	 * cylinder 1's.
	 *
	 * <pre>
	 * geometricOffset(i) = cyclePhaseOffset(i) % 360
	 * </pre>
	 *
	 * <p><b>Derived, and that is the invariant.</b> It is the same {@code % 360} that
	 * turns a cycle angle into a physical angle, applied to the offsets, so a
	 * cylinder's piston position computed from its physical angle and one computed by
	 * folding its cycle angle are the same number by construction rather than by two
	 * tables agreeing. Storing this separately is exactly how they would drift apart.
	 */
	public float geometricOffsetDegrees(int index) {
		return FourStrokeCycle.normalizeRevolution(cyclePhaseOffsetDegrees(index));
	}

	/**
	 * Cylinder numbers in the order they fire, 1-based: {@code [1, 3, 4, 2]} for an
	 * inline-4. Derived by sorting the ignition offsets, so it cannot disagree with
	 * the schedule the simulation actually runs.
	 */
	public int[] firingOrder() {
		Integer[] indices = new Integer[cylinderCount()];
		for (int i = 0; i < indices.length; i++)
			indices[i] = i;
		java.util.Arrays.sort(indices, java.util.Comparator.comparingDouble(i -> ignitionOffsets[i]));
		int[] order = new int[indices.length];
		for (int i = 0; i < indices.length; i++)
			order[i] = indices[i] + 1;
		return order;
	}

	/**
	 * Crank degrees between consecutive ignitions, in firing order and wrapping round
	 * the cycle. Sums to 720 by construction.
	 *
	 * <p>Where evenness lives: {@code [180, 180, 180, 180]} for an inline-4,
	 * {@code [720]} for a single, {@code [180, 540]} for the uneven twin. Nothing in
	 * the simulation reads it - it is how a test, or a diagnostic overlay, can state
	 * what the engine's rhythm actually is.
	 */
	public float[] ignitionIntervalsDegrees() {
		float[] sorted = ignitionOffsets.clone();
		java.util.Arrays.sort(sorted);
		float[] intervals = new float[sorted.length];
		for (int i = 0; i < sorted.length; i++)
			intervals[i] = i + 1 < sorted.length ? sorted[i + 1] - sorted[i]
				: FourStrokeCycle.CYCLE_DEGREES - sorted[i] + sorted[0];
		return intervals;
	}

	/** Whether every ignition is equally spaced round the cycle. */
	public boolean evenFire() {
		float[] intervals = ignitionIntervalsDegrees();
		for (float interval : intervals)
			if (Math.abs(interval - FourStrokeCycle.CYCLE_DEGREES / cylinderCount()) > 1.0E-3F)
				return false;
		return true;
	}
}
