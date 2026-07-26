# 单 JAR 面板部署

## 运维人员只需要知道的内容

只部署一个文件。把构建产物

```text
YuWorldRepair-2.1.0-rc3+mc1.21.1-neoforge.jar
```

复制或重命名为：

```text
mods/YuWorldRepair-maintenance.jar
```

`mods` 中只能保留这一份 YuWorldRepair 维护版，必须移除旧版本文件。以后升级只替换这个
固定文件，不需要修改面板启动命令。

然后用同一个 JAR 包住原来的 Youer 启动命令：

```text
java -jar mods/YuWorldRepair-maintenance.jar -- <原来的 Youer 启动命令>
```

例如原命令是：

```text
java -Xms4G -Xmx4G -jar youer-1.21.1.jar nogui
```

面板中改成：

```text
java -jar mods/YuWorldRepair-maintenance.jar -- java -Xms4G -Xmx4G -jar youer-1.21.1.jar nogui
```

不需要第二个 supervisor JAR，不需要启动脚本，也不需要修改现有的 `PANEL` 配置。

## 配置

可以保持：

```json
{
  "enabled": true,
  "countdownSeconds": 10,
  "startupWaitSeconds": 1800,
  "restartStrategy": "PANEL",
  "restartCommand": []
}
```

`PANEL` 维护现在会检查统一 JAR 启动器的握手。如果运维人员误用原命令直接启动 Youer，
服务器仍可正常运行，但修复命令会在停服前拒绝执行并提示启动方式不正确。

旧配置中已经使用 `SUPERVISOR` 的可以继续使用，它作为兼容别名保留。

## 日常修复操作

服务器完全启动后，在控制台执行：

```text
yuworldrepair repair iceandfire
```

此命令会自动包含当前由 Youer/MV 加载的主世界和所有独立世界目录；运维不填写
`world`、`playerworld` 或玩家世界名称。清理历史鸡附件或其他已删除 Mod 的孤儿
物品/附件时统一使用 `yuworldrepair repair orphaned-items`。

复制返回的确认令牌，并在两分钟内执行：

```text
yuworldrepair repair confirm <返回的令牌>
```

随后不要在面板中手工点击“停止”或“重启”。正常流程是：

1. Mod 保存世界并让 worker 完成授权握手。
2. Youer/Minecraft Java 子进程退出。
3. 同一个 YuWorldRepair JAR 中的 supervisor 仍然作为面板主进程存活。
4. worker 独占取得全部已加载世界的 `session.lock`，先完成全世界扫描和备份，再修复和验证。
5. worker 原子写入 `result.json` 并退出。
6. supervisor 随后退出，面板按原有策略重新拉起相同启动命令。

## 禁止事项

不要手写或修改：

```text
yuworldrepair-maintenance/requests/<request-id>/request.json
```

它包含有效期、父进程 PID、HMAC 路径绑定和一次性授权。只允许 Mod 通过游戏内命令生成。

不要为了排障删除请求目录、跳过 HMAC、缩短启动门禁或忽略 `session.lock`。

## 首次上线验证

先在完整隔离的 Youer 副本上演练：

1. 启动日志出现 `server_started`。
2. 执行修复和确认命令后，`handoff.json` 先到达 `WAITING_FOR_STOP`。
3. Youer Java 退出时，面板显示的主进程仍然存活。
4. `worker.log` 出现结构化状态转换记录。
5. 作业目录产生备份、变更记录和验证报告。
6. 请求目录产生终态 `result.json`。
7. 日志出现 `maintenance_complete`，随后面板重新启动服务器。
8. 新服务器启动时不再等待额外 1800 秒，并能正常加载世界。

若失败发生在任何可能已经写世界的阶段，启动门禁仍会拒绝加载，除非回滚已经验证。
若旧服务端在 worker 交接前退出，并且请求已过期且没有任何写入证据，启动门禁会写入
安全的失败结果后立即继续启动。

## 实现边界

统一 JAR 的 supervisor 只使用 Java 标准库，不链接 Gson、Minecraft、NeoForge 或世界修复类。
它只读取请求目录中的生命周期控制文件和进程信息；实际世界访问仍由经过 HMAC 授权、
并成功独占 `session.lock` 的隔离 worker 完成。
