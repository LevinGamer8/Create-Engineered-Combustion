package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The engine's sound events.
 *
 * <p>All of them are created with {@code createVariableRangeEvent}, the same
 * call Create uses for its own machine sounds. A variable-range event takes its
 * audible radius from the volume the sound is played at rather than baking a
 * fixed range into the registry, which is what lets a quiet idle be a local
 * sound while the start-up carries further.
 *
 * <p>The asset each event points at is declared in
 * {@code assets/engineered_combustion/sounds.json}; the two must agree on the
 * subtitle keys, which live in the language files.
 */
public class ECSounds {

	public static final DeferredRegister<SoundEvent> SOUNDS =
		DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, EngineeredCombustion.ID);

	/** Looped while the engine is turned over without running under its own power. */
	public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_CRANKING = register("engine_cranking");

	/** One cough per pre-start firing opportunity, i.e. per point of start progress. */
	public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_FIRE_ATTEMPT = register("engine_fire_attempt");

	/** Fires once, on the STARTING to RUNNING transition. */
	public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_START = register("engine_start");

	/** Looped for as long as the engine is producing or carrying its own rotation. */
	public static final DeferredHolder<SoundEvent, SoundEvent> ENGINE_RUNNING = register("engine_running");

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
