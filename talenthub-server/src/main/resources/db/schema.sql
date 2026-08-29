-- DDL 与业务设计文档 docs/course-enrollment-design.md §2 保持一致

CREATE TABLE IF NOT EXISTS course (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title         VARCHAR(128) NOT NULL,
    total_quota   INT          NOT NULL,
    stock         INT          NOT NULL CHECK (stock >= 0),
    enroll_start  TIMESTAMPTZ  NOT NULL,
    enroll_end    TIMESTAMPTZ  NOT NULL,
    status        SMALLINT     NOT NULL DEFAULT 0,
    version       INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
) WITH (fillfactor = 70);   -- 热点行高频 UPDATE，页内预留空间促成 HOT 更新（业务设计 §2.2）

COMMENT ON TABLE  course              IS '培训课程';
COMMENT ON COLUMN course.total_quota  IS '总名额';
COMMENT ON COLUMN course.stock        IS '剩余名额';
COMMENT ON COLUMN course.status       IS '0-未开始 1-报名中 2-已结束 3-已取消';

ALTER TABLE course SET (
    autovacuum_vacuum_scale_factor = 0.01,
    autovacuum_vacuum_cost_delay   = 0
);

CREATE TABLE IF NOT EXISTS enrollment (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    course_id   BIGINT      NOT NULL,
    status      SMALLINT    NOT NULL DEFAULT 1,
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    canceled_at TIMESTAMPTZ NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ★ 风险点2的DB防线：同一用户同一课程物理上只允许一条记录
    CONSTRAINT uk_user_course UNIQUE (user_id, course_id)
);
CREATE INDEX IF NOT EXISTS idx_enrollment_course ON enrollment (course_id);

COMMENT ON TABLE  enrollment        IS '报名记录';
COMMENT ON COLUMN enrollment.status IS '1-已报名 2-已取消';

-- 对账修正审计日志（业务设计 §5：对账任务不允许静默纠错）
CREATE TABLE IF NOT EXISTS reconcile_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    course_id   BIGINT      NOT NULL,
    redis_stock BIGINT      NULL,
    db_stock    INT         NOT NULL,
    action      VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE reconcile_log IS '库存对账修正记录';
