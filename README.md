# 自习室预约系统

本项目开发座位预约、签到、整室申请审批、教室与座位报修，以及教室管理和站内通知。业务需求已形成 V1；容器中已有开发工具、本地 PostgreSQL 开发/测试库、Vue Web 和一个 Spring Boot 后端的最小骨架。业务功能、自动测试、CI 和部署尚未实现。环境准备见[容器开发环境搭建记录](docs/task-reports/2026-11-11-容器开发环境搭建.md)，骨架搭建见[应用脚手架记录](docs/task-reports/2026-11-12-应用脚手架.md)。

## 系统组成

| 模块 | 负责的业务事实 |
|---|---|
| M1 | 个人座位预约、签到、违约、座位时段限制与最终可约性 |
| M2 | 整室申请和两级审批 |
| M3 | 教室与座位报修、维修和验收 |
| D（公共层） | 教室与座位主数据、配置、权限、站内消息和运营统计 |

骨架使用 Vue 3、Vite、TypeScript、Java 21、Spring Boot 和 PostgreSQL。M1/M2/M3/D 是单个后端内部的业务模块，通过公开的 Java 接口协作；它们不需要各自启动进程。选型依据及尚待实测的容量见[技术方案](docs/13_技术方案_V1.md)。六人责任分配见[分工与数据归属](docs/12_六人分工与数据归属_V1.md)；公共层 D 是模块名，与成员代号 D 不同。

## 本地启动

需要 Java 21、Node.js（满足 `web/package.json` 的 `engines`）和 PostgreSQL。先准备仅本机可读的数据库配置：参照[配置示例](config/application-db.example.properties)在仓库外建立 `application-db.properties`，填入实际开发库连接信息。不要把真实密码提交到仓库。

```sh
./mvnw -B -ntp verify
cd web && npm ci && npm run type-check && npm run lint && npm run build
```

在仓库根目录启动后端；将示例配置路径替换为本机绝对路径。后端默认只监听本机的 18080 端口；其他机器可用 `--server.port=<端口>` 覆盖。另开终端在 `web/` 运行 `npm run dev -- --host 127.0.0.1`，打开终端打印的地址。

```sh
java -jar target/room-reservation-0.1.0-SNAPSHOT.jar --spring.config.additional-location=file:/absolute/path/application-db.properties
```

| 后端 | 本机端口 | 当前可用接口 |
|---|---:|---|
| 单个 Spring Boot 应用 | 18080 | `/actuator/health` |

Web 当前只有占位首页，尚未接入后端；健康接口只证明后端可启动，不能代表业务功能或模块协作已实现。更多运行现状见[运行总览](docs/operations.md)。

## 阅读入口

1. 从[文档索引](docs/README.md)进入[需求基线 V1](docs/07_需求基线_V1.md)、[业务决策记录](docs/08_业务决策记录_V1.md)及对应 BP/AC。
2. 开发前阅读[共享领域词典](docs/domain.md)、所属模块的 `docs/rules/` 文件，以及[跨模块接口](docs/14_跨模块接口_V1.md)。
3. 协作时遵守[所有 agent 共用规则](AGENTS.md)和[贡献流程](CONTRIBUTING.md)；具体任务再指定 `docs/agent-roles/` 中的角色说明。进度、质量、部署与伦理记录从[文档索引](docs/README.md)进入。

需求变化先由需求负责人确定业务结果，再同步决策记录、BP/AC、模块规则与基线版本。后续阶段见[需求分析完成后的步骤](docs/15_需求分析后续步骤.md)。
