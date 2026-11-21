# 需求与验收追踪

> 本文只追踪实现与验证，不重写业务规则。业务结果以[需求基线 V1](business/07_需求基线_V1.md)、[业务决策记录](business/08_业务决策记录_V1.md)及各 BP/AC 为准；[领域词典](domain.md)和[模块规则](README.md)帮助开发者理解术语与边界。

BP-02 的 B 负责规则已有模块测试；提醒记录、本人违约查询尚未接入持久化，定时入口及跨模块联调也未完成，因此仍为“开发中”。其他 AC 尚未开发。后续填入可复查的测试或人工验收证据；未完成条目保留在表中。

| 流程与 AC | 实现 | 验证证据 | 状态 |
|---|---|---|---|
| [BP-01](business/00_BP-01_查询并预约_V1.md)：AC-01～06、24～28、35 | — | — | 未开发 |
| [BP-02](business/01_BP-02_签到提醒与未签到处置_V1.md)：AC-07/08 | [B 提醒触发规则](../src/main/java/org/roomreservation/seatbooking/internal/application/BookingReminders.java)；A 存储与 F 消息记录、定时入口待接入 | [M1 模块测试](../src/test/java/org/roomreservation/seatbooking/internal/application/BookingLifecycleTest.java)：时点、重复、取消/已签到、投递失败；`./mvnw -B -ntp verify`（2026-11-21，17 项通过） | 开发中 |
| [BP-02](business/01_BP-02_签到提醒与未签到处置_V1.md)：AC-09/10/30/32/33 | [签到与超时核心](../src/main/java/org/roomreservation/seatbooking/internal/application/BookingLifecycle.java)；A 现有存储适配与 D 接口、定时入口待联调 | [M1 模块测试](../src/test/java/org/roomreservation/seatbooking/internal/application/BookingLifecycleTest.java)：边界秒、锁后受理、跨日编码、延迟超时、竞争及通知失败；`./mvnw -B -ntp verify`（2026-11-21，17 项通过） | 开发中 |
| [BP-02](business/01_BP-02_签到提醒与未签到处置_V1.md)：AC-31 | B 超时取消后个人占用释放；实际再次预约属于后续 A 接入任务 | [M1 模块测试](../src/test/java/org/roomreservation/seatbooking/internal/application/BookingLifecycleTest.java)：取消后不再持有个人占用；`./mvnw -B -ntp verify`（2026-11-21） | 开发中 |
| [BP-02](business/01_BP-02_签到提醒与未签到处置_V1.md)：AC-34 | [B 本人违约过滤规则](../src/main/java/org/roomreservation/seatbooking/internal/application/OwnNoShowViolations.java)；A 查询与登录入口待接入 | [本人可见性模块测试](../src/test/java/org/roomreservation/seatbooking/internal/application/OwnNoShowViolationsTest.java)；`./mvnw -B -ntp verify`（2026-11-21） | 开发中 |
| [BP-03](business/02_BP-03_空间与座位维护_V1.md)：AC-11～14 | — | — | 未开发 |
| [BP-05](business/04_BP-05_系统配置与权限_V1.md)：AC-15～16、36 | — | — | 未开发 |
| [BP-06](business/05_BP-06_整教室申请审批_V1.md)：AC-17～19、29 | — | — | 未开发 |
| [BP-07](business/06_BP-07_报修维修验收_V1.md)：AC-20～23 | — | — | 未开发 |

状态使用“未开发、开发中、待验收、已通过、阻塞”。一行内若各 AC 进度不同，就拆行，不把未完成内容从索引删除。实现列指向代码或接口，证据列指向测试结果、联调记录或人工验收记录；仅写“已测试”不算证据。业务结果变更先按[协作流程](../CONTRIBUTING.md)更新决策、BP/AC 和基线，再调整本索引。
