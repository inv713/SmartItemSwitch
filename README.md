# 智能物品切换

Minecraft 1.21.1 + NeoForge 21.1.x 的纯客户端模组。
给方块设置左键或右键要交互的物品，交互时自动查找最优工具从背包替换到手上，攻击自动切换武器。

## 功能

- 左键/右键方块时自动换物品，支持方块标签（例如 `#minecraft:logs`）
- 右键原木/木头自动换斧去皮，右键铜块自动换斧刮锈/脱蜡，右键泥土类自动换铲或锄
- 挖方块时挑最快的工具；树叶、羊毛、蜘蛛网、绊线、藤蔓类优先剪刀
- 南瓜雕刻、蜂巢取蜜、生长植物修剪等剪刀特殊机制优先
- 攻击时自动换武器。
- 模组工具识别不了时，手持物品按 Shift 再点类别按钮，就能手动归类；
- 按 ` 键打开配置界面。

## 安装

需要 Minecraft 1.21.1、NeoForge 21.1.x、Java 21。

把 `smartinteract-neoforge-1.21.1-1.0.0.jar` 放进 `mods` 文件夹，启动游戏即可。

## 用法

按 ` 打开界面。准星对着方块按「从准星」填方块 id，手持物品按「从手持」填物品 id，选左键或右键，点「添加」。

映射按列表顺序尝试，第一个条件不满足就试下一个。

- 「自动工具」控制左键/右键的自动换工具（手写映射不受此开关影响）
- 「攻击切武器」控制打怪时换武器
- 排序列表里的工具和武器优先使用
- 「重置」连点两次恢复内置默认配置，会清空映射、收藏和手动分类

配置文件在 `config/smartinteract.json`

默认方块优先级只会在首次安装时写进配置的 `blockToolPriority`
## 配置优先级

1. 玩家手写映射：目标物品在背包里时直接使用，优先于所有默认规则。
2. 玩家手动分类：Shift+点类别按钮或 `toolCategories` 里的类别，优先于自动识别。
3. 内置默认方块优先级：只在首次生成配置时写入，删掉后不会被重新加回来。
4. 全局 `toolPriority`：方块没有匹配到任何规则时兜底。

## 已知限制

只支持 1.21.1 + NeoForge 21.1.x。只翻背包 0–35 格，不管副手和模组背包。创造模式下不会自动切换。

## 构建

```
./gradlew build
```

产物在 `build/libs/`。

## 协议

MIT，见 [LICENSE](LICENSE)。

## English

A client-side NeoForge mod for Minecraft 1.21.1. Assign an item to a block's left- or right-click action and it is swapped in automatically; mining picks the fastest tool (ranked tools first), attacking switches to your best weapon. Press ` to configure in-game.

Requires Minecraft 1.21.1, NeoForge 21.1.x and Java 21. Drop the jar into `mods/`. No server installation needed.