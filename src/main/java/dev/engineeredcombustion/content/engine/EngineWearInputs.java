package dev.engineeredcombustion.content.engine;

/**
 * The physical condition of one engine, as the simulation sees it at the start
 * of a tick.
 *
 * <p>An immutable snapshot, resolved from the blocks the engine is actually made
 * of - each Crankshaft section's own bearing wear, each installed Piston
 * Assembly's own wear, and whether an Air Filter is fitted. Like
 * {@link EngineInputs} it is free of any Minecraft, NeoForge or Create type:
 * reading it out of the world is the block entity's business, and the simulation
 * only ever sees numbers.
 *
 * <h2>Why wear arrives as an input rather than living here</h2>
 * Wear belongs to <b>parts</b>, not to the engine. A Crankshaft section keeps
 * its bearing wear when it is mined and carries it back when it is placed again;
 * a Piston Assembly keeps its wear when it is pulled out of a bore. If the
 * simulation owned these numbers they would belong to the controller, and every
 * controller migration - adding a section at the negative end, cutting an engine
 * in half - would move or reset wear that has nothing to do with which block
 * happens to be running the engine.
 *
 * <p>So the flow is one-directional and explicit: the parts are read into this
 * snapshot before the tick, the simulation uses it and never writes to it, and
 * the wear the tick <i>produced</i> is applied back to the parts afterwards - see
 * {@code CrankshaftBlockEntity#accumulateWear}. Nothing accumulated this tick can
 * retroactively change a power stroke that already happened.
 *
 * <h2>Average or worst?</h2>
 * Both, for different questions. The engine's <b>friction</b> comes from the
 * average, because four bearings share one crankshaft and it is the sum of their
 * drag that the flywheel fights - and because using the worst would make an
 * inline-4 with one tired section behave like an engine with four of them. The
 * <b>diagnostics</b> report the worst, because that is the section the player
 * needs to go and look at.
 */
public final class EngineWearInputs {

	/**
	 * A brand-new, fully filtered engine.
	 *
	 * <p>The right answer wherever wear has not been resolved: a call site from
	 * before this milestone, a pure test that is not about wear, and an engine the
	 * client has heard nothing about yet. Shared and never mutated - the class has
	 * no mutators at all, which is what makes sharing it safe.
	 */
	public static final EngineWearInputs PRISTINE = new EngineWearInputs(null, 0.0F, 0.0F, true);

	private final float[] pistonWear;
	private final float averageBearingWear;
	private final float worstBearingWear;
	private final boolean airFilterInstalled;

	/**
	 * @param pistonWear         wear of the Piston Assembly in each cylinder,
	 *                           indexed by cylinder. Copied defensively, padded
	 *                           with zeros and clamped, so the caller may reuse its
	 *                           array and a short one is not an error. Null means
	 *                           every cylinder is pristine
	 * @param averageBearingWear mean bearing wear over the engine's crankshaft
	 *                           sections - what its friction is derived from
	 * @param worstBearingWear   the tiredest single section - what the diagnostics
	 *                           point the player at
	 * @param airFilterInstalled whether the Carburetor has an Air Filter on it
	 */
	public EngineWearInputs(float[] pistonWear, float averageBearingWear, float worstBearingWear,
		boolean airFilterInstalled) {
		this.pistonWear = new float[EngineTuning.MAX_CYLINDERS];
		if (pistonWear != null)
			for (int cylinder = 0; cylinder < this.pistonWear.length && cylinder < pistonWear.length; cylinder++)
				this.pistonWear[cylinder] = EngineWearMath.clampWear(pistonWear[cylinder]);
		this.averageBearingWear = EngineWearMath.clampWear(averageBearingWear);
		this.worstBearingWear = EngineWearMath.clampWear(worstBearingWear);
		this.airFilterInstalled = airFilterInstalled;
	}

	/** A snapshot with nothing but bearing wear known: what the client is told. */
	public static EngineWearInputs ofBearings(float averageBearingWear) {
		return new EngineWearInputs(null, averageBearingWear, averageBearingWear, true);
	}

	/** Wear of the Piston Assembly in this cylinder. 0 for anything out of range. */
	public float pistonWear(int cylinder) {
		return cylinder >= 0 && cylinder < pistonWear.length ? pistonWear[cylinder] : 0.0F;
	}

	/**
	 * How much of its compression this cylinder still has, {@code [MIN, 1]}.
	 *
	 * <p><b>The</b> multiplier a worn cylinder is worth - to its combustion torque,
	 * to its share of Create's Stress Capacity, and to how hard the engine is to
	 * start. See {@link EngineWearMath#compressionEfficiency(float)}.
	 */
	public float compressionEfficiency(int cylinder) {
		return EngineWearMath.compressionEfficiency(pistonWear(cylinder));
	}

	/**
	 * Mean compression over the first {@code cylinderCount} cylinders: how healthy
	 * the engine is as a thing to be started, in one number.
	 */
	public float averageCompressionEfficiency(int cylinderCount) {
		int count = Math.min(Math.max(cylinderCount, 1), pistonWear.length);
		float total = 0.0F;
		for (int cylinder = 0; cylinder < count; cylinder++)
			total += compressionEfficiency(cylinder);
		return total / count;
	}

	/** Mean bearing wear over the engine's sections - what its friction comes from. */
	public float averageBearingWear() {
		return averageBearingWear;
	}

	/** The tiredest single section - what the diagnostics report. */
	public float worstBearingWear() {
		return worstBearingWear;
	}

	/** How much harder this engine is to turn than a new one. */
	public float bearingFrictionMultiplier() {
		return EngineWearMath.bearingFrictionMultiplier(averageBearingWear);
	}

	public boolean airFilterInstalled() {
		return airFilterInstalled;
	}

	/** The engine's mechanical condition, from its average bearing wear. */
	public WearCondition mechanicalCondition() {
		return WearCondition.of(averageBearingWear);
	}

	/** The condition of this cylinder's compression. */
	public WearCondition compressionCondition(int cylinder) {
		return WearCondition.of(pistonWear(cylinder));
	}

	/**
	 * The engine's condition as one word: the <b>worst</b> of its mechanical
	 * condition and any one cylinder's compression.
	 *
	 * <p>Deliberately the worst rather than an average. An inline-4 with three
	 * perfect cylinders and one at the service limit is an engine that needs a
	 * piston, and averaging would report it as lightly used - which is precisely
	 * the "43 % health" reading this milestone exists to avoid.
	 */
	public WearCondition overallCondition(int cylinderCount) {
		WearCondition condition = mechanicalCondition();
		int count = Math.min(Math.max(cylinderCount, 1), pistonWear.length);
		for (int cylinder = 0; cylinder < count; cylinder++)
			condition = WearCondition.worst(condition, compressionCondition(cylinder));
		return condition;
	}
}
