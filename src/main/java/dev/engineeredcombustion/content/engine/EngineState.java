package dev.engineeredcombustion.content.engine;

/**
 * The authoritative mechanical state of a single engine.
 *
 * <p>This class is intentionally free of any Minecraft, NeoForge or Create
 * types. It is pure engine simulation: it owns the crank angle and the current
 * output speed and knows how to advance itself by one tick. The Create kinetic
 * network is fed from this state by a separate adapter
 * ({@code EngineFlywheelBlockEntity}), never the other way around.
 *
 * <h2>Crank angle</h2>
 * {@link #getCrankAngleDegrees()} is the single source of truth for every
 * mechanical animation in this mod. Piston position, and later combustion
 * timing and firing order, are all derived from it. There is deliberately no
 * separate cosmetic animation timer anywhere in the codebase.
 *
 * <h2>Debug power (milestone 1 only)</h2>
 * There is no combustion yet. {@link #setRunning(boolean)} is driven by a
 * redstone signal, and while running the engine turns at a fixed
 * {@link #DEBUG_TARGET_RPM}. Milestone 2 replaces this by giving the state its
 * own angular velocity, inertia and friction; nothing outside this class needs
 * to change for that, because callers only ever read
 * {@link #getOutputRpm()} and {@link #getCrankAngleDegrees()}.
 */
public final class EngineState {

	/** Fixed rotational output while the debug power source is active, in RPM. */
	public static final float DEBUG_TARGET_RPM = 32.0F;

	private boolean running;
	private float crankAngleDegrees;

	/**
	 * Advances the simulation by exactly one tick.
	 *
	 * <p>Called on the server (authoritative) and on the client (so the renderer
	 * has a locally advancing angle between the rare sync packets). Both sides
	 * run the identical integration from the identical synced starting point.
	 */
	public void tick() {
		if (!running)
			return;
		crankAngleDegrees = normalizeDegrees(crankAngleDegrees + getDegreesPerTick());
	}

	/**
	 * Angular step per game tick.
	 *
	 * <p>rpm * 360 deg/rev / 60 s/min / 20 ticks/s - the same conversion Create
	 * uses in {@code KineticBlockEntity#convertToAngular}.
	 */
	public float getDegreesPerTick() {
		return running ? getOutputRpm() * 360.0F / 60.0F / 20.0F : 0.0F;
	}

	/** Current rotational output of the engine in RPM. 0 while stopped. */
	public float getOutputRpm() {
		return running ? DEBUG_TARGET_RPM : 0.0F;
	}

	/** The authoritative crank angle, always in {@code [0, 360)}. */
	public float getCrankAngleDegrees() {
		return crankAngleDegrees;
	}

	/**
	 * Crank angle interpolated into the current frame. Renderers must use this
	 * instead of re-deriving an angle from wall-clock time.
	 */
	public float getRenderCrankAngleDegrees(float partialTicks) {
		return normalizeDegrees(crankAngleDegrees + getDegreesPerTick() * partialTicks);
	}

	public boolean isRunning() {
		return running;
	}

	/** @return true if the running flag actually changed. */
	public boolean setRunning(boolean running) {
		if (this.running == running)
			return false;
		this.running = running;
		return true;
	}

	public void setCrankAngleDegrees(float crankAngleDegrees) {
		this.crankAngleDegrees = normalizeDegrees(crankAngleDegrees);
	}

	private static float normalizeDegrees(float degrees) {
		float wrapped = degrees % 360.0F;
		return wrapped < 0.0F ? wrapped + 360.0F : wrapped;
	}
}
