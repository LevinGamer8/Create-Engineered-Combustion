package dev.engineeredcombustion.prototype.fourstroke;

import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.LubricationState;

/**
 * A prototype four-stroke engine with momentum, so that questions about ripple,
 * flywheel inertia and stability can be measured instead of argued.
 *
 * <p><b>Prototype. Nothing in the mod depends on this.</b> The dependency runs the
 * other way and only in this direction: the rig imports the production
 * {@code EngineTuning} so that friction, inertia, the governor and the load model
 * are <i>the real ones</i>, and a number measured here is directly comparable with
 * one measured from the live {@code EngineState}. Production cannot see this class -
 * {@code src/prototype/java} is in the {@code simulationTest} source set alone.
 *
 * <h2>What it adds over {@link FourStrokeEngine}, and what it deliberately does not</h2>
 * It adds exactly one thing: a crankshaft with mass. The integration is the
 * production {@code EngineState#integrate} arithmetic - combustion plus the gas
 * spring, less friction and load, over {@code FLYWHEEL_INERTIA}. It does <b>not</b>
 * add a phase machine, a fuel tank, wear, lubrication states, output filtering or
 * network publishing, because those are not what any open question is about, and
 * reproducing them here would be the start of the second engine framework the
 * milestone says not to build.
 */
public final class FourStrokeRig {

	/**
	 * How much stronger one four-stroke combustion event must be to hold the same
	 * mean output.
	 *
	 * <p>Not a tuning constant and not a fudge: {@code peakCombustionTorqueFor}
	 * solves {@code peak * POWER_STROKE_DUTY * 0.5 = friction(target)}, and the duty
	 * goes from {@code 180/360} to {@code 180/720}. Halving the duty doubles the
	 * solution, exactly. This factor is that ratio, written once, so the tests can
	 * demonstrate the identity rather than assume it.
	 */
	public static final float FOUR_STROKE_TORQUE_SCALE = 2.0F;

	private final FourStrokeEngine engine;
	private final float targetRpm;
	private final float loadFactor;
	private final float compressionPeak;
	private final float pumpingPeak;

	private float rpm;

	public FourStrokeRig(FourStrokeFiringOrder configuration, float startRpm, float targetRpm, float loadFactor) {
		this(configuration, startRpm, targetRpm, loadFactor, EngineTuning.COMPRESSION_PEAK_TORQUE, 0.0F);
	}

	public FourStrokeRig(FourStrokeFiringOrder configuration, float startRpm, float targetRpm, float loadFactor,
		float compressionPeak, float pumpingPeak) {
		this.engine = new FourStrokeEngine(configuration);
		this.rpm = startRpm;
		this.targetRpm = targetRpm;
		this.loadFactor = loadFactor;
		this.compressionPeak = compressionPeak;
		this.pumpingPeak = pumpingPeak;
		engine.armAsIfRested();
	}

	/**
	 * One game tick: turn the crank by what the current speed says, settle the
	 * cylinders, then integrate the torque they produced.
	 *
	 * @return which cylinders ignited, one bit each
	 */
	public int tick() {
		float delta = EngineTuning.degreesPerTick(rpm);
		int ignited = engine.step(delta, true);

		float combustion = engine.combustionTorque(peakCombustionTorque());
		float spring = engine.compressionTorque(compressionPeak);
		float pumping = pumpingTorque();
		integrate(combustion + spring + pumping);
		return ignited;
	}

	/**
	 * Peak combustion torque of this engine at its current speed, four-stroke.
	 *
	 * <p>The production figure with the duty correction applied. Everything else -
	 * the governor's response to being off target, the division by cylinder count -
	 * is the production function, unchanged.
	 */
	public float peakCombustionTorque() {
		// The ENGINE-wide figure, deliberately not the per-cylinder one:
		// FourStrokeEngine divides by the cylinder count itself, so asking
		// EngineTuning for the already-divided value here would divide twice and
		// leave an inline-4 making a sixteenth of the torque it should.
		return EngineTuning.combustionTorqueAt(rpm, targetRpm) * FOUR_STROKE_TORQUE_SCALE;
	}

	private float pumpingTorque() {
		if (pumpingPeak == 0.0F)
			return 0.0F;
		float total = 0.0F;
		for (int i = 0; i < engine.cylinderCount(); i++)
			total += FourStrokeCycle.pumpingTorque(engine.cylinder(i).cycleAngle()) * pumpingPeak;
		return total;
	}

	/**
	 * The production {@code EngineState#integrate} arithmetic, less the parts that
	 * only apply to an engine that is not firing.
	 *
	 * <p>Friction always opposes rotation; the kinetic load Create has hung on the
	 * engine is drag of the same kind. Compression is deliberately not added to drag:
	 * it has a sign of its own and returns over a cycle exactly what it takes.
	 */
	private void integrate(float torque) {
		float drag = EngineTuning.frictionTorqueAt(rpm, LubricationState.NORMAL)
			+ EngineTuning.loadDragTorque(loadFactor);
		float net = torque - Math.signum(rpm) * drag;
		rpm += net / EngineTuning.FLYWHEEL_INERTIA;
	}

	/**
	 * Runs the engine until its speed stops moving, then reports the ripple over a
	 * whole number of cycles.
	 *
	 * @param settleTicks how long to run before measuring
	 * @param measureTicks how long to measure over
	 */
	public Sample run(int settleTicks, int measureTicks) {
		for (int i = 0; i < settleTicks; i++)
			tick();

		float minRpm = Float.MAX_VALUE;
		float maxRpm = -Float.MAX_VALUE;
		float minTorque = Float.MAX_VALUE;
		float maxTorque = -Float.MAX_VALUE;
		double rpmSum = 0.0D;
		double torqueSum = 0.0D;
		double torqueSquares = 0.0D;
		int ignitionCount = 0;

		for (int i = 0; i < measureTicks; i++) {
			if (tick() != 0)
				ignitionCount++;
			float torque = engine.netTorque(peakCombustionTorque(), compressionPeak, pumpingPeak);
			minRpm = Math.min(minRpm, rpm);
			maxRpm = Math.max(maxRpm, rpm);
			minTorque = Math.min(minTorque, torque);
			maxTorque = Math.max(maxTorque, torque);
			rpmSum += rpm;
			torqueSum += torque;
			torqueSquares += (double) torque * torque;
		}

		double meanTorque = torqueSum / measureTicks;
		double rms = Math.sqrt(torqueSquares / measureTicks - meanTorque * meanTorque);
		return new Sample((float) (rpmSum / measureTicks), minRpm, maxRpm, (float) meanTorque,
			minTorque, maxTorque, (float) rms, ignitionCount, measureTicks);
	}

	/** One measurement run's results. */
	public record Sample(float meanRpm, float minRpm, float maxRpm, float meanTorque, float minTorque,
		float maxTorque, float rmsTorqueRipple, int ignitionTicks, int ticks) {

		/** Peak-to-peak speed swing, in RPM. What a player sees the flywheel do. */
		public float rpmRipple() {
			return maxRpm - minRpm;
		}

		/** The same as a fraction of mean speed, so engines at different speeds compare. */
		public float rpmRippleFraction() {
			return meanRpm == 0.0F ? 0.0F : rpmRipple() / meanRpm;
		}

		/** Peak-to-peak torque swing. */
		public float torqueRipple() {
			return maxTorque - minTorque;
		}

		/** Combustion events per second at 20 ticks a second. */
		public float eventsPerSecond() {
			return ignitionTicks * 20.0F / ticks;
		}
	}

	public float rpm() {
		return rpm;
	}

	public FourStrokeEngine engine() {
		return engine;
	}
}
