# DocQuery N5.3 v22 正常文本 40 题累计评测

- 状态：`COMPLETED`
- 主评测：40 个互不重复的事实，19 份文档，不传 `documentIds`
- 配置：HYBRID，topK=10，answer-policy-v22
- 样本构成：首批 20 道原始且对象明确的公开题；第二批 20 道公开事实仅补充正常用户会提供的公司、人物、案件、机构、主题或时间范围
- 第二批不提供文档标题、文件名、页码、documentIds、答案或隐藏检索提示；不属于官方原题榜单结果

| 请求成功 | 正常作答 | 人工正确 | 不受引用支持 | 自动参考匹配 | 正确文档引用 | Gold 页引用 | P50 | P95 | 平均耗时 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 40/40 | 38/40 | 38/40 | 0 | 29/40 | 38/40 | 38/40 | 12119.553 ms | 22062.067 ms | 12436.585 ms |

## 仍存在的失败

1. `mmlongbench-text-0364`：问题只给出公司实体 Godfrey Phillips India Limited；单页存在完整银行名单，Agent 返回 `INSUFFICIENT_EVIDENCE`。
2. `mmlongbench-text-0700`：问题给出 Diane Hanson 与 Delaware State Public Integrity Commission 案件实体；第一页存在 `Filing ID 48897809` 和 `Case Number 5152012`，Agent 返回 `INSUFFICIENT_EVIDENCE`。

当前证据表明，正常正文、正文列表、定义、金额、日期、程序步骤和简单计算的整体效果稳定；剩余失败分别涉及多项名单和页首元数据，表现为证据充分但过早拒答。

## 评测设计诊断

原始第二批公开题未传 `documentIds`，却大量使用“这个文档、第 14 页、这个案件、这张幻灯片”等未解析指代，原始正确率仅 11/20，保留在 `../n5.3-a33-v22-normal-text20-b/`。明确具体文档的受控对照为 18/20，保留在 `../n5.3-a34-v22-contextualized-text20-c/`。两者都不计入累计 40 题主结果；主结果使用 `../n5.3-a36-v22-entity-scoped-text20-d/`。
