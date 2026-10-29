# GT Multiblock Sockets

一个**独立补丁 mod**：给 GregTech CEu / GTLCore 的多方块结构增加“手持仓室高亮”功能。

手持任意 GregTech 仓室、总线、维护仓、消声仓等部件，并用准星看向一台多方块机器时，该机器结构 pattern 中**接受该部件的格位**会用彩色线框标出（只显示这一台机器）：

- **青色**：空闲/可替换的壳体位置，可以把手中的仓室放进去。
- **橙色**：该位置已经放了同类仓室、被其他机器占用，或者被不相干的方块挡住。

物品提示（tooltip）里也会写清这两种颜色的含义。

灵感来自 OmniSequence: Transfinite 2.0.11 的“拿着方块看高亮 / 告诉你哪里能放”功能，但代码完全独立编写。

---

## 验证状态

- javac 编译通过：Forge 47.4.16 / Minecraft 1.20.1 SRG / GTCEu 1.4.4 / LDLib 1.0.33.b。
- 离线 `test/PatternTest.java` 通过：3×3×3 测试 pattern 得到 26 个非控制器格位，坐标范围正好是 3×3×3。
- jar 结构、`mods.toml`、`pack.mcmeta`、mixin 配置和 manifest 已做静态校验。

---

## 安装

1. 关闭游戏。
2. 把 `gtmbsockets-1.0.3.jar` 放进：

   ```
   .minecraft/versions/GregTech Leisure/mods/
   ```

3. 重启游戏。不需要改任何配置。

> **版本策略**：更新时保留旧版本 jar 作为备份（例如 `gtmbsockets-1.0.2.jar`），直接增加新版本文件（如 `gtmbsockets-1.0.3.jar`），不覆盖旧文件。**但 `mods` 目录里同一时间只能放一个 `gtmbsockets` jar**，否则会产生重复 modId；确认新版正常后再删除旧版。

**卸载**：删除这个 jar 即可。没有配置文件，没有存档数据，没有服务端内容。

---

## 兼容性

| 项目 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.x（构建时使用 47.4.16） |
| GTCEu | 1.20.1-1.4.4（依赖 `[1.4.4,)`） |
| GTLCore | 1.2.x（可选，已按 1.2.3.3 的结构/仓室注册方式适配） |
| Java | 游戏用 Java 17+ 即可；jar 以 Java 17 字节码编译 |

- **纯客户端功能**：专用服务器可以不装；`displayTest` 已设为忽略版本，不会因为服务端缺少它而显示红叉。
- 不修改、不重打包、不复制 GTCEu / GTLCore / 任何附属的代码。只通过一个 Mixin 读取 `BlockPattern` 的两个私有字段和私有坐标方法。
- 使用标准 GT pattern API 的附属多方块（包括 GTLCore）会自动适用，不需要逐个适配。
- 单方块机器手持时不会显示任何框（因为它的物品不会匹配多方块 pattern 的格位）。

---

## 显示规则与限制

实现上直接遍历控制器的 `BlockPattern`：

- **只显示准星指向的那一台多方块机器**：看向控制器、仓室/总线或结构外壳都能定位到它；看向空气或非多方块方块时不显示。
- 控制器发现使用 **13×13 区块（96 格）**范围；刷新是自适应的：没有目标时约每 20 tick，目标稳定时约每 60 tick，玩家移动超过 8 格时立即刷新。这一步不可见，只用于从外壳方块反查它所属的控制器。
- 只渲染玩家**视线前方约 90° 视锥、24 格范围内**的格位；背后的机器不会产生线框。
- 只显示**外壳/结构上可操作**的格位：
  - 已有方块的格位：至少有一个空气邻居（表面/可接触）才显示；
  - 空气格位：至少有两个固体邻居（说明它是已搭建外壳上的缺口）才显示，避免未成形结构内部整片空气变成“幽灵框”。
- 特别大的机器按 **16×16×16 分区**只遍历玩家附近的分区，并最多绘制离玩家最近的 **512** 个框，避免整台机器全量渲染。
- 准星检测有缓存：静止时最多每 10 tick 重算一次，移动/转向时约每秒最多 10 次射线检测。
- 线框重建是自适应的：视角变化时每 4 tick，静止时每 10 tick；目标或手持物变化时立即重建。
- 外壳反查结果按“准星命中的方块位置”缓存，盯住同一块壳体时不会反复遍历所有 pattern。
- **会检查全局/分层数量上限**：
  - 统计目标结构 pattern 每个格位上已有的方块，对 `SimplePredicate.maxCount`（全局）和 `maxLayerCount`（按 aisle 层）做容量判断。
  - 达到上限后，额外的壳体/空气格位不再显示；已经放着仓室的格位仍以橙色显示，方便定位。
  - `minCount` 不影响显示；计数每 20 tick 刷新；从手中放下仓室时会立即失效并重建（快捷栏数量变化），拆除或他人改动最多约 1 秒延迟。
  - 统计粒度与 GT 一致：按 `SimplePredicate` 对象分别计数，而不是按仓室类型合并；不同格位若使用了不同的 predicate，数量上限也分别计算。
  - `AdvancedBlockPattern` 的动态重复段仍按 pattern 枚举，数量上限判断可能是近似值。
  - 带 NBT / `slotName` / 纯 `custom(...)` 条件的 predicate 只按候选方块统计，可能与 GT 的精确条件有偏差。
- 对于使用 `Predicates.custom(...)` 且没有候选物品列表的极少数自定义格位，无法从物品反推，因此不会高亮；标准 `Predicates.blocks(...)`、`Predicates.abilities(...)`、`Predicates.autoAbilities(...)` 都支持。
- 从外壳方块反查控制器依赖控制器在玩家 **96 格**内；更远时可以改为直接看控制器或仓室。
- 准星射线长度为 **64 格**；超过这个距离视为没有注视目标。
- GTLCore 的 `AdvancedBlockPattern` 会在运行时按实际重复次数扩展结构；本 mod 按 pattern 的 `aisleRepetitions` 枚举。已成形/已放置壳体的部分能正常显示，未成形的重复段不会凭空出现。
- 物品提示依赖 `PartAbility` 注册表；如果某个附属的仓室没有注册任何 `PartAbility`，高亮仍然有效，只是物品提示不会出现。

---

## 工作方式（简）

1. 客户端按自适应间隔扫描附近区块的 `BlockEntity`，收集 `MultiblockControllerMachine`；目标稳定时不再每秒全量扫描。
2. 对每个控制器，按当前朝向取出它的 `BlockPattern`：
   - 通过 Mixin accessor 读取 `blockMatches`、`centerOffset`；
   - 通过 Mixin invoker 调用 GTCEu 自己的 `setActualRelativeOffset(...)`，保证翻转/非 Y 轴朝向和 GT 的原生检查完全一致；
   - 按 `checkPatternAt` 的相同循环（包含 `aisleRepetitions`）枚举所有格位。
3. 对每个格位，读取该格位 pattern 的候选方块/物品，判断是否接受手中的部件。
4. 按视角变化/静止状态自适应重建世界方块状态与青色/橙色线框，在 `RenderLevelStageEvent.AFTER_TRANSLUCENT_BLOCKS` 阶段用 `RenderType.lines()` 绘制。
5. 所有 pattern / 候选集合 / 格位列表都做了缓存，正常游玩不会每帧重建结构。

---

## 构建（可复现）

`build.ps1` 不依赖 ForgeGradle、不联网，直接使用整合包自带的 SRG 客户端 jar 和 Forge 47.4.16：

```powershell
.\build.ps1
```

产物：`gtmbsockets-1.0.3.jar`。

脚本会自动在 `D:\MineCraft\save\*\` 下寻找带有 `GregTech Leisure` 版本的 `.minecraft`，然后使用：

- `libraries/net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar`
- `libraries/net/minecraftforge/forge/1.20.1-47.4.16/...`
- `mods/gtceu-1.20.1-1.4.4.jar`
- `mods/ldlib-forge-1.20.1-1.0.33.b.jar`

`test/PatternTest.java` 是一个离线自检程序（不打包）：构造一个 3×3×3 的 GT pattern，用与 mod 相同的循环和反射调用 GT 的坐标方法，验证得到 26 个非控制器格位并填满 3×3×3 立方体。

---

## 文件结构

```
src/main/java/com/wcwen/gtmbsockets/
  GtmBSockets.java                  @Mod 入口
  client/ClientEvents.java          Forge 事件注册（tick/render/tooltip）
  client/SocketOverlay.java         控制器扫描、世界状态判断、线框绘制
  client/PatternSockets.java        BlockPattern 格位枚举与候选物品匹配、缓存
  client/PartItems.java             仓室物品识别 / PartAbility 反射
  mixin/BlockPatternAccessor.java   BlockPattern 只读访问器
src/main/resources/
  META-INF/mods.toml
  META-INF/MANIFEST.MF              MixinConfigs
  mixins.gtmbsockets.json
  assets/gtmbsockets/lang/*.json
  pack.mcmeta
```

---

## 许可

MIT。代码为本项目独立编写；运行时不包含 GTCEu、GTLCore 或 OmniSequence 的任何代码/资源。
