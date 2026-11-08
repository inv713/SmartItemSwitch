# 智能物品切换

Minecraft 1.21.1 + NeoForge 21.1.x 的客户端模组。

给方块设置左键或右键要用的物品，交互时自动从背包换到手上；挖方块自动挑工具，打怪自动换武器。服务器不用装。

## 功能

- 左键/右键方块时自动换物品，支持方块标签（例如 `#minecraft:logs`）
- 挖方块时挑最快的工具，排序（收藏）里的工具优先
- 攻击时自动换武器，可以在界面里关掉
- 模组工具识别不了时，手持物品按 Shift 再点类别按钮，就能手动归类
- 按 ` 键打开配置界面，自带帮助页

## 安装

需要 Minecraft 1.21.1、NeoForge 21.1.x、Java 21。

把 `smartinteract-neoforge-1.21.1-1.0.0.jar` 放进 `mods` 文件夹，启动游戏即可。

## 用法

按 ` 打开界面。准星对着方块按「从准星」填方块 id，手持物品按「从手持」填物品 id，选左键或右键，点「添加」。

映射按列表顺序尝试，第一个条件不满足就试下一个。

- 「自动工具」控制挖方块时换工具
- 「攻击切武器」控制打怪时换武器
- 排序列表里的工具和武器优先使用

配置文件在 `config/smartinteract.json`，改完要重启游戏。填错的 id 会被跳过，不会崩游戏。

## 已知限制

只支持 1.21.1 + NeoForge 21.1.x。只翻背包 0–35 格，不管副手和模组背包。创造模式下不自动换。

## 自己构建

```
./gradlew build
```

产物在 `build/libs/`。

## 协议

MIT，见 [LICENSE](LICENSE)。

## English

A client-side NeoForge mod for Minecraft 1.21.1. Assign an item to a block's left- or right-click action and it is swapped in automatically; mining picks the fastest tool (ranked tools first), attacking switches to your best weapon. Press ` to configure in-game.

Requires Minecraft 1.21.1, NeoForge 21.1.x and Java 21. Drop the jar into `mods/`. No server installation needed.
