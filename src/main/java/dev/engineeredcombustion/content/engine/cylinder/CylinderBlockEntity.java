package dev.engineeredcombustion.content.engine.cylinder;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import dev.engineeredcombustion.content.engine.CrankMath;
import dev.engineeredcombustion.content.engine.EngineComponents;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import dev.engineeredcombustion.foundation.ECLang;
import dev.engineeredcombustion.registry.ECBlockEntityTypes;
import dev.engineeredcombustion.registry.ECItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds which of the cylinder's two installable parts are fitted: the Piston
 * Assembly in the bore, and the Spark Plug in the head.
 *
 * <p>Both are <i>items</i> installed into a placed cylinder rather than blocks
 * of their own, because both occupy the same physical volume the cylinder does.
 * Neither has any independent state worth storing: the piston's position is
 * derived from the crankshaft's crank angle, and the plug either is there or is
 * not.
 *
 * <p>The two flags mean quite different things to the engine, and the difference
 * is the whole of this milestone. The piston is <i>structural</i> - without it
 * the engine cannot turn at all - so it feeds
 * {@code EngineComponents#isMechanicallyValid}. The plug is not: an engine
 * missing its plug is mechanically perfect and will be motored happily by any
 * other Create source. It simply never lights a charge.
 */
public class CylinderBlockEntity extends BlockEntity implements IHaveGoggleInformation {

	private static final String KEY_PISTON_INSTALLED = "PistonInstalled";
	private static final String KEY_SPARK_PLUG_INSTALLED = "SparkPlugInstalled";

	private boolean pistonInstalled;
	private boolean sparkPlugInstalled;

	/** Client-side render cache only; never used for game logic. */
	@Nullable
	private CrankshaftBlockEntity cachedCrankshaft;

	public CylinderBlockEntity(BlockPos pos, BlockState state) {
		super(ECBlockEntityTypes.CYLINDER.get(), pos, state);
	}

	public boolean hasPistonAssembly() {
		return pistonInstalled;
	}

	/** @return false when a piston assembly is already installed. */
	public boolean installPistonAssembly() {
		if (pistonInstalled)
			return false;
		setPistonInstalled(true);
		return true;
	}

	/** @return false when there was nothing to remove. */
	public boolean removePistonAssembly() {
		if (!pistonInstalled)
			return false;
		setPistonInstalled(false);
		return true;
	}

	public boolean hasSparkPlug() {
		return sparkPlugInstalled;
	}

	/** @return false when a spark plug is already installed. */
	public boolean installSparkPlug() {
		if (sparkPlugInstalled)
			return false;
		sparkPlugInstalled = true;
		onInstalledPartsChanged();
		return true;
	}

	/** @return false when there was nothing to remove. */
	public boolean removeSparkPlug() {
		if (!sparkPlugInstalled)
			return false;
		sparkPlugInstalled = false;
		onInstalledPartsChanged();
		return true;
	}

	private void setPistonInstalled(boolean installed) {
		pistonInstalled = installed;
		onInstalledPartsChanged();
	}

	/**
	 * Publishes a change to either installed part.
	 *
	 * <p>Both need exactly the same treatment and for the same reason: fitting or
	 * pulling a part changes what the engine can do without changing any block
	 * state, so the client has to be told (it draws both parts) and the crankshaft
	 * has to be told (it decides whether the engine may turn, and now whether it
	 * may spark). Neither would ever hear about it otherwise.
	 */
	private void onInstalledPartsChanged() {
		setChanged();
		if (level == null || level.isClientSide)
			return;
		level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		if (level.getBlockEntity(EngineComponents.crankshaftPosFromCylinder(worldPosition)) instanceof CrankshaftBlockEntity crankshaft)
			crankshaft.onSurroundingsChanged();
	}

	/**
	 * Crank angle driving this cylinder's piston, interpolated into the current
	 * frame. Returns 0 when there is no crankshaft below.
	 *
	 * <p>This is the mechanism that keeps the animation honest: there is no local
	 * animation counter here, the piston reads the same crank angle the engine
	 * simulation advances.
	 */
	public float getCrankAngleForRender(float partialTicks) {
		CrankshaftBlockEntity crankshaft = getCrankshaft();
		if (crankshaft == null)
			return 0.0F;
		// This cylinder's own angle: the engine's one master crank angle plus the
		// phase its throw sits at. On an inline-4 that is what puts cylinder 1 near
		// top dead centre while cylinder 3 is near the bottom - four pistons moving
		// from one number, so they can never drift out of step with each other or
		// with the crank webs the player can see turning underneath them.
		return crankshaft.getEngineState()
			.getLocalRenderCrankAngleDegrees(crankshaft.getCylinderIndex(), partialTicks);
	}

	/**
	 * How brightly the combustion chamber should be drawn this frame, 0 when
	 * nothing is burning.
	 *
	 * <p>Read from the crankshaft rather than tracked here, because the crank
	 * angle and the simulation state that decide it both live there - a second
	 * copy in the cylinder could only ever drift from the first.
	 */
	public float getCombustionFlashIntensity(float partialTicks) {
		CrankshaftBlockEntity crankshaft = getCrankshaft();
		return crankshaft == null ? 0.0F
			: crankshaft.getEngineState()
				.getCombustionFlashIntensity(crankshaft.getCylinderIndex(), partialTicks);
	}

	/**
	 * Which way the crankshaft below runs, so the renderer knows which plane the
	 * connecting rod swings in. Falls back to the axis the models are authored
	 * for when there is no crankshaft; with nothing to drive it the rod is
	 * vertical anyway, so the choice is invisible.
	 */
	public Direction.Axis getEngineAxisForRender() {
		CrankshaftBlockEntity crankshaft = getCrankshaft();
		return crankshaft == null ? Direction.Axis.X : crankshaft.getAxis();
	}

	/** This cylinder's place in its engine, counting from 1 for the player. */
	public int getCylinderNumber() {
		CrankshaftBlockEntity crankshaft = getCrankshaft();
		return crankshaft == null ? 1 : crankshaft.getCylinderIndex() + 1;
	}

	/** How many cylinders this cylinder's engine has. */
	public int getEngineCylinderCount() {
		CrankshaftBlockEntity crankshaft = getCrankshaft();
		return crankshaft == null ? 1 : crankshaft.getCylinderCount();
	}

	@Nullable
	private CrankshaftBlockEntity getCrankshaft() {
		if (cachedCrankshaft != null && !cachedCrankshaft.isRemoved())
			return cachedCrankshaft;
		cachedCrankshaft = null;
		if (level != null && level.getBlockEntity(EngineComponents.crankshaftPosFromCylinder(worldPosition)) instanceof CrankshaftBlockEntity crankshaft)
			cachedCrankshaft = crankshaft;
		return cachedCrankshaft;
	}

	// --- persistence & synchronisation -------------------------------------

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		pistonInstalled = tag.getBoolean(KEY_PISTON_INSTALLED);
		// Absent on a cylinder saved before this milestone, and getBoolean answers
		// false for a missing key - so an existing world loads its engines with no
		// plug fitted, which is exactly right: nobody has ever installed one.
		sparkPlugInstalled = tag.getBoolean(KEY_SPARK_PLUG_INSTALLED);
	}

	@Override
	public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putBoolean(KEY_PISTON_INSTALLED, pistonInstalled);
		tag.putBoolean(KEY_SPARK_PLUG_INSTALLED, sparkPlugInstalled);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Nullable
	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	// --- goggle overlay -----------------------------------------------------

	/**
	 * Deliberately concise - the full engine diagnostic lives on the crankshaft.
	 *
	 * <p>This is where the <i>installed components</i> of an engine are listed,
	 * and it is the reason the crankshaft's overlay does not have to grow a line
	 * per part: a player asking "what is fitted to this cylinder" looks at the
	 * cylinder.
	 */
	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		// Which cylinder of which engine, so a player working along an inline-4 can
		// tell at a glance which bore they are looking into. Numbered from 1 and in
		// crank-axis order, which is the order the phases and later the firing order
		// are defined in - "Cylinder 2 / 4" is a position on the shaft, not a label.
		int number = getCylinderNumber();
		int total = getEngineCylinderCount();
		(total > 1 ? ECLang.translate("gui.cylinder_number", ECLang.number(number)
			.component(),
			ECLang.number(total)
				.component())
			: ECLang.translate("gui.cylinder")).style(ChatFormatting.WHITE)
				.forGoggles(tooltip);

		ECLang.translate("gui.piston",
			ECLang.translate(pistonInstalled ? "gui.value.installed"
				: "gui.value.missing")
				.style(pistonInstalled ? ChatFormatting.GREEN : ChatFormatting.RED)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		ECLang.translate("gui.spark_plug",
			ECLang.translate(sparkPlugInstalled ? "gui.value.installed"
				: "gui.value.missing")
				.style(sparkPlugInstalled ? ChatFormatting.GREEN : ChatFormatting.RED)
				.component())
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);

		if (pistonInstalled)
			ECLang.translate("gui.piston_position",
				ECLang.number(CrankMath.pistonPosition(getCrankAngleForRender(0.0F)))
					.style(ChatFormatting.AQUA)
					.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
		return true;
	}

	@Override
	public ItemStack getIcon(boolean isPlayerSneaking) {
		return new ItemStack(ECItems.CYLINDER.get());
	}
}
