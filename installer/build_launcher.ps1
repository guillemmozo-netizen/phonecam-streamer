# Builds "FrameCast Setup.exe" — the icon-bearing entry point that starts
# Install_FrameCast.vbs. Uses the csc that ships with the .NET Framework on
# every Windows box, so there is nothing to install to reproduce this.
#
#   powershell -ExecutionPolicy Bypass -File installer\build_launcher.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) {
    throw "csc.exe not found at $csc"
}

$icon = Join-Path $root 'branding\framecast.ico'
$source = Join-Path $PSScriptRoot 'Launcher.cs'
$output = Join-Path $root 'dist\FrameCast Setup.exe'
New-Item -ItemType Directory -Force -Path (Split-Path $output) | Out-Null

# /target:winexe, not exe: a console window flashing up behind a silent
# installer is exactly the thing the installer goes out of its way to avoid.
& $csc /nologo /target:winexe /platform:anycpu /optimize+ `
    "/win32icon:$icon" "/out:$output" `
    /r:System.dll /r:System.Windows.Forms.dll $source

if ($LASTEXITCODE -ne 0) { throw "csc failed with $LASTEXITCODE" }
Write-Host ("built {0} ({1:N0} bytes)" -f $output, (Get-Item $output).Length)
