# M1 B 历史再订：提交与修改说明

- 负责人：成员 B
- 模块与角色：M1；implementer
- 对应需求：BP-01 AC-05
- 工作树与分支：`/team/xyq/room-reservation-worktrees/b-history`；`task/m1-history-rebooking-b`，从 `task/m1-checkin-b-lifecycle` 的 `7a94344` 创建

## 拟提交

- 提交标题：`feat：实现 AC-05 历史再订入口规则`

## 修改与验证

- 主要改动：B application 只返回本人历史；再订时从本人历史取原座位，携带登录学生身份与新时段调用正常新预约提交，不复用旧预约的资格或可约性结论。
- 验证：`./mvnw -B -ntp verify`（2026-11-21）：BUILD SUCCESS，22 项测试，0 失败、0 错误、0 跳过；其中新增 5 项历史再订模块测试，依赖均为 fake。
- 未执行：A 真实历史存储与新预约接口、登录入口、数据库并发、跨模块联调、Web 及 BP-01 AC-05 人工验收。

## 请负责人关注

- A 需提供历史读取：`findHistoryForStudent(studentId)` 与 `findHistoryBooking(bookingId)`，每条至少含 `bookingId/studentId/seatId/start/end`，并确认哪些状态属于“历史”；若历史筛选会影响用户可见结果，请需求负责人先决定。
- A 需提供正常新预约提交：`submit(studentId, seatId, newStart, newEnd)`，在提交时依据当前身份、时段、开放、占用和限制重验，并返回新预约 ID 或拒绝结果。现有工作树没有该入口；B 只定义内部适配契约，未修改 A 的公共接口或持久化。
- 登录入口须从服务端认证信息提供 `studentId`，不得信任请求体。A 的接口语义和锁/事务经 A 复核后，再安排接入与数据库测试；本任务的 fake 成功不代表 AC-05 已人工验收。

## 人工验收

- 验收人及结论：任务负责人；2026-11-21 明确回复“人工审查通过”。
