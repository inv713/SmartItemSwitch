package io.github.inv713.smartinteract;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/** 配置界面，按 ` 打开。 */
public class MappingScreen extends Screen {

    private static final int WIDTH  = 290;
    private static final int HEIGHT = 330;
    // 屏幕矮时压缩面板
    private static final int MIN_PANEL_HEIGHT = 232;
    private int panelHeight = HEIGHT;

    private final Screen lastScreen;
    private EditBox blockField;
    private EditBox itemField;
    private Button rightBtn;
    private Button leftBtn;
    private Button autoToolBtn;
    private Button attackWeaponBtn;

    private boolean isRight = true;
    private int scrollOffset;
    private int listTop;
    private int maxVisible;
    private static final int ITEM_H = 16;

    // 工具排序
    private EditBox rankField;
    private int rankScrollOffset;
    private int rankListTop;
    private int rankMaxVisible;

    private boolean showHelp;
    private int helpScrollOffset;

    // 帮助页内容，超出部分滚轮翻
    private static final String[] HELP_LINES = {
        "对准: 将准星瞄准的方块填入方块栏",
        "手持: 将手持物品填入物品栏",
        "工具: 剑/斧/镐/铲/锄自动识别",
        "  Shift+点类别按钮 = 手动记入/取消分类",
        "",
        "方块名以 # 开头 = 方块标签",
        "  例: #minecraft:logs 匹配所有原木",
        "",
        "映射顺序 = fallback 顺序",
        "  用户映射优先于默认自动切换",
        "",
        "工具排序: 上 = 高优先级",
        "  左键挖方块时按此顺序选工具",
        "攻击时: 排序(收藏)中的武器优先",
        "  底部 [攻击切武器] 开关控制",
        "",
        "blockToolPriority (JSON 中配置):",
        "  #minecraft:dirt → [shovel, hoe]",
        "  改成 [hoe, shovel] 可优先锄地",
        "  方块 id 比标签更优先",
        "",
        "默认优先级只写入一次，删掉不会自动恢复",
        "",
        "界面 [重置] 连点两次 = 恢复默认",
        "  会清空映射、收藏、手动分类",
        "",
        "toolCategories (JSON 中配置):",
        "  \"modid:emerald_pickaxe\": \"pickaxe\"",
    };
    private static final int HELP_LINE_HEIGHT = 14;

    // 动作栏在界面里看不到，提示画在界面内
    private String statusMessage;
    private int statusTicks;
    // 重置确认倒计时
    private int resetConfirmTicks;

    private final SmartInteractConfig config = SmartInteractConfig.get();

    public MappingScreen(Screen lastScreen) {
        super(Component.literal("智能物品切换"));
        this.lastScreen = lastScreen;
    }

    // 生命周期

    @Override
    protected void init() {
        // 屏幕矮就少显示几行
        panelHeight = Math.max(MIN_PANEL_HEIGHT, Math.min(HEIGHT, height - 8));
        if (panelHeight > height) panelHeight = height;
        int guiLeft = Math.max(0, (width - WIDTH) / 2);
        int guiTop  = Math.max(0, (height - panelHeight) / 2);

        // 方块栏
        blockField = new EditBox(font, guiLeft + 45, guiTop + 16, 187, 16, Component.literal("block"));
        blockField.setMaxLength(128);
        addRenderableWidget(blockField);

        addRenderableWidget(Button.builder(Component.literal("对准"), b -> fillBlockFromCrosshair())
            .bounds(guiLeft + 236, guiTop + 15, 42, 18).build());

        // 物品栏
        itemField = new EditBox(font, guiLeft + 45, guiTop + 36, 187, 16, Component.literal("item"));
        itemField.setMaxLength(128);
        addRenderableWidget(itemField);

        addRenderableWidget(Button.builder(Component.literal("手持"), b -> fillItemFromHand())
            .bounds(guiLeft + 236, guiTop + 35, 42, 18).build());

        // 工具类别
        String[][] toolButtons = {
            {"剑", "sword", "minecraft:wooden_sword"},
            {"斧", "axe", "minecraft:wooden_axe"},
            {"镐", "pickaxe", "minecraft:wooden_pickaxe"},
            {"铲", "shovel", "minecraft:wooden_shovel"},
            {"锄", "hoe", "minecraft:wooden_hoe"},
        };
        for (int i = 0; i < toolButtons.length; i++) {
            final String category = toolButtons[i][1];
            final String fallbackId = toolButtons[i][2];
            addRenderableWidget(Button.builder(Component.literal(toolButtons[i][0]),
                    b -> fillToolCategory(category, fallbackId))
                .bounds(guiLeft + 10 + i * 55, guiTop + 56, 50, 18).build());
        }

        // 左右键、添加、重置
        rightBtn = Button.builder(Component.literal("右键"), b -> toggleButton(true))
            .bounds(guiLeft + 30, guiTop + 78, 55, 18).build();
        leftBtn  = Button.builder(Component.literal("左键"),  b -> toggleButton(false))
            .bounds(guiLeft + 90, guiTop + 78, 55, 18).build();
        addRenderableWidget(rightBtn);
        addRenderableWidget(leftBtn);
        updateButtonColors();

        addRenderableWidget(Button.builder(Component.literal("添加"), b -> addCurrentMapping())
            .bounds(guiLeft + 150, guiTop + 78, 50, 18).build());

        addRenderableWidget(Button.builder(Component.literal("重置"), b -> confirmOrReset())
            .bounds(guiLeft + 205, guiTop + 78, 55, 18).build());

        // 映射列表行数
        listTop = guiTop + 114;
        int extra = panelHeight - MIN_PANEL_HEIGHT;
        maxVisible = 1 + Math.min(3, extra / ITEM_H);

        // 排序区
        int rankSepY  = listTop + maxVisible * ITEM_H + 4;   // ~182
        int rankFldY  = rankSepY + 14;                       // ~196
        rankListTop   = rankFldY + ITEM_H + 6;               // ~218
        int remaining = extra - (maxVisible - 1) * ITEM_H;
        rankMaxVisible = 1 + Math.min(3, remaining / ITEM_H);

        rankField = new EditBox(font, guiLeft + 50, rankFldY, 145, 16, Component.literal("tool rank"));
        rankField.setMaxLength(128);
        addRenderableWidget(rankField);

        addRenderableWidget(Button.builder(Component.literal("手持"), b -> fillRankFromHand())
            .bounds(guiLeft + 200, rankFldY - 1, 38, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> addRanking())
            .bounds(guiLeft + 242, rankFldY - 1, 20, 18).build());

        // 底部开关
        autoToolBtn = Button.builder(toggleLabel("自动工具", config.isAutoToolSwitch()), b -> {
                config.toggleAutoToolSwitch(); rebuildToggles(); })
            .bounds(guiLeft + 10, guiTop + panelHeight - 34, 88, 18).build();
        addRenderableWidget(autoToolBtn);

        attackWeaponBtn = Button.builder(toggleLabel("攻击切武器", config.isAttackWeaponSwitch()), b -> {
                config.toggleAttackWeaponSwitch(); rebuildToggles(); })
            .bounds(guiLeft + 102, guiTop + panelHeight - 34, 88, 18).build();
        addRenderableWidget(attackWeaponBtn);

        addRenderableWidget(Button.builder(Component.literal("帮助"), b -> {
            showHelp = !showHelp;
            if (showHelp) helpScrollOffset = 0;
        }).bounds(guiLeft + 194, guiTop + panelHeight - 34, 40, 18).build());

        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose())
            .bounds(guiLeft + WIDTH - 55, guiTop + panelHeight - 34, 45, 18).build());

        // 打开时自动填准星方块
        if (blockField.getValue().isEmpty()) {
            fillBlockFromCrosshair();
        }
        setInitialFocus(blockField);
    }

    @Override
    public void tick() {
        super.tick();
        if (statusTicks > 0) statusTicks--;
        if (resetConfirmTicks > 0) resetConfirmTicks--;
    }

    // 准星/手持检测

    private void fillBlockFromCrosshair() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult hit = (BlockHitResult) mc.hitResult;
            BlockState state = mc.level.getBlockState(hit.getBlockPos());
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            blockField.setValue(id.toString());
        }
    }

    private void fillItemFromHand() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(held.getItem());
            itemField.setValue(id.toString());
        }
    }

    // 普通点击优先手持，Shift 点击手动记类别
    private void fillToolCategory(String category, String fallbackId) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        ItemStack held = player.getMainHandItem();

        // Shift 点类别
        if (hasShiftDown()) {
            String id = held.isEmpty()
                ? itemField.getValue().trim()
                : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            if (id.isEmpty()) {
                setStatus("先手持一个物品，或在物品栏填入 id");
                return;
            }
            if (category.equals(config.getToolCategoryOverride(id))) {
                config.removeToolCategoryOverride(id);
                setStatus("已取消手动分类: " + id);
            } else {
                config.setToolCategoryOverride(id, category);
                itemField.setValue(id);
                setStatus("已记入" + categoryLabel(category) + "类: " + id);
            }
            return;
        }

        // 手持优先
        if (!held.isEmpty() && category.equals(InteractionHandler.getToolCategory(held))) {
            itemField.setValue(BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
            return;
        }
        // 扫描背包
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && category.equals(InteractionHandler.getToolCategory(stack))) {
                itemField.setValue(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                return;
            }
        }
        itemField.setValue(fallbackId);
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "pickaxe" -> "镐";
            case "shovel"  -> "铲";
            case "axe"     -> "斧";
            case "hoe"     -> "锄";
            case "sword"   -> "剑";
            default        -> category;
        };
    }

    // 当前手持填进排序框
    private void fillRankFromHand() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(held.getItem());
            rankField.setValue(id.toString());
        }
    }

    private void addRanking() {
        String itemId = rankField.getValue().trim();
        if (itemId.isEmpty()) return;
        ResourceLocation id = SmartInteractConfig.parseIdOrNull(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            setStatus("无法收藏: 不存在的物品 " + itemId);
            return;
        }
        config.addToolRanking(itemId);
        rankField.setValue("");
        setStatus("已收藏: " + itemId);
    }

    // 辅助方法

    private void updateButtonColors() {
        rightBtn.active = !isRight;
        leftBtn.active  = isRight;
    }

    private void toggleButton(boolean right) {
        isRight = right;
        updateButtonColors();
    }

    private Component toggleLabel(String prefix, boolean on) {
        return Component.literal(prefix + ": " + (on ? "ON" : "OFF"));
    }

    private void setStatus(String message) {
        statusMessage = message;
        statusTicks = 120;
    }

    // 连点两次确认
    private void confirmOrReset() {
        if (resetConfirmTicks <= 0) {
            resetConfirmTicks = 100;
            setStatus("再点一次[重置]确认: 会清空映射/收藏/手动分类");
            return;
        }
        resetConfirmTicks = 0;
        config.resetToDefaults();
        scrollOffset = 0;
        rankScrollOffset = 0;
        rebuildToggles();
        setStatus("已恢复默认配置");
    }

    private void rebuildToggles() {
        SmartInteractConfig cfg = SmartInteractConfig.get();
        autoToolBtn.setMessage(toggleLabel("自动工具", cfg.isAutoToolSwitch()));
        attackWeaponBtn.setMessage(toggleLabel("攻击切武器", cfg.isAttackWeaponSwitch()));
    }

    private void addCurrentMapping() {
        String block = blockField.getValue().trim();
        String item  = itemField.getValue().trim();
        if (block.isEmpty() || item.isEmpty()) return;

        // 校验 id
        String error = validateMapping(block, item);
        if (error != null) {
            setStatus("无法添加: " + error);
            return;
        }

        config.addMapping(block, item, isRight ? "RIGHT" : "LEFT");
        blockField.setValue("");
        itemField.setValue("");
        setStatus("已添加映射: " + block + " → " + item);
    }

    // 合法返回 null，否则返回错误
    private static String validateMapping(String block, String item) {
        ResourceLocation itemId = SmartInteractConfig.parseIdOrNull(item);
        if (itemId == null) return "物品 id 格式非法: " + item;
        if (!BuiltInRegistries.ITEM.containsKey(itemId)) return "不存在的物品: " + item;

        if (block.startsWith("#")) {
            ResourceLocation tagId = SmartInteractConfig.parseIdOrNull(block.substring(1));
            if (tagId == null) return "方块标签格式非法: " + block;
            if (BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, tagId)).isEmpty())
                return "不存在的方块标签: " + block;
        } else {
            ResourceLocation blockId = SmartInteractConfig.parseIdOrNull(block);
            if (blockId == null) return "方块 id 格式非法: " + block;
            if (!BuiltInRegistries.BLOCK.containsKey(blockId)) return "不存在的方块: " + block;
        }
        return null;
    }

    // 渲染

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);

        int guiLeft = Math.max(0, (width - WIDTH) / 2);
        int guiTop  = Math.max(0, (height - panelHeight) / 2);

        // 帮助页
        if (showHelp) {
            int panelBottom = Math.min(guiTop + panelHeight, height - 4);
            g.fill(guiLeft, guiTop, guiLeft + WIDTH, panelBottom, 0xFF0E0E0E);
            g.drawString(font, "智能物品切换 使用说明", guiLeft + 12, guiTop + 12, 0xFFFF55);

            int textTop = helpTop(guiTop);
            int textBottom = helpTextBottom(guiTop);
            int visible = Math.max(1, (textBottom - textTop) / HELP_LINE_HEIGHT);
            int maxOffset = Math.max(0, HELP_LINES.length - visible);
            helpScrollOffset = Math.max(0, Math.min(helpScrollOffset, maxOffset));

            g.enableScissor(guiLeft + 8, textTop - 2, guiLeft + WIDTH - 18, textBottom);
            for (int i = 0; i < visible && helpScrollOffset + i < HELP_LINES.length; i++) {
                g.drawString(font, HELP_LINES[helpScrollOffset + i], guiLeft + 12,
                    textTop + i * HELP_LINE_HEIGHT, 0xCCCCCC);
            }
            g.disableScissor();

            // 右侧滚动条
            if (maxOffset > 0) {
                int barX = guiLeft + WIDTH - 14;
                int barHeight = textBottom - textTop;
                g.fill(barX, textTop, barX + 4, textBottom, 0x40FFFFFF);
                int thumbHeight = Math.max(10, barHeight * visible / HELP_LINES.length);
                int thumbY = textTop + (barHeight - thumbHeight) * helpScrollOffset / maxOffset;
                g.fill(barX, thumbY, barX + 4, thumbY + thumbHeight, 0xFFAAAAAA);
            }

            String hint = maxOffset > 0 ? "滚轮翻页 · 点击任意位置关闭" : "点击任意位置关闭";
            int hintY = Math.min(guiTop + panelHeight - 18, panelBottom - 12);
            g.drawString(font, hint, guiLeft + 12, hintY, 0x808080);
            return;
        }

        // 面板背景
        g.fill(guiLeft, guiTop, guiLeft + WIDTH, guiTop + panelHeight, 0xF0101010);

        super.render(g, mouseX, mouseY, partialTick);

        // 标题
        g.drawString(font, "方块:", guiLeft + 10, guiTop + 19,  0xA0A0A0);
        g.drawString(font, "物品:",  guiLeft + 10, guiTop + 39,  0xA0A0A0);

        // 映射分隔线
        int sepY = guiTop + 100;
        g.hLine(guiLeft + 10, guiLeft + WIDTH - 10, sepY, 0x55FFFFFF);
        g.drawCenteredString(font, "映射列表 (点[X]删除)", guiLeft + WIDTH / 2, sepY + 2, 0x808080);

        // 映射列表
        List<SmartInteractConfig.Mapping> mappings = config.getMappings();
        int total = mappings.size();
        // 修正滚动位置
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, total - maxVisible)));
        int renderY = listTop;

        for (int i = 0; i < Math.min(total - scrollOffset, maxVisible); i++) {
            int idx = scrollOffset + i;
            SmartInteractConfig.Mapping m = mappings.get(idx);

            String line = shorten(m.targetBlock, 17) + " → " + shorten(m.item, 17);
            g.drawString(font, line, guiLeft + 12, renderY, 0xE0E0E0);

            String btn = "RIGHT".equals(m.button) ? "右键" : "LEFT".equals(m.button) ? "左键" : "?";
            g.drawString(font, btn, guiLeft + 210, renderY, "右键".equals(btn) ? 0x55FF55 : 0xFFAA55);

            // [X] 删除
            int xX = guiLeft + 253;
            boolean hover = mouseX >= xX && mouseX < xX + 26 && mouseY >= renderY - 2 && mouseY < renderY + 12;
            g.drawString(font, "[X]", xX, renderY, hover ? 0xFF4444 : 0xAA4444);

            renderY += ITEM_H;
        }

        if (total > maxVisible) {
            String scroll = (scrollOffset + 1) + "-" + Math.min(scrollOffset + maxVisible, total) + " / " + total;
            g.drawString(font, scroll, guiLeft + WIDTH - 40, sepY + 2, 0x808080);
        }

        // 排序区
        int rankSepY = listTop + maxVisible * ITEM_H + 4;
        g.hLine(guiLeft + 10, guiLeft + WIDTH - 10, rankSepY, 0x55FFFFFF);
        g.drawString(font, "工具排序 (上到下=高到低)", guiLeft + 12, rankSepY + 4, 0x808080);

        List<String> ranking = config.getToolRanking();
        rankScrollOffset = Math.max(0, Math.min(rankScrollOffset,
            Math.max(0, ranking.size() - rankMaxVisible)));
        int rankY = rankListTop;
        for (int i = 0; i < Math.min(ranking.size() - rankScrollOffset, rankMaxVisible); i++) {
            int idx = rankScrollOffset + i;
            String rid = ranking.get(idx);
            g.drawString(font, (idx + 1) + ". " + shorten(rid, 20), guiLeft + 12, rankY, 0xE0E0E0);

            // 上移/下移/删除
            int bx = guiLeft + 205;
            boolean upHov = mouseX >= bx && mouseX < bx + 14 && mouseY >= rankY - 2 && mouseY < rankY + 12;
            boolean dnHov = mouseX >= bx + 16 && mouseX < bx + 30 && mouseY >= rankY - 2 && mouseY < rankY + 12;
            boolean xHov  = mouseX >= bx + 32 && mouseX < bx + 46 && mouseY >= rankY - 2 && mouseY < rankY + 12;
            g.drawString(font, "↑", bx,      rankY, upHov ? 0x55FF55 : 0x55AA55);
            g.drawString(font, "↓", bx + 16, rankY, dnHov ? 0x55FF55 : 0x55AA55);
            g.drawString(font, "X", bx + 32, rankY, xHov  ? 0xFF4444 : 0xAA4444);
            rankY += ITEM_H;
        }

        if (ranking.size() > rankMaxVisible) {
            String rsc = (rankScrollOffset + 1) + "-" + Math.min(rankScrollOffset + rankMaxVisible, ranking.size()) + " / " + ranking.size();
            g.drawString(font, rsc, guiLeft + WIDTH - 40, rankSepY + 4, 0x808080);
        }

        // 状态提示
        if (statusMessage != null && statusTicks > 0) {
            g.drawString(font, shorten(statusMessage, 38), guiLeft + 10,
                guiTop + panelHeight - 46, 0xFFFF55);
        }
    }

    // 输入处理

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // 帮助页优先吃点击
        if (showHelp) { showHelp = false; return true; }
        if (btn == 0) {
            int guiLeft = Math.max(0, (width - WIDTH) / 2);

            // 映射列表删除
            List<SmartInteractConfig.Mapping> mappings = config.getMappings();
            int renderY = listTop;
            for (int i = 0; i < Math.min(mappings.size() - scrollOffset, maxVisible); i++) {
                int idx = scrollOffset + i;
                int xX = guiLeft + 253;
                if (mx >= xX && mx < xX + 26 && my >= renderY - 2 && my < renderY + 12) {
                    config.removeMapping(idx);
                    if (scrollOffset >= config.getMappings().size())
                        scrollOffset = Math.max(0, config.getMappings().size() - 1);
                    return true;
                }
                renderY += ITEM_H;
            }

            // 排序列表按钮
            List<String> ranking = config.getToolRanking();
            int rankY = rankListTop;
            for (int i = 0; i < Math.min(ranking.size() - rankScrollOffset, rankMaxVisible); i++) {
                int idx = rankScrollOffset + i;
                int bx = guiLeft + 205;
                if (mx >= bx && mx < bx + 14 && my >= rankY - 2 && my < rankY + 12) {
                    // 上移
                    if (idx > 0) config.moveToolRanking(idx, idx - 1);
                    return true;
                }
                if (mx >= bx + 16 && mx < bx + 30 && my >= rankY - 2 && my < rankY + 12) {
                    // 下移
                    if (idx < ranking.size() - 1) config.moveToolRanking(idx, idx + 1);
                    return true;
                }
                if (mx >= bx + 32 && mx < bx + 46 && my >= rankY - 2 && my < rankY + 12) {
                    // 删除
                    config.removeToolRanking(idx);
                    if (rankScrollOffset >= config.getToolRanking().size())
                        rankScrollOffset = Math.max(0, config.getToolRanking().size() - 1);
                    return true;
                }
                rankY += ITEM_H;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // 帮助页优先吃滚轮
        if (showHelp) {
            int guiTop = Math.max(0, (height - panelHeight) / 2);
            int visible = helpVisibleLines(guiTop);
            int maxOffset = Math.max(0, HELP_LINES.length - visible);
            int direction = sy > 0 ? 1 : (sy < 0 ? -1 : 0);
            helpScrollOffset = (int) Math.clamp(helpScrollOffset - direction, 0, maxOffset);
            return true;
        }

        // 判断鼠标是否在排序区
        int rankSepY = listTop + maxVisible * ITEM_H + 4;
        List<String> ranking = config.getToolRanking();
        if (my >= rankSepY && ranking.size() > rankMaxVisible) {
            rankScrollOffset = (int) Math.clamp(
                rankScrollOffset - sy, 0, Math.max(0, ranking.size() - rankMaxVisible));
            return true;
        }
        // 映射列表滚动
        List<SmartInteractConfig.Mapping> mappings = config.getMappings();
        if (mappings.size() > maxVisible) {
            scrollOffset = (int) Math.clamp(
                scrollOffset - sy, 0, Math.max(0, mappings.size() - maxVisible));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(lastScreen);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private int helpTop(int guiTop) {
        return guiTop + 32;
    }

    // 帮助文字的可视底边
    private int helpTextBottom(int guiTop) {
        int panelBottom = Math.min(guiTop + panelHeight, height - 4);
        return Math.max(helpTop(guiTop) + HELP_LINE_HEIGHT,
            Math.min(guiTop + panelHeight - 26, panelBottom - 18));
    }

    private int helpVisibleLines(int guiTop) {
        return Math.max(1, (helpTextBottom(guiTop) - helpTop(guiTop)) / HELP_LINE_HEIGHT);
    }

    private static String shorten(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 2) + "..";
    }
}