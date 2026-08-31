package dev.engineeredcombustion.advancement;

import java.util.UUID;

import dev.engineeredcombustion.content.engine.EngineEventRecord;
import dev.engineeredcombustion.registry.ECCriteriaTriggers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Who was last messing with this engine, and how long ago.
 *
 * <p>Some of what the mod rewards is not a thing a player does directly. Nobody
 * "starts" an engine the way they mine a block - they turn it over with a hand
 * crank, let go, and several seconds later combustion catches on its own. The
 * advancement has to go to the person who cranked it, and by the time it fires
 * that person is standing there doing nothing.
 *
 * <h2>Why not engine ownership</h2>
 * Because an engine is not owned. It is a structure of blocks that anyone can
 * walk up to, and a permanent owner field would be wrong the first time a second
 * player touched it, wrong again on a server where the builder logged off, and
 * an extra thing to persist, synchronise and migrate forever after.
 *
 * <p>So this is deliberately small and deliberately forgetful: one UUID and one
 * expiry. Interacting with an engine in any meaningful way refreshes it, and it
 * lapses on its own shortly afterwards. It is not saved to disk - an engine
 * loading from a world nobody has touched has no recent interactor, which is the
 * correct answer.
 *
 * <h2>Where attribution is not needed</h2>
 * Events that are facts about the world rather than about a person - an engine
 * reaching a condition, a sustained abuse window elapsing - fall back to any
 * player near enough to be watching. That is a deliberate looseness: those
 * advancements are jokes about a machine's state, and awarding one to the wrong
 * bystander on a busy server costs nothing, while failing to award it to the
 * person who caused it would be the real bug.
 */
public final class EngineInteractionMemory {

	/**
	 * How long an interaction keeps pointing at its player, in ticks. Fifteen
	 * seconds.
	 *
	 * <p>Sized against the slowest thing it has to bridge: hand-cranking an engine
	 * and waiting for it to catch. A healthy engine catches in a second or two, a
	 * critically worn one can take considerably longer, and the window has to
	 * outlast the worst case or the advancement for starting a wrecked engine could
	 * never be earned by the person who earned it.
	 */
	public static final int ATTRIBUTION_WINDOW_TICKS = 20 * 15;

	/** How far away a bystander may be and still be credited for a world event. */
	public static final double NEARBY_RADIUS = 16.0D;

	private UUID interactor;
	private long expiresAtGameTime;

	/** Records that this player just did something to the engine. */
	public void remember(Player player, ServerLevel level) {
		interactor = player.getUUID();
		expiresAtGameTime = level.getGameTime() + ATTRIBUTION_WINDOW_TICKS;
	}

	/** Forgets immediately - used when an engine stops being the same engine. */
	public void forget() {
		interactor = null;
		expiresAtGameTime = 0L;
	}

	/**
	 * The player this engine's actions should be credited to, or null.
	 *
	 * <p>Checks that the remembered player is still online and still in this
	 * level, because a UUID that has logged out is not someone who can be handed an
	 * advancement.
	 */
	public ServerPlayer recentInteractor(ServerLevel level) {
		if (interactor == null)
			return null;
		if (level.getGameTime() > expiresAtGameTime) {
			forget();
			return null;
		}
		return level.getServer()
			.getPlayerList()
			.getPlayer(interactor) instanceof ServerPlayer player && player.level() == level ? player : null;
	}

	/**
	 * Fires an event at the player who caused it.
	 *
	 * <p>Does nothing at all when nobody can be credited, which is the common case
	 * on a server full of engines nobody is standing next to.
	 */
	public void fireAttributed(ServerLevel level, EngineEventRecord record) {
		ServerPlayer player = recentInteractor(level);
		if (player != null)
			ECCriteriaTriggers.ENGINE_EVENT.get()
				.fire(player, record);
	}

	/**
	 * Fires an event at whoever is around to see it.
	 *
	 * <p>Prefers the recent interactor when there is one - the person who set the
	 * situation up is the right answer whenever it is available - and falls back to
	 * everyone nearby otherwise. Only for events that are statements about the
	 * machine rather than about a person; see the class note.
	 */
	public void fireNearby(ServerLevel level, double x, double y, double z, EngineEventRecord record) {
		ServerPlayer interacting = recentInteractor(level);
		if (interacting != null) {
			ECCriteriaTriggers.ENGINE_EVENT.get()
				.fire(interacting, record);
			return;
		}
		for (ServerPlayer player : level.players())
			if (player.distanceToSqr(x, y, z) <= NEARBY_RADIUS * NEARBY_RADIUS)
				ECCriteriaTriggers.ENGINE_EVENT.get()
					.fire(player, record);
	}
}
