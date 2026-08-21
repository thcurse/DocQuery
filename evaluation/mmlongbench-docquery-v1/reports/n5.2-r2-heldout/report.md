# DocQuery N5.2-R2 检索保留集报告

- 状态：`COMPLETED`
- 生成时间：`2026-08-21T07:30:23.914461+00:00`
- 正式 held-out：54 道，HYBRID，topK=10
- 成功：54/54，降级：0，重试：0
- Answer / DeepSeek：未调用

## R2 held-out 54 主结果

| Doc Hit@5 | Page Coverage@10 | MRR@10 | Any Page Hit@10 | All Pages Hit@10 | P50 ms | P95 ms |
|---:|---:|---:|---:|---:|---:|---:|
| 0.7407 | 0.5593 | 0.6181 | 0.6296 | 0.4815 | 314.883 | 451.525 |

## R1+R2 64 题补充汇总

> 该汇总仅用于描述；R1 PILOT 已用于选择 HYBRID，不能冒充纯 held-out 分数。

| 题数 | Doc Hit@5 | Page Coverage@10 | MRR@10 |
|---:|---:|---:|---:|
| 64 | 0.7812 | 0.6021 | 0.6673 |

## 边界

R2 只评估当前 100 份 READY PDF 上的顺序 HYBRID 检索，不包含 Answer 质量、不可回答题、并发容量、扫描件 OCR、复杂表格语义、精确供应商 Token 或金额。
