package dev.engineeredcombustion.content.engine;

/**
 * The engine's view of its oil supply.
 *
 * <p>The same arrangement as {@link FuelSupply}: no Minecraft type appears here,
 * so {@link EngineState} never learns what a fluid or a block entity is. It asks
 * how well it is lubricated and occasionally asks to burn a little oil; whether
 * that is a tank, a pipe network or a test harness on the other side is not its
 * concern.
 */
public interface OilSupply {

	/** Lubrication quality right now. Never null. */
	LubricationState lubrication();

	/**
	 * Draws oil for wear and burn-off.
	 *
	 * @return true only when the full amount was actually removed; a partial draw
	 *         must be refused outright so the tank can never go negative
	 */
	boolean consume(int millibuckets);

	/** An engine with no oil sump attached at all. */
	OilSupply NONE = new OilSupply() {

		@Override
		public LubricationState lubrication() {
			return LubricationState.DRY;
		}

		@Override
		public boolean consume(int millibuckets) {
			return false;
		}
	};
}
