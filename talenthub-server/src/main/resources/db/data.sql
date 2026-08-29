-- 演示种子课程：时间相对应用启动时刻生成，便于直接演示倒计时 / 抢课 / 已结束三种形态。
-- ON CONFLICT DO NOTHING：重复启动不重复插入（旧数据时间可能过期，可清库重启刷新）。
INSERT INTO course (id, title, total_quota, stock, enroll_start, enroll_end, status)
    OVERRIDING SYSTEM VALUE
VALUES
    (1, 'PostgreSQL 高并发实战（名额 20）',  20, 20, now() - interval '5 minutes', now() + interval '2 hours', 0),
    (2, 'Redis 缓存一致性设计（名额 5）',     5,  5, now() + interval '3 minutes', now() + interval '2 hours', 0),
    (3, '往期课程：Java 17 新特性（已结束）', 30, 12, now() - interval '2 days',    now() - interval '1 day',   2)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('course', 'id'), GREATEST((SELECT MAX(id) FROM course), 1));
