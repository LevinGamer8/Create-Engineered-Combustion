package dev.engineeredcombustion.client;

import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import dev.engineeredcombustion.network.EngineTickPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Turns the server's engine event payloads into things a player can see and hear.
 *
 * <p>Reached only through {@code ECPackets}, behind a dist check, so a dedicated
 * server never loads this class - and therefore never loads the client-side effect
 * code it leads to.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientEngineEvents {

	private ClientEngineEvents() {
	}

	/**
	 * Plays out one engine's sparks and combustions for one server tick.
	 *
	 * <p>Everything is looked up rather than assumed, because a packet can always
	 * outlive what it describes: the player may have walked away, the chunk may have
	 * gone, or the engine may have been mined in the tick it took the packet to
	 * arrive. Each of those simply ends in nothing happening.
	 *
	 * <p><b>No client-side prediction, and no replay.</b> The client never decides
	 * that a cylinder fired - it is told - and it is told only about the tick that
	 * just happened. A chunk coming into view carries no events with it, which is
	 * what stops a player who arrives at a running engine from being greeted by a
	 * burst of bangs that happened while they were elsewhere.
	 *
	 * <p>The phase anchor is applied whether or not anything fired, and before the
	 * events are played: a packet sent with both masks empty is an anchor for a
	 * motored engine, which is the one case that has no bangs to anchor on.
	 */
	public static void onEngineTick(EngineTickPayload payload, IPayloadContext context) {
		Player player = context.player();
		if (player == null)
			return;
		Level level = player.level();
		if (level == null || !level.isLoaded(payload.controllerPos()))
			return;
		if (!(level.getBlockEntity(payload.controllerPos()) instanceof CrankshaftBlockEntity crankshaft))
			return;
		crankshaft.getEngineState()
			.correctCyclePhase(payload.cycleAngleDegrees(), payload.armedMask());
		if (payload.hasEvents())
			crankshaft.playCombustionEvents(payload.sparkMask(), payload.combustionMask());
	}
}
