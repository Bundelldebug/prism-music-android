$ErrorActionPreference = 'Stop'
# Robolectric native-graphics screenshots. Reuses the dependency cache of OneUI_Viewer_Android/tests/ui.
# Run build.ps1 first (uses its staged classes + resource APK). Not a substitute for a real device.
$UiRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $UiRoot '../..'))
$ViewerCache = [IO.Path]::GetFullPath((Join-Path $ProjectRoot '../OneUI_Viewer_Android/tests/ui/.cache'))
$Toolchain = $env:PRISM_TOOLCHAIN
$ToolchainFile = Join-Path $ProjectRoot 'toolchain.path'
if (-not $Toolchain -and (Test-Path -LiteralPath $ToolchainFile)) { $Toolchain = (Get-Content -LiteralPath $ToolchainFile -TotalCount 1).Trim() }
if (-not $Toolchain) { throw 'Set PRISM_TOOLCHAIN or create toolchain.path (see README)' }
$JavaHome = Join-Path $Toolchain 'jdk21/jdk-21.0.13+9'
$Stage = Join-Path $env:LOCALAPPDATA 'Temp\PrismMusic_Android_Build'
$Work = Join-Path $env:TEMP 'PrismMusicUiTest'
if (Test-Path -LiteralPath $Work) { Remove-Item -LiteralPath $Work -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$Work\test-classes", "$Work\out", "$Work\cfg\com\android\tools" | Out-Null

$Deps = @(Get-ChildItem (Join-Path $ViewerCache 'dependencies') -Filter '*.jar' | ForEach-Object FullName)
# androidx.test aars (monitor, core…) were extracted next to the dependency folder by the viewer harness
$Deps += @(Get-ChildItem $ViewerCache -Directory | ForEach-Object { Join-Path $_.FullName 'classes.jar' } | Where-Object { Test-Path $_ })
$AndroidJar = Join-Path $Toolchain 'platforms-android-35/android-35/android.jar'
$AppClasses = Join-Path $Stage 'build\classes'
$Manifest = Join-Path $Stage 'app\src\main\AndroidManifest.xml'
$ResApk = Join-Path $Stage 'build\unsigned.apk'
if (-not (Test-Path $ResApk)) { throw 'Run build.ps1 first' }

$props = "android_merged_manifest=$($Manifest.Replace('\','/'))`nandroid_resource_apk=$($ResApk.Replace('\','/'))`nandroid_custom_package=com.oneuibook.prism`n"
[IO.File]::WriteAllText("$Work\cfg\com\android\tools\test_config.properties", $props)

& (Join-Path $JavaHome 'bin/javac.exe') --release 11 -encoding UTF-8 -cp ((@($AppClasses, $AndroidJar) + $Deps) -join ';') -d "$Work\test-classes" (Join-Path $UiRoot 'PrismUiTest.java')
if ($LASTEXITCODE -ne 0) { throw 'test compile failed' }
$Cp = (@($AppClasses, "$Work\test-classes", "$Work\cfg") + $Deps) -join ';'
& (Join-Path $JavaHome 'bin/java.exe') '--add-opens=java.base/java.io=ALL-UNNAMED' "-Duser.home=$ViewerCache/home" "-Dmaven.repo.local=$ViewerCache/m2" "-Djava.io.tmpdir=$Work" '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2' "-Dprism.output=$Work\out" -cp $Cp org.junit.runner.JUnitCore com.oneuibook.prism.PrismUiTest
$code = $LASTEXITCODE
New-Item -ItemType Directory -Force -Path (Join-Path $UiRoot 'output') | Out-Null
Copy-Item "$Work\out\*" (Join-Path $UiRoot 'output') -Force
if ($code -ne 0) { throw 'UI checks failed' }
