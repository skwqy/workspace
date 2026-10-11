# hello-world —— Spring Boot 4.1.1 容器化样例工程（开发 → Dockerfile → 打包 → 部署全流程）

> **这个工程是什么**：一份可运行的**样例模板**。给新项目套用时，复制本目录 → 改三个标识符（见 §9 清单）→ 换业务代码，其余流程原样可用。
>
> **配套文档**：容器环境怎么装、学习栈（MySQL/Redis/Kafka）怎么起、报错怎么查，见 `D:\ai\tools\容器开发操作指导手册.md`；规范与原理见 `D:\ai\tools\容器化开发建议.md`。本 README 只讲"这一个工程"从代码到容器的完整流程。
>
> **实测记录（2026-10-11）**：jar 21MB → 镜像 527MB → 应用启动 1.36s → HTTP 访问 200，`docker build` 完全离线。

---

## 1. 技术栈与前置条件

| 项 | 值 | 检查命令 |
|---|---|---|
| Spring Boot | **4.1.1**（parent 版本决定 Dockerfile 模板，见 §5.1） | 看 `pom.xml` |
| Java | 25（宿主机 JDK 25.0.1，容器基座 `eclipse-temurin:25-jre`） | `java -version` |
| 构建 | Maven 3.9.x（已配阿里云镜像） | `mvn -version` |
| 运行 | Docker Desktop（WSL2 引擎，托盘鲸鱼变绿） | `docker version` 出 Client+Server 两段 |

本样例**不依赖数据库/Redis**；有中间件依赖的项目看 §7。

## 2. 工程结构

```
hello-world\
├── pom.xml                                   # 依赖与构建定义
├── Dockerfile                                # 多阶段 + 分层（§5.1，逐行讲）
├── .dockerignore                             # 构建上下文瘦身（§5.3）
├── README.md                                 # 本文件
└── src\main\
    ├── java\com\example\helloworld\
    │   └── HelloWorldApplication.java        # 启动类 + HTTP 端点
    └── resources\
        └── application.yml                   # 优雅停机 + actuator 配置
```

## 3. 开发阶段

### 3.1 pom.xml 关键点

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>          <!-- ≥3.3 用本 README 的 Dockerfile；≤3.2 见 §5.1 末尾 -->
    <relativePath/>
</parent>

<properties>
    <java.version>25</java.version>   <!-- 与容器基座大版本一致 -->
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>   <!-- /actuator/health 给容器探活用 -->
    </dependency>
</dependencies>
```

### 3.2 启动类 + HTTP 端点

```java
package com.example.helloworld;

import java.net.InetAddress;
import java.time.LocalDateTime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class HelloWorldApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelloWorldApplication.class, args);
    }

    @GetMapping("/")
    public String hello() throws Exception {
        // hostname 能看出代码跑在哪个容器里（容器内是随机容器 ID）
        String host = InetAddress.getLocalHost().getHostName();
        return "Hello, World!  (Spring Boot 4.1.1 | host=" + host + " | " + LocalDateTime.now() + ")";
    }
}
```

### 3.3 application.yml

```yaml
spring:
  application:
    name: hello-world
  lifecycle:
    timeout-per-shutdown-phase: 10s   # 优雅停机：给收尾工作 10 秒

server:
  shutdown: graceful                  # 收到 SIGTERM 先处理完存量请求

management:
  endpoints:
    web:
      exposure:
        include: health               # 暴露 /actuator/health
```

## 4. Dockerfile 与 .dockerignore（核心）

### 4.1 Dockerfile 全文（Boot 3.3+/4.x，含分层、非 root、优雅停机）

```dockerfile
# ---- 阶段一：把 fat jar 解压成"依赖层 + 代码层" ----
FROM eclipse-temurin:25-jre AS builder
WORKDIR /builder
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- 阶段二：运行镜像 ----
FROM eclipse-temurin:25-jre
RUN useradd --system --create-home app
USER app                                # 非 root 运行
WORKDIR /application
# 四个 COPY 各成一层：依赖在前、代码在后——改代码只重建最后一层
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./
ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
# exec 形式（JSON 数组）：docker stop 时 SIGTERM 直达 java，优雅停机的前提
ENTRYPOINT ["java", "-jar", "application.jar"]
```

**设计意图**：①多阶段——最终镜像只含 JRE 和应用；②分层 COPY——依赖层稳定放前、代码层在后，改一行代码只重建最后一层，构建与拉取都从全量变增量；③`MaxRAMPercentage=75.0`——堆按容器限额的 75% 自适应，不用固定 `-Xmx`；④exec 形式 ENTRYPOINT——配合 yml 里的 `graceful shutdown`，`docker stop` 时日志能看到优雅收尾。

**逐行对照表**（心智模型：Dockerfile 是一份"自动装机脚本"，每条指令都在镜像里盖一层可缓存的"存档"；两个 FROM = 脏车间干活、成品车间交付）：

| 指令 | 作用 |
|---|---|
| **—— 阶段一（builder，用完即弃的临时车间）——** | |
| `FROM eclipse-temurin:25-jre AS builder` | 基于预装 JRE 25 的官方镜像开始；`AS builder` 给阶段起名，供阶段二引用 |
| `WORKDIR /builder` | 设定后续指令的工作目录（相当于 cd，目录不存在会自动创建） |
| `ARG JAR_FILE=target/*.jar` | 构建期变量（容器运行时不存在），可用 `--build-arg` 覆盖；`*` 通配符兼容带版本号的 jar 名 |
| `COPY ${JAR_FILE} application.jar` | 把宿主机构建上下文里的 jar 复制进镜像，顺手改成固定名 application.jar |
| `RUN java -Djarmode=tools ... extract ...` | 执行 Boot 自带的解压：fat jar 拆成 4 个目录（依赖 / 启动器 / 快照依赖 / 应用代码），为分层做准备 |
| **—— 阶段二（最终镜像从这里开始算）——** | |
| `FROM eclipse-temurin:25-jre`（第二个 FROM） | 开启全新阶段：阶段一的一切全部丢弃——这就是最终镜像没有源码和中间产物的原因 |
| `RUN useradd --system --create-home app` | 创建 app 系统账号：不以 root 跑进程是安全底线 |
| `USER app` | 从此行起，后续指令与容器内进程都以 app 身份运行 |
| `WORKDIR /application` | 运行阶段的工作目录（应用在容器里的"家"） |
| `COPY --from=builder ... ./`（4 条） | **不从宿主机复制，从阶段一搬文件**；4 条各成一层，按变动频率排序=缓存策略：改代码只重建最后的 application 层 |
| `ENV TZ=... JAVA_TOOL_OPTIONS="..."` | 运行时环境变量：时区差 8 小时的解药；JVM 参数（堆=限额×75% 容器感知、OOM 即退出、UTF-8 防乱码），`docker run -e` 可覆盖 |
| `ENTRYPOINT ["java", "-jar", ...]` | 容器启动的主命令；必须用 JSON 数组（exec 形式）——SIGTERM 直达 JVM 触发优雅停机，写成 shell 形式信号会被 sh 吃掉 |

**Boot ≤3.2 的差异**：解压命令换 `java -Djarmode=layertools -jar application.jar extract`（产出在 `/builder/application/` 下），四个 COPY 路径相应改为 `/builder/application/dependencies/` 等，ENTRYPOINT 换 `["java", "org.springframework.boot.loader.JarLauncher"]`。**多模块项目**：`ARG JAR_FILE=模块名/target/*.jar`。

### 4.2 ⚠️ 不要写 `# syntax=docker/dockerfile:1`

官方示例首行有这个指令，但它会让 BuildKit **每次构建都去 Docker Hub 拉 frontend 镜像**，国内网络经常卡死在这里超时（本工程实测踩过）。本模板没用到它支持的高级语法，删掉后配合本地已有的基础镜像，`docker build` **完全离线**。

### 4.3 .dockerignore

```
.git
.idea/
*.iml
HELP.md
logs/
target/classes/
target/test-classes/
target/maven-status/
.env
```

（注意没有整体排除 `target/`——构建阶段要从 `target/*.jar` COPY 产物。）

## 5. 打包与部署（五步）

```bash
cd /d/code/workspace/hello-world
mvn -q package -DskipTests                       # ① 宿主机打包出 target/*.jar（构建阶段要 COPY 它）
docker build -t hello-world:dev .                # ② 构建镜像（基础镜像本地已有，离线秒级）
docker run -d --name hello-world -p 127.0.0.1:8080:8080 hello-world:dev   # ③ 后台启动
docker logs -f hello-world                       # ④ 看日志：出现 "Started HelloWorldApplication in x seconds" 即成功
curl http://localhost:8080/                      # ⑤ 验证（/actuator/health 应返回 {"status":"UP"}）
```

命令口诀：**build=打包成安装包，run=安装并启动，`-d`=后台，`-p 本机端口:容器端口`，`--name`=起名**。

> **在哪个窗口执行**：本 README 命令以 **Git Bash** 为准（第一行 `cd /d/...` 是 Git Bash 路径风格；开始菜单搜 `Git Bash` 打开）。用 **PowerShell / cmd** 也行——`mvn`、`docker`、`curl` 都是全局命令，只有 `cd` 要换写法：PowerShell 用 `cd D:\code\workspace\hello-world`，cmd 用 `cd /d D:\code\workspace\hello-world`。两个小坑：①Windows 自带 PowerShell 5.1 里 `curl` 是 `Invoke-WebRequest` 的别名（输出格式不同），验证 HTTP 最直观的方式是**浏览器打开 http://localhost:8080**；②`docker logs -f` 会持续滚动日志，`Ctrl+C` 只退出查看、不会停容器。

**验证分层生效**：改一行代码重新 `docker build`，日志里只有 `application` 那层在重建，`dependencies` 层显示 CACHED。

## 6. 连接中间件（有 DB/Redis 依赖的项目）

关键认知：**容器里的 `localhost` 是容器自己**。把地址做成环境变量、默认 localhost——IDEA 和容器两种跑法代码零改动：

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:3306/study?serverTimezone=Asia/Shanghai
    username: root
    password: ${DB_PASSWORD:你的密码}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      password: ${REDIS_PASSWORD:你的密码}
```

容器跑的时候注入：

```bash
docker run -d --name hello-world -p 127.0.0.1:8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e REDIS_HOST=host.docker.internal \
  hello-world:dev
```

> Kafka：学习栈的广播地址按宿主机直连设计，容器里同样用 `host.docker.internal:9092`；遇 advertised listener 校验问题看 `D:\ai\mq` 的 Kafka 笔记。

## 7. 日常操作

```bash
# 改代码后重新部署（就这四行）
mvn -q package -DskipTests
docker rm -f hello-world
docker build -t hello-world:dev .
docker run -d --name hello-world -p 127.0.0.1:8080:8080 hello-world:dev

# 停止并删除（应用无状态随便删，镜像还在）
docker rm -f hello-world

# 用 git sha 做版本 tag（可选，便于回滚追溯）
docker build -t hello-world:$(git rev-parse --short HEAD) .
```

优雅停机验证：`docker stop hello-world` 后看日志，应有 "Commencing graceful shutdown" → "Graceful shutdown complete" 字样（默认给 10 秒宽限）。

## 8. 套用到新项目的清单

1. 复制本目录到新工程（不复制 `target/`）；
2. 改 `pom.xml`：`groupId` / `artifactId` / `name`，Java 包路径同步改；
3. 端口不是 8080 的话：改 `application.yml` 的 `server.port` + `docker run -p 127.0.0.1:宿主端口:容器端口`；
4. 多模块项目：Dockerfile 的 `ARG JAR_FILE` 指到对应模块；
5. 有中间件依赖：按 §6 加环境变量；
6. 构建报错查 `D:\ai\tools\容器开发操作指导手册.md` §7 速诊表（本工程踩过的坑都在里面）。

## 9. 本工程实测踩过的坑（速查）

| 现象 | 原因 | 解法 |
|---|---|---|
| `docker build` 卡在 `resolve docker/dockerfile:1` 后超时 | `# syntax=` 指令强制联网拉 frontend 镜像 | 删掉该行（§4.2） |
| `COPY failed ... no source files` | 忘了先 `mvn package` | 先执行 §5 的第①步 |
| 端口被占 `port is already allocated` | 8080 被其他程序占用 | 换宿主端口 `-p 127.0.0.1:8081:8080` |
| 容器名冲突 `already in use` | 旧容器还在 | `docker rm -f hello-world` |
| 容器里连不上 DB | 容器的 localhost 是它自己 | 注入 `-e DB_HOST=host.docker.internal`（§6） |
