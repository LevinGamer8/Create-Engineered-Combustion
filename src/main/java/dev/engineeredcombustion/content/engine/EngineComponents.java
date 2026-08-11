package dev.engineeredcombustion.content.engine;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import dev.engineeredcombustion.content.engine.carburetor.CarburetorBlockEntity;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlock;
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
 * The one place that knows what an engine is made of and where its parts are.
 *
 * <h2>Authoritative layout</h2>
 * An engine is a run of Crankshaft sections along one horizontal axis, each with
 * a Cylinder on top of it, one Flywheel bolted to either end of the run, and one
 * Carburetor and one Oil Sump serving the whole thing:
 *
 * <pre>
 *                    [Carburetor]                  above any one cylinder
 *   [Cylinder] [Cylinder] [Cylinder] [Cylinder]    one per crankshaft section
 *       |          |          |          |         connecting rods (implicit)
 *   ...[Crank]  [Crank]   [Crank]   [Crank] [Flywheel] [Create shaft]...
 *       |
 *   [Oil Sump]                                     below any one section
 * </pre>
 *
 * <p>Inline-1 through inline-4 are supported;
 * {@link EngineTuning#MAX_CYLINDERS} is the single place that limit lives.
 *
 * <h2>One engine, not several</h2>
 * Adjacent crankshaft sections sharing an axis are <b>one engine</b>. The section
 * at the negative end of the run is its <i>controller</i> - the one block entity
 * that runs the simulation, owns the master crank angle, holds the controls and
 * talks to Create. Every other section is a follower that reads the controller's
 * state and contributes a cylinder to it.
 *
 * <p>Choosing the negative end is arbitrary but <b>deterministic</b>, which is
 * the property that matters: every section of an engine resolves the same
 * controller, from block states alone, in the same handful of lookups, whether
 * it is asked on the server, on the client, mid-tick or during a chunk load. No
 * block entity reference is ever serialised, so the assembly rebuilds itself from
 * the world after any reload rather than being restored from a list that may no
 * longer be true.
 *
 * <p>Cylinder index follows the same order: index 0 sits on the controller and
 * the rest ascend along the axis. Nothing depends on block entity tick order,
 * which is what makes the crank phases - and later the firing order - stable.
 *
 * <h2>The Flywheel may sit at either end</h2>
 * The crankshaft's rotation axis is an {@link Axis} - a line, not a direction -
 * so both ends of the <i>whole run</i> are the same kind of place. This resolver
 * looks at the neighbour beyond the first section and the neighbour beyond the
 * last, and accepts a Flywheel at either. Nothing here mentions a world
 * direction, so an engine built along X and the same engine built along Z resolve
 * by the same rule.
 *
 * <p>Whichever end the Flywheel takes, the <i>other</i> end of the run is an
 * ordinary Create shaft output on the same network, because every crankshaft
 * section is kinetic on both axial faces. There is still exactly one kinetic
 * source for the whole engine, however many cylinders it has - see
 * {@code EngineFlywheelBlockEntity}.
 *
 * <p>Two Flywheels, one at each end, are <b>not</b> a supported engine. Rather
 * than silently picking one, the resolver reports
 * {@link FlywheelPlacement#AMBIGUOUS AMBIGUOUS} and the engine reads as
 * unsupported until one is removed.
 *
 * <h2>Why this is a record resolved on demand</h2>
 * Every subsystem - combustion, fuel draw, lubrication, the overlays, the
 * renderers - calls {@link #resolve} and reads the result. Nothing caches a
 * component between ticks and nothing computes an offset of its own. Resolving
 * an inline-4 costs about twenty block lookups at fixed positions: no scan, no
 * cache to invalidate, and no way for two callers to disagree.
 *
 * <h2>Mechanical validity versus supporting systems</h2>
 * {@link #isMechanicallyValid()} covers only what the engine needs in order to
 * <i>turn</i>: every section carrying a Cylinder with a Piston Assembly in it,
 * and exactly one Flywheel. The Carburetor and Oil Sump are looked up
 * independently and never affect it, so an engine missing either is still a real
 * engine that Create can motor - it just cannot burn fuel, or runs dry.
 *
 * <p>A Spark Plug is not structural either, and is deliberately <i>per
 * cylinder</i>: an inline-4 with one plug missing is a complete, sound machine
 * that runs on three cylinders.
 */
public record EngineComponents(BlockPos controllerPos, Axis axis,

	List<Cylinder> cylinders, boolean oversized,

	FlywheelPlacement flywheelPlacement, @Nullable BlockPos flywheelPos,
	@Nullable EngineFlywheelBlockEntity flywheel,

	@Nullable BlockPos carburetorPos, @Nullable CarburetorBlockEntity carburetor,

	@Nullable BlockPos oilSumpPos, @Nullable OilSumpBlockEntity oilSump,

	boolean chunksLoaded) {

	/**
	 * One cylinder of an engine: its place in the firing order, the crankshaft
	 * section it sits on, and whatever is installed in it.
	 *
	 * @param index          0-based position along the crank axis from the
	 *                       controller. Fixes this cylinder's crank phase - see
	 *                       {@link EngineTuning#cylinderPhaseOffsetDegrees}
	 * @param crankshaftPos  the section carrying this cylinder's throw
	 * @param cylinderPos    directly above it
	 * @param blockEntity    null when the Cylinder is missing or its chunk is not
	 *                       loaded
	 */
	public record Cylinder(int index, BlockPos crankshaftPos, BlockPos cylinderPos,
		@Nullable CylinderBlockEntity blockEntity) {

		public boolean hasPiston() {
			return blockEntity != null && blockEntity.hasPistonAssembly();
		}

		public boolean hasSparkPlug() {
			return blockEntity != null && blockEntity.hasSparkPlug();
		}
	}

	/** Where along the crank axis this engine's Flywheel was found, if anywhere. */
	public enum FlywheelPlacement {

		/** No Flywheel adjacent to either end of the run. */
		NONE,

		/** One Flywheel, beyond the last section, on {@code Direction.get(POSITIVE, axis)}. */
		POSITIVE,

		/** One Flywheel, beyond the controller, on {@code Direction.get(NEGATIVE, axis)}. */
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
	 * The outcome of looking beyond both ends of the crank run.
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

	/** On top of a cylinder - the intake end of the bore. */
	public static BlockPos carburetorPos(BlockPos crankshaftPos) {
		return crankshaftPos.above()
			.above();
	}

	/** Directly under a crankcase section, as a real sump is. */
	public static BlockPos oilSumpPos(BlockPos crankshaftPos) {
		return crankshaftPos.below();
	}

	/**
	 * One of the two places a Flywheel may be bolted to: the neighbour beyond an
	 * end of the crank run, in the given axial direction.
	 *
	 * <p>Derived from the axis with {@link Direction#get} rather than from any
	 * world direction, so this is the same rule for an engine running along X and
	 * one running along Z.
	 */
	public static BlockPos flywheelCandidatePos(BlockPos endSectionPos, Axis axis, AxisDirection side) {
		return endSectionPos.relative(Direction.get(side, axis));
	}

	// --- the same layout, inverted -----------------------------------------
	//
	// Components have to tell their engine when they are placed or removed,
	// because adding fuel or oil changes no block state and the crankshaft would
	// otherwise never hear about it. Deriving those offsets here rather than
	// open-coding them at each call site is what keeps a component and the engine
	// that owns it from ever disagreeing about which of them is the other's.
	//
	// Each answers with the crankshaft SECTION under the component. That section
	// may be a follower; it forwards to its controller, which is the one place an
	// engine-wide change has to arrive.

	/** The crankshaft section a cylinder at this position belongs to. */
	public static BlockPos crankshaftPosFromCylinder(BlockPos cylinderPos) {
		return cylinderPos.below();
	}

	/** The crankshaft section a carburetor at this position sits over. */
	public static BlockPos crankshaftPosFromCarburetor(BlockPos carburetorPos) {
		return carburetorPos.below()
			.below();
	}

	/** The crankshaft section an oil sump at this position hangs from. */
	public static BlockPos crankshaftPosFromOilSump(BlockPos oilSumpPos) {
		return oilSumpPos.above();
	}

	// --- resolution ---------------------------------------------------------

	/**
	 * Where a crankshaft section sits in its engine, from block states alone.
	 *
	 * @param controllerPos the negative-end section that runs this engine
	 * @param index         this section's 0-based place along the axis, and
	 *                      therefore its cylinder's index and crank phase
	 * @param count         how many sections the engine has
	 * @param oversized     the run is longer than {@link EngineTuning#MAX_CYLINDERS}
	 *                      and is therefore not a supported engine
	 */
	public record Placement(BlockPos controllerPos, int index, int count, boolean oversized) {

		public boolean isController() {
			return index == 0;
		}
	}

	/**
	 * The cheap half of {@link #resolve}: which engine this section belongs to and
	 * where in it, without touching a single block entity.
	 *
	 * <p>Followers need nothing more than this - their index fixes their crank
	 * phase and points at the controller - and the renderers ask for it every
	 * frame, so it is deliberately block states only: at most ten lookups for an
	 * inline-4, no block entity resolution, and no allocation beyond the record.
	 *
	 * <p>Identical on both sides, because block states are synchronised. That is
	 * what lets a client work out on its own which throw it is drawing.
	 */
	public static Placement locate(Level level, BlockPos fromPos, Axis axis) {
		Direction negative = Direction.get(AxisDirection.NEGATIVE, axis);
		Direction positive = negative.getOpposite();

		BlockPos controllerPos = fromPos;
		int index = 0;
		while (index < EngineTuning.MAX_CYLINDERS && isCrankshaftOn(level, controllerPos.relative(negative), axis)) {
			controllerPos = controllerPos.relative(negative);
			index++;
		}
		boolean oversized = index >= EngineTuning.MAX_CYLINDERS
			&& isCrankshaftOn(level, controllerPos.relative(negative), axis);

		int count = index + 1;
		BlockPos section = fromPos;
		while (count < EngineTuning.MAX_CYLINDERS && isCrankshaftOn(level, section.relative(positive), axis)) {
			section = section.relative(positive);
			count++;
		}
		if (count >= EngineTuning.MAX_CYLINDERS && isCrankshaftOn(level, section.relative(positive), axis))
			oversized = true;

		return new Placement(controllerPos, index, count, oversized);
	}

	/**
	 * Resolves the whole engine that the crankshaft section at {@code fromPos}
	 * belongs to.
	 *
	 * <p>Safe on either side and at any time. A component in an unloaded chunk
	 * resolves to absent - and says so through {@link #chunksLoaded()}, so a caller
	 * that must not mistake "not loaded yet" for "not there" can wait - and never
	 * throws.
	 *
	 * <p>{@code fromPos} may be any section of the engine, not just its
	 * controller: the walk below finds that for itself.
	 */
	public static EngineComponents resolve(Level level, BlockPos fromPos, Axis axis) {
		Direction negative = Direction.get(AxisDirection.NEGATIVE, axis);
		Direction positive = negative.getOpposite();

		// Walk to the negative end of the run - the controller. Bounded, so a long
		// line of crankcases is a cheap fixed cost rather than a world scan.
		boolean loaded = true;
		BlockPos controllerPos = fromPos;
		int stepsBack = 0;
		while (stepsBack < EngineTuning.MAX_CYLINDERS) {
			BlockPos next = controllerPos.relative(negative);
			if (!level.isLoaded(next)) {
				loaded = false;
				break;
			}
			if (!isCrankshaftOn(level, next, axis))
				break;
			controllerPos = next;
			stepsBack++;
		}
		// Still more crankcase beyond the bound: too long to be one of our engines.
		boolean oversized = stepsBack >= EngineTuning.MAX_CYLINDERS
			&& isCrankshaftOn(level, controllerPos.relative(negative), axis);

		// Then forward along it, collecting one cylinder per section.
		List<Cylinder> cylinders = new ArrayList<>(EngineTuning.MAX_CYLINDERS);
		BlockPos section = controllerPos;
		while (true) {
			BlockPos cylinderPos = cylinderPos(section);
			if (!level.isLoaded(cylinderPos))
				loaded = false;
			cylinders.add(new Cylinder(cylinders.size(), section, cylinderPos,
				blockEntity(level, cylinderPos, CylinderBlockEntity.class)));

			BlockPos next = section.relative(positive);
			if (!level.isLoaded(next)) {
				loaded = false;
				break;
			}
			if (!isCrankshaftOn(level, next, axis))
				break;
			if (cylinders.size() >= EngineTuning.MAX_CYLINDERS) {
				oversized = true;
				break;
			}
			section = next;
		}

		BlockPos lastSection = cylinders.get(cylinders.size() - 1)
			.crankshaftPos();

		// One Carburetor and one Oil Sump feed the whole engine, and either may be
		// fitted anywhere along it - above any cylinder, below any section. The
		// lowest-indexed one wins so the answer is deterministic; a player who
		// fits several gets one working engine, not several.
		BlockPos carburetorPos = null;
		CarburetorBlockEntity carburetor = null;
		BlockPos oilSumpPos = null;
		OilSumpBlockEntity oilSump = null;
		for (Cylinder cylinder : cylinders) {
			if (carburetor == null) {
				BlockPos candidate = carburetorPos(cylinder.crankshaftPos());
				if (!level.isLoaded(candidate))
					loaded = false;
				CarburetorBlockEntity found = blockEntity(level, candidate, CarburetorBlockEntity.class);
				if (found != null) {
					carburetorPos = candidate;
					carburetor = found;
				}
			}
			if (oilSump == null) {
				BlockPos candidate = oilSumpPos(cylinder.crankshaftPos());
				if (!level.isLoaded(candidate))
					loaded = false;
				OilSumpBlockEntity found = blockEntity(level, candidate, OilSumpBlockEntity.class);
				if (found != null) {
					oilSumpPos = candidate;
					oilSump = found;
				}
			}
		}

		FlywheelAttachment attachment = findFlywheel(level, controllerPos, lastSection, axis);
		BlockPos flywheelPos = attachment.pos();
		EngineFlywheelBlockEntity flywheel =
			flywheelPos == null ? null : blockEntity(level, flywheelPos, EngineFlywheelBlockEntity.class);
		// A flywheel block with no block entity behind it is not a flywheel this
		// engine can drive, so it is not one of ours at all.
		FlywheelPlacement placement =
			flywheel == null && attachment.isSingle() ? FlywheelPlacement.NONE : attachment.placement();
		if (flywheel == null)
			flywheelPos = null;

		return new EngineComponents(controllerPos, axis, List.copyOf(cylinders), oversized, placement, flywheelPos,
			flywheel, carburetorPos, carburetor, oilSumpPos, oilSump, loaded);
	}

	/**
	 * A single-section placeholder for a crankshaft with no level to look at.
	 * Structurally invalid by construction, which is the right answer for an
	 * engine nobody can see.
	 */
	public static EngineComponents detached(BlockPos pos, Axis axis) {
		return new EngineComponents(pos, axis,
			List.of(new Cylinder(0, pos, cylinderPos(pos), null)), false, FlywheelPlacement.NONE, null, null, null,
			null, null, null, false);
	}

	/**
	 * The single rule for where an engine's flywheel is: adjacent along the crank
	 * axis, beyond <i>either</i> end of the run, with a matching axis of its own.
	 *
	 * <p>Both candidates are always examined, and the answer says which one
	 * matched. Stopping at the first match would make one end quietly win over the
	 * other, which is precisely the arbitrary choice
	 * {@link FlywheelPlacement#AMBIGUOUS} exists to avoid.
	 */
	public static FlywheelAttachment findFlywheel(Level level, BlockPos firstSection, BlockPos lastSection,
		Axis axis) {
		BlockPos negative = flywheelCandidatePos(firstSection, axis, AxisDirection.NEGATIVE);
		BlockPos positive = flywheelCandidatePos(lastSection, axis, AxisDirection.POSITIVE);

		boolean hasNegative = isFlywheelOn(level, negative, axis);
		boolean hasPositive = isFlywheelOn(level, positive, axis);

		if (hasNegative && hasPositive)
			// Both ends occupied. Unsupported for now, and reported as such rather
			// than resolved: two flywheels would otherwise mean two Create sources
			// asking one engine for its generated speed.
			return new FlywheelAttachment(FlywheelPlacement.AMBIGUOUS, null);
		if (hasPositive)
			return new FlywheelAttachment(FlywheelPlacement.POSITIVE, positive);
		if (hasNegative)
			return new FlywheelAttachment(FlywheelPlacement.NEGATIVE, negative);
		return FlywheelAttachment.NONE;
	}

	/** Whether the given position holds a Crankshaft section lined up with the axis. */
	public static boolean isCrankshaftOn(Level level, BlockPos pos, Axis axis) {
		if (!level.isLoaded(pos))
			return false;
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof CrankshaftBlock))
			return false;
		return state.getValue(CrankshaftBlock.HORIZONTAL_AXIS) == axis;
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

	/** How many cylinders this engine has: 1 to {@link EngineTuning#MAX_CYLINDERS}. */
	public int cylinderCount() {
		return cylinders.size();
	}

	/** This engine's cylinder at the given crankshaft section, or -1 if it is not ours. */
	public int indexOf(BlockPos crankshaftPos) {
		for (Cylinder cylinder : cylinders)
			if (cylinder.crankshaftPos()
				.equals(crankshaftPos))
				return cylinder.index();
		return -1;
	}

	public Cylinder cylinder(int index) {
		return cylinders.get(Math.min(Math.max(index, 0), cylinders.size() - 1));
	}

	/**
	 * Bit {@code i} set when cylinder {@code i} has a Spark Plug, in exactly the
	 * form {@link EngineInputs} wants it.
	 */
	public int sparkPlugMask() {
		int mask = 0;
		for (Cylinder cylinder : cylinders)
			if (cylinder.hasSparkPlug())
				mask |= 1 << cylinder.index();
		return mask;
	}

	/**
	 * Whether the engine can physically turn: every section carrying a cylinder
	 * with a piston assembly in it, exactly one flywheel, and a run no longer than
	 * this milestone supports.
	 *
	 * <p>Deliberately independent of the Carburetor and Oil Sump. Neither is
	 * required for the engine to be motored by another Create source.
	 */
	public boolean isMechanicallyValid() {
		if (oversized || flywheel == null)
			return false;
		for (Cylinder cylinder : cylinders)
			if (!cylinder.hasPiston())
				return false;
		return true;
	}

	/** Whether a Spark Plug is screwed into <i>every</i> cylinder head. */
	public boolean hasSparkPlug() {
		return Integer.bitCount(sparkPlugMask()) == cylinderCount();
	}

	/** How many of this engine's cylinders have a Spark Plug fitted. */
	public int sparkPlugCount() {
		return Integer.bitCount(sparkPlugMask());
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
