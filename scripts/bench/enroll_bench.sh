#!/usr/bin/env bash
# 并发抢课压测：验证防超卖与幂等（工程设计 §5 演示剧本 2）
# 用法: ./enroll_bench.sh <courseId> [请求数=200] [并发=50] [起始用户ID=1000]
set -euo pipefail

COURSE_ID=${1:?用法: enroll_bench.sh <courseId> [请求数] [并发] [起始用户ID]}
REQUESTS=${2:-200}
CONCURRENCY=${3:-50}
START_UID=${4:-1000}
BASE_URL=${BASE_URL:-http://localhost:8080}

export COURSE_ID BASE_URL
OUT=$(mktemp)

echo "== 课程 $COURSE_ID: $REQUESTS 个用户 / 并发 $CONCURRENCY =="
seq "$START_UID" $((START_UID + REQUESTS - 1)) | xargs -P "$CONCURRENCY" -n 1 -I{} \
    sh -c 'curl -s -X POST -H "X-User-Id: {}" "$BASE_URL/api/courses/$COURSE_ID/enroll"; echo' \
    > "$OUT"

echo "== 响应码分布（0=成功, 41001=名额已满, 42900=限流, 见 ResultCode）=="
grep -o '"code":[0-9]*' "$OUT" | sort | uniq -c | sort -rn
rm -f "$OUT"

echo
echo "== 验证（预期：报名数 == 总名额-剩余库存，且无超卖）=="
echo "docker exec talenthub-postgres psql -U talenthub -c \\"
echo "  \"SELECT c.stock, c.total_quota, COUNT(e.id) AS enrolled FROM course c"
echo "   LEFT JOIN enrollment e ON e.course_id = c.id AND e.status = 1"
echo "   WHERE c.id = $COURSE_ID GROUP BY c.id;\""
