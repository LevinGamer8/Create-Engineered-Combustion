package dev.engineeredcombustion.content.engine.control;

import dev.engineeredcombustion.content.engine.EngineTuning;
import net.minecraft.util.Mth;

/**
 * What the engine has been <i>told</i> to do this tick, after every control
 * input has been reconciled.
 *
 * <p>There is exactly one producer of this record -
 * {@code CrankshaftBlockEntity#resolveControlState()} - and the simulation reads
 * nothing else. That is the point: before this existed, ignition came from a
 * redstone read buried in the tick loop, so "does redstone control this engine"
 * had no single answer and could not be changed without touching the simulation.
 * Now the mode is applied once, here, and everything downstream - combustion, the
 * indicator lamp, the ignition switch model, the HUD - reads the same resolved
 * answer.
 *
 * <p>The distinction that matters for the player is between the <b>manual</b>
 * settings, which are stored (the ignition switch on the crankcase, the throttle
 * on the Carburetor), and the <b>effective</b> values in this record, which may
 * come from redstone instead. Redstone never writes back into a stored setting,
 * so pulling the module out - or setting it back to
 * {@link ControlMode#MANUAL} - restores exactly what the player last set by hand.
 *
 * @param mode             the mode that produced these values;
 *                         {@link ControlMode#MANUAL} whenever no Redstone Control
 *                         Module is installed
 * @param ignitionEnabled  whether the ignition is live
 * @param throttlePercent  effective throttle opening, {@code [0, 100]}
 * @param redstoneSignal   the signal the resolution actually used, {@code [0, 15]};
 *                         always 0 in a mode that does not read redstone, so this
 *                         can be shown as-is without implying an input that is
 *                         being ignored
 */
public record EngineControlState(ControlMode mode, boolean ignitionEnabled, int throttlePercent, int redstoneSignal) {

	public EngineControlState {
		throttlePercent =
			Mth.clamp(throttlePercent, EngineTuning.THROTTLE_MIN_PERCENT, EngineTuning.THROTTLE_MAX_PERCENT);
		redstoneSignal = Mth.clamp(redstoneSignal, 0, ControlMode.MAX_SIGNAL);
	}

	/** The same throttle opening as the fraction in {@code [0, 1]} the simulation wants. */
	public float throttle() {
		return throttlePercent / (float) EngineTuning.THROTTLE_MAX_PERCENT;
	}

	/** Whether redstone automation is actually driving this engine right now. */
	public boolean isRedstoneControlled() {
		return mode.usesRedstone();
	}
}
