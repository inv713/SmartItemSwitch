package io.github.inv713.smartinteract;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 客户端配置，路径 config/smartinteract.json。 */
public class SmartInteractConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Path.of("config", "smartinteract.json");

    // 首次生成的默认方块优先级，之后以 JSON 为准
    private static final Map<String, List<String>> DEFAULT_BLOCK_TOOL_PRIORITY = createDefaultBlockToolPriority();

    private static Map<String, List<String>> createDefaultBlockToolPriority() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        // 树叶：剪刀另算，这里给锄/剑兜底
        map.put("#minecraft:leaves", List.of("hoe", "sword"));
        // 泥土默认铲土径，想优先锄地就调换顺序
        map.put("#minecraft:dirt", List.of("shovel", "hoe"));
        map.put("#minecraft:logs", List.of("axe"));
        map.put("#minecraft:mineable/pickaxe", List.of("pickaxe"));
        map.put("#minecraft:mineable/axe", List.of("axe"));
        map.put("#minecraft:mineable/shovel", List.of("shovel"));
        map.put("#minecraft:mineable/hoe", List.of("hoe"));
        return Collections.unmodifiableMap(map);
    }

    boolean autoToolSwitch = true;
    boolean attackWeaponSwitch = true;
    boolean requireSneak   = false;
    // 默认优先级只装一次，删了不再加回来
    boolean defaultBlockPriorityInstalled;
    // 方块没匹配到时的全局优先级
    List<String> toolPriority = new ArrayList<>(List.of("pickaxe", "shovel", "axe", "hoe", "sword"));
    // 收藏工具，越靠前越优先
    final List<String> toolRanking  = new ArrayList<>();
    // 手写映射
    final List<Mapping> rawMappings = new ArrayList<>();
    // 方块/标签 → 工具类别优先级
    final Map<String, List<String>> blockToolPriority = new LinkedHashMap<>();
    // 手动分类，优先于自动识别
    final Map<String, String> toolCategories = new LinkedHashMap<>();

    // 右键索引
    private final Map<Block, List<Mapping>> rightClickMap = new HashMap<>();
    // 左键索引
    private final Map<Block, List<Mapping>> leftClickMap  = new HashMap<>();

    public static class Mapping {
        // 方块 id 或 #标签
        public String targetBlock;
        // 物品 id
        public String item;
        // RIGHT / LEFT
        public String button = "RIGHT";

        public Mapping() {}
        public Mapping(String block, String item, String button) {
            this.targetBlock = block; this.item = item; this.button = button;
        }
    }

    private static SmartInteractConfig instance;

    public static SmartInteractConfig get() {
        if (instance == null) { instance = new SmartInteractConfig(); instance.reload(); }
        return instance;
    }

    public boolean isAutoToolSwitch() { return autoToolSwitch; }
    public boolean isRequireSneak()   { return requireSneak; }
    public List<String> getToolRanking()  { return Collections.unmodifiableList(toolRanking); }

    // 查方块自己的优先级，没有就用全局
    public List<String> getToolPriorityFor(Block block) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        // 普通方块 id
        List<String> prio = blockToolPriority.get(blockId.toString());
        if (prio != null) return prio;
        // # 开头按标签
        for (var entry : blockToolPriority.entrySet()) {
            if (entry.getKey().startsWith("#")) {
                ResourceLocation tagId = parseIdOrNull(entry.getKey().substring(1));
                if (tagId == null) continue;
                TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, tagId);
                if (block.defaultBlockState().is(tagKey))
                    return entry.getValue();
            }
        }
        return toolPriority;
    }

    // 解析 id，写错返回 null
    public static ResourceLocation parseIdOrNull(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        try {
            return ResourceLocation.parse(v);
        } catch (Exception e) {
            return null;
        }
    }
    public void toggleAutoToolSwitch()       { autoToolSwitch = !autoToolSwitch; save(); }
    public boolean isAttackWeaponSwitch()    { return attackWeaponSwitch; }
    public void toggleAttackWeaponSwitch()   { attackWeaponSwitch = !attackWeaponSwitch; save(); }

    // 恢复默认配置
    public void resetToDefaults() {
        autoToolSwitch = true;
        attackWeaponSwitch = true;
        requireSneak = false;
        toolPriority = new ArrayList<>(List.of("pickaxe", "shovel", "axe", "hoe", "sword"));
        toolRanking.clear();
        blockToolPriority.clear();
        blockToolPriority.putAll(DEFAULT_BLOCK_TOOL_PRIORITY);
        defaultBlockPriorityInstalled = true;
        toolCategories.clear();
        rawMappings.clear();
        rebuildMaps();
        save();
    }
    public void addToolRanking(String itemId) { if (!toolRanking.contains(itemId)) { toolRanking.add(itemId); save(); } }
    public void removeToolRanking(int index) { if (index >= 0 && index < toolRanking.size()) { toolRanking.remove(index); save(); } }

    private static final Set<String> TOOL_CATEGORIES =
        Set.of("pickaxe", "shovel", "axe", "hoe", "sword");

    // 没手动分类或类别非法时返回 null
    public String getToolCategoryOverride(String itemId) {
        String category = toolCategories.get(itemId);
        if (category == null) return null;
        category = category.trim().toLowerCase(Locale.ROOT);
        return TOOL_CATEGORIES.contains(category) ? category : null;
    }
    public void setToolCategoryOverride(String itemId, String category) {
        toolCategories.put(itemId, category); save();
    }
    public void removeToolCategoryOverride(String itemId) { toolCategories.remove(itemId); save(); }
    public void moveToolRanking(int from, int to) {
        if (from >= 0 && from < toolRanking.size() && to >= 0 && to < toolRanking.size()) {
            String item = toolRanking.remove(from); toolRanking.add(to, item); save();
        }
    }
    public List<Mapping> getRightClickMappings(Block block) { return rightClickMap.getOrDefault(block, List.of()); }
    public List<Mapping> getLeftClickMappings(Block block)   { return leftClickMap.getOrDefault(block, List.of()); }
    public List<Mapping> getMappings()               { return Collections.unmodifiableList(rawMappings); }

    public void addMapping(String blockId, String itemId, String button) {
        rawMappings.add(new Mapping(blockId, itemId, button.toUpperCase()));
        rebuildMaps(); save();
    }

    public void removeMapping(int index) {
        if (index >= 0 && index < rawMappings.size()) { rawMappings.remove(index); rebuildMaps(); save(); }
    }

    private void reload() {
        rightClickMap.clear(); leftClickMap.clear(); rawMappings.clear();
        RawConfig raw;
        boolean parsed = true;
        try {
            if (Files.exists(CONFIG_PATH)) raw = GSON.fromJson(Files.readString(CONFIG_PATH), RawConfig.class);
            else { raw = new RawConfig(); saveRaw(raw); }
        } catch (Exception e) {
            // 文件坏了用默认值，不覆盖原文件
            raw = new RawConfig();
            parsed = false;
        }
        if (raw == null) raw = new RawConfig();
        autoToolSwitch = raw.autoToolSwitch;
        attackWeaponSwitch = raw.attackWeaponSwitch;
        requireSneak   = raw.requireSneak;
        // 空列表就是不要兜底
        if (raw.toolPriority != null) toolPriority = new ArrayList<>(raw.toolPriority);
        toolRanking.clear();
        if (raw.toolRanking != null) toolRanking.addAll(raw.toolRanking);
        blockToolPriority.clear();
        if (raw.blockToolPriority != null) blockToolPriority.putAll(raw.blockToolPriority);
        defaultBlockPriorityInstalled = raw.defaultBlockPriorityInstalled;
        boolean addedDefaults = false;
        // 默认值只装一次
        if (!defaultBlockPriorityInstalled) {
            for (var entry : DEFAULT_BLOCK_TOOL_PRIORITY.entrySet()) {
                if (!blockToolPriority.containsKey(entry.getKey())) {
                    blockToolPriority.put(entry.getKey(), entry.getValue());
                    addedDefaults = true;
                }
            }
            defaultBlockPriorityInstalled = true;
            addedDefaults = true;
        }
        toolCategories.clear();
        if (raw.toolCategories != null) toolCategories.putAll(raw.toolCategories);
        if (raw.mappings != null) rawMappings.addAll(raw.mappings);
        rebuildMaps();
        // 把默认值写进文件，方便直接改
        if (addedDefaults && parsed) save();
    }

    void save() {
        RawConfig raw = new RawConfig();
        raw.autoToolSwitch = autoToolSwitch;
        raw.attackWeaponSwitch = attackWeaponSwitch;
        raw.requireSneak   = requireSneak;
        raw.defaultBlockPriorityInstalled = defaultBlockPriorityInstalled;
        raw.toolPriority   = new ArrayList<>(toolPriority);
        raw.toolRanking    = new ArrayList<>(toolRanking);
        raw.blockToolPriority = new LinkedHashMap<>(blockToolPriority);
        raw.toolCategories = new LinkedHashMap<>(toolCategories);
        raw.mappings.addAll(rawMappings);
        saveRaw(raw);
    }

    private void saveRaw(RawConfig raw) {
        try { Files.createDirectories(CONFIG_PATH.getParent()); Files.writeString(CONFIG_PATH, GSON.toJson(raw)); }
        catch (IOException ignored) {}
    }

    private void rebuildMaps() {
        rightClickMap.clear(); leftClickMap.clear();
        for (Mapping m : rawMappings) {
            if (m.targetBlock == null || m.item == null) continue;

            // # 开头按标签
            if (m.targetBlock.startsWith("#")) {
                String tagId = m.targetBlock.substring(1);
                ResourceLocation tagLocation = parseIdOrNull(tagId);
                if (tagLocation == null) continue;
                TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, tagLocation);
                BuiltInRegistries.BLOCK.getTag(tagKey).ifPresent(tag -> {
                    for (var holder : tag) {
                        Block block = holder.value();
                        if ("RIGHT".equalsIgnoreCase(m.button))
                            rightClickMap.computeIfAbsent(block, k -> new ArrayList<>()).add(m);
                        else
                            leftClickMap.computeIfAbsent(block, k -> new ArrayList<>()).add(m);
                    }
                });
                continue;
            }

            // 普通方块 id
            ResourceLocation blockId = parseIdOrNull(m.targetBlock);
            if (blockId == null) continue;
            Block block = BuiltInRegistries.BLOCK.get(blockId);
            if (block == null) continue;
            if ("RIGHT".equalsIgnoreCase(m.button))
                rightClickMap.computeIfAbsent(block, k -> new ArrayList<>()).add(m);
            else
                leftClickMap.computeIfAbsent(block, k -> new ArrayList<>()).add(m);
        }
    }

    private static class RawConfig {
        boolean autoToolSwitch = true;
        boolean attackWeaponSwitch = true;
        boolean requireSneak   = false;
        boolean defaultBlockPriorityInstalled = false;
        List<String> toolPriority = new ArrayList<>(List.of("pickaxe", "shovel", "axe", "hoe", "sword"));
        List<String> toolRanking  = new ArrayList<>();
        Map<String, List<String>> blockToolPriority = new LinkedHashMap<>();
        Map<String, String> toolCategories = new LinkedHashMap<>();
        List<Mapping> mappings = new ArrayList<>();
    }
}