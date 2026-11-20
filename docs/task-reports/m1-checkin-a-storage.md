# M1 签到依赖 A：提交与修改说明

- 负责人：成员 A；本改动由 implementer agent 编写
- 模块与角色：M1；实现
- 对应需求：BP-01 AC-24/25，BP-02 AC-09/10/30/33 的存储与并发基础

## 拟提交

- 提交标题：feat：接入 M1 预约存储与数据库锁

## 修改与验证

- `V1__m1_booking.sql` 增加 M1 预约、唯一违约和教室/学生/座位协调锁表；有效预约按 `(room_id, seat_id)` 和学生分别防重叠，预约身份与时段不可原位修改。Flyway 依赖只改 `pom.xml`。
- `BookingCoordinationLocks` 和 `JdbcBookingLifecycleStore` 实现同事务教室协调锁、D 编码事实预锁 hook、预约行锁及签到写入；超时取消与违约同事务，D 消息通过必需的 `NoShowNoticeWriter` adapter seam 入队。未改 B 的 `BookingLifecycle.java`。
- `./mvnw -B -ntp verify`：10 项通过。空 PostgreSQL 专用库执行 `./mvnw -B -ntp verify -Pdb-integration`（指定 `rr_m1_test` JDBC URL 和本地测试凭据）：Flyway V1 和 5 项集成测试通过；随后增加真实行锁竞态并聚焦复测，6 项 `BookingStorageIT` 通过。集成测试含约束、房间与预约行锁等待、签到/超时竞争、入队失败回滚及句柄失效；每例随机 ID 并精准清理自身预约、违约与协调锁行。

## 请负责人关注

- **尚未完成 B/E 接线**：B 现有 verifier 仍在预约行锁内调用。需 B 复核并改为 `LockedBooking.matchesDailyRoomCode(code)`，仅比较 A 在锁行前经 E `DailyRoomCodeFacts.lockForBookingUseDate(roomId, start)` 取得的句柄。缺码异常由 E 延迟到 `matches`，保留 B 原有结果顺序。当前 A adapter 的预锁 hook 已就位，但不能声称编码联调完成。
- **尚未完成 F 接线**：`NoShowNoticeWriter` 需适配 F 的 `InAppNoticePort.enqueue(NoticeEvent)`，同一事务保存、提交后投递；不能用空实现上线。通知入队失败应回滚超时处置，投递失败由 D 重试。提醒、超时扫描调度、对外 HTTP、真实 D 编码发布与跨模块来源取消未验证。
- `BookingCoordinationLocks` 是共享锁协议，需 M1 B、M2 C、M3 D、D E 复核顺序；M1 表和 Flyway 迁移需 schema 评审。`docs/requirements.md` 应在联调与人工验收后更新，当前未代填通过。

## 人工验收

- 验收人及结论：成员 A（任务发起用户，2026-11-20）已查看 A 的 diff 与报告，确认预约存储字段及约束、锁顺序，以及给 B 的预锁编码比对接线点，并授权提交本 A 分支改动。此确认不代表 B 的签到逻辑或 E/F 接口已完成联调，也不替代 B/E/F 人工确认、schema 评审及合并请求评审。

## 包边界合入补充（已人工验收）

- 合入 `task/backend-package-boundaries` 后，A 的锁和存储适配器移至 `seatbooking.internal.persistence`；与 D 对接的三个内部函数接口独立成文件，供 M1 应用层测试与后续装配使用。`BookingStorageIT` 位于 `seatbooking.internal.application`，保留原有签到与并发验证；没有更改 B 的签到规则或 A 的 SQL/锁顺序。
- 在 A 工作树执行 `./mvnw -B -ntp verify`：10 项通过。执行 `./mvnw -B -ntp -Pdb-integration -Dspring.config.additional-location=file:/opt/room-reservation/secrets/application-rr-local-test.properties -Dtest.database.name=rr_local_test verify`：普通测试 10 项、PostgreSQL 集成测试 7 项通过。该测试库已在 Flyway V3；本轮验证了已有迁移的校验及现存表上的行为，未重新执行空库迁移。
- 成员 A（本轮对话，2026-11-20）查看本次实际 diff 与验证结果后，明确回复“验收，完成本地合并提交”。此确认仅覆盖 A 分支包迁移和本地合并，不代替 B/E/F 接口复核、非作者评审或 BP/AC 验收。
