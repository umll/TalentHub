# TalentHub 人员报名综合查询 —— 慢查询优化方案（可复现）

> 版本：v1.0（2026-08-29）
> 场景：HR 管理端「按条件查人」——按组织（含下级）、姓名/工号、课程、报名时间段、状态筛选报名记录，分页展示，导出复用同一查询
> 目标：**在 50 万人员 / 200 万报名记录下，列表接口首页与深页（第 1000 页）均稳定在 100ms 内；每一步优化都有 `EXPLAIN (ANALYZE, BUFFERS)` 前后对比，数字可复现**

---

## 1. 为什么这个接口必然慢

人事系统的综合查询有三个典型特征，叠加起来就是慢查询的教科书案例：

| 特征 | 表现 | 代价 |
|---|---|---|
| 多表关联 | 报名 ⋈ 人员 ⋈ 组织 ⋈ 课程，4 张表 | 关联顺序选错就是大表全扫 + Hash Join |
| 组织树筛选 | "查某部门及其所有下级" | 递归 CTE 或 `LIKE 'path%'`，结果集大且难走索引 |
| 深分页 + 排序 | `ORDER BY enrolled_at DESC OFFSET 20000 LIMIT 20` | 前 20000 行全部关联、排序后丢弃 |

再加上 HR 场景特有的「count 总数」需求（前端分页器要显示总页数），一个页面请求往往是**两条慢 SQL**。

---

## 2. 数据模型（新增两张表）

```sql
-- 组织架构：path 物化路径，避免递归查询
CREATE TABLE org (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(64)  NOT NULL,
    parent_id  BIGINT       NULL,
    path       VARCHAR(256) NOT NULL,   -- 如 /1/12/135/，含自身
    level      SMALLINT     NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 人员（模拟员工主数据）
CREATE TABLE employee (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    emp_no      VARCHAR(32)  NOT NULL,           -- 工号
    name        VARCHAR(64)  NOT NULL,
    org_id      BIGINT       NOT NULL,
    status      SMALLINT     NOT NULL DEFAULT 1, -- 1-在职 2-离职
    hire_date   DATE         NOT NULL,
    phone       VARCHAR(20)  NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_employee_no UNIQUE (emp_no)
);

-- enrollment.user_id 即 employee.id，现有表不动
```

造数规模（`scripts/gen_query_data.sql`，用 `generate_series` 直接在 PG 内生成，无需应用参与）：

| 表 | 行数 | 分布 |
|---|---|---|
| org | 3 层 × 约 500 节点 | 省 → 市 → 部门 |
| employee | 500,000 | 随机挂到叶子部门，5% 离职 |
| course | 2,000 | 报名时间跨 2 年 |
| enrollment | 2,000,000 | 每人 0~10 门，`enrolled_at` 随机分布，10% 已取消 |

造数完成后 `ANALYZE` 全表，并记录 `pg_relation_size`，保证每次复现的基线一致。

---

## 3. 基线：先写出最"自然"的实现

接口 `GET /api/admin/enrollments?orgId=&keyword=&courseId=&from=&to=&status=&page=&size=`，第一版 SQL 就是大多数人第一反应会写的：

```sql
-- v0 基线
SELECT e.id, emp.emp_no, emp.name, o.name AS org_name, c.title, e.status, e.enrolled_at
FROM enrollment e
JOIN employee emp ON emp.id = e.user_id
JOIN org o        ON o.id = emp.org_id
JOIN course c     ON c.id = e.course_id
WHERE o.path LIKE '/1/12/%'                        -- 组织及下级
  AND (emp.name LIKE '%张%' OR emp.emp_no LIKE '%张%')
  AND e.enrolled_at BETWEEN '2025-01-01' AND '2025-12-31'
  AND e.status = 1
ORDER BY e.enrolled_at DESC
OFFSET 20000 LIMIT 20;

SELECT COUNT(*) FROM (同样的 JOIN + WHERE);
```

只有现有索引（主键、`uk_user_course`、`idx_enrollment_course`）。用 §6 的脚本记录：首页耗时、第 1000 页耗时、count 耗时、`shared hit/read` 块数、计划中出现的 `Seq Scan` / `Hash Join` / `Sort` 节点。**这组数字就是简历里"从 x s"的来源。**

预期现象（实际以测出为准）：
- `enrollment` 上按时间范围过滤没有索引 → Seq Scan 200 万行
- `employee.name LIKE '%张%'` 前导通配 → 无法走 B-tree
- `ORDER BY ... OFFSET 20000` → 对全部匹配行 Sort 后丢弃 2 万行
- count 与列表各扫一遍

---

## 4. 优化步骤（每步单独测，单独留数）

每一步只改一件事，改完跑一次 §6 脚本，这样最终能说清"哪一步贡献了多少"。

### Step 1：给驱动表加复合索引（解决 Seq Scan）

```sql
CREATE INDEX idx_enrollment_status_time ON enrollment (status, enrolled_at DESC, id DESC);
CREATE INDEX idx_employee_org ON employee (org_id);
CREATE INDEX idx_org_path ON org (path text_pattern_ops);   -- 让 LIKE 'prefix%' 走索引
```

- 等值列在前（`status`），范围/排序列在后（`enrolled_at`），末尾带 `id` 让排序键唯一，为 Step 4 的游标分页做准备。
- `text_pattern_ops` 是 PG 在非 C locale 下让 `LIKE 'xx%'` 使用 B-tree 的前提，容易漏。
- 观察点：`Seq Scan on enrollment` 应变为 `Index Scan using idx_enrollment_status_time`，Sort 节点消失（索引顺序即排序顺序）。

### Step 2：改写关联顺序——先缩小人员集合

组织筛选实际是在 `employee` 上过滤，让优化器先算出"该组织下有哪些人"再去关联报名，比反过来便宜得多：

```sql
WITH emp_scope AS (
    SELECT emp.id, emp.emp_no, emp.name, emp.org_id
    FROM employee emp
    WHERE emp.org_id IN (SELECT id FROM org WHERE path LIKE '/1/12/%')
      AND (emp.name LIKE '张%' OR emp.emp_no LIKE '张%')     -- 见 Step 5，先改为前缀匹配
)
SELECT ...
FROM enrollment e
JOIN emp_scope emp ON emp.id = e.user_id
JOIN course c ON c.id = e.course_id
WHERE e.status = 1 AND e.enrolled_at BETWEEN ... 
ORDER BY e.enrolled_at DESC, e.id DESC
OFFSET 20000 LIMIT 20;
```

- 观察点：计划顶层是否变为 `Nested Loop`/`Hash Join` 以 `emp_scope` 为内表；`enrollment` 侧的 `rows` 估算是否接近实际。
- 如果优化器仍然选错顺序，用 `MATERIALIZED` 提示 CTE 或调整 `join_collapse_limit` 验证，但**不要把 hint 当最终方案**，先看统计信息是否过期（`ANALYZE`）。

### Step 3：先查主键再回表（延迟关联）

分页时真正需要排序和跳过的只有 `enrollment.id`，宽行的拼装只对最终 20 行做：

```sql
SELECT e.id, emp.emp_no, emp.name, o.name, c.title, e.status, e.enrolled_at
FROM (
    SELECT e.id, e.enrolled_at
    FROM enrollment e
    WHERE e.status = 1 AND e.enrolled_at BETWEEN ... 
      AND e.user_id IN (SELECT id FROM emp_scope)
    ORDER BY e.enrolled_at DESC, e.id DESC
    OFFSET 20000 LIMIT 20
) page
JOIN enrollment e ON e.id = page.id
JOIN employee emp ON emp.id = e.user_id
JOIN org o        ON o.id = emp.org_id
JOIN course c     ON c.id = e.course_id
ORDER BY page.enrolled_at DESC, page.id DESC;
```

- 内层只碰索引列 + `user_id`。把 `user_id` 加进索引 `INCLUDE (user_id)` 后，内层可以走 **Index Only Scan**（`Heap Fetches` 应接近 0，前提是表刚 `VACUUM` 过，可见性映射干净）。

```sql
DROP INDEX idx_enrollment_status_time;
CREATE INDEX idx_enrollment_status_time ON enrollment (status, enrolled_at DESC, id DESC) INCLUDE (user_id);
```

- 观察点：`Index Only Scan` + `Heap Fetches: 0`；外层 4 表关联的 `rows=20`。
- 这一步对深页效果最明显：OFFSET 跳过的 2 万行不再触发回表和拼宽行。

### Step 4：游标分页替代 OFFSET（解决深分页）

Step 3 之后深页仍要在索引上扫过 20000 条；对"下一页"这种典型交互，改为游标：

```sql
WHERE e.status = 1 AND e.enrolled_at BETWEEN ...
  AND (e.enrolled_at, e.id) < (#{lastEnrolledAt}, #{lastId})   -- 行值比较，PG 原生支持
ORDER BY e.enrolled_at DESC, e.id DESC
LIMIT 20
```

- 接口层：响应里返回 `nextCursor`（base64 的 `enrolledAt|id`），前端传回；同时保留 `page` 参数兼容"跳到第 N 页"（走 Step 3 的 OFFSET 路径，并限制最大 OFFSET，如 10000，超出提示缩小条件——这是产品约束，不是技术回避）。
- 观察点：第 1 页和第 1000 页耗时曲线持平；`Index Only Scan` 的 `rows` 恒为 20 左右。
- 附带收益：翻页期间有新报名插入不会出现重复/漏行（OFFSET 分页的固有问题）。

### Step 5：模糊搜索（前导通配符）

`LIKE '%张%'` 无法走 B-tree。按业务真实需求分流：
- 工号：几乎总是前缀查询 → 改为 `LIKE '张%'` 走 `text_pattern_ops` 索引。
- 姓名：确需包含匹配 → `pg_trgm` 扩展 + GIN 索引：

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_employee_name_trgm ON employee USING gin (name gin_trgm_ops);
```

- 观察点：`Bitmap Index Scan on idx_employee_name_trgm`。中文两字姓名的 trigram 选择性偏低，测试时对比 2 字 / 3 字关键词的差异并如实记录。

### Step 6：count 优化

分页器需要总数，但 count 和列表一样贵。三个档位，按产品需要选：

| 方案 | 做法 | 适用 |
|---|---|---|
| 精确 count 复用索引 | `SELECT COUNT(*)` 走 Step 3 的 Index Only Scan，不做 4 表 JOIN（count 不需要宽行） | 结果集 < 10 万 |
| 估算 | `EXPLAIN` 取 `rows` 估算值，或 `pg_class.reltuples` 比例 | 结果集很大、只需展示"约 x 条" |
| 上限截断 | `SELECT COUNT(*) FROM (... LIMIT 10001)`，超过显示"10000+" | 通用，Google 式 |

推荐 **精确 count 走覆盖索引 + 上限截断** 组合：一条索引扫描，最多扫 10001 行。

### Step 7：应用层收尾

- MyBatis 动态 SQL：条件为空时不拼接（尤其 `LIKE`），否则优化器无法用索引。
- 时间范围强制：不传 `from/to` 时默认最近 3 个月，避免全量时间扫描（HR 系统常见约定）。
- 组织子树 ID 列表可缓存（组织变动频率低），把 `IN (SELECT ... FROM org ...)` 变成参数列表，进一步减少关联。
- 慢 SQL 可观测：`log_min_duration_statement = 200ms` + `pg_stat_statements`，优化后验证接口不再出现在 top 列表。

---

## 5. 预期计划形态（优化完成的判定标准）

最终列表 SQL 的 `EXPLAIN (ANALYZE, BUFFERS)` 应满足：

- 顶层 `Limit` 下面是 `Nested Loop`，驱动侧为 `Index Only Scan using idx_enrollment_status_time`，`Heap Fetches: 0`
- 不存在 `Seq Scan`（`org` 这种几百行的小表除外）、不存在 `Sort` 节点
- `Buffers: shared hit` 在几百块量级，`read` 接近 0（热数据）
- `actual rows` 与 `rows` 估算同量级（统计信息准确）

---

## 6. 复现脚本

```
scripts/query-opt/
├── 01_schema.sql          # org / employee 建表
├── 02_gen_data.sql        # generate_series 造数 + ANALYZE
├── 03_baseline.sql        # v0 SQL，3 组参数（宽条件/窄条件/深页）
├── 04_step1_index.sql ... 09_step6_count.sql
└── bench.sh               # 每条 SQL 跑 5 次取中位数，输出 markdown 表格
```

`bench.sh` 核心：

```bash
for f in "$@"; do
  for i in 1 2 3 4 5; do
    psql -qAt -c "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $(cat $f)" \
      | jq -r '.[0]["Execution Time"]'
  done | sort -n | sed -n 3p     # 中位数
done
```

每一步产出一行记录，最终汇总成表：

| 步骤 | 首页 (ms) | 第 1000 页 (ms) | count (ms) | shared hit | 关键计划节点 |
|---|---|---|---|---|---|
| v0 基线 | | | | | Seq Scan + Sort |
| Step1 索引 | | | | | Index Scan |
| Step2 关联顺序 | | | | | |
| Step3 延迟关联 | | | | | Index Only Scan |
| Step4 游标 | | | | | |
| Step5 trgm | | | | | Bitmap Index Scan |
| Step6 count | – | – | | | |

数据要在**同一台机器、同一份数据、缓存预热后**测，冷缓存单独记一组作为参考。

---

## 7. 面试时可能被追问的点（提前准备）

- 为什么 `(status, enrolled_at, id)` 而不是 `(enrolled_at, status)`：等值列在前，范围列在后，才能让索引同时做过滤和排序。
- Index Only Scan 为什么有时 `Heap Fetches` 不为 0：可见性映射未更新，需要 VACUUM；高频更新的表这个收益会打折。
- 游标分页的局限：不能跳页、排序键必须唯一且稳定、排序方式变化时需要不同索引。
- `pg_trgm` 对短中文的选择性问题、写入放大。
- count 为什么不能靠 `reltuples`：那是全表估算，带条件时不可用；带条件的估算只能取 `EXPLAIN` 的 rows。
- 优化前后的数据量、机器配置、是否预热——数字要能说清楚测试条件。

---

## 参考

- [PostgreSQL 官方文档：Index-Only Scans and Covering Indexes](https://www.postgresql.org/docs/current/indexes-index-only-scans.html)
- [Why You Should Avoid LIMIT OFFSET for Pagination in PostgreSQL](https://eagleeye.com/blog/why-you-should-avoid-limit-offset-for-pagination-in-postgresql)：OFFSET 深页的执行计划分析
- [Optimizing SQL Pagination in Postgres (ReadySet)](https://readyset.io/blog/optimizing-sql-pagination-in-postgres)：复合键 keyset 分页
- [Efficient pagination in YugabyteDB / PostgreSQL (Franck Pachot)](https://dev.to/yugabyte/efficient-pagination-in-yugabytedb-postgresql-4h5a)：索引列 = 搜索键 + 排序键 + 主键 的设计准则
- [PostgreSQL 分页、offset、扫描方法原理（阿里云德哥）](https://developer.aliyun.com/article/746265)：各类扫描方式与 OFFSET 重复数据问题
