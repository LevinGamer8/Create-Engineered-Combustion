package dev.engineeredcombustion.content.engine;

import org.jetbrains.annotations.Nullable;

import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlockEntity;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlockEntity;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one engine shape milestone 1 supports, plus its detection.
 *
 * <p>This is <b>not</b> a generic multiblock framework - it is three hard-coded
 * relative positions. A framework can be introduced later when there is more
 * than one layout to describe.
 *
 * <h2>Supported orientation</h2>
 * <pre>
 *             [Carburetor]       &lt;- directly above the cylinder (optional)
 *              [Cylinder]        &lt;- directly above the crankshaft
 *                  |             &lt;- connecting rod (implicit)
 *   ... [Crankshaft] [Flywheel] [Create Shaft] ...
 * </pre>
 * <ul>
 * <li>The <b>Crankshaft</b> is the controller and defines the engine's axis. Its
 * {@code axis} block state value is either {@code x} or {@code z}; the
 * crankshaft is always horizontal.</li>
 * <li>The <b>Cylinder</b> must sit at {@code crankshaft.above()}. The cylinder is
 * always vertical - the piston travels straight up and down.</li>
 * <li>The <b>Flywheel</b> must sit directly next to the crankshaft along the
 * crankshaft's own axis, and its own {@code axis} must match. Both ends are
 * accepted: the crankshaft axis is an <em>axis</em>, not a direction, so
 * requiring one specific end would make the block's placement rotation
 * silently significant without any visual cue. This is still exactly one
 * engine shape, mirrored.</li>
 * </ul>
 * Anything else - diagonal placements, vertical crankshafts, a cylinder to the
 * side, more than one cylinder - is deliberately not supported yet.
 */
public record EngineStructure(BlockPos crankshaftPos, Axis axis, BlockPos cylinderPos, BlockPos flywheelPos,
	@Nullable BlockPos carburetorPos) {

	/**
	 * Whether the engine can burn fuel, as opposed to merely being turnable.
	 *
	 * <p>The crankshaft, cylinder, piston and flywheel alone form a <i>mechanically
	 * valid</i> assembly that any Create source can motor. Combustion additionally
	 * needs the carburetor - and fuel in it - which is why the two are separate
	 * questions everywhere in the codebase.
	 */
	public boolean hasCarburetor() {
		return carburetorPos != null;
	}

	/**
	 * Attempts to find a complete, valid engine around the given crankshaft.
	 *
	 * <p>Touches at most four block positions and never scans the world. Returns
	 * {@code null} when any required component is missing, when the cylinder has
	 * no piston assembly installed, or when a required position is in an unloaded
	 * chunk (an unloaded neighbour is treated as "not valid right now" rather than
	 * force-loading it).
	 */
	@Nullable
	public static EngineStructure detect(Level level, BlockPos crankshaftPos, Axis axis) {
		BlockPos cylinderPos = crankshaftPos.above();
		if (!level.isLoaded(cylinderPos))
			return null;
		if (!(level.getBlockEntity(cylinderPos) instanceof CylinderBlockEntity cylinder))
			return null;
		if (!cylinder.hasPistonAssembly())
			return null;

		for (AxisDirection axisDirection : AxisDirection.values()) {
			BlockPos flywheelPos = crankshaftPos.relative(Direction.get(axisDirection, axis));
			if (!level.isLoaded(flywheelPos))
				continue;

			BlockState flywheelState = level.getBlockState(flywheelPos);
			if (!(flywheelState.getBlock() instanceof EngineFlywheelBlock))
				continue;
			if (flywheelState.getValue(EngineFlywheelBlock.HORIZONTAL_AXIS) != axis)
				continue;

			// The carburetor sits directly on top of the cylinder. It is optional
			// here: without it the engine is still mechanically complete.
			BlockPos carburetorPos = cylinderPos.above();
			boolean hasCarburetor = level.isLoaded(carburetorPos)
				&& level.getBlockEntity(carburetorPos) instanceof CarburetorBlockEntity;

			return new EngineStructure(crankshaftPos, axis, cylinderPos, flywheelPos,
				hasCarburetor ? carburetorPos : null);
		}

		return null;
	}
}
