param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('setup', 'start', 'build-android', 'service-install', 'service-status', 'service-uninstall')]
    [string]$Action,
    [Parameter(Mandatory = $true)]
    [string]$ProjectPath,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$')]
    [string]$Distribution = 'Ubuntu'
)

$ErrorActionPreference = 'Stop'
if ($ProjectPath -notmatch '^/home/[^/]+/.+' -or $ProjectPath -match '[\x00-\x1f\x7f"\\]' -or $ProjectPath -match '(^|/)\.\.(/|$)') {
    throw 'ProjectPath must be the absolute Linux path of a clone inside your WSL home, for example /home/USER/KT_TABLE_TV.'
}
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw 'Install WSL 2 Ubuntu first. See docs/WINDOWS_KO.md.'
}
$wslArgs = @('--distribution', $Distribution, '--cd', $ProjectPath, '--exec')
$userId = & wsl.exe @wslArgs id -u
if ($LASTEXITCODE -ne 0 -or "$userId".Trim() -notmatch '^\d+$' -or "$userId".Trim() -eq '0') {
    throw 'Run the project as your normal Ubuntu user, not root. Check the distribution and Linux project path.'
}
switch ($Action) {
    'setup'             { & wsl.exe @wslArgs /bin/sh ./Setup.sh }
    'start'             { & wsl.exe @wslArgs /bin/sh ./Start.sh }
    'build-android'     { & wsl.exe @wslArgs /bin/sh ./Build-Android.sh }
    'service-install'   { & wsl.exe @wslArgs python3 scripts/linux-service.py install }
    'service-status'    { & wsl.exe @wslArgs python3 scripts/linux-service.py status }
    'service-uninstall' { & wsl.exe @wslArgs python3 scripts/linux-service.py uninstall }
}
exit $LASTEXITCODE
