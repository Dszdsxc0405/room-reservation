# M1 签到 B 依赖起点：提交与修改说明

- 负责人：成员 B（用户）；总调度 agent 负责合并准备
- 模块与角色：M1 与 D；联调准备
- 对应需求：BP-02/AC-10、AC-32；本次仅合入依赖，不声称验收业务结果

## 拟提交

- 提交标题：`Merge branch 'task/m1-checkin-f-message' into task/m1-checkin-b-lifecycle`

## 修改与验证

- 主要改动：B 工作树从 A 分支建立；先合入 E 的 D 教室使用日编码接口、V2 迁移和测试，再合入 F 的 D 站内消息入队、投递接口、V3 迁移和测试。按人工决定将架构文档更新为 A/E/F 均已迁移。E 的 PostgreSQL 测试现会恢复连接的 `search_path` 和 `lock_timeout`，避免影响同一测试进程中的 M1 测试。
- 验证：A+E 合并时 `./mvnw -B -ntp verify` 通过 13 项；专用 `rr_local_test` 上 `-Pdb-integration verify` 通过 13 项普通测试、8 项 PostgreSQL 集成测试。A+E+F 待提交状态在同一专用测试库上 `-Pdb-integration verify` 通过 13 项普通测试、9 项 PostgreSQL 集成测试（F 测试所需环境变量从专用测试配置读取，未输出凭据）。`git diff --cached --check` 通过。Web 测试未执行，本次未改 Web。

## 请负责人关注

- B 仍需将 `BookingLifecycle` 的现有签到规则接到 A 的 JDBC 存储和 E 的预锁编码事实，并在有超时通知时调用 F 的消息接口；仅合入依赖尚不构成签到端到端验收。
- E 的编码生成/发布、学校时区配置来源、跨教室跨使用日不复用及缺码提示仍须需求负责人决定。F 的提醒调度和实际投递入口尚未接入。
- `platform` 的公开编码与消息接口需 M1 B、D E/F 模块负责人复核；agent 的合并结论不冒充成员确认。未合并 `main`，也未推送 B 分支。

## 人工验收

- 验收人及结论：成员 B（用户）于 2026-11-20 查看 A+E、A+E+F 两次合并的实际 diff 与测试结果后，分别明确回复“验收，允许本地提交”。该验收仅授权 B 分支的本地依赖合并提交，不代表 BP/AC 验收或 E/F 模块负责人对后续公共接口使用的复核。
