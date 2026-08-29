# TalentHub 抢课系统 —— 工程设计方案（技术栈 / 项目结构 / 编码约束）

> 版本：v1.0（2026-08-29）
> 配套文档：[course-enrollment-design.md](./course-enrollment-design.md)（业务与数据一致性设计，本文不重复其内容）
> 定位：内部 toB 抢课业务的**可运行演示系统**，代码质量按生产标准约束，部署形态按单机演示简化。

---

## 1. 技术栈选型

### 1.1 后端

| 组件 | 选型 | 说明 |
|---|---|---|
| 语言 / 运行时 | Java 17 | Spring Boot 3 的基线版本 |
| 框架 | Spring Boot 3.x | Web、校验、调度一站式 |
| ORM | MyBatis 3 + mapper.xml | 按用户要求：mapper 接口定义操作，XML 写 SQL，SQL 显式可控（条件 UPDATE、upsert 这类关键 SQL 不适合被 ORM 隐藏） |
| 数据库 | PostgreSQL 15+ | 见业务设计文档 §2 |
| Redis 客户端 | Spring Data Redis（Lettuce） | `DefaultRedisScript` 加载执行 Lua，启动时预加载 |
| 接口限流 | 自实现 Redis 固定窗口/令牌桶（Lua） | 演示系统不引入 Sentinel/网关，限流逻辑收敛在 service 层，与业务设计文档 §7 对应 |
| 定时任务 | Spring `@Scheduled` | 对账任务、库存预热任务；单机演示不引入 xxl-job/Quartz |
| API 文档 | springdoc-openapi | 自动生成 Swagger UI，演示时直接可视化调接口 |
| 构建 | Maven | 单模块即可，不做多模块拆分（演示规模下多模块是负收益） |
| 辅助 | Lombok、MapStruct（可选） | Lombok 只允许 `@Getter/@Setter/@Builder/@Slf4j/@RequiredArgsConstructor`，禁用 `@Data`（避免可变实体的 equals/hashCode 陷阱） |

### 1.2 前端

| 组件 | 选型 | 说明 |
|---|---|---|
| 框架 | Vue 3（Composition API + `<script setup>`） | 全项目统一 setup 写法，不混用 Options API |
| UI 库 | Arco Design Vue | 表格、表单、消息提示全部优先用 Arco，禁止重复造轮子 |
| 语言 | TypeScript（strict） | 所有 API 出入参有类型定义 |
| 构建 | Vite | |
| 状态管理 | Pinia | 只存跨页面共享状态（当前用户、课程列表缓存），页面内状态一律 `ref/reactive` 本地管理 |
| 路由 | Vue Router 4 | |
| HTTP | Axios（统一封装） | 拦截器处理统一响应体、错误消息（Arco Message）、模拟登录头 |

### 1.3 演示形态说明

- **登录简化**：不做真实认证。前端提供"用户切换器"（下拉选择演示用户），请求头带 `X-User-Id`；后端拦截器解析后放入上下文。**水平鉴权接口正常预留并被调用**（见 §3.2），只是实现类直接放行——这样鉴权逻辑的"位置"是对的，将来替换实现即可。
- **并发演示**：提供 `scripts/bench/` 下的压测脚本（如 `k6` 或简单 shell + curl 并发），演示抢课时的超卖防护效果。
- 基础设施：`docker-compose.yml` 一键拉起 PostgreSQL + Redis。

---

## 2. 项目结构

```
TalentHub/
├── docs/                                # 设计文档（已有）
├── docker-compose.yml                   # PostgreSQL + Redis
├── scripts/
│   └── bench/                           # 并发压测演示脚本
├── talenthub-server/                    # 后端（Spring Boot 3，单模块）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/talenthub/
│       │   ├── TalentHubApplication.java
│       │   ├── common/                  # 与业务无关的通用件
│       │   │   ├── Result.java          #   统一响应体 Result<T>
│       │   │   ├── ResultCode.java      #   业务码枚举
│       │   │   ├── BizException.java    #   业务异常（携带 ResultCode）
│       │   │   ├── GlobalExceptionHandler.java
│       │   │   └── UserContext.java     #   当前用户上下文（ThreadLocal）
│       │   ├── config/                  # 配置类
│       │   │   ├── RedisConfig.java     #   RedisTemplate、Lua 脚本 Bean 预加载
│       │   │   ├── WebConfig.java       #   拦截器注册（用户上下文解析）
│       │   │   └── AsyncConfig.java     #   调度线程池
│       │   ├── controller/              # 仅做：参数校验 + 水平鉴权 + 调 service + 返回 Result
│       │   │   ├── CourseController.java
│       │   │   ├── EnrollmentController.java
│       │   │   └── AdminCourseController.java
│       │   ├── service/                 # 接口
│       │   │   ├── AuthService.java     #   ★ 水平数据鉴权接口（预留）
│       │   │   ├── CourseService.java
│       │   │   ├── EnrollmentService.java
│       │   │   ├── StockCacheService.java   # Redis 库存操作的唯一入口（Lua 调用封装）
│       │   │   └── RateLimitService.java    # 限流接口
│       │   ├── service/impl/
│       │   ├── mapper/                  # MyBatis 接口
│       │   │   ├── CourseMapper.java
│       │   │   └── EnrollmentMapper.java
│       │   ├── job/                     # 定时任务
│       │   │   ├── StockReconcileJob.java   # 对账补偿（业务文档 §5）
│       │   │   └── StockPreheatJob.java     # 库存预热（业务文档 §6）
│       │   └── model/
│       │       ├── entity/              # 与表一一对应，仅 mapper 层出入
│       │       ├── dto/                 # 入参（controller 接收，带校验注解）
│       │       └── vo/                  # 出参（返回前端，永不直接返回 entity）
│       └── resources/
│           ├── application.yml
│           ├── mapper/                  # mapper.xml（与接口同名）
│           │   ├── CourseMapper.xml
│           │   └── EnrollmentMapper.xml
│           ├── scripts/redis/           # Lua 脚本（业务文档 §3）
│           │   ├── enroll.lua
│           │   ├── rollback.lua
│           │   └── rate_limit.lua
│           └── db/
│               ├── schema.sql           # DDL（与业务文档 §2 一致）
│               └── data.sql             # 演示种子数据（用户、课程）
└── talenthub-web/                       # 前端（Vue3 + Vite + TS）
    ├── package.json / vite.config.ts / tsconfig.json
    └── src/
        ├── main.ts
        ├── api/                         # 每个后端 controller 对应一个文件
        │   ├── request.ts               #   Axios 封装（拦截器、统一错误处理）
        │   ├── course.ts
        │   ├── enrollment.ts
        │   └── admin.ts
        ├── types/                       # API 出入参类型（与后端 DTO/VO 对齐）
        │   ├── course.ts
        │   └── enrollment.ts
        ├── stores/                      # Pinia
        │   └── user.ts                  #   当前演示用户
        ├── router/
        │   └── index.ts
        ├── components/                  # 公共组件（跨页面复用才放这里）
        │   ├── UserSwitcher.vue         #   演示用户切换器
        │   ├── CourseStatusTag.vue      #   课程状态标签（Arco Tag 封装）
        │   └── StockBadge.vue           #   剩余名额徽标
        ├── composables/                 # 可复用逻辑
        │   └── useCountdown.ts          #   开抢倒计时
        ├── views/                       # 页面（路由级组件）
        │   ├── course/
        │   │   ├── CourseListView.vue   #   课程列表 + 抢课入口
        │   │   └── CourseDetailView.vue
        │   ├── enrollment/
        │   │   └── MyEnrollmentView.vue #   我的报名 / 取消
        │   └── admin/
        │       └── AdminCourseView.vue  #   建课、改名额、手动预热、查看对账日志
        └── utils/
            └── format.ts                #   时间/数字格式化
```

---

## 3. 后端分层职责与编码约束

### 3.1 分层铁律（每层只做自己的事）

```
Controller ──► Service(接口) ──► ServiceImpl ──► Mapper(接口) ──► mapper.xml
```

| 层 | 允许做 | 禁止做 |
|---|---|---|
| controller | `@Valid` 参数校验；调用 `AuthService` 做水平鉴权；调用一个 service 方法；包装 `Result` | 任何业务逻辑、任何 Redis/Mapper 直接访问、try-catch 业务异常（交给全局处理器） |
| service | 限流、Redis 库存操作（经 `StockCacheService`）、业务编排、事务边界（`@Transactional` 只标注在"DB 事务段"的方法上） | 直接拼 SQL、感知 HTTP（不出现 HttpServletRequest/Response） |
| mapper | 接口方法定义 + 精确的方法名 | 默认方法里写逻辑、注解 SQL（统一走 XML） |
| mapper.xml | 具体 SQL | `select *`（必须显式列名）、业务无关的通用大而全 SQL |

### 3.2 水平数据鉴权（预留接口，位置先行）

```java
/** 水平数据权限校验。演示阶段实现类直接放行，接入真实权限体系时仅替换实现。 */
public interface AuthService {
    /** 校验当前用户是否可操作该报名记录（本人数据） */
    void checkEnrollmentOwner(long userId, long enrollmentId);
    /** 校验当前用户是否具备管理端操作权限 */
    void checkAdmin(long userId);
}
```

Controller 模板（这是全项目 controller 的标准形态，不允许更复杂）：

```java
@PostMapping("/{courseId}/enroll")
public Result<EnrollResultVO> enroll(@PathVariable long courseId) {
    long userId = UserContext.currentUserId();
    // 抢课操作的是"自己"的报名，无需 owner 校验；取消/查询他人数据的接口必须先调 authService
    return Result.ok(enrollmentService.enroll(userId, courseId));
}
```

鉴权不通过时 `AuthService` 抛 `BizException(ResultCode.FORBIDDEN)`，由全局异常处理器统一转为响应，controller 不感知。

### 3.3 关键编码约束

**通用**

- 统一响应体：所有接口返回 `Result<T>`（`code` / `message` / `data`），业务码集中在 `ResultCode` 枚举，禁止散落的魔法数字与裸字符串提示语。
- 异常：业务失败一律 `throw new BizException(ResultCode.XXX)`；`GlobalExceptionHandler` 统一兜底（含 `MethodArgumentNotValidException` → 参数错误响应）。service 内不吞异常。
- 命名：类 `UpperCamelCase`；方法/变量 `lowerCamelCase`；常量 `UPPER_SNAKE_CASE`；Redis key 常量集中在 `RedisKeys` 工具类（`course:stock:{id}` 等模板 + 拼装方法），禁止在业务代码里手写 key 字符串。
- DTO 入参加 Jakarta Validation 注解（`@NotNull`、`@Min` 等）；VO 出参按前端需要裁剪，**entity 永不出 service 层**。
- 注释：只写"代码表达不了的约束"（如"此处顺序必须先 upsert 后 UPDATE，见业务设计文档 §2.2"），不写复述代码的注释。关键一致性逻辑必须引用业务文档章节号。
- 时间统一 `OffsetDateTime`/`Instant`，数据库 `TIMESTAMPTZ` 对齐，禁止 `java.util.Date`。

**抢课主链路的落位（与业务文档对齐）**

| 业务文档设计点 | 代码落位 |
|---|---|
| 接口/用户级限流（§7） | `RateLimitService`，在 `EnrollmentServiceImpl.enroll()` 最先调用 |
| enroll.lua / rollback.lua（§3） | `StockCacheService` 封装，返回语义化枚举（`PRE_DEDUCTED / SOLD_OUT / DUPLICATE / NOT_PREHEATED`），**业务代码不接触裸的 Lua 返回码** |
| DB 事务段（§4.1 ③） | `EnrollmentServiceImpl` 内单独的 `@Transactional` 方法 `doEnrollInDb()`，方法内只有两条 mapper 调用；注意通过自注入/`TransactionTemplate` 规避同类内调用事务失效 |
| DB 失败同步回补（§4.3） | `enroll()` 在事务方法外 catch，按分支 A/B/C 处理，回补调用带 3 次重试 |
| 对账任务（§5） | `StockReconcileJob`，修正动作全部落审计日志表 `reconcile_log` |

- upsert、条件 UPDATE 等关键 SQL 写在 XML 中并配注释引用文档章节；mapper 方法名表达语义：`deductStock`（返回 affected rows）、`upsertEnrollment`、`restoreStock`。
- `@Transactional` 方法内禁止出现 Redis 调用、HTTP 调用、日志落库（业务文档 §2.2 短事务纪律的代码化）。

### 3.4 接口清单（REST 风格）

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/api/courses` | 课程列表（含剩余名额，读 Redis） | 登录即可 |
| GET | `/api/courses/{id}` | 课程详情 | 登录即可 |
| POST | `/api/courses/{id}/enroll` | 抢课报名 | 本人操作 |
| DELETE | `/api/courses/{id}/enroll` | 取消报名 | `checkEnrollmentOwner` |
| GET | `/api/enrollments/my` | 我的报名列表 | 本人数据 |
| POST | `/api/admin/courses` | 创建课程 | `checkAdmin` |
| PUT | `/api/admin/courses/{id}` | 修改课程/名额（走统一入口同步刷 Redis，文档 §6） | `checkAdmin` |
| POST | `/api/admin/courses/{id}/preheat` | 手动触发预热 | `checkAdmin` |
| GET | `/api/admin/reconcile-logs` | 对账修正记录 | `checkAdmin` |

---

## 4. 前端分层职责与编码约束

### 4.1 目录职责铁律

| 目录 | 职责 | 禁止 |
|---|---|---|
| `api/` | 每个文件对应一个后端 controller；只做请求定义与类型标注 | 写业务逻辑、组件内直接 import axios |
| `types/` | 与后端 DTO/VO 一一对齐的 interface | 组件内内联定义跨文件复用的类型 |
| `views/` | 路由级页面：组合 components/composables/api 完成页面 | 被其他组件 import（页面不是组件） |
| `components/` | **至少两个页面复用**才允许放入；单页面私有组件放该 view 同级 `components/` 子目录 | 预防性抽取（"以后可能用到"不是理由） |
| `composables/` | 可复用的响应式逻辑（`useXxx` 命名） | 无状态纯函数（那是 `utils/`） |
| `stores/` | 跨页面共享状态 | 页面本地状态入 store |

### 4.2 关键编码约束

- **复用优先级：Arco 组件 > 已有公共组件 > 新抽公共组件 > 页面私有实现**。列表用 `a-table`、表单用 `a-form` + Arco 校验规则、提示用 `Message`/`Notification`，不手写样式重造这些能力。
- 全部 `<script setup lang="ts">`；组件 props/emits 用类型化声明（`defineProps<T>()`）。
- `request.ts` 统一处理：注入 `X-User-Id` 头（从 Pinia user store 取）、解包 `Result<T>`（code ≠ 0 时统一 `Message.error` 并 reject）、返回类型直接是 `T`——**页面代码里不出现 `.data.data` 和重复的错误弹窗逻辑**。
- 抢课按钮交互约束：点击后立即置 loading 并禁用（防连点的前端第一道防线），根据业务码区分"已报名/名额已满/系统繁忙"的提示文案，与后端 `ResultCode` 对齐。
- 命名：组件文件 `PascalCase.vue`；composable `useXxx.ts`；路由级页面以 `View` 结尾。
- 样式：优先 Arco 的间距/栅格与 CSS 变量，自定义样式一律 `scoped`；禁止全局覆盖 Arco 样式（主题定制走 Arco 官方 token 配置）。

---

## 5. 演示剧本（系统要能讲出来的故事）

1. 管理员建课（名额 20）→ 手动预热 → 课程列表可见、倒计时组件工作。
2. 压测脚本模拟 200 并发抢课 → 最终 DB 报名数恰好 20、`stock = 0`、Redis 与 DB 一致 → **防超卖生效**。
3. 同一用户并发多次请求 → 只有一条报名记录 → **幂等去重生效**。
4. 手动制造不一致（直接改 Redis 库存）→ 对账任务 1 分钟内修正并落 `reconcile_log` → **补偿兜底生效**。
5. 取消报名 → 名额回补 → 另一用户可抢到 → 重新报名走 upsert 正常。

---

## 6. 实施顺序建议

1. 工程骨架 + docker-compose + schema.sql/data.sql（可跑通空应用）
2. common/config 通用件（Result、异常处理、UserContext、Redis 配置与 Lua 预加载）
3. 抢课主链路（enroll.lua → StockCacheService → EnrollmentService → mapper/XML）
4. 取消/查询接口 + 预热与对账 Job
5. 前端骨架（request.ts、路由、用户切换器）→ 课程列表/抢课页 → 我的报名 → 管理页
6. 压测脚本与演示剧本走查
