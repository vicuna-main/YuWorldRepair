# YuWorldRepair 服务端维护版 2.1.0-rc1

这是可直接放入独立服务端 `mods` 的 NeoForge 服务端 Mod。服主在游戏内发起并二次确认；Mod 强制保存、踢出玩家并停服，隔离 worker 只在服务端进程退出且 `session.lock` 可获得后修改世界。所有受影响文件先做完整 SHA-256 备份，采用副本写入与原子替换，应用后重新解析并验证；失败时自动回滚。下次启动会在世界加载前检查维护终态。

当前产物：

```text
build/libs/YuWorldRepair-2.1.0-rc1+mc1.21.1-neoforge.jar
```

运行要求：Java 21、Minecraft 1.21.1、NeoForge 21.1.241 至 21.1.x。不要同时安装同 Mod ID 的 1.0.0 观察版。

## 游戏内命令

需要权限等级 4。

### 精确修复 Ice and Fire 鸡附件

```text
/yuworldrepair repair iceandfire-chicken-data
```

只删除鸡实体 `neoforge:attachments` 下的 `iceandfire:chicken_data`。只接受已经核验的 Ice and Fire 2.0-beta.17 JAR：

```text
ee52349445615417e69ab64cf15dbb96b9332a9117225119d281695c5f8d90f0
```

内部 `.mca` 和外置 `c.<x>.<z>.mcc` 实体区块都支持备份、应用、验证与回滚。

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

### 二次确认、取消、状态与回滚

每次请求都会返回一次性、与发起者绑定、两分钟过期的确认令牌：

```text
/yuworldrepair repair confirm <token>
/yuworldrepair repair cancel
/yuworldrepair repair status
/yuworldrepair repair rollback
```

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
- 注册表快照、命名空间、模式、路径、父进程、版本和重启策略都绑定到 HMAC 请求。
- worker 是嵌套隔离 JAR，不打包或链接 Minecraft/NeoForge 运行时类。
- 扫描为有界流式处理；region、chunk、目标、NBT 大小/深度、外置 sidecar、时间和备份总量都有硬上限。
- 只为受影响文件建立完整备份和副本；未受影响 region 不重写。
- 256 个独立小 region 的本机回归样本在不同冷/热构建中为 1.853–4.862 秒，约 53–138 region/秒。该数字是合成小文件结果，不等于真实机械盘、大 NBT 世界或杀毒软件环境的承诺。

## 文件位置

维护控制文件和备份位于世界目录之外：

```text
yuworldrepair-maintenance/
  requests/<request-uuid>/
    request.json
    registry-snapshot.json       # 命名空间作业
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
  "restartStrategy": "PANEL",
  "restartCommand": []
}
```

`PANEL` 不执行 shell，由面板/容器守护策略拉起。`SELF` 只接受显式 JSON 参数数组并直接交给 `ProcessBuilder`，不经过 shell。生产使用前必须在隔离世界副本上验证面板停服、worker、自动拉起和启动门禁全链路。

## 当前验证结论

自动化测试覆盖精确附件、内部/外置区块、命名空间实体、乘客、附件、方块 palette、方块实体、tick、玩家 GZIP NBT、不可支持 DataVersion 全拒绝、空 region、故障恢复、HMAC、启动门禁、Minecraft 原生 palette codec 以及产物边界。实际世界隔离副本已完成只读扫描，但没有发现目标键；完整 Youer 克隆又因检测到引用原目录的 Java 进程而安全拒绝。RC 仍未完成停服—修复—重启演练，因此不能标为“生产稳定版”。
