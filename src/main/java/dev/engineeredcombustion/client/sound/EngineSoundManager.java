package dev.engineeredcombustion.client.sound;

import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import dev.engineeredcombustion.content.engine.EngineState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Guarantees that one engine has at most one instance of each sound layer.
 *
 * <h2>Why the loops are keyed by position</h2>
 * A block entity is not a stable identity. Chunk reloads, resyncs and block
 * updates all replace the object while the engine in the world is unchanged, so
 * a loop owned by the block entity would be duplicated every time that happened
 * - the old object would keep its sound and the new one would start another.
 * Keyed by position, an engine has exactly one slot per layer, and a replacement
 * block entity adopts the loops already in them rather than starting more.
 *
 * <h2>Layers, not modes</h2>
 * An engine can be making several continuous sounds at once - a mechanical bed
 * with, in a hypothetical fast engine, an aggregate combustion layer over it - so
 * each {@link EngineSoundLayer} gets its own slot and its own lifetime. That is
 * the difference from the arrangement this replaced, where an engine had exactly
 * one loop and starting or stopping combustion swapped the entire sound of the
 * machine for a different recording. Layers cross-fade independently: combustion
 * can stop while the mechanical layer carries on unchanged, which is precisely
 * what a player needs to hear when an engine runs out of fuel but keeps spinning.
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

	private static final Map<BlockPos, EnumMap<EngineSoundLayer, EngineLoopSound>> ACTIVE = new HashMap<>();

	/**
	 * The level the entries belong to. Weak, because a level the player has left
	 * must be collectable; the map itself must not be what keeps it alive.
	 */
	private static WeakReference<ClientLevel> boundLevel = new WeakReference<>(null);

	private EngineSoundManager() {
	}

	/**
	 * Makes sure this engine has the layers its state calls for, and no more than
	 * one of each. Call once per client tick from the engine that owns the position.
	 */
	public static void tick(ClientLevel level, BlockPos pos, EngineState engine, float eventRateHz) {
		rebindIfLevelChanged(level);

		EnumMap<EngineSoundLayer, EngineLoopSound> layers = ACTIVE.get(pos);
		for (EngineSoundLayer layer : EngineSoundLayer.values())
			layers = tickLayer(level, pos, layers, layer, engine, eventRateHz);

		if (layers != null && layers.isEmpty())
			ACTIVE.remove(pos);
	}

	/**
	 * Brings one layer of one engine up to date, and returns the engine's layer map
	 * - created here if this is the first layer that turned out to need one, so an
	 * engine that is making no sound at all costs no allocation.
	 */
	private static EnumMap<EngineSoundLayer, EngineLoopSound> tickLayer(ClientLevel level, BlockPos pos,
		EnumMap<EngineSoundLayer, EngineLoopSound> layers, EngineSoundLayer layer, EngineState engine,
		float eventRateHz) {
		EngineLoopSound current = layers == null ? null : layers.get(layer);

		// Aged from here, not from the instance's own tick: an instance Minecraft
		// rejected never ticks, so it could never age out of its grace period and
		// would hold this layer's slot forever.
		if (current != null) {
			current.age();
			if (current.isStopped() || !current.wasAccepted(ACCEPTANCE_GRACE_TICKS)) {
				// Finished, or Minecraft never took it (a muted category, say). Either
				// way the slot is free, and a later unmute can start a fresh one.
				layers.remove(layer);
				current = null;
			}
		}

		boolean wanted = layer.volumeFor(engine, eventRateHz) > 0.0F;

		if (current != null) {
			if (!wanted) {
				// Releasing the slot as well as fading means a fresh instance can start
				// the moment this layer is wanted again, without waiting for the fade
				// of the old one to finish - so the two overlap rather than gapping.
				current.fadeOut();
				layers.remove(layer);
			} else
				// The loop reads the engine itself; this only says "still here".
				current.keepAlive();
			return layers;
		}

		if (!wanted)
			return layers;

		EngineLoopSound started = new EngineLoopSound(layer, level, pos,
			layer.pitchFor(engine, eventRateHz, level.getGameTime()));
		if (layers == null) {
			layers = new EnumMap<>(EngineSoundLayer.class);
			ACTIVE.put(pos.immutable(), layers);
		}
		layers.put(layer, started);
		Minecraft.getInstance()
			.getSoundManager()
			.play(started);
		return layers;
	}

	private static void rebindIfLevelChanged(ClientLevel level) {
		if (boundLevel.get() == level)
			return;
		// Everything in the map belongs to a level the player has left. Those
		// instances retire themselves once they notice, but drop the entries now so
		// they cannot accumulate across dimension changes.
		for (EnumMap<EngineSoundLayer, EngineLoopSound> layers : ACTIVE.values())
			for (EngineLoopSound sound : layers.values())
				sound.fadeOut();
		ACTIVE.clear();
		boundLevel = new WeakReference<>(level);
	}
}
