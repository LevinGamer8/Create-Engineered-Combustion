package dev.engineeredcombustion.client.sound;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import dev.engineeredcombustion.content.engine.EnginePhase;
import dev.engineeredcombustion.content.engine.EngineTuning;
import dev.engineeredcombustion.content.engine.LubricationState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Guarantees that one engine has at most one continuous sound.
 *
 * <h2>Why the loops are keyed by position</h2>
 * A block entity is not a stable identity. Chunk reloads, resyncs and block
 * updates all replace the object while the engine in the world is unchanged, so
 * a loop owned by the block entity would be duplicated every time that happened
 * - the old object would keep its sound and the new one would start another.
 * Keyed by position, an engine has exactly one slot, and a replacement block
 * entity adopts the loop already in it rather than starting a second.
 *
 * <p>Once created, a loop looks after itself: {@link EngineLoopSound} re-reads
 * the engine every client tick and retires when it should. This class only
 * decides whether a loop needs to exist and hands it to the sound manager.
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
	 * Makes sure this engine has the loop its state calls for, and no more than
	 * one. Call once per client tick from the engine that owns the position.
	 */
	public static void tick(ClientLevel level, BlockPos pos, EnginePhase phase, float mechanicalRpm,
		LubricationState lubrication) {
		rebindIfLevelChanged(level);

		EngineLoopKind wanted = EngineLoopKind.forState(phase, mechanicalRpm);
		EngineLoopSound current = ACTIVE.get(pos);

		if (current != null && (current.isStopped() || !current.wasAccepted(ACCEPTANCE_GRACE_TICKS))) {
			// Finished, or Minecraft never took it (a muted category, say). Either way
			// the slot is free, and a later unmute can start a fresh one.
			ACTIVE.remove(pos);
			current = null;
		}

		if (current != null && current.getKind() != wanted) {
			// Switching loops, e.g. cranking to running. The old instance is already
			// retiring itself - it saw the same state change - so just release the
			// slot and let the two crossfade.
			current.fadeOut();
			ACTIVE.remove(pos);
			current = null;
		}

		if (wanted == EngineLoopKind.NONE) {
			if (current != null) {
				current.fadeOut();
				ACTIVE.remove(pos);
			}
			return;
		}

		if (current == null) {
			EngineLoopSound started = new EngineLoopSound(wanted, level, pos,
				pitchFor(wanted, mechanicalRpm, lubrication, level.getGameTime()));
			ACTIVE.put(pos.immutable(), started);
			Minecraft.getInstance()
				.getSoundManager()
				.play(started);
			return;
		}

		// The loop reads the engine itself; this only says "the engine is still here".
		current.keepAlive();
	}

	/**
	 * Speed to pitch, plus the roughness a dry engine gets. One entry point, so the
	 * loop and the manager can never compute a different pitch for the same engine.
	 */
	static float pitchFor(EngineLoopKind kind, float mechanicalRpm, LubricationState lubrication, long gameTime) {
		return roughen(kind.pitchFor(mechanicalRpm), lubrication, gameTime);
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

	/**
	 * Eases the loop in over the first few RPM so an engine crawling to a halt
	 * trails off rather than cutting out at the audible-speed threshold.
	 */
	static float volumeFactor(float mechanicalRpm) {
		float rpm = Math.abs(mechanicalRpm);
		float full = EngineTuning.STALL_RPM;
		return rpm >= full ? 1.0F : rpm / full;
	}

	private static void rebindIfLevelChanged(ClientLevel level) {
		if (boundLevel.get() == level)
			return;
		// Everything in the map belongs to a level the player has left. Those
		// instances retire themselves once they notice, but drop the entries now so
		// they cannot accumulate across dimension changes.
		for (EngineLoopSound sound : ACTIVE.values())
			sound.fadeOut();
		ACTIVE.clear();
		boundLevel = new WeakReference<>(level);
	}
}
