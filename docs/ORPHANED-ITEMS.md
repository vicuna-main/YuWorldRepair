# 全部失效 Mod 物品：一次统计并清理

适用版本：YuWorldRepair `2.1.0-rc3` 维护版、Minecraft 1.21.1、Youer/NeoForge。

## 运维只需要执行什么

先确认：

- 已做宿主机级整服快照；
- `world` 和所有需要处理的 Multiverse 世界都已加载；
- 服务器是通过 YuWorldRepair 的单 JAR supervisor 启动的。

然后以 4 级权限执行：

```text
/yuworldrepair repair orphaned-items
```

如果主世界约 200GB、其余 MV 世界很小，而这次不准备扫描主世界区块，可执行：

```text
/yuworldrepair repair orphaned-items except world
```

多个名称用英文逗号分隔；嵌套世界可以使用服务器相对路径：

```text
/yuworldrepair repair orphaned-items except world,playerworld/Archive
/yuworldrepair repair orphaned-items only playerworld/Vicuna,playerworld/Calypso2
```

`except`/`only` 只决定哪些世界的 `region/*.mca`、`entities/*.mca`、外置 `.mcc`
和自定义维度 region 被扫描。所有已加载世界的 `playerdata/*.dat`、
`data/refinedstorage_storages.dat` 等支持的非区块数据仍会扫描。命令只接受本次由
Youer/Bukkit 捕获的已加载世界名称；同名叶目录有歧义时必须写服务器相对路径。

当任意世界 region 被排除时，Mekanism QIO 类型缓存不会删除，只会把可删除条目计入
`deferredTargets`。这是因为未扫描的 region 可能仍放着引用这些 UUID 的 QIO 驱动。
已扫描范围中的 QIO 驱动条目仍可清理，RS、AE2、玩家背包不因此停用。结果会显示
`regionScopeComplete=false`；它表示选定范围完成，不表示全服 region 已清零。
延期项目会另外显示 `deferredByNamespace`、`deferredByStore` 和
`deferredAmountByNamespace`；游戏内状态将其显示为 `deferredByMod`、
`deferredByStore` 和 `deferredAmounts`。这些统计表示“已识别但未删除”，不会混入
本次已经修复的 `removedByMod`。状态同时显示包含延期项的 `detectedEntries` /
`detectedByMod`、实际完成写入的 `removedEntries` / `removedByMod`，以及
`cleanupComplete=false`。`success=true` 只表示所选范围安全完成，不再表示整服已经
彻底清理。

点击聊天中的红色确认按钮即可。也可以复制聊天给出的
`/yuworldrepair repair confirm <token>`；token 两分钟过期且只属于发起者。

不要创建、复制或修改
`yuworldrepair-maintenance/requests/*/request.json`。它包含有效期、父 PID、HMAC、
注册表快照哈希和本次已加载世界目录集合，只能由 Mod 生成。

确认后会依次执行：

1. 强制保存世界、断开玩家并停止 Youer；
2. 等待进程退出以及所有签名世界的 `session.lock` 释放；
3. 扫描主世界和全部已加载 MV 世界；
4. 在替换任何文件前，完成全部受影响文件的 SHA-256 完整备份；
5. 一次清理全部注册表已不存在的 Mod 物品和递归嵌套的 NeoForge attachment；
6. 重新读取 NBT、校验文件哈希并要求剩余目标为 0；
7. 任一步失败时逆序恢复已经替换的文件。

重启后查看：

```text
/yuworldrepair repair status
```

结果包含所有已发现项目的 `detectedByMod`、已修复项目的 `removedByMod`，以及因范围
安全策略延期的 `deferredByMod`。只有 `cleanupComplete=true` 且
`regionScopeComplete=true` 才能解释为全部签名世界范围已彻底完成。完整逐世界报告位于：

```text
yuworldrepair-maintenance/requests/<request-id>/result.json
yuworldrepair-maintenance/requests/<request-id>/job-group.json
yuworldrepair-maintenance/requests/<request-id>/scan-progress.json
yuworldrepair-maintenance/jobs/namespace-*/scan-summary.json
yuworldrepair-maintenance/jobs/namespace-*/scan-progress.json
```

`scan-progress.json` 是崩溃安全的原子进度快照，不是可从某个 region 自动续跑的恢复
点；进程异常终止后仍会由启动门禁失败关闭，重新发起作业会重新扫描所选范围。

需要恢复最近一次仍可回滚的作业时：

```text
/yuworldrepair repair rollback
```

回滚仍需要点击确认并停服。若修复后服务器已经再次保存过相关文件，内置回滚会拒绝
覆盖新进度，此时应使用宿主机整服快照。

## 判定规则

命令不需要也不接受 modid 列表。服务器在线时会捕获并签名完整的实时 `ITEM` 和
NeoForge `ATTACHMENT_TYPES` 注册表；离线 worker 只把以下对象判定为问题数据：

- 是结构明确的 Minecraft 1.21 ItemStack 或受支持数字仓库物品条目；
- 或是位于明确 `neoforge:attachments` 容器中的附件键；
- 资源 ID 不在对应的签名实时注册表中；
- 命名空间不是 `minecraft`、`neoforge` 或 `forge`。

因此，仍安装且仍注册的 Mekanism、AE2、RS 等物品不会因为名称或所在容器而被清理。
同样，仍注册的附件会保留；`iceandfire:chicken_data`、已删除 Mod 的附件以及其他
任意命名空间的孤儿附件会被一次找出。失效附件按父节点原子删除，不会因为其内部
恰好还包含失效物品而连带删除外层仍有效的龙号角、女仆收纳物或容器物品。
统计中的 `entries` 是落盘序列化条目数；`amounts` 是能安全读取到的物品数量。
某些 Mod 会保存镜像副本，例如女仆数据同时存在于 `NeoForgeData` 和组件中，这种情况
会显示两个序列化条目，不能把 `entries` 直接当成玩家看到的格子数。

## 当前明确支持的存储

- 玩家背包、末影箱、装备、物品实体；
- 方块实体、实体、NeoForge attachment 里的标准 1.21 ItemStack；
- 方块实体、实体、玩家、物品组件、龙号角实体数据和女仆镜像数据中任意深度的
  `neoforge:attachments`；
- 标准容器、嵌套 ItemStack 和固定长度装备列表；
- AE2 `ae2:storage_cell_inv` 与 `ae2:storage_cell_config_inv`；
- Refined Storage 2.x `data/refinedstorage_storages.dat` 的 item repository；
- Mekanism QIO：
  - 主世界 `data/mekanism_qio_type_cache.dat` 的 UUID→ItemStack 类型映射；
  - 任意已加载 MV 世界中的 `mekanism:drive_contents` UUID/数量三元组；
  - 删除 QIO 条目时同步修正 `mekanism:drive_metadata` 的数量和类型数。

Mekanism 的 QIO 类型缓存属于主世界，但驱动可以存在于 `playerworld/*`。维护请求会在
全部世界的准备、应用和复验期间共享主世界的同一份预写入索引，避免漏掉非 `world`
目录里的 QIO 驱动。

## 大世界的执行模型

- region 文件以独立任务并行只读；队列最多保留约 `2 × scanWorkers` 个未完成任务，
  不会把全部 chunk/NBT 同时装入内存。
- `scanWorkers=0` 默认使用约一半 CPU，最少 1、最多 8；可以在
  `config/yuworldrepair-maintenance.json` 显式设为 1–16。机械盘建议从 2 开始，
  NVMe 可先用 4–8，并观察吞吐。
- 所有签名世界的 `session.lock` 会在首次扫描前一次性获取，并一直持有到全部世界
  完成、验证或回滚。并行线程从不执行写入。
- 持锁后每个完整纳入范围的世界只做一次全量扫描；应用前逐源文件复核 SHA-256，
  每个目标 chunk 再核对精确目标集；应用后只重扫受影响 region/独立 NBT 文件。
- 备份只包含实际受影响源文件，不按 200GB 世界总大小制作第二份世界副本；宿主机
  完整快照仍必须由运维在作业前完成。
- 受影响文件备份总量硬上限为 512GiB；作业还会按备份总量、最大单文件临时副本和
  64MiB 安全余量检查实际可用空间，空间不足时在首次替换前失败。
- 扫描上限为 1,000,000 个 region、64,000,000 个 chunk、24 小时和 262,144 个
  目标。超限、旧 DataVersion、损坏 region 或超过 100,000 个 playerdata 文件都
  明确失败关闭，不会静默截断。

用户所说的 “aio” 在提供的整合包中没有找到对应 JAR 或 modid；真实地图中存在的是
Mekanism QIO，本实现已覆盖 QIO。如果 “AIO” 是另一个独立 Mod，需要提供准确的
modid/JAR，不能拿未知私有格式按 QIO 猜测。

## 不会盲目处理的对象

- 任意未知 Mod 的私有 SavedData、任务、领地、经济、队伍或网络图；
- 没有使用标准 ItemStack、AE2、RS 或 QIO 已验证格式的私有数字仓库；
- 注册 ID 仍存在，但 Mod 新版本改变了私有字段语义的数据损坏；
- 发起命令时未由 Youer/Bukkit 暴露为“已加载”的 MV 世界。

未知私有格式不会因为内部出现一个像资源 ID 的字符串就被删除。已支持的 RS/QIO
SavedData 如果存在但结构损坏，会产生 `coverageGaps`，并让全部世界在首次替换前
整体拒绝写入。

## 真实样本回归

提供的隔离 `world + playerworld` 样本共有 34 个有效世界根。模拟移除已确认的命名
空间后，扫描得到 29 个目标且 34/34 世界 `coverageGaps=0`：

| 存储 | 序列化目标数 |
|---|---:|
| 标准 ItemStack | 22 |
| AE2 | 6 |
| QIO 类型缓存 | 1 |
| RS | 0 |

样本命中的问题命名空间为 `adpother`、`evolvedmekanism`、
`kaleidoscope_tavern` 和 `toughasnails`。样本的 RS 仓库含有效库存，没有发现失效
RS 物品；这表示“不需要修改”，不是没有扫描 RS。

在隔离副本上的完整写入回归已完成：34 个世界全部准备后应用 29 个目标，逐世界复扫
为 0，随后 8 个实际受影响世界全部逐文件回滚并通过哈希校验。桌面原始 ZIP 未修改。

## YuVault 与 Youer

YuVault 不属于世界 NBT，它使用自己的 SQLite/MySQL `vaults.contents`，因此本命令
不会跨边界修改 YuVault 数据库。

当前 YuVault `1.1.1.0` 会在保险柜被加载时：

1. Bukkit 反序列化；
2. 用 `ItemStacks.isPresent()` 把 `null`、数量小于等于 0 或
   `Material.AIR` 归一化为空槽；
3. 因规范化结果变化而标记 migration；
4. `StorageManager.migrateLoadedVault()` 立即回写数据库。

所以它会自动清除“本次被加载的保险柜”里的已删除 Mod 物品，但不是一次遍历所有
owner 的全库清理。这个自动回写也意味着生产操作前必须备份 YuVault 数据库。

Youer 已经在 `CraftServer` 初始化时调用 `NeoForgeInjectBukkit.init()`，遍历
`BuiltInRegistries.ITEM`，为当前已安装的 Mod 物品动态注入 Bukkit `Material`，并
建立 `CraftMagicNumbers.ITEM_MATERIAL/MATERIAL_ITEM` 双向映射。
`CraftItemStack.isEmpty()` 也直接委托真实 handle 的 `isEmpty()`。因此 YuVault
判空可以优先使用公开的 `ItemStack.isEmpty()`（为兼容 Bukkit 1.8 编译目标可反射
调用），不需要反射 NMS handle；`Material.AIR` 只能作为旧服务端的兼容回退。
