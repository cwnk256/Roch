using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;

namespace Roch.Desktop;

internal sealed class LocalProcesses(LocalData data) : IDisposable
{
    private readonly HttpClient http = new(new SocketsHttpHandler { UseProxy = false, AllowAutoRedirect = false })
    {
        Timeout = TimeSpan.FromSeconds(15)
    };
    private readonly ChildJob javaJob = new();
    private Process? java;
    internal Uri? Origin { get; private set; }
    internal string Token { get; private set; } = "";
    internal bool BackendExited => java?.HasExited == true;
    internal bool BackendRunning => java?.HasExited == false;

    internal async Task CheckStorageAsync(CancellationToken token)
    {
        await RunDockerAsync(["info", "--format", "{{.OSType}}"], TimeSpan.FromSeconds(15), token,
            "请启动 Docker Desktop 并检查 Linux 容器引擎。", expected: "linux");
        foreach (var (service, internalPort, hostPort) in new[]
        {
            ("postgres", 5432, data.Storage.PostgresPort), ("standalone", 19530, data.Storage.MilvusPort)
        })
        {
            var label = service == "postgres" ? "PostgreSQL" : "Milvus";
            var message = $"Roch 的 {label} 尚未就绪。请启动存储服务；端口冲突时可先更改端口。";
            var id = (await RunComposeAsync(["ps", "-q", service], TimeSpan.FromSeconds(15), token, message)).Trim();
            if (id.Length == 0) throw new InvalidOperationException(message);
            await RunDockerAsync(["inspect", "--format", "{{.State.Health.Status}}", id],
                TimeSpan.FromSeconds(15), token, message, expected: "healthy");
            var binding = await RunComposeAsync(["port", service, internalPort.ToString()], TimeSpan.FromSeconds(15), token, message);
            if (binding.Trim() != $"127.0.0.1:{hostPort}") throw new InvalidOperationException(message);
        }
    }

    internal async Task StartStorageAsync(CancellationToken token)
    {
        await RunDockerAsync(["info", "--format", "{{.OSType}}"], TimeSpan.FromSeconds(15), token,
            "请启动 Docker Desktop 并检查 Linux 容器引擎。", expected: "linux");
        await RunComposeAsync(["up", "-d", "--wait", "--wait-timeout", "180"], TimeSpan.FromMinutes(20), token,
            "存储服务启动失败。请检查端口是否被占用、镜像网络是否可用，并打开日志核对详情。");
    }

    private Task<string> RunComposeAsync(string[] args, TimeSpan timeout, CancellationToken token, string message)
        => RunDockerAsync(["compose", "--project-name", data.Storage.Project, "-f",
            Path.Combine(AppContext.BaseDirectory, "compose.yaml"), ..args], timeout, token, message);

    private async Task<string> RunDockerAsync(string[] args, TimeSpan timeout, CancellationToken token, string message,
        string? expected = null)
    {
        var start = HiddenProcess(FindDocker(), ["--host", "npipe:////./pipe/dockerDesktopLinuxEngine", ..args]);
        start.Environment.Remove("DOCKER_CONTEXT");
        start.Environment.Remove("DOCKER_HOST");
        start.Environment["ROCH_POSTGRES_PORT"] = data.Storage.PostgresPort.ToString();
        start.Environment["ROCH_MILVUS_PORT"] = data.Storage.MilvusPort.ToString();
        start.Environment["POSTGRES_PASSWORD"] = data.Storage.PostgresPassword;
        start.Environment["MINIO_SECRET_KEY"] = data.Storage.MinioPassword;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(timeout);
        using var process = new Process { StartInfo = start };
        try
        {
            if (!process.Start()) throw new InvalidOperationException(message);
        }
        catch (System.ComponentModel.Win32Exception error)
        {
            throw new InvalidOperationException("无法启动 Docker。请安装 Docker Desktop 或检查 Docker CLI。", error);
        }
        var output = process.StandardOutput.ReadToEndAsync(deadline.Token);
        var errors = process.StandardError.ReadToEndAsync(deadline.Token);
        try
        {
            await process.WaitForExitAsync(deadline.Token);
        }
        catch (OperationCanceledException)
        {
            if (!process.HasExited) process.Kill(entireProcessTree: true);
            if (token.IsCancellationRequested) throw;
            throw new TimeoutException(message);
        }
        var text = await output;
        var details = await errors;
        if (process.ExitCode != 0 || (expected is not null && text.Trim() != expected))
        {
            Log("docker.log", details);
            throw new InvalidOperationException(message);
        }
        if (args.Contains("up")) Log("docker.log", details);
        return text;
    }

    internal async Task<string> StartBackendAsync(CancellationToken token)
    {
        ForceStop();
        Token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        var startDirectory = Directory.CreateDirectory(Path.Combine(data.Root, "runtime")).FullName;
        var readyFile = Path.Combine(startDirectory, Guid.NewGuid().ToString("N") + ".ready");
        var info = HiddenProcess(Path.Combine(AppContext.BaseDirectory, "java", "bin", "java.exe"),
            ["-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar",
            Path.Combine(AppContext.BaseDirectory, "roch-server.jar"), "--spring.profiles.active=desktop"]);
        info.StandardOutputEncoding = System.Text.Encoding.UTF8;
        info.StandardErrorEncoding = System.Text.Encoding.UTF8;
        info.WorkingDirectory = data.Root;
        info.Environment["ROCH_DESKTOP_TOKEN"] = Token;
        info.Environment["ROCH_DESKTOP_SETTINGS_FILE"] = Path.Combine(data.Root, "settings.json");
        info.Environment["ROCH_DESKTOP_READY_FILE"] = readyFile;
        info.Environment["SPRING_DATASOURCE_URL"] = $"jdbc:postgresql://127.0.0.1:{data.Storage.PostgresPort}/roch";
        info.Environment["SPRING_DATASOURCE_USERNAME"] = "roch";
        info.Environment["SPRING_DATASOURCE_PASSWORD"] = data.Storage.PostgresPassword;
        info.Environment["POSTGRES_PASSWORD"] = data.Storage.PostgresPassword;
        info.Environment["SPRING_AI_VECTORSTORE_MILVUS_CLIENT_HOST"] = "127.0.0.1";
        info.Environment["SPRING_AI_VECTORSTORE_MILVUS_CLIENT_PORT"] = data.Storage.MilvusPort.ToString();
        var credentials = data.ReadCredentials();
        info.Environment["FIRECRAWL_API_KEY"] = credentials.GetValueOrDefault("firecrawl", "");
        info.Environment["AMAP_GEO_KEY"] = credentials.GetValueOrDefault("amapGeo", "");
        info.Environment["AMAP_WEATHER_KEY"] = credentials.GetValueOrDefault("amapWeather", "");

        var process = new Process { StartInfo = info };
        try
        {
            if (!process.Start()) throw new InvalidOperationException("无法启动 Roch 后端。");
            java = process;
            javaJob.Add(process);
            process.OutputDataReceived += (_, line) => Log("server.log", line.Data);
            process.ErrorDataReceived += (_, line) => Log("server.log", line.Data);
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();

            var deadline = DateTime.UtcNow + TimeSpan.FromMinutes(2);
            while (DateTime.UtcNow < deadline)
            {
                token.ThrowIfCancellationRequested();
                if (process.HasExited) throw new InvalidOperationException("Roch 后端启动失败，请打开日志查看原因。");
                if (File.Exists(readyFile) && int.TryParse(await File.ReadAllTextAsync(readyFile, token), out var port)
                    && port is > 0 and <= 65535)
                {
                    Origin = new Uri($"http://127.0.0.1:{port}/");
                    using var request = AuthenticatedRequest(HttpMethod.Get, new Uri(Origin, "api/desktop/settings"));
                    using var response = await http.SendAsync(request, token);
                    response.EnsureSuccessStatusCode();
                    using var settings = JsonDocument.Parse(await response.Content.ReadAsStreamAsync(token));
                    return string.IsNullOrEmpty(settings.RootElement.GetProperty("settings").GetProperty("model").GetString())
                        ? "/settings" : "/";
                }
                await Task.Delay(250, token);
            }
            throw new TimeoutException("Roch 后端启动超时。请检查存储服务并打开日志查看。");
        }
        catch
        {
            if (java == process) ForceStop();
            else process.Dispose();
            throw;
        }
        finally
        {
            File.Delete(readyFile);
        }
    }

    internal async Task StopAsync(bool restart)
    {
        var process = java;
        if (process?.HasExited == false && Origin is not null)
        {
            using var request = AuthenticatedRequest(HttpMethod.Post, new Uri(Origin,
                "api/desktop/shutdown?restart=" + (restart ? "true" : "false")));
            request.Headers.Add("Origin", Origin.GetLeftPart(UriPartial.Authority));
            using var response = await http.SendAsync(request);
            if (response.StatusCode == HttpStatusCode.Conflict)
            {
                using var details = JsonDocument.Parse(await response.Content.ReadAsStreamAsync());
                throw new InvalidOperationException(details.RootElement.GetProperty("error").GetString());
            }
            response.EnsureSuccessStatusCode();
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(35));
            try { await process.WaitForExitAsync(timeout.Token); }
            catch (OperationCanceledException) { ForceStop(); }
        }
        ForceStop();
    }

    private HttpRequestMessage AuthenticatedRequest(HttpMethod method, Uri target)
    {
        var request = new HttpRequestMessage(method, target);
        request.Headers.Add("Cookie", "roch_desktop=" + Token);
        return request;
    }

    internal async Task<HttpResponseMessage> ReadFileAsync(Uri target, CancellationToken token)
    {
        using var request = AuthenticatedRequest(HttpMethod.Get, target);
        return await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
    }

    internal void ForceStop()
    {
        var process = java;
        java = null;
        Origin = null;
        Token = "";
        if (process is null) return;
        using (process)
        {
            if (process.HasExited) return;
            process.Kill(entireProcessTree: true);
            process.WaitForExit(5000);
        }
    }

    private static ProcessStartInfo HiddenProcess(string executable, IEnumerable<string> args)
    {
        var start = new ProcessStartInfo(executable)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        foreach (var arg in args) start.ArgumentList.Add(arg);
        return start;
    }

    private static string FindDocker()
    {
        foreach (var root in new[]
        {
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs")
        })
        {
            foreach (var relative in new[] { @"Docker\Docker\resources\bin\docker.exe", @"DockerDesktop\resources\bin\docker.exe" })
            {
                var path = Path.Combine(root, relative);
                if (File.Exists(path)) return path;
            }
        }
        return "docker.exe";
    }

    private readonly object logLock = new();

    private void Log(string file, string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return;
        lock (logLock)
        {
            try { File.AppendAllText(Path.Combine(data.Logs, file), line + Environment.NewLine); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    public void Dispose()
    {
        javaJob.Dispose();
        java?.Dispose();
        http.Dispose();
    }
}
