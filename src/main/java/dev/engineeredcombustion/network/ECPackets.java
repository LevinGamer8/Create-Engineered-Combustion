package dev.engineeredcombustion.network;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * This mod's one network channel.
 *
 * <h2>Why registration is common code and the handler is not</h2>
 * A clientbound payload has to be <i>registered</i> on both sides - the server
 * needs it in its registry to be allowed to send it - but its handler only ever
 * runs on a client. So the registration lives here, in a class a dedicated server
 * loads happily, and the work is behind a dist check that keeps
 * {@code ClientEngineEvents} - and through it {@code Minecraft} - off a dedicated
 * server's class path entirely.
 *
 * <p>The guard is what makes that true rather than merely likely. The JVM resolves
 * the reference to {@code ClientEngineEvents} when the invocation below actually
 * executes, not when this class is loaded, and on a dedicated server it never
 * executes: nothing there receives a clientbound payload.
 */
public final class ECPackets {

	private ECPackets() {
	}

	/**
	 * Bumped when a payload's wire format changes incompatibly. NeoForge refuses a
	 * connection between two installations whose versions disagree, which is a far
	 * better failure than a silently misread packet.
	 */
	private static final String VERSION = "2";

	/** Registered from the mod event bus - see {@code EngineeredCombustion}. */
	public static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(VERSION);
		registrar.playToClient(EngineTickPayload.TYPE, EngineTickPayload.STREAM_CODEC,
			ECPackets::handleEngineTick);
	}

	/**
	 * Hands one engine's per-tick news to the client, on the main thread.
	 *
	 * <p>{@code PayloadRegistrar} wraps handlers in a {@code MainThreadPayloadHandler}
	 * by default, so this already runs where particles, sounds and block entities may
	 * safely be touched. Nothing here has to schedule anything itself.
	 */
	private static void handleEngineTick(EngineTickPayload payload, IPayloadContext context) {
		if (!FMLEnvironment.dist.isClient())
			return;
		dev.engineeredcombustion.client.ClientEngineEvents.onEngineTick(payload, context);
	}
}
