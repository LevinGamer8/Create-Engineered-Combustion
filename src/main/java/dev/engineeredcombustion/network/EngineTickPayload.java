package dev.engineeredcombustion.network;

import io.netty.buffer.ByteBuf;

import dev.engineeredcombustion.EngineeredCombustion;
import dev.engineeredcombustion.content.engine.EngineTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One engine's news for one server tick: what fired, and where in its four-stroke
 * cycle the crank now is.
 *
 * <h2>Why the events are a packet at all</h2>
 * The spark, the chamber flash and the firing bang are all triggered on the client
 * by the server's per-cylinder event counters moving, and those counters travel in
 * the block entity's data. So every single spark and every single combustion used
 * to force a <b>full block entity synchronisation</b> - the crank angle, the phase,
 * the speed, the fuel flag, the lubrication state, four spark counters and four
 * combustion counters - for what is, in the end, eight bits of news.
 *
 * <p>That scaled badly in exactly the direction the mod was going. This payload
 * replaces that traffic with <b>at most one small packet per engine per tick</b>.
 *
 * <h2>Why a bitmask is lossless here</h2>
 * The simulation asks each cylinder exactly once per tick whether it has just
 * crossed its firing angle, so a cylinder can register <b>at most one</b> spark and
 * at most one combustion in any single tick. One bit per cylinder is therefore not
 * an approximation - it is exactly as much information as the server produced, and
 * no event can be lost to aliasing however fast the engine turns.
 *
 * <h2>Why the cycle angle rides along</h2>
 * <b>A client cannot derive a four-stroke phase from what it can see.</b> The
 * piston is in exactly the same place at cycle angle 137 and at 497 - the two are
 * one revolution apart - and those are opposite halves of the cycle: on one the
 * cylinder is compressing with both valves shut, on the other it is pushing exhaust
 * out with a valve wide open. Nothing about the crank distinguishes them. So a
 * client that integrated its own phase and drifted by a revolution would draw a
 * perfectly correct-looking engine whose valves were an entire stroke wrong, and
 * nothing would ever put it right.
 *
 * <p>Both sides integrate the same position from the same synchronised speed, so in
 * steady state they do not drift at all. What they cannot agree on for free is the
 * few ticks around every speed change, where the client's Create speed lags the
 * server's - and at 192 RPM one tick is nearly 60 degrees. Those accumulate.
 *
 * <p>So the server states the angle, and it is four bytes riding on a packet that
 * was being sent anyway. A running engine is therefore anchored on every bang it
 * makes - which is free, and exact. An engine being <i>motored</i> by another
 * source makes no bangs and has no such anchor, so for those the controller sends
 * this payload on its own every
 * {@link EngineTuning#PHASE_ANCHOR_INTERVAL_TICKS} ticks with both masks empty.
 *
 * <p>The <b>cycle index is deliberately not sent.</b> Nothing on the client asks
 * which cycle it is in - the firing logic that needs an event identity is
 * server-only - so it would be eight bytes a second buying nothing.
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
 * @param controllerPos    the crankshaft section that runs this engine. Cylinder
 *                         {@code i} is {@code i} sections along the crank axis from
 *                         it, which is how one position addresses four bores
 * @param sparkMask        bit {@code i} set when cylinder {@code i}'s coil fired
 * @param combustionMask   bit {@code i} set when cylinder {@code i} burned a charge
 *                         that was actually paid for
 * @param cycleAngleDegrees where the engine is in its 720-degree cycle, as of the
 *                         tick this was sent
 * @param armedMask        bit {@code i} set when cylinder {@code i} is holding an
 *                         inducted charge. One byte, and it is what lets the client
 *                         tell a compressing cylinder from a pumping one without
 *                         running the simulation
 */
public record EngineTickPayload(BlockPos controllerPos, byte sparkMask, byte combustionMask,
	float cycleAngleDegrees, byte armedMask) implements CustomPacketPayload {

	public static final Type<EngineTickPayload> TYPE =
		new Type<>(EngineeredCombustion.asResource("engine_tick"));

	/**
	 * Fifteen bytes on the wire: the packed position, the three masks and the angle.
	 *
	 * <p>A byte per mask rather than a var-int, because
	 * {@link EngineTuning#MAX_CYLINDERS} is 4 and a byte covers eight. Raising the
	 * cylinder limit past eight is the one change that would have to widen this.
	 */
	public static final StreamCodec<ByteBuf, EngineTickPayload> STREAM_CODEC =
		StreamCodec.composite(BlockPos.STREAM_CODEC, EngineTickPayload::controllerPos,
			ByteBufCodecs.BYTE, EngineTickPayload::sparkMask,
			ByteBufCodecs.BYTE, EngineTickPayload::combustionMask,
			ByteBufCodecs.FLOAT, EngineTickPayload::cycleAngleDegrees,
			ByteBufCodecs.BYTE, EngineTickPayload::armedMask,
			EngineTickPayload::new);

	/** Whether any cylinder did anything this tick. A phase anchor alone is not an event. */
	public boolean hasEvents() {
		return sparkMask != 0 || combustionMask != 0;
	}

	@Override
	public Type<EngineTickPayload> type() {
		return TYPE;
	}
}
