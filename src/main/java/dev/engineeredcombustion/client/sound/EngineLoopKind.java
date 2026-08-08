package dev.engineeredcombustion.client.sound;

import dev.engineeredcombustion.content.engine.EnginePhase;
import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.registry.ECSounds;
import net.minecraft.sounds.SoundEvent;

/**
 * The continuous sounds an engine can be making, and the rule that picks one.
 *
 * <p>An engine is only ever in exactly one of these, which is what makes "no two
 * loops at once" a property of the type rather than something the manager has to
 * police.
 */
public enum EngineLoopKind {

	/** Silent: stopped, or turning too slowly to be worth hearing. */
	NONE,
	/** Being turned over by something else - not yet running on its own. */
	CRANKING,
	/** Running under its own power, or coasting on its own momentum. */
	RUNNING;

	/**
	 * Which loop a given engine state should be playing.
	 *
	 * <p>COASTING keeps the running loop: the engine is no longer firing but the
	 * flywheel is still spinning, and the loop's pitch and volume follow the speed
	 * down until it stalls. Cutting to silence at the moment combustion stops
	 * would sound like the engine teleported to a halt.
	 */
	public static EngineLoopKind forState(EnginePhase phase, float mechanicalRpm) {
		if (Math.abs(mechanicalRpm) < EngineTuning.SOUND_MIN_AUDIBLE_RPM)
			return NONE;
		return switch (phase) {
			case RUNNING, COASTING -> RUNNING;
			case CRANKING, STARTING -> CRANKING;
			case STOPPED -> NONE;
		};
	}

	public SoundEvent soundEvent() {
		return switch (this) {
			case RUNNING -> ECSounds.ENGINE_RUNNING.get();
			case CRANKING -> ECSounds.ENGINE_CRANKING.get();
			case NONE -> throw new IllegalStateException("NONE has no sound event");
		};
	}

	public float baseVolume() {
		return switch (this) {
			case RUNNING -> EngineTuning.SOUND_RUNNING_VOLUME;
			case CRANKING -> EngineTuning.SOUND_CRANKING_VOLUME;
			case NONE -> 0.0F;
		};
	}

	/** Speed to pitch, using the reference speed that matches this loop's asset. */
	public float pitchFor(float mechanicalRpm) {
		return switch (this) {
			case RUNNING -> EngineTuning.mapMechanicalRpmToEnginePitch(mechanicalRpm);
			case CRANKING -> EngineTuning.mapMechanicalRpmToCrankingPitch(mechanicalRpm);
			case NONE -> 1.0F;
		};
	}
}
