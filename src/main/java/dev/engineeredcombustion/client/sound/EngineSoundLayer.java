package dev.engineeredcombustion.client.sound;

import dev.engineeredcombustion.content.engine.EngineState;
import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.LubricationState;
import dev.engineeredcombustion.registry.ECSounds;
import net.minecraft.sounds.SoundEvent;

/**
 * The continuous layers an engine can be playing, and what each one is worth at
 * any given moment.
 *
 * <h2>Two clocks, two layers</h2>
 * The engine makes two kinds of noise and they do not follow the same thing, so
 * they are not the same sound:
 *
 * <dl>
 * <dt>{@link #MECHANICAL}</dt>
 * <dd>Crankshaft, bearings, flywheel, the piston pumping against compression. It
 * follows <b>mechanical RPM</b>, because it is the sound of something rotating,
 * and it plays whenever the crank turns at all - being cranked by hand, running,
 * coasting down, or motored by another engine on the network. It contains no
 * combustion whatsoever, which is what lets a fuel-starved engine still being
 * spun by its neighbour sound exactly like what it is.</dd>
 * <dt>{@link #COMBUSTION}</dt>
 * <dd>The aggregate of firing events, for engines that fire too fast to hear
 * individually. It follows the <b>measured firing rate</b> and is silent below
 * {@link EngineTuning#SOUND_COMBUSTION_PULSE_MAX_RATE_HZ} - which the current
 * single-cylinder engine never reaches, so today this layer never plays. What
 * carries combustion now is a one-shot per real event; see
 * {@code CombustionAudio}.</dd>
 * </dl>
 *
 * <p>Both layers may be live at once, which is why they are layers rather than
 * the mutually exclusive "loop kinds" this replaced. The old arrangement had one
 * loop for cranking and another for running, so the moment combustion started or
 * stopped the whole sound of the engine was swapped for a different recording -
 * and the running loop had firing baked into it, which meant an engine that had
 * run out of fuel went on sounding like it was burning some until it fell below
 * the swap threshold.
 */
public enum EngineSoundLayer {

	MECHANICAL,
	COMBUSTION;

	public SoundEvent soundEvent() {
		return switch (this) {
			case MECHANICAL -> ECSounds.ENGINE_MECHANICAL.get();
			case COMBUSTION -> ECSounds.ENGINE_COMBUSTION_LOOP.get();
		};
	}

	/**
	 * What this layer should be playing at, right now, for this engine.
	 *
	 * @param eventRateHz measured combustion events per second
	 * @return the volume, or 0 when this layer has nothing to say - which is how a
	 *         layer asks to be retired
	 */
	public float volumeFor(EngineState engine, float eventRateHz) {
		return switch (this) {
			case MECHANICAL -> mechanicalVolume(engine);
			case COMBUSTION -> EngineTuning.SOUND_COMBUSTION_LOOP_VOLUME
				* EngineTuning.combustionLoopBlend(eventRateHz);
		};
	}

	/**
	 * The mechanical bed's level, which depends on what else the engine is doing.
	 *
	 * <p>Loudest while the engine is being turned over and not firing, because then
	 * it is the entire sound of the machine. Quietest under a running engine, where
	 * it is a bed and the combustion pulses are the voice - a mechanical layer that
	 * competed with them would turn the firing rhythm to mush. In between while
	 * coasting: nothing is masking it any more, and nothing is driving it either.
	 *
	 * <p>Faded out over the last few RPM so an engine crawling to a halt trails off
	 * instead of cutting out at the audible-speed threshold.
	 */
	private static float mechanicalVolume(EngineState engine) {
		float rpm = engine.getMechanicalRpm();
		if (Math.abs(rpm) < EngineTuning.SOUND_MIN_AUDIBLE_RPM)
			return 0.0F;
		float base = switch (engine.getPhase()) {
			case RUNNING -> EngineTuning.SOUND_MECHANICAL_RUNNING_VOLUME;
			case COASTING -> EngineTuning.SOUND_MECHANICAL_COASTING_VOLUME;
			case CRANKING, STARTING -> EngineTuning.SOUND_MECHANICAL_CRANKING_VOLUME;
			case STOPPED -> 0.0F;
		};
		return base * fadeNearRest(rpm);
	}

	/** Eases the loop in and out over the last few RPM of rotation. */
	private static float fadeNearRest(float mechanicalRpm) {
		float rpm = Math.abs(mechanicalRpm);
		return rpm >= EngineTuning.STALL_RPM ? 1.0F : rpm / EngineTuning.STALL_RPM;
	}

	/**
	 * Playback pitch for this layer.
	 *
	 * <p>The mechanical layer is pitched by speed, because it is a rotating object
	 * and its sound genuinely is a function of how fast it rotates. The combustion
	 * layer is pitched by the firing <i>rate</i> - never by RPM - so that even in
	 * the aggregate regime the layer follows combustion rather than rotation.
	 *
	 * <p>{@code gameTime} only feeds the roughness a dry engine gets, which is a
	 * pure function of it so that it cannot desynchronise between players.
	 */
	public float pitchFor(EngineState engine, float eventRateHz, long gameTime) {
		float pitch = switch (this) {
			case MECHANICAL -> EngineTuning.mechanicalLayerPitch(engine.getMechanicalRpm());
			case COMBUSTION -> EngineTuning.mechanicalLayerPitch(
				eventRateHz / EngineTuning.SOUND_COMBUSTION_PULSE_MAX_RATE_HZ * EngineTuning.SOUND_REFERENCE_RPM);
		};
		return roughen(pitch, engine.getLubrication(), gameTime);
	}

	/**
	 * Gives a dry engine a slight unevenness.
	 *
	 * <p>Optional flavour, kept as a pure function of game time so it adds no state
	 * to the sound system and sounds the same for every player. The HUD is the real
	 * lubrication warning; this only makes a dry engine sound like one.
	 */
	private static float roughen(float pitch, LubricationState lubrication, long gameTime) {
		if (lubrication != LubricationState.DRY)
			return pitch;
		float wobble = (float) Math.sin(gameTime * EngineTuning.SOUND_DRY_ROUGHNESS_RATE);
		return pitch * (1.0F + EngineTuning.SOUND_DRY_ROUGHNESS * wobble);
	}
}
