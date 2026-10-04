package dev.engineeredcombustion.client.sound;

import dev.engineeredcombustion.content.engine.EngineState;
import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.EngineWearMath;
import dev.engineeredcombustion.content.engine.LubricationState;
import dev.engineeredcombustion.content.engine.RecordedEngineAudio;
import dev.engineeredcombustion.content.engine.WearCondition;
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
 *
 * <h2>Recorded inline-4 layers (experimental)</h2>
 * {@link #RECORDED_R4_IDLE} and {@link #RECORDED_R4_1500} are the first recorded
 * pass: real loops of a four-cylinder engine at 1000 and 1500 RPM, cross-faded by
 * speed. They play only for an inline-4 that is genuinely running - see
 * {@link RecordedEngineAudio} - and while they do, the mechanical and combustion
 * layers fall silent, because the recording already contains both. Every other
 * engine, and an inline-4 in any other phase, sounds exactly as before. A later
 * recording is one more constant here bound to one more
 * {@link RecordedEngineAudio#R4_BANDS} entry.
 */
public enum EngineSoundLayer {

	MECHANICAL(-1),
	COMBUSTION(-1),
	RECORDED_R4_IDLE(0),
	RECORDED_R4_1500(1);

	/** Index into {@link RecordedEngineAudio#R4_BANDS}, or -1 for a synthetic layer. */
	private final int recordedBand;

	EngineSoundLayer(int recordedBand) {
		this.recordedBand = recordedBand;
	}

	public boolean isRecorded() {
		return recordedBand >= 0;
	}

	public SoundEvent soundEvent() {
		return switch (this) {
			case MECHANICAL -> ECSounds.ENGINE_MECHANICAL.get();
			case COMBUSTION -> ECSounds.ENGINE_COMBUSTION_LOOP.get();
			case RECORDED_R4_IDLE -> ECSounds.ENGINE_RECORDED_R4_IDLE.get();
			case RECORDED_R4_1500 -> ECSounds.ENGINE_RECORDED_R4_1500.get();
		};
	}

	/**
	 * Whether this layer should exist at all right now. Separate from
	 * {@link #volumeFor} because a recorded band can honestly sit at zero weight -
	 * the 1500 RPM loop at idle - while the engine it belongs to is still running.
	 * Retiring it there would restart it every time the speed ripples across the
	 * end of the cross-fade, so recorded layers live for as long as the recorded
	 * mode does and are merely faded. For the synthetic layers the two questions
	 * are the same one, exactly as before.
	 */
	public boolean isWanted(EngineState engine, float eventRateHz) {
		if (isRecorded())
			return RecordedEngineAudio.isActive(engine)
				&& Math.abs(engine.getMechanicalRpm()) >= EngineTuning.SOUND_MIN_AUDIBLE_RPM;
		return volumeFor(engine, eventRateHz) > 0.0F;
	}

	/**
	 * What this layer should be playing at, right now, for this engine.
	 *
	 * @param eventRateHz measured combustion events per second
	 * @return the volume, or 0 when this layer has nothing to say - which, for a
	 *         synthetic layer, is how it asks to be retired. A recorded layer is
	 *         retired by {@link #isWanted} instead, and may sit at 0 while wanted.
	 */
	public float volumeFor(EngineState engine, float eventRateHz) {
		if (isRecorded())
			return RecordedEngineAudio.r4Volume(engine, recordedBand);
		// The recording already contains the combustion and the mechanical texture;
		// stacking the synthetic layers under it would double both.
		if (!RecordedEngineAudio.syntheticVoiceAudible(engine))
			return 0.0F;
		return switch (this) {
			case MECHANICAL -> mechanicalVolume(engine);
			case COMBUSTION -> EngineTuning.SOUND_COMBUSTION_LOOP_VOLUME
				* EngineTuning.combustionLoopBlend(eventRateHz);
			default -> throw new IllegalStateException(name());
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
	 * <p>{@code gameTime} only feeds the roughness an unhealthy engine gets - dry,
	 * mechanically worn, or both - which is a pure function of it so that it cannot
	 * desynchronise between players.
	 */
	public float pitchFor(EngineState engine, float eventRateHz, long gameTime) {
		float pitch = switch (this) {
			case MECHANICAL -> EngineTuning.mechanicalLayerPitch(engine.getMechanicalRpm());
			case COMBUSTION -> EngineTuning.mechanicalLayerPitch(
				eventRateHz / EngineTuning.SOUND_COMBUSTION_PULSE_MAX_RATE_HZ * EngineTuning.SOUND_REFERENCE_RPM);
			case RECORDED_R4_IDLE, RECORDED_R4_1500 -> RecordedEngineAudio.r4Pitch(engine, recordedBand);
		};
		return roughen(pitch, engine, gameTime);
	}

	/**
	 * Gives an unhealthy engine a slight unevenness.
	 *
	 * <p>Two independent wobbles, because they are two different complaints and a
	 * player should be able to tell them apart:
	 * <ul>
	 * <li><b>dry</b> - a fast flutter, switched on outright by the lubrication
	 * state, because running an engine dry is an immediate fault;</li>
	 * <li><b>worn</b> - a slower, shallower chatter that fades in from
	 * {@link WearCondition#WORN} and reaches full depth at the service limit. A
	 * healthy engine is untouched by it, and even a finished one only ever gets a
	 * tired-sounding bearing rather than a different engine.</li>
	 * </ul>
	 *
	 * <p>No new sound asset, deliberately: this is the existing loop, pitched. Both
	 * terms are pure functions of game time, so they add no state to the sound
	 * system, cannot accumulate error, and sound identical for every player watching
	 * the same engine.
	 *
	 * <p>Flavour only. The goggles remain the authoritative statement of an engine's
	 * condition; this makes a tired one sound tired.
	 */
	private static float roughen(float pitch, EngineState engine, long gameTime) {
		float roughness = 0.0F;
		if (engine.getLubrication() == LubricationState.DRY)
			roughness += EngineTuning.SOUND_DRY_ROUGHNESS
				* (float) Math.sin(gameTime * EngineTuning.SOUND_DRY_ROUGHNESS_RATE);

		float wear = EngineWearMath.mechanicalRoughness(engine.getWear()
			.averageBearingWear());
		if (wear > 0.0F)
			roughness += EngineTuning.SOUND_WEAR_ROUGHNESS * wear
				* (float) Math.sin(gameTime * EngineTuning.SOUND_WEAR_ROUGHNESS_RATE);

		return roughness == 0.0F ? pitch : pitch * (1.0F + roughness);
	}
}
