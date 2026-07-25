# Changelog

本项目使用语义化版本风格；预发布版本在完成真实服务器全链验证前保持 `rc` 标识。

## 2.1.0-rc1

- 项目名称、Mod ID、命令、包名、配置和产物统一为 YuWorldRepair / `yuworldrepair`。
- 新增服务端游戏内维护命令和二次确认。
- 新增注册表快照驱动的 `orphaned` 清理。
- 新增删除 Mod 前的 `prepare-remove` 模式。
- 新增 `iceandfire:chicken_data` 精确修复。
- 支持实体、乘客、NeoForge attachment、方块 palette、方块实体、方块/流体计划刻和玩家根 attachment。
- 支持内部 `.mca`、外置 `.mcc` 和玩家 GZIP NBT。
- 新增完整受影响文件备份、原子替换、写后验证、自动故障恢复和受 post hash 保护的回滚。
- 新增 HMAC 请求绑定、父进程退出等待、`session.lock`、启动门禁和可配置重启策略。
- 移除操作者特定路径，离线工具改用显式 `protectedRoots`。
- 增加公开发布文档、GitHub Actions 构建和隐私清理。

## 1.1.0-experimental

- 独立离线 world tool，用于精确 Ice and Fire 废弃附件修复。

## 1.0.0

- 零游戏数据修改的观察和日志风暴防护版。
