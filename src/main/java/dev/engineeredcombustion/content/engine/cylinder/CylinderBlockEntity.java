package dev.engineeredcombustion.content.engine.cylinder;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import dev.engineeredcombustion.content.engine.CrankMath;
import dev.engineeredcombustion.content.engine.EngineComponents;
import dev.engineeredcombustion.content.engine.EngineWearMath;
import dev.engineeredcombustion.content.engine.WearCondition;
import dev.engineeredcombustion.content.engine.crankshaft.CrankshaftBlockEntity;
import dev.engineeredcombustion.foundation.ECLang;
import dev.engineeredcombustion.foundation.EngineConditionText;
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
	private static final String KEY_PISTON_WEAR = "PistonWear";

	private boolean pistonInstalled;
	private boolean sparkPlugInstalled;

	/**
	 * Compression wear of the Piston Assembly currently in this bore, {@code [0, 1]}.
	 *
	 * <p><b>The part's wear, being kept for it while it is installed.</b> It arrives
	 * with the item and leaves with the item - see
	 * {@link #installPistonAssembly(float)} and {@link #takePistonAssemblyWear()} -
	 * which is what makes pulling a tired piston out and pushing it back in do
	 * nothing at all. The cylinder bore itself is deliberately not modelled
	 * separately yet, which is why fitting a new assembly restores this cylinder's
	 * compression completely.
	 *
	 * <p>Meaningless while no assembly is fitted, and forced to zero then, so an
	 * empty bore can never hand its previous occupant's wear to the next one.
	 *
	 * <p>Absent from an old world's save data, and {@code getFloat} answers 0 for a
	 * missing key - which is exactly right: nothing that existed before this
	 * milestone has ever worn.
	 */
	private float pistonWear;

	/**
	 * The last wear figure the client was told, quantised.
	 *
	 * <p>Wear moves by about a millionth per revolution and the client only ever
	 * needs enough of it to name a condition band, so the server keeps the exact
	 * value and synchronises a hundredth. Without this a running engine would send a
	 * block entity update for every cylinder on every tick, which is precisely the
	 * traffic the combustion payload was introduced to remove.
	 */
	private float syncedPistonWear;

	/** Client-side render cache only; never used for game logic. */
	@Nullable
	private CrankshaftBlockEntity cachedCrankshaft;

	public CylinderBlockEntity(BlockPos pos, BlockState state) {
		super(ECBlockEntityTypes.CYLINDER.get(), pos, state);
	}

	public boolean hasPistonAssembly() {
		return pistonInstalled;
	}

	/**
	 * Fits an assembly that has already done {@code wear} worth of work.
	 *
	 * <p>Zero for a freshly crafted part, and whatever the item was carrying for one
	 * that has been in an engine before. <b>This is the no-free-repair rule</b>: the
	 * cylinder does not decide the condition of the part it is given, it is told.
	 *
	 * @return false when an assembly is already installed
	 */
	public boolean installPistonAssembly(float wear) {
		if (pistonInstalled)
			return false;
		pistonWear = EngineWearMath.clampWear(wear);
		syncedPistonWear = EngineWearMath.quantiseWear(pistonWear);
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

	/**
	 * Takes the assembly out and hands back the wear to put on the item.
	 *
	 * <p>One call rather than a read and a remove, because the two must not be able
	 * to happen apart: reading without removing would duplicate the wear onto an
	 * item while the cylinder kept it, and removing without reading would destroy
	 * it. Returns -1 when there was nothing fitted, which is distinguishable from a
	 * pristine assembly's 0.
	 */
	public float takePistonAssemblyWear() {
		if (!pistonInstalled)
			return -1.0F;
		float wear = pistonWear;
		setPistonInstalled(false);
		return wear;
	}

	/**
	 * Wear of the assembly in this bore, or 0 when there is none.
	 *
	 * <p>Server-exact; the client's copy is quantised to a hundredth, which is finer
	 * than any condition band it is used to name.
	 */
	public float getPistonWear() {
		return pistonInstalled ? pistonWear : 0.0F;
	}

	/** How much compression this cylinder still has, as a fraction of a healthy one. */
	public float getCompressionEfficiency() {
		return EngineWearMath.compressionEfficiency(getPistonWear());
	}

	/** This cylinder's compression, in the words the player is shown. */
	public WearCondition getCompressionCondition() {
		return WearCondition.of(getPistonWear());
	}

	/**
	 * Wears the installed assembly by one tick's worth of work.
	 *
	 * <p>Called from the engine controller on the server, once per tick, with the
	 * increment the pure wear model computed - see
	 * {@code CrankshaftBlockEntity#accumulateWear}. Nothing is decided here; this
	 * only keeps the number.
	 *
	 * <p>It deliberately does <b>not</b> synchronise on every call. The exact value
	 * is kept on the server and written to disk; the client is told only when the
	 * quantised figure actually moves, which over a whole piston's service life is a
	 * hundred updates rather than one per tick.
	 */
	public void addPistonWear(float delta) {
		if (!pistonInstalled || delta <= 0.0F || level == null || level.isClientSide)
			return;
		float updated = EngineWearMath.clampWear(pistonWear + delta);
		if (updated == pistonWear)
			return;
		pistonWear = updated;

		float quantised = EngineWearMath.quantiseWear(pistonWear);
		if (quantised == syncedPistonWear)
			return;
		syncedPistonWear = quantised;
		// setChanged as part of the update: this is the point at which the value on
		// disk would be meaningfully stale, and it is rare enough to be free.
		setChanged();
		level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
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
		if (!installed) {
			// An empty bore has no condition. Leaving the old figure behind would hand
			// the next assembly fitted here the previous one's wear - which is the
			// no-free-repair rule running backwards, and just as wrong.
			pistonWear = 0.0F;
			syncedPistonWear = 0.0F;
		}
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
		// Absent on a cylinder saved before wear existed, and getFloat answers 0 for a
		// missing key - so every piston in every existing world comes back pristine,
		// which is the only honest answer for a part that has never been worn by
		// anything.
		pistonWear = pistonInstalled ? EngineWearMath.clampWear(tag.getFloat(KEY_PISTON_WEAR)) : 0.0F;
		syncedPistonWear = EngineWearMath.quantiseWear(pistonWear);
	}

	@Override
	public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putBoolean(KEY_PISTON_INSTALLED, pistonInstalled);
		tag.putBoolean(KEY_SPARK_PLUG_INSTALLED, sparkPlugInstalled);
		// The server's exact figure, both to disk and to the client. It is one float
		// on a packet that is only sent when something about this cylinder actually
		// changed, so there is nothing to save by quantising it here - the saving is
		// in not sending the packet, which addPistonWear does.
		if (pistonInstalled)
			tag.putFloat(KEY_PISTON_WEAR, pistonWear);
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

		if (pistonInstalled) {
			// The service state of the part that is actually in the bore. This is the
			// overlay a player checks once the engine's own reads "Worn" and they want
			// to know which cylinder to open, so it names the condition of THIS
			// assembly and nothing else. Omitted with no assembly fitted: there is no
			// compression to report, and the line above has already said the part is
			// missing.
			ECLang.translate("gui.compression", EngineConditionText.name(getCompressionCondition()))
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);

			ECLang.translate("gui.piston_position",
				ECLang.number(CrankMath.pistonPosition(getCrankAngleForRender(0.0F)))
					.style(ChatFormatting.AQUA)
					.component())
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
		}
		return true;
	}

	@Override
	public ItemStack getIcon(boolean isPlayerSneaking) {
		return new ItemStack(ECItems.CYLINDER.get());
	}
}
