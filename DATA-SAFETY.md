# Data Safety

`orphaned-items` uses the signed live item registry as its only orphan authority. It prepares and
backs up all signed world roots before replacing the first file. Standard ItemStacks and the
verified AE2, Refined Storage, and Mekanism QIO schemas are supported; arbitrary private SavedData
is not inferred from resource-like strings. The primary-world QIO UUID index is held constant
through scanning, apply, and verification of every loaded Multiverse world.

Schema-5 requests additionally HMAC-bind the exact worlds whose region files are excluded and the
parallel reader count. Exclusion never suppresses playerdata or supported SavedData. If any region
root is excluded, QIO type-cache deletion is deferred so a drive inside an unscanned region cannot
retain a dangling UUID.

## 在线阶段

维护请求在线创建时只捕获当前注册表快照、请求参数、一次性确认信息，以及 Youer/Bukkit 当前已加载的全部独立世界根。执行 `confirm` 后，Mod 强制保存世界、踢出玩家并停止服务器；worker 会在首次扫描前一次性获取所有已签名世界的 `session.lock`，并持有到整组作业验证或回滚完成。

请求中的完整世界路径集合、作业根、父进程、操作、命名空间、注册表快照、版本和重启策略都绑定到 HMAC。worker 不接受临时替换的路径或未签名参数。

## 离线写入

- 每个区块写入前重新扫描，目标集合必须与扫描记录完全一致；
- 每个受影响 `.mca`、`.mcc` 或玩家 `.dat` 都先建立完整备份并校验 SHA-256；
- 多世界作业先扫描和备份所有世界，之后才允许替换第一个文件；
- 使用同目录临时副本、语义复读和原子替换；
- 完整范围在首次扫描后通过源哈希和逐 chunk 精确目标集复核；修复后只重扫受影响文件并要求剩余目标为 0；
- 任一步失败都会在安全前提满足时逆序恢复已经替换的文件和世界；
- 无法证明原子替换可靠时直接拒绝，不使用非原子降级。

回滚只覆盖当前仍等于作业记录 post hash 的文件。如果修复后文件已被服务器再次保存，内置回滚拒绝抹掉新进度。

## 通用结构的失败关闭

命名空间适配器只处理已经定义并测试的 Minecraft/NeoForge 结构。遇到不支持的 DataVersion、损坏 region、不可解析 NBT 或覆盖缺口时，整个通用作业拒绝全部写入，而不是跳过未知文件后继续清理其他位置。

私有 SavedData、任务、领地、经济、队伍、数字仓储网络、完整维度目录和非标准背包格式没有统一删除语义，只记录警告。

## 独立实验工具

离线 world tool 与维护 Mod 是独立产物。它只接受：

- 绝对世界副本路径；
- 精确副本标记 `.yuworldrepair-world-copy`；
- 常规 `level.dat`；
- 当前可获取的 `session.lock`；
- 世界目录之外的作业根；
- 非符号链接、junction 或特殊路径。

公开版本不包含任何操作者本机路径。可通过 JVM 属性 `yuworldrepair.protectedRoots` 传入平台路径分隔的生产根目录列表；包装脚本的 `-ProtectedRoot` 参数会自动设置该属性。世界副本、作业目录或已存在作业位于保护根内时直接拒绝。

实验工具对内部 `.mca` 和由 region 引用的外置 `.mcc` 均执行独立完整备份、应用、验证和回滚。

## 宿主机备份

内置备份只覆盖被当前作业修改的文件，不能替代整服快照。生产维护前必须保留世界、配置、Mod、玩家数据和维护作业目录的宿主机级完整备份。
