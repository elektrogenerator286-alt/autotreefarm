package dev.example.treefarm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Авто-ферма дуба (клиентский мод).
 *
 * Включаете клавишей G, сажаете саженец дуба рядом с собой. Дальше мод:
 *  1. удобряет саженец костной мукой (из инвентаря или из сундука с табличкой «костная мука» рядом);
 *  2. рубит выросшее дерево топором из инвентаря;
 *  3. убирает листву мотыгой из инвентаря.
 *
 * Всё работает только в пределах досягаемости игрока (как при ручной игре).
 */
public class TreeFarmClient implements ClientModInitializer {
	public static final String MOD_ID = "autotreefarm";

	/** Дальность, в пределах которой мод взаимодействует с блоками. */
	private static final double REACH = 4.3;
	/** Пауза между применениями костной муки, тиков. */
	private static final int BONE_MEAL_DELAY = 6;
	/** Максимум применений костной муки на один саженец. */
	private static final int MAX_BONE_MEAL_USES = 80;
	/** Если состояние не меняется дольше этого времени (тиков) — останавливаемся. */
	private static final int STUCK_LIMIT = 20 * 40;
	/** Сколько раз забирать стаки из сундука за один заход. */
	private static final int MAX_TAKE_CLICKS = 3;

	private enum State { IDLE, GROW, OPEN_CHEST, TAKE_ITEMS, CHOP, LEAVES }

	private enum Tool { READY, PENDING, MISSING }

	private static KeyMapping toggleKey;

	private static boolean enabled = false;
	private static State state = State.IDLE;
	private static int stateTicks = 0;
	private static int cooldown = 0;

	private static BlockPos base;
	private static BlockPos chestPos;
	private static int boneMealUses = 0;
	private static int takeClicks = 0;

	private static final Set<BlockPos> ignored = new HashSet<>();
	private static final List<BlockPos> treeLogs = new ArrayList<>();
	private static BlockPos treeMin;
	private static BlockPos treeMax;
	private static boolean warnedUnreachable = false;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.autotreefarm.toggle",
				InputConstants.Type.KEYSYM,
				InputConstants.KEY_G,
				category));

		ClientTickEvents.END_CLIENT_TICK.register(TreeFarmClient::onTick);
	}

	// ------------------------------------------------------------------ main loop

	private static void onTick(Minecraft mc) {
		while (toggleKey.consumeClick()) {
			enabled = !enabled;
			resetAll(mc);
			message(mc, enabled
					? "Авто-ферма: ВКЛ. Посадите саженец дуба рядом с собой."
					: "Авто-ферма: ВЫКЛ");
		}

		if (!enabled || mc.player == null || mc.level == null || mc.gameMode == null) {
			return;
		}

		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;

		boolean chestFlow = state == State.OPEN_CHEST || state == State.TAKE_ITEMS;
		if (!chestFlow && mc.screen != null) {
			return;
		}

		if (cooldown > 0) {
			cooldown--;
		}
		stateTicks++;
		if (state != State.IDLE && stateTicks > STUCK_LIMIT) {
			fail(mc, "Застрял и остановился. Проверьте, что всё в пределах досягаемости.");
			return;
		}

		switch (state) {
			case IDLE -> tickIdle(mc, p, level);
			case GROW -> tickGrow(mc, p, level);
			case OPEN_CHEST -> tickOpenChest(mc, p);
			case TAKE_ITEMS -> tickTakeItems(mc, p);
			case CHOP -> tickChop(mc, p, level);
			case LEAVES -> tickLeaves(mc, p, level);
		}
	}

	// ------------------------------------------------------------------ states

	private static void tickIdle(Minecraft mc, LocalPlayer p, ClientLevel level) {
		BlockPos sapling = findSapling(p, level);
		if (sapling == null) {
			return;
		}
		base = sapling;
		boneMealUses = 0;
		setState(State.GROW);
		message(mc, "Нашёл саженец, удобряю…");
	}

	private static void tickGrow(Minecraft mc, LocalPlayer p, ClientLevel level) {
		BlockState bs = level.getBlockState(base);
		if (bs.is(Blocks.OAK_LOG)) {
			cooldown = 15; // даём клиенту получить все блоки дерева
			setState(State.CHOP);
			message(mc, "Дерево выросло, рублю…");
			return;
		}
		if (!bs.is(Blocks.OAK_SAPLING)) {
			fail(mc, "Саженец пропал.");
			return;
		}
		if (cooldown > 0) {
			return;
		}
		if (!reachable(p, base)) {
			fail(mc, "Саженец вне досягаемости. Стойте ближе.");
			return;
		}
		if (boneMealUses >= MAX_BONE_MEAL_USES) {
			fail(mc, "Дерево не растёт (возможно, нет места).");
			return;
		}

		if (findSlot(p, s -> s.is(Items.BONE_MEAL)) < 0) {
			BlockPos chest = findBoneMealChest(level, p);
			if (chest == null) {
				fail(mc, "Нет костной муки: ни в инвентаре, ни в сундуке с табличкой «костная мука» рядом.");
				return;
			}
			chestPos = chest;
			takeClicks = 0;
			setState(State.OPEN_CHEST);
			return;
		}

		Tool t = ensureHotbar(mc, p, s -> s.is(Items.BONE_MEAL));
		if (t == Tool.PENDING) {
			cooldown = 2;
			return;
		}
		if (t == Tool.MISSING) {
			return;
		}

		lookAt(p, Vec3.atCenterOf(base));
		mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(base), Direction.UP, base, false));
		p.swing(InteractionHand.MAIN_HAND);
		boneMealUses++;
		cooldown = BONE_MEAL_DELAY;
	}

	private static void tickOpenChest(Minecraft mc, LocalPlayer p) {
		if (p.containerMenu instanceof ChestMenu) {
			cooldown = 4;
			setState(State.TAKE_ITEMS);
			return;
		}
		if (stateTicks > 60) {
			fail(mc, "Не удалось открыть сундук.");
			return;
		}
		if (stateTicks == 1 || stateTicks % 20 == 0) {
			if (!reachable(p, chestPos)) {
				fail(mc, "Сундук вне досягаемости.");
				return;
			}
			lookAt(p, Vec3.atCenterOf(chestPos));
			mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(chestPos), Direction.UP, chestPos, false));
			p.swing(InteractionHand.MAIN_HAND);
		}
	}

	private static void tickTakeItems(Minecraft mc, LocalPlayer p) {
		if (!(p.containerMenu instanceof ChestMenu menu)) {
			if (stateTicks > 5) {
				setState(State.GROW);
			}
			return;
		}
		if (cooldown > 0) {
			return;
		}
		int chestSlots = menu.getRowCount() * 9;
		if (takeClicks < MAX_TAKE_CLICKS) {
			for (int i = 0; i < chestSlots; i++) {
				if (menu.getSlot(i).getItem().is(Items.BONE_MEAL)) {
					mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, p);
					takeClicks++;
					cooldown = 4;
					return;
				}
			}
		}
		p.closeContainer();
		cooldown = 8;
		setState(State.GROW);
	}

	private static void tickChop(Minecraft mc, LocalPlayer p, ClientLevel level) {
		if (cooldown > 0) {
			return;
		}
		if (treeLogs.isEmpty() && treeMin == null) {
			collectTree(level);
			if (treeLogs.isEmpty()) {
				fail(mc, "Не нашёл брёвен дерева.");
				return;
			}
		}

		Tool t = ensureHotbar(mc, p, s -> s.is(ItemTags.AXES));
		if (t == Tool.MISSING) {
			fail(mc, "Нет топора в инвентаре.");
			return;
		}
		if (t == Tool.PENDING) {
			cooldown = 2;
			return;
		}

		BlockPos next = nearestReachable(p, level, treeLogs, Blocks.OAK_LOG);
		if (next == null) {
			mc.gameMode.stopDestroyBlock();
			if (anyLeft(level, treeLogs, Blocks.OAK_LOG)) {
				warnUnreachable(mc, "Часть брёвен вне досягаемости, пропускаю их.");
			}
			setState(State.LEAVES);
			cooldown = 4;
			return;
		}
		breakStep(mc, p, next);
	}

	private static void tickLeaves(Minecraft mc, LocalPlayer p, ClientLevel level) {
		if (cooldown > 0) {
			return;
		}
		Tool t = ensureHotbar(mc, p, s -> s.is(ItemTags.HOES));
		if (t == Tool.MISSING) {
			finish(mc, "Дерево срублено. Мотыги нет — листву не трогаю.");
			return;
		}
		if (t == Tool.PENDING) {
			cooldown = 2;
			return;
		}

		List<BlockPos> leaves = collectLeaves(level);
		BlockPos next = nearestReachable(p, level, leaves, Blocks.OAK_LEAVES);
		if (next == null) {
			mc.gameMode.stopDestroyBlock();
			if (anyLeft(level, leaves, Blocks.OAK_LEAVES)) {
				finish(mc, "Готово. Часть листвы вне досягаемости, она опадёт сама.");
			} else {
				finish(mc, "Готово: дерево срублено, листва убрана.");
			}
			return;
		}
		breakStep(mc, p, next);
	}

	// ------------------------------------------------------------------ helpers: world

	private static void breakStep(Minecraft mc, LocalPlayer p, BlockPos pos) {
		Vec3 center = Vec3.atCenterOf(pos);
		lookAt(p, center);
		Vec3 eye = p.getEyePosition();
		Direction face = Direction.getApproximateNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
		mc.gameMode.continueDestroyBlock(pos, face);
		if (stateTicks % 4 == 0) {
			p.swing(InteractionHand.MAIN_HAND);
		}
	}

	private static BlockPos findSapling(LocalPlayer p, ClientLevel level) {
		BlockPos origin = p.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dx = -5; dx <= 5; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				for (int dz = -5; dz <= 5; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					if (ignored.contains(pos) || !level.getBlockState(pos).is(Blocks.OAK_SAPLING)) {
						continue;
					}
					double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
					if (d <= REACH && d < bestDist) {
						bestDist = d;
						best = pos;
					}
				}
			}
		}
		return best;
	}

	/** Сундук в досягаемости, рядом с которым висит табличка «костная мука». */
	private static BlockPos findBoneMealChest(ClientLevel level, LocalPlayer p) {
		BlockPos origin = p.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dx = -5; dx <= 5; dx++) {
			for (int dy = -4; dy <= 4; dy++) {
				for (int dz = -5; dz <= 5; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					if (!(level.getBlockState(pos).getBlock() instanceof ChestBlock)) {
						continue;
					}
					double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
					if (d > REACH || d >= bestDist) {
						continue;
					}
					if (chestHasBoneMealSign(level, pos)) {
						bestDist = d;
						best = pos;
					}
				}
			}
		}
		return best;
	}

	private static boolean chestHasBoneMealSign(ClientLevel level, BlockPos chest) {
		List<BlockPos> halves = new ArrayList<>();
		halves.add(chest);
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = chest.relative(d);
			if (level.getBlockState(n).getBlock() instanceof ChestBlock) {
				halves.add(n);
			}
		}
		for (BlockPos c : halves) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockEntity be = level.getBlockEntity(c.offset(dx, dy, dz));
						if (be instanceof SignBlockEntity sign
								&& (signMatches(sign.getFrontText()) || signMatches(sign.getBackText()))) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private static boolean signMatches(SignText text) {
		for (int i = 0; i < 4; i++) {
			String line = text.getMessage(i, false).getString().toLowerCase(Locale.ROOT);
			if (line.contains("костная") || line.contains("bone meal") || line.contains("bonemeal")) {
				return true;
			}
		}
		return false;
	}

	/** Собираем все брёвна дуба, связанные с основанием дерева. */
	private static void collectTree(ClientLevel level) {
		treeLogs.clear();
		Set<BlockPos> visited = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(base);
		visited.add(base);
		int minX = base.getX(), minY = base.getY(), minZ = base.getZ();
		int maxX = minX, maxY = minY, maxZ = minZ;

		while (!queue.isEmpty() && visited.size() < 400) {
			BlockPos cur = queue.poll();
			if (!level.getBlockState(cur).is(Blocks.OAK_LOG)) {
				continue;
			}
			treeLogs.add(cur);
			minX = Math.min(minX, cur.getX());
			minY = Math.min(minY, cur.getY());
			minZ = Math.min(minZ, cur.getZ());
			maxX = Math.max(maxX, cur.getX());
			maxY = Math.max(maxY, cur.getY());
			maxZ = Math.max(maxZ, cur.getZ());

			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos n = cur.offset(dx, dy, dz);
						if (Math.abs(n.getX() - base.getX()) > 8 || Math.abs(n.getZ() - base.getZ()) > 8
								|| n.getY() < base.getY() || n.getY() > base.getY() + 20) {
							continue;
						}
						if (visited.add(n)) {
							queue.add(n);
						}
					}
				}
			}
		}
		treeMin = new BlockPos(minX, minY, minZ);
		treeMax = new BlockPos(maxX, maxY, maxZ);
	}

	private static List<BlockPos> collectLeaves(ClientLevel level) {
		List<BlockPos> result = new ArrayList<>();
		if (treeMin == null || treeMax == null) {
			return result;
		}
		for (int x = treeMin.getX() - 3; x <= treeMax.getX() + 3; x++) {
			for (int y = treeMin.getY() - 1; y <= treeMax.getY() + 3; y++) {
				for (int z = treeMin.getZ() - 3; z <= treeMax.getZ() + 3; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (level.getBlockState(pos).is(Blocks.OAK_LEAVES)) {
						result.add(pos);
					}
				}
			}
		}
		return result;
	}

	/** Ближайший (с приоритетом нижних) блок из списка, до которого можно дотянуться. */
	private static BlockPos nearestReachable(LocalPlayer p, ClientLevel level, List<BlockPos> list,
			net.minecraft.world.level.block.Block block) {
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		Vec3 eye = p.getEyePosition();
		for (BlockPos pos : list) {
			if (!level.getBlockState(pos).is(block)) {
				continue;
			}
			double d = eye.distanceTo(Vec3.atCenterOf(pos));
			if (d > REACH) {
				continue;
			}
			double score = d + pos.getY() * 0.01;
			if (score < bestScore) {
				bestScore = score;
				best = pos;
			}
		}
		return best;
	}

	private static boolean anyLeft(ClientLevel level, List<BlockPos> list, net.minecraft.world.level.block.Block block) {
		for (BlockPos pos : list) {
			if (level.getBlockState(pos).is(block)) {
				return true;
			}
		}
		return false;
	}

	private static boolean reachable(LocalPlayer p, BlockPos pos) {
		return p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= REACH;
	}

	private static void lookAt(LocalPlayer p, Vec3 target) {
		Vec3 eye = p.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		p.setYRot((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f);
		p.setXRot((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
	}

	// ------------------------------------------------------------------ helpers: inventory

	/** Индекс предмета в инвентаре игрока (0-8 хотбар, 9-35 основной), либо -1. */
	private static int findSlot(LocalPlayer p, Predicate<ItemStack> pred) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && pred.test(s)) {
				return i;
			}
		}
		return -1;
	}

	/** Переносит нужный предмет в руку (выбирает слот хотбара или меняет из основного инвентаря). */
	private static Tool ensureHotbar(Minecraft mc, LocalPlayer p, Predicate<ItemStack> pred) {
		Inventory inv = p.getInventory();
		ItemStack held = inv.getItem(inv.getSelectedSlot());
		if (!held.isEmpty() && pred.test(held)) {
			return Tool.READY;
		}
		int slot = findSlot(p, pred);
		if (slot < 0) {
			return Tool.MISSING;
		}
		if (slot < 9) {
			inv.setSelectedSlot(slot);
			return Tool.READY;
		}
		mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, slot, inv.getSelectedSlot(),
				ClickType.SWAP, p);
		return Tool.PENDING;
	}

	// ------------------------------------------------------------------ helpers: state

	private static void setState(State s) {
		state = s;
		stateTicks = 0;
	}

	private static void finish(Minecraft mc, String text) {
		resetWork(mc);
		message(mc, text);
	}

	private static void fail(Minecraft mc, String text) {
		if (base != null) {
			ignored.add(base);
		}
		resetWork(mc);
		message(mc, "Авто-ферма: " + text);
	}

	private static void resetWork(Minecraft mc) {
		if (mc.gameMode != null) {
			mc.gameMode.stopDestroyBlock();
		}
		if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
			mc.player.closeContainer();
		}
		state = State.IDLE;
		stateTicks = 0;
		cooldown = 0;
		base = null;
		chestPos = null;
		treeLogs.clear();
		treeMin = null;
		treeMax = null;
		warnedUnreachable = false;
	}

	private static void resetAll(Minecraft mc) {
		resetWork(mc);
		ignored.clear();
	}

	private static void warnUnreachable(Minecraft mc, String text) {
		if (!warnedUnreachable) {
			warnedUnreachable = true;
			message(mc, text);
		}
	}

	private static void message(Minecraft mc, String text) {
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal(text), true);
		}
	}
}
