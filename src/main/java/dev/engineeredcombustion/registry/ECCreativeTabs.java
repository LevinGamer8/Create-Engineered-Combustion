package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ECCreativeTabs {

	public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
		DeferredRegister.create(Registries.CREATIVE_MODE_TAB, EngineeredCombustion.ID);

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN =
		CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
			.title(Component.translatable("itemGroup." + EngineeredCombustion.ID + ".main"))
			.icon(() -> new ItemStack(ECItems.CRANKSHAFT.get()))
			// Ordered the way the engine is built and then fuelled: the blocks of
			// the stack, the parts that install into them, then the petroleum chain
			// from the ground up. The sequenced-assembly intermediates are
			// deliberately absent - they are produced by a process, never held.
			.displayItems((parameters, output) -> {
				output.accept(ECItems.CRANKSHAFT.get());
				output.accept(ECItems.CYLINDER.get());
				output.accept(ECItems.PISTON_ASSEMBLY.get());
				output.accept(ECItems.SPARK_PLUG.get());
				output.accept(ECItems.FLYWHEEL.get());
				output.accept(ECItems.CARBURETOR.get());
				output.accept(ECItems.AIR_FILTER.get());
				output.accept(ECItems.OIL_SUMP.get());
				output.accept(ECItems.REDSTONE_CONTROL_MODULE.get());
				output.accept(ECItems.OIL_SHALE.get());
				output.accept(ECItems.CRUSHED_OIL_SHALE.get());
				output.accept(ECItems.PETROLEUM_RESIDUE.get());
				output.accept(ECItems.CRUDE_OIL_BUCKET.get());
				output.accept(ECItems.GASOLINE_BUCKET.get());
				output.accept(ECItems.ENGINE_OIL_BUCKET.get());
			})
			.build());

	public static void register(IEventBus modEventBus) {
		CREATIVE_MODE_TABS.register(modEventBus);
	}
}
