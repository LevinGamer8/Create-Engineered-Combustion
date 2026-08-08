package dev.engineeredcombustion.client.sound;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import dev.engineeredcombustion.content.engine.EnginePhase;
import dev.engineeredcombustion.content.engine.LubricationState;
import dev.engineeredcombustion.content.engine.EngineTuning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Owns every engine's continuous sound, client side.
 *
 * <h2>Why the loops live here and not on the block entity</h2>
 * A block entity is not a stable identity. Chunk reloads, resyncs and block
 * updates all replace the object while the engine in the world is unchanged, so
 * a loop owned by the block entity would be duplicated every time that happened
 * - the old object would keep its sound and the new one would start another.
 * Keyed by position instead, an engine can only ever hold one loop, which is
 * what makes the reload cases behave.
 *
 * <p>Client-only by construction: nothing on the common side references this
 * class, so a dedicated server never loads it or the Minecraft client types it
 * touches.
 */
public class EngineSoundManager {

	/**
	 * Long enough for {@code SoundEngine} to tick a newly played instance at least
	 * once under any reasonable frame rate.
	 */
	private static final int ACCEPTANCE_GRACE_TICKS = 5;

	private static final Map<BlockPos, EngineLoopSound> ACTIVE = new HashMap<>();

	/**
	 * The level the entries belong to. Weak, because a level the player has left
	 * must be collectable; the map itself must not be what keeps it alive.
	 */
	private static WeakReference<ClientLevel> boundLevel = new WeakReference<>(null);

	private EngineSoundManager() {
	}

	/**
	 * Brings one engine's continuous sound in line with its current state. Call
	 * once per client tick, from the engine that owns the position.
	 *
	 * <p>Not calling it is the supported way to stop the sound: the loop is on a
	 * keep-alive timer and retires on its own once the refreshes stop.
	 */
	public static void tick(ClientLevel level, BlockPos pos, EnginePhase phase, float mechanicalRpm,
		LubricationState lubrication) {
		rebindIfLevelChanged(level);

		EngineLoopKind wanted = EngineLoopKind.forState(phase, mechanicalRpm);
		EngineLoopSound current = ACTIVE.get(pos);

		if (current != null) {
			current.age();
			boolean usable = !current.isStopped() && current.wasAccepted(ACCEPTANCE_GRACE_TICKS);
			if (!usable) {
				ACTIVE.remove(pos);
				current = null;
			} else if (current.getKind() != wanted) {
				// Switching loops, e.g. cranking to running. Let the old one fade out
				// on its own timer instead of cutting it, and drop our reference so the
				// new loop can take the slot immediately.
				current.fadeOut();
				ACTIVE.remove(pos);
				current = null;
			}
		}

		if (wanted == EngineLoopKind.NONE) {
			if (current != null) {
				current.fadeOut();
				ACTIVE.remove(pos);
			}
			return;
		}

		float pitch = roughen(wanted.pitchFor(mechanicalRpm), lubrication, level.getGameTime());
		if (current == null) {
			current = new EngineLoopSound(wanted, pos, pitch);
			ACTIVE.put(pos.immutable(), current);
			Minecraft.getInstance()
				.getSoundManager()
				.play(current);
			return;
		}

		current.keepAlive();
		current.setPitch(pitch);
		current.setVolumeFactor(volumeFactor(mechanicalRpm));
	}

	/**
	 * Gives a dry engine a slight unevenness.
	 *
	 * <p>Optional flavour, kept as a pure function of game time so it adds no
	 * state to the sound system and sounds the same for every player. The HUD is
	 * the real lubrication warning; this only makes a dry engine sound like one.
	 */
	private static float roughen(float pitch, LubricationState lubrication, long gameTime) {
		if (lubrication != LubricationState.DRY)
			return pitch;
		float wobble = (float) Math.sin(gameTime * EngineTuning.SOUND_DRY_ROUGHNESS_RATE);
		return pitch * (1.0F + EngineTuning.SOUND_DRY_ROUGHNESS * wobble);
	}

	/**
	 * Eases the loop in over the first few RPM so an engine crawling to a halt
	 * trails off rather than cutting out at the audible-speed threshold.
	 */
	private static float volumeFactor(float mechanicalRpm) {
		float rpm = Math.abs(mechanicalRpm);
		float full = EngineTuning.STALL_RPM;
		return rpm >= full ? 1.0F : rpm / full;
	}

	private static void rebindIfLevelChanged(ClientLevel level) {
		if (boundLevel.get() == level)
			return;
		// Everything in the map belongs to a level the player has left. Those
		// instances are no longer ticked by anyone, so retire them explicitly rather
		// than leaving the entries to accumulate across dimension changes.
		for (Iterator<EngineLoopSound> it = ACTIVE.values()
			.iterator(); it.hasNext();)
			it.next()
				.fadeOut();
		ACTIVE.clear();
		boundLevel = new WeakReference<>(level);
	}
}
