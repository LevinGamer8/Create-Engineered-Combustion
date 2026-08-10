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
 *                        [Carburetor]     crankshaft.above().above()   (optional)
 *                         [Cylinder]      crankshaft.above()
 *                             |           connecting rod (implicit)
 *  ... [Create shaft] [Crankshaft] [Flywheel] [Create shaft] ...
 *                             |
 *                         [Oil Sump]      crankshaft.below()           (optional)
 * </pre>
 *
 * <h2>The Flywheel may sit at either end</h2>
 * The crankshaft's rotation axis is an {@link Axis} - a line, not a direction -
 * so both of its ends are the same kind of place. This resolver therefore looks
 * at <i>both</i> neighbours along that axis,
 * {@code Direction.get(POSITIVE, axis)} and {@code Direction.get(NEGATIVE, axis)},
 * and accepts a Flywheel at either. Nothing here mentions a world direction, so
 * an engine built along X and the same engine built along Z resolve by the same
 * rule, and mirroring a build end for end changes nothing but which BlockPos the
 * answer names.
 *
 * <p>Whichever end the Flywheel takes, the <i>other</i> end of the crankshaft is
 * an ordinary Create shaft output on the same network, because the crankshaft
 * itself is kinetic on both axial faces. There is still exactly one kinetic
 * source - see {@code EngineFlywheelBlockEntity}.
 *
 * <p>Two Flywheels, one at each end, are <b>not</b> a supported engine. Rather
 * than silently picking one - which would give the player an engine whose
 * generator is on an arbitrary side and a second Flywheel that looks connected
 * but is inert - the resolver reports
 * {@link FlywheelPlacement#AMBIGUOUS AMBIGUOUS} and the engine reads as
 * unsupported until one of them is removed. See {@link FlywheelAttachment}.
 *
 * <p>The Oil Sump hangs directly under the crankcase, as a real sump does, and is
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
 * that was visibly burning fuel from one. Resolving on demand costs at most six
 * block-entity lookups at fixed positions - no scan, no caching, and no way for
 * two callers to disagree, because there is only one rule and both sides run it
 * against the same blocks.
 *
 * <h2>Mechanical validity versus supporting systems</h2>
 * {@link #isMechanicallyValid()} covers only what the engine needs in order to
 * <i>turn</i>: a cylinder with a piston in it, and exactly one flywheel. The
 * Carburetor and Oil Sump are looked up independently and never affect it, so an
 * engine missing either is still a real engine that Create can motor - it just
 * cannot burn fuel, or runs dry.
 */
public record EngineComponents(BlockPos crankshaftPos, Axis axis,

	BlockPos cylinderPos, @Nullable CylinderBlockEntity cylinder,

	FlywheelPlacement flywheelPlacement, @Nullable BlockPos flywheelPos,
	@Nullable EngineFlywheelBlockEntity flywheel,

	BlockPos carburetorPos, @Nullable CarburetorBlockEntity carburetor,

	BlockPos oilSumpPos, @Nullable OilSumpBlockEntity oilSump) {

	/** Where along the crank axis this engine's Flywheel was found, if anywhere. */
	public enum FlywheelPlacement {

		/** No Flywheel adjacent to either end of the crank axis. */
		NONE,

		/** One Flywheel, on {@code Direction.get(POSITIVE, axis)}. */
		POSITIVE,

		/** One Flywheel, on {@code Direction.get(NEGATIVE, axis)}. */
		NEGATIVE,

		/**
		 * A Flywheel at <i>both</i> ends. Deliberately not an engine: see the class
		 * comment. Reported rather than resolved so the HUD can say why.
		 */
		AMBIGUOUS;

		/** Whether exactly one Flywheel was found, i.e. this engine has a generator. */
		public boolean isSingle() {
			return this == POSITIVE || this == NEGATIVE;
		}
	}

	/**
	 * The outcome of looking along both ends of the crank axis.
	 *
	 * @param placement which end carried the Flywheel, or why there is no single
	 *                  one
	 * @param pos       that Flywheel's position; null unless exactly one was found,
	 *                  so a caller that only reads this can never be handed an
	 *                  arbitrary choice between two
	 */
	public record FlywheelAttachment(FlywheelPlacement placement, @Nullable BlockPos pos) {

		public static final FlywheelAttachment NONE = new FlywheelAttachment(FlywheelPlacement.NONE, null);

		public boolean isSingle() {
			return placement.isSingle();
		}
	}

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

	/**
	 * One of the two places a Flywheel may be bolted to: the neighbour along the
	 * crank axis in the given axial direction.
	 *
	 * <p>Derived from the axis with {@link Direction#get} rather than from any
	 * world direction, so this is the same rule for an engine running along X and
	 * one running along Z.
	 */
	public static BlockPos flywheelCandidatePos(BlockPos crankshaftPos, Axis axis, AxisDirection side) {
		return crankshaftPos.relative(Direction.get(side, axis));
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

		FlywheelAttachment attachment = findFlywheel(level, crankshaftPos, axis);
		BlockPos flywheelPos = attachment.pos();
		EngineFlywheelBlockEntity flywheel =
			flywheelPos == null ? null : blockEntity(level, flywheelPos, EngineFlywheelBlockEntity.class);
		// A flywheel block with no block entity behind it is not a flywheel this
		// engine can drive, so it is not one of ours at all.
		FlywheelPlacement placement = flywheel == null && attachment.isSingle()
			? FlywheelPlacement.NONE
			: attachment.placement();
		if (flywheel == null)
			flywheelPos = null;

		return new EngineComponents(crankshaftPos, axis, cylinderPos, cylinder, placement, flywheelPos, flywheel,
			carburetorPos, carburetor, oilSumpPos, oilSump);
	}

	/**
	 * The single rule for where a crankshaft's flywheel is: adjacent along the
	 * crankshaft's own axis - at <i>either</i> end - with a matching axis of its
	 * own.
	 *
	 * <p>Both candidates are always examined, and the answer says which one
	 * matched. Stopping at the first match would make one end quietly win over the
	 * other, which is precisely the arbitrary choice
	 * {@link FlywheelPlacement#AMBIGUOUS} exists to avoid.
	 */
	public static FlywheelAttachment findFlywheel(Level level, BlockPos crankshaftPos, Axis axis) {
		BlockPos positive = null;
		BlockPos negative = null;

		for (AxisDirection side : AxisDirection.values()) {
			BlockPos candidate = flywheelCandidatePos(crankshaftPos, axis, side);
			if (!isFlywheelOn(level, candidate, axis))
				continue;
			if (side == AxisDirection.POSITIVE)
				positive = candidate;
			else
				negative = candidate;
		}

		if (positive != null && negative != null)
			// Both ends occupied. Unsupported for now, and reported as such rather
			// than resolved: two flywheels would otherwise mean two Create sources
			// asking one engine for its generated speed.
			return new FlywheelAttachment(FlywheelPlacement.AMBIGUOUS, null);
		if (positive != null)
			return new FlywheelAttachment(FlywheelPlacement.POSITIVE, positive);
		if (negative != null)
			return new FlywheelAttachment(FlywheelPlacement.NEGATIVE, negative);
		return FlywheelAttachment.NONE;
	}

	/** Whether the given position holds a Flywheel lined up with the crank axis. */
	private static boolean isFlywheelOn(Level level, BlockPos pos, Axis axis) {
		if (!level.isLoaded(pos))
			return false;
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof EngineFlywheelBlock))
			return false;
		return state.getValue(EngineFlywheelBlock.HORIZONTAL_AXIS) == axis;
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
	 * installed, and exactly one flywheel to carry the rotation into Create.
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

	/** Whether a flywheel is bolted to <i>both</i> ends, which is not an engine. */
	public boolean hasFlywheelConflict() {
		return flywheelPlacement == FlywheelPlacement.AMBIGUOUS;
	}

	/** Lubrication implied by the sump, treating a missing sump as an empty one. */
	public LubricationState lubrication() {
		return oilSump == null ? LubricationState.DRY : oilSump.getLubricationState();
	}
}
