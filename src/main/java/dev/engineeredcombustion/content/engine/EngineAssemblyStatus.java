package dev.engineeredcombustion.content.engine;

/**
 * How much of an engine the world could actually be asked about, and therefore
 * whether the layout just derived may be acted on.
 *
 * <p><b>Only {@link #COMPLETE} is an engine.</b> The other two are the two ways a
 * crank run can fail to be one, and both are deliberately <i>fail-closed</i>: no
 * controller, no combustion, no generated speed and no Stress Capacity. Neither is
 * ever quietly downgraded into a smaller working engine, which is the single
 * failure this enum exists to make impossible.
 *
 * <p>Free of any Minecraft type on purpose, so that {@link EngineLayout} - and the
 * pure simulation tests that drive it - can name the answer without a world.
 */
public enum EngineAssemblyStatus {

	/**
	 * The whole run was visible, both of its ends were genuine block-state answers
	 * rather than unloaded chunks, and it is short enough to be an engine.
	 */
	COMPLETE,

	/**
	 * The scan reached an unloaded chunk before it found an end of the run, so the
	 * layout it derived is a guess about a machine it could not see all of.
	 *
	 * <p>An engine straddling a chunk border must not turn into a shorter engine - or
	 * into two - because a neighbour unloaded, so this suspends the engine rather
	 * than re-deriving it. Transient by construction: it clears itself when the
	 * chunks come back.
	 */
	INCOMPLETE_CHUNKS,

	/**
	 * The run is longer than {@link EngineTuning#MAX_CYLINDERS} sections.
	 *
	 * <p>Takes precedence over {@link #INCOMPLETE_CHUNKS}: a run already proven too
	 * long cannot become short enough by loading more of it, so this is a stable
	 * answer rather than a provisional one.
	 */
	OVERSIZED;

	/** Whether this layout may be run as an engine. */
	public boolean isUsable() {
		return this == COMPLETE;
	}
}
