[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn.cmd',
    [string]$Dotnet = 'dotnet.exe',
    [string]$InnoCompiler = 'ISCC.exe',
    [string]$OutputDirectory,
    [string]$Version,
    [string]$SigningThumbprint,
    [string]$SignTool = 'signtool.exe'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent $PSScriptRoot
$desktop = Join-Path $repo 'desktop'
$web = Join-Path $repo 'web'
if (!$OutputDirectory) { $OutputDirectory = Join-Path $repo 'dist/windows' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (!$Version) { $Version = (Get-Content -Raw (Join-Path $web 'package.json') | ConvertFrom-Json).version }
if ($Version -notmatch '^\d+\.\d+\.\d+(\.\d+)?$') { throw '请使用数字版本号，例如 0.1.0。' }
if (!$JavaHome) { throw '请通过 JAVA_HOME 或 -JavaHome 指定 Windows x64 Java 21 JDK。' }
$java = Join-Path $JavaHome 'bin/java.exe'
$jlink = Join-Path $JavaHome 'bin/jlink.exe'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $jlink)) { throw '未找到指定 JDK 的 java.exe 或 jlink.exe。' }
$Maven = (Get-Command $Maven -ErrorAction Stop).Source
$Dotnet = (Get-Command $Dotnet -ErrorAction Stop).Source
$InnoCompiler = (Get-Command $InnoCompiler -ErrorAction Stop).Source
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source

function Invoke-BuildStep {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path -Leaf $Executable) 失败，退出码 $LASTEXITCODE。" }
}

$stageRoot = [IO.Path]::GetFullPath((Join-Path $desktop 'obj/package'))
$stage = Join-Path $stageRoot ([guid]::NewGuid().ToString('N'))
$compileOutput = Join-Path $stage 'installer'
$payload = Join-Path $stage 'payload'
$savedJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JavaHome
try {
    New-Item -ItemType Directory -Force -Path $payload, $compileOutput | Out-Null
    Push-Location $web
    try {
        Invoke-BuildStep $npm @('ci', '--cache', (Join-Path $repo '.tools/npm-cache'))
        Invoke-BuildStep $npm @('run', 'build')
    } finally { Pop-Location }

    Invoke-BuildStep $Maven @('-f', (Join-Path $repo 'rag/pom.xml'), '-Pdesktop', '-DskipTests', 'clean', 'package')
    $server = @(Get-ChildItem -File -LiteralPath (Join-Path $repo 'rag/target') -Filter 'rag-*.jar')
    if ($server.Count -ne 1) { throw '需要且只允许一个 Spring Boot JAR。' }

    Push-Location $desktop
    try {
        Invoke-BuildStep $Dotnet @('restore', 'Roch.Desktop.csproj', '-r', 'win-x64', '--locked-mode')
        Invoke-BuildStep $Dotnet @('publish', 'Roch.Desktop.csproj', '-c', 'Release', '-r', 'win-x64',
            '--self-contained', '--no-restore', '-o', $payload, "-p:Version=$Version")
    } finally { Pop-Location }

    Copy-Item -LiteralPath $server[0].FullName -Destination (Join-Path $payload 'roch-server.jar')
    Copy-Item -LiteralPath (Join-Path $desktop 'compose.yaml'), (Join-Path $repo 'WINDOWS.md') -Destination $payload
    $modules = 'java.se,jdk.charsets,jdk.crypto.ec,jdk.crypto.mscapi,jdk.httpserver,jdk.jfr,jdk.localedata,jdk.management,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.security.auth,jdk.security.jgss,jdk.unsupported,jdk.zipfs'
    Invoke-BuildStep $jlink @('--module-path', (Join-Path $JavaHome 'jmods'), '--add-modules', $modules,
        '--strip-debug', '--no-header-files', '--no-man-pages', '--output', (Join-Path $payload 'java'))

    $innoArgs = @("/DStageDir=$payload", "/DPackageVersion=$Version", "/O$compileOutput")
    if ($SigningThumbprint) {
        if ($SigningThumbprint -notmatch '^[0-9A-Fa-f]{40}$') { throw '签名证书指纹格式无效。' }
        $SignTool = (Get-Command $SignTool -ErrorAction Stop).Source
        $innoArgs += '/DSignedPackage'
        $innoArgs += ('/SRochSign=$q' + $SignTool + '$q sign /sha1 ' + $SigningThumbprint +
            ' /fd SHA256 /tr https://timestamp.digicert.com /td SHA256 $f')
    }
    $innoArgs += (Join-Path $desktop 'Roch.iss')
    Invoke-BuildStep $InnoCompiler $innoArgs

    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $installer = Join-Path $OutputDirectory 'Roch-Setup.exe'
    Copy-Item -Force -LiteralPath (Join-Path $compileOutput 'Roch-Setup.exe') -Destination $installer
    Copy-Item -Force -LiteralPath (Join-Path $repo 'WINDOWS.md') -Destination $OutputDirectory
    $checksum = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$installer.sha256" -Value "$checksum  Roch-Setup.exe" -Encoding ascii
    Get-Item -LiteralPath $installer, "$installer.sha256", (Join-Path $OutputDirectory 'WINDOWS.md')
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $resolvedStage = [IO.Path]::GetFullPath($stage)
    if ($resolvedStage.StartsWith($stageRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedStage)) {
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}
