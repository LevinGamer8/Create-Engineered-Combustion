package dev.engineeredcombustion.network;

import io.netty.buffer.ByteBuf;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.content.engine.EngineTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One engine's combustion events for one server tick, as two bitmasks.
 *
 * <h2>Why this exists</h2>
 * The spark, the chamber flash and the firing bang are all triggered on the client
 * by the server's per-cylinder event counters moving, and those counters travel in
 * the block entity's data. So every single spark and every single combustion used
 * to force a <b>full block entity synchronisation</b> - the crank angle, the phase,
 * the speed, the fuel flag, the lubrication state, four spark counters and four
 * combustion counters - for what is, in the end, eight bits of news.
 *
 * <p>That scaled badly in exactly the direction the mod was going. A single-cylinder
 * engine fires 3.2 times a second at full throttle; an inline-4 fires four times
 * per revolution, so it produced four times the events and, worse, they land on
 * different ticks, so they could not even be coalesced. This payload replaces that
 * traffic with <b>at most one small packet per engine per tick</b>.
 *
 * <h2>Why a bitmask is lossless here</h2>
 * The simulation asks each cylinder exactly once per tick whether it has just
 * crossed its firing angle ({@code EngineState#crossedFiringAngle} is a boolean
 * test evaluated once per cylinder per tick), so a cylinder can register <b>at most
 * one</b> spark and at most one combustion in any single tick. One bit per cylinder
 * is therefore not an approximation - it is exactly as much information as the
 * server produced, and no event can be lost to aliasing however fast the engine
 * turns.
 *
 * <p>That also holds physically, which is worth stating because it is the thing a
 * future speed increase would have to re-check. A cylinder fires once per
 * revolution, so two firings within one tick would need more than 720 degrees of
 * crank rotation per tick - 2400 RPM. The engine's own ceiling is
 * {@link EngineTuning#MAX_RPM} (208 RPM, about 62 degrees per tick) and even an
 * external Create network at the default {@code maxRotationSpeed} of 256 RPM turns
 * the crank only about 77 degrees per tick. There is an order of magnitude of
 * headroom before the question becomes interesting.
 *
 * <h2>World context</h2>
 * Carries no dimension, and does not need one. The packet is delivered only to
 * players who are tracking the controller's chunk <i>in the level it was sent
 * from</i> - see {@code PacketDistributor#sendToPlayersTrackingChunk} - so a client
 * that receives it is by construction in that dimension, at that position. The
 * handler still resolves the block entity at {@link #controllerPos()} and checks
 * its type before acting, so a packet that arrives after the player has left simply
 * does nothing.
 *
 * @param controllerPos  the crankshaft section that runs this engine. Cylinder
 *                       {@code i} is {@code i} sections along the crank axis from
 *                       it, which is how one position addresses four bores
 * @param sparkMask      bit {@code i} set when cylinder {@code i}'s coil fired
 * @param combustionMask bit {@code i} set when cylinder {@code i} burned a charge
 *                       that was actually paid for
 */
public record EngineCombustionEventsPayload(BlockPos controllerPos, byte sparkMask, byte combustionMask)
	implements CustomPacketPayload {

	public static final Type<EngineCombustionEventsPayload> TYPE =
		new Type<>(EngineeredCombustion.asResource("engine_combustion_events"));

	/**
	 * Eleven bytes on the wire: the packed position and the two masks.
	 *
	 * <p>A byte per mask rather than a var-int, because
	 * {@link EngineTuning#MAX_CYLINDERS} is 4 and a byte covers eight. Raising the
	 * cylinder limit past eight is the one change that would have to widen this.
	 */
	public static final StreamCodec<ByteBuf, EngineCombustionEventsPayload> STREAM_CODEC =
		StreamCodec.composite(BlockPos.STREAM_CODEC, EngineCombustionEventsPayload::controllerPos,
			ByteBufCodecs.BYTE, EngineCombustionEventsPayload::sparkMask,
			ByteBufCodecs.BYTE, EngineCombustionEventsPayload::combustionMask,
			EngineCombustionEventsPayload::new);

	/** Whether this payload carries anything at all. Nothing empty is ever sent. */
	public boolean isEmpty() {
		return sparkMask == 0 && combustionMask == 0;
	}

	@Override
	public Type<EngineCombustionEventsPayload> type() {
		return TYPE;
	}
}
