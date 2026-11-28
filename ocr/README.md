# Roch 本机扫描页识别

在仓库根目录运行：

```powershell
docker compose -f compose.ocr.yaml up -d --build --wait
Invoke-RestMethod http://127.0.0.1:18765/health
```

首次构建下载固定版本的 Linux amd64 Python 依赖和 Paddle 模型。模型归档在构建阶段校验 SHA-256，运行时从镜像内的目录加载。已构建的镜像可离线启动。容器通过回环地址对宿主机提供服务，OCR 进程禁止主动建立连接或使用 IP 数据报。设置 `ROCH_OCR_PORT` 可更改宿主机端口，后端的 `RAG_OCR_BASE_URL` 需同步更新。

`POST /v1/recognize` 接收一个 `image/png` 页面。服务最多处理一个识别请求，限制请求大小为 32 MiB，图像为 2400 万像素且长边不超过 10000 像素。响应包含 `engineVersion`、`rotation`、`blocks` 和 `tables`。坐标以原始图片左上角为原点，归一化到 0–1。页面纠正方向后，单元格行列号按阅读顺序排列，坐标映射回输入图片。模型的行列跨度与边框无法共同支持结构时，保留页内 OCR 文字。

无需模型或后端即可运行协议和结构适配测试：

```powershell
$env:PYTHONPATH = "ocr"
$env:PYTHONDONTWRITEBYTECODE = "1"
python -m unittest discover -s ocr/tests -v
```
