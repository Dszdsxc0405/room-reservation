# M1 签到依赖 F：D 站内消息接口与最小存储

- 负责人：成员 F（AI 实现，成员 F 已确认本任务范围）
- 模块与角色：D；implementer
- 对应需求：BP-02 AC-07～09、AC-33；跨模块契约 16 第 4 节通知与审计

## 拟提交

- 提交标题：feat：增加 D 站内消息事务入队与投递记录

## 修改与验证

- D 提供 `InAppNoticePort.enqueue(NoticeEvent)`，要求加入调用方事务；六元事件键唯一，同键同内容返回原 ID，内容冲突报错。`triggeredAt` 是执行时点，重试可能变化，故不纳入固定意图冲突判断。D 独占 V3 消息、收件箱和尝试记录表。
- D 提供 `JdbcInAppNoticeDispatcher.deliverNext()`：独立事务只读取已提交的待投递行，成功写入站内收件箱；失败记录尝试及一分钟后重试，原业务事务不受影响。调用方须在提交后由 D 工作器执行；工作器调度、站内消息读取 API 和 M1/B 提醒触发尚未接入。
- 验证：`./mvnw -B -ntp verify` 通过（10 项骨架及 M1 测试）；专用 `rr_f_test` PostgreSQL 上执行 `F_NOTICE_TEST_JDBC_URL=jdbc:postgresql://localhost:55439/rr_f_test F_NOTICE_TEST_DB_USER=postgres ./mvnw -B -ntp -Pdb-integration -Dit.test=InAppNoticePostgresIT test-compile failsafe:integration-test failsafe:verify` 通过（1 项）。该测试使用随机独立 schema，仅删除本次 schema；实际 Spring 代理下验证 `MANDATORY` 拒绝无事务、`REQUIRES_NEW` 不见原事务未提交事件，以及模拟部分写入失败时 `NESTED` 保存点回滚、失败记录和重试。另覆盖业务事务回滚、同键去重与内容冲突。
- 未完成及未验证：M1/B 的定时提醒与超时触发接线、D 自动投递调度、学生站内消息入口、生产 `JdbcInAppInboxSink` 在完整应用容器中的装配及 Web 可见性；BP-02 AC-07～09/33 的端到端验收未执行。

## 请负责人关注

- B 与 F 复核事件类型、提醒和超时的固定 `businessOperationId`/`stepId` 派生、内容与收件人；消息内容模板和可见范围未由本任务决定。M1 同事务取消、违约和入队的调用点仍由 A/B 联调。
- 公共端口与 D schema 影响 M1/M2/M3 的通知调用；须 M1（A/B）与 D（F）负责人确认。迁移 V3 需要与 A/E 的迁移顺序及 Flyway 接入联调；本分支基线尚未配置 Flyway。

## 人工验收

- 验收人及结论：成员 F（用户），2026-11-20，确认本任务范围内三项：① 超时取消、违约及待发送消息同事务，入队保存失败回滚；业务提交后的投递失败只记录重试，不撤销处置；② 同一业务事件重试去重，同键不同内容报冲突；③ 本次交付 D 消息记录、去重与投递基础，定时提醒、自动投递及学生入口后续接线。以上范围认可并准予提交；未完成项不视为 BP/AC 验收通过。
