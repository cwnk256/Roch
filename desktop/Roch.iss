[Setup]
AppId={{7CCCDAD9-25E6-4B16-8055-F341F5EC607D}
AppName=Roch
AppVersion={#PackageVersion}
DefaultDirName={localappdata}\Programs\Roch
DefaultGroupName=Roch
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputBaseFilename=Roch-Setup
SetupIconFile=Assets\Roch.ico
Compression=lzma2
SolidCompression=yes
CloseApplications=yes
UninstallDisplayIcon={app}\Roch.exe
#ifdef SignedPackage
SignTool=RochSign
SignedUninstaller=yes
#endif

[Files]
Source: "{#StageDir}\*"; DestDir: "{app}"; Excludes: "*.pdb"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Roch"; Filename: "{app}\Roch.exe"

[Run]
Filename: "{app}\Roch.exe"; Description: "Open Roch"; Flags: nowait postinstall skipifsilent
Filename: "https://developer.microsoft.com/microsoft-edge/webview2/"; Description: "Get Microsoft Edge WebView2 Runtime"; Flags: shellexec postinstall skipifsilent; Check: NeedsWebView

[Code]
function HasVersion(const Root: Integer): Boolean;
var
  Version: String;
begin
  Result := RegQueryStringValue(Root,
    'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version)
    and (Version <> '') and (Version <> '0.0.0.0');
end;

function NeedsWebView(): Boolean;
begin
  Result := not (HasVersion(HKLM32) or HasVersion(HKCU));
end;
