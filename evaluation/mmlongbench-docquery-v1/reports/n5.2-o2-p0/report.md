# DocQuery N5.2-O2-P0 评测口径修正报告

- 状态：`COMPLETED`
- 25题只按问题文本的可定位性筛选，不改写、不生成问题
- 使用冻结的 R1/R2 HYBRID 原始响应重新计分，付费请求：0
- 相同 SHA-256 文档按同一内容来源计分
- Gold 印刷页通过 PDF Page Labels 映射到物理页
- 本报告是已观察结果后的诊断子集，不替代 R2 held-out 报告

| 口径 | Doc Hit@5 | Doc Hit@10 | Page Coverage@10 | Any Page Hit@10 | MRR@10 |
|---|---:|---:|---:|---:|---:|
| 原冻结算法 | 0.9600 | 0.9600 | 0.8200 | 0.8400 | 0.9200 |
| 修正口径 | 0.9600 | 0.9600 | 0.8200 | 0.8400 | 0.9200 |

发生非恒等页码映射的入选题：mmlongbench-0093, mmlongbench-0908。

页码修正逐题影响：

- `mmlongbench-0093`：Any Page Hit 1 → 0，Page Coverage 1.0000 → 0.0000，映射 {'11': 13, '12': 14}
- `mmlongbench-0908`：Any Page Hit 0 → 1，Page Coverage 0.0000 → 1.0000，映射 {'28': 30}
