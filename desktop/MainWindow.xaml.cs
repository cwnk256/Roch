using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using Microsoft.Win32;

namespace Roch.Desktop;

public partial class MainWindow : Window
{
    private static readonly Regex FileRoute = new(
        @"^/api/(documents/[^/]+/file|document-tasks/[^/]+/(file|artifacts/[^/]+))$",
        RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(100));
    private static readonly HashSet<string> AppRoutes = new(StringComparer.Ordinal)
    {
        "/", "/dashboard", "/chat", "/conversation", "/search", "/import", "/documents", "/reports", "/webpage", "/settings"
    };

    private readonly LocalData data = new();
    private readonly LocalProcesses processes;
    private readonly CancellationTokenSource lifetime = new();
    private readonly DispatcherTimer backendMonitor = new() { Interval = TimeSpan.FromSeconds(1) };
    private CoreWebView2Environment? environment;
    private WebView2? workbench;
    private WebView2? preview;
    private bool busy, closing, allowClose;

    public MainWindow()
    {
        InitializeComponent();
        processes = new(data);
        PostgresPort.Text = data.Storage.PostgresPort.ToString();
        MilvusPort.Text = data.Storage.MilvusPort.ToString();
        backendMonitor.Tick += (_, _) =>
        {
            if (busy || closing || !processes.BackendExited) return;
            processes.ForceStop();
            ShowStartup("Roch 后端已退出。请查看日志后重试。");
        };
        backendMonitor.Start();
        Loaded += async (_, _) => await StartAsync(startStorage: false);
        Closing += OnClosing;
        Closed += (_, _) =>
        {
            backendMonitor.Stop();
            DisposeViews();
            processes.Dispose();
            lifetime.Dispose();
        };
    }

    private async void RetryClicked(object sender, RoutedEventArgs e) => await StartAsync(startStorage: false);
    private async void StartStorageClicked(object sender, RoutedEventArgs e) => await StartAsync(startStorage: true);

    private async Task StartAsync(bool startStorage)
    {
        if (busy || closing) return;
        SetBusy(true);
        ShowStartup("正在检查本机依赖…");
        WebViewHelp.Visibility = Visibility.Collapsed;
        try
        {
            try { CoreWebView2Environment.GetAvailableBrowserVersionString(); }
            catch (WebView2RuntimeNotFoundException)
            {
                WebViewHelp.Visibility = Visibility.Visible;
                throw new InvalidOperationException("请安装 Microsoft Edge WebView2 Runtime，然后重试。");
            }

            int postgres = ReadPort(PostgresPort.Text);
            int milvus = ReadPort(MilvusPort.Text);
            if (postgres != data.Storage.PostgresPort || milvus != data.Storage.MilvusPort)
                data.SaveStorage(postgres, milvus);
            if (startStorage)
            {
                StartupStatus.Text = "正在启动专用存储服务。首次使用可能需要下载镜像…";
                await processes.StartStorageAsync(lifetime.Token);
            }

            await processes.CheckStorageAsync(lifetime.Token);
            StartupStatus.Text = "正在启动 Roch 后端…";
            var startPage = await processes.StartBackendAsync(lifetime.Token);
            lifetime.Token.ThrowIfCancellationRequested();
            environment ??= await CoreWebView2Environment.CreateAsync(userDataFolder: Path.Combine(data.Root, "webview"));
            await OpenWorkbenchAsync(startPage);
        }
        catch (OperationCanceledException) when (closing) { }
        catch (Exception error)
        {
            processes.ForceStop();
            ShowStartup(error.Message);
        }
        finally { SetBusy(false); }
    }

    private static int ReadPort(string value) => int.TryParse(value, out var port)
        ? port : throw new ArgumentException("请输入有效的本机端口。");

    private void ShowStartup(string message)
    {
        DisposeViews();
        StartupStatus.Text = message;
        StartupPanel.Visibility = Visibility.Visible;
    }

    private void SetBusy(bool value)
    {
        busy = value;
        StartStorage.IsEnabled = Retry.IsEnabled = PostgresPort.IsEnabled = MilvusPort.IsEnabled = !value;
    }

    private async Task OpenWorkbenchAsync(string startPage)
    {
        var origin = processes.Origin!;
        var view = new WebView2();
        workbench = view;
        Workspace.Content = view;
        Workspace.Visibility = Visibility.Visible;
        await view.EnsureCoreWebView2Async(environment);
        var core = view.CoreWebView2;
        ConfigureBrowser(core, origin, mainView: true);
        var cookie = core.CookieManager.CreateCookie("roch_desktop", processes.Token, "127.0.0.1", "/");
        cookie.IsHttpOnly = true;
        cookie.SameSite = CoreWebView2CookieSameSiteKind.Strict;
        core.CookieManager.AddOrUpdateCookie(cookie);
        core.WebMessageReceived += ReceiveMessage;
        core.NewWindowRequested += OpenFile;
        core.Navigate(new Uri(origin, startPage).AbsoluteUri);
        StartupPanel.Visibility = Visibility.Collapsed;
    }

    private void ConfigureBrowser(CoreWebView2 browser, Uri origin, bool mainView)
    {
        browser.Settings.AreHostObjectsAllowed = false;
        browser.Settings.IsWebMessageEnabled = mainView;
        browser.Settings.AreDevToolsEnabled = false;
        browser.Settings.AreDefaultContextMenusEnabled = false;
        browser.Settings.IsStatusBarEnabled = false;
        browser.NavigationStarting += (_, args) =>
        {
            args.Cancel = !TrySameOrigin(args.Uri, origin, out var uri)
                || (mainView ? !AppRoutes.Contains(uri.AbsolutePath) : !FileRoute.IsMatch(uri.AbsolutePath));
        };
        browser.FrameNavigationStarting += (sender, args) =>
        {
            args.Cancel = !TrySameOrigin(args.Uri, origin, out _)
                && (mainView || !args.Uri.StartsWith("chrome-extension://", StringComparison.Ordinal));
        };
        browser.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All,
            CoreWebView2WebResourceRequestSourceKinds.All);
        browser.WebResourceRequested += (sender, args) =>
        {
            if (TrySameOrigin(args.Request.Uri, origin, out _)) return;
            if (!Uri.TryCreate(args.Request.Uri, UriKind.Absolute, out var uri)
                || uri.Scheme is "http" or "https" or "file")
            {
                args.Response = environment!.CreateWebResourceResponse(Stream.Null, 403, "Forbidden", "");
            }
        };
        browser.PermissionRequested += (_, args) => args.State = CoreWebView2PermissionState.Deny;
        browser.DownloadStarting += (_, args) =>
        {
            var target = args.DownloadOperation.Uri;
            var isFile = TrySameOrigin(target, origin, out var uri) && FileRoute.IsMatch(uri.AbsolutePath);
            var isLocalBlob = target.StartsWith("blob:" + origin.AbsoluteUri, StringComparison.Ordinal);
            args.Handled = true;
            args.Cancel = true;
            if (!isFile && !isLocalBlob) return;
            using var deferral = args.GetDeferral();
            var suggestedName = Path.GetFileName(args.ResultFilePath);
            var dialog = new SaveFileDialog
            {
                Title = "保存文件",
                FileName = suggestedName,
                DefaultExt = Path.GetExtension(suggestedName),
                OverwritePrompt = true,
                AddExtension = true
            };
            if (dialog.ShowDialog(this) == true)
            {
                args.ResultFilePath = dialog.FileName;
                args.Cancel = false;
            }
        };
        if (!mainView) browser.NewWindowRequested += (_, args) => args.Handled = true;
    }

    private async void OpenFile(object? sender, CoreWebView2NewWindowRequestedEventArgs args)
    {
        args.Handled = true;
        if (processes.Origin is not { } origin || !TrySameOrigin(args.Uri, origin, out var file)
            || !FileRoute.IsMatch(file.AbsolutePath)) return;
        try
        {
            using var response = await processes.ReadFileAsync(file, lifetime.Token);
            response.EnsureSuccessStatusCode();
            var disposition = response.Content.Headers.ContentDisposition;
            if (disposition?.DispositionType == "attachment"
                || response.Content.Headers.ContentType?.MediaType != "application/pdf")
            {
                var fileName = disposition?.FileNameStar ?? disposition?.FileName?.Trim('"') ?? "Roch.pdf";
                await SaveFileAsync(response, Path.GetFileName(fileName));
                return;
            }

            preview?.Dispose();
            var view = new WebView2();
            preview = view;
            PreviewContent.Content = view;
            Workspace.Visibility = Visibility.Collapsed;
            Preview.Visibility = Visibility.Visible;
            await view.EnsureCoreWebView2Async(environment);
            if (preview != view) return;
            var browser = view.CoreWebView2;
            ConfigureBrowser(browser, origin, mainView: false);
            browser.Navigate(file.AbsoluteUri);
        }
        catch (Exception error)
        {
            if (closing) return;
            ClosePreview(this, new RoutedEventArgs());
            MessageBox.Show(this, error.Message, "无法打开文件");
        }
    }

    private async Task SaveFileAsync(System.Net.Http.HttpResponseMessage response, string fileName)
    {
        var dialog = new SaveFileDialog { Title = "保存文件", FileName = fileName, OverwritePrompt = true };
        if (dialog.ShowDialog(this) != true) return;
        var temporary = Path.Combine(Path.GetDirectoryName(dialog.FileName)!, ".roch-" + Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            await using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                await response.Content.CopyToAsync(output, lifetime.Token);
            File.Move(temporary, dialog.FileName, overwrite: true);
        }
        finally { File.Delete(temporary); }
    }

    private async void ReceiveMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs args)
    {
        if (workbench?.CoreWebView2 is not { } core || processes.Origin is not { } origin
            || !TrySameOrigin(args.Source, origin, out var source) || !AppRoutes.Contains(source.AbsolutePath)
            || !TrySameOrigin(core.Source, origin, out var current) || !AppRoutes.Contains(current.AbsolutePath)) return;

        string? id = null;
        var restart = false;
        try
        {
            if (args.WebMessageAsJson.Length > 16_384) return;
            using var message = JsonDocument.Parse(args.WebMessageAsJson);
            var root = message.RootElement;
            id = root.GetProperty("id").GetString();
            if (id is null || id.Length is < 1 or > 24 || !id.All(char.IsAsciiDigit)) return;
            if (busy || closing) throw new InvalidOperationException("Roch 正在启动或退出，请稍后再试。");
            object? value;
            switch (root.GetProperty("action").GetString())
            {
                case "chooseDirectory":
                    var dialog = new OpenFolderDialog { Title = "选择报告输出目录" };
                    value = dialog.ShowDialog(this) == true ? dialog.FolderName : null;
                    break;
                case "credentialStatus":
                    value = data.CredentialStatus();
                    break;
                case "saveCredentials":
                    var updates = root.GetProperty("credentials").Deserialize<Dictionary<string, string>>()
                        ?? throw new ArgumentException("密钥格式无效。");
                    data.UpdateCredentials(updates);
                    value = null;
                    break;
                case "restart":
                    SetBusy(true);
                    try { await processes.StopAsync(restart: true); }
                    finally { SetBusy(false); }
                    restart = true;
                    value = null;
                    break;
                default:
                    throw new ArgumentException("不支持的桌面操作。");
            }
            core.PostWebMessageAsJson(JsonSerializer.Serialize(new { id, ok = true, value }));
        }
        catch (Exception error)
        {
            if (id is not null && workbench?.CoreWebView2 == core)
                core.PostWebMessageAsJson(JsonSerializer.Serialize(new { id, ok = false, error = error.Message }));
        }
        if (restart) await StartAsync(startStorage: false);
    }

    private static bool TrySameOrigin(string source, Uri origin, out Uri result)
    {
        if (Uri.TryCreate(source, UriKind.Absolute, out var uri) && uri.Scheme == origin.Scheme
            && uri.Host == origin.Host && uri.Port == origin.Port && uri.UserInfo.Length == 0)
        {
            result = uri;
            return true;
        }

        result = null!;
        return false;
    }

    private void ClosePreview(object sender, RoutedEventArgs e)
    {
        Preview.Visibility = Visibility.Collapsed;
        if (workbench is not null) Workspace.Visibility = Visibility.Visible;
    }

    private void DisposeViews()
    {
        Preview.Visibility = Workspace.Visibility = Visibility.Collapsed;
        preview?.Dispose();
        workbench?.Dispose();
        preview = workbench = null;
        PreviewContent.Content = Workspace.Content = null;
    }

    private async void OnClosing(object? sender, CancelEventArgs e)
    {
        if (allowClose) return;
        e.Cancel = true;
        if (closing) return;
        closing = true;
        if (busy)
        {
            lifetime.Cancel();
            while (busy) await Task.Delay(100);
        }

        try
        {
            await processes.StopAsync(restart: false);
        }
        catch (Exception error)
        {
            var answer = MessageBox.Show(this, error.Message + "\n\n仍要退出并结束本次 Roch 后端吗？",
                "退出 Roch", MessageBoxButton.YesNo, MessageBoxImage.Warning);
            if (answer != MessageBoxResult.Yes && !lifetime.IsCancellationRequested)
            {
                closing = false;
                return;
            }
            processes.ForceStop();
        }
        allowClose = true;
        Close();
    }

    private void OpenLogs(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo(data.Logs) { UseShellExecute = true });

    private void OpenWebViewHelp(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo("https://developer.microsoft.com/microsoft-edge/webview2/") { UseShellExecute = true });
}
