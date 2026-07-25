# Benchmark

以下结果用于发现明显性能回退，不是所有服务器环境的 SLA。

## 日志热路径

测试环境为 Java 21 / Windows，预热 1,000,000 次后测量 5,000,000 次：

```text
guard matched-path: 5,000,000 ops
100.2 ns/op
suppressed=5,999,997
signatures=1
```

合成 1,000,000 条相同错误时：

```text
passed=3
suppressed=999,997
reduction=99.9997%
signature_entries=1
```

它只测量内存内规则匹配、受限 ID 扫描、签名表和令牌桶，不等同于 Minecraft MSPT、磁盘日志或客户端帧时间。

## 离线命名空间扫描

`NamespacePerformanceTest` 创建 256 个独立小 region，每个包含一个目标：

```text
regions=256
targets=256
elapsedMillis=1853..4862
regionsPerSecond=53..138
hardRegressionBudgetMillis=15000
```

真实隔离副本的只读结构探针扫描 20,317 个 chunk，用时 6,331 ms。两组数据都不能替代真实大型世界、机械盘、杀毒软件或超大 NBT 的测量。

扫描器采用有界顺序流式处理，避免机械盘随机 I/O 和无界内存增长。安全流程仍需要扫描前后哈希、完整受影响文件备份、临时文件语义复读和替换后哈希，这些校验不会为追求虚高吞吐而省略。
