package dev.engineeredcombustion.client;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

import dev.engineeredcombustion.EngineeredCombustion;

/**
 * Models that are rendered by a block entity renderer instead of being baked
 * into the chunk mesh, i.e. the parts that move.
 *
 * <p>Uses Flywheel's {@code PartialModel}, the same mechanism Create itself
 * uses. The instances must exist before models are loaded, which is why
 * {@link #init()} is called from the client mod constructor.
 */
public class ECPartialModels {

	/** The moving piston inside a cylinder. */
	public static final PartialModel PISTON_HEAD = block("piston_head");

	/**
	 * The rotating crank throw - counterweights and crank pin - inside the open
	 * crankcase. Two variants for the two crankshaft axes, as with the flywheel.
	 */
	public static final PartialModel CRANK_THROW_X = block("crank_throw_x");
	public static final PartialModel CRANK_THROW_Z = block("crank_throw_z");

	/**
	 * The spinning flywheel disc. Two variants instead of one plus a 90 degree
	 * buffer rotation: composing two rotations on a SuperByteBuffer is easy to get
	 * subtly wrong, and two tiny model files are not worth the risk.
	 */
	public static final PartialModel FLYWHEEL_WHEEL_X = block("flywheel_wheel_x");
	public static final PartialModel FLYWHEEL_WHEEL_Z = block("flywheel_wheel_z");

	private static PartialModel block(String path) {
		return PartialModel.of(EngineeredCombustion.asResource("block/" + path));
	}

	/** Forces static initialisation of this class. */
	public static void init() {
	}
}
