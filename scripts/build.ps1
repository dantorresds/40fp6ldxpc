# Gera o motor (waengine.aar) e o APK do Atendente IA no Windows.
#
#   ./scripts/build.ps1            APK de debug
#   ./scripts/build.ps1 -Release   APK release (assinado com a chave de debug)
#   ./scripts/build.ps1 -SkipEngine  reaproveita o waengine.aar já gerado
#
# Variáveis opcionais: JAVA_HOME (JDK 17), ANDROID_HOME, ANDROID_NDK_HOME, GO (caminho do go.exe).
param([switch]$Release, [switch]$SkipEngine)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not $env:ANDROID_HOME) {
  foreach ($p in "$env:USERPROFILE\.android\sdk", "$env:LOCALAPPDATA\Android\Sdk") {
    if (Test-Path "$p\ndk") { $env:ANDROID_HOME = $p; break }
  }
}
if (-not $env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\27.2.12479018' }
if (-not (Test-Path "$env:ANDROID_NDK_HOME\source.properties")) { throw "NDK não encontrado em $env:ANDROID_NDK_HOME" }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
$env:PATH = (Join-Path $env:USERPROFILE 'go\bin') + ";$env:PATH"
$env:GOTOOLCHAIN = 'local'
$go = if ($env:GO) { $env:GO } else { 'go' }

if (-not $SkipEngine) {
  Push-Location "$root\engine"
  try {
    & $go test ./...
    if ($LASTEXITCODE) { throw 'Testes do motor falharam.' }
    New-Item -ItemType Directory -Force out | Out-Null
    gomobile bind -target="android/arm64,android/arm" -androidapi 23 -ldflags="-s -w" -javapkg com.atendenteia.wa -o out/waengine.aar ./waengine
    if ($LASTEXITCODE -or -not (Test-Path out/waengine.aar)) { throw 'AAR não foi gerada.' }
  } finally { Pop-Location }
  New-Item -ItemType Directory -Force "$root\android\app\libs" | Out-Null
  Copy-Item "$root\engine\out\waengine.aar" "$root\android\app\libs\waengine.aar" -Force
}

Push-Location "$root\android"
try {
  $task = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
  & .\gradlew.bat $task --console=plain
  if ($LASTEXITCODE) { throw 'Build do APK falhou.' }
} finally { Pop-Location }
Get-ChildItem "$root\android\app\build\outputs\apk" -Recurse -Filter *.apk | Select-Object FullName, Length
