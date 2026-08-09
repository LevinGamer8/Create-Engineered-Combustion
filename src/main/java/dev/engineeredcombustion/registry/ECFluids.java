package dev.engineeredcombustion.registry;

import dev.engineeredcombustion.EngineeredCombustion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.function.Consumer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The mod's fluids: gasoline and engine oil.
 *
 * <h2>Why there is no placeable fluid block</h2>
 * Both exist to be piped, stored and consumed, not poured into pools, so no
 * {@code LiquidBlock} is registered and {@link SimpleFluid#createLegacyBlock} returns
 * air - the same arrangement Create uses for its own {@code VirtualFluid}. The
 * fluids still behave normally everywhere it matters: buckets, tanks, pipes and
 * anything else that speaks the NeoForge fluid capability.
 *
 * <p>Adding a world-placeable block later is purely additive: register a
 * {@code LiquidBlock}, pass it to {@code .block(...)} below and drop the
 * {@code createLegacyBlock} override.
 */
public class ECFluids {

	public static final DeferredRegister<FluidType> FLUID_TYPES =
		DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, EngineeredCombustion.ID);
	public static final DeferredRegister<Fluid> FLUIDS =
		DeferredRegister.create(BuiltInRegistries.FLUID, EngineeredCombustion.ID);

	public static final ResourceLocation GASOLINE_STILL_TEXTURE =
		EngineeredCombustion.asResource("block/gasoline_still");
	public static final ResourceLocation GASOLINE_FLOWING_TEXTURE =
		EngineeredCombustion.asResource("block/gasoline_flow");

	public static final DeferredHolder<FluidType, FluidType> GASOLINE_TYPE =
		FLUID_TYPES.register("gasoline", () -> new SimpleFluidType(FluidType.Properties.create()
			.descriptionId("fluid.engineered_combustion.gasoline")
			.density(750)
			.viscosity(600)
			.temperature(300)
			.lightLevel(0)
			.canSwim(false)
			.canDrown(true)
			.supportsBoating(false)
			.canHydrate(false), GASOLINE_STILL_TEXTURE, GASOLINE_FLOWING_TEXTURE));

	public static final DeferredHolder<Fluid, SimpleFluid> FLOWING_GASOLINE =
		FLUIDS.register("flowing_gasoline", () -> new SimpleFluid(properties(), false));

	public static final DeferredHolder<Fluid, SimpleFluid> GASOLINE =
		FLUIDS.register("gasoline", () -> new SimpleFluid(properties(), true));

	public static final ResourceLocation ENGINE_OIL_STILL_TEXTURE =
		EngineeredCombustion.asResource("block/engine_oil_still");
	public static final ResourceLocation ENGINE_OIL_FLOWING_TEXTURE =
		EngineeredCombustion.asResource("block/engine_oil_flow");

	public static final DeferredHolder<FluidType, FluidType> ENGINE_OIL_TYPE =
		FLUID_TYPES.register("engine_oil", () -> new SimpleFluidType(FluidType.Properties.create()
			.descriptionId("fluid.engineered_combustion.engine_oil")
			.density(890)
			.viscosity(2500)
			.temperature(300)
			.lightLevel(0)
			.canSwim(false)
			.canDrown(true)
			.supportsBoating(false)
			.canHydrate(false), ENGINE_OIL_STILL_TEXTURE, ENGINE_OIL_FLOWING_TEXTURE));

	public static final DeferredHolder<Fluid, SimpleFluid> FLOWING_ENGINE_OIL =
		FLUIDS.register("flowing_engine_oil", () -> new SimpleFluid(engineOilProperties(), false));

	public static final DeferredHolder<Fluid, SimpleFluid> ENGINE_OIL =
		FLUIDS.register("engine_oil", () -> new SimpleFluid(engineOilProperties(), true));

	/**
	 * Built lazily and cached. They cannot be plain field initialisers because the
	 * properties reference the very holders declared above.
	 */
	private static BaseFlowingFluid.Properties properties;
	private static BaseFlowingFluid.Properties engineOilProperties;

	private static BaseFlowingFluid.Properties properties() {
		if (properties == null)
			properties = new BaseFlowingFluid.Properties(GASOLINE_TYPE, GASOLINE, FLOWING_GASOLINE)
				.bucket(ECItems.GASOLINE_BUCKET);
		return properties;
	}

	private static BaseFlowingFluid.Properties engineOilProperties() {
		if (engineOilProperties == null)
			engineOilProperties = new BaseFlowingFluid.Properties(ENGINE_OIL_TYPE, ENGINE_OIL, FLOWING_ENGINE_OIL)
				.bucket(ECItems.ENGINE_OIL_BUCKET);
		return engineOilProperties;
	}

	/**
	 * A fluid type that does nothing but tell the client which textures to use.
	 *
	 * <p>NeoForge 21.1 has no {@code RegisterClientExtensionsEvent} - that arrived
	 * in 21.3. On this version the hook is {@code FluidType#initializeClient}, which
	 * is what Create's own {@code AllFluids.TintedFluidType} uses. It is only ever
	 * invoked on the client, so the client-only types referenced inside stay
	 * unloaded on a dedicated server.
	 */
	public static class SimpleFluidType extends FluidType {

		private final ResourceLocation stillTexture;
		private final ResourceLocation flowingTexture;

		public SimpleFluidType(Properties properties, ResourceLocation stillTexture,
			ResourceLocation flowingTexture) {
			super(properties);
			this.stillTexture = stillTexture;
			this.flowingTexture = flowingTexture;
		}

		@Override
		public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
			consumer.accept(new IClientFluidTypeExtensions() {

				@Override
				public ResourceLocation getStillTexture() {
					return stillTexture;
				}

				@Override
				public ResourceLocation getFlowingTexture() {
					return flowingTexture;
				}
			});
		}
	}

	/** Mirrors Create's VirtualFluid: a real fluid with no block form. */
	public static class SimpleFluid extends BaseFlowingFluid {

		private final boolean source;

		public SimpleFluid(Properties properties, boolean source) {
			super(properties);
			this.source = source;
		}

		@Override
		public Fluid getSource() {
			return source ? this : super.getSource();
		}

		@Override
		public Fluid getFlowing() {
			return source ? super.getFlowing() : this;
		}

		@Override
		public boolean isSource(FluidState state) {
			return source;
		}

		/**
		 * Fluid#getAmount is abstract and normally implemented by
		 * BaseFlowingFluid.Source/Flowing, which also add the LEVEL state property.
		 * Neither is meaningful for a fluid that is never placed in the world, so
		 * this mirrors Create's VirtualFluid and reports nothing.
		 */
		@Override
		public int getAmount(FluidState state) {
			return 0;
		}

		@Override
		protected BlockState createLegacyBlock(FluidState state) {
			return Blocks.AIR.defaultBlockState();
		}
	}

	public static void register(IEventBus modEventBus) {
		FLUID_TYPES.register(modEventBus);
		FLUIDS.register(modEventBus);
	}
}
