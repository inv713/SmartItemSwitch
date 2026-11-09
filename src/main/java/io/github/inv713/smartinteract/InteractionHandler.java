package io.github.inv713.smartinteract;

import io.github.inv713.smartinteract.SmartInteractConfig.Mapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.ItemAbilities;
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

/** 客户端交互处理：左右键方块自动换物品，攻击实体自动换武器。 */
@EventBusSubscriber(modid = SmartInteract.MODID, value = Dist.CLIENT)
public final class InteractionHandler {

    private InteractionHandler() {}

    // 方块交互

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

        // 树叶、羊毛等优先剪刀
        if (prefersShears(state)) {
            int shearsSlot = findShearsSlot(player);
            if (shearsSlot >= 0) {
                if (shearsSlot != player.getInventory().selected) equipSlot(player, shearsSlot);
                return;
            }
        }
        autoSelectBestTool(player, state);
    }

    // 右键自动换工具

    // 按键阶段换，事件触发时客户端已经拿好旧物品了
    private static void handleRightClickUse(Minecraft mc, LocalPlayer player) {
        if (mc.level == null || mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return;

        SmartInteractConfig config = SmartInteractConfig.get();
        if (config.isRequireSneak() && !player.isShiftKeyDown()) return;

        BlockHitResult hit = (BlockHitResult) mc.hitResult;
        BlockState state = mc.level.getBlockState(hit.getBlockPos());

        // 手写映射优先
        List<Mapping> mappings = config.getRightClickMappings(state.getBlock());
        for (Mapping mapping : mappings) {
            if (isHoldingTarget(player, mapping.item)) return;
            ResourceLocation itemId = SmartInteractConfig.parseIdOrNull(mapping.item);
            Item targetItem = itemId == null ? null : BuiltInRegistries.ITEM.get(itemId);
            if (targetItem != null && findAndEquip(player, targetItem)) return;
        }

        // 手持方块时优先放方块
        ItemStack held = player.getMainHandItem();
        if (held.getItem() instanceof BlockItem) return;
        // 主手空、副手方块时让原版用副手放
        if (held.isEmpty() && player.getOffhandItem().getItem() instanceof BlockItem) return;
        // 食物、桶之类交给原版
        if (!held.isEmpty() && "other".equals(getToolCategory(held)) && !isShears(held)) return;

        // 没有映射就按方块自动选工具
        if (!config.isAutoToolSwitch()) return;
        autoSelectRightClickTool(player, state, hit);
    }

    private static void autoSelectRightClickTool(LocalPlayer player, BlockState state, BlockHitResult hit) {
        Inventory inv = player.getInventory();

        // 南瓜、蜂巢、修剪类优先剪刀
        int shearsSlot = findShearsSlot(player);
        if (shearsSlot >= 0 && usesShearsOnRightClick(player, state, hit, inv.getItem(shearsSlot))) {
            if (shearsSlot != inv.selected) equipSlot(player, shearsSlot);
            return;
        }

        // 按方块优先级试斧、铲、锄
        for (String category : SmartInteractConfig.get().getToolPriorityFor(state.getBlock())) {
            int slot = findToolSlot(player, category);
            if (slot < 0 || !canUseOn(player, state, hit, inv.getItem(slot), category)) continue;
            if (slot != inv.selected) equipSlot(player, slot);
            return;
        }
    }

    private static boolean usesShearsOnRightClick(LocalPlayer player, BlockState state,
                                                   BlockHitResult hit, ItemStack shears) {
        if (state.is(Blocks.PUMPKIN)) return true;
        if (state.getBlock() instanceof BeehiveBlock
                && state.getValue(BeehiveBlock.HONEY_LEVEL) >= 5) return true;
        return state.getToolModifiedState(
            new ProbeUseOnContext(player.level(), player, InteractionHand.MAIN_HAND, shears, hit),
            ItemAbilities.SHEARS_TRIM, true) != null;
    }

    private static boolean canUseOn(LocalPlayer player, BlockState state, BlockHitResult hit,
                                    ItemStack stack, String category) {
        UseOnContext context = new ProbeUseOnContext(
            player.level(), player, InteractionHand.MAIN_HAND, stack, hit);
        return switch (category) {
            case "axe" -> state.getToolModifiedState(context, ItemAbilities.AXE_STRIP, true) != null
                || state.getToolModifiedState(context, ItemAbilities.AXE_SCRAPE, true) != null
                || state.getToolModifiedState(context, ItemAbilities.AXE_WAX_OFF, true) != null;
            case "shovel" -> state.getToolModifiedState(context, ItemAbilities.SHOVEL_FLATTEN, true) != null
                || state.getToolModifiedState(context, ItemAbilities.SHOVEL_DOUSE, true) != null;
            case "hoe" -> state.getToolModifiedState(context, ItemAbilities.HOE_TILL, true) != null;
            default -> false;
        };
    }

    // UseOnContext 的带物品构造器是 protected，用子类绕一下
    private static final class ProbeUseOnContext extends UseOnContext {
        ProbeUseOnContext(Level level, LocalPlayer player, InteractionHand hand,
                          ItemStack stack, BlockHitResult hit) {
            super(level, player, hand, stack, hit);
        }
    }

    // 攻击换武器

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || cannotInteract(player)) return;

        if (event.isUseItem()) {
            handleRightClickUse(mc, player);
            return;
        }
        if (!event.isAttack()) return;
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

            // 收藏的武器优先
            int rank = ranking.indexOf(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if (rank >= 0 && rank < rankedIdx) { rankedIdx = rank; rankedSlot = i; }

            if ("sword".equals(cat) && bestSword == -1) bestSword = i;
            if ("axe".equals(cat) && bestAxe == -1) bestAxe = i;
        }

        // 手里已经是最好的收藏武器
        if (heldIsWeapon && heldRank >= 0 && heldRank <= rankedIdx) return;

        int best;
        if (rankedSlot != -1) best = rankedSlot;              // 收藏武器优先
        else if (heldIsWeapon) return;                        // 已手持武器，不折腾
        else best = bestSword != -1 ? bestSword : bestAxe;    // 剑优先，其次斧
        if (best == -1) return;

        equipSlot(player, best);
    }

    // 背包查找

    static boolean findAndEquip(LocalPlayer player, Item target) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).getItem() == target) { equipSlot(player, i); return true; }
        }
        return false;
    }

    private static boolean isShears(ItemStack stack) {
        return !stack.isEmpty()
            && (stack.getItem() instanceof ShearsItem
                || stack.canPerformAction(ItemAbilities.SHEARS_DIG));
    }

    // 这些方块用剪刀能拿本体或拆线
    private static boolean prefersShears(BlockState state) {
        return state.is(BlockTags.LEAVES)
            || state.is(BlockTags.WOOL)
            || state.is(Blocks.COBWEB)
            || state.is(Blocks.TRIPWIRE)
            || state.is(BlockTags.CAVE_VINES)
            || state.is(Blocks.VINE)
            || state.is(Blocks.GLOW_LICHEN)
            || state.is(Blocks.HANGING_ROOTS)
            || state.is(Blocks.WEEPING_VINES)
            || state.is(Blocks.WEEPING_VINES_PLANT)
            || state.is(Blocks.TWISTING_VINES)
            || state.is(Blocks.TWISTING_VINES_PLANT);
    }

    private static int findShearsSlot(LocalPlayer player) {
        return findSlot(player, InteractionHandler::isShears);
    }

    private static int findToolSlot(LocalPlayer player, String category) {
        return findSlot(player, stack -> category.equals(getToolCategory(stack)));
    }

    // 当前手持 > 收藏靠前 > 背包第一个
    private static int findSlot(LocalPlayer player, java.util.function.Predicate<ItemStack> filter) {
        Inventory inv = player.getInventory();
        List<String> ranking = SmartInteractConfig.get().getToolRanking();
        int fallback = -1;
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !filter.test(stack)) continue;
            if (i == inv.selected) return i;
            int rank = ranking.indexOf(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if (rank >= 0 && rank < bestRank) {
                bestRank = rank;
                best = i;
            } else if (fallback < 0) {
                fallback = i;
            }
        }
        return best != -1 ? best : fallback;
    }

    // 选工具

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

        // 一次遍历记下每类最快和最靠前的收藏
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String cat = getToolCategory(stack);
            float speed = stack.getDestroySpeed(state);

            // 没收藏的只换更快的
            if (i != selected && speed > currentSpeed) {
                Best prev = fastestPerCat.get(cat);
                if (prev == null || speed > prev.speed)
                    fastestPerCat.put(cat, new Best(i, speed));
            }

            // 收藏的不看速度
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

        // 先看收藏，再看最快
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

    // 只缓存自动识别
    private static final Map<Item, String> CATEGORY_CACHE = new IdentityHashMap<>();

    static String getToolCategory(ItemStack stack) {
        if (stack.isEmpty()) return "other";
        Item item = stack.getItem();

        // 手动分类优先
        String override = SmartInteractConfig.get().getToolCategoryOverride(
            BuiltInRegistries.ITEM.getKey(item).toString());
        if (override != null && !override.isEmpty()) return override;

        // 自动识别结果按物品缓存
        String cached = CATEGORY_CACHE.get(item);
        if (cached != null) return cached;

        String detected = detectToolCategory(stack);
        CATEGORY_CACHE.put(item, detected);
        return detected;
    }

    private static String detectToolCategory(ItemStack stack) {
        Item item = stack.getItem();

        // 原版工具
        if (item instanceof PickaxeItem) return "pickaxe";
        if (item instanceof ShovelItem)  return "shovel";
        if (item instanceof AxeItem)     return "axe";
        if (item instanceof HoeItem)     return "hoe";
        if (item instanceof SwordItem)   return "sword";
        if (item instanceof ShearsItem)  return "other";

        // 模组工具先问 NeoForge 标准能力
        if (stack.canPerformAction(ItemAbilities.PICKAXE_DIG)) return "pickaxe";
        if (stack.canPerformAction(ItemAbilities.SHOVEL_DIG))  return "shovel";
        if (stack.canPerformAction(ItemAbilities.AXE_DIG))     return "axe";
        if (stack.canPerformAction(ItemAbilities.HOE_DIG))     return "hoe";
        if (stack.canPerformAction(ItemAbilities.SWORD_DIG))   return "sword";
        if (stack.canPerformAction(ItemAbilities.SHEARS_DIG))  return "other";

        // 再按挖掘速度猜
        String bySpeed = categoryFromDigSpeed(stack);
        if (bySpeed != null) return bySpeed;

        // 最后看物品 id
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (path.contains("pickaxe")) return "pickaxe";
        if (path.contains("shovel") || path.contains("spade")) return "shovel";
        if (path.contains("hoe")) return "hoe";
        if (path.contains("axe")) return "axe";
        if (path.contains("sword") || path.contains("blade")) return "sword";
        return "other";
    }

    // 按代表方块挖掘速度猜，空手是 1.0
    private static String categoryFromDigSpeed(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.contains("shears")) return null;
        if (stack.getDestroySpeed(Blocks.STONE.defaultBlockState()) > 1.0F) return "pickaxe";
        if (stack.getDestroySpeed(Blocks.DIRT.defaultBlockState()) > 1.0F) return "shovel";
        if (stack.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) > 1.0F) return "axe";
        if (stack.getDestroySpeed(Blocks.OAK_LEAVES.defaultBlockState()) > 1.0F) return "hoe";
        return null;
    }

    // 背包辅助

    // 快捷栏直接选中，背包里的先换到当前格
    static void equipSlot(LocalPlayer player, int slot) {
        Inventory inv = player.getInventory();
        if (slot >= 0 && slot < 9) {
            if (inv.selected != slot) {
                inv.selected = slot;
                // 同步给服务端
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

    // 判定

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