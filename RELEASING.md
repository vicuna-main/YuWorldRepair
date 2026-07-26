# Releasing

当前公开发布目标是 `2.1.0-rc3`，不是生产稳定版。

## 发布前检查

1. 确认源码树不含绝对本机路径、真实日志、世界文件、令牌或内部证据。
2. 执行：

   ```powershell
   .\gradlew.bat clean build --no-build-cache --rerun-tasks
   ```

3. 确认所有测试和以下任务通过：

   ```text
   verifyReleaseBoundary
   verifyWorldToolBoundary
   verifyMaintenanceBoundary
   ```

4. 再执行一次强制构建，确认产物 SHA-256 可复现。
5. 更新 `SHA256SUMS`、`CHANGELOG.md`、`TESTING.md` 和兼容范围。
6. 使用标签 `v2.1.0-rc3` 创建 GitHub prerelease。

## GitHub Release 附件

主要附件：

```text
YuWorldRepair-2.1.0-rc3+mc1.21.1-neoforge.jar
YuWorldRepair-2.1.0-rc3+mc1.21.1-neoforge-sources.jar
SHA256SUMS
```

离线实验工具可以作为额外附件，但必须明确标记 `experimental`。不要把 `1.0.0` 观察版和 `2.1.0-rc3` 维护版描述为可以同时安装。

## Release 说明必须包含

- RC 状态和未完成的真实全链验证；
- Java/Minecraft/NeoForge 要求；
- 安装与完整备份要求；
- `prepare-remove` 的破坏性语义；
- 内置回滚无法覆盖修复后新增世界进度；
- 私有 SavedData、数字仓储和完整维度目录不在通用自动修复范围。
