# TalentHub 报表异步导入导出 —— 详细设计方案

> 版本：v1.0（2026-08-29）
> 场景：人力资源系统中的批量数据处理——管理员导入学员/报名名单、导出报名明细与统计报表，单次数据量 1 万 ~ 100 万行
> 核心目标：**接口不超时、服务不 OOM、任务可查询可重试、导入结果可解释（哪一行为什么失败）**

---

## 1. 问题定义

同步导入导出在数据量上来后有三个必然的问题：

| 问题 | 原因 | 后果 |
|---|---|---|
| 接口超时 | 10 万行的查询 + 写 Excel 通常 > 30s，超过网关/浏览器超时 | 用户拿不到文件，任务却仍在后台跑 |
| 内存溢出 | 一次性 `selectList` 全量装进内存，再整体写文件 | 一个大导出打垮整个服务 |
| 结果不可解释 | 导入失败只返回"第 N 行错误"或整体回滚 | 用户无法定位、无法修正后重传 |

设计原则：**请求只负责创建任务，执行与请求解耦；数据全程流式，内存与数据量无关；每一步状态落库，任意时刻可查、可续、可重试。**

---

## 2. 总体架构

```
管理员                          应用服务                                存储
  │                                │                                    │
  │ POST /reports/export           │                                    │
  │ POST /reports/import (文件)    │                                    │
  ├───────────────────────────────►│ ① 参数校验、防重（同人同参数进行中任务）│
  │◄── 202 {taskId} ───────────────┤ ② INSERT report_task(status=PENDING) │
  │                                │ ③ 提交到任务线程池（或仅落表等调度）    │
  │                                │                                    │
  │                                │ ┌── 任务执行器 ReportTaskWorker ──┐ │
  │                                │ │ 领取任务：UPDATE ... WHERE      │ │
  │                                │ │   status=PENDING → RUNNING     │ │
  │                                │ │ 导出：分页查询 → 流式写 Excel     │ │
  │                                │ │ 导入：流式读 Excel → 分批校验/入库│ │
  │                                │ │ 每批次更新 processed_rows       │ │
  │                                │ │ 结果文件/错误文件 → 存储          ├─►│ 本地目录 / MinIO / OSS
  │                                │ │ 更新 status=SUCCESS / FAILED    │ │
  │                                │ └────────────────────────────────┘ │
  │ GET /reports/tasks/{id}        │                                    │
  ├───────────────────────────────►│ 返回状态、进度、下载地址              │
  │ GET /reports/tasks/{id}/file   │                                    │
  ├───────────────────────────────►│ 校验归属 → 302 / 流式回传            │
  │                                │                                    │
  │                                │ 补偿任务 ReportTaskRecoverJob：     │
  │                                │   RUNNING 超时 → 重置 PENDING 重跑  │
  │                                │   过期文件清理                      │
```

组件职责：

| 组件 | 职责 |
|---|---|
| `ReportTaskService` | 创建任务、查询任务、防重、下载鉴权 |
| `ReportTaskWorker` | 从任务表领取任务并执行，隔离线程池 |
| `ExportHandler` / `ImportHandler` | 按 `biz_type` 注册的业务处理器（策略模式），只关心"查哪些数据 / 一行怎么校验入库" |
| `FileStorage` | 文件存储抽象，本地目录实现用于演示，接口预留 MinIO/OSS |
| `ReportTaskRecoverJob` | 兜底：僵死任务恢复、过期文件清理 |

**为什么任务表本身做队列，而不引入 MQ**：单机演示规模下，DB 任务表 + `UPDATE ... WHERE status=PENDING` 的抢占式领取已经满足"不重复执行、可恢复"两个要求；任务本身持续几十秒到几分钟，MQ 的削峰价值不大。多实例部署时任务表方案依然成立（领取靠行更新的原子性），只是需要轮询。MQ 作为 §8 的扩展点。

---

## 3. 数据库设计

```sql
CREATE TABLE report_task (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_type       SMALLINT      NOT NULL,            -- 1-导出 2-导入
    biz_type        VARCHAR(64)   NOT NULL,            -- 业务类型：ENROLLMENT_EXPORT / STUDENT_IMPORT ...
    status          SMALLINT      NOT NULL DEFAULT 0,  -- 0-待处理 1-执行中 2-成功 3-失败 4-部分成功(导入)
    params          JSONB         NULL,                -- 导出查询条件 / 导入选项
    param_hash      CHAR(32)      NULL,                -- 防重指纹：user_id + biz_type + params
    user_id         BIGINT        NOT NULL,            -- 任务归属，下载鉴权依据
    total_rows      INT           NULL,                -- 总行数（导出：count；导入：文件行数）
    processed_rows  INT           NOT NULL DEFAULT 0,  -- 已处理行数，进度 = processed/total
    success_rows    INT           NOT NULL DEFAULT 0,  -- 导入成功行数
    fail_rows       INT           NOT NULL DEFAULT 0,  -- 导入失败行数
    source_file     VARCHAR(256)  NULL,                -- 导入原始文件路径
    result_file     VARCHAR(256)  NULL,                -- 导出结果文件 / 导入错误明细文件
    error_msg       VARCHAR(1024) NULL,                -- 整体失败原因
    retry_count     INT           NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ   NULL,
    finished_at     TIMESTAMPTZ   NULL,
    expire_at       TIMESTAMPTZ   NULL,                -- 文件过期时间，过期后清理
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_task_user   ON report_task (user_id, created_at DESC);
CREATE INDEX idx_report_task_status ON report_task (status, created_at);    -- worker 领取
CREATE INDEX idx_report_task_hash   ON report_task (param_hash) WHERE status IN (0, 1);  -- 防重，部分索引

-- 导入错误明细：按行记录，生成错误文件的数据源，也支持页面直接查看
CREATE TABLE report_task_error (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id     BIGINT        NOT NULL,
    row_num     INT           NOT NULL,                -- Excel 行号（从 1 开始，含表头偏移）
    raw_data    JSONB         NULL,                    -- 原始行内容
    error_msg   VARCHAR(512)  NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_error_task ON report_task_error (task_id, row_num);
```

状态机：

```
PENDING ──领取──► RUNNING ──► SUCCESS
                     │──────► PARTIAL_SUCCESS   （导入：有失败行但未超阈值）
                     │──────► FAILED            （异常 / 失败行超阈值 / 校验表头不通过）
                     └──超时──► PENDING          （RecoverJob，retry_count+1，超过上限置 FAILED）
```

---

## 4. 任务生命周期

### 4.1 创建

1. 参数校验（导出：查询条件；导入：文件类型、大小上限、表头模板）。
2. 防重：`param_hash = md5(userId + bizType + 规范化 params)`，查询是否存在 `status IN (PENDING, RUNNING)` 的同指纹任务，存在则直接返回已有 `taskId`，避免同一用户连点产生多个相同任务（与接口防重复提交组件的思路一致，但这里是**业务级**防重，窗口是"任务完成前"而不是几秒）。
3. 导入：先把上传文件落到 `FileStorage`，记录 `source_file`；不在请求线程解析文件。
4. `INSERT report_task`，返回 `202 Accepted + taskId`。
5. 事务提交后（`TransactionSynchronization.afterCommit`）再向线程池提交，避免 worker 领取时任务行还不可见。

### 4.2 领取（保证一个任务只被执行一次）

```sql
UPDATE report_task
SET status = 1, started_at = now(), updated_at = now()
WHERE id = #{id} AND status = 0
```

affected = 0 说明已被其他线程/实例领走，直接放弃。多实例下 `ReportTaskRecoverJob` 定期按 `status=0 ORDER BY created_at LIMIT n` 拉取遗漏任务，同样走这条语句抢占。

### 4.3 执行 —— 导出

```
count(*) → total_rows
loop:
    分页查询 1 批（游标/主键分页，batch = 2000）
    转换为行对象 → ExcelWriter.write(batch, sheet)
    每 N 批更新 processed_rows
    batch.clear()
finish() → 上传 → result_file, expire_at, status = SUCCESS
```

要点：
- **禁止 OFFSET 深分页**：按主键 `WHERE id > #{lastId} ORDER BY id LIMIT 2000`，每页成本恒定。
- **单 sheet 上限**：xlsx 最大 1,048,576 行，超过 100 万行按 sheet 拆分；演示阶段直接限制单任务上限 100 万行，超出提示缩小条件。
- **EasyExcel 写入**：`EasyExcel.write(outputStream, RowVO.class).build()` + 按 sheet `write(list)`；`finish()` 放在 `finally`。
- 大导出（> 20 万行）可按主键区间分片并行查询、各写一个 sheet，再顺序合并写入；单线程 100 万行实测约 40s 量级，并行不是首要优化项，先保证内存稳定。

### 4.4 执行 —— 导入

```
校验表头（与模板一致，否则整体 FAILED）
EasyExcel.read(inputStream, RowDTO.class, listener).sheet().doRead()
listener.invoke(row):
    行级校验（注解校验 + 业务校验：外键存在、重复行、格式）
    合法 → buffer；非法 → 写 report_task_error
    buffer 满 1000 → 批量写入 DB（单批一个事务）
    更新 processed_rows / success_rows / fail_rows
listener.doAfterAllAnalysed():
    flush 剩余 buffer
    fail_rows > 0 → 生成错误文件（原始行 + 错误原因列）→ result_file
    status = fail_rows == 0 ? SUCCESS : (fail_rows / total > 阈值 ? FAILED : PARTIAL_SUCCESS)
```

要点：
- **事务粒度 = 一批而不是整个文件**：文件级事务在百万行下锁表时间过长且回滚代价大；按批提交，失败行单独记录，用户修正错误文件后重传。这是"部分成功"语义的来源，需要在产品上明确（HR 场景通常接受）。
- **文件内重复**：如员工工号在文件内重复，用 `Set` 在 listener 内去重并记为错误行，避免打到唯一约束才发现。
- **与已有数据冲突**：`INSERT ... ON CONFLICT DO NOTHING` 返回影响行数判断，或按选项走 `DO UPDATE` 覆盖。
- **重跑幂等**：任务被 RecoverJob 重置重跑时，先 `DELETE report_task_error WHERE task_id = ?` 并清零计数；数据写入本身依赖唯一约束幂等。

### 4.5 查询与下载

- `GET /reports/tasks/{id}`：返回状态、`processed/total`、`result_file` 是否可下载、错误行数；前端 2s 轮询，终态后停止。
- `GET /reports/tasks/{id}/file`：校验 `user_id == 当前用户`（或管理员），本地存储走 `StreamingResponseBody` 流式回传，对象存储返回预签名 URL 重定向。
- `GET /reports/tasks?page=`：我的任务列表，按 `created_at DESC`。

### 4.6 兜底与清理（ReportTaskRecoverJob）

| 场景 | 判定 | 处理 |
|---|---|---|
| 服务重启导致任务卡在 RUNNING | `status=1 AND started_at < now() - 超时阈值(如 30min)` | `retry_count < 3` → 重置 PENDING 重新领取；否则 FAILED + 告警日志 |
| PENDING 无人领取（线程池满/提交丢失） | `status=0 AND created_at < now() - 1min` | 重新提交线程池 |
| 文件过期 | `expire_at < now()` | 删除文件，清空 `result_file`，状态不变 |

---

## 5. 线程池与资源隔离

```java
@Bean("reportTaskExecutor")
ThreadPoolTaskExecutor reportTaskExecutor() {
    core = 2, max = 4, queue = 50            // 单任务 IO 密集但持有连接，数量小
    rejectedHandler = 落表即可，不抛异常      // 任务已在表里，由 RecoverJob 补提交
    threadNamePrefix = "report-task-"
}
```

- **独立线程池**，不与 `@Async` 默认池、报名接口共用。
- **数据库连接**：导出分页查询每批用完即还，不长期持有；导入每批一个事务。现有 Hikari 上限 30（报名热点行锁场景收紧），报表任务并发不超过 4，不会挤占报名接口。
- **JVM 内存**：批次 2000 行 × 单行约 1KB ≈ 2MB，加上 EasyExcel 内部缓冲（默认写入 100 行刷盘），单任务峰值几十 MB，与总数据量无关。
- **单用户并发限制**：同一用户最多 2 个进行中任务，避免一人占满线程池。

---

## 6. 接口设计

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/admin/reports/export` | body: `{bizType, params}` → `202 {taskId}` |
| POST | `/api/admin/reports/import` | multipart: `bizType, file` → `202 {taskId}` |
| GET | `/api/admin/reports/templates/{bizType}` | 下载导入模板 |
| GET | `/api/admin/reports/tasks` | 我的任务列表 |
| GET | `/api/admin/reports/tasks/{id}` | 任务详情与进度 |
| GET | `/api/admin/reports/tasks/{id}/errors` | 导入错误行（分页） |
| GET | `/api/admin/reports/tasks/{id}/file` | 下载结果/错误文件 |
| POST | `/api/admin/reports/tasks/{id}/retry` | 失败任务手动重试 |

首批业务处理器（对应 TalentHub 已有数据）：

| bizType | 方向 | 内容 |
|---|---|---|
| `ENROLLMENT_EXPORT` | 导出 | 按课程/时间段导出报名明细（用户、课程、报名时间、状态） |
| `COURSE_STAT_EXPORT` | 导出 | 课程维度统计：名额、已报、取消数、报名率 |
| `ENROLLMENT_IMPORT` | 导入 | 批量补录报名（线下报名回填），校验课程存在、名额、重复 |
| `COURSE_IMPORT` | 导入 | 批量创建课程 |

`ENROLLMENT_IMPORT` 与抢课模块的关系：导入走 DB 直写，绕过 Redis 预扣，因此**导入完成后必须触发该课程的 Redis 重建**（复用 `StockCacheService.preheat`），否则对账任务会在下一轮发现 Redis 虚高并修正——这是两个模块的衔接点，也是面试时可以讲的一致性细节。

---

## 7. 代码结构（对齐工程设计 §2）

```
com.talenthub
├── controller/AdminReportController.java
├── service/ReportTaskService.java / impl
├── report/
│   ├── ReportTaskWorker.java            # 领取 + 执行 + 状态更新
│   ├── handler/
│   │   ├── ExportHandler.java           # interface: bizType(), count(params), fetchBatch(params, lastId, size), rowClass()
│   │   ├── ImportHandler.java           # interface: bizType(), rowClass(), validate(row, ctx), saveBatch(rows), afterAll(ctx)
│   │   ├── EnrollmentExportHandler.java
│   │   └── EnrollmentImportHandler.java
│   ├── ReportHandlerRegistry.java       # bizType → handler，启动时收集 Bean
│   └── storage/FileStorage.java + LocalFileStorage.java
├── job/ReportTaskRecoverJob.java
├── mapper/ReportTaskMapper.java / ReportTaskErrorMapper.java
└── model/entity/ReportTask.java, ReportTaskError.java
```

新增依赖：`com.alibaba:easyexcel:4.0.3`。

---

## 8. 扩展点（不在首版实现，但结构上预留）

| 方向 | 做法 |
|---|---|
| 多实例 | 任务表领取已支持；把"提交线程池"改为各实例 RecoverJob 轮询领取即可，或引入 MQ 投递 taskId |
| 对象存储 | 实现 `MinioFileStorage`，下载改为预签名 URL |
| 进度推送 | 轮询改 SSE，进度写 Redis 而非每批 UPDATE DB |
| 百万级并行导出 | 按主键区间分片，多线程查询各写临时文件，最后合并 sheet |
| 通知 | 任务终态后站内信/邮件 |

---

## 9. 验证方式（写进简历前要做的事）

1. 造数：报名表插入 50 万 ~ 100 万行。
2. 对比：同步 `selectList` 全量导出（观察 OOM / 超时）vs 异步分页导出，记录耗时、JVM 堆峰值（`jconsole` 或 `-Xmx512m` 下是否稳定）。
3. 导入：10 万行文件，混入 5% 错误行，验证错误文件可下载、部分成功计数正确、重传错误文件后全部成功。
4. 中断恢复：任务执行中 kill 服务，重启后 RecoverJob 重置并完成，最终数据无重复（依赖唯一约束）。
5. 与抢课衔接：导入报名后 Redis 库存与 DB 一致。

---

## 参考

- [EasyExcel 百万数据导出最佳实践](https://zhuanlan.zhihu.com/p/1990530059505800779)：分页 + 流式写、`finish()` 放 finally、页大小 1000–5000 经验值
- [EasyExcel 处理 MySQL 百万数据导入导出案例](https://www.cnblogs.com/JavaBuild/p/18185854)：百万行导出约 40s、导入分钟级基准
- [基于 EasyExcel + 线程池 + 批量插入实现百万级数据导入](http://blog.hypo.ink/archives/ji-yu-easyexcel-xian-cheng-chi-pi-liang-cha-ru-shi-xian-bai-wan-ji-shu-ju-dao-ru)：ReadListener 分批 + 线程池
- [EasyExcel 带格式多线程导出百万数据](https://zhuanlan.zhihu.com/p/611571625)：分片多 sheet 并行写
- [async-excel 组件](https://github.com/2229499815/async-excel)：任务表、错误文件、进度、存储抽象的开源参考
