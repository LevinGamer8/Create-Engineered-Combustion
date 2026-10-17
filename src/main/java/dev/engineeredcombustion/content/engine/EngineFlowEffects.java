package dev.engineeredcombustion.content.engine;

import java.util.function.IntFunction;

import org.joml.Vector3f;

import dev.engineeredcombustion.content.engine.fourstroke.FourStrokePhase;
import dev.engineeredcombustion.content.engine.fourstroke.ValveTiming;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * What the gas does, made visible - restrained in normal play, explicit for a
 * player wearing Engineer's Goggles.
 *
 * <h2>Two layers, and the second is opt-in</h2>
 * <b>Normal:</b> one small grey puff from the exhaust outlet each time a burnt
 * charge is pushed out. Nothing on the intake: a real engine's intake is not
 * visible from outside, and an engine permanently wreathed in coloured smoke would
 * be a worse thing to look at than one with none.
 *
 * <p><b>Teaching</b> (goggles): a cool trace at each cylinder's intake valve while
 * it is open, and a warm one at each exhaust header while that cylinder is venting
 * a charge it really burned. This is the layer that answers "which valve is which,
 * and when does it open" - and it costs nothing for a player who is not asking.
 *
 * <h2>Nothing here has a clock of its own</h2>
 * Every effect is a function of two things the engine already owns: the combustion
 * events the server reports, and each cylinder's place in the cycle. A puff needs
 * BOTH - a charge that burned, and then that cylinder's exhaust valve coming off
 * its seat - so it happens on the exhaust stroke after a real power stroke and on
 * no other. An engine being motored by another Create source pumps air and burns
 * nothing, and so it never puffs; an engine whose tank has just run dry stops
 * puffing one exhaust stroke after its last real bang, exactly as its sound does.
 *
 * <p>The intake trace needs no combustion, because an engine being turned over
 * really does draw air whenever an intake valve is open. That is honest, and it is
 * also the diagnosis a player with goggles wants: air moving and no exhaust heat
 * is an engine that is turning and not firing.
 *
 * <h2>One outlet</h2>
 * An engine has one exhaust stack, on its last cylinder (see
 * {@code exhaust_elements} in the model generator), so every cylinder's charge
 * leaves from the same place - a train of pulses from one pipe, which is what an
 * inline-4 at the tailpipe is. Cylinders that vent on the same tick are one puff,
 * so the outlet emits at most once a tick whatever the layout.
 *
 * <h2>Cost</h2>
 * At most a couple of particles per engine per tick in normal play, and the
 * teaching layer is strided so each cylinder emits on one tick in
 * {@link #TEACHING_STRIDE_TICKS}. Vanilla already drops particles more than 32
 * blocks from the camera and honours the player's particle setting, so neither is
 * re-implemented here.
 *
 * <p>Dist-neutral for the same reason {@link CombustionAudio} is: it touches only
 * {@code Level}, {@code BlockPos} and vanilla particle options, and
 * {@code Level#addParticle} is an empty method off the client. Whether the
 * teaching layer is on is decided by the caller, which is the only part of this
 * that needs to know who is looking.
 */
public final class EngineFlowEffects {

	/**
	 * The exhaust stack's mouth, in Cylinder block model units, authored for an
	 * engine running along X. Must match {@code EXHAUST_OUTLET} in
	 * {@code tools/generate_engine_models.py}; {@code tools/check_models.py} checks.
	 */
	public static final double OUTLET_X = 13.0D;
	public static final double OUTLET_Y = 22.0D;
	public static final double OUTLET_Z = 17.3D;

	/** Just outboard of a cylinder's exhaust header, where the warm trace starts. */
	private static final double EXHAUST_PORT_X = 8.0D;
	private static final double EXHAUST_PORT_Y = 15.0D;
	private static final double EXHAUST_PORT_Z = 18.8D;

	/** Beside the intake valve's guide, over the intake port it opens. */
	private static final double INTAKE_VALVE_X = 3.6D;
	private static final double INTAKE_VALVE_Y = 18.0D;
	private static final double INTAKE_VALVE_Z = 4.2D;

	/** Each cylinder's teaching trace is drawn on one tick in this many. */
	private static final int TEACHING_STRIDE_TICKS = 3;

	/** Below this much valve lift the teaching layer stays dark: a valve just cracking open. */
	private static final float TEACHING_MIN_LIFT = 0.25F;

	/**
	 * How long a burnt charge waits for its exhaust stroke before it is forgotten.
	 *
	 * <p>Ten seconds, which is far longer than any running engine takes and is only
	 * there so an engine that stalled mid power stroke, and was then taken apart,
	 * does not keep a stale charge forever. An engine that stalls and is cranked
	 * again within it vents that charge on its first exhaust stroke, which is what
	 * would really happen.
	 */
	private static final int BURNT_CHARGE_TIMEOUT_TICKS = 200;

	/** Above this throttle a puff carries a second particle: the engine is working. */
	private static final float LOADED_THROTTLE = 0.66F;

	private static final DustParticleOptions COOL_TRACE = new DustParticleOptions(new Vector3f(0.45F, 0.72F, 1.0F),
		0.55F);
	private static final DustParticleOptions WARM_TRACE = new DustParticleOptions(new Vector3f(1.0F, 0.48F, 0.18F),
		0.6F);

	private final boolean[] burntCharge = new boolean[EngineTuning.MAX_CYLINDERS];
	private final boolean[] vented = new boolean[EngineTuning.MAX_CYLINDERS];
	private final int[] burntAge = new int[EngineTuning.MAX_CYLINDERS];

	/** Cylinder {@code cylinder} really burned a charge. Called from the server's event bit. */
	public void onCombustion(int cylinder) {
		if (cylinder < 0 || cylinder >= burntCharge.length)
			return;
		burntCharge[cylinder] = true;
		vented[cylinder] = false;
		burntAge[cylinder] = 0;
	}

	/**
	 * One client tick of flow.
	 *
	 * @param cylinderPos where cylinder {@code i}'s Cylinder block is
	 * @param teaching    whether the player looking is wearing Engineer's Goggles
	 */
	public void tick(Level level, EngineState engine, Axis axis, int cylinderCount, IntFunction<BlockPos> cylinderPos,
		boolean teaching) {
		int count = Math.min(cylinderCount, EngineTuning.MAX_CYLINDERS);
		if (count <= 0)
			return;
		long time = level.getGameTime();
		boolean turning = engine.getMechanicalRpm() != 0.0F;
		int venting = 0;

		for (int cylinder = 0; cylinder < count; cylinder++) {
			float angle = engine.localCycleAngleDegrees(cylinder);
			boolean onIntakeStroke = FourStrokePhase.at(angle) == FourStrokePhase.INTAKE;
			boolean strideTick = (time + cylinder) % TEACHING_STRIDE_TICKS == 0L;

			if (burntCharge[cylinder]) {
				float exhaustLift = ValveTiming.EXHAUST.lift(angle);
				// Venting starts the moment the exhaust valve lifts. The intake stroke is
				// accepted too, so an engine fast enough to cross the whole exhaust window
				// inside one tick still puffs for the charge it burned - late by a tick,
				// never invented.
				if (!vented[cylinder] && (exhaustLift > 0.0F || onIntakeStroke)) {
					vented[cylinder] = true;
					venting++;
				}
				if (teaching && strideTick && exhaustLift > TEACHING_MIN_LIFT)
					emit(level, cylinderPos.apply(cylinder), axis, WARM_TRACE, EXHAUST_PORT_X, EXHAUST_PORT_Y,
						EXHAUST_PORT_Z, 0.0D, 0.01D, 0.025D);
				if (onIntakeStroke || ++burntAge[cylinder] > BURNT_CHARGE_TIMEOUT_TICKS)
					burntCharge[cylinder] = false;
			}

			if (teaching && turning && strideTick && ValveTiming.INTAKE.lift(angle) > TEACHING_MIN_LIFT)
				emit(level, cylinderPos.apply(cylinder), axis, COOL_TRACE, INTAKE_VALVE_X, INTAKE_VALVE_Y,
					INTAKE_VALVE_Z, 0.012D, -0.018D, 0.012D);
		}

		if (venting > 0)
			puff(level, cylinderPos.apply(count - 1), axis, engine.getThrottle());
	}

	private static void puff(Level level, BlockPos outletCylinder, Axis axis, float throttle) {
		RandomSource random = level.random;
		int particles = throttle > LOADED_THROTTLE ? 2 : 1;
		double rise = 0.035D + 0.025D * Math.max(0.0F, Math.min(1.0F, throttle));
		for (int i = 0; i < particles; i++)
			emit(level, outletCylinder, axis, ParticleTypes.SMOKE, OUTLET_X + jitter(random), OUTLET_Y,
				OUTLET_Z + jitter(random), jitter(random) * 0.01D, rise, jitter(random) * 0.01D);
	}

	private static double jitter(RandomSource random) {
		return (random.nextDouble() - 0.5D) * 0.6D;
	}

	/**
	 * One particle at a point given in Cylinder model units for an engine along X.
	 *
	 * <p>An engine along Z has its Cylinder drawn a quarter turn round, mapping the
	 * block's (x, z) to (16 - z, x) - the blockstate's {@code "y": 90} - so the
	 * point and its motion make the same turn, as the spark particle does.
	 */
	private static void emit(Level level, BlockPos pos, Axis axis, ParticleOptions particle, double x, double y,
		double z, double vx, double vy, double vz) {
		double px = axis == Axis.Z ? 16.0D - z : x;
		double pz = axis == Axis.Z ? x : z;
		double mx = axis == Axis.Z ? -vz : vx;
		double mz = axis == Axis.Z ? vx : vz;
		level.addParticle(particle, pos.getX() + px / 16.0D, pos.getY() + y / 16.0D, pos.getZ() + pz / 16.0D, mx, vy,
			mz);
	}
}
