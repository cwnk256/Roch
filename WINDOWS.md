# Roch Windows 桌面版

运行 `Roch-Setup.exe`，然后从开始菜单打开 Roch。桌面版提供文档导入、问答、文件检索、总结、对比、引用核验和 PDF 导出。首次启动时创建本用户的独立桌面数据环境。

安装前可在 PowerShell 执行 `Get-FileHash .\Roch-Setup.exe -Algorithm SHA256`，与随包的 `Roch-Setup.exe.sha256` 对照。未签名验收包可能显示 Windows 的未知发布者提示。核对交付来源和校验值后再安装。

## 首次使用

1. 安装并启动 [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/)，使用 Linux 容器。Roch 启动页可创建自己的 PostgreSQL 和 Milvus 服务。首次拉取 Docker 镜像需要联网。端口已被占用时，在启动页修改连接端口后重试。
2. 安装 [LM Studio](https://lmstudio.ai/docs/app)，下载并加载一个支持结构化输出的生成模型，以及一个嵌入模型。启动本地服务，保持只监听本机，并让 Roch 使用该服务的本地地址。
3. 打开 Roch 的「设置」，刷新模型列表，分别测试嵌入和生成模型，再保存配置。如果更改了嵌入连接，使用应用内的重启入口。然后导入资料，在「总结 / 报告」中创建任务。

安装包包含应用所需的 Java 和 .NET 运行时。缺少 WebView2 Runtime 时，安装程序会引导安装。调用高德和 Firecrawl 须联网，可在「设置」中填写自己申请的密钥。本地问答和报告走 LM Studio。需要工具调用时请选支持该能力的生成模型。

## 设置与数据

- 生成模型、温度、Top P 和最大输出 token 数在保存后作用于新任务及后续问答。报告创建时还会记录输出目录，任务重试沿用原记录。重新导出使用当前输出目录。
- 报告默认写入 `%LOCALAPPDATA%\Roch\reports`。可以更改为其他本机目录。旧报告按记录路径读取，移动目录前先备份。
- 嵌入模型与向量索引绑定。已有索引时，应用会拦截改变向量空间的设置。
- `%LOCALAPPDATA%\Roch` 保存当前用户的设置、原件和日志。知识库的文档索引和会话记录保存在 Roch 的专用 Docker 卷中。备份时同时保留这些卷、应用数据目录和自选报告目录。
- 升级时关闭 Roch，再运行新安装包。升级和卸载均保留应用数据、专用 Docker 卷及自选报告目录。退出 Roch 会结束本次启动的后端，Docker 存储服务和 LM Studio 继续由用户管理。启动页可打开日志目录。
- Firecrawl 和高德密钥以当前 Windows 用户加密保存，重启 Roch 后生效。

## 构建安装包

构建使用 Windows x64、仓库 POM 指定的 Java 21、Maven、Node.js/npm、`desktop/global.json` 指定的 .NET SDK 和 Inno Setup。在仓库根目录运行以下命令。若构建工具未加入 PATH，可传入 `-Maven`、`-Dotnet`、`-InnoCompiler` 的绝对路径。

```powershell
.\scripts\build-windows.ps1 -JavaHome $env:JAVA_HOME
```

产物位于 `dist/windows`。有代码签名证书时，可传入 `-SigningThumbprint`，并通过 `-SignTool` 指定 Windows SDK 中的 `signtool.exe`。构建脚本据此签名安装包和卸载程序。

网页版的开发和服务器运行方式见 [README](README.md)。
