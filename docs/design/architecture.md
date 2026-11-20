# 系统架构与模块边界

> 本页是架构入口。技术选择依据在[技术方案](13_技术方案_V1.md)，数据主责在[六人分工与数据归属](12_六人分工与数据归属_V1.md)，调用时点和结果在[跨模块接口](14_跨模块接口_V1.md)；本页不复制它们的细节。当前已有可本地启动的骨架及 M1 签到状态转换核心，尚未接入数据库/D 接口、完成 BP/AC 验收或部署系统。

一个 Vue Web 应用面向学生、教职工和管理员；一个 Spring Boot 后端提供 HTTP API，M1、M2、M3、公共层 D 是其中的内部业务模块。开发、测试、生产三套环境逻辑隔离，各用独立 PostgreSQL 数据库；每套环境内模块共库，但各自修改所属业务事实。用户 RBAC 与模块数据主责分别约束用户操作和模块写入。

| 模块 | 事实主责 | 对外协作要点 |
|---|---|---|
| M1 | 座位预约、签到、违约、时段限制、最终可约性 | 接受 M2、M3、D 的来源限制请求；其他模块不能直接改预约 |
| M2 | 整室申请与审批 | 终审通过后请 M1 限制座位；教室报修或管理计划会影响申请 |
| M3 | 教室/座位报修与维修 | 提交报修时请 M1 限制座位；教室报修另请 M2 处置整室申请 |
| D | 主数据、配置、RBAC、站内消息、审计与运营统计 | 向业务模块提供权威数据；管理变更请求 M1/M2 处置；负责通知投递 |

查询结果只是当时快照；提交预约、终审、报修和管理变更时，后端须重新判断状态。内部模块经公开 Java 接口协作，事务边界与故障恢复按[接口契约](14_跨模块接口_V1.md)细化。课程要求的整室占座、报修下架及限制来源追溯均在相应 BP/AC 中验收。

## 后端代码边界

Java 根包为 `org.roomreservation`。`RoomReservationApplication` 是启动类；请求从 `web` 进入，涉及多个模块的命令由 `workflow` 编排，随后只调用各模块的公开接口。模块代码包分别为 `seatbooking`（M1）、`roomapplication`（M2）、`maintenance`（M3）和 `platform`（D）；M1/M2/M3/D 代号继续用于需求、数据归属和表名，不随 Java 包重命名。

| 模块 | 公开接口位置 | 内部实现位置 |
|---|---|---|
| M1 | `seatbooking.SeatBookingPort` | `seatbooking.internal.application`、`domain`、`persistence` |
| M2 | `roomapplication.RoomApplicationPort` | `roomapplication.internal.application`、`domain`、`persistence` |
| M3 | `maintenance.MaintenancePort` | `maintenance.internal.application`、`domain`、`persistence` |
| D | `platform.ResourceFactsPort`、`DailyRoomCodePort`、`MessagePort` | `platform.internal.resource`、`authorization`、`messaging`、`persistence` |

表中是接口落点，不表示所有接口和内部目录均已有代码。当前已迁移 M1 签到状态转换与存储适配器、D 编码锁定读取、对应测试和四个模块的包声明；具体 Port 在相应能力接入且契约复核后创建。`web` 和 `workflow` 也在有实际入口或编排代码时建立。跨模块代码只能依赖被调用模块的公开接口和公开数据类型，不能导入其 `internal` 类型或 Repository；同一模块的内部层可以相互调用。数据库表和 Flyway 脚本仍由原模块负责。

A 的 M1 存储适配器位于 `seatbooking.internal.persistence`，使用 M1 内部的存储契约；B 继续负责 `seatbooking.internal.application` 的签到逻辑；E 的 D 编码公开接口位于 `platform`，JDBC 实现位于 `platform.internal.persistence`；F 的消息实现待合入。接入时先核对公开 Port，再调整各自测试和引用。

业务接口 URL、字段、表结构、部署端口和运行拓扑尚未设计完成；本地骨架端口见[运行文档](../ops/operations.md)。这些实现细节在接口细化与环境搭建时落到代码、迁移脚本及运行文档，不在此凭空确定。
