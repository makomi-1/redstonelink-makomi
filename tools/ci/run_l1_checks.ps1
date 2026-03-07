<#
.SYNOPSIS
统一执行 L1 构建检查链路（Windows/PowerShell 版本）。
.DESCRIPTION
默认使用“前后工作区状态对比”做漂移检测，允许在脏工作区执行；
可通过 -StrictClean 强制要求运行前工作区干净（CI 推荐）。
#>
param(
    [switch]$StrictClean,
    [switch]$SkipGradle,
    [switch]$SkipDriftCheck
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-GitStatusLines {
    # 使用数组子表达式确保输出始终是数组；干净仓库时得到空数组而不是 $null。
    return @(git status --porcelain)
}

function ConvertTo-JoinedStatusText {
    param(
        [AllowNull()]
        [AllowEmptyCollection()]
        [string[]]$Lines = @()
    )
    if ($null -eq $Lines -or $Lines.Count -eq 0) {
        return ""
    }
    return (($Lines | Sort-Object) -join "`n")
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Push-Location $repoRoot
try {
    $beforeStatus = @(Get-GitStatusLines)
    if ($StrictClean -and $beforeStatus.Count -gt 0) {
        Write-Error "Workspace is dirty but -StrictClean is enabled."
    }

    if (-not $SkipGradle) {
        & .\gradlew.bat --no-daemon clean compileJava processResources validateAccessWidener test runDatagen
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle task chain failed with exit code $LASTEXITCODE."
        }
    }

    if (-not $SkipDriftCheck) {
        $afterStatus = @(Get-GitStatusLines)
        $beforeText = ConvertTo-JoinedStatusText -Lines $beforeStatus
        $afterText = ConvertTo-JoinedStatusText -Lines $afterStatus
        if ($beforeText -ne $afterText) {
            Write-Host "Detected workspace status drift after checks:" -ForegroundColor Yellow
            $diff = Compare-Object -ReferenceObject ($beforeStatus | Sort-Object) -DifferenceObject ($afterStatus | Sort-Object)
            $diff | ForEach-Object {
                if ($_.SideIndicator -eq '=>') {
                    Write-Host "+ $($_.InputObject)"
                } elseif ($_.SideIndicator -eq '<=') {
                    Write-Host "- $($_.InputObject)"
                }
            }
            throw "Drift check failed."
        }
    }

    Write-Host "L1 checks passed."
}
finally {
    Pop-Location
}
