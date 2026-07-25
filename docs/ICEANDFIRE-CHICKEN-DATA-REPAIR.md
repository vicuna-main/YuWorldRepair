# Ice and Fire `chicken_data` 离线工具

`YuWorldRepair-world-tool-1.1.0-experimental+mc1.21.1.jar` 是独立 Java 21 CLI。它不是游戏内 Mod，只允许操作已经停服的世界副本，并且只删除：

```text
entity["neoforge:attachments"]["iceandfire:chicken_data"]
```

服务器维护版用户应优先使用游戏内：

```text
/yuworldrepair repair iceandfire-chicken-data
```

独立 CLI 主要用于隔离副本预检和开发验证。

## 副本要求

世界副本必须包含常规 `level.dat`，不得包含符号链接或 junction，并在副本根创建：

```text
.yuworldrepair-world-copy
```

文件内容必须精确为：

```text
YUWORLDREPAIR_WORLD_COPY_V1
```

只能在确认复制完成的副本中创建该标记。不要在生产世界中创建标记。`scripts/New-YouerTestClone.ps1` 会在验证源文件和副本 SHA-256 一致后自动创建标记。

## 包装脚本

示例路径仅为占位符：

```powershell
$tool = 'E:\YuWorldRepair\YuWorldRepair-world-tool-1.1.0-experimental+mc1.21.1.jar'
$world = 'E:\MinecraftCopies\maintenance-001\world'
$jobs = 'E:\MinecraftCopies\maintenance-001\repair-jobs'
$ice = 'E:\MinecraftCopies\maintenance-001\mods\iceandfire-2.0-beta.17.jar'
$production = 'D:\MinecraftProduction'
```

只读扫描：

```powershell
.\scripts\Run-WorldRepair.ps1 `
  -Action Scan `
  -ToolJar $tool `
  -WorldCopy $world `
  -JobRoot $jobs `
  -IceAndFireJar $ice `
  -ProtectedRoot $production
```

参数含义：

- `Scan`：只读扫描并创建绑定源哈希的作业，不修改世界；
- `ProtectedRoot`：拒绝世界副本、作业根或现有作业落入指定生产目录；
- `JobRoot`：存放作业、报告和完整备份，必须位于世界副本之外；
- `IceAndFireJar`：必须是已核验的 2.0-beta.17 JAR。

准备备份并取得一次性应用令牌：

```powershell
.\scripts\Run-WorldRepair.ps1 -Action Prepare -ToolJar $tool -Job '<absolute-job-directory>' -ProtectedRoot $production
```

应用修复：

```powershell
.\scripts\Run-WorldRepair.ps1 -Action Apply -ToolJar $tool -Job '<absolute-job-directory>' -ConfirmToken '<one-time-token>' -ProtectedRoot $production
```

验证修复：

```powershell
.\scripts\Run-WorldRepair.ps1 -Action Verify -ToolJar $tool -Job '<absolute-job-directory>' -ProtectedRoot $production
```

申请并执行回滚：

```powershell
.\scripts\Run-WorldRepair.ps1 -Action Rollback -ToolJar $tool -Job '<absolute-job-directory>' -ConfirmToken '<rollback-token>' -ProtectedRoot $production
.\scripts\Run-WorldRepair.ps1 -Action VerifyRollback -ToolJar $tool -Job '<absolute-job-directory>' -ProtectedRoot $production
```

`Apply` 和 `Rollback` 的令牌都是短期、一次性并绑定作业状态的。回滚前如果当前文件不再等于记录的修复后哈希，工具会拒绝覆盖。

内部 `.mca` 和外置 `c.<x>.<z>.mcc` 都支持完整备份、应用、验证和逐字节回滚。任何来源哈希、实体语义、附件路径、UUID 或目标集合变化都会让写入失败关闭。
