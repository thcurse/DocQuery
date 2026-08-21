# DocQuery N3 确定性冒烟夹具 v1

`n3-eval-v1` 是完全虚构、可随仓库分发的中文企业文档夹具，用于验证格式解析、评测数据映射、拒答和引用机械校验。它不是正式质量 Gold Set，不得用于声称真实检索或回答效果，也不代表任何真实企业政策。

## 固定规模

- 12 份文档：PDF、DOCX、Markdown、TXT 各 3 份；
- 40 条问题：24 条 development、16 条 acceptance；
- 24 条单证据、8 条多证据、8 条不可回答；
- acceptance 分片在调参期间不得用于选择参数，只用于阶段门禁复验。

`manifest.json` 固定文档身份、展示名、大小和 SHA-256；`cases.jsonl` 每行一条夹具问题。`checksums.sha256` 用于发现二进制语料漂移。

## 夹具语义

- `relevance[].documentKey` 指向 `manifest.json` 中的文档；
- `anchorTexts` 是必须能在标准化原文中定位的证据锚点；匹配时允许空白归一化，不允许同义改写替代；
- `requiredClaims` 只用于 Answer 人工/规则复核，不把字符串完全相等当成语义正确；
- `NOT_ANSWERABLE` 的金标证据必须为空，期望 `/answer` 返回受控拒答且不伪造引用。

## 使用顺序

1. 运行 `tools/evaluation/build_n3_eval_corpus.py` 仅用于可重复构建二进制语料；重建会改变 DOCX/PDF 的字节和校验和，必须作为数据集版本变更评审。
2. 在专用 KnowledgeBase 中按 `manifest.json` 上传 12 份文件并等待全部 READY。
3. 冻结该知识库的 `knowledgeBaseId`、应用凭证和运行配置。
4. 先跑 development；确定参数后再跑 acceptance。
5. 原始响应、逐题判定和汇总报告写入独立输出目录，不回写金标。

## v1 边界

- 数据集结构和锚点完整性：100%；
- 指标计算器和报告结构必须可重复；
- 不设置或宣称 Recall、MRR、拒答率等质量门槛；
- 正式效果评测在完整功能落地后使用成熟公开数据集和真实供应商另行执行。
