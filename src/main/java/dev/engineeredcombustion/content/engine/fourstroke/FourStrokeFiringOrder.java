package dev.engineeredcombustion.content.engine.fourstroke;

import java.util.Arrays;
import java.util.Comparator;

/**
 * The crank and firing schedule of each inline engine the mod builds.
 *
 * <h2>The correction this class exists to make</h2>
 * Before Milestone 15B the engine had one number per cylinder and used it for two
 * different jobs: where the crank throw is, and when the cylinder fires. On a
 * two-stroke those are the same question. On a four-stroke they are not - cylinders
 * 1 and 4 of an inline-4 sit on the <i>same</i> throw and their pistons move
 * together, yet they fire a full revolution apart - so this class carries both,
 * separately:
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
 * controller: exactly the numbering {@code EngineComponents} already assigns, and the
 * only one available, because the Flywheel may legally sit at <i>either</i> end of the
 * run and so cannot define an end.
 *
 * <h2>Sign convention</h2>
 * A cylinder's angle is {@code master + phaseOffset(i)}, so the offset that is
 * <i>added</i> is {@link #cyclePhaseOffsetDegrees}, the negation of the ignition
 * offset. The distinction is not cosmetic: with evenly spaced offsets on an even
 * cylinder count the sign is invisible, but on an inline-3 it reverses the firing
 * order.
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
	 * <p>Measurably the smoother of the two twins - 9.4 % speed ripple at idle against
	 * 15.6 % - and its engine-level torque waveform is <i>identical</i> to that of the
	 * pre-15B inline-1. Passed over for character rather than for engineering: see
	 * {@link #R2_UNEVEN}. Kept implemented, and one line from being the default, as the
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
	 * 2.6 % at full throttle. That leaves it correctly between the inline-1 (23.8 %)
	 * and the inline-3 (3.7 %), so the smoothness ladder stays monotone, and it never
	 * approaches a stall.
	 */
	R2_UNEVEN(new float[] { 0.0F, 180.0F }),

	/**
	 * Inline-3, firing 1-2-3 evenly every 240 degrees.
	 *
	 * <p>Its throws come out at 0, 120 and 240 - <b>exactly the crank the engine
	 * already had</b>. An inline-3 therefore looks identical after the switch to four
	 * stroke; only its firing schedule spreads over two revolutions, and its order
	 * corrects itself from 1-3-2 to 1-2-3.
	 */
	R3(new float[] { 0.0F, 240.0F, 480.0F }),

	/**
	 * Inline-4, firing <b>1-3-4-2</b> evenly every 180 degrees.
	 *
	 * <p>Why this order rather than 1-2-4-3: both are even-fire on the same flat-plane
	 * crank and both are used in the real world. 1-3-4-2 is the order the overwhelming
	 * majority of inline-4 road engines use, so it is the one a player is most likely
	 * to recognise. There is no simulation difference: the two are mirror images.
	 *
	 * <p>The throws come out at 0, 180, 180, 0 - cylinders 1 and 4 paired against 2 and
	 * 3, which is the flat-plane crank every inline-4 four-stroke has. This is a
	 * visible change from the old 0/90/180/270 stagger.
	 */
	R4(new float[] { 0.0F, 540.0F, 180.0F, 360.0F });

	/**
	 * The frozen inline-2 crank: <b>180 degrees, opposed, uneven-fire</b>.
	 *
	 * <p>Named rather than inlined into {@link #forCylinderCount} so the one decision
	 * lives at one identifier, and so reversing it - should playtesting want the
	 * smoother twin - is a single edit.
	 */
	public static final FourStrokeFiringOrder DEFAULT_R2 = R2_UNEVEN;

	private final float[] ignitionOffsets;
	private final float[] cyclePhaseOffsets;
	private final float[] geometricOffsets;

	FourStrokeFiringOrder(float[] ignitionOffsets) {
		this.ignitionOffsets = ignitionOffsets;
		// Derived once, at class initialisation, so the per-tick path is an array read
		// and the derived-not-authored rule costs nothing.
		this.cyclePhaseOffsets = new float[ignitionOffsets.length];
		this.geometricOffsets = new float[ignitionOffsets.length];
		for (int i = 0; i < ignitionOffsets.length; i++) {
			cyclePhaseOffsets[i] = FourStrokeCycle.normalizeCycle(-ignitionOffsets[i]);
			geometricOffsets[i] = FourStrokeCycle.normalizeRevolution(cyclePhaseOffsets[i]);
		}
	}

	/** How many cylinders this configuration describes. */
	public int cylinderCount() {
		return ignitionOffsets.length;
	}

	/**
	 * The default configuration for an engine of this many cylinders.
	 *
	 * <p>The one place a cylinder count becomes a crank, so that {@code EngineState}
	 * never has to know that R2 has two candidates.
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
	 * Crank travel from cylinder 1's ignition to cylinder {@code index}'s, in
	 * {@code [0, 720)}. Authoritative; everything else here is derived from it.
	 */
	public float ignitionOffsetDegrees(int index) {
		return ignitionOffsets[clamp(index)];
	}

	/**
	 * The offset <i>added</i> to the master cycle angle to get this cylinder's own
	 * cycle angle.
	 *
	 * <pre>
	 * localCycleAngle(i) = normalizeCycle(masterCycleAngle + cyclePhaseOffsetDegrees(i))
	 * </pre>
	 *
	 * <p>The negation of the ignition offset, because a cylinder that fires <i>later</i>
	 * in the cycle is one whose own angle is <i>behind</i> the master's. Getting this
	 * sign wrong is not a rounding error - it plays the firing order backwards.
	 */
	public float cyclePhaseOffsetDegrees(int index) {
		return cyclePhaseOffsets[clamp(index)];
	}

	/**
	 * Where this cylinder's crank throw sits, in {@code [0, 360)}, relative to
	 * cylinder 1's.
	 *
	 * <p><b>Derived, and that is the invariant.</b> It is the same {@code % 360} that
	 * turns a cycle angle into a physical angle, applied to the offsets, so a piston
	 * position computed from a physical angle and one computed by folding a cycle
	 * angle are the same number by construction rather than by two tables agreeing.
	 */
	public float geometricOffsetDegrees(int index) {
		return geometricOffsets[clamp(index)];
	}

	private int clamp(int index) {
		return index < 0 ? 0 : Math.min(index, ignitionOffsets.length - 1);
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
		Arrays.sort(indices, Comparator.comparingDouble(i -> ignitionOffsets[i]));
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
	 * {@code [720]} for a single, {@code [180, 540]} for the uneven twin.
	 */
	public float[] ignitionIntervalsDegrees() {
		float[] sorted = ignitionOffsets.clone();
		Arrays.sort(sorted);
		float[] intervals = new float[sorted.length];
		for (int i = 0; i < sorted.length; i++)
			intervals[i] = i + 1 < sorted.length ? sorted[i + 1] - sorted[i]
				: FourStrokeCycle.CYCLE_DEGREES - sorted[i] + sorted[0];
		return intervals;
	}

	/** Whether every ignition is equally spaced round the cycle. */
	public boolean evenFire() {
		for (float interval : ignitionIntervalsDegrees())
			if (Math.abs(interval - FourStrokeCycle.CYCLE_DEGREES / cylinderCount()) > 1.0E-3F)
				return false;
		return true;
	}

	/**
	 * The mean crank travel between two consecutive combustion events anywhere in the
	 * engine: {@code 720 / cylinderCount}.
	 *
	 * <p>Used to scale how long a cylinder may go without firing before it stops
	 * counting towards the engine's output - see
	 * {@code EngineTuning#generationCombustionAllowanceTicks}. Note it is the mean and
	 * not the shortest interval: on the uneven twin the two are 360 and 180, and an
	 * allowance built on the shorter one would expire a perfectly healthy cylinder
	 * every long gap.
	 */
	public float meanIgnitionIntervalDegrees() {
		return FourStrokeCycle.CYCLE_DEGREES / cylinderCount();
	}
}
