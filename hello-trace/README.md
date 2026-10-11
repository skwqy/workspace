# hello-trace —— 双进程调用链样例（Boot 4.1.1 + JDK 25）

两个 Java 进程的调用链示例工程：**order-svc（8081，调用方）通过 HTTP 调用 stock-svc（8082，被调方）**，
一次性展示四个技术的真实用法与可验证效果：

| 技术 | 落点 |
|---|---|
| 虚拟线程（JDK 21+ 稳定 API） | `spring.threads.virtual.enabled=true`（Tomcat 请求线程）、`Thread.ofVirtual()` 命名工厂并行扇出（`OrderService`） |
| 调用链追踪（Micrometer Tracing + Brave） | 跨进程 W3C traceparent 自动传播；traceId/spanId 打进两进程日志与响应体（`logback-spring.xml` 的 pattern + `Traces`） |
| 日志 Marker | `trace-common` 的 `Markers` 常量 + 三种消费方式：TurboFilter 全局丢弃 / 独立审计文件 / pattern 中显示标签 |
| 日志 TurboFilter | 自定义限流 `RateLimitTurboFilter`（stock-svc）+ 内置 `MarkerFilter` / `DynamicThresholdFilter` / `DuplicateMessageFilter` |

无中间件依赖（不需要 Docker），`mvn install` 后两个 `java -jar` 即可复现全部演示。

## 一、结构与启动

```
hello-trace/
├── trace-common/          共享：Marker 常量、DTO（StockView/QuoteView）、虚拟线程工具、MDC 读取
├── order-svc/             进程 1（8081）：RestClient 并行扇出调用 stock-svc；内置 TurboFilter 演示
└── stock-svc/             进程 2（8082）：库存/询价/审计接口；自定义 TurboFilter 演示
```

```bash
cd /d/code/workspace/hello-trace
mvn -q install
java -jar stock-svc/target/stock-svc-0.0.1-SNAPSHOT.jar > logs/stock-console.log 2>&1 &
java -jar order-svc/target/order-svc-0.0.1-SNAPSHOT.jar > logs/order-console.log 2>&1 &
curl localhost:8081/actuator/health && curl localhost:8082/actuator/health   # 均 {"status":"UP"}
```

Postman 集合在 `postman/hello-trace.postman_collection.json`（health / 下单 / burst / 审计等 7 个请求）。

## 二、演示剧本（全部实测通过）

### 1) 虚拟线程 + 并行扇出 + 跨进程一条链

```bash
curl -X POST localhost:8081/orders -H 'Content-Type: application/json' -d '{"sku":"A-100","qty":2}'
```

响应（节选）：

```json
{"orderId":"O-9a5ced62","available":85,"unitPrice":84.13,"total":168.26,
 "handlerThread":"tomcat-handler-1","handlerVirtual":true,
 "traceId":"6acba46119d0c678b5d37f4cf4f3d35f","costMs":187}
```

- `handlerVirtual:true`：Tomcat 用**虚拟线程**处理请求（`spring.threads.virtual.enabled=true`）。
- 下单内部两个子任务跑在命名虚拟线程 `order-vc-0` / `order-vc-1` 上**并行**调 stock-svc：
  costMs≈187ms（两个子调用 120ms+80ms 串行应为 200ms+），日志里两组 child 记录时间重叠可见。
- 同一个 traceId 贯穿两个进程，stock 侧 spanId 各不相同（同一 trace 的两个 server span）：

```
[order-svc  6acba…4f3d35f/b5d37f4cf4f3d35f] [tomcat-handler-1] place order: sku=A-100 qty=2 virtual=true
[order-svc  6acba…4f3d35f/b5d37f4cf4f3d35f] [order-vc-0] child[stock] start: thread=order-vc-0 virtual=true
[stock-svc  6acba…4f3d35f/ad5fc947aee71017] [tomcat-handler-6] quote request: sku=A-100
[stock-svc  6acba…4f3d35f/a1af3f729429bf5e] [tomcat-handler-5] stock query: sku=A-100
```

> **原理与坑**：RestClient 的 client span 与 traceparent 注入由 Boot 自动装配完成；
> 但 Observation 上下文是 ThreadLocal，**新开的虚拟线程不会自动继承**——`OrderService.withParentScope()`
> 在提交任务前抓取当前 Observation、在子线程里重新 `openScope()`，子线程的 HTTP 调用才挂到同一 trace。

### 2) 并发扇出：`GET /orders/burst?n=30&delayMs=60`

30 个虚拟线程并发打 stock-svc，30/30 全部成功、总耗时 87ms（单次就要 60ms）。
同时两侧各演示一种 TurboFilter（见下）。

### 3) Marker 的三种命运：`GET /stock/audit?sku=A-100&phone=13800001111`

一次调用打出三条 Marker 不同的日志：

| 日志（Marker） | 结果 | 机制 |
|---|---|---|
| `SECURITY - audit started: … phone(masked)=138****1111` | 控制台可见（pattern 里带 `{SECURITY}` 标签），且**额外写入** `logs/stock-svc/security-audit.log` | logback appender 上的 `EvaluatorFilter` + `OnMarkerEvaluator` 按 Marker 路由 |
| `CONFIDENTIAL - audit raw phone = 13800001111` | **任何输出里都没有**——哪怕代码误写也不漏 | 全局 TurboFilter `MarkerFilter` 对 CONFIDENTIAL 无条件 DENY（两个服务同一条安全底线） |
| `DEBUG - audit verbose detail: …` | 默认看不到 | `DynamicThresholdFilter` 在 turbo 层门禁（见下） |

### 4) TurboFilter 按请求动态调级：`…/stock/audit?...&debug=true`

加 `debug=true` 后控制器往 MDC 放 `debugOn=true`，同一请求的 DEBUG 立刻可见：

```
INFO  [stock-svc 6acba…/{span}] SECURITY - audit started: …
DEBUG [stock-svc 6acba…/{span}]          - audit verbose detail: sku=A-100 phone=13800001111 …
```

`DynamicThresholdFilter` 的标准姿势：**root 开到 DEBUG，门禁放在 turbo 层**——
无 MDC 的 DEBUG 在 turbo 阶段就被 DENY（连格式化都不发生），带 MDC 的请求放行。
注意：TurboFilter 只能"放行到常规流程"，若 root 仍是 INFO，常规级别检查照样拦掉 DEBUG。

### 5) 自定义 TurboFilter 限流（stock-svc `RateLimitTurboFilter`）

每个 logger 每秒最多放行 10 条 INFO/DEBUG，超出在 turbo 层直接 DENY；**WARN/ERROR 永不拦截**。
连打 12 次 `GET /stock/A-100?delayMs=0`：12 次请求只出现 10 条 `stock query` 日志；
而 `?fail=true` 的 500 错误堆栈（ERROR）永远完整输出——"高峰限噪、错误必留"。

### 6) 内置 `DuplicateMessageFilter`（order-svc）：对照组

burst 的 30 条同格式日志只输出 6 条（原始 1 条 + allowedRepetitions=5）。
它是**按消息格式计数、超限永久 DENY**——最粗暴的省日志手段，长期运行的服务慎用；
这正是 stock-svc 用带时间窗口的自定义限流的原因。

## 三、踩坑实录（Boot 4.1.1 模块化相关，供复制本项目时参考）

1. **追踪依赖要加两个，缺一不可**：Boot 4 把追踪自动配置彻底模块化，`spring-boot-starter-actuator`
   只带 metrics 不带 tracing。
   - `org.springframework.boot:spring-boot-micrometer-tracing-brave`——Boot 的装配（建 `brave.Tracing`/`Propagator`，接通 ObservationRegistry）
   - `io.micrometer:micrometer-tracing-bridge-brave`——Brave 桥接**库**（装配模块不传递依赖它）
   缺第二个：`BraveAutoConfiguration` 因找不到 `brave.Tracer` 不生效，Tracer 退化为 `noopTracer`，traceId 全空。
2. **`RestClient.Builder` 自动装配在独立模块**：需加 `org.springframework.boot:spring-boot-restclient`，
   否则注入 `RestClient.Builder` 直接启动失败。
3. **logback 1.5 的 `DynamicThresholdFilter` 配置变了**：子元素是 `<MDCValueLevelPair>`（旧文档的
   `MDCValuePair` 不认识），且 pair 里**没有** `<key>`——MDC key 由外层 `<Key>` 统一指定。
   配错了只会在启动日志里留一条 `|-WARN ... Ignoring unknown property`，不报错、静默失效。
4. **logback 1.5 的 `OnMarkerEvaluator` 在 `ch.qos.logback.classic.boolex`**（不在 core 包）。
5. 其余沿用工作区既有坑：日志消息保持 ASCII（Git Bash curl 中文参数会 GBK 400）；本项目无中间件，
   不涉及 study-stack。

## 四、日常操作

```bash
# 构建
mvn -q install

# 启动（顺序：先被调方）
java -jar stock-svc/target/stock-svc-0.0.1-SNAPSHOT.jar &
java -jar order-svc/target/order-svc-0.0.1-SNAPSHOT.jar &

# 停止（Windows / Git Bash）
netstat -ano | grep -E ':(8081|8082).*LISTENING'   # 找 PID
cmd //c "taskkill /PID <pid> /F"
```

日志输出：两服务控制台；SECURITY 审计另落 `logs/stock-svc/security-audit.log`（相对启动目录）。
