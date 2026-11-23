using System.Security.Principal;
using System.Windows;

namespace Roch.Desktop;

public partial class App : Application
{
    private Mutex? instance;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var user = WindowsIdentity.GetCurrent().User?.Value;
        instance = new Mutex(true, @"Global\RochDesktop-" + user, out var firstInstance);
        if (!firstInstance)
        {
            MessageBox.Show("Roch 已经在运行。", "Roch");
            Shutdown();
            return;
        }

        try
        {
            MainWindow = new MainWindow();
            MainWindow.Show();
        }
        catch (Exception error)
        {
            MessageBox.Show("无法打开 Roch。" + Environment.NewLine + error.Message, "Roch");
            Shutdown(1);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        instance?.Dispose();
        base.OnExit(e);
    }
}
