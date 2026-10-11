# hello-component —— Spring Boot 4.1.1 + MySQL + Redis + Kafka 组件串联样例

> **这个工程是什么**：在 hello-world 模板（见其 README）之上，把学习栈的 **MySQL、Redis、Kafka** 三个组件真正用起来，并完整记录了 Boot 4 + 容器环境下的五个踩坑实录（见 §六）。
>
> **前置条件**：study-stack 在运行（**MySQL 是硬依赖，缺失则应用无法启动**），且 Kafka 为 2026-10-11 之后的**双 listener 版** compose.yaml——详见 §二。

## 一、端点与链路

一条业务链：**POST 保存 → MySQL 落库 → 自动发 Kafka → 消费者收到 → 写 Redis → 接口回读**。

| 端点 | 干什么 | 验证什么 |
|---|---|---|
| `POST /api/messages?content=xxx` | 存 MySQL（表 `hello_message`）+ 发 Kafka topic `hello-topic` | MySQL 写 + Kafka 生产 |
| `GET /api/messages` | 查全部（新→旧） | MySQL 读 |
| `POST /api/redis/{key}?value=xxx` | 写 Redis（TTL 10 分钟） | Redis 写 |
| `GET /api/redis/{key}` | 读 Redis | Redis 读 |
| `GET /api/kafka/last` | 回读"消费者写进 Redis 的最后一条消息" | **Kafka 消费 + Kafka→Redis 链路** |
| `GET /actuator/health` | 聚合 db/redis/kafka 健康状态 | 三组件连通性 |

**Postman 集合**：`postman/hello-component.postman_collection.json`——Postman → Import → 选择该文件即可导入，6 个接口已按验证顺序编组、每个都带中文说明和状态断言（断言失败即链路有问题）。导入后按文件夹顺序执行：健康检查 → MySQL+Kafka 链路（① ② ③，①和③之间隔 2~3 秒）→ Redis（④ ⑤）。应用换端口只需改集合变量 `baseUrl`。

## 二、前置依赖：先部署 study-stack（MySQL / Redis / Kafka）

**部署顺序是硬性的：先依赖，后应用。** 三个组件的依赖强度不同：

| 组件 | 缺失时的后果 | 依赖强度 |
|---|---|---|
| MySQL | **应用直接启动失败**（JPA 初始化要连库 + 自动建表 `hello_message`） | 硬依赖，必须先起 |
| Redis / Kafka | 应用能启动，但对应端点不可用、Kafka 消费者反复重连 | 软依赖，强烈建议先起 |

**依赖从哪来**：三个组件都在学习栈 study-stack 里（compose 定义：`D:\code\workspace\docker-stacks\study-stack\compose.yaml`；凭证一览见操作手册 §3.0）。

**一键启动**（推荐，自动开 Docker Desktop、等引擎、拉栈、显示状态）：

```bash
bash /d/code/workspace/docker-stacks/startup-all.sh
```

**手动启动**（等价分解）：

```bash
# ① 打开 Docker Desktop，等托盘鲸鱼变绿
# ② 拉起栈
cd /d/code/workspace/docker-stacks/study-stack
docker compose up -d
# ③ 等三个服务全部 (healthy)——MySQL/Kafka 首次自检约 30 秒
docker compose ps
```

**就绪判定**：`docker compose ps` 里 mysql / redis / kafka 三行全部 `(healthy)` 才算就绪。可选的连通性抽查：

```bash
docker exec redis redis-cli -a "ffb4c9be878cde5c0af6b7b3" ping                     # PONG
docker exec mysql mysql -uroot -p"7c0e0059c35a9391232befb0" -e "SELECT VERSION();" # 8.4.x
```

**全程部署顺序总览**：

```
① Docker Desktop（引擎就绪）
   → ② study-stack：MySQL / Redis / Kafka 全部 (healthy)
      → ③ mvn package + docker build（hello-component）
         → ④ docker run 注入三个组件地址（见 §五）
```

> **Kafka 特别说明**：容器里的应用 bootstrap 必须用 `host.docker.internal:29092`，这要求 study-stack 的 compose.yaml 是 2026-10-11 之后的**双 listener 版**（旧版只有 9092 一个 listener，容器侧必然连不上）——就是 §六 问题 3 的坑。

## 三、数据资产清单：MySQL 表 / Kafka Topic / Redis Key

### 3.1 MySQL：study 库的 `hello_message` 表

**谁创建**：应用首次启动时由 JPA（`spring.jpa.hibernate.ddl-auto: update`）按实体自动生成；**前提是 study 库已存在**（由 study-stack 的初始化脚本创建）。表结构 DDL 存档于工程内 `db/schema.sql`。

**实体 → 列映射**：

| Java（Message.java） | 列名 | 实际类型 |
|---|---|---|
| `id`（@Id，IDENTITY） | `id` | bigint，AUTO_INCREMENT，主键 |
| `content` | `content` | varchar(255)（String 未显式标注时的默认长度） |
| `createdAt` | `created_at` | datetime(6)（LocalDateTime，微秒精度） |

**实际生成的 DDL**（2026-10-11 从运行环境 `SHOW CREATE TABLE` 导出）：

```sql
CREATE TABLE `hello_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

（字符集继承 study 库的 utf8mb4；`db/schema.sql` 是补了列 COMMENT 的规范化版，可用于手工重建。）

> **正规项目做法**：DDL 不交给 `ddl-auto: update`，改用 **Flyway/Liquibase** 版本化管理（`V1__init.sql` 起步），`ddl-auto` 设为 `validate`——应用只校验不改动结构。本工程保留 update 属学习态便利。

### 3.2 Kafka：topic `hello-topic`

**谁创建**：应用启动时由 `KafkaAdmin` 依据 `HelloComponentApplication#helloTopic()` 的 `NewTopic` Bean 创建（不存在才建，已存在则校验分区数一致）。

| 属性 | 值 | 说明 |
|---|---|---|
| 名称 | `hello-topic` | 生产者 `kafka.send("hello-topic", ...)`；消费者 `@KafkaListener(topics = "hello-topic")` |
| 分区数 | 1 | 学习栈单节点无需多分区 |
| 副本数 | 1 | 单节点集群只能 1；正规环境应 ≥3 并评审 |
| 序列化 | String / String | yml 中 producer/consumer 的 serializer 指定 |
| 消费组 | `hello-component` | `auto-offset-reset: earliest`（首次加入从最早消息开始） |

集群中的 `__consumer_offsets` 是 Kafka **内部 topic**（存消费组位移），自动生成，勿动勿删。

> **正规项目做法**：topic 的名称/分区/副本应由运维脚本或 IaC 统一声明——分区数、副本数是容量参数，需要评审，不应埋在应用代码里由启动隐式创建。

### 3.3 Redis：key 约定

| key | 写入方 | TTL | 用途 |
|---|---|---|---|
| `kafka:last` | KafkaConsumerService（消费者） | 1 天 | 最近一条消费消息，供 `/api/kafka/last` 回读验证链路 |
| 任意 key（演示） | `POST /api/redis/{key}` | 10 分钟 | 手动读写演示；正规项目应定命名规范（如 `hello-component:{业务}:{id}`） |

## 四、本地运行（IDEA 直跑）

配置默认值就是 `localhost` + 真实密码（来自 study-stack `.env`）；按 §二 确认依赖就绪后，直接启动 `HelloComponentApplication` 即可。

## 五、容器部署

> 前置：§二 的依赖已就绪（`docker compose ps` 三服务全部 `(healthy)`）。

```bash
cd /d/code/workspace/hello-component
mvn -q package -DskipTests
docker build -t hello-component:dev .
docker rm -f hello-component
docker run -d --name hello-component -p 127.0.0.1:8081:8080 \
  -e DB_HOST=host.docker.internal \
  -e REDIS_HOST=host.docker.internal \
  -e KAFKA_BOOTSTRAP=host.docker.internal:29092 \
  hello-component:dev
```

验证：

```bash
curl -s http://localhost:8081/actuator/health                 # status UP（db/redis/kafka 聚合）
curl -s -X POST "http://localhost:8081/api/messages?content=hello-chain"
sleep 3
curl -s http://localhost:8081/api/kafka/last                  # 应返回 "N:hello-chain"
docker logs hello-component | grep "收到 Kafka 消息"
```

## 六、开发过程问题实录（按踩坑顺序，5 个全部已解决）

本次从建工程到全链路跑通共踩了 5 个问题，按时间顺序记录——每个都给"现象→原因→解法"，以后遇到同类报错直接对号入座。

| # | 环节 | 问题一句话 | 一句话解法 |
|---|---|---|---|
| 1 | docker build | 卡死在拉取 docker/dockerfile:1 前端镜像 | 删 `# syntax=` 行，完全离线构建 |
| 2 | 应用启动 | KafkaTemplate bean 找不到，启动失败 | pom 补 `spring-boot-kafka` 模块（Boot 4 模块化） |
| 3 | Kafka 链路 | 生产/消费在容器内连 localhost:9092 失败 | bootstrap 改用 `host.docker.internal:29092` |
| 4 | 接口测试 | curl 传中文参数必 400 | Git Bash GBK 编码问题，测试用 ASCII |
| 5 | 栈基础设施 | Kafka 数据从未持久化，容器重建即丢 | 显式设 `KAFKA_LOG_DIRS` 指向挂载卷 |

### 问题 1：docker build 卡死——`# syntax=` 指令强制联网

- **现象**：首次 `docker build` 卡在 `#2 resolve image config for docker.io/docker/dockerfile:1`，最终 `dial tcp ... connectex: 连接尝试失败`。
- **原因**：官方模板首行 `# syntax=docker/dockerfile:1` 会让 BuildKit **每次构建都去 Docker Hub 拉 frontend 镜像**，而本机到 Docker Hub 时通时断。
- **解法**：删掉该行——本模板没用到它支持的高级语法；配合本地已有的 `eclipse-temurin:25-jre` 基础镜像，`docker build` **完全离线**。

### 问题 2：应用启动失败——KafkaTemplate bean 找不到

- **现象**：容器启动即退出，日志 `APPLICATION FAILED TO START ... Parameter 1 of constructor in ApiController required a bean of type 'org.springframework.kafka.core.KafkaTemplate' that could not be found`。
- **原因**：**Spring Boot 4 模块化改造**——`KafkaAutoConfiguration` 从老的 `spring-boot-autoconfigure` 挪进了独立模块，只加 `spring-kafka` 不再触发自动配置。
- **解法**：pom 显式加 `org.springframework.boot:spring-boot-kafka`（版本由 parent 管理）。
- **排障入口**：`docker logs <容器> | grep -A 12 "APPLICATION FAILED"`——Boot 的 FailureAnalysis 会直接告诉你缺哪个 bean。

### 问题 3：Kafka 生产/消费失败——容器内连 localhost:9092（本工程最典型）

- **现象**：MySQL 保存成功但 `/api/kafka/last` 永远是"(还没消费到任何消息)"；日志反复刷 `Connection to node 1 (localhost/127.0.0.1:9092) could not be established`。
- **原因**：Kafka 客户端 bootstrap 后**按端口号匹配** metadata 里的广播地址——bootstrap 用 9092 就选中了宿主机侧的 `PLAINTEXT://localhost:9092`，然后在容器内连自己。旁证：KafkaAdmin 自动建 topic 成功（复用 bootstrap 连接），而生产/消费必须按广播地址重新解析分区 leader，所以失败。
- **解法**：study-stack 的 Kafka 配**双 listener**（9092=宿主机侧 `localhost:9092`；29092=容器侧，广播 `host.docker.internal:29092`），容器里的应用 **bootstrap 用 `host.docker.internal:29092`**。
- **原理**：`D:\ai\mq` Kafka 笔记的 listener 机制部分。

### 问题 4：curl 传中文参数必 400

- **现象**：`POST /api/messages?content=你好` 返回 400（先 Tomcat HTML 400，URL 编码后变 Spring JSON 400）；应用日志里 `org.apache.tomcat.util.buf.ByteChunk.toString` 解码异常。
- **原因**：**Git Bash 控制台按 GBK 把命令行里的中文传给 curl**，服务端按 UTF-8 解码失败——工程与服务端都没问题，纯属测试工具链编码问题（ASCII 参数同一接口秒过）。
- **解法**：测试参数用 ASCII；或把中文写进 UTF-8 文件用 `curl --data-urlencode content@file.txt`；或用浏览器/Postman。

### 问题 5：Kafka 数据从未持久化——重建容器即丢（隐藏最深的坑）

- **现象**：重建 kafka 容器后旧 topic 消失；深查发现挂载的 `/var/lib/kafka/data` 卷是**空的**（`ls` 只剩 `.` 和 `..`）。
- **排查**：`docker exec kafka find / -name meta.properties` → 真实数据在 **`/tmp/kafka-logs`**——apache/kafka 官方镜像默认 `log.dirs` 在 `/tmp`（易失！），compose 里挂的卷根本没被用上，此前所有 Kafka 数据都是"假持久化"。
- **解法**：compose 的 kafka 环境变量显式加 `KAFKA_LOG_DIRS: /var/lib/kafka/data`，重建容器。
- **验证**：应用重启自动建 topic → `docker restart kafka` → topic 依然在 ✓，随后全链路复测通过。

## 七、配置速查（环境变量 → 默认值）

| 环境变量 | 默认值 | 什么时候注入 |
|---|---|---|
| `DB_HOST` / `DB_USER` / `DB_PASSWORD` | localhost / root / `.env` 里的密码 | 容器里 `DB_HOST=host.docker.internal` |
| `REDIS_HOST` / `REDIS_PASSWORD` | localhost / `.env` 里的密码 | 容器里 `REDIS_HOST=host.docker.internal` |
| `KAFKA_BOOTSTRAP` | localhost:9092 | 容器里 `host.docker.internal:29092` |
