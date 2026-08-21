# DocQuery N5.2-R1 检索 PILOT 报告

- 状态：`COMPLETED`
- 生成时间：`2026-08-19T16:27:59.282678+00:00`
- PILOT：10 道可回答题，30 个逻辑检索请求
- KnowledgeBase：`1`
- Ranking：`retrieve-ranking-v1`，`topK=10`
- Answer / DeepSeek：未调用

## 三种模式

| 模式 | 成功 | 降级 | Doc R@5 | Page R@10 | MRR@10 | P95 ms |
|---|---:|---:|---:|---:|---:|---:|
| KEYWORD | 10/10 | 0 | 0.9000 | 1.0000 | 0.7458 | 1979.021 |
| SEMANTIC | 10/10 | 0 | 0.9000 | 0.8000 | 0.8667 | 1222.019 |
| HYBRID | 10/10 | 0 | 1.0000 | 1.0000 | 0.9333 | 503.257 |

## 边界

该报告只代表固定 10 道 PILOT 在当前 100 份 READY PDF、当前 Application Grant、activeVersion 快照和 `retrieve-ranking-v1` 下的真实外部 HTTP 结果。PILOT 不是剩余 54 道正式验收分数，不包含 Answer 质量、DeepSeek 成本或并发压测。
