package io.github.inv713.smartinteract;

import io.github.inv713.smartinteract.SmartInteractConfig.Mapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端事件处理（只在 Dist.CLIENT 生效）。
 *
 * <ul>
 *   <li>右键方块 → 换成该方块配置的物品（不会顺手换工具）</li>
 *   <li>左键方块 → 先按映射换物品，没有映射时自动选最好的挖掘工具</li>
 *   <li>攻击实体 → 在按键触发阶段自动换成剑/斧</li>
 * </ul>
 * <p>查找范围是整个背包（0–35 格）。9–35 格的物品会通过原版 SWAP 操作换到当前手持栏位。</p>
 */
@EventBusSubscriber(modid = SmartInteract.MODID, value = Dist.CLIENT)
public final class InteractionHandler {

    private InteractionHandler() {}

    // ==================== 方块交互 ====================

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || cannotInteract(player)) return;

        SmartInteractConfig config = SmartInteractConfig.get();
        if (config.isRequireSneak() && !player.isShiftKeyDown()) return;

        BlockState state = event.getLevel().getBlockState(event.getPos());
        for (Mapping mapping : config.getRightClickMappings(state.getBlock())) {
            if (isHoldingTarget(player, mapping.item)) return;
            ResourceLocation itemId = SmartInteractConfig.parseIdOrNull(mapping.item);
            Item targetItem = itemId == null ? null : BuiltInRegistries.ITEM.get(itemId);
            if (targetItem != null && findAndEquip(player, targetItem)) return;
        }

        // 右键只按映射换物品；没有匹配映射就不切换任何工具
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || cannotInteract(player)) return;

        SmartInteractConfig config = SmartInteractConfig.get();
        if (config.isRequireSneak() && !player.isShiftKeyDown()) return;

        BlockState state = event.getLevel().getBlockState(event.getPos());
        Block block = state.getBlock();

        for (Mapping mapping : config.getLeftClickMappings(block)) {
            if (isHoldingTarget(player, mapping.item)) return;
            ResourceLocation itemId = SmartInteractConfig.parseIdOrNull(mapping.item);
            Item targetItem = itemId == null ? null : BuiltInRegistries.ITEM.get(itemId);
            if (targetItem != null && findAndEquip(player, targetItem)) return;
        }

        if (!config.isAutoToolSwitch()) return;
        autoSelectBestTool(player, state);
    }

    // ==================== 攻击切武器 ====================

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || cannotInteract(player)) return;
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY) return;

        SmartInteractConfig config = SmartInteractConfig.get();
        if (!config.isAttackWeaponSwitch()) return;

        ItemStack held = player.getMainHandItem();
        String heldCat = getToolCategory(held);
        boolean heldIsWeapon = "sword".equals(heldCat) || "axe".equals(heldCat);

        Inventory inv = player.getInventory();
        List<String> ranking = config.getToolRanking();
        int heldRank = held.isEmpty() ? -1
            : ranking.indexOf(BuiltInRegistries.ITEM.getKey(held.getItem()).toString());

        int rankedSlot = -1, rankedIdx = Integer.MAX_VALUE;
        int bestSword = -1, bestAxe = -1;
        for (int i = 0; i < 36; i++) {
            if (i == inv.selected) continue;
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String cat = getToolCategory(stack);
            if (!"sword".equals(cat) && !"axe".equals(cat)) continue;

            // 收藏（工具排序）中的武器优先
            int rank = ranking.indexOf(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if (rank >= 0 && rank < rankedIdx) { rankedIdx = rank; rankedSlot = i; }

            if ("sword".equals(cat) && bestSword == -1) bestSword = i;
            if ("axe".equals(cat) && bestAxe == -1) bestAxe = i;
        }

        // 已手持最优收藏武器 → 保持
        if (heldIsWeapon && heldRank >= 0 && heldRank <= rankedIdx) return;

        int best;
        if (rankedSlot != -1) best = rankedSlot;              // 收藏武器优先
        else if (heldIsWeapon) return;                        // 已手持武器，不折腾
        else best = bestSword != -1 ? bestSword : bestAxe;    // 剑优先，其次斧
        if (best == -1) return;

        equipSlot(player, best);
    }

    // ==================== 物品查找（整个背包） ====================

    static boolean findAndEquip(LocalPlayer player, Item target) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).getItem() == target) { equipSlot(player, i); return true; }
        }
        return false;
    }

    // ==================== 工具切换 ====================

    private static void autoSelectBestTool(LocalPlayer player, BlockState state) {
        Inventory inv = player.getInventory();
        int selected = inv.selected;
        float currentSpeed = inv.getItem(selected).getDestroySpeed(state);
        List<String> priority = SmartInteractConfig.get().getToolPriorityFor(state.getBlock());
        List<String> ranking = SmartInteractConfig.get().getToolRanking();

        record Best(int slot, float speed) {}
        Map<String, Best> fastestPerCat = new LinkedHashMap<>();
        Map<String, Best> rankedPerCat = new LinkedHashMap<>();
        Map<String, Integer> rankedIndex = new HashMap<>();

        // 单次遍历同时得到"每类最快"和"每类收藏最靠前"，避免二次全背包扫描
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String cat = getToolCategory(stack);
            float speed = stack.getDestroySpeed(state);

            // 未收藏工具：只考虑比当前手持更快的，避免降级
            if (i != selected && speed > currentSpeed) {
                Best prev = fastestPerCat.get(cat);
                if (prev == null || speed > prev.speed)
                    fastestPerCat.put(cat, new Best(i, speed));
            }

            // 收藏工具：不受速度限制；同排名时优先当前手持槽位
            if (!ranking.isEmpty()) {
                int rank = ranking.indexOf(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                Integer prevRank = rankedIndex.get(cat);
                if (rank >= 0 && (prevRank == null || rank < prevRank
                        || (rank == prevRank && i == selected))) {
                    rankedIndex.put(cat, rank);
                    rankedPerCat.put(cat, new Best(i, speed));
                }
            }
        }

        // 按类别优先级选取：收藏（排序）工具优先于更快的未收藏工具
        for (String cat : priority) {
            Best ranked = rankedPerCat.get(cat);
            if (ranked != null) {
                if (ranked.slot != selected) equipSlot(player, ranked.slot);
                return;
            }
            Best fastest = fastestPerCat.get(cat);
            if (fastest != null) { equipSlot(player, fastest.slot); return; }
        }
    }

    /** 自动识别结果缓存（仅缓存自动识别，手动分类每次实时读取）。 */
    private static final Map<Item, String> CATEGORY_CACHE = new IdentityHashMap<>();

    static String getToolCategory(ItemStack stack) {
        if (stack.isEmpty()) return "other";
        Item item = stack.getItem();

        // 1) 手动分类（界面 Shift+点类别按钮 / JSON toolCategories）优先，不缓存
        String override = SmartInteractConfig.get().getToolCategoryOverride(
            BuiltInRegistries.ITEM.getKey(item).toString());
        if (override != null && !override.isEmpty()) return override;

        // 2) 自动识别结果按物品缓存，避免每次点击都做 4 次挖掘速度探测
        String cached = CATEGORY_CACHE.get(item);
        if (cached != null) return cached;

        String detected = detectToolCategory(stack);
        CATEGORY_CACHE.put(item, detected);
        return detected;
    }

    private static String detectToolCategory(ItemStack stack) {
        Item item = stack.getItem();

        // 原版工具类
        if (item instanceof PickaxeItem) return "pickaxe";
        if (item instanceof ShovelItem)  return "shovel";
        if (item instanceof AxeItem)     return "axe";
        if (item instanceof HoeItem)     return "hoe";
        if (item instanceof SwordItem)   return "sword";

        // 模组工具：按对代表方块的挖掘速度识别
        String bySpeed = categoryFromDigSpeed(stack);
        if (bySpeed != null) return bySpeed;

        // 物品 id 关键词兜底
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (path.contains("pickaxe")) return "pickaxe";
        if (path.contains("shovel") || path.contains("spade")) return "shovel";
        if (path.contains("hoe")) return "hoe";
        if (path.contains("axe")) return "axe";
        if (path.contains("sword") || path.contains("blade")) return "sword";
        return "other";
    }

    /** 非原版类的模组工具：用代表方块的挖掘速度推断类别（高于普通物品的 1.0）。 */
    private static String categoryFromDigSpeed(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.contains("shears")) return null;
        if (stack.getDestroySpeed(Blocks.STONE.defaultBlockState()) > 1.0F) return "pickaxe";
        if (stack.getDestroySpeed(Blocks.DIRT.defaultBlockState()) > 1.0F) return "shovel";
        if (stack.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) > 1.0F) return "axe";
        if (stack.getDestroySpeed(Blocks.OAK_LEAVES.defaultBlockState()) > 1.0F) return "hoe";
        return null;
    }

    // ==================== 背包辅助 ====================

    /**
     * 切换到指定槽位。快捷栏（0–8）直接选中；其他槽位通过 SWAP 换到当前手持栏位，并同步给服务端。
     */
    static void equipSlot(LocalPlayer player, int slot) {
        Inventory inv = player.getInventory();
        if (slot >= 0 && slot < 9) {
            if (inv.selected != slot) {
                inv.selected = slot;
                // 通知服务端切换手持栏位，否则服务端仍按旧物品处理交互
                var connection = Minecraft.getInstance().getConnection();
                if (connection != null) connection.send(new ServerboundSetCarriedItemPacket(slot));
            }
            return;
        }
        swapToHotbar(player, slot);
    }

    private static void swapToHotbar(LocalPlayer player, int fromSlot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryMouseClick(0, fromSlot,
                player.getInventory().selected, ClickType.SWAP, player);
        }
    }

    // ==================== 判定方法 ====================

    private static boolean cannotInteract(LocalPlayer player) {
        return player.isSpectator() || player.isCreative();
    }

    private static boolean isHoldingTarget(LocalPlayer player, String itemIdStr) {
        ResourceLocation targetId = SmartInteractConfig.parseIdOrNull(itemIdStr);
        if (targetId == null) return false;
        ResourceLocation heldId = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem());
        return targetId.equals(heldId);
    }
}