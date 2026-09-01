package dev.engineeredcombustion.content.engine;

import dev.engineeredcombustion.registry.ECSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * Turns real combustion events into sound, one engine at a time.
 *
 * <h2>The pulses are the engine</h2>
 * A single-cylinder engine fires once per revolution: 1.07 times a second at
 * idle, 3.2 at full throttle. The ear resolves every one of those, so the honest
 * sound of this machine is a train of separate bangs over a mechanical bed -
 * <b>PUT ... PUT ... PUT</b> at idle, <b>PUT-PUT-PUT-PUT</b> with the throttle
 * open - and not a smooth loop pitched up and down to imply a speed.
 *
 * <p>Every pulse this class plays is one charge that really burned. It is driven
 * from the combustion bits of {@code EngineTickPayload} - the server's
 * own record of the same event that consumed the fuel, delivered the torque,
 * advanced the start attempt and lit the chamber flash, sent once per engine per
 * tick. There is no audio timer anywhere in the mod,
 * and nothing here re-derives when a combustion "should" have happened from the
 * crank angle - which is what makes the rhythm the player hears the rhythm the
 * engine actually has, including when it is uneven, and including when it stops.
 *
 * <h2>Scaling past one slow cylinder</h2>
 * One-shot per event is right at 3.2 Hz and would be absurd at 60. Rather than
 * bake that assumption in, the scheduler <i>measures</i> the rate the events are
 * arriving at and, past
 * {@link EngineTuning#SOUND_COMBUSTION_PULSE_MAX_RATE_HZ}, thins the pulses out
 * by an integer stride while a continuous combustion layer fades in underneath
 * them (see {@link EngineTuning#combustionLoopBlend}). The current engine cannot
 * reach that threshold, so today this is entirely dormant - but a faster engine,
 * a four-stroke or a second cylinder will not need the audio rewritten to arrive.
 *
 * <h2>Why this is not in the client package</h2>
 * It is only ever driven from client-side paths, but it touches no client-only
 * type: {@code Level}, {@code BlockPos}, the sound registry, and
 * {@code Level#playLocalSound}, which is an empty method outside the client. That
 * lets the block entity hold one as a plain final field, with no dist guard on
 * the declaration and no chance of a dedicated server failing to load it.
 */
public final class CombustionAudio {

	/**
	 * Height within the Cylinder block that the pulse comes from: the combustion
	 * chamber, just under the head.
	 *
	 * <p>The point of putting it here rather than at the crankshaft is that the
	 * engine is five blocks tall and its two voices come from two different parts
	 * of it. The mechanical layer sits at the crankcase, where the bearings and the
	 * flywheel are; the bang comes from the top of the bore, a block and a half
	 * higher. Standing next to a running engine, the difference is audible.
	 */
	private static final double CHAMBER_HEIGHT = 0.78D;

	/**
	 * How much of a newly measured interval is believed, per event.
	 *
	 * <p>Half. A single-cylinder engine's firing interval genuinely varies
	 * cycle to cycle, so the rate must not chase every wobble; but it also has to
	 * catch up within a revolution or two when the throttle is swept, or the
	 * scheduler would be reasoning about a speed the engine has left behind.
	 */
	private static final float RATE_SMOOTHING = 0.5F;

	/** Pitch factor for a charge that fires in an engine which has not caught yet. */
	private static final float STARTING_PITCH_FACTOR = 0.94F;

	/** Game time of the last combustion event this client saw, or unset. */
	private long lastEventTick = Long.MIN_VALUE;

	private float eventRateHz;

	/** Events skipped since the last pulse actually played. Only used above the threshold. */
	private int eventsSinceLastPulse;

	/**
	 * Lets the measured rate fall when the engine stops firing.
	 *
	 * <p>No timer and no extra state: an engine that last fired {@code n} ticks ago
	 * cannot be firing faster than once every {@code n} ticks, so simply capping the
	 * rate at that ceiling every tick makes it decay on its own - immediately for a
	 * fast engine, gently for a slow one. When combustion stops altogether the rate
	 * slides to zero, which is exactly what the blend logic needs to hear.
	 */
	public void tick(long gameTime) {
		if (lastEventTick == Long.MIN_VALUE || eventRateHz <= 0.0F)
			return;
		long silence = gameTime - lastEventTick;
		if (silence <= 0L)
			return;
		float ceiling = 20.0F / silence;
		if (eventRateHz > ceiling)
			eventRateHz = ceiling;
	}

	/**
	 * One charge burned. Updates the measured rate and, if this event is one the
	 * scheduler wants heard individually, plays it.
	 *
	 * @param cylinderPos the Cylinder block, i.e. where the combustion chamber is
	 */
	public void onCombustion(Level level, BlockPos cylinderPos, EngineState engine) {
		long now = level.getGameTime();
		if (lastEventTick != Long.MIN_VALUE) {
			float instant = 20.0F / Math.max(1L, now - lastEventTick);
			eventRateHz = eventRateHz <= 0.0F ? instant : eventRateHz + (instant - eventRateHz) * RATE_SMOOTHING;
		}
		lastEventTick = now;

		if (wantsIndividualPulse())
			playPulse(level, cylinderPos, engine);
	}

	/** Events per second, as measured from the events themselves. */
	public float getEventRateHz() {
		return eventRateHz;
	}

	/**
	 * Whether this event gets its own one-shot.
	 *
	 * <p>Always, at any rate this engine can currently reach. Above the threshold
	 * the stride thins them out so the count of one-shots in flight stays bounded
	 * however fast a future engine fires.
	 */
	private boolean wantsIndividualPulse() {
		int stride = pulseStride();
		if (stride <= 1) {
			eventsSinceLastPulse = 0;
			return true;
		}
		if (++eventsSinceLastPulse < stride)
			return false;
		eventsSinceLastPulse = 0;
		return true;
	}

	private int pulseStride() {
		if (eventRateHz <= EngineTuning.SOUND_COMBUSTION_PULSE_MAX_RATE_HZ)
			return 1;
		return Math.max(1, Math.round(eventRateHz / EngineTuning.SOUND_COMBUSTION_PULSE_MAX_RATE_HZ));
	}

	/**
	 * The bang itself.
	 *
	 * <p>Three things vary, all of them deliberately subtle. The <b>variant</b> is
	 * picked by Minecraft from the three recordings behind
	 * {@link ECSounds#ENGINE_FIRE}, and the small random <b>pitch</b> and
	 * <b>volume</b> spread added here are what stop a steady engine from sounding
	 * like one sample on a metronome - a real single-cylinder engine is never
	 * metronomic. The <b>speed</b> term bends the pitch by at most a tenth across
	 * the whole range, and it is emphatically not how the firing rate is conveyed:
	 * the rate is conveyed by there being one of these per combustion.
	 *
	 * <p>A charge that fires in an engine which has not caught yet is quieter and a
	 * little duller - same event, in a cylinder that is barely turning with no
	 * momentum behind it - which is what makes a start read as
	 * "rrrr, PUT, rrrr, PUT, PUT-BRUM".
	 */
	private void playPulse(Level level, BlockPos cylinderPos, EngineState engine) {
		RandomSource random = level.getRandom();
		boolean running = engine.getPhase() == EnginePhase.RUNNING;
		// Pulses give way as the continuous layer takes over, so the two never
		// simply stack. Zero blend at every rate this engine can reach today.
		float blend = 1.0F - EngineTuning.combustionLoopBlend(eventRateHz);
		float volume = (running ? EngineTuning.SOUND_COMBUSTION_VOLUME
			: EngineTuning.SOUND_COMBUSTION_STARTING_VOLUME)
			* jitter(random, EngineTuning.SOUND_COMBUSTION_VOLUME_JITTER) * blend;
		float pitch = EngineTuning.combustionPulsePitch(engine.getMechanicalRpm())
			* jitter(random, EngineTuning.SOUND_COMBUSTION_PITCH_JITTER)
			* (running ? 1.0F : STARTING_PITCH_FACTOR);

		if (volume <= 0.0F)
			return;

		level.playLocalSound(cylinderPos.getX() + 0.5D, cylinderPos.getY() + CHAMBER_HEIGHT,
			cylinderPos.getZ() + 0.5D, ECSounds.ENGINE_FIRE.get(), SoundSource.BLOCKS, volume, pitch, false);
	}

	/** A multiplier in {@code [1 - spread, 1 + spread]}. */
	private static float jitter(RandomSource random, float spread) {
		return 1.0F + (random.nextFloat() * 2.0F - 1.0F) * spread;
	}
}
