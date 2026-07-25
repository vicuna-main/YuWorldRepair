# YuWorldRepair

YuWorldRepair 是面向 Minecraft 1.21.1 / NeoForge 21.1.x 的服务端世界修复 Mod。它用于处理 Mod 更新或移除后遗留的孤儿实体、附件、方块、方块实体和计划刻，并为每次写入提供停服交接、完整备份、写后验证和受控回滚。

> 当前维护版为 `2.1.0-rc1`。它适合在完整整服快照和隔离副本演练后进行受控维护，但尚未完成真实 Youer 服务器的“请求—停服—修复—面板拉起—玩家登录—回滚”全链验证，因此不应标记为生产稳定版。

## 环境要求

- Java 21
- Minecraft 1.21.1
- NeoForge 21.1.241 至 21.1.x
- 独立服务器；维护指令需要权限等级 4

维护版产物：

```text
build/libs/YuWorldRepair-2.1.0-rc1+mc1.21.1-neoforge.jar
```

只把这一份维护版 JAR 放入服务端 `mods`。不要同时安装仓库生成的 `1.0.0` 观察版，因为两者使用同一个 Mod ID：`yuworldrepair`。

## 维护流程

1. 先创建宿主机级完整整服快照。
2. 在隔离副本完成相同操作的演练。
3. 在线发起修复指令；此时只创建注册表快照和待确认请求，不修改世界。
4. 执行聊天中给出的 `confirm` 指令。
5. Mod 强制保存世界、踢出玩家并停止服务器。
6. 隔离 worker 等待服务器进程退出且 `session.lock` 可获取后，离线扫描、备份、修复并验证。
7. 检查作业报告，再由面板或管理员启动服务器。

首次维护建议在 `config/yuworldrepair-maintenance.json` 中使用：

```json
{
  "enabled": true,
  "countdownSeconds": 30,
  "startupWaitSeconds": 1800,
  "restartStrategy": "NONE",
  "restartCommand": []
}
```

- `enabled`：是否允许创建维护请求。
- `countdownSeconds`：确认后到停服之间的倒计时，允许 5–300 秒。
- `startupWaitSeconds`：启动门禁等待离线 worker 终态的最长时间，允许 60–3600 秒。
- `restartStrategy: "NONE"`：修复后不自动启动，便于先检查报告。
- `restartStrategy: "PANEL"`：不执行命令，由面板或容器守护策略重新拉起。
- `restartStrategy: "SELF"`：按 `restartCommand` 参数数组直接启动进程，不经过 shell；数组不能为空。

## 全部游戏内指令

观察与诊断指令：

| 指令 | 作用 |
|---|---|
| `/yuworldrepair status` | 显示日志防护模式、过滤器状态、签名容量和累计计数。它不是维护作业状态。 |
| `/yuworldrepair report [seconds]` | 将指定时间窗口的脱敏诊断报告写入服务器目录；省略参数时使用 3600 秒。 |
| `/yuworldrepair signatures [domain]` | 列出当前有界错误签名，可按错误域筛选；不会读取或修改世界。 |
| `/yuworldrepair inspect ae2` | 显示 AE2 兼容性识别结果，只读。 |
| `/yuworldrepair reload` | 重新读取观察/日志配置；配置无效时回退到安全默认值。 |
| `/yuworldrepair selftest` | 执行只读运行时自检，报告规则和环境状态。 |

世界维护指令：

| 指令 | 作用 |
|---|---|
| `/yuworldrepair repair iceandfire-chicken-data` | 精确扫描并删除鸡实体附件中的废弃键 `iceandfire:chicken_data`，不删除鸡实体。 |
| `/yuworldrepair repair orphaned <modid>` | 根据在线服务器签名注册表快照，只清理 `<modid>:` 下当前确实不存在的注册项。 |
| `/yuworldrepair repair prepare-remove <modid>` | 删除 Mod 前的显式清理模式；即使注册项仍存在，也清理已支持结构中的该命名空间数据。 |
| `/yuworldrepair repair confirm <token>` | 使用两分钟内有效、与发起者绑定的一次性令牌确认停服维护。 |
| `/yuworldrepair repair cancel` | 在离线 worker 接管前取消待确认或倒计时中的请求；世界不会被修改。 |
| `/yuworldrepair repair status` | 显示当前维护请求或最近一次维护结果、成功状态和回滚可用性。 |
| `/yuworldrepair repair rollback` | 为最近一次可回滚作业创建恢复请求；仍需再次执行 `confirm`，随后停服回滚。 |

`<modid>` 是资源 ID 冒号前的命名空间。例如 `examplemod:machine` 的 Mod ID 是 `examplemod`。`minecraft`、`neoforge` 和 `forge` 被禁止作为清理目标。

## 扫描和修改范围

命名空间维护会扫描当前世界存档中所有已经生成并落盘的标准数据，而不只扫描停服前加载的区块：

- 主世界、下界、末地；
- `dimensions/<namespace>/<path>/` 下的自定义维度；
- 每个维度的 `region/r.<x>.<z>.mca` 和 `entities/r.<x>.<z>.mca`；
- 由 region 引用的外置 `c.<x>.<z>.mcc`；
- `playerdata/<uuid>.dat` 中的玩家根 NeoForge attachment。

已支持的动作包括孤儿实体/乘客删除、NeoForge attachment 删除、方块 palette 替换为空气、方块实体删除，以及方块/流体计划刻删除。任何不可解析 region、未支持 DataVersion 或覆盖缺口都会让整次通用写入失败关闭。

以下数据没有统一安全删除语义，目前只审计或交给 Minecraft 自身处理：

- Mod 私有 SavedData、任务、领地、经济、队伍和网络；
- AE2 等数字仓储网络内部数据；
- 整个自定义维度目录和世界生成器；
- 非标准背包私有格式；
- 注册 ID 仍存在、但新版 Mod 改变了私有 NBT 字段语义的升级损坏；
- 标准 inventory、末影箱和容器 ItemStack 的缺失物品清理由 Minecraft codec 处理。

## 备份与回滚

维护数据位于服务器根目录下的 `yuworldrepair-maintenance/`，不放在世界目录内。只有实际受影响的源文件会建立完整备份，写入采用同目录副本、语义复读、SHA-256 校验和原子替换。

内置回滚只在当前文件仍等于作业记录的修复后哈希时执行。如果服务器重启后又保存过相关文件，回滚会拒绝覆盖新进度。宿主机级完整快照始终是最终恢复手段。

## 构建与验证

Windows：

```powershell
.\gradlew.bat build --no-daemon
```

Linux/macOS：

```bash
./gradlew build --no-daemon
```

构建会运行 Minecraft/NeoForge 测试、纯 Java 离线工具测试、维护交接测试和三个产物边界检查。详细证据见 [TESTING.md](TESTING.md)，兼容范围见 [COMPATIBILITY.md](COMPATIBILITY.md)，已知限制见 [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md)。

离线实验工具的单独说明见 [docs/ICEANDFIRE-CHICKEN-DATA-REPAIR.md](docs/ICEANDFIRE-CHICKEN-DATA-REPAIR.md)。发布流程见 [RELEASING.md](RELEASING.md)。

## 许可证

项目使用 [MIT License](LICENSE)。离线工具打包的第三方组件许可证和通知位于 `src/worldTool/resources/META-INF/`。
