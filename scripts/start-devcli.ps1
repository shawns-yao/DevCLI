$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

$projectRoot = Split-Path -Parent $PSScriptRoot
$projectVersion = ([xml][IO.File]::ReadAllText((Join-Path $projectRoot 'pom.xml'))).project.version
$jarPath = Join-Path $projectRoot ("target\devcli-$projectVersion.jar")
$envPath = Join-Path $projectRoot '.env'

$workingDirectory = (Get-Location).Path.TrimEnd('\', '/')
$profileDirectory = $env:USERPROFILE.TrimEnd('\', '/')
$systemDirectory = [Environment]::SystemDirectory.TrimEnd('\', '/')
$driveRoot = [IO.Path]::GetPathRoot($workingDirectory).TrimEnd('\', '/')
if ($workingDirectory -in @($profileDirectory, $systemDirectory, $driveRoot)) {
    Set-Location -LiteralPath $projectRoot
}

if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    [Console]::Error.WriteLine('DevCLI JAR not found. Run .\mvnw.cmd -DskipTests package in: ' + $projectRoot)
    exit 1
}

if (Test-Path -LiteralPath $envPath -PathType Leaf) {
    foreach ($line in [IO.File]::ReadAllLines($envPath, [Text.UTF8Encoding]::new($false, $true))) {
        if ($line -notmatch '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            continue
        }
        $name = $matches[1]
        $value = $matches[2]
        if ($value.Length -ge 2 -and (
            ($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

$javaPath = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $javaCommand = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue
    if (-not $javaCommand) {
        [Console]::Error.WriteLine('Java 17 or newer is required. Set JAVA_HOME or add java.exe to PATH.')
        exit 1
    }
    $javaCommand.Source
}

& $javaPath '--enable-native-access=ALL-UNNAMED' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-jar' $jarPath @args
exit $LASTEXITCODE
