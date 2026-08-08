package dev.engineeredcombustion.client.sound;

import dev.engineeredcombustion.content.engine.EngineTuning;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * One continuous engine sound, anchored at one engine.
 *
 * <h2>Keep-alive rather than explicit stopping</h2>
 * The instance fades out and retires unless something refreshes it every tick.
 * {@code CrankshaftBlockEntity} does that from its client tick, so every way an
 * engine can stop existing - broken, chunk unloaded, dimension changed, replaced
 * by a resync - stops the refresh and the sound retires by itself. No teardown
 * path has to remember to kill audio, which is what orphaned loops are usually
 * caused by. Create's steam whistle uses the same arrangement.
 *
 * <p>The instance is positional and mono ({@code relative = false}), so
 * Minecraft applies its normal distance attenuation.
 */
public class EngineLoopSound extends AbstractTickableSoundInstance {

	private final EngineLoopKind kind;
	private final BlockPos pos;

	/** Volume this instance is heading towards; reached over several ticks. */
	private float targetVolume;
	private int keepAlive;
	private int age;
	private boolean ticked;

	public EngineLoopSound(EngineLoopKind kind, BlockPos pos, float initialPitch) {
		super(kind.soundEvent(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
		this.kind = kind;
		this.pos = pos.immutable();

		looping = true;
		delay = 0;
		relative = false;
		// Start silent and ramp up, so an engine coming into view does not thump.
		volume = 0.0F;
		targetVolume = kind.baseVolume();
		pitch = initialPitch;

		Vec3 center = Vec3.atCenterOf(pos);
		x = center.x;
		y = center.y;
		z = center.z;

		keepAlive();
	}

	public EngineLoopKind getKind() {
		return kind;
	}

	public BlockPos getPos() {
		return pos;
	}

	/** Called every client tick by the engine that owns this loop. */
	public void keepAlive() {
		keepAlive = EngineTuning.SOUND_KEEP_ALIVE_TICKS;
	}

	/** Retire this loop gracefully; it stays audible for a few ticks while it fades. */
	public void fadeOut() {
		targetVolume = 0.0F;
		keepAlive = 0;
	}

	public void setPitch(float pitch) {
		this.pitch = pitch;
	}

	/** Scales the loop below its nominal level, for engines that are barely turning. */
	public void setVolumeFactor(float factor) {
		targetVolume = kind.baseVolume() * Math.max(0.0F, Math.min(1.0F, factor));
	}

	/**
	 * Whether Minecraft ever actually took this instance.
	 *
	 * <p>{@code SoundEngine#play} silently drops a sound whose category volume is
	 * zero, and a dropped instance is never ticked. Without this the manager would
	 * hold a reference to a sound that is not playing, believe the engine already
	 * has its loop, and stay silent even after the player turns the volume back up.
	 *
	 * @param graceTicks how long to allow for the first tick before judging
	 */
	public boolean wasAccepted(int graceTicks) {
		return ticked || age <= graceTicks;
	}

	/** Ages the instance even on ticks Minecraft does not deliver, so the grace period is real time. */
	public void age() {
		if (age < Integer.MAX_VALUE)
			age++;
	}

	@Override
	public void tick() {
		ticked = true;
		if (keepAlive > 0)
			keepAlive--;
		else
			targetVolume = 0.0F;

		if (volume < targetVolume)
			volume = Math.min(targetVolume, volume + EngineTuning.SOUND_FADE_PER_TICK);
		else if (volume > targetVolume)
			volume = Math.max(targetVolume, volume - EngineTuning.SOUND_FADE_PER_TICK);

		if (volume <= 0.0F && targetVolume <= 0.0F)
			stop();
	}
}
