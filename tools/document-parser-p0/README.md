# DeepDoc P0 容器说明

DeepDoc CPU/GPU 服务统一定义在项目根目录 `compose.yaml`，不会由默认 `docker compose up` 启动：

- `deepdoc-p0-cpu`：profile 为 `deepdoc-p0-cpu`；
- `deepdoc-p0-gpu`：profile 为 `deepdoc-p0-gpu`。

本目录只保存容器构建和运行代码：

- `Dockerfile.gpu`：在固定 RAGFlow `v0.26.4` 基础镜像上安装固定 PyTorch CUDA 12.6 依赖；
- `run_deepdoc_p0.py`：校验输入指纹、运行 DeepDoc、记录实际 ONNX provider、位置覆盖、耗时和内存，不保存正文。

固定样本和报告分别位于：

- `evaluation/mmlongbench-docquery-v1/deepdoc-p0-sample-2.json`；
- `evaluation/mmlongbench-docquery-v1/reports/`。

GPU 复现命令：

```powershell
docker compose --profile deepdoc-p0-gpu build deepdoc-p0-gpu
docker compose --profile deepdoc-p0-gpu up --abort-on-container-exit deepdoc-p0-gpu
docker compose --profile deepdoc-p0-gpu rm -f deepdoc-p0-gpu
```

复现报告写入 `deepdoc-p0-report-gpu-replay.json`，不会覆盖本次冻结的 `deepdoc-p0-report-gpu.json`。运行时容器断网；如果实际 ONNX session 不包含 `CUDAExecutionProvider`，runner 会在解析前失败，不能静默回落 CPU。
