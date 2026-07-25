# Contributing

感谢参与 YuWorldRepair。涉及世界数据写入的改动必须比普通功能改动提供更强的证据。

## 开发环境

- Java 21
- Gradle Wrapper 8.10.2
- Minecraft 1.21.1
- NeoForge 21.1.241

运行完整验证：

```bash
./gradlew build --no-daemon --no-build-cache
```

Windows 使用：

```powershell
.\gradlew.bat build --no-daemon --no-build-cache
```

## 提交要求

- 不提交 `build/`、`run/`、`.gradle/`、真实世界、日志或本机证据；
- 不提交用户名、绝对服务器路径、玩家 UUID、令牌或未脱敏 NBT；
- 修改写入逻辑时必须包含扫描、应用、验证和回滚测试；
- 新适配器必须绑定明确 Mod/版本和可证明的数据语义；
- 不得通过“跳过错误”绕过 `coverageGaps`；
- 不得移除写前哈希、完整备份、原子替换或 post hash 回滚保护；
- 性能优化必须保留硬上限和失败关闭行为。

Pull Request 应说明兼容范围、最坏失败模式、测试结果和尚未验证的真实环境。
