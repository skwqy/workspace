# AGENTS.md —— D:\code\workspace 容器化开发环境说明（供 AI 与开发者）

> **本文件定位**：上级 `D:\ai\AGENTS.md` 的目录规范（新项目放本目录、临时文件放 `D:\tmp` 等）继续有效；本文件补充**容器化开发环境**的全部事实、约定与坑。这里的所有环境信息均为 2026-10-11 实测结果。

## 1. 环境事实

| 项 | 值 |
|---|---|
| 宿主机 | Windows 11 专业版 build 26300，虚拟化已启用（HypervisorPresent=True） |
| WSL | 2.7.13（内核 6.18.33.2-2）；资源上限在 `C:\Users\Feng\.wslconfig`：memory=8GB / processors=8 / swap=4GB / 空闲还内存（autoMemoryReclaim）/ 稀疏磁盘（sparseVhd） |
| 容器运行时 | **Docker Desktop 4.94.0（WSL2 引擎）**，`docker` CLI 已在 PATH；**不开机自启**（AutoStart=false）+ Resource Saver 空闲自动挂起——"用时手动启动，用完归零" |
| Shell | AI 命令环境为 Git Bash（路径 `/d/code/...` 风格）；PowerShell/cmd 差异见操作手册 |
| 宿主工具链 | JDK 25.0.1、Maven 3.9.16（已配阿里云镜像） |
| 使用模式 | 用户**大部分时间不跑容器**——开工先起环境，收工执行归零脚本 |

**版本约定（新项目必须遵守）**：Java 后端项目统一 **JDK 25**（pom 里 `java.version=25`，容器基座配套 `eclipse-temurin:25-jre`）+ **Spring Boot 4.1.1**（parent 的 `<version>` 写死 `4.1.1`，依赖已经阿里云镜像实测可正常解析）。§4 的 hello-world / hello-component 即此版本的落地模板。

## 2. 学习栈 study-stack（本目录下的依赖中心）

compose 定义：`D:\code\workspace\docker-stacks\study-stack\compose.yaml`；**凭证在同目录 `.env`**（不进 git；`.env.example` 是模板；完整凭证表见操作手册 §3.0）。

| 组件 | 版本 | 宿主机地址 | 认证 | 备注 |
|---|---|---|---|---|
| MySQL | 8.4 LTS | `localhost:3306`（只绑 127.0.0.1） | root + `.env` 密码 | 库 `study` 已初始化 |
| Redis | 8.2 | `localhost:6379` | 仅密码（`.env`） | 无用户名概念 |
| Kafka | 4.0（KRaft） | 宿主机应用 `localhost:9092`；**容器内应用 `host.docker.internal:29092`** | 无（PLAINTEXT） | **双 listener**，见 §5 坑 3 |

## 3. 一键脚本（`D:\code\workspace\docker-stacks\` 下）

| 脚本 | 作用 | 执行方式 |
|---|---|---|
| `startup-all.sh` | 启动 Docker Desktop → 等引擎就绪 → 拉起所有 compose 栈 → 显示状态 | Git Bash：`bash /d/code/workspace/docker-stacks/startup-all.sh`；或双击同名 `.cmd` |
| `shutdown-all.sh` | **归零**：停所有栈 → 退 Docker Desktop → `wsl --shutdown` → 逐项验证 | 同上（`shutdown-all.cmd`） |
| `backup.sh` | 物理备份三个数据卷 + mysqldump 全库，产物落 `D:\code\backup` | 同上（`backup.cmd`） |

cmd/PowerShell 跑不了 `.sh`——用 `.cmd` 双击版或 `bash <脚本>`；脚本会自动扫描 `docker-stacks/*/compose.yaml`，新增栈无需改脚本。

## 4. 样例工程（新项目的复制模板）

| 工程 | 内容 | 给新项目的价值 |
|---|---|---|
| `hello-world\` | Boot 4.1.1 纯 Web：Dockerfile 模板 A（多阶段+分层）、逐行对照表、全流程 README | **纯 Web 项目从它复制**；Dockerfile 写法以它为准 |
| `hello-component\` | Boot 4.1.1 + MySQL/Redis/Kafka 串联：JPA、双 listener、数据资产清单（表 DDL/Topic/Redis Key 见其 README §三 与 `db/schema.sql`）、Postman 集合（`postman\*.json`） | **要连中间件的项目从它复制** |

新项目套路：复制模板 → 改 groupId/artifactId/包路径 → 接口配 Postman 集合 → 数据资产（表 DDL/Topic/Key）写进 README。

## 5. 必须知道的坑（完整版在操作手册 §7，此处是浓缩版）

1. **Dockerfile 不要写 `# syntax=docker/dockerfile:1`**——会强制联网拉 frontend 镜像，国内网络常超时；删掉可完全离线构建。
2. **Boot 4 的 Kafka 自动配置在独立模块**——只加 `spring-kafka` 会报 `KafkaTemplate` bean 缺失，pom 需加 `org.springframework.boot:spring-boot-kafka`。
3. **容器内应用连 Kafka 的 bootstrap 必须用 `host.docker.internal:29092`**——客户端按端口匹配广播地址，用 9092 会选中 `localhost:9092` 然后在容器内连自己。
4. **apache/kafka 官方镜像默认 log.dirs 在 `/tmp`（易失）**——study-stack 已显式设 `KAFKA_LOG_DIRS: /var/lib/kafka/data`，改 compose 时勿删此行，否则容器重建即丢数据。
5. **端口映射一律 `127.0.0.1:` 前缀**；镜像 tag 禁用 `:latest`（用 git sha 或语义版本）；密码只走 `.env`（不入库、不进 Dockerfile——镜像层可被 `docker history` 翻出）。
6. **Git Bash 传中文参数给 curl 会 GBK 400**（Tomcat 按 UTF-8 解码失败）——接口测试用 ASCII、Postman 集合或浏览器。
7. **MySQL root 密码仅在数据卷首次初始化时生效**——之后改密码要 `ALTER USER ... IDENTIFIED BY`，且必须同步改 `.env`（compose 健康检查用它）。
8. **高危命令**：`docker compose down -v`、`docker system prune -a --volumes` 删数据——未经用户确认不得执行；日常停栈只用 `docker compose stop`。
9. 应用容器连宿主机上的服务用 `host.docker.internal`（容器里的 localhost 是它自己）。
10. 休眠唤醒后容器网络异常/命令卡死 → `wsl --shutdown` 后重开 Docker Desktop。

## 6. 参考文档（按需查阅，均在 `D:\ai\tools\`）

| 文档 | 用途 |
|---|---|
| `容器开发操作指导手册.md` | **日常操作手册**：首次启动、每日启停、凭证一览（§3.0）、报错速诊表（§7）、命令速查（§8）、Postman 用法 |
| `容器化开发建议.md` | 规范与原理：17 个动作（环境/镜像/编排/数据/JVM/安全/K8s）、选型对比 |
| `DockerDesktop.md` | Docker Desktop 软件本身：界面导览、设置详解、维护更新、故障恢复阶梯 |
| `D:\code\workspace\hello-world\README.md` | 全流程样例（含 Dockerfile 逐行对照表） |
| `D:\code\workspace\hello-component\README.md` | 三组件串联样例（含数据资产清单与 5 个踩坑实录） |

## 7. 给 AI 的协作约定

- **版本约定**：新建 Spring Boot 项目时 parent 用 **4.1.1**、`java.version` 用 **25**（见 §1 版本约定），容器基座 `eclipse-temurin:25-jre`
- **先依赖后应用**：部署任何连库应用前，先 `startup-all.sh` 并确认 `docker compose ps` 三服务全部 `(healthy)`。
- **改代码部署循环**：`mvn -q package -DskipTests` → `docker build -t <名>:dev .` → `docker rm -f <名>` → `docker run -d --name <名> -p 127.0.0.1:<宿主>:8080 <名>:dev`（连中间件记得注入 `host.docker.internal` 相关环境变量，见样例工程 README）。
- **验证优先级**：actuator/health → Postman 集合 → curl（避开中文参数）。
- **收工归零**：用户明确说"不用了/停一下"时执行 `shutdown-all.sh`；数据安全靠卷 + `backup.sh`，不靠容器。
- **改 study-stack 的 compose 后**必须 `docker compose up -d` 重建容器并确认 `(healthy)`，涉及 Kafka 时检查数据目录仍在挂载卷（见 §5 坑 4）。
