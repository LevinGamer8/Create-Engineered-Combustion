package dev.engineeredcombustion.content.engine;

/**
 * Every equation that turns operating conditions into wear, and wear back into
 * mechanical consequences.
 *
 * <p>Pure: no Minecraft, no NeoForge, no Create, no state. It sits beside
 * {@link EngineState} on the simulation side of the boundary, and it is the
 * single home of the wear model - block entities own <i>where</i> wear is stored
 * and <i>when</i> it is written, and nothing outside this class computes how
 * much of it there is.
 *
 * <h2>Two directions</h2>
 * <dl>
 * <dt>Conditions to wear</dt>
 * <dd>{@link #bearingWearPerRevolution}, {@link #cylinderWearPerRevolution} and
 * {@link #cylinderWearPerCombustion}. All three are quoted <b>per revolution</b>
 * or <b>per combustion event</b>, never per tick, so wear follows the work the
 * machine actually did rather than how long a server happened to run.</dd>
 * <dt>Wear to consequences</dt>
 * <dd>{@link #compressionEfficiency} and {@link #bearingFrictionMultiplier}.
 * Both feed the physics the engine already solves - combustion torque and
 * friction torque - so that everything a player notices about a worn engine
 * emerges from the same equilibrium a healthy one settles into. Nothing here
 * subtracts RPM, blocks a start, or gates an engine on a threshold.</dd>
 * </dl>
 *
 * <p>The numbers themselves all live in {@link EngineTuning}. This class is
 * shapes, not values.
 */
public final class EngineWearMath {

	private EngineWearMath() {
	}

	// --- the domain ----------------------------------------------------------

	/**
	 * Wear, clamped to the range every part of this system agrees on.
	 *
	 * <p>0 is a part fresh out of the crate, 1 is a part at its service limit.
	 * Applied on every read as well as every write, because wear can arrive from
	 * outside the simulation - an old world, a hand-edited item stack, a data pack
	 * - and none of those are programming errors to be asserted against.
	 */
	public static float clampWear(float wear) {
		if (Float.isNaN(wear))
			return 0.0F;
		return wear < 0.0F ? 0.0F : wear > 1.0F ? 1.0F : wear;
	}

	// --- wear to consequences -----------------------------------------------

	/**
	 * How much of its compression a cylinder with this much piston wear still has,
	 * as a fraction of a healthy one.
	 *
	 * <pre>
	 * efficiency = 1 - LINEAR * wear - QUADRATIC * wear^2
	 * </pre>
	 *
	 * <p>Smooth, strictly decreasing, 1.0 when pristine and
	 * {@link EngineTuning#MIN_COMPRESSION_EFFICIENCY} at the service limit. It is
	 * <b>the</b> multiplier a worn cylinder is worth, and it is used for exactly
	 * three things, all of which have to move together or the engine would be
	 * lying somewhere:
	 * <ul>
	 * <li>the torque a charge burning in that cylinder delivers, whether the engine
	 * is running or being started;</li>
	 * <li>the share of Stress Capacity that cylinder contributes to Create;</li>
	 * <li>how much harder the engine is to start, through the aggregate.</li>
	 * </ul>
	 *
	 * <p>Never zero and never negative. A critically worn cylinder is a weak
	 * cylinder, not a dead one - it still fires, still burns its charge, and still
	 * appears in the active cylinder mask.
	 */
	public static float compressionEfficiency(float pistonWear) {
		float wear = clampWear(pistonWear);
		float efficiency = 1.0F - EngineTuning.COMPRESSION_LOSS_LINEAR * wear
			- EngineTuning.COMPRESSION_LOSS_QUADRATIC * wear * wear;
		return Math.max(EngineTuning.MIN_COMPRESSION_EFFICIENCY, efficiency);
	}

	/**
	 * How much harder a worn engine is to turn, as a multiple of its healthy
	 * internal friction.
	 *
	 * <pre>
	 * multiplier = 1 + LINEAR * wear + QUADRATIC * wear^2
	 * </pre>
	 *
	 * <p>In the engine's <i>average</i> bearing wear - see
	 * {@link EngineWearInputs#averageBearingWear()} for why the average rather than
	 * the worst - from 1.0 pristine to
	 * {@code 1 + MAX_EXTRA_BEARING_FRICTION} at the service limit.
	 *
	 * <p>Mostly quadratic, so a healthy engine pays essentially nothing and a
	 * critically worn one pays all of it. That is the same shape as
	 * {@link #compressionEfficiency(float)} and for the same reason: wear must be a
	 * consequence of abuse, not a slow tax on playing normally.
	 *
	 * <p>Multiplies the friction torque the engine already fights, so the
	 * consequences are the ones a real worn engine has and none of them had to be
	 * written down: less reserve torque, a lower speed under the same load, a
	 * shorter coast-down, a harder start, and more combustion events - so more fuel
	 * - to hold any given speed.
	 */
	public static float bearingFrictionMultiplier(float averageBearingWear) {
		float wear = clampWear(averageBearingWear);
		return 1.0F + EngineTuning.BEARING_FRICTION_LINEAR * wear
			+ EngineTuning.BEARING_FRICTION_QUADRATIC * wear * wear;
	}

	// --- operating conditions to wear rate ----------------------------------

	/**
	 * Wear multiplier for a lubrication state. Applies to bearings and cylinders
	 * alike: oil is what keeps metal off metal in both places.
	 */
	public static float lubricationWearMultiplier(LubricationState lubrication) {
		return switch (lubrication) {
			case NORMAL -> EngineTuning.WEAR_MULTIPLIER_OIL_NORMAL;
			case LOW -> EngineTuning.WEAR_MULTIPLIER_OIL_LOW;
			case DRY -> EngineTuning.WEAR_MULTIPLIER_OIL_DRY;
		};
	}

	/**
	 * Wear multiplier for the intake. Cylinders only - unfiltered air is abrasive
	 * to rings and bores and has nothing to do with the main bearings.
	 */
	public static float filtrationWearMultiplier(boolean airFilterInstalled) {
		return airFilterInstalled ? 1.0F : EngineTuning.WEAR_MULTIPLIER_UNFILTERED;
	}

	/**
	 * How much harder the engine is on itself at this speed, on top of simply
	 * completing more revolutions.
	 *
	 * <pre>
	 * stress    = min(1, |rpm| / RATED)
	 * overspeed = max(0, |rpm| - RATED) / RATED
	 * factor    = 1 + RPM_STRESS * stress^2 + OVERSPEED * overspeed^2
	 * </pre>
	 *
	 * <p>Two quadratics that meet at the rated speed. The factor is continuous
	 * there - smooth to seven digits either side - and the overspeed term starts
	 * from zero slope, so an engine that ticks one RPM over its rating is charged
	 * about a sixth of a percent extra and there is no threshold anywhere for a
	 * governor oscillation to trip.
	 *
	 * <p>The slope does step at the join, by about 0.0036 per RPM, because the
	 * stress term stops growing where it clamps. That step is <i>downwards</i> -
	 * the penalty briefly gets cheaper per RPM as the engine passes its rating,
	 * before the overspeed term takes over - so it is a kink in the player's
	 * favour and not a cliff. Removing it would mean letting the stress term keep
	 * climbing above the rating, which would double-count the same overspeed the
	 * second term already charges for.
	 *
	 * <p>Below the rating the penalty is mild by design - about 1.04x at idle,
	 * 1.35x flat out - because the per-revolution accounting has already charged a
	 * fast engine three times as much as a slow one. Above it the second term
	 * takes over hard, which is what makes an engine geared up by a network far
	 * stronger than itself the fastest way to destroy one, and after the 13.1
	 * rebalance the only way to destroy a well-lubricated one at all.
	 *
	 * <p>Uses the <b>mechanical</b> speed, always. An engine being motored at 220
	 * RPM is really turning at 220 RPM, whatever it thinks it is generating.
	 */
	public static float rpmWearFactor(float mechanicalRpm) {
		float rpm = Math.abs(mechanicalRpm);
		float stress = Math.min(1.0F, rpm / EngineTuning.RATED_CONTINUOUS_RPM);
		float overspeed = overspeedFraction(rpm);
		return 1.0F + EngineTuning.RPM_STRESS_COEFFICIENT * stress * stress
			+ EngineTuning.OVERSPEED_WEAR_COEFFICIENT * overspeed * overspeed;
	}

	/** How far past its rated speed the engine is turning, as a fraction. 0 below it. */
	public static float overspeedFraction(float mechanicalRpm) {
		float over = Math.abs(mechanicalRpm) - EngineTuning.RATED_CONTINUOUS_RPM;
		return over <= 0.0F ? 0.0F : over / EngineTuning.RATED_CONTINUOUS_RPM;
	}

	/** Whether the goggles should be warning the player about overspeed right now. */
	public static boolean isOverspeed(float mechanicalRpm) {
		return overspeedFraction(mechanicalRpm) > EngineTuning.OVERSPEED_WARNING_MARGIN;
	}

	/** Whether the goggles should be warning the player that the engine is working hard. */
	public static boolean isHeavyLoad(float loadFactor) {
		return loadFactor >= EngineTuning.HEAVY_LOAD_WARNING_FACTOR;
	}

	/**
	 * Extra bearing wear from the work the engine is doing, linear in the
	 * normalised load factor Create's network already gives us.
	 *
	 * <p>The bearings carry the load, so they feel it: 1.0 unloaded, 1.9 at
	 * capacity. Deliberately not the dominant term - an engine is worn out by
	 * turning, and load only makes each turn cost more.
	 */
	public static float bearingLoadWearFactor(float loadFactor) {
		return 1.0F + EngineTuning.clamp01(loadFactor) * EngineTuning.BEARING_LOAD_WEAR_COEFFICIENT;
	}

	/** The same, in the bore, where load matters much less than contamination does. */
	public static float cylinderLoadWearFactor(float loadFactor) {
		return 1.0F + EngineTuning.clamp01(loadFactor) * EngineTuning.CYLINDER_LOAD_WEAR_COEFFICIENT;
	}

	// --- the three wear rates ------------------------------------------------

	/**
	 * Bearing wear one crankshaft section takes per revolution under these
	 * conditions.
	 *
	 * <p><b>Per section, not per engine.</b> Each section carries its own journal
	 * and its own load, so an inline-4 does not wear four times as fast as an
	 * inline-1 - it has four sections each wearing at their own rate, which is what
	 * lets one of them be tired while the rest are fine.
	 *
	 * <p>Charged for rotation of any origin. An engine being motored by another
	 * Create source is doing exactly the same mechanical work on its bearings as
	 * one running under its own power, and a dry one being motored is destroying
	 * them just as fast.
	 */
	public static float bearingWearPerRevolution(LubricationState lubrication, float mechanicalRpm,
		float loadFactor) {
		return EngineTuning.BASE_BEARING_WEAR_PER_REVOLUTION * lubricationWearMultiplier(lubrication)
			* rpmWearFactor(mechanicalRpm) * bearingLoadWearFactor(loadFactor);
	}

	/**
	 * Piston and ring wear from the piston moving in its bore, per revolution.
	 *
	 * <p>Charged to any cylinder with a Piston Assembly fitted, firing or not -
	 * the rings are being dragged up and down the bore either way, and the engine
	 * is pumping air past them either way, which is why filtration applies here
	 * too.
	 */
	public static float cylinderWearPerRevolution(LubricationState lubrication, float mechanicalRpm, float loadFactor,
		boolean airFilterInstalled) {
		return EngineTuning.BASE_CYLINDER_WEAR_PER_REVOLUTION * lubricationWearMultiplier(lubrication)
			* filtrationWearMultiplier(airFilterInstalled) * rpmWearFactor(mechanicalRpm)
			* cylinderLoadWearFactor(loadFactor);
	}

	/**
	 * Piston and ring wear from one charge that <b>actually burned</b> in that
	 * cylinder.
	 *
	 * <p>The combustion-only half of cylinder wear, and the half an engine that is
	 * not burning anything must never accumulate. It is charged from the same
	 * event that consumed the fuel and delivered the torque, so it cannot be
	 * claimed by an engine being spun by its neighbour however fast the pistons
	 * are moving.
	 */
	public static float cylinderWearPerCombustion(LubricationState lubrication, float mechanicalRpm, float loadFactor,
		boolean airFilterInstalled) {
		return EngineTuning.CYLINDER_WEAR_PER_COMBUSTION * lubricationWearMultiplier(lubrication)
			* filtrationWearMultiplier(airFilterInstalled) * rpmWearFactor(mechanicalRpm)
			* cylinderLoadWearFactor(loadFactor);
	}

	// --- output ---------------------------------------------------------------

	/**
	 * Quantises a capacity figure to the step Create is allowed to see it move in.
	 *
	 * @see EngineTuning#CAPACITY_QUANTUM
	 */
	public static float quantiseCapacity(float capacityFactor) {
		return Math.round(capacityFactor / EngineTuning.CAPACITY_QUANTUM) * EngineTuning.CAPACITY_QUANTUM;
	}

	/** Quantises a wear figure to the step the client is told about it in. */
	public static float quantiseWear(float wear) {
		return Math.round(clampWear(wear) / EngineTuning.WEAR_SYNC_QUANTUM) * EngineTuning.WEAR_SYNC_QUANTUM;
	}

	/**
	 * How loudly a mechanically worn engine should chatter, {@code [0, 1]}.
	 *
	 * <p>Zero until the bearings are {@link WearCondition#WORN}, then ramping to
	 * full at the service limit, so a healthy engine sounds exactly as it always
	 * did and a tired one is audibly tired without a new sound asset.
	 */
	public static float mechanicalRoughness(float averageBearingWear) {
		float wear = clampWear(averageBearingWear);
		if (wear <= EngineTuning.CONDITION_WORN_WEAR)
			return 0.0F;
		return (wear - EngineTuning.CONDITION_WORN_WEAR) / (1.0F - EngineTuning.CONDITION_WORN_WEAR);
	}
}
