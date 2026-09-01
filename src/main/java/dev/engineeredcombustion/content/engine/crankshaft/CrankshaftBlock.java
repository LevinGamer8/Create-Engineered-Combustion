package dev.engineeredcombustion.content.engine.crankshaft;

import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import dev.engineeredcombustion.foundation.ECLang;
import dev.engineeredcombustion.foundation.EngineCasting;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECDataComponents;
import dev.engineeredcombustion.registry.ECItems;
import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The crankshaft: logical controller of a single-cylinder engine, and a real
 * Create kinetic block on both ends of its own axis.
 *
 * <p>It owns the engine simulation; the adjacent Flywheel is what <i>generates</i>
 * for Create. See {@code EngineComponents} for the supported layout and every
 * component offset.
 *
 * <h2>Why this is a kinetic block now</h2>
 * The crankshaft model has always shown a main journal leaving the crankcase at
 * <i>both</i> ends, but the block was a plain {@link Block} with no
 * {@code IRotate} on it and a plain block entity underneath. Create's
 * {@code RotationPropagator} only ever walks between {@code KineticBlockEntity}
 * instances whose blocks implement {@code IRotate}, so the crankshaft was simply
 * invisible to the kinetic graph: a Shaft placed against the visible journal on
 * the far side from the Flywheel received nothing, because there was nothing
 * there for it to connect to. Only the Flywheel's own free face transmitted.
 *
 * <p>Extending {@link HorizontalAxisKineticBlock} fixes that with Create's own
 * semantics rather than a reimplementation of them:
 * {@code hasShaftTowards(...)} returns true exactly when
 * {@code face.getAxis() == HORIZONTAL_AXIS}, which is both ends of the crank
 * axis and nowhere else; {@code getRotationAxis(...)} agrees with it; and wrench
 * rotation plus the {@code onRemove} / {@code updateIndirectNeighbourShapes}
 * kinetic bookkeeping come along for free.
 *
 * <p>Crucially the crankshaft is a <i>relay</i>, not a second generator. Its
 * block entity extends {@code KineticBlockEntity}, not
 * {@code GeneratingKineticBlockEntity}, so {@code getGeneratedSpeed()} stays 0
 * and it can never become a source; and it registers neither stress capacity nor
 * stress impact. Crankshaft and Flywheel sit adjacent along a shared axis, which
 * {@code RotationPropagator} resolves as a 1:1 axis connection, so the two are
 * one network turning at one speed, fed by one source: the engine.
 *
 * <p>Placement: the crankshaft's axis follows the horizontal direction the
 * player is facing, so walking towards where the engine should run and placing
 * the block gives the expected axis. That deliberately overrides
 * {@code HorizontalAxisKineticBlock}, which prefers the axis of an adjacent
 * shaft and otherwise takes the player's <i>clockwise</i> direction - correct
 * for a cogwheel, wrong for a machine the player is lining up by eye.
 *
 * <h2>The engine's controls</h2>
 * The crankcase carries the ignition switch, and it is the whole of what an
 * engine needs to be started and stopped:
 * <ul>
 * <li>right-click empty-handed - work the switch;</li>
 * <li>right-click holding a Camshaft - fit the engine's valvetrain, which every
 * engine needs exactly one of before it can run;</li>
 * <li>right-click holding a Redstone Control Module - plug it in, which is the
 * <i>only</i> way this engine ever comes to care about redstone;</li>
 * <li>sneak + right-click empty-handed - take an installed part back out;</li>
 * <li>right-click and hold with a Wrench on the module's value box - Create's
 * own value UI, for choosing what redstone is allowed to drive.</li>
 * </ul>
 */
public class CrankshaftBlock extends HorizontalAxisKineticBlock
	implements IBE<CrankshaftBlockEntity>, EngineCasting {

	/**
	 * Whether the ignition is live: the tell-tale lamp on the crankcase glows and
	 * the ignition switch beside it stands up.
	 *
	 * <p>Purely cosmetic - nothing reads it back. It is written from the engine's
	 * <i>effective</i> ignition (see
	 * {@code CrankshaftBlockEntity#updateIgnitionIndicator}), so the switch on the
	 * model shows the state the engine is actually in, whether that came from a
	 * player flipping it or from a Redstone Control Module. A player without
	 * Engineer's Goggles can therefore read the engine's ignition by looking at it,
	 * which is exactly what a real machine's switch is for.
	 *
	 * <p>Kept as a block state rather than a renderer so it costs nothing to draw
	 * and works on any rendering backend. It is deliberately not part of
	 * {@code areStatesKineticallyEquivalent}, which compares block and rotation axis
	 * only, so toggling it can never re-propagate the kinetic network.
	 */
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	/**
	 * Whether another crankshaft section sits against this one's <b>negative</b>
	 * axial face, i.e. this is not the first cylinder of its engine.
	 *
	 * <p>Purely cosmetic, and it changes exactly one thing: the machined top deck
	 * runs across the seam instead of stopping short of it, so an inline-4 reads
	 * as one continuous casting with four bores rather than four one-cylinder
	 * engines standing shoulder to shoulder. Only the negative side is tested,
	 * because a seam has two sides and only one of them needs to reach across it -
	 * testing both would put two decks in the same place.
	 *
	 * <p>Like {@link #LIT} it is deliberately outside
	 * {@code areStatesKineticallyEquivalent}, which compares block and rotation
	 * axis only, so growing an engine can never re-propagate its kinetic network.
	 */
	public static final BooleanProperty JOINED = BooleanProperty.create("joined");

	public CrankshaftBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(HORIZONTAL_AXIS, Axis.X)
			.setValue(LIT, false)
			.setValue(JOINED, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT, JOINED);
		// super adds HORIZONTAL_AXIS - the same BlockStateProperties instance the
		// old hand-rolled declaration used, so the blockstate JSON is unchanged.
		super.createBlockStateDefinition(builder);
	}

	/**
	 * Places the crankcase along the direction the player is facing, with the
	 * ignition tell-tale already lit.
	 *
	 * <p>Lit, because a fresh engine's ignition switch is on - see
	 * {@code CrankshaftBlockEntity#manualIgnition}. Setting it here rather than
	 * leaving the first server tick to notice is only tidiness: it saves a block
	 * update on the tick after placement, and it means the engine never appears,
	 * for a frame, to disagree with its own switch.
	 */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Axis axis = context.getHorizontalDirection()
			.getAxis();
		return defaultBlockState().setValue(HORIZONTAL_AXIS, axis)
			.setValue(LIT, true)
			.setValue(JOINED, joinsSectionTowardsNegative(context.getLevel(), context.getClickedPos(), axis));
	}

	/**
	 * Keeps {@link #JOINED} true exactly while a crankshaft section on the same
	 * axis sits against this one's negative face.
	 *
	 * <p>{@code updateShape} rather than a block entity tick, because this is
	 * purely a question about the neighbouring <i>block</i>: vanilla calls it for
	 * every adjacent change, so extending or cutting an engine re-knits the
	 * casting in the same tick, with no state of our own to keep in step.
	 */
	@Override
	protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
		LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
		Axis axis = state.getValue(HORIZONTAL_AXIS);
		if (direction.getAxis() != axis || direction.getAxisDirection() != AxisDirection.NEGATIVE)
			return super.updateShape(state, direction, neighbourState, level, pos, neighbourPos);
		return state.setValue(JOINED, isSectionOn(neighbourState, axis));
	}

	/**
	 * The same answer {@link #updateShape} gives, asked of the world rather than
	 * handed a neighbour.
	 *
	 * <p>For {@link EngineCasting#refresh}, which brings an engine saved before a
	 * piece of this casting existed back in line on the first tick after it loads.
	 * {@code updateShape} cannot do that on its own: it only runs when a neighbour
	 * changes, and an engine that is standing still has no neighbours changing.
	 */
	@Override
	public BlockState castingState(LevelReader level, BlockPos pos, BlockState state) {
		boolean joined = joinsSectionTowardsNegative(level, pos, state.getValue(HORIZONTAL_AXIS));
		return state.getValue(JOINED) == joined ? state : state.setValue(JOINED, joined);
	}

	private static boolean joinsSectionTowardsNegative(LevelReader level, BlockPos pos, Axis axis) {
		return isSectionOn(level.getBlockState(pos.relative(Direction.get(AxisDirection.NEGATIVE, axis))), axis);
	}

	/** Whether this block state is a crankshaft section lined up with the given axis. */
	private static boolean isSectionOn(BlockState state, Axis axis) {
		return state.getBlock() instanceof CrankshaftBlock && state.getValue(HORIZONTAL_AXIS) == axis;
	}

	/**
	 * The crankshaft neither loads the network nor adds capacity to it - it only
	 * relays. Hiding the impact keeps a permanent "0 su" line off its tooltip.
	 */
	@Override
	public boolean hideStressImpact() {
		return true;
	}

	/**
	 * Carries a mined crankcase's bearing condition back into the block.
	 *
	 * <p>Belt to {@code CrankshaftBlockEntity#applyImplicitComponents}'s braces, and
	 * the same pair Create's own Toolbox keeps: the implicit-component path is the
	 * mechanism, and this makes the result independent of when in the placement
	 * sequence it runs. Both are idempotent - they write the same number from the
	 * same stack - so having both costs nothing and guarantees that placing a worn
	 * section really does put a worn section down.
	 */
	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide)
			return;
		if (level.getBlockEntity(pos) instanceof CrankshaftBlockEntity crankshaft) {
			crankshaft.setBearingWear(ECDataComponents.wearOf(stack, ECDataComponents.CRANKSHAFT_BEARING_WEAR));
			// A player who has just discovered the inline limit gets told, once, by
			// the block they placed to find it. The section stays where it was put -
			// this is a joke about hitting an edge, never a way past one.
			if (placer instanceof Player player)
				crankshaft.reportLayoutIfRefused(player);
		}
	}

	@Override
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
		boolean movedByPiston) {
		super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
		// Not server-only. onSurroundingsChanged is safe on both sides and on the
		// client it does one thing: drop the cached flywheel. An engine that grew a
		// cylinder moved its flywheel to the far end of a longer run, and a client
		// still holding the old one would draw and read the wrong generator.
		if (level.getBlockEntity(pos) instanceof CrankshaftBlockEntity crankshaft)
			crankshaft.onSurroundingsChanged();
	}

	/**
	 * Fits one of the engine's two engine-wide parts: the Camshaft, or the Redstone
	 * Control Module.
	 *
	 * <p>Items installed into a placed block, exactly like the Piston Assembly and the
	 * Air Filter: they are parts you plug into an engine, not machines standing beside
	 * it, and the engine is already five blocks tall.
	 *
	 * <p>Either may be fitted through <b>any</b> section of the engine - the flag lands
	 * on the controller regardless of which crankcase was clicked - because a player
	 * building an inline-4 should not have to work out which end runs it. The
	 * <i>engine's</i> answer is checked rather than this section's, so a second
	 * Camshaft cannot be fitted to the far end of an engine that already has one.
	 */
	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof CrankshaftBlockEntity crankshaft))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		if (stack.is(ECItems.CAMSHAFT.get()))
			return fit(crankshaft.engineHasCamshaft(), "gui.camshaft_already_installed",
				crankshaft::installCamshaft, state, level, pos, player, stack);
		if (stack.is(ECItems.REDSTONE_CONTROL_MODULE.get()))
			return fit(crankshaft.engineHasControlModule(), null, crankshaft::installControlModule, state,
				level, pos, player, stack);
		return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
	}

	/**
	 * Installs one engine-wide part, on the server, and charges the player for it.
	 *
	 * <p>Shared by both so the two cannot drift apart in the details that matter - the
	 * creative exemption, the sound, and <b>passing rather than claiming</b> the click
	 * when the engine already has one. Passing is what lets a player holding a spare
	 * still place a block against a finished engine.
	 *
	 * @param already whether the ENGINE already has one. Never this section's own flag:
	 *                a follower answering for itself would let a second part be fitted
	 *                to the far end of an engine that is already equipped
	 * @param busyKey translation key for the status message, or null to say nothing.
	 *                The Camshaft says so because it is mandatory, and a player who
	 *                cannot see where theirs went needs telling
	 */
	private static ItemInteractionResult fit(boolean already, @Nullable String busyKey, Runnable install,
		BlockState state, Level level, BlockPos pos, Player player, ItemStack stack) {
		if (already) {
			if (!level.isClientSide && busyKey != null)
				ECLang.translate(busyKey)
					.style(ChatFormatting.YELLOW)
					.sendStatus(player);
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		}

		if (!level.isClientSide) {
			install.run();
			if (!player.isCreative())
				stack.shrink(1);
			level.playSound(null, pos, state.getSoundType()
				.getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.1F);
		}
		return ItemInteractionResult.sidedSuccess(level.isClientSide);
	}

	/**
	 * The engine's bare-handed interactions.
	 *
	 * <ul>
	 * <li>right-click - works the ignition switch. This is the normal way to start
	 * and stop the engine, and it needs no redstone whatsoever.</li>
	 * <li>sneak + right-click - takes an installed engine-wide part back out and hands
	 * it over, the same gesture that recovers a Piston Assembly or an Air Filter. The
	 * Camshaft comes first when both are fitted, because it is the one a player
	 * services; the Redstone Control Module is optional and rarely removed.</li>
	 * </ul>
	 *
	 * <p>Both require an empty hand, and that is load-bearing rather than
	 * decoration: this method also runs when the player is holding something the
	 * block did not consume, so claiming the interaction unconditionally would stop
	 * a Shaft, a Flywheel or any other block from being placed against the
	 * crankshaft. Passing when the hand is full is what keeps building next to a
	 * running engine possible.
	 *
	 * <p>The value box that selects the control mode cannot swallow either gesture:
	 * it is wrench-only ({@code requiresWrench}), and Create's value UI ignores a
	 * sneaking click in any case.
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
		BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof CrankshaftBlockEntity crankshaft))
			return InteractionResult.PASS;
		if (!player.getMainHandItem()
			.isEmpty())
			return InteractionResult.PASS;

		if (player.isShiftKeyDown())
			return service(crankshaft, state, level, pos, player);

		if (!level.isClientSide)
			crankshaft.toggleIgnitionFor(player);
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	/**
	 * Takes one engine-wide part back out of a stopped engine.
	 *
	 * <p>Pulling the Camshaft out of a turning engine is not a thing anyone does, and
	 * the gate is on <i>rotation</i> rather than on the engine phase - so it covers an
	 * engine being motored by a neighbour and one coasting down after the fuel ran out,
	 * neither of which is "running" and both of which have a valvetrain moving. The
	 * Redstone Control Module is deliberately not covered: it is a control box bolted
	 * to the outside, and unplugging one from a running engine is an ordinary way to
	 * hand the controls back to the switch.
	 */
	private static InteractionResult service(CrankshaftBlockEntity crankshaft, BlockState state, Level level,
		BlockPos pos, Player player) {
		if (crankshaft.hasCamshaft()) {
			if (!crankshaft.getEngineState()
				.isAtRest()) {
				if (!level.isClientSide)
					ECLang.translate("gui.stop_engine_before_servicing")
						.style(ChatFormatting.RED)
						.sendStatus(player);
				// Claimed rather than passed, so the click does not fall through to
				// placing a block against the engine the player was trying to service.
				return InteractionResult.sidedSuccess(level.isClientSide);
			}
			if (!level.isClientSide && crankshaft.removeCamshaft())
				recover(level, pos, state, player, new ItemStack(ECItems.CAMSHAFT.get()));
			return InteractionResult.sidedSuccess(level.isClientSide);
		}

		if (!crankshaft.hasControlModule())
			return InteractionResult.PASS;
		if (!level.isClientSide && crankshaft.removeControlModule())
			recover(level, pos, state, player, new ItemStack(ECItems.REDSTONE_CONTROL_MODULE.get()));
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	/** Hands a removed part back, dropping it only when the inventory is full. */
	private static void recover(Level level, BlockPos pos, BlockState state, Player player, ItemStack recovered) {
		if (!player.getInventory()
			.add(recovered))
			popResource(level, pos, recovered);
		level.playSound(null, pos, state.getSoundType()
			.getBreakSound(), SoundSource.BLOCKS, 0.8F, 0.9F);
	}

	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof CrankshaftBlockEntity crankshaft) {
			// THE ONE CANONICAL DROP PATH for this block's engine-wide parts. A pickaxe,
			// a creative click, a Create Wrench, an explosion and a piston are five ways
			// to reach onRemove, not five behaviours - so the item is emitted here, once,
			// at the moment the block ceases to exist, and no interaction anywhere drops
			// one itself. That is what keeps "install one, get exactly one back" true
			// whichever way the block came apart.
			//
			// Both read THIS SECTION'S OWN flag, never the engine-wide answer. An
			// engine-wide answer here would have every section of an inline-4 drop a
			// part the player only ever crafted one of.
			if (crankshaft.hasControlModule())
				popResource(level, pos, new ItemStack(ECItems.REDSTONE_CONTROL_MODULE.get()));
			if (crankshaft.hasCamshaft())
				popResource(level, pos, new ItemStack(ECItems.CAMSHAFT.get()));
			// Clear the engine and stop the flywheel *before* the block entity goes
			// away, otherwise the flywheel would keep asking a crankshaft that no
			// longer exists until Create's periodic kinetic validation notices.
			crankshaft.onEngineRemoved();
		}
		// KineticBlock#onRemove routes to IBE.onRemove, which destroys the block
		// entity and lets it detach from the kinetic network. Skipping it would
		// strand this position in whatever network it belonged to.
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	public Class<CrankshaftBlockEntity> getBlockEntityClass() {
		return CrankshaftBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends CrankshaftBlockEntity> getBlockEntityType() {
		return ECBlockEntityTypes.CRANKSHAFT.get();
	}

	// The bare-handed click used to print the engine's internal simulation values
	// into chat. That was development output; it is gone, and the gesture now works
	// the ignition switch instead. The engine's state is still read by looking at
	// it - plain hovering information for anyone, full instrumentation through
	// Engineer's Goggles - never by clicking for a chat dump.
}
