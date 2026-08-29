# TalentHub 培训课程报名演示系统

内部 toB 类秒杀业务演示：Redis 限流 + Lua 原子预扣 + PostgreSQL 条件更新防超卖 + 对账补偿。

- 业务与一致性设计：[docs/course-enrollment-design.md](docs/course-enrollment-design.md)
- 工程设计与编码约束：[docs/system-design.md](docs/system-design.md)

## 快速启动

```bash
# 1. 基础设施（PostgreSQL 16 + Redis 7）
docker compose up -d

# 2. 后端（首次启动自动建表并写入演示课程）
cd talenthub-server && mvn spring-boot:run
# Swagger: http://localhost:8080/swagger-ui.html

# 3. 前端
cd talenthub-web && npm install && npm run dev
# 页面: http://localhost:5173 （右上角切换演示用户，管理员可见管理端）

# 4. 并发压测演示（对课程 1 发起 200 用户 / 50 并发抢课）
./scripts/bench/enroll_bench.sh 1 200 50
```

## 演示剧本

1. **防超卖**：压测后报名数恰好等于总名额，`stock = 0`，Redis 与 DB 一致。
2. **幂等去重**：同一用户反复点击/并发请求，只产生一条报名记录。
3. **对账兜底**：手动改坏 Redis 库存（`docker exec talenthub-redis redis-cli SET course:stock:1 99`），30 秒内对账任务修正并在管理端"对账记录"落审计日志。
4. **取消回补**：取消报名后名额回补，其他用户可继续抢；重新报名走 upsert。
