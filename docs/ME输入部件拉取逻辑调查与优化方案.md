# GTLCore ME 输入部件拉取逻辑调查与优化方案(工作记录)

- 仓库:`C:\Users\wcwen\Documents\GitHub\GTLCore`
- 日期:2026-11-03
- 调查方式:直接读源码 + `javap` 反汇编 `libs\gtceu-1.20.1-1.4.4.jar` 字节码 + 子代理调用链追踪
- 状态:调查完成,实施方案待执行

---

## 1. 任务背景与目标

优化 GTLCore 中三个 ME 输入部件(**ME输入总成 / ME输入仓 / ME输入总线**)的拉取逻辑,缓解 **1tick 拉取与默认间隔(0tick)同相位拉取**造成的卡顿。

**硬性约束**:玩家可见语义完全不变——"拉取间隔 + 标记数量"的设定和行为保持原样,只做内部性能优化。

### 方案讨论结论(重要)

- 用户最初设想"影子库存 + 可用化"(标记后读网络建影子库存并禁用,工作时按间隔/数量激活):
  - **机制可行**(库存类部件有现成范式),但经分析,**在语义不变的前提下省不掉大头**——激活仍是真实 `extract(MODULATE)`,调用次数与现状一致。
  - 之前估算的 ~40× 收益建立在"长间隔×大批量替代 1tick×小批量"的**语义变化**上,既然不改语义,该收益不成立。
  - **重新定位:影子库存属于显示/机制增强,不是性能方案**。可作为可选项,不作为本次性能优化的主体。
- 确认 **3 个玩家完全无感、直接命中成本中心的优化点**(见 §5.3),为本次实施方案。

---

## 2. 部件识别与名称对照

| 注册名 | 中文名 | 实现类 | 注册位置 |
|---|---|---|---|
| `me_input_bus` | ME输入总线 | GTM `MEInputBusPartMachine` + GTLCore mixin | `GTLMachines.java:674` |
| `me_input_hatch` | ME输入仓 | GTM `MEInputHatchPartMachine` + GTLCore mixin | `GTLMachines.java:718` |
| `me_input_assembly` | ME输入总成 | `MEDualInputHatchPartMachine`(物品+流体双 64 槽) | `GTLMachines.java:643` |
| `me_dual_hatch_stock_part_machine` | ME库存输入总成 | `MEDualHatchStockPartMachine` | `GTLMachines.java:657` |
| `tag_filter_me_stock_bus_part_machine` | 标签过滤ME库存输入总线 | `TagFilterMEStockBusPartMachine` | `GTLMachines.java:614` |
| `tag_filter_me_stock_hatch_part_machine` | 标签过滤ME库存输入仓 | `TagFilterMEStockHatchPartMachine` | `GTLMachines.java:628` |

(注册文件:`src\main\java\org\gtlcore\gtlcore\common\data\GTLMachines.java`)

---

## 3. 现有拉取逻辑详解

### 3.1 调度链(字节码已确认)

- 普通总线 `ItemBusPartMachine.autoIO()`:`getOffsetTimer() % 5 == 0` 才执行(固定 5t),之后按 IO 方向 export/import。
- **ME输入总线** `MEInputBusPartMachine.autoIO()`(GTM,字节码):
  `isWorkingEnabled → shouldSyncME → updateMEStatus → syncME() → updateInventorySubscription()`
- **ME输入仓** `MEInputHatchPartMachine.autoIO()`(GTM,字节码):
  同上,但最后是 `updateTankSubscription()`。
- `MEInputBusPartMachine` / `MEBusPartMachine` 自身**都没有 `shouldSyncME` 方法**(方法表确认),说明它定义于更上层且被 GTLCore mixin 覆写:
  - `MEBusPartMachineMixin.shouldSyncME()`([MEBusPartMachineMixin.java:47](..\src\main\java\org\gtlcore\gtlcore\mixin\gtm\ae\machine\MEBusPartMachineMixin.java)): `offsetTimer % (syncOffset==0 ? ME_UPDATE_INTERVAL : syncOffset) == 0` —— **所有设默认 offset 的部件在同一全局 tick 齐拉,这就是"0tick 也卡"的相位尖峰来源**。
  - `MEHatchPartMachineMixin` 对 hatch 族做了同款覆写([MEHatchPartMachineMixin.java:46-49],子代理报告)。
- `ME_UPDATE_INTERVAL` 常量:GTM 侧定义(GTLCore 两个 mixin 直接引用),**具体数值与定义类未从字节码最终确认**(见 §7)。

### 3.2 ME输入总线 —— `MEInputBusPartMachineMixin.syncME`(@Overwrite)

[MEInputBusPartMachineMixin.java:86-112](..\src\main\java\org\gtlcore\gtlcore\mixin\gtm\ae\machine\MEInputBusPartMachineMixin.java),逐物品槽双向:

1. **回推**:槽内 `exceedStack()`(超出配置量的部分)→ `networkInv.insert(MODULATE)` 推回网络;插入失败(网络满)→ `extractGenericStack(total, false, true)` 本地清除。
2. **拉取**:槽内 `requestStack()`(配置请求 = config − stock)→ `networkInv.extract(what, amount, MODULATE)` **真实抽取** → `slot.addStack(...)` 进入槽 stock。

`autoIO` 注入([:41-50](..\src\main\java\org\gtlcore\gtlcore\mixin\gtm\ae\machine\MEInputBusPartMachineMixin.java)):**每次 syncME 后无条件 `setChanged(true)`**——这是优化点 1 的目标。

另有:SyncOffset 持久化(`writeConfigToTag/readConfigFromTag` 注入)、`AdvancedMEConfigurator` GUI、节点上线时 `notifyListeners()`。

### 3.3 ME输入仓 —— GTM `MEInputHatchPartMachine`(字节码重建,GTLCore 未覆写 syncME)

```java
protected void syncME() {
    MEStorage networkInv = getMainNode().getGrid().getStorageService().getInventory();
    for (ExportOnlyAEFluidSlot slot : aeFluidHandler.getInventory()) {
        GenericStack exceed = slot.exceedStack();
        if (exceed != null) {
            long inserted = networkInv.insert(exceed.what(), exceed.amount(), MODULATE, actionSource);
            slot.drain(inserted > 0 ? inserted : exceed.amount(), false);   // 推回;失败则本地清除
        }
        GenericStack req = slot.requestStack();
        if (req != null) {
            long extracted = networkInv.extract(req.what(), req.amount(), MODULATE, actionSource);
            if (extracted > 0) slot.addStack(new GenericStack(req.what(), extracted));
        }
    }
}
```

- 与物品总线完全同构(流体版)。`flushInventory()` 在 `onMachineRemoved()` 时把槽内 stock 全部 `insert` 回网络。
- GTLCore 的 `MEInputHatchPartMachineMixin` 只做:autoIO 注入(无条件 setChanged)、SyncOffset 持久化、GUI configurator、节点上线 notifyListeners —— **未覆写 syncME**。

### 3.4 ME输入总成 —— `MEDualInputHatchPartMachine`

[MEDualInputHatchPartMachine.java](..\src\main\java\org\gtlcore\gtlcore\common\machine\multiblock\part\MEDualInputHatchPartMachine.java),继承 `MEInputBusPartMachine`:

- 双列表:物品 `ExportOnlyAEItemList` + 流体 `ExportOnlyAEFluidList`,各 64 槽(`CONFIG_SIZE=64`)。
- `syncME()`([:86-115](..\src\main\java\org\gtlcore\gtlcore\common\machine\multiblock\part\MEDualInputHatchPartMachine.java)):`super.syncME()`(物品,走 3.2 的覆写)+ 流体循环(同 3.3 的 exceed/request 模式,带 null 网格保护)。
- `flushInventory()`([:118-132](..\src\main\java\org\gtlcore\gtlcore\common\machine\multiblock\part\MEDualInputHatchPartMachine.java)):`super`(物品)+ 流体 stock 全部 insert 回网络。
- NBT:物品/流体共用 64 个槽位编号,按 `AEItemKey`/`AEFluidKey` 类型分配到对应 handler;含幽灵电路与 SyncOffset。

### 3.5 库存类部件(影子库存范式,对照参考)

- **ME库存输入总成** `MEDualHatchStockPartMachine`:
  - `syncME()`([:233-268](..\src\main\java\org\gtlcore\gtlcore\common\machine\multiblock\part\MEDualHatchStockPartMachine.java)):**不真实抽取**,从 `storageService.getCachedInventory()`(AE2 `KeyCounter`)读缓存数量直接 `setStock`(影子),变化时 `markMEStockChanged()`(setChanged + notifyListeners)。
  - `refreshList()`([:154-208](..\src\main\java\org\gtlcore\gtlcore\common\machine\multiblock\part\MEDualHatchStockPartMachine.java)):自动拉取核心——遍历网络缓存,按 `autoPullMode`(关/全部/物品/流体)过滤,`extract(SIMULATE)` 模拟后填 64 槽,`autoIO` 中每 `offset`(默认 50t)执行。
- **标签过滤库存总线/仓** `TagFilterMEStock*PartMachine`:
  - `refreshList()`:`TagPriorityList(tagWhite, tagBlack)` 白黑名单过滤 + 可选按数量降序取前 64。
  - `syncME()`:`extract(key, Long.MAX_VALUE, SIMULATE)` 模拟最大可供应量显示(影子,不实取);hatch 版额外提供流体 drain 接口(:321/:361/:390)。
  - `autoIO()`:super + 每 50t `refreshList() + syncME()`。
- **GTM 原版库存总线/仓增强 mixin**(`MEStockingBusPartMachineMixin` / `MEStockingHatchPartMachineMixin`):
  - `setAutoPullTest(stack -> true)` 解锁脱离控制器的独立自动拉取;
  - 节点上线 `restoreStock()`:onConfigChanged + syncME + updateInventorySubscription + notifyListeners;
  - `@ModifyConstant` 把 autoIO 的 100t 间隔替换为自定义 offset;
  - 注意:stocking 子类有自己的 autoIO,**父类输入 mixin 的 setChanged 注入对它不生效**(子代理报告确认)。
- **输出部件**(对照,不在本次范围):`MEOutputBus/HatchPartMachineMixin` `@Overwrite autoIO` 只做推回 + 订阅,无 setChanged。

### 3.6 配方消耗侧(与拉取的关系)

- 非 stocking 列表:配方 `handleRecipeInner` 从**槽 stock** 扣料(`ExportOnlyAEItemSlotMixin` 的 `extractGenericStack`,带 notifyChanges 上抛 onContentsChanged);即"拉取时从网络实扣一次进槽,消耗时扣槽"——无双扣。
- stocking 列表:`handleRecipeInner` **直连 ME 网络** `extract`(槽 stock 为虚拟),成功后 `setChanged(true)+onContentsChanged()`。
- `onContentsChanged()` = super(NotifiableItemStackHandler → notifyListeners)+ 置脏。

---

## 4. 关键机制结论

| 机制 | 结论 | 证据 |
|---|---|---|
| `IOptimizedMEList.setChanged(true)` | **纯脏标记**,不触发任何通知;使下一次配方匹配前 `getMEItemMap/getMEFluidList` 惰性重建 ME 快照 | `IOptimizedMEList.java:15-44`、`ExportOnlyAEItemListMixin.java:54-68`、`ExportOnlyAEFluidListMixin.java:58-71` |
| `notifyListeners()` | 沿 handler 监听器 → `recipeLogic::updateTickSubscription` → **唤醒控制器重查配方** | `NotifiableMERecipeHandlerTrait.java:19-41`、`WorkableMultiblockMachineMixin.java:365-366`、`MachineUtil.java:124-135` |
| `MachineUtil.notifyControllersRecipeLogic` | 遍历部件 controllers 调 `recipeLogic.updateTickSubscription()`;用于 setWorkingEnabled 时唤醒 | `MachineUtil.java:129-135` |
| `getCachedInventory()` 返回类型 | AE2 `appeng.api.stacks.KeyCounter`(可 `get(AEKey)`、可按 `Object2LongMap.Entry<AEKey>` 迭代) | `NetworkCraftingSimulationStateMixin.java:54-59`(descriptor 证据) |
| 槽位扩展 | `ExportOnlyAE*SlotMixin` 注入 `IMETransfer`(长数量 extract/drain);`ExportOnlyAE*ListMixin` 注入 `IOptimizedMEList` 缓存 + 直连扣料 | `mixin\gtm\ae\slot\` 各文件 |
| 无条件 setChanged 的实际代价 | 不是配方风暴(不通知),而是**强制下一次配方搜索重建 ME 快照**——即使槽位毫无变化;1tick 拉取时为每部件每 tick 置脏 | §3.2 注入代码 |

---

## 5. 性能分析与优化点

### 5.1 1tick/0tick 拉取的成本结构

1. **网络调用风暴**:每槽最多 2 次 `MODULATE`(exceed 回推 insert + request 拉取 extract);每次调用 AE2 遍历全部存储提供器,成本 O(槽数 × 提供器数)。64 槽 × 大网络 × 几十部件 = 每 tick 上万次提供器访问。
2. **变更风暴**:每次 MODULATE 产生库存 delta → 缓存增量更新 + 向所有终端发同步包;1tick 拉取时"拉了又推回"净变化为零也照发,是终端卡顿元凶。
3. **相位对齐尖峰**:`offsetTimer % interval == 0` 使所有默认 offset 部件同一全局 tick 齐拉,形成周期性尖峰("0tick 即默认间隔也卡"的原因)。
4. **无条件置脏**:syncME 后无条件 `setChanged(true)`,即使 64 槽无变化也强制下轮配方搜索重建快照。

### 5.2 语义不变前提下能省什么、不能省什么

- **不能省**:每间隔"真实进料 N 个"所需的 extract 调用次数(语义决定)。
- **能省**:exceed/空槽的无效调用、无变化时的置脏、同 tick 尖峰(摊平)。

### 5.3 确认的三个优化点(本次实施方案主体)

| # | 优化点 | 做法 | 预期收益 |
|---|---|---|---|
| 1 | **条件化 setChanged** | 在 `@Overwrite syncME` 内跟踪"本槽是否实际变化",autoIO 注入仅在变化时 `setChanged(true)`;作用于输入总线 mixin 与输入仓 mixin(总成经 super 自动继承物品侧,流体侧同法) | 消除无变化时的快照重建;1tick 场景下显著 |
| 2 | **extract 前缓存预检** | syncME 拉取分支先 `grid.getStorageService().getCachedInventory().get(key) > 0` 再实调 `extract`;key 不存在直接跳过。物品/流体两处同步实施 | "等产能的空槽位"从每 tick 全提供器链遍历降为一次 map 读;最坏延迟一个缓存刷新周期(玩家无感) |
| 3 | **错峰相位** | `shouldSyncME` 改为 `(offsetTimer + phaseOffset) % interval == 0`,`phaseOffset = 位置哈希`(需跨重启稳定);改 `MEBusPartMachineMixin` + `MEHatchPartMachineMixin` 两处 | 每部件节奏不变(仍每 interval 一次),全员同 tick 尖峰摊平 |

### 5.4 可选/明确不做

- **影子库存显示**(槽位显示网络余量):可选,属显示增强,对性能无贡献,默认不做。
- **拉取数量/间隔语义**:不变(用户硬性要求)。
- 已知权衡:优化点 2 依赖缓存,极端情况下(缓存未刷新)晚一个缓存周期拿到料,行为差异玩家不可感知。

---

## 6. 实施方案(待执行)

### 改动文件

1. `mixin\gtm\ae\machine\MEBusPartMachineMixin.java` — shouldSyncME 加相位偏移(优化点 3,总线族)。
2. `mixin\gtm\ae\machine\MEHatchPartMachineMixin.java` — 同上(优化点 3,仓族)。
3. `mixin\gtm\ae\machine\MEInputBusPartMachineMixin.java` — @Overwrite syncME 增加:变化跟踪(优化点 1)+ 拉取前缓存预检(优化点 2);autoIO 注入改条件化。
4. `mixin\gtm\ae\machine\MEInputHatchPartMachineMixin.java` — 新增 @Overwrite syncME(按 §3.3 字节码重建逻辑 + 优化点 1/2,流体版)。
5. `common\machine\multiblock\part\MEDualInputHatchPartMachine.java` — 流体循环加缓存预检与变化跟踪(优化点 1/2 流体侧);物品侧经 super 继承。

### 验证

- `gradlew build` 编译 + 既有 mixin 配置加载(启动不报 mixin apply 错误)。
- 逻辑自查清单:空槽跳过后 `requestStack` 语义不变;变化跟踪覆盖 exceed 回推与拉取两个分支;相位偏移持久化稳定(位置不变则相位不变)。
- (有条件时)游戏内:大网络挂多个输入总线/仓,对比 F3 tick 时间与终端同步流量。

### 提交

- 本地 commit(不推送)。**阻塞项**:本机 shell 无 `git` 命令且标准路径未找到 git.exe;会话开始快照显示分支为 `HEAD`(疑似 detached),提交前需定位 git 可执行文件并核实分支状态。

---

## 7. 未决事项

1. `ME_UPDATE_INTERVAL` 的定义类与数值(GTM 侧;GTLCore mixin 直接引用该常量,offset=0 时回落使用)。字节码方法表未覆盖,待从 `MEHatchPartMachine`/`MEBusPartMachine` 字段段确认。
2. 用户提供的 `https://github.com/liansishen/GTMThings` 与本任务的关联未核实(本机外网 DNS 失败,WebFetch 不可用)。已确认:`MEStocking*`、`ExportOnlyAEStocking*` 等本任务涉及的类**均在 `libs\gtceu-1.20.1-1.4.4.jar` 内**,GTLCore 的 ME 相关 mixin 目标不在 gtmthings jar(`libs\gtmthings-1.3.5.b.jar`)。
3. git 环境未就绪(见 §6 提交项)。

## 8. 证据文件索引

- GTLCore 源码:`src\main\java\org\gtlcore\gtlcore\mixin\gtm\ae\machine\`(8 个 ME 部件 mixin)、`mixin\gtm\ae\slot\`(槽位扩展 mixin)、`api\machine\trait\MEStock\`(IMETransfer/IOptimizedMEList/IMESlot)、`common\machine\multiblock\part\MEDual*.java`、`TagFilterMEStock*.java`、`utils\MachineUtil.java`、`common\data\GTLMachines.java`
- 字节码反汇编(临时目录):`%TEMP%\meinbus.txt`(MEInputBusPartMachine)、`%TEMP%\meinh.txt`(MEInputHatchPartMachine)、`%TEMP%\meh.txt`(MEHatchPartMachine)、`%TEMP%\mebus.txt`(MEBusPartMachine)、`%TEMP%\itembus.txt`(ItemBusPartMachine)
- 子代理调用链报告:§4 表格三条链路结论
