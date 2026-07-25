# Testing

## 自动化

完整验证命令：

```powershell
.\gradlew.bat clean build --no-build-cache --rerun-tasks
```

构建覆盖：

- 观察/日志规则、限流、并发和配置回退；
- HMAC 维护请求、一次性确认、父进程退出、`session.lock` 和启动门禁；
- NBT 类型、深度、集合、尾随数据和压缩限制；
- Anvil header、sector、gzip/zlib/未压缩 chunk，以及内部 `.mca` 和外置 `.mcc`；
- `iceandfire:chicken_data` 精确扫描、备份、删除、验证和逐字节回滚；
- 命名空间实体/乘客、attachment、方块 palette、方块实体和 scheduled tick；
- 玩家 GZIP NBT；
- 不支持 DataVersion、不可解析 region 和覆盖缺口的全写入拒绝；
- 修复后新变化阻止回滚覆盖；
- Minecraft 1.21.1 原生 `PalettedContainer` codec 兼容；
- 观察版、离线工具、维护版和嵌套 worker 的依赖/入口边界。

构建任务还执行：

- `verifyReleaseBoundary`
- `verifyWorldToolBoundary`
- `verifyMaintenanceBoundary`

它们分别阻止观察版泄漏修改入口、离线工具依赖 Minecraft/NeoForge，以及维护版缺少嵌套 worker 或必要命令。

当前发布候选的两次强制全构建均通过，共 57 项测试：

- Minecraft/NeoForge 测试：22；
- 维护交接测试：6；
- 离线工具测试：29；
- failure/error/skipped：0/0/0。

## 真实世界只读证据

一个真实服务器世界的隔离副本完成了只读结构验证：

- 精确实体扫描：9 个非空 entity region、384 个实体 chunk、22 个空 region 被记录并跳过、0 个目标；
- 通用结构探针：73 个非空 chunk/entity region、20,317 个 chunk、0 个结构覆盖缺口；
- 原始服务器目录没有被写入；
- 因副本中不存在 `iceandfire:chicken_data`，没有冒充完成真实目标 apply/rollback。

## 尚未完成

- 真实 Youer 的游戏内请求、停服、worker、面板拉起、玩家登录和回滚全链；
- 含真实目标的世界副本 apply/rollback；
- 大型机械盘世界、杀毒软件环境、超大 `.mcc` 的总耗时和峰值 RSS；
- 任意 Mod 私有 SavedData 或网络的通用迁移。

这些项目完成前，维护版保持 `rc` 标识。
