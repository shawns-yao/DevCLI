param(
    [ValidateSet('win-x64', 'win-arm64')]
    [string]$RuntimeIdentifier = 'win-x64'
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$buildRoot = Join-Path $repositoryRoot 'target'
& dotnet publish (Join-Path $PSScriptRoot 'DevCli.WindowsSandbox.csproj') `
    -c Release -r $RuntimeIdentifier --self-contained true `
    -o (Join-Path $buildRoot 'windows-sandbox') `
    "-p:BaseIntermediateOutputPath=$(Join-Path $buildRoot 'windows-sandbox-obj')\" `
    "-p:BaseOutputPath=$(Join-Path $buildRoot 'windows-sandbox-bin')\"
if ($LASTEXITCODE -ne 0) { throw 'Windows sandbox build failed' }
