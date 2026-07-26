# Known Limitations

- `orphaned-items` supports standard 1.21 ItemStacks and the explicitly verified AE2, Refined
  Storage 2.x, and Mekanism QIO formats. Unknown private SavedData/network schemas are not mutated.
- YuVault is a separate SQLite/MySQL data store and is not modified by world maintenance. Its
  current load-time normalization can rewrite loaded vaults; back up that database separately.
- The supplied pack has no identifiable `aio` JAR/modid. Mekanism QIO is supported; a distinct
  AIO implementation requires its exact modid and storage schema.

- `2.1.0-rc3` 已完成真实 34 世界隔离副本写入验证及真实短扇区尾区域文件重写验证，但尚未完成“游戏内请求 → 保存/停服 → worker 修复 → 面板拉起 → 玩家登录 → 回滚”的生产形态整链演练，因此不能标记为生产稳定版。
- 多世界维护只包含发起命令时由 Youer/Bukkit 暴露为“已加载”的独立世界。没有加载的 MV 世界不会被猜测或遍历；需要先加载后再发起维护。
- `orphaned-items except/only` 是显式部分 region 范围。排除的世界仍扫描 playerdata 和受支持 SavedData；QIO 类型缓存删除会延迟，报告不能解释为全服 region 已清零。
- `scan-progress.json` 是持久进度快照，不支持在 JVM 中断后从已完成 region 自动续跑。
- 四线程已在 34 个真实隔离世界根上扫描 2,231,752,908 region 字节并得到零覆盖缺口；
  尚未测量 200GB 单世界、机械盘、杀毒软件、超大 `.mcc` 的总耗时和峰值 RSS。
- 通用结构适配器支持 Minecraft DataVersion 3953..3955。遇到其他 DataVersion、损坏 header、不可解析 NBT 或未知结构，会产生 `coverageGaps` 并拒绝整次写入。
- Mod 私有 SavedData、AE2/数字仓储网络、任务、领地、经济、队伍、整个维度目录和世界生成器只审计，不修改。
- `orphaned` 只能处理注册 ID 已经消失的数据；ID 仍存在但私有字段语义已变化的版本升级，必须有对应 Mod 的专用迁移适配器。
- `prepare-remove` 是显式破坏模式，只覆盖实体/乘客、NeoForge attachment、方块 palette、方块实体、scheduled ticks 和玩家根 attachment；它不会删除 JAR、配置、SavedData 或维度目录。
- 标准 ItemStack 的无效物品仍由 Minecraft 自己解析为空槽；第三方自定义背包若既不使用标准 ItemStack，也不使用可识别 attachment，当前只会在报告中说明未覆盖。
- 文件系统必须支持可靠原子替换。不可用时直接拒绝，不使用非原子降级。
- 原始 140,382 行错误日志已被覆盖；重建 fixture 能验证 logger、模板与 ID，但不能替代原文件逐行哈希和准确计数证据。
