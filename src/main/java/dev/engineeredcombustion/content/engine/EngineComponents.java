package dev.engineeredcombustion.content.engine;

import org.jetbrains.annotations.Nullable;

import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlockEntity;
import dev.engineeredcombustion.content.engine.cylinder.CylinderBlockEntity;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlock;
import dev.engineeredcombustion.content.engine.flywheel.EngineFlywheelBlockEntity;
import dev.engineeredcombustion.content.engine.sump.OilSumpBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one place that knows where an engine's parts are.
 *
 * <h2>Authoritative layout</h2>
 * All offsets are relative to the <b>Crankshaft</b>, which is the engine's
 * controller and defines its axis:
 *
 * <pre>
 *          [Carburetor]     crankshaft.above().above()   (optional)
 *           [Cylinder]      crankshaft.above()
 *               |           connecting rod (implicit)
 *  ... [Crankshaft] [Flywheel] [Create shaft] ...        (either end of the axis)
 *               |
 *           [Oil Sump]      crankshaft.below()           (optional)
 * </pre>
 *
 * The Oil Sump hangs directly under the crankcase, as a real sump does, and is
 * deliberately <i>not</i> accepted anywhere beside the cylinder. The Carburetor
 * sits on top of the cylinder, on the intake end of the bore; that position is
 * the one the working build already fuels from, so it is kept.
 *
 * <h2>Why this is a record resolved on demand</h2>
 * Every subsystem - combustion, fuel draw, lubrication, the engine overlay, the
 * diagnostics - calls {@link #resolve} and reads the result. Nothing caches a
 * component between ticks and nothing computes an offset of its own.
 *
 * <p>That matters because the previous design cached the detected structure in a
 * field that was only ever populated on the server: the goggle overlay runs on
 * the client, found the field null, and reported "No Carburetor" about an engine
 * that was visibly burning fuel from one. Resolving on demand costs at most five
 * block-entity lookups at fixed positions - no scan, no caching, and no way for
 * two callers to disagree, because there is only one rule and both sides run it
 * against the same blocks.
 *
 * <h2>Mechanical validity versus supporting systems</h2>
 * {@link #isMechanicallyValid()} covers only what the engine needs in order to
 * <i>turn</i>: a cylinder with a piston in it, and a flywheel. The Carburetor and
 * Oil Sump are looked up independently and never affect it, so an engine missing
 * either is still a real engine that Create can motor - it just cannot burn fuel,
 * or runs dry.
 */
public record EngineComponents(BlockPos crankshaftPos, Axis axis,

	BlockPos cylinderPos, @Nullable CylinderBlockEntity cylinder,

	@Nullable BlockPos flywheelPos, @Nullable EngineFlywheelBlockEntity flywheel,

	BlockPos carburetorPos, @Nullable CarburetorBlockEntity carburetor,

	BlockPos oilSumpPos, @Nullable OilSumpBlockEntity oilSump) {

	// --- the layout, in one place ------------------------------------------

	public static BlockPos cylinderPos(BlockPos crankshaftPos) {
		return crankshaftPos.above();
	}

	/** On top of the cylinder - the intake end of the bore. */
	public static BlockPos carburetorPos(BlockPos crankshaftPos) {
		return crankshaftPos.above()
			.above();
	}

	/** Directly under the crankcase, as a real sump is. */
	public static BlockPos oilSumpPos(BlockPos crankshaftPos) {
		return crankshaftPos.below();
	}

	// --- the same layout, inverted -----------------------------------------
	//
	// Components have to tell their crankshaft when they are placed or removed,
	// because adding fuel or oil changes no block state and the crankshaft would
	// otherwise never hear about it. Deriving those offsets here rather than
	// open-coding them at each call site is what keeps a component and the engine
	// that owns it from ever disagreeing about which of them is the other's.

	/** The crankshaft a cylinder at this position belongs to. */
	public static BlockPos crankshaftPosFromCylinder(BlockPos cylinderPos) {
		return cylinderPos.below();
	}

	/** The crankshaft a carburetor at this position belongs to. */
	public static BlockPos crankshaftPosFromCarburetor(BlockPos carburetorPos) {
		return carburetorPos.below()
			.below();
	}

	/** The crankshaft an oil sump at this position belongs to. */
	public static BlockPos crankshaftPosFromOilSump(BlockPos oilSumpPos) {
		return oilSumpPos.above();
	}

	// --- resolution ---------------------------------------------------------

	/**
	 * Looks up every component of the engine around the given crankshaft.
	 *
	 * <p>Safe on either side and at any time. A component in an unloaded chunk
	 * resolves to absent rather than force-loading it, and never throws.
	 */
	public static EngineComponents resolve(Level level, BlockPos crankshaftPos, Axis axis) {
		BlockPos cylinderPos = cylinderPos(crankshaftPos);
		BlockPos carburetorPos = carburetorPos(crankshaftPos);
		BlockPos oilSumpPos = oilSumpPos(crankshaftPos);

		CylinderBlockEntity cylinder = blockEntity(level, cylinderPos, CylinderBlockEntity.class);
		CarburetorBlockEntity carburetor = blockEntity(level, carburetorPos, CarburetorBlockEntity.class);
		OilSumpBlockEntity oilSump = blockEntity(level, oilSumpPos, OilSumpBlockEntity.class);

		BlockPos flywheelPos = findFlywheelPos(level, crankshaftPos, axis);
		EngineFlywheelBlockEntity flywheel =
			flywheelPos == null ? null : blockEntity(level, flywheelPos, EngineFlywheelBlockEntity.class);
		if (flywheel == null)
			flywheelPos = null;

		return new EngineComponents(crankshaftPos, axis, cylinderPos, cylinder, flywheelPos, flywheel, carburetorPos,
			carburetor, oilSumpPos, oilSump);
	}

	/**
	 * The single rule for where a crankshaft's flywheel is: adjacent along the
	 * crankshaft's own axis, with a matching axis of its own.
	 *
	 * <p>Both ends are accepted. The crankshaft axis is an <i>axis</i>, not a
	 * direction, so requiring one particular end would make the block's placement
	 * rotation silently significant with no visual cue. This is still exactly one
	 * engine shape, mirrored.
	 */
	@Nullable
	public static BlockPos findFlywheelPos(Level level, BlockPos crankshaftPos, Axis axis) {
		for (AxisDirection axisDirection : AxisDirection.values()) {
			BlockPos candidate = crankshaftPos.relative(Direction.get(axisDirection, axis));
			if (!level.isLoaded(candidate))
				continue;
			BlockState state = level.getBlockState(candidate);
			if (!(state.getBlock() instanceof EngineFlywheelBlock))
				continue;
			if (state.getValue(EngineFlywheelBlock.HORIZONTAL_AXIS) != axis)
				continue;
			return candidate;
		}
		return null;
	}

	@Nullable
	private static <T> T blockEntity(Level level, BlockPos pos, Class<T> type) {
		if (!level.isLoaded(pos))
			return null;
		Object be = level.getBlockEntity(pos);
		return type.isInstance(be) ? type.cast(be) : null;
	}

	// --- queries ------------------------------------------------------------

	/**
	 * Whether the engine can physically turn: a cylinder with a piston assembly
	 * installed, and a flywheel to carry the rotation into Create.
	 *
	 * <p>Deliberately independent of the Carburetor and Oil Sump. Neither is
	 * required for the engine to be motored by another Create source.
	 */
	public boolean isMechanicallyValid() {
		return cylinder != null && cylinder.hasPistonAssembly() && flywheel != null;
	}

	public boolean hasCarburetor() {
		return carburetor != null;
	}

	public boolean hasOilSump() {
		return oilSump != null;
	}

	public boolean hasFlywheel() {
		return flywheel != null;
	}

	/** Lubrication implied by the sump, treating a missing sump as an empty one. */
	public LubricationState lubrication() {
		return oilSump == null ? LubricationState.DRY : oilSump.getLubricationState();
	}
}
