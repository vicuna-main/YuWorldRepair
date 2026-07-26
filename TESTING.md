# Testing

Additional orphan-item coverage includes standard ItemStacks, chunk attachments, AE2 cell
inventory/config entries, Refined Storage repositories, QIO type/drive data and metadata,
cross-Multiverse shared QIO UUID resolution, recursively nested NeoForge attachments checked
against the signed attachment registry, post-apply zero-target verification, and byte-exact
rollback.

Anvil compatibility coverage includes files that end at the final chunk's declared
`length + 4` boundary without the remainder of the allocated 4 KiB sector. Copy-on-write
zero-fills that absent tail only after validating the complete record. A paired negative test
removes one declared payload byte and verifies fail-closed behavior plus temporary-file cleanup.
The supplied production `r.-7.1.mca` was also exercised non-destructively: all 36 chunks were
read, the rewritten copy preserved the semantic hashes of all 35 unedited chunks, and the source
SHA-256 remained `2089353c915a0dad6438b32689f6d240625afd758eb3f63bcb16ccc81db4f02d`.

Large-world coverage additionally verifies deterministic four-worker region scanning, bounded
progress completion, region-only world exclusion, continued playerdata/RS scanning, QIO cache
deferral with per-namespace/per-store evidence under partial scope, unique/ambiguous MV labels,
and all-world lock lifetime.

## Orphan-item real-world regression

The isolated 34-world fixture produced 29 exact targets (22 ItemStack, 6 AE2, 1 QIO, and 0
orphan RS). The regression prepared every world before writing, applied all 29, rescanned every
world to zero, and byte-restored all eight changed worlds. The valid RS repository was parsed and
left unchanged. The rc2 four-worker read-only pass scanned 2,231,752,908 region bytes across all
34 roots, reproduced the same 29 targets, and reported zero coverage gaps and empty stderr.

The isolated `ml554104` fixture now produces 17 targets instead of the old false zero: five
deeply nested `iceandfire:chicken_data` attachments (dragon horn and maid mirror/component data)
plus twelve `kaleidoscope_tavern` ItemStacks. Apply, zero-target rescan, rollback, and a final
404-file SHA-256 comparison all passed with zero coverage gaps.

## 自动化

完整验证命令：

```powershell
.\gradlew.bat clean build --no-build-cache --rerun-tasks
```

构建覆盖：

- 观察/日志规则、限流、并发和配置回退；
- HMAC 维护请求、一次性确认、父进程退出、`session.lock` 和启动门禁；
- NBT 类型、Modified UTF-8、深度、集合、尾随数据和压缩限制；
- Anvil header、sector、gzip/zlib/未压缩 chunk，以及内部 `.mca` 和外置 `.mcc`；
- 旧 `iceandfire:chicken_data` 精确适配器，以及统一孤儿物品指令对任意深度失效附件的
  扫描、备份、删除、验证和逐字节回滚；
- 命名空间实体/乘客、attachment、方块 palette、方块实体和 scheduled tick；
- 玩家 GZIP NBT；
- Youer/Bukkit/MV 已加载世界目录捕获、全世界先备份后应用和多世界逆序回滚；
- 不支持 DataVersion、不可解析 region 和覆盖缺口的全写入拒绝；
- 修复后新变化阻止回滚覆盖；
- Minecraft 1.21.1 原生 `PalettedContainer` codec 兼容；
- 观察版、离线工具、维护版和嵌套 worker 的依赖/入口边界。

构建任务还执行：

- `verifyReleaseBoundary`
- `verifyWorldToolBoundary`
- `verifyMaintenanceBoundary`

它们分别阻止观察版泄漏修改入口、离线工具依赖 Minecraft/NeoForge，以及维护版缺少嵌套 worker 或必要命令。

当前发布候选完整构建通过，共 88 项测试：

- Minecraft/NeoForge 测试：22；
- 维护交接测试：15；
- 单 JAR supervisor 测试：1；
- 离线工具测试：50；
- failure/error/skipped：0/0/0。

## 真实世界读写证据

真实服务器隔离样本保持 `world` 与同级 `playerworld` 的原始布局：

- 主世界加 33 个包含 `level.dat` 的玩家世界，共 34 个有效世界根；
- 修复前发现 508 个 `iceandfire:` 目标，包括玩家/实体 attachment、方块 palette 和方块实体；
- 9 个含 Minecraft Modified UTF-8 的玩家 NBT 在修正 codec 后全部可解析；
- 所有世界先完成扫描和哈希备份，随后完成 508 个目标的应用与逐世界验证；
- 独立写后复扫为 34/34 个世界 `targets=0`、`coverageGaps=0`，stderr 为 0；
- 桌面的源 ZIP 未修改，写入仅发生在仓库 `run/realworld-regression` 隔离副本；
- 每个受影响世界的逐字节备份保留在 `run/realworld-regression-jobs`。

## 尚未完成

- 真实 Youer 的游戏内请求、停服、worker、面板拉起、玩家登录和回滚全链；
- 真实服务器重启后的玩家登录与插件级功能验收；
- 大型机械盘世界、杀毒软件环境、超大 `.mcc` 的总耗时和峰值 RSS；
- 任意 Mod 私有 SavedData 或网络的通用迁移。

这些项目完成前，维护版保持 `rc` 标识。
