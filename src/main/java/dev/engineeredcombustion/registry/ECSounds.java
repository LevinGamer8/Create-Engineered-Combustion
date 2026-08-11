package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The engine's sound events, in the two layers the engine actually has.
 *
 * <p>All of them are created with {@code createVariableRangeEvent}, the same
 * call Create uses for its own machine sounds. A variable-range event takes its
 * audible radius from the volume the sound is played at rather than baking a
 * fixed range into the registry, which is what lets a quiet idle be a local
 * sound while the start-up carries further.
 *
 * <p>The asset each event points at is declared in
 * {@code assets/engineered_combustion/sounds.json}; the two must agree on the
 * subtitle keys, which live in the language files. Every asset is synthesised
 * from noise, impulses and filters by {@code tools/generate_sounds.py} - there is
 * no sampled or recorded material anywhere in this mod.
 */
public class ECSounds {

	public static final DeferredRegister<SoundEvent> SOUNDS =
		DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, EngineeredCombustion.ID);

	/**
	 * The mechanical layer: crankshaft, bearings, flywheel, and the piston pumping
	 * against compression.
	 *
	 * <p>Looped for as long as the crank turns - being cranked, running, coasting,
	 * or motored by another engine - and pitched by mechanical RPM. It contains no
	 * combustion whatsoever, which is exactly what lets a fuel-starved engine that
	 * is still being spun sound like the dead weight it is.
	 */
	public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_MECHANICAL = register("engine_mechanical");

	/**
	 * One charge burning: the engine's voice, and the whole of its rhythm.
	 *
	 * <p>Played once per real combustion event, from the server-authoritative
	 * counter - never from a timer and never inferred from the crank angle.
	 *
	 * <p>Three interchangeable recordings sit behind this one event
	 * ({@code engine_fire_1..3} in {@code sounds.json}), so Minecraft picks one per
	 * play exactly as it does for footsteps; the caller adds a little pitch and
	 * volume spread on top. Both halves of that matter, because a single-cylinder
	 * engine is never metronomic and one sample on a metronome is precisely what
	 * that sounds like.
	 */
	public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_FIRE = register("engine_fire");

	/**
	 * The aggregate of firing events for an engine that fires too fast to hear
	 * individually.
	 *
	 * <p>Silent for the current engine, whose 3.2 Hz at full throttle is far below
	 * the threshold. It exists so a faster engine, a four-stroke or a second
	 * cylinder is a tuning change rather than an audio rewrite - see
	 * {@code EngineTuning#combustionLoopBlend}.
	 */
	public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_COMBUSTION_LOOP =
		register("engine_combustion_loop");

	/**
	 * The ignition coil discharging: a tiny electrical tick, played once per
	 * spark event while the engine is not yet running.
	 *
	 * <p>Deliberately not a combustion sound. A spark happens whether or not
	 * there is any fuel to light, so it has to be audibly the wrong thing to
	 * mistake for a charge burning.
	 */
	public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_SPARK = register("engine_spark");

	/** Fires once, on the STARTING to RUNNING transition. */
	public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_START = register("engine_start");

	/** The engine died against the player's wishes: out of fuel, or dragged below stall speed. */
	public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_STALL = register("engine_stall");

	/** The engine was shut down deliberately - ignition off, and it coasted to a halt. */
	public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_STOP = register("engine_stop");

	private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
		ResourceLocation id = EngineeredCombustion.asResource(name);
		return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
	}

	public static void register(IEventBus modEventBus) {
		SOUNDS.register(modEventBus);
	}
}
