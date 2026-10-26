# Bir uygulamayı local profiliyle, DEV'e bağlı çalıştırır (docs/local-run.md).
#   .\scripts\local-run.ps1 admin|runtime|env-admin|login [-Build]
# local.env varsa ortam değişkenleri ondan okunur. Jar yoksa ya da -Build verilmişse önce derlenir (testsiz).
param(
    [Parameter(Mandatory = $true)][ValidateSet('admin', 'runtime', 'env-admin', 'login')][string]$App,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

$modules = @{ 'admin' = 'yapi-admin'; 'runtime' = 'yapi-runtime'; 'env-admin' = 'yapi-env-admin'; 'login' = 'yapi-login' }
$module = $modules[$App]

if (Test-Path 'local.env') {
    foreach ($line in Get-Content 'local.env') {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
}

$jar = Get-ChildItem "$module/target/$module-*-exec.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar -or $Build) {
    mvn -B -q -DskipTests -pl $module -am package
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $jar = Get-ChildItem "$module/target/$module-*-exec.jar" | Select-Object -First 1
}

java -jar $jar.FullName --spring.profiles.active=local
exit $LASTEXITCODE
