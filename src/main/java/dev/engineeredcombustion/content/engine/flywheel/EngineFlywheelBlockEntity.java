package dev.engineeredcombustion.content.engine.flywheel;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Create kinetic adapter.
 *
 * <p>This is the <b>only</b> class in the mod that knows about Create's kinetic
 * network. It answers one question for Create - "what speed do you generate?" -
 * and gets the answer from the engine simulation living in the adjacent
 * {@link CrankshaftBlockEntity}.
 *
 * <h2>Why the generator lives here and not in the crankshaft</h2>
 * The crankshaft is a kinetic block too - it has to be, or the journal its model
 * shows on the far side from this flywheel would have nothing to connect to. But
 * it is a plain {@code KineticBlockEntity}: a relay that never generates.
 *
 * <p>The <i>generator</i> stays here, and there is exactly one of it. Create
 * decides a source's capacity and speed from the block that generates, so a
 * second {@code GeneratingKineticBlockEntity} on the crankshaft would mean two
 * sources, two stress capacities and two speeds to reconcile for one engine.
 * Instead the crankshaft and this flywheel sit adjacent along a shared axis,
 * which {@code RotationPropagator} resolves as a 1:1 axis connection, so both
 * ends of the engine are one network at one speed fed by this one source.
 *
 * <p>Everything Create needs is reachable through
 * {@link CrankshaftBlockEntity#getGeneratedRpmFor(BlockPos)}, so if Create's
 * kinetic API changes, this class and {@code CrankshaftBlock}'s connectivity are
 * the only places that have to follow.
 */
public class EngineFlywheelBlockEntity extends GeneratingKineticBlockEntity {

	public EngineFlywheelBlockEntity(BlockPos pos, BlockState state) {
		super(ECBlockEntityTypes.FLYWHEEL.get(), pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
	}

	@Override
	public void initialize() {
		super.initialize();
		// Mirrors Create's own generators: re-assert generated rotation after load,
		// unless something faster is already driving this network.
		if (!hasSource() || getGeneratedSpeed() > getTheoreticalSpeed())
			updateGeneratedRotation();
	}

	/**
	 * What Create asks for. Zero whenever the engine is not actively generating,
	 * which is exactly what turns this block into an ordinary <i>passive</i> kinetic
	 * component that a hand crank, another engine, or any other Create source can
	 * motor - see {@code GeneratingKineticBlockEntity#applyNewSpeed}, which keeps a
	 * generator attached to its external source while it generates 0.
	 *
	 * <p>This is also the first of the two places the free-power exploit is closed.
	 * {@code KineticNetwork#getActualCapacityOf} is
	 * {@code sources.get(be) * |be.getGeneratedSpeed()|}, so an engine answering 0
	 * here contributes exactly 0 SU no matter how fast the network it sits on is
	 * being spun, and no matter what capacity value the network has cached for it.
	 *
	 * <p>The value is latched by the simulation rather than computed live: Create
	 * calls this during propagation and during its periodic kinetic validation, and
	 * a value that drifted every tick would make it re-propagate the network
	 * constantly.
	 */
	@Override
	public float getGeneratedSpeed() {
		CrankshaftBlockEntity crankshaft = getAdjacentCrankshaft();
		if (crankshaft == null)
			return 0.0F;
		// Always positive along the block's own axis: the engine only ever fires
		// while turning forwards, so the generated speed can never disagree in sign
		// with the rotation already present - which matters, because
		// RotationPropagator destroys blocks on opposing-sign sources.
		return crankshaft.getGeneratedRpmFor(worldPosition);
	}

	/**
	 * Stress Capacity this engine adds to its network, per RPM.
	 *
	 * <p>The second of the two places the exploit is closed, and the belt to
	 * {@link #getGeneratedSpeed()}'s braces. Create caches this value per source in
	 * {@code KineticNetwork#sources} and only refreshes it when told to, so gating
	 * it alone would not have been enough - but gating it as well means a dead
	 * engine cannot contribute capacity even if a future change to Create stopped
	 * scaling capacity by generated speed.
	 *
	 * <p>Zero is returned for anything that is not a genuinely running engine:
	 * cranking, starting, coasting, stalling, unfuelled, unlit, or simply being
	 * spun by the engine next door. <b>Turning is not generating.</b>
	 *
	 * <p>What it is multiplied by is no longer a count of cylinders but their
	 * <i>effective</i> number - each firing cylinder weighted by the compression it
	 * has left - so a worn engine supplies less without any cylinder having to stop
	 * counting as active.
	 */
	@Override
	public float calculateAddedStressCapacity() {
		// Scaled by the cylinders that are GENUINELY FIRING, each weighted by how much
		// compression it has left. That is the whole of how a bigger engine is a more
		// powerful one and how a tired engine is a weaker one, in one number:
		//
		//   healthy inline-4        1.0 + 1.0 + 1.0 + 1.0 = 4.00
		//   one bore worn out       1.0 + 1.0 + 0.65 + 1.0 = 3.65
		//   one Spark Plug pulled   1.0 + 1.0 + 0.0 + 1.0 = 3.00
		//   motored by a neighbour                          0.00
		//
		// The last line is the exploit this mod closed once and must keep closed: a
		// dry engine being spun supplies nothing however many cylinders it has,
		// because none of them are burning. Cylinder count alone is deliberately never
		// the multiplier, and neither is cylinder HEALTH - a worn cylinder that is
		// firing still contributes, it just contributes less.
		float cylinders = capacityFactor();
		if (cylinders <= 0.0F) {
			lastCapacityProvided = 0.0F;
			return 0.0F;
		}
		float capacity = super.calculateAddedStressCapacity() * cylinders;
		lastCapacityProvided = capacity;
		return capacity;
	}

	/**
	 * Stress Capacity, in SU, that <b>this engine</b> is contributing to its
	 * network right now.
	 *
	 * <p>Deliberately not the network's total. A network's capacity is the sum over
	 * every source on it, which Create already displays; the question this answers
	 * is the one only the engine can - "what is this machine actually putting in" -
	 * and on a shared shaft the two are very different numbers.
	 *
	 * <p>It is exactly the arithmetic Create performs, and it is performed here
	 * rather than reproduced anywhere else:
	 * {@code KineticNetwork#getActualCapacityOf} is the registered per-RPM capacity
	 * times the absolute generated speed, and those are precisely
	 * {@link #calculateAddedStressCapacity()} and {@link #getGeneratedSpeed()}. The
	 * goggle overlay used to multiply a tuning constant by a speed by a cylinder
	 * count of its own; that reproduction could - and did - disagree with the real
	 * thing, and it also ignored any datapack that had retuned the capacity.
	 *
	 * <p>Identical on both sides. Every input is synchronised: Create synchronises
	 * its stress values to clients so its own goggle overlays can print them, and
	 * both the published speed and the capacity factor travel in the engine's block
	 * entity data - the latter because it is derived from combustion ages and
	 * per-cylinder compression the client is never sent.
	 */
	public float getEngineGeneratedCapacity() {
		return calculateAddedStressCapacity() * Math.abs(getGeneratedSpeed());
	}

	/**
	 * Load this engine puts <i>on</i> the network, per RPM.
	 *
	 * <p>The parasitic cost of turning a dead engine over: compression, bearing
	 * friction, pumping losses. Real, and worth charging for - motoring a wall of
	 * unfuelled engines should cost the network that motors them.
	 *
	 * <p>Zero while the engine is generating, and that is not an optimisation: a
	 * running engine already fights exactly this friction inside its own
	 * simulation, where it is what sets the engine's equilibrium speed. Charging it
	 * to the network as well would be billing the same drag twice.
	 *
	 * <p>Create refreshes this whenever the engine's generated rotation changes -
	 * {@code GeneratingKineticBlockEntity#updateGeneratedRotation} calls
	 * {@code updateStressFor}, and {@code applyNewSpeed} does so on the way to zero
	 * - which is precisely when this answer changes. Nothing extra has to poll it.
	 */
	@Override
	public float calculateStressApplied() {
		if (isEngineGenerating()) {
			lastStressApplied = 0.0F;
			return 0.0F;
		}
		return super.calculateStressApplied();
	}

	/**
	 * Whether the engine this flywheel belongs to is actively generating.
	 *
	 * <p>One question, asked of the one authority
	 * ({@code EngineState#isActivelyGenerating}). A flywheel with no crankshaft of
	 * its own - one bolted to a second engine's far end, say - is not generating
	 * anything by definition.
	 */
	private boolean isEngineGenerating() {
		CrankshaftBlockEntity crankshaft = getAdjacentCrankshaft();
		return crankshaft != null && crankshaft.isGeneratingFor(worldPosition);
	}

	/**
	 * How many healthy cylinders' worth of output the engine on the other side of
	 * this flywheel is providing. Zero for a flywheel with no engine of its own.
	 *
	 * <p>The crankshaft this flywheel touches may be a follower of a four-cylinder
	 * engine; it answers for the whole engine, not for its own bore.
	 *
	 * <p>The generation gate is kept in front of it deliberately, as belt to the
	 * simulation's braces: the engine already publishes 0 when it is not generating,
	 * and this makes a dead engine's capacity impossible rather than merely correct.
	 */
	private float capacityFactor() {
		if (!isEngineGenerating())
			return 0.0F;
		CrankshaftBlockEntity crankshaft = getAdjacentCrankshaft();
		return crankshaft == null ? 0.0F : crankshaft.getCapacityFactorFor(worldPosition);
	}

	/** Called by the crankshaft when the engine's rotational output changed. */
	public void onEngineOutputChanged() {
		updateGeneratedRotation();
	}

	/**
	 * Called by the crankshaft when the engine's <i>capacity basis</i> changed while
	 * the speed it generates did not.
	 *
	 * <h2>The bug this exists for</h2>
	 * Create does not ask a source for its capacity. It caches one number per source
	 * in {@code KineticNetwork#sources} and multiplies it by the source's generated
	 * speed on demand ({@code KineticNetwork#getActualCapacityOf}). The cache is
	 * only refreshed when something explicitly refreshes it - and the only thing
	 * that did was {@code updateGeneratedRotation}, which the engine calls when its
	 * <b>published speed</b> changes.
	 *
	 * <p>Those two are not the same event. Pull a Spark Plug out of a running
	 * inline-4 while another source on the network is holding the shaft at a steady
	 * speed, and the engine drops from four firing cylinders to three - a quarter of
	 * its capacity - without its published speed moving by a single quantum. Nothing
	 * refreshed the cache, so the network went on being told the engine could
	 * support four cylinders' worth of machinery it was no longer powering.
	 *
	 * <h2>Why this is not simply updateGeneratedRotation</h2>
	 * {@code updateGeneratedRotation} would work, and it is far too big a hammer: it
	 * re-evaluates the generated speed, can call {@code applyNewSpeed} - which
	 * detaches and re-attaches the whole kinetic network - queues rotation
	 * indicators, and sends a block entity update. None of that is warranted when
	 * the only thing that moved is a multiplier.
	 *
	 * <p>What this does instead is exactly the stress half of
	 * {@code updateGeneratedRotation}, which is the part Create itself uses to
	 * refresh those two caches:
	 * {@code notifyStressCapacityChange} re-registers the per-RPM capacity and
	 * recomputes the network's total, and {@code updateStressFor} does the same for
	 * the passive load this engine applies while it is not generating. Both figures
	 * change together whenever an engine starts or stops generating, so both are
	 * refreshed together here.
	 */
	public void onEngineCapacityChanged() {
		if (level == null || level.isClientSide)
			return;
		// getOrCreateNetwork() would build a network for a block that is not on one,
		// and then hand it a capacity to remember. A block with no network has no
		// cache to invalidate.
		if (!hasNetwork())
			return;
		notifyStressCapacityChange(calculateAddedStressCapacity());
		getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
	}

	/**
	 * Called once by the crankshaft on the first server tick after a world load,
	 * with the engine's state freshly derived from the world.
	 *
	 * <p>Does everything {@link #onEngineOutputChanged()} does, and then refreshes
	 * the Stress figures unconditionally.
	 *
	 * <p>That last part is the point. Create persists a source's capacity per
	 * network and restores it in {@code KineticNetwork#addSilently}, and
	 * {@code updateGeneratedRotation} only refreshes it while the block is turning
	 * ({@code hasNetwork() && speed != 0}). An engine that stopped generating while
	 * the chunk was unloaded - it lost its fuel, its Spark Plug or its Cylinder -
	 * would therefore come back holding the capacity it had when the world was
	 * saved, on a network nobody had asked to recompute. Refreshing here means the
	 * numbers Create is running on after a reload are the ones this engine can
	 * actually justify, which for a dead engine is zero of both.
	 */
	public void reconcileEngineOutput() {
		if (level == null || level.isClientSide)
			return;
		updateGeneratedRotation();
		if (!hasNetwork())
			return;
		notifyStressCapacityChange(calculateAddedStressCapacity());
		getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
		getOrCreateNetwork().updateStress();
	}

	/**
	 * Finds a crankshaft sitting directly next to this flywheel along this
	 * flywheel's own rotation axis, with a matching axis of its own.
	 *
	 * <p>Whether that crankshaft actually drives <i>this</i> flywheel is decided by
	 * the crankshaft in {@link CrankshaftBlockEntity#getGeneratedRpmFor(BlockPos)},
	 * which returns 0 for anything that is not its structural flywheel.
	 */
	@Nullable
	public CrankshaftBlockEntity getAdjacentCrankshaft() {
		if (level == null)
			return null;
		Axis axis = getBlockState().getValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS);
		for (AxisDirection axisDirection : AxisDirection.values()) {
			BlockPos neighbour = worldPosition.relative(Direction.get(axisDirection, axis));
			if (!level.isLoaded(neighbour))
				continue;
			if (level.getBlockEntity(neighbour) instanceof CrankshaftBlockEntity crankshaft
				&& crankshaft.getAxis() == axis)
				return crankshaft;
		}
		return null;
	}
}
