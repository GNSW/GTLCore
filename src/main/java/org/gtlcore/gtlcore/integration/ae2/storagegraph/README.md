# AE2 存储图编译器（Storage Graph Compiler）实现与独立验收说明

> 对应设计文档：`GTLCore_AE2_StorageGraphCompiler_Implementation_Guide.md`（PR-1 / PR-2、G01–G25、3.4 OPAQUE 安全边界、5.6 插入语义）。
> 本目录实现 PR-1 与 PR-2 的读路径（`getAvailableStacks`、`SIMULATE EXTRACT`、`SIMULATE INSERT`），并提供可选的 ON 模式。真实 IO（`MODULATE`）仍走 AE2 原版，PR-3 才接入路由执行器。

## 1. 环境

| 项 | 版本 |
|---|---|
| Minecraft / Forge | 1.20.1 / 47.4.16 |
| GTCEu | 1.4.4 |
| AE2 | 15.4.10 |
| 依赖 | LDLib、Registrate 等（见 `build.gradle`） |

## 2. 配置

`config/gtlcore.yaml`，`ae:` 段：

```yaml
ae:
  ae2StorageGraphMode: SHADOW        # OFF | SHADOW | ON
  ae2StorageGraphLogging: true       # SHADOW 下输出快照/来源/可用库存对比日志
  ae2StorageGraphCompatFallback: true # 遇到 OPAQUE 有库存时是否回退 AE2 原版
```

| 模式 | 行为 |
|---|---|
| `OFF` | 不建图，完全原版 |
| `SHADOW` | 建图并输出诊断日志，不改变任何真实/模拟结果 |
| `ON` | `getAvailableStacks` / `SIMULATE EXTRACT` / `SIMULATE INSERT` 使用图结果；`MODULATE` 仍原版 |

`ae2StorageGraphCompatFallback` 只影响 ON 模式：

- `true`（默认）：任一 OPAQUE 来源有库存时，整次操作回退 AE2 原版并记录 `compat fallback`；
- `false`：不回退，OPAQUE 来源被忽略（严格图模式），见 6.3。

## 3. 代码结构

```text
storagegraph/
  StorageGraphManager.java        // collect / snapshot / fillUniqueIfExact / simulateUniqueIfExact
                                  // insertUniqueIfExact / shadow* 诊断 / dump
  StorageGraphSnapshot.java       // epoch、raw/unique/alias/opaque/unverified、sources、edges
  SourceRegistry.java             // SourceId 稳定分配（按对象 identity + delegateHash）
  StorageGraphDiagnostics.java    // 限频日志（1 秒）
  StorageGraphLifecycle.java      // 服务器停止 reset
  adapters/                       // NetworkStorage、StorageBus、Delegating、Composite、DriveWatcher、Opaque
  Edge / EdgeKind / Source / SourceKind / DirectionMode / PolicySnapshot / GridId
```

Mixin/入口：

- `mixin/ae2/storage/NetworkStorageMixin.java`：mount/unmount epoch、`getAvailableStacks`、`extract`、`insert` 的 SHADOW/ON 分支；
- `mixin/ae2/storage/DelegatingMEInventoryMixin.java`、`CompositeStorageMixin.java`：`setDelegate` 失效 SourceId；
- `common/command/StorageGraphTestCommand.java`：`/gtltest` 调试命令；
- `config/ConfigHolder.java`、`common/CommonProxy.java`：配置与命令注册。

## 4. 已实现

### 4.1 PR-1：图 IR、身份、拓扑观察

- BFS 采集 `NetworkStorage` 挂载、存储总线、`DelegatingMEInventory`、`CompositeStorage`、`DriveWatcher`（盘），未知/外部存储落为 `OPAQUE` 叶子；
- `SourceId` 按对象 identity 分配，`delegateHash` 变化或 `setDelegate` 时失效；
- topology/policy epoch、SHADOW 快照日志、`/gtltest graph` dump（`epoch/raw/unique/alias/opaque/unverified/nodes/edges/cycles` + source/edge 列表）。

### 4.2 PR-2：读路径与 SIMULATE

- `getAvailableStacks`（ON）：`fillUniqueIfExact` 汇总 FINITE/INFINITE 叶子；OPAQUE 有库存按配置回退；
- `extract(SIMULATE)`（ON）：`simulateUniqueIfExact` 对每个唯一来源模拟提取并累加；OPAQUE 有库存按配置回退；
- `insert(SIMULATE)`（ON）：`insertUniqueIfExact`；见 4.3；
- `extract/insert` 的 `MODULATE` 与 `throughput` 统计保持原路径（PR-3 前不改变真实 IO）；
- SHADOW 下对比日志：`availableStacks legacyKeys/uniqueKeys/... mismatchedKeys`、`simulate legacy/unique`、`insert legacy/unique`。

### 4.3 本次补充（PR-2 剩余）

1. **`SIMULATE INSERT` 的 ON 替换**
   `StorageGraphManager.insertUniqueIfExact`：
   - 只遍历 FINITE/INFINITE 来源，按顺序 `insert(SIMULATE)`，用 `remaining` 递减，收满 `amount` 停止；
   - 不把多个来源的空位相加（guide 5.6 第 475 行「唯一目的地空位预算」）；
   - OPAQUE 来源用 `getAvailableStacks` 检测；按 `ae2StorageGraphCompatFallback` 决定回退或忽略；
   - 返回 `-1` 表示调用方回退 legacy。
   `NetworkStorageMixin` 的 `insert` SIMULATE 分支在 ON 下优先调用它，与 `extract` 分支对称。

2. **`COMPAT_FALLBACK` 真正生效**
   新增 `StorageGraphManager.compatFallback()` 读取配置；`fillUniqueIfExact`、`simulateUniqueIfExact`、`insertUniqueIfExact` 三处回退点都由它控制。默认 `true` 行为与之前一致；`false` 时为严格图模式，OPAQUE 库存被忽略（这是「不谎称已精确去重」的取舍，不是 bug）。

## 5. 调试命令

```text
/gtltest stocks                         # 当前网络 getAvailableStacks 的 keys/total
/gtltest graph                          # dump epoch/raw/unique/alias/opaque/unverified/sources/edges/cycles
/gtltest simulate <item|block|fluid> <amount>
/gtltest insert   <item|block|fluid> <amount>
```

- 站在网络方块（线缆/总线/端口）16 格内、视线对准；命令通过 `IGridNode` 取该网格的 `NetworkStorage`；
- `stocks/graph` 反映当前模式：SHADOW 为 legacy + 诊断，ON 为图结果；
- `simulate/insert` 只做 `SIMULATE`，不改变库存；
- `simulate/insert` 的 key 参数是注册名（item/block/fluid），暂不支持 SNBT/NBT 后缀。

## 6. 独立验收步骤

以下场景都只需要一个测试用 ME 网（记为 A），可按顺序执行。每次拆/连、改配置后等 1~2 秒再操作（日志 1 秒限频）。

### 6.1 准备

1. `ae2StorageGraphMode: SHADOW`、`ae2StorageGraphLogging: true` 进档；
2. A 网：ME 驱动器 + 一个有空间的存储元件 + ME 终端，**不接存储总线**（保证图里没有 OPAQUE 叶子干扰）。

### 6.2 场景 A：图与 legacy 一致 + NBT 变体不合并（G01/G24）

1. 往存储元件里放两个**同 ID、不同 NBT** 的物品各 32，例如命名牌分别命名为 1 和 2（或两种药水）；
2. 打开一次 ME 终端（触发 `getAvailableStacks`），执行：

```text
/gtltest stocks
/gtltest graph
```

期望：

```text
[StorageGraph] availableStacks legacyKeys=2 uniqueKeys=2 legacyTotal=64 uniqueTotal=64 mismatchedKeys=0 ...
stocks keys=2 total=64
graph: unique=1 opaque=1（CraftingServiceStorage 空叶子，0 库存，不触发回退） ...
```

判定：`uniqueKeys == legacyKeys`、`mismatchedKeys=0`（图没有合并不同 NBT 的 `AEKey`）。

3. 切 ON 重启，重复上面两条：

```text
stocks keys=2 total=64
graph: unique=1 opaque=0/1 ...
```

ME 终端应显示两条独立条目、各 32，且日志没有 `compat fallback`。

### 6.3 场景 B：`COMPAT_FALLBACK`

1. A 网加一根存储总线贴箱子，箱内放 5 个物品（如 `minecraft:dirt`），可保留盘；图里出现 OPAQUE 叶子；
2. ON + `ae2StorageGraphCompatFallback: true`：

```text
/gtltest stocks        -> keys 包含箱子物品（如 3/69：盘 2/64 + 箱 1/5）
/gtltest insert minecraft:stone 10 -> 10（回退 legacy，箱子可接受）
日志: [StorageGraph] compat fallback uniqueSources=0 opaqueSources=2
```

3. 改 `ae2StorageGraphCompatFallback: false` 重启：

```text
/gtltest stocks        -> keys=0 total=0（严格模式忽略 OPAQUE）
/gtltest insert minecraft:stone 10 -> 0（图无 FINITE/INFINITE，且不回退）
日志: 仍有 compat fallback（只记录，不回退）
```

判定：true 时结果与 OFF 一致（回退）；false 时 OPAQUE 被忽略、`insert` 为 0（严格模式生效）。终端里 OPAQUE 物品消失是预期。

### 6.4 场景 C：`SIMULATE INSERT` 的 ON 替换

在 6.3 的 false 场景下：

```text
/gtltest insert ae2:interface 10 -> 0
```

说明 ON 的 `insert(SIMULATE)` 没有走 legacy（否则箱子会返回可接受量）；恢复 true 后同一命令返回 10（回退 legacy）。无 OPAQUE 时（6.2 拓扑）ON 下 `insert` 返回盘内可接受量，与 OFF 一致。

### 6.5 场景 D：网格拆分/合并（G25）

在 6.2 拓扑（只有盘）下：

1. 基线：`/gtltest graph` 记 `epoch`、`edge <rootHash>`、`source ... id=`、`stocks 2/64`；
2. 断开驱动器与 A 网的线缆，等 1~2 秒：

```text
/gtltest graph  -> unique=0（盘消失）
/gtltest stocks -> keys=0 total=0
终端 -> 两条消失（重开终端确认）
日志 -> topology changed epoch=N
```

3. 接回线缆：

```text
/gtltest graph  -> epoch 增加、unique=1、edge <rootHash> 可能变化（旧根失效）
/gtltest stocks -> keys=2 total=64（不是 128）
终端 -> 两条各 32 恢复
```

判定：拆分后无幽灵库存、重连后数量正确、不重复计数；`source id` 对同一 `DriveWatcher` 对象复用时不变属预期（`SourceRegistry` 按对象 identity），要验证「旧 id 失效」可拆分后破坏并重放驱动器（新对象 → 新 id）。

## 7. 验收判定汇总

| 场景 | 通过条件 |
|---|---|
| A SHADOW | `uniqueKeys == legacyKeys` 且 `mismatchedKeys=0`；`stocks` 与终端一致 |
| A ON | `stocks` 与 SHADOW/legacy 一致；无 `compat fallback` |
| B true | 结果与 OFF 一致（OPAQUE 参与，日志记录 fallback） |
| B false | `stocks keys=0`、`insert=0`；日志仍记录 fallback |
| C | false 下 `insert=0`，true 下 `insert=请求量` |
| D | 拆分后 0/0 无幽灵；重连后数量恢复、epoch 增加、不重复 |

## 8. 已知限制（PR-3 前）

- **ON 目前仅用于诊断验证，默认不开启**；是否放开应等 PR-3 的 `RoutePredicate`/合法路径实现；
- `MODULATE` 的 extract/insert 仍走 AE2 原版；图的路由、priority、`isPreferredStorageFor`、过滤、access 尚未进入执行路径（PR-3）；
- ON 下 `fillUniqueIfExact` 直接读叶子，可能绕过过滤/只写总线（PR-3 route 语义补）；
- `OPAQUE` 默认回退原版；`COMPAT_FALLBACK=false` 为严格模式，会忽略 OPAQUE 库存；
- `SIMULATE EXTRACT` 已按 `remaining` 递减（多个独立来源不会超量）；`getAvailableStacks` 保持 AE2 累加语义（不清空调用方计数器）；`SIMULATE INSERT` 对 OPAQUE 做空位探测（空箱子也会触发回退），OFF 模式直接走原版；
- `SourceRegistry` 在 `unmount` 时清理节点条目（`onRemoved`）；`EXTERNAL` 条目与 id 计数不复用（id 单调递增）；
- SHADOW 的 `probeInsertUnique` 只统计 FINITE 且不 cap 到 `amount`，仅用于诊断对比；
- 空 `NetworkStorage` 不再被当作 `OPAQUE` 物理来源（`isPhysicalSource=false`），不会自我递归；
- `collect` 的节点预算是硬限制（每条边入队前检查），超限时 `snapshot.status()` 为 `INCOMPLETE_NODE_LIMIT`/`INCOMPLETE_DEPTH_LIMIT`，ON 的 `getAvailableStacks`/`SIMULATE EXTRACT/INSERT` 会回退原版并记录 `incomplete graph status=...`；
- `status=COMPLETE` 只表示拓扑完整，不代表访问策略已验证（`PolicySnapshot` 仍是 `unknown()`，过滤/方向/优先级要等 PR-3）；回退原版不等于修复重复库存，二者在验收中必须分开记录；
- G09–G25 目前是手工 + 单点验证，自动化测试尚未落地（参考 `GTLCore_StorageGraph_Automated_Testing_Guide.md`，目标是 `src/verification` + `runVreGameTest`）；
- `/gtltest` 是调试命令，不保证长期 API 兼容。

## 9. 涉及文件

```text
src/main/java/org/gtlcore/gtlcore/integration/ae2/storagegraph/   （整个包，新增）
src/main/java/org/gtlcore/gtlcore/mixin/ae2/storage/NetworkStorageMixin.java
src/main/java/org/gtlcore/gtlcore/mixin/ae2/storage/DelegatingMEInventoryMixin.java
src/main/java/org/gtlcore/gtlcore/mixin/ae2/storage/CompositeStorageMixin.java
src/main/java/org/gtlcore/gtlcore/common/command/StorageGraphTestCommand.java
src/main/java/org/gtlcore/gtlcore/common/CommonProxy.java（命令注册）
src/main/java/org/gtlcore/gtlcore/config/ConfigHolder.java（3 个配置项）
src/main/resources/assets/gtlcore/lang/{en_us,zh_cn}.json（配置项文本）
```
