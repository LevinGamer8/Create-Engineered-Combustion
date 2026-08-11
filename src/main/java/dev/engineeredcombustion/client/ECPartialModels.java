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
 *
 * <h2>Why some parts come in X and Z variants</h2>
 * A partial model is not affected by the blockstate's {@code y} rotation - that
 * only applies to the baked model. Anything whose shape depends on which way the
 * crankshaft runs therefore needs one file per axis, rather than one file plus a
 * buffer rotation composed with the animation rotation, which is easy to get
 * subtly wrong. Parts that are symmetric about the cylinder axis (the piston)
 * need only one.
 */
public class ECPartialModels {

	/** The moving piston inside a cylinder. */
	public static final PartialModel PISTON = block("piston_head");

	/**
	 * The connecting rod, drawn by the cylinder because the rod is part of the
	 * Piston Assembly. Authored hanging straight down from the wrist pin at the
	 * middle of the block, which is exactly the point the renderer pivots about.
	 */
	public static final PartialModel CONNECTING_ROD_X = block("connecting_rod_x");
	public static final PartialModel CONNECTING_ROD_Z = block("connecting_rod_z");

	/**
	 * The Spark Plug, drawn only when one is installed in the cylinder head.
	 *
	 * <p>It used to be baked into the Cylinder's own model, which meant every
	 * cylinder had a plug in it whether or not the player had ever made one.
	 * Authored directly in Cylinder block space, exactly where the head's boss
	 * leaves the hole for it, so it needs no transform at all - the same
	 * arrangement as the Air Filter on the Carburetor.
	 */
	public static final PartialModel SPARK_PLUG = block("spark_plug");

	/**
	 * Main journals, crank webs, counterweights and the offset crank pin.
	 * Authored at crank angle 0 - pin at bottom dead centre.
	 */
	public static final PartialModel CRANK_ASSEMBLY_X = block("crank_assembly_x");
	public static final PartialModel CRANK_ASSEMBLY_Z = block("crank_assembly_z");

	/**
	 * The spinning flywheel: rim, spokes, hub <i>and</i> the shaft through the
	 * block. The shaft turns with everything else rather than being left in the
	 * baked model, so no part of the output side is visibly stationary.
	 */
	public static final PartialModel FLYWHEEL_WHEEL_X = block("flywheel_wheel_x");
	public static final PartialModel FLYWHEEL_WHEEL_Z = block("flywheel_wheel_z");

	/**
	 * The burn inside the combustion chamber: a thin disc filling the top of the
	 * bore, drawn translucent and at full brightness for a few ticks. A partial
	 * model rather than a particle so its lifetime is exactly the simulation's and
	 * its cost does not grow with engine speed.
	 */
	public static final PartialModel COMBUSTION_FLASH = block("combustion_flash");

	/**
	 * The carburetor's throttle lever. Authored with its pivot on the block
	 * centre so {@code rotateCentered} turns it about the throttle shaft; the
	 * renderer then translates it onto the shaft's real position.
	 */
	public static final PartialModel THROTTLE_LEVER = block("throttle_lever");

	/**
	 * The air cleaner, drawn only when one is installed. Authored directly in
	 * Carburetor block space, so it needs no transform at all.
	 */
	public static final PartialModel AIR_FILTER = block("air_filter");

	private static PartialModel block(String path) {
		return PartialModel.of(EngineeredCombustion.asResource("block/" + path));
	}

	/** Forces static initialisation of this class. */
	public static void init() {
	}
}
