package dev.engineeredcombustion.content.engine;

import java.util.List;

/**
 * <b>Experimental, first recorded-R4 audio pass.</b> When an inline-4 is
 * genuinely running, its voice comes from real recordings of a running
 * four-cylinder petrol engine instead of from the synthetic layers.
 *
 * <h2>Why only the inline-4</h2>
 * The recordings are of a real four-cylinder engine (a VW Golf 1 Cabrio), and an
 * inline-4 is the only layout they honestly describe. R1 to R3 keep the
 * event-driven sound - one synthetic pulse per real combustion over the
 * mechanical bed - which also makes the two approaches directly comparable in
 * game.
 *
 * <h2>Why only while running</h2>
 * A recording of a running engine is the sound of fuel burning. Cranking,
 * starting, coasting, and being spun by the network without firing are all
 * honestly the mechanical layer, and must stay that way: a dead engine being
 * motored must never sound like a running car. {@link #isActive} demands the
 * RUNNING phase <i>and</i> {@link EngineState#isActivelyGenerating()}, the one
 * authoritative "combustion is genuinely happening" predicate.
 *
 * <h2>What it replaces, and what it does not</h2>
 * While active, the recording already contains the combustion, the valvetrain
 * and the block's mechanical texture, so the synthetic combustion pulses, the
 * aggregate combustion loop and the mechanical bed are all silenced - otherwise
 * every recorded firing would get a synthetic bang on top. Only <i>audio</i> is
 * affected. The simulation, its combustion events, fuel, wear, particles and
 * flashes never read anything in this class.
 *
 * <h2>Bands</h2>
 * Each recording is a {@link Band}: a loop captured at one real crankshaft speed.
 * The engine's mechanical RPM is mapped onto a recorded-RPM scale
 * ({@link #sampleRpm}), the two bands either side of that are equal-power
 * cross-faded ({@link #bandWeight}), and each band gets only a small, clamped
 * pitch correction around its own native speed ({@link #bandPitch}). The
 * cross-fade, not the pitch, carries the change. Adding the 2000, 2500 and 3000
 * RPM recordings later means appending to {@link #R4_BANDS}, widening the
 * mapping and adding one sound layer each - nothing here assumes two bands.
 *
 * <p>Pure: no Minecraft types, so it is covered by the simulation test source set.
 */
public final class RecordedEngineAudio {

	/** One real recording: a seamless loop of the engine held at {@code nativeRpm}. */
	public record Band(String name, float nativeRpm) {
	}

	/** The layout the recordings are of. */
	public static final int RECORDED_CYLINDER_COUNT = 4;

	/**
	 * The inline-4 recordings, ascending by native speed. The order is load-bearing:
	 * the cross-fade walks neighbouring entries.
	 */
	public static final List<Band> R4_BANDS = List.of(
		new Band("golf_r4_idle_1000", 1000.0F),
		new Band("golf_r4_1500", 1500.0F));

	/**
	 * Recorded-RPM the engine's idle maps onto. A deliberately narrow prototype map
	 * between the only two speeds recorded so far; it changes nothing about the
	 * engine's real speed.
	 */
	public static final float SAMPLE_RPM_AT_IDLE = 1000.0F;

	/** Recorded-RPM full throttle maps onto. */
	public static final float SAMPLE_RPM_AT_FULL_THROTTLE = 1500.0F;

	/**
	 * Pitch correction clamps, symmetric in octaves (1.15 and 1 / 1.15). A band is
	 * only ever nudged towards a neighbouring speed, never stretched to reach it:
	 * at the midpoint of the 1000-1500 cross-fade both recordings sit on their
	 * clamps, and beyond that they are fading out anyway.
	 */
	public static final float PITCH_MAX = 1.15F;
	public static final float PITCH_MIN = 1.0F / PITCH_MAX;

	/**
	 * Level of the recorded layers at full weight. The recordings are left at their
	 * own relative loudness - the 1500 RPM loop is genuinely about 5 dB louder than
	 * idle - so this is one master figure for both.
	 */
	public static final float VOLUME = 0.75F;

	private RecordedEngineAudio() {
	}

	/**
	 * Whether this engine plays the recorded layers instead of its synthetic voice.
	 *
	 * <p>The exact condition: four cylinders, RUNNING, and actively generating.
	 */
	public static boolean isActive(EngineState engine) {
		return isActive(engine.getCylinderCount(), engine.getPhase(), engine.isActivelyGenerating());
	}

	public static boolean isActive(int cylinderCount, EnginePhase phase, boolean activelyGenerating) {
		return cylinderCount == RECORDED_CYLINDER_COUNT && phase == EnginePhase.RUNNING && activelyGenerating;
	}

	/**
	 * Whether the synthetic voice should be heard: the one-shot per combustion
	 * event, the aggregate combustion loop and the mechanical bed. False exactly
	 * when the recording is speaking for the engine. The combustion events
	 * themselves happen either way; this only decides whether they get a sound.
	 */
	public static boolean syntheticVoiceAudible(EngineState engine) {
		return !isActive(engine);
	}

	/**
	 * Mechanical RPM to recorded RPM: idle to {@link #SAMPLE_RPM_AT_IDLE}, full
	 * throttle to {@link #SAMPLE_RPM_AT_FULL_THROTTLE}, linear in between and
	 * clamped outside. Direction is ignored.
	 */
	public static float sampleRpm(float mechanicalRpm) {
		float t = (Math.abs(mechanicalRpm) - EngineTuning.IDLE_RPM)
			/ (EngineTuning.FULL_THROTTLE_RPM - EngineTuning.IDLE_RPM);
		return SAMPLE_RPM_AT_IDLE + EngineTuning.clamp01(t) * (SAMPLE_RPM_AT_FULL_THROTTLE - SAMPLE_RPM_AT_IDLE);
	}

	/**
	 * How much of band {@code index} is heard at a recorded RPM, 0 to 1.
	 *
	 * <p>Equal-power between the two neighbouring bands: cos and sin of the
	 * position across the gap, so the squares always sum to one. The recordings are
	 * different takes and add as uncorrelated signals, so constant power is what
	 * keeps the cross-over free of a dip. Below the lowest band or above the
	 * highest, that end band plays alone.
	 */
	public static float bandWeight(List<Band> bands, int index, float sampleRpm) {
		int last = bands.size() - 1;
		if (sampleRpm <= bands.get(0).nativeRpm())
			return index == 0 ? 1.0F : 0.0F;
		if (sampleRpm >= bands.get(last).nativeRpm())
			return index == last ? 1.0F : 0.0F;

		int lower = 0;
		while (sampleRpm >= bands.get(lower + 1).nativeRpm())
			lower++;
		if (index != lower && index != lower + 1)
			return 0.0F;

		float from = bands.get(lower).nativeRpm();
		float to = bands.get(lower + 1).nativeRpm();
		double angle = (sampleRpm - from) / (to - from) * (Math.PI / 2.0D);
		return (float) (index == lower ? Math.cos(angle) : Math.sin(angle));
	}

	/** Playback pitch of band {@code index}: its native speed nudged towards the target, clamped. */
	public static float bandPitch(List<Band> bands, int index, float sampleRpm) {
		float ratio = sampleRpm / bands.get(index).nativeRpm();
		return ratio < PITCH_MIN ? PITCH_MIN : Math.min(ratio, PITCH_MAX);
	}

	/** The volume band {@code index} of {@link #R4_BANDS} should play at for this engine, or 0. */
	public static float r4Volume(EngineState engine, int index) {
		if (!isActive(engine))
			return 0.0F;
		return VOLUME * bandWeight(R4_BANDS, index, sampleRpm(engine.getMechanicalRpm()));
	}

	/** Playback pitch of band {@code index} of {@link #R4_BANDS} for this engine, before any roughness. */
	public static float r4Pitch(EngineState engine, int index) {
		return bandPitch(R4_BANDS, index, sampleRpm(engine.getMechanicalRpm()));
	}
}
