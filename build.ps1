$ErrorActionPreference = 'Stop'

# Build GT Multiblock Sockets against the exact production classpath used by the
# "GregTech Leisure" instance.  No ForgeGradle, no network access, no reobfuscation:
# the game runtime and the GTCEu jar both use SRG names.

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$version = '1.0.3'
$jdk = 'C:\Program Files\Java\jdk-21.0.11\bin'

# Discover the save folder instead of embedding a non-ASCII path in this script.
$saveRoot = Get-ChildItem 'D:\MineCraft\save' -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName '.minecraft\versions\GregTech Leisure\mods') } |
    Select-Object -First 1
if ($null -eq $saveRoot) {
    throw 'Could not find the GregTech Leisure instance under D:\MineCraft\save'
}
$libs = Join-Path $saveRoot.FullName '.minecraft\libraries'
$mods = Join-Path $saveRoot.FullName '.minecraft\versions\GregTech Leisure\mods'

$classpathJars = @(
    (Join-Path $libs 'net\minecraft\client\1.20.1-20230612.114412\client-1.20.1-20230612.114412-srg.jar'),
    (Join-Path $libs 'net\minecraftforge\forge\1.20.1-47.4.16\forge-1.20.1-47.4.16-universal.jar'),
    (Join-Path $libs 'net\minecraftforge\mergetool\1.1.5\mergetool-1.1.5-api.jar'),
    (Join-Path $libs 'com\mojang\brigadier\1.1.8\brigadier-1.1.8.jar'),
    (Join-Path $libs 'net\minecraftforge\fmlcore\1.20.1-47.4.16\fmlcore-1.20.1-47.4.16.jar'),
    (Join-Path $libs 'net\minecraftforge\javafmllanguage\1.20.1-47.4.16\javafmllanguage-1.20.1-47.4.16.jar'),
    (Join-Path $libs 'net\minecraftforge\eventbus\6.2.33\eventbus-6.2.33.jar'),
    (Join-Path $libs 'org\spongepowered\mixin\0.8.5\mixin-0.8.5.jar'),
    (Join-Path $libs 'org\slf4j\slf4j-api\2.0.1\slf4j-api-2.0.1.jar'),
    (Join-Path $libs 'com\electronwill\night-config\core\3.6.4\core-3.6.4.jar'),
    (Join-Path $libs 'com\electronwill\night-config\toml\3.6.4\toml-3.6.4.jar'),
    (Join-Path $mods 'gtceu-1.20.1-1.4.4.jar'),
    (Join-Path $mods 'ldlib-forge-1.20.1-1.0.33.b.jar')
)

foreach ($jar in $classpathJars) {
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "Missing classpath jar: $jar"
    }
}
$classpath = $classpathJars -join ';'

$buildDir = Join-Path $root 'build'
$classes = Join-Path $buildDir 'classes'
Remove-Item -LiteralPath $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter '*.java' |
    ForEach-Object { $_.FullName }
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 -proc:none --release 17 -cp $classpath -d $classes $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

# Resources (mods.toml, mixin config, pack.mcmeta, language files).
$resources = Join-Path $root 'src\main\resources'
Copy-Item -Path (Join-Path $resources '*') -Destination $classes -Recurse -Force

# Forge reads MixinConfigs from the jar manifest.  Write it with CRLF explicitly.
$manifest = Join-Path $buildDir 'MANIFEST.MF'
$manifestLines = @(
    'Manifest-Version: 1.0',
    'Implementation-Title: GT Multiblock Sockets',
    ('Implementation-Version: ' + $version),
    'MixinConfigs: mixins.gtmbsockets.json'
)
[System.IO.File]::WriteAllText($manifest, ($manifestLines -join "`r`n") + "`r`n", [System.Text.Encoding]::ASCII)

$jarPath = Join-Path $root "gtmbsockets-$version.jar"
Remove-Item -LiteralPath $jarPath -Force -ErrorAction SilentlyContinue
& (Join-Path $jdk 'jar.exe') cfm $jarPath $manifest -C $classes .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}

Write-Host "Built: $jarPath"
Get-Item -LiteralPath $jarPath | Select-Object FullName, Length, LastWriteTime
