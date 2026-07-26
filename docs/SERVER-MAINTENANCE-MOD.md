# YuWorldRepair 服务端维护版 2.1.0-rc3

这是可直接放入独立服务端 `mods` 的 NeoForge 服务端 Mod。服主在游戏内发起并二次确认；Mod 强制保存、踢出玩家并停服，隔离 worker 只在服务端进程退出且 `session.lock` 可获得后修改世界。所有受影响文件先做完整 SHA-256 备份，采用副本写入与原子替换，应用后重新解析并验证；失败时自动回滚。下次启动会在世界加载前检查维护终态。

当前产物：

```text
build/libs/YuWorldRepair-2.1.0-rc3+mc1.21.1-neoforge.jar
```

运行要求：Java 21、Minecraft 1.21.1、NeoForge 21.1.241 至 21.1.x。不要同时安装同 Mod ID 的 1.0.0 观察版。

## 游戏内命令

需要权限等级 4。

### 一次清理 Ice and Fire

```text
/yuworldrepair repair iceandfire
```

这是 `prepare-remove iceandfire` 的安全快捷入口。它会自动捕获 Youer/Bukkit 当前加载的
全部独立世界根，包括主 `world` 和 MV 加载的 `playerworld/<世界名>`，并一次清理已支持
结构中的 `iceandfire:` 实体、NeoForge attachment、方块 palette、方块实体以及方块/流体
计划刻。

全部世界路径都会写入 HMAC 绑定。worker 先扫描并备份所有世界，任一世界存在不可解析
数据或覆盖缺口时不会修改任何世界；应用中途失败会逆序回滚已经写入的世界。

### 一次清理所有孤儿物品和附件

```text
/yuworldrepair repair orphaned-items
```

这是运维的统一入口，不再暴露 `iceandfire-chicken-data` 专用指令。服务器会同时签名
实时 `ITEM` 和 NeoForge `ATTACHMENT_TYPES` 注册表，离线 worker 递归检查标准物品、
AE2、RS、QIO，以及方块实体、物品组件、龙号角实体数据、女仆镜像数据等任意深度的
`neoforge:attachments`。`iceandfire:chicken_data` 和其他已删除 Mod 遗留附件使用
相同规则判定；仍注册的 `iceandfire:misc_data` 等附件会保留。

失效附件作为单个原子目标删除，不继续删除其内部或外层的有效物品。内部 `.mca`、
外置 `.mcc`、playerdata 和已支持 SavedData 都沿用完整备份、应用、验证与回滚流程。

### 清理已经成为孤儿的数据

```text
/yuworldrepair repair orphaned <modid>
```

服务器线程会先生成并签名当前注册表快照。停服 worker 只处理 `<modid>:` 下、并且快照证明当前已不存在的注册项。禁止选择 `minecraft`、`neoforge` 或 `forge`。

安全可判定并已实现的动作：

- 删除实体区块中的孤儿实体或乘客子树；
- 删除实体、区块和玩家根数据中的孤儿 NeoForge attachment；
- 把孤儿方块 palette 状态替换为 `minecraft:air`，并删除原 Properties；
- 删除相应方块实体、方块 scheduled tick 和流体 scheduled tick；
- 支持普通 `.mca`、外置 `.mcc` 与 `playerdata/<uuid>.dat`；
- 每个区块写入前重新扫描，要求目标集合与停服前记录完全一致。

`ORPHANED_ONLY` 不会删除快照中仍然注册的 ID。因此它适合 Mod 已删除或更新后旧 ID 已消失的情况。

### 删除 Mod 前预清理

```text
/yuworldrepair repair prepare-remove <modid>
```

这是显式破坏模式：即使 `<modid>:` 的注册项当前仍有效，也会清理上述结构中属于该命名空间的数据。只应在完整备份已确认、准备移除该 Mod 时使用。它不会删除 Mod JAR，也不会删除整个维度目录。

`iceandfire` 快捷命令与 `prepare-remove iceandfire` 使用同一清理策略。

### 二次确认、取消、状态与回滚

每次请求都会返回一次性、与发起者绑定、两分钟过期的确认令牌：

```text
/yuworldrepair repair confirm <token>
/yuworldrepair repair cancel
/yuworldrepair repair status
/yuworldrepair repair rollback
```

### 大世界只排除 region

```text
/yuworldrepair repair orphaned-items except world
/yuworldrepair repair orphaned-items only playerworld/Vicuna,playerworld/Calypso2
```

`except` 和 `only` 只控制指定世界的 region/entities/custom-dimension region。
`playerdata`、RS SavedData 等仍会处理。只要有 region 被排除，QIO 类型缓存删除会
延迟，结果标记为部分 region 范围；这样不会给未扫描 region 内的 QIO 驱动制造悬空
UUID。不存在或尚未加载的 MV 配置项不会被猜测成磁盘路径，也不会拖慢扫描；需要处理
的实际 MV 世界必须先加载，再发起命令。

回滚也要再次确认和停服。若修复后文件又被服务器写过，当前哈希既不等于修复前也不等于修复后记录值，worker 会拒绝覆盖新数据。

## 明确不做的“万能清理”

以下对象没有统一、公开、可证明正确的删除或迁移语义，当前只写入报告，不盲删：

- 任意 Mod 私有 `SavedData`、任务、领地、经济、队伍与网络图；
- AE2 网络内部存储和其他数字化仓储；
- 整个自定义维度目录、世界生成器和数据包生命周期；
- 任意自定义背包/装备槽中不使用标准 ItemStack 或 NeoForge attachment 的私有格式；
- 注册 ID 仍存在、但内部字段语义已改变的任意 Mod 更新损坏。

标准玩家 inventory、末影箱和容器中的 ItemStack 由 Minecraft 1.21.1 codec 处理：无法解析的物品变为空槽，并在后续保存时落盘。YuWorldRepair 不重复盲改这些标准槽位。

存在无法解析的 region、未支持的 DataVersion 或目标集合变化时，通用作业会把它记为 `coverageGaps` 并拒绝全部写入。报告中的 `saved_data_requires_adapter`、`dimension_directory_not_deleted` 等警告表示仍需专用适配器或人工决策，不表示已经解决。

## 安全与性能

- 正常开服期间不扫描 region、不遍历实体、不写 NBT；只有常量时间的待办/倒计时判断。
- 注册表快照、命名空间、模式、全部已加载世界路径、父进程、版本和重启策略都绑定到 HMAC 请求。
- worker 是嵌套隔离 JAR，不打包或链接 Minecraft/NeoForge 运行时类。
- 扫描为有界流式处理；region、chunk、目标、NBT 大小/深度、外置 sidecar、时间和备份总量都有硬上限。
- region 读取可按 `scanWorkers`（0=自动，1–16）并行；任务队列有界，写入始终串行。
- worker 在首次扫描前一次性持有所有签名世界的 `session.lock`，直至验证或回滚完成。
- 完整纳入范围的世界只全扫一次，应用后只复核受影响 region/独立 NBT 文件。
- 当前世界和总体扫描进度原子写入请求目录及 namespace job 的 `scan-progress.json`。
- 只为受影响文件建立完整备份和副本；未受影响 region 不重写。
- 256 个独立小 region 的本机回归样本在不同冷/热构建中为 1.853–4.862 秒，约 53–138 region/秒。该数字是合成小文件结果，不等于真实机械盘、大 NBT 世界或杀毒软件环境的承诺。

## 文件位置

维护控制文件和备份位于世界目录之外：

```text
yuworldrepair-maintenance/
  requests/<request-uuid>/
    request.json
    registry-snapshot.json       # 命名空间作业
    job-group.json               # 多世界原子作业索引
    result.json
    worker.log
    worker.jar
  jobs/<job-id>/
    manifest.json                # 精确 Ice and Fire 作业
    namespace-manifest.json      # 命名空间作业
    source-hashes.json
    targets.jsonl / namespace-targets.jsonl
    backups/
    changes.jsonl
    tool.log
    *-verification.json
```

## 重启策略

默认配置由 `config/yuworldrepair-maintenance.json` 生成：

```json
{
  "enabled": true,
  "countdownSeconds": 10,
  "startupWaitSeconds": 1800,
  "scanWorkers": 0,
  "restartStrategy": "PANEL",
  "restartCommand": []
}
```

`PANEL` 不执行 shell，由面板/容器守护策略拉起。`SELF` 只接受显式 JSON 参数数组并直接交给 `ProcessBuilder`，不经过 shell。生产使用前必须在隔离世界副本上验证面板停服、worker、自动拉起和启动门禁全链路。

## 当前验证结论

自动化测试覆盖精确附件、内部/外置区块、命名空间实体、乘客、附件、方块 palette、方块实体、tick、玩家 GZIP NBT、Minecraft Modified UTF-8、不可支持 DataVersion 全拒绝、空 region、多世界全准备后应用、多世界回滚、HMAC、启动门禁、Minecraft 原生 palette codec 以及产物边界。

真实隔离样本包含主 `world` 和 33 个有效 `playerworld` 世界。完整写入回归修复并验证了
508 个 Ice and Fire 目标；独立复扫结果为 34/34 个世界 `targets=0`、
`coverageGaps=0`，错误日志为 0。该验证不是生产服务器停服—拉起演练，因此版本仍保持
RC 标识。
