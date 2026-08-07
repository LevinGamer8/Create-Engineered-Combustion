package dev.engineeredcombustion.content.engine;

/**
 * How the simulation reaches fuel, without knowing what fuel <i>is</i>.
 *
 * <p>{@link EngineState} must stay free of Minecraft and Create types, so it
 * never sees a fluid, a tank or a carburetor - only these two questions. The
 * crankshaft supplies an implementation backed by the attached Carburetor, and
 * a later milestone can back it with an injector, a fuel rail or anything else
 * without touching the simulation.
 */
public interface FuelSupply {

	/** A carburetor that is missing, empty or holding something that is not fuel. */
	FuelSupply NONE = new FuelSupply() {

		@Override
		public boolean hasFuel() {
			return false;
		}

		@Override
		public boolean consume(int millibuckets) {
			return false;
		}
	};

	/** Whether at least one combustion event's worth of valid fuel is available. */
	boolean hasFuel();

	/**
	 * Attempts to draw fuel for a single combustion event.
	 *
	 * @return true only if the full amount was actually removed. A false return
	 *         means the combustion event must not happen.
	 */
	boolean consume(int millibuckets);
}
