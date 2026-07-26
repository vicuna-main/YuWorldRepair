# Changelog

## 2.1.0-rc3 short-sector Anvil compatibility

- Accept otherwise valid `.mca` files whose physical size ends immediately after the final
  declared chunk record instead of including zero padding through the allocated 4 KiB sector.
- During copy-on-write, validate and read exactly the unedited record's `length + 4` bytes, then
  zero-fill only the missing sector tail in the rewritten file.
- Continue to fail closed when the declared payload itself is truncated, the record exceeds its
  allocated sectors, or its compression/external marker is invalid.
- Reproduce the RC2 failure with a synthetic regression and verify the supplied 157,629-byte
  `r.-7.1.mca`: 36 chunks read, 35 unedited chunks semantically identical after rewrite, and the
  source SHA-256 unchanged.

## 2.1.0-rc2 large-world scoped maintenance

- Fold legacy `iceandfire-chicken-data` cleanup into the single public
  `/yuworldrepair repair orphaned-items` workflow. Recursively remove any unregistered NeoForge
  attachment while preserving registered attachments and valid outer items.
- Report deferred QIO findings separately by namespace, store, and amount so partial-region
  maintenance shows exactly which orphan Mod data was detected but intentionally not deleted.
- Separate detected, removed, and deferred totals in operator status, and mark every partial-region
  run as `cleanupComplete=false` even when its selected scope completed safely.
- Add `orphaned-items only <world,...>` and `orphaned-items except <world,...>`. Exclusion only
  skips that world's `region`/`entities` files; playerdata and supported SavedData remain active.
- Sign the exact excluded region-root set and worker count into schema-5 HMAC requests.
- Add bounded parallel region readers (1–16 workers, safe automatic default capped at 8), while
  keeping backups, mutation, atomic replacement, verification, and rollback serial.
- Hold every signed world's `session.lock` from before the first scan until all worlds finish or
  roll back, eliminating the preparation/apply race.
- Replace the two extra full rescans under the held lock with exact pre-hash/target checks and a
  post-apply scan of affected region/standalone files.
- Keep RS and playerdata scanning active for excluded worlds. Defer QIO type-cache deletion when
  any region root is excluded so unscanned QIO drives cannot retain dangling UUID references.
- Write stable request-level and per-job `scan-progress.json` reports and expose partial-scope,
  deferred-QIO, byte, and worker metrics.
- Raise large-world scan ceilings, fail explicitly rather than silently truncate excessive
  playerdata, and add deterministic parallel, exclusion, QIO, HMAC-scope, and all-world-lock tests.

## 2.1.0-rc1 maintenance handoff hardening

- Add `/yuworldrepair repair orphaned-items` to report and remove all ItemStack IDs absent from
  the signed live item registry without entering one modid at a time.
- Add verified adapters for ordinary 1.21 ItemStacks, AE2 cell inventory/config entries, Refined
  Storage 2.x item repositories, and Mekanism QIO type/drive data.
- Share the primary world's QIO type index with every signed Multiverse world and update QIO drive
  metadata when UUID/count triples are removed.
- Aggregate item findings by namespace, store, and amount in maintenance results and scan reports.
- Validate the new mode on the isolated 34-world fixture: 29 targets applied, zero remained, and
  all eight changed worlds were byte-restored by rollback.
- Add `/yuworldrepair repair iceandfire` as the one-command full namespace cleanup.
- Capture the main world and every loaded Youer/Bukkit/MV world root in the signed HMAC request.
- Keep schema 3 single-world requests readable with their original HMAC format while schema 4
  exclusively signs the new multi-world root set.
- Prepare every world before applying any replacement and roll back changed worlds in reverse on
  failure.
- Decode and encode Minecraft NBT strings as Modified UTF-8; this removes false coverage gaps in
  valid player data.
- Validate against an isolated 34-world sample: 508 targets repaired, then 34/34 worlds rescanned
  with zero targets and zero coverage gaps.
- Close expired, dead-parent `WAITING_FOR_STOP` requests without a second 1,800-second startup
  wait, using a cross-process locked and idempotent terminal result.
- Add authenticated worker readiness and machine-readable `handoff.json` lifecycle reporting.
- Embed the fail-closed, JDK-only panel supervisor in the same executable NeoForge maintenance
  JAR; `PANEL` mode requires its launcher handshake.
- Preserve HMAC/path/session-lock safety and refuse stale cleanup when any world-work evidence is
  present.
- Add stale-state, concurrent-startup, authorization, and supervised lifecycle tests.

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
