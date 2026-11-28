# Docker 依赖部署说明

当前 [compose.yaml](compose.yaml) 负责启动 PostgreSQL、Milvus、etcd 和 MinIO。后端和前端按主 README 的步骤单独启动或发布。

部署前在根目录的本地 `.env` 中设置 `POSTGRES_PASSWORD`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`。数据库卷位置通过 `DOCKER_VOLUME_DIRECTORY` 指定。实际凭据和业务数据应保存在受控环境。

```bash
docker compose config --quiet
docker compose up -d
docker compose ps
```

依赖健康后，继续完成模型服务、后端、前端和验证步骤，详见 [本地部署](README.md#本地部署) 与 [服务器发布](README.md#服务器发布)。

根目录 `Dockerfile` 仍引用缺失的 Maven Wrapper 和根目录构建文件，需先调整才能构建应用镜像。Compose 当前没有应用容器，执行上述命令后不能据此认为后端已在监听。

已有资料迁移时保留数据库、MinIO 凭据和卷目录，并同时备份应用原件及报告目录。完整配置、备份和排查方法见 [主 README](README.md)。
