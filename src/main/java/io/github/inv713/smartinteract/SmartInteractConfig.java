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

/**
 * 客户端 JSON 配置，路径为 config/smartinteract.json。
 *
 * <p>{@code toolPriority} 决定一个方块能同时被多种工具挖时优先用哪一类
 * （比如泥土用铲还是锄）。调整列表顺序即可改变优先级。</p>
 * <p>类别取值：pickaxe、shovel、axe、hoe、sword。</p>
 *
 * <pre>{@code
 * {
 *   "autoToolSwitch": true,
 *   "requireSneak": false,
 *   "toolPriority": ["pickaxe", "shovel", "axe", "hoe", "sword"],
 *   "mappings": [
 *     { "targetBlock": "modid:block_id", "item": "modid:item_id", "button": "RIGHT" }
 *   ]
 * }}</pre>
 */
public class SmartInteractConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Path.of("config", "smartinteract.json");

    boolean autoToolSwitch = true;
    boolean attackWeaponSwitch = true;
    boolean requireSneak   = false;
    List<String> toolPriority = new ArrayList<>(List.of("pickaxe", "shovel", "axe", "hoe", "sword"));
    final List<String> toolRanking  = new ArrayList<>();
    final List<Mapping> rawMappings = new ArrayList<>();
    final Map<String, List<String>> blockToolPriority = new LinkedHashMap<>();
    /** 手动工具分类：物品 id → 类别 (pickaxe/shovel/axe/hoe/sword)。优先于自动识别。 */
    final Map<String, String> toolCategories = new LinkedHashMap<>();

    private final Map<Block, List<Mapping>> rightClickMap = new HashMap<>();
    private final Map<Block, List<Mapping>> leftClickMap  = new HashMap<>();

    public static class Mapping {
        public String targetBlock;
        public String item;
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

    /** 返回该方块/标签配置的工具优先级，没有配置就用全局默认值。 */
    public List<String> getToolPriorityFor(Block block) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        // 直接按方块 id 匹配
        List<String> prio = blockToolPriority.get(blockId.toString());
        if (prio != null) return prio;
        // 按标签匹配（键以 # 开头）
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

    /** 容错解析 id：非法字符串（例如误输入的空格、反引号）返回 null，绝不抛异常。 */
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
    public void addToolRanking(String itemId) { if (!toolRanking.contains(itemId)) { toolRanking.add(itemId); save(); } }
    public void removeToolRanking(int index) { if (index >= 0 && index < toolRanking.size()) { toolRanking.remove(index); save(); } }

    private static final Set<String> TOOL_CATEGORIES =
        Set.of("pickaxe", "shovel", "axe", "hoe", "sword");

    /** 手动分类优先：返回 null 表示未手动指定或类别非法。 */
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

    public int reload() {
        rightClickMap.clear(); leftClickMap.clear(); rawMappings.clear();
        RawConfig raw;
        try {
            if (Files.exists(CONFIG_PATH)) raw = GSON.fromJson(Files.readString(CONFIG_PATH), RawConfig.class);
            else { raw = new RawConfig(); saveRaw(raw); }
        } catch (Exception e) { raw = new RawConfig(); }
        if (raw == null) raw = new RawConfig();
        autoToolSwitch = raw.autoToolSwitch;
        attackWeaponSwitch = raw.attackWeaponSwitch;
        requireSneak   = raw.requireSneak;
        if (raw.toolPriority != null && !raw.toolPriority.isEmpty()) {
            toolPriority = new ArrayList<>(raw.toolPriority);
        }
        if (raw.toolRanking != null) {
            toolRanking.clear(); toolRanking.addAll(raw.toolRanking);
        }
        if (raw.blockToolPriority != null) {
            blockToolPriority.clear(); blockToolPriority.putAll(raw.blockToolPriority);
        }
        if (raw.toolCategories != null) {
            toolCategories.clear(); toolCategories.putAll(raw.toolCategories);
        }
        if (raw.mappings != null) rawMappings.addAll(raw.mappings);
        rebuildMaps();
        return rawMappings.size();
    }

    void save() {
        RawConfig raw = new RawConfig();
        raw.autoToolSwitch = autoToolSwitch;
        raw.attackWeaponSwitch = attackWeaponSwitch;
        raw.requireSneak   = requireSneak;
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

            // 标签匹配，例如 #minecraft:logs
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

            // 直接按方块 id 匹配
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
        List<String> toolPriority = new ArrayList<>(List.of("pickaxe", "shovel", "axe", "hoe", "sword"));
        List<String> toolRanking  = new ArrayList<>();
        Map<String, List<String>> blockToolPriority = new LinkedHashMap<>();
        Map<String, String> toolCategories = new LinkedHashMap<>();
        List<Mapping> mappings = new ArrayList<>();
    }
}