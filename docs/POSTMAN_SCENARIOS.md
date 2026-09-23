# 报价单接口故障场景 —— 外部 Postman 触发手册

给 AIOps 平台做检测/诊断验证用：**从外部（Postman / curl）复现每个场景，并逐条判读。**

> 配套：`docs/postman/aiops-order-scenarios.postman_collection.json` 可直接 Import 进 Postman，
> 里面每个场景一个请求、带断言脚本。本文是它的说明书。

## 0. 先读这三条

1. **换场景前清日志窗口**：连续跑不同场景会互相污染（上一条的 ERROR 还在 ES 的查询窗口里）。
   平台侧约定：`curl -XDELETE localhost:19200/app-logs`。
2. **场景4、5 是并发型，单发请求永远不触发** —— 它们需要"在途请求 ≥ 2"。
   Postman 的 Collection Runner 是**串行**的，也不行；用本文的 **burst 端点**（首选）或
   **Pre-request Script 造并发**（备选，见 §9）。
3. **场景5 一旦触发，服务整体卡死**（Tomcat 线程全被锁住），后续场景都跑不了，只能重启进程。
   所以**它排最后**。

## 1. 请求目标

| 环境 | Base URL | 说明 |
|---|---|---|
| 测试床（minikube） | `http://localhost:18082` | 网关统一入口，traceId 从这进 |
| 测试床（直连服务） | `http://localhost:18080` | 绕过网关，排查用 |
| 本地 `bootRun` | `http://localhost:8080` | 本地开发 |

下面统一写 `{{baseUrl}}`。Postman 里建两个环境变量：`baseUrl`、`orderId`（填 `ORD001`）。

> ⚠️ **`orderId` 必须以 `ORD` 开头**（`ORD001`、`ORD1234` 都行）。场景6 的校验就是拦这个前缀，
> 写成别的前缀会命中场景6（500）。

## 2. 场景速查

| 场景 | 触发 | 期望症状 | 诊断结论应该是什么 |
|---|---|---|---|
| 1 磁盘写满 | `POST /test/leak` 或 `QUOTATION_TEMP_LEAK=true` 后反复 `GET /quotation` | 500 `No space left on device` | 临时文件未清理 → 磁盘 100% |
| 2 Feign 无超时 | `POST /checkout`（warranty 侧注入挂起） | 无响应（一直转圈） | 下游挂起 + 没配 read-timeout |
| 3 未判空 NPE | `GET /quotation/exception` | 500 + NPE 堆栈 | `loadTemplate` 返回 null 未判空 |
| 4 SDF 竞态 | `POST /test/scenario/concurrent-burst?threads=8&rounds=10` | 偶发 500（`NumberFormatException`）/ 响应里日期错乱 | 共享 `SimpleDateFormat` 非线程安全 |
| 5 ABBA 死锁 | `POST /test/scenario/concurrent-burst?threads=50&rounds=50` | 请求全部无响应，**日志里没有 ERROR** | 两把锁获取顺序相反 |
| 6 日志误报 | `GET /quotation?orderId=abc` | 500 + **常量** ERROR 签名 | **误报**：调用方问题被记成应用故障 |
| 7 日志风暴 | `GET /quotation` | 200，但单请求数百条 INFO | 循环里逐行打日志 |

## 3. 场景7：日志风暴（单发就能看）

```
GET {{baseUrl}}/quotation?orderId=ORD001
```

- **Method/URL**：`GET {{baseUrl}}/quotation?orderId={{orderId}}`
- **参数**：`orderId`（必填，`ORD` 前缀）
- **预期响应**：`200`，body 是报价单文本（`Content-Disposition: attachment`）
- **判读**：
  - 单请求打出 **`quotation.line-items + 1` 条 INFO**（默认 500 + 1 = 501 条）。
    本机实测（`line-items=100`）：一次请求新增 101 行日志。
  - ES 里按 `service: order-service` 看日志量曲线 —— 陡增但**没有 ERROR**，所以**日志检测器不会开单**，
    这一条是给"日志量突增"类检测用的。
  - 吞吐会下降（同步 ConsoleAppender），压测时留意响应时间基线被它抬高。
  - ⚠️ **日志量有多大**：单请求约 **200KB**（501 条 JSON）。80 个请求的爆发就能写满 kubelet 的容器日志
    上限（10MB）触发轮转 —— **刚轮转完 `kubectl logs` 会短暂读到空文件**（实测踩过：一度以为应用不写日志了）。
    filebeat 读的是节点上的日志文件，ES 侧不受影响，查日志以 Kibana/ES 为准。
- **修复方向**：汇总成一条（或降到 DEBUG）。`line-items` 可调小来减弱症状。

## 4. 场景6：日志误报（单发就能看）

```
GET {{baseUrl}}/quotation?orderId=abc
```

- **Method/URL**：`GET {{baseUrl}}/quotation?orderId=abc`（任意**不以 `ORD` 开头**的值）
- **预期响应**：**`500`**（不是 400 —— 调用方的错却走了服务端错误）
- **判读**：
  - Kibana/ES 里会出现**常量**签名：`报价单参数校验失败: 订单号格式非法`
    （**刻意不带插值**：带 `orderId` 的话每条签名都不同，`signature_aggregate` 聚合不出 LogAnomaly，
    反而一条单也开不出来 —— 同 `LogGenerator#emitTransientBurst` 的注释）。
  - 同时还有一条框架兜底签名：`unhandled exception: GET /quotation`（带堆栈）。
  - 用 `curl` 反复打同一串脏参数（同签名累积 ≥ 5 条）→ 日志检测器 `min_count=5` 就会**开出问题单**。
  - **期望的诊断结论：误报**，不是应用故障 —— 对照 `GlobalExceptionHandler` 里已经立好的规矩
    （"客户端错误不能记 ERROR，否则会混进基于 ERROR 的日志监控"）。
  - 与本场景对照的是 `POST /test/scenario/transient`（瞬时故障已自愈），两者结论都应是"不用处理"。

## 5. 场景4：SimpleDateFormat 竞态（**要并发**）

### 触发

```
POST {{baseUrl}}/test/scenario/concurrent-burst?threads=8&rounds=10
```

- **Method/URL**：`POST {{baseUrl}}/test/scenario/concurrent-burst`
- **参数**：`threads=8`、`rounds=10` → 投递 80 个请求，**跑不到一秒**（短爆发是关键，见下）
- **预期响应**（本机实测两次：80 请求里 16 个 / 8 个 500）：

```json
{"dispatched":80,"completed":80,"timedOut":0,"http5xx":16,"note":"..."}
```

- **判读**：
  - **`http5xx > 0` = 竞态已触发**。实测：本机（`line-items=100`）80 请求里 8~16 个；
    集群上（默认 `line-items=500`）80 请求里 28~40 个 —— **明细行越多，竞态窗口越大，中招率越高**
    （每次爆发都有波动，偶尔一次 0 也正常，再打一次即可）。
  - 只要 `timedOut == 0` 就说明场景5 的死锁没掺进来。
  - ES 里的 ERROR 签名是 `unhandled exception: GET /quotation`，堆栈**直接指向本服务代码**：

    ```
    java.lang.NumberFormatException: For input string: ""
        at com.company.order.service.QuotationService.formatDeliveryDate(QuotationService.java:184)
        at com.company.order.service.QuotationService.renderItems(QuotationService.java:171)
        at com.company.order.service.QuotationService.generateQuotation(QuotationService.java:134)
    ```

    日志带 traceId（burst 走 HTTP 自调，过了 `TraceFilter`），可继续按 traceId 关联。
  - **另一半是"静默错乱"**：不抛异常但日期算错、接口照样 200。
    明细行的日期输入是**常量**，所以正确时响应里那一列**恒等于** `2026-01-01 00:00:00`；
    要看这一半，得把响应体收下来（burst 端点只统计状态码），见 §9 的备选脚本或：

    ```bash
    seq 1 100 | xargs -P 25 -I{} curl -s "http://localhost:18080/quotation?orderId=ORD{}" \
      | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}' | sort | uniq -c
    # 正常只出现 2026-01-01 00:00:00；出现别的值 = 竞态算错
    ```

  - **期望的诊断结论**：`QuotationService` 里 `static` 共享的 `SimpleDateFormat` 非线程安全，
    高并发下 `parse/format` 踩内部 Calendar → 偶发异常/脏数据。修复方向：每次新建实例，或换
    `DateTimeFormatter`（不可变、线程安全）。

> ⚠️ **短爆发（`threads=8`）是刻意的**：只要爆发持续时间超过一个清理周期（默认 2s），
> 就会先撞出场景5 的死锁，场景4 就测不成了。撞上了（`timedOut > 0` 或服务无响应）→ 重启再试。

## 6. 场景5：ABBA 死锁（**要持续并发**，放最后跑）

### 触发

```
POST {{baseUrl}}/test/scenario/concurrent-burst?threads=50&rounds=50
```

- **预期响应**（本机实测）：

```json
{"dispatched":2500,"completed":91,"timedOut":50,"http5xx":3,"note":"..."}
```

  `dispatched` 是**投递**数；并发上限是 `threads`，所以短窗口内只跑得完一部分，
  `completed + timedOut` 远小于它 —— 这不代表失败。

- **判读（三步）**：
  1. **`timedOut > 0`**：有请求没在 3s 内拿到响应。
  2. **随后单发一个请求也无响应**，而 **`/actuator/health` 仍然返回 200**：

     ```bash
     curl -s -m 5 -o /dev/null -w 'quotation=%{http_code}\n' '{{baseUrl}}/quotation?orderId=ORD001'   # 000 = 无响应
     curl -s -m 5 -o /dev/null -w 'health=%{http_code}\n'    '{{baseUrl}}/actuator/health'             # 200
     ```

     > ⚠️ **健康检查仍然通过**是这类死锁最坑的地方：探针不重启、监控看着"活着"，
     > 只有业务请求全挂。做诊断规则时值得单独覆盖。
  3. **线程 dump 一眼定案**。⚠️ 容器是 `eclipse-temurin:21-jre`，**没有 `jstack` 也没有 `jcmd`**
     （实测镜像里只有 `java/jfr/jrunscript/jwebserver/keytool/rmiregistry`）。
     用 `kill -3`（SIGQUIT）让 JVM 自己把线程栈打到 stdout，再从 pod 日志里看：

     ```bash
     kubectl -n order exec deploy/order-service -- kill -3 1        # PID 1 就是 java，SIGQUIT 只打栈不杀进程
     kubectl -n order logs deploy/order-service --tail=300 | grep -A 6 'QuotationService'
     ```

     集群上实测（`kill -3` 抓的）看到的两条互等（`fd8`/`fe8` 是两把锁的地址）：

     ```
     "scheduling-1"        at QuotationService.cleanExpiredFiles(QuotationService.java:225)
         - waiting to lock <0x00000000f66809d8>   ← 想要 templateLock
         - locked <0x00000000f66809e8>            ← 手里攥着 fileLock
     "http-nio-8080-exec-21" at QuotationService.generateQuotation(QuotationService.java:142)
         - waiting to lock <0x00000000f66809e8>   ← 想要 fileLock
         - locked <0x00000000f66809d8>            ← 手里攥着 templateLock
     ```

  - **日志里没有 ERROR**（真死锁不打日志），所以**日志检测器抓不到它** ——
    这个问题单只能从 APM 延迟/超时或线程指标里开出来，是刻意的对照。
  - **期望的诊断结论**：两条代码路径以相反顺序获取 `templateLock`/`fileLock`
    （生成路径 `templateLock → fileLock`，清理任务 `fileLock → templateLock`）→ ABBA 死锁。
    **修复方向**：统一加锁顺序（或合并成一把锁）。
  - **恢复**：锁无法从外部释放，只能重启：

    ```bash
    kubectl -n order rollout restart deploy/order-service        # 测试床
    # 本地：Ctrl-C 后重新 bootRun
    ```

### 触发条件（想调松紧时看这个）

死锁成立的条件是：**某个请求在清理任务持有 `fileLock` 的窗口内去请求 `fileLock`**。
清理任务每 2s（`CLEANUP_INTERVAL_MS` 常量，`QuotationService`）跑一次，锁内停留
`quotation.cleanup-scan-hold-ms`（**默认 5ms**）+ 真实目录扫描（`/data/tmp` 里文件越多越慢）。所以：

- 爆发**持续时间跨过一个完整周期**（≥2s）⇒ 必然撞上（实测一撞就死）：窗口期内请求以亚毫秒间隔到达。
- 单发请求、或几百毫秒内跑完的短爆发 ⇒ 撞不上（这正是场景4 能单独演示的原因）。
  ⚠️ 但单发也**不是完全免疫**：中招概率 ≈ (请求耗时 + 窗口) / 周期 ≈ **1.5%**。
  真被单发打死了（`/quotation` 突然无响应），重启即可 —— 曲线调参数本来就是这么设计的。
- **调参**（不用重新构建，加环境变量即可）：

  ```bash
  kubectl -n order set env deploy/order-service QUOTATION_CLEANUP_SCAN_HOLD_MS=50   # 更容易死锁
  kubectl -n order set env deploy/order-service QUOTATION_CLEANUP_SCAN_HOLD_MS=0    # 几乎不会死锁，适合只看场景4
  kubectl -n order set env deploy/order-service QUOTATION_CLEANUP_SCAN_HOLD_MS=5    # 恢复默认
  ```

- 想让窗口更宽又不想误伤单发：调大 `CLEANUP_INTERVAL_MS`（常量，要重新构建）——窗口出现得更少，
  但每次持续更久。

## 7. 场景1 / 2 / 3（已有场景，入口速查）

| 场景 | 触发 | 判读 |
|---|---|---|
| 1 磁盘写满 | `POST {{baseUrl}}/test/leak?count=200&sizeMb=1`；或开 `QUOTATION_TEMP_LEAK=true` 后反复 `GET /quotation` | `GET /quotation` → 500 `No space left on device`；Prometheus `data_disk_free_bytes` 掉底 |
| 2 结账挂起 | `POST {{baseUrl}}/checkout?orderId=ORD001`（需 warranty 侧已注入 `WARRANTY_MISSING_FIN=true`） | 请求一直不返回；Kibana 按 traceId 关联到 warranty-service 的异常日志 |
| 3 未判空 NPE | `GET {{baseUrl}}/quotation/exception` | 500 + `QuotationService.quotationSummary` 的 NPE 堆栈 |

> 场景1、2 的注入/恢复脚本在平台侧 `agentflow-testbed/fault-inject/`（`scenario1.sh` 等），
> 细节见那份 README。

## 8. 场景之间的干扰（排期时注意）

| 干扰 | 说明 |
|---|---|
| 场景5 → 其他全部 | 一触发服务就卡死，`/quotation` 全部无响应（`/actuator/health` 仍 200）。**排在最后跑**。 |
| 场景4 ↔ 场景5 | 持续超过一个清理周期的爆发会先撞出死锁，场景4 就看不到干净结果。场景4 用短爆发。 |
| 场景7 → 吞吐基线 | 每个 `/quotation` 都多几百条日志，会压低其他场景的吞吐/延迟基线。必要时把 `quotation.line-items` 调小。 |
| 清理任务 ↔ 场景1 | 清理任务只删 `quotation_*` 且超过 10 分钟的过期文件，**不碰 `leak_*.bin`**（场景1 的填盘证据安全）。但场景1 若靠 `QUOTATION_TEMP_LEAK=true` 反复打 `/quotation` 泄漏，那些文件 10 分钟后会被回收 —— 要压磁盘请用 `/test/leak`。 |

## 9. 备选触发方式（不改代码 / Postman 沙箱对并发节流时）

### 9.1 Postman Pre-request Script 造并发

Collection Runner 是串行的，但**请求的 Pre-request Script 里循环 `pm.sendRequest`** 是并发的
（不 await，全部同时在途）。粘进任意请求的 Pre-request Script 即可：

```js
// 造 200 个并发请求（"爆发"在脚本执行完之前就已经在路上）
const base = pm.environment.get("baseUrl");
for (let i = 0; i < 200; i++) {
    pm.sendRequest({
        url: `${base}/quotation?orderId=ORD${100000 + i}`,
        method: "GET",
        timeout: 8000
    }, (err, res) => {
        // 回调不保证都执行（请求已发出，脚本已结束），不影响发压效果
        if (err) console.log("timeout/failed:", err.message);
    });
}
console.log("已并发派发 200 个 /quotation 请求");
```

- 判读仍然看 Kibana 里的 ERROR 签名和返回体日期；并发是否够，看是否出现 500。
- 想看"日期错乱"这一半，把回调里 `res.text()` 存进 `pm.collectionVariables` 累积统计。

### 9.2 curl 版（最省事，本手册的实测就用它做的）

```bash
BASE=http://localhost:18080

# 场景4：短并发，看状态码分布与日期
seq 1 100 | xargs -P 25 -I{} curl -s -o /dev/null -w "%{http_code}\n" "$BASE/quotation?orderId=ORD{}" | sort | uniq -c

# 场景5：持续并发（几秒），判据是随后单发无响应
seq 1 300 | xargs -P 50 -I{} curl -s -m 5 -o /dev/null "$BASE/quotation?orderId=ORD{}"
curl -s -m 5 -o /dev/null -w 'quotation now=%{http_code}\n' "$BASE/quotation?orderId=ORD001"
```
