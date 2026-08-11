package dev.engineeredcombustion.client.sound;

import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * One continuous engine sound layer, anchored at one engine.
 *
 * <h2>It drives itself</h2>
 * Every client tick the instance looks up the crankshaft at its position and
 * decides whether it should still exist, then takes its pitch and volume from
 * that engine's live state through its {@link EngineSoundLayer}. Nothing has to
 * push updates into it, and nothing has to remember to stop it: a broken engine,
 * an unloaded chunk, a dimension change or a layer falling silent all end the
 * loop through the same check.
 *
 * <p>A keep-alive timer backs that up. If whatever owns this loop stops
 * refreshing it - the block entity stopped ticking for a reason the self-check
 * cannot see - it fades out anyway rather than playing forever. Create's steam
 * whistle uses the same timer idea.
 *
 * <h2>Never construct this silent</h2>
 * The volume starts at {@link EngineTuning#SOUND_INITIAL_VOLUME}, not zero.
 * {@code SoundEngine#play} throws away any instance whose volume is zero when it
 * is handed over, and a thrown-away instance is never ticked, so it can never
 * fade in - it is simply gone.
 *
 * <p>The instance is positional and mono ({@code relative = false}), so
 * Minecraft applies its normal distance attenuation from the engine's block.
 */
public class EngineLoopSound extends AbstractTickableSoundInstance {

	private final EngineSoundLayer layer;
	private final BlockPos pos;
	private final ClientLevel level;

	/** Volume this instance is heading towards; reached over several ticks. */
	private float targetVolume;
	private int keepAlive;
	private int age;
	private boolean ticked;

	/**
	 * Whether this instance has been told to go away.
	 *
	 * <p>Tracked separately from the target volume, because a layer's honest target
	 * can pass through zero - the mechanical bed of an engine crawling to a halt
	 * does exactly that - and an instance must not read "quiet right now" as
	 * "finished".
	 */
	private boolean retiring;

	public EngineLoopSound(EngineSoundLayer layer, ClientLevel level, BlockPos pos, float initialPitch) {
		super(layer.soundEvent(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
		this.layer = layer;
		this.level = level;
		this.pos = pos.immutable();

		looping = true;
		delay = 0;
		relative = false;
		// Audible from the very first tick, then ramped up - see the class comment.
		volume = EngineTuning.SOUND_INITIAL_VOLUME;
		targetVolume = EngineTuning.SOUND_INITIAL_VOLUME;
		pitch = initialPitch;

		// The crankcase: bearings, crankshaft and flywheel. The combustion pulses
		// deliberately come from higher up, at the cylinder head - see CombustionAudio.
		Vec3 center = Vec3.atCenterOf(pos);
		x = center.x;
		y = center.y;
		z = center.z;

		keepAlive();
	}

	/** Called every client tick by the engine that owns this loop. Final: the constructor calls it. */
	public final void keepAlive() {
		keepAlive = EngineTuning.SOUND_KEEP_ALIVE_TICKS;
	}

	/** Retire this loop gracefully; it stays audible for a few ticks while it fades. */
	public void fadeOut() {
		targetVolume = 0.0F;
		keepAlive = 0;
		retiring = true;
	}

	/**
	 * Whether Minecraft ever actually took this instance.
	 *
	 * <p>Even with a non-zero starting volume, {@code SoundEngine#play} still drops
	 * a sound whose whole category is muted, and a dropped instance is never
	 * ticked. Without this the manager would hold a reference to a sound that is
	 * not playing, believe the engine already has its layer, and stay silent even
	 * after the player turns the volume back up.
	 *
	 * @param graceTicks how long to allow for the first tick before judging
	 */
	public boolean wasAccepted(int graceTicks) {
		return ticked || age <= graceTicks;
	}

	/**
	 * Ages the instance by one tick.
	 *
	 * <p>Must be driven from outside, by whoever owns this loop - never from
	 * {@link #tick()}. {@link #wasAccepted} asks whether Minecraft ever ticked this
	 * instance, so an instance that was rejected is exactly the one that never gets
	 * a tick; aging it there would freeze its age at zero and leave it looking
	 * "still within its grace period" forever, holding a slot and keeping the
	 * engine permanently silent.
	 */
	public void age() {
		if (age < Integer.MAX_VALUE)
			age++;
	}

	@Override
	public void tick() {
		ticked = true;

		if (!retiring)
			followEngine();

		if (keepAlive > 0)
			keepAlive--;
		else
			fadeOut();

		if (volume < targetVolume)
			volume = Math.min(targetVolume, volume + EngineTuning.SOUND_FADE_PER_TICK);
		else if (volume > targetVolume)
			volume = Math.max(targetVolume, volume - EngineTuning.SOUND_FADE_PER_TICK);

		if (retiring && volume <= 0.0F)
			stop();
	}

	/**
	 * Re-reads the engine and either follows it or begins retiring.
	 *
	 * <p>The lookup is by position rather than by a held block entity reference on
	 * purpose. A chunk reload or a resync replaces the block entity object while
	 * the engine in the world is unchanged; holding the old object would strand
	 * this loop, and holding it across an unload would keep a dead block entity
	 * alive. Asking the world each tick has neither problem.
	 */
	private void followEngine() {
		if (Minecraft.getInstance().level != level) {
			fadeOut();
			return;
		}
		if (!level.isLoaded(pos)) {
			fadeOut();
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof CrankshaftBlockEntity crankshaft) || crankshaft.isRemoved()) {
			fadeOut();
			return;
		}

		float eventRateHz = crankshaft.getCombustionEventRateHz();
		targetVolume = layer.volumeFor(crankshaft.getEngineState(), eventRateHz);
		if (targetVolume <= 0.0F) {
			// This layer has nothing left to say - the engine stopped turning, or its
			// firing rate dropped back below the aggregation threshold.
			fadeOut();
			return;
		}
		pitch = layer.pitchFor(crankshaft.getEngineState(), eventRateHz, level.getGameTime());
	}
}
