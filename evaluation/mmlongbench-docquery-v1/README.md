# mmlongbench-docquery-v1

这是 MMLongBench-Doc 的 DocQuery 确定性非商业评测子集：100 份真实原始 PDF、64 个纯文本证据可回答问题和 16 个不可回答问题。PDF 均来自上游锁定快照，DocQuery 不生成、不转换、不改写这些文件。

正式来源：[MMLongBench-Doc 官方仓库](https://github.com/mayubo2333/MMLongBench-Doc)。上游数据仅限研究用途并采用 CC BY-NC 4.0；本产物只用于 DocQuery 的非商业评测与秋招展示，不得作为商用语料重新分发。

本子集只选择官方标为 `Pure-text (Plain-text)` 的可回答题，排除依赖图片、图表、表格或版面坐标的问题；不可回答题保留官方空证据标注。它不是官方完整榜单结果，也不证明 OCR、扫描件、复杂表格、视觉问答或 DOCX 质量。

由于上游纯文本可回答题只覆盖 60 份不同 PDF，80 个 case 不强制一题一文档。选择器优先扩大 case 文档覆盖面，再从同一公开数据集确定性补足到 100 份 PDF；实际 CASE_SOURCE 与 DISTRACTOR 数量记录在 manifest。

复现（不调用模型或供应商）：

```powershell
python tools/evaluation/build_mmlongbench_subset.py all `
  --cache tmp/pdfs/mmlongbench-source `
  --output evaluation/mmlongbench-docquery-v1
```

`source-annotations.jsonl` 保存1091条官方标注的规范化快照；PDF 校验和位于`corpus-checksums.sha256`。上传时保留原始文件名，评测器按 manifest 的`displayName` 映射稳定 documentKey。

2026-08-17 P2 真实入库时，原 DISTRACTOR `mmdetection-readthedocs-io-en-v2.18.0.pdf` 因 468 页解析峰值超过 12 GiB 容器边界，且没有冻结 case 引用，经用户明确确认后替换为同一上游固定快照中的 24 页真实 DISTRACTOR `NUS-Business-School-BBA-Brochure-2024.pdf`。旧文件保存在 `excluded/` 作为排除证据；80 个 case 未改变，manifest 和校验和已同步。
