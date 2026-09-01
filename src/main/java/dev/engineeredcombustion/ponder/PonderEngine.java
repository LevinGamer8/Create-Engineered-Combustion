package dev.engineeredcombustion.ponder;

import dev.engineeredcombustion.content.engine.EngineComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.phys.Vec3;

/**
 * Where every part of one scene's engine is.
 *
 * <p>A Ponder scene teaches by <b>pointing at things</b>, and a scene that points
 * at the wrong thing teaches the wrong thing. The first in-game test of these
 * scenes found exactly that: the line about the Air Filter drew a box around most
 * of the engine, and the line about two Flywheels drew one box spanning both of
 * them <i>and the crankshaft in between</i> - so the reader was told "this is the
 * Air Filter" while being shown a machine.
 *
 * <p>The cause was hand-written coordinates. Every scene carried its own
 * {@code new BlockPos(3, 4, 2)} constants, so "the Carburetor" was a number a
 * person had worked out once, and a highlight could be off by a block without
 * anything noticing - a schematic is a binary file and a wrong outline still
 * compiles.
 *
 * <p>So the coordinates live here instead, derived from the layout
 * {@link EngineComponents} enforces rather than restated. A scene says
 * {@code ENGINE.carburetor()} and gets the Carburetor, and if the engine in a
 * scene's schematic ever moves, every highlight in that scene moves with it.
 * {@code tools/validate_ux.py} then checks the other half - that the block each
 * of these names really is at that position in that scene's structure file - so
 * a highlight pointing at empty air fails the build rather than the play-test.
 *
 * <h2>Points, not just blocks</h2>
 * Some of what these scenes teach is smaller than a block. A Spark Plug is a
 * part of the Cylinder, an Air Filter is a part of the Carburetor, and the
 * ignition switch is a part of the crankcase, so an outline of the block that
 * holds one is necessary but not sufficient. The {@code Vec3} accessors below are
 * where each of those parts actually is on the model, so a scene can outline the
 * block <i>and</i> point at the part.
 *
 * @param controller the crankshaft section at the negative end of the run, which
 *                   is also the section {@code EngineComponents} makes the
 *                   engine's controller
 * @param axis       which way the run goes. Every scene builds along X, but the
 *                   arithmetic is written from the axis rather than assuming it,
 *                   for the same reason {@code EngineComponents} is
 * @param sections   how many crankshaft sections, i.e. which inline layout
 * @param accessory  which section carries the single Carburetor and Oil Sump.
 *                   Not always the first: the inline scene grows an engine from
 *                   the far end towards the camera, so its accessories are on the
 *                   section it starts from
 */
public record PonderEngine(BlockPos controller, Axis axis, int sections, int accessory) {

	/** An engine whose Carburetor and Oil Sump are on its controller. */
	public static PonderEngine of(int x, int y, int z, int sections) {
		return new PonderEngine(new BlockPos(x, y, z), Axis.X, sections, 0);
	}

	/** The same, with the accessories on the far end instead. */
	public static PonderEngine endLoaded(int x, int y, int z, int sections) {
		return new PonderEngine(new BlockPos(x, y, z), Axis.X, sections, sections - 1);
	}

	private BlockPos along(int steps) {
		return controller.relative(Direction.get(AxisDirection.POSITIVE, axis), steps);
	}

	// --- the blocks ---------------------------------------------------------

	/** Section {@code index} of the crankshaft, counting from the controller. */
	public BlockPos crankshaft(int index) {
		return along(index);
	}

	/** The Cylinder on section {@code index}. */
	public BlockPos cylinder(int index) {
		return EngineComponents.cylinderPos(crankshaft(index));
	}

	/**
	 * Where a Carburetor would go on section {@code index} - two above it.
	 *
	 * <p>Only one section of an engine carries one; this names the <i>seat</i>
	 * rather than the block, so the inline scene can reveal a whole section's
	 * column at once without having to know which section is the one with
	 * something in it.
	 */
	public BlockPos carburetorSeat(int index) {
		return EngineComponents.carburetorPos(crankshaft(index));
	}

	/** Where an Oil Sump would hang under section {@code index}. */
	public BlockPos oilSumpSeat(int index) {
		return EngineComponents.oilSumpPos(crankshaft(index));
	}

	/** The one Carburetor. */
	public BlockPos carburetor() {
		return carburetorSeat(accessory);
	}

	/** The one Oil Sump. */
	public BlockPos oilSump() {
		return oilSumpSeat(accessory);
	}

	/** The one Flywheel, beyond the positive end of the run. */
	public BlockPos flywheel() {
		return along(sections);
	}

	/**
	 * The position beyond the <i>negative</i> end, where a second Flywheel would
	 * go - which the assembly scene uses to show that either end is valid, and
	 * then that both at once is not an engine.
	 */
	public BlockPos farFlywheel() {
		return controller.relative(Direction.get(AxisDirection.NEGATIVE, axis));
	}

	/** The last crankshaft section, i.e. the far end of the run. */
	public BlockPos lastCrankshaft() {
		return crankshaft(sections - 1);
	}

	/** The last Cylinder. */
	public BlockPos lastCylinder() {
		return cylinder(sections - 1);
	}

	// --- the parts ----------------------------------------------------------
	//
	// Model coordinates, in sixteenths, taken from tools/generate_engine_models.py
	// and turned into a point in the scene. Each names the part a sentence is
	// about rather than the middle of the block that holds it.

	/**
	 * The Spark Plug on a cylinder: its insulator and terminal, standing above the
	 * head where a player can see them. Well above the block, because that is
	 * where the plug is - see {@code spark_plug_elements}.
	 */
	public Vec3 sparkPlug(int index) {
		return on(cylinder(index), 11.9, 19.4, 8.0);
	}

	/** The middle of a bore, where the piston runs. */
	public Vec3 bore(int index) {
		return on(cylinder(index), 8.0, 7.0, 8.0);
	}

	/** The Air Filter, clamped on the Carburetor's air horn. */
	public Vec3 airFilter() {
		return on(carburetor(), 8.0, 12.8, 2.65);
	}

	/** The throttle lever, on the outer end of the throttle shaft. */
	public Vec3 throttle() {
		return on(carburetor(), 12.0, 5.6, 2.6);
	}

	/** The float bowl's sight window, where the fuel level is visible. */
	public Vec3 floatBowl() {
		return on(carburetor(), 8.0, 3.1, 7.6);
	}

	/** The Oil Sump's dipstick, up the flank of the pan. */
	public Vec3 dipstick() {
		return on(oilSump(), 7.4, 15.0, 15.7);
	}

	/**
	 * The ignition switch on the crankcase - on the controller, which is the only
	 * section that carries one however long the engine is.
	 */
	public Vec3 ignition() {
		return on(controller, 11.7, 3.6, 0.5);
	}

	/** The middle of a block, as a scene-space point. */
	public static Vec3 centre(BlockPos pos) {
		return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
	}

	private static Vec3 on(BlockPos pos, double x, double y, double z) {
		return new Vec3(pos.getX() + x / 16.0D, pos.getY() + y / 16.0D, pos.getZ() + z / 16.0D);
	}
}
