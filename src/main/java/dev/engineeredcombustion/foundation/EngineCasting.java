package dev.engineeredcombustion.foundation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block whose <i>appearance</i> is knitted to the engine around it.
 *
 * <h2>Why an engine needs this at all</h2>
 * An inline-4 is one machine: one crankshaft, one Carburetor, one Oil Sump, one
 * Flywheel. Built out of four repetitions of the same three blocks it did not
 * look like one - it looked like four one-cylinder engines standing shoulder to
 * shoulder, because nothing in the geometry crossed a seam. The casting that
 * makes it one machine - the top deck, the joint band, the oil gallery, the main
 * bearing caps, the cooling fins and the shared intake manifold - has to know
 * which of its neighbours belong to the same engine.
 *
 * <p>That knowledge is kept in <b>cosmetic block state properties</b>, computed
 * from the neighbouring block states and nothing else. Which is worth being
 * precise about, because there was an easier-looking option: resolving the engine
 * in each block entity renderer and drawing the connecting parts there. Block
 * states win on all three counts that matter here.
 *
 * <ul>
 * <li><b>Cost.</b> A block state is baked into the chunk mesh once and drawn for
 * free thereafter. A renderer would rebuild the same static geometry every frame,
 * for every cylinder, forever - to draw a manifold that never moves.</li>
 * <li><b>Lighting.</b> Baked geometry gets the chunk's ambient occlusion and
 * smooth lighting. Renderer geometry does not, and a manifold lit differently
 * from the head it is bolted to reads as a separate object - which is exactly the
 * impression being fixed.</li>
 * <li><b>Ponder.</b> A Ponder scene is a structure file: block states, no block
 * entities to resolve. Putting the appearance in the state is what lets the
 * tutorial show the engine the player will actually build.</li>
 * </ul>
 *
 * <h2>The rule these all follow</h2>
 * {@link #castingState} is a pure function of the block states within one step,
 * and it is called from three places that must agree: on placement, from
 * {@code updateShape} whenever a neighbour changes, and from
 * {@link #refresh} when a crankshaft first ticks. Nothing caches it, nothing
 * serialises it beyond the block state itself, and nothing in the simulation
 * reads it - {@code EngineComponents} resolves the real engine from the
 * crankshaft's axis every time it is asked.
 */
public interface EngineCasting {

	/**
	 * This block's cosmetic state for the engine currently around it.
	 *
	 * @param level  read no further than one block away. {@code updateShape} runs
	 *               during chunk loading and world generation, where a distant
	 *               lookup can mean loading a chunk from inside a chunk load
	 * @param state  the state to knit, whose non-cosmetic properties are kept
	 * @return {@code state} itself when nothing needs to change, so callers can
	 *         compare by identity and skip the write
	 */
	BlockState castingState(LevelReader level, BlockPos pos, BlockState state);

	/**
	 * Brings one block's cosmetic state back in line with its neighbours.
	 *
	 * <p>For worlds saved before a piece of this geometry existed. The properties
	 * are maintained by {@code updateShape}, which only runs when a neighbour
	 * changes - so an engine that was already standing would keep the state it was
	 * saved with, and its shared castings would stay unbuilt until the player
	 * happened to disturb a block next to it. A crankshaft calls this for its own
	 * section and its stack on the first tick after it loads; see
	 * {@code CrankshaftBlockEntity#knitCastings}.
	 *
	 * <p>Costs nothing when there is nothing to do: an engine that is already
	 * knitted computes the state it already has and the write is skipped.
	 */
	static void refresh(LevelAccessor level, BlockPos pos) {
		if (!level.hasChunkAt(pos))
			return;
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof EngineCasting casting))
			return;
		BlockState knitted = casting.castingState(level, pos, state);
		if (knitted != state)
			// Clients only: this changes how the block looks and nothing else, so
			// there is no reason to wake the neighbours' redstone or comparators.
			// Their own casting states are refreshed by the same caller.
			level.setBlock(pos, knitted, Block.UPDATE_CLIENTS);
	}
}
