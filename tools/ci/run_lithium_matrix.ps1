<#
.SYNOPSIS
执行“无锂/有锂”双矩阵兼容校验（Windows/PowerShell）。
.DESCRIPTION
默认执行 compileJava + remapJar 两组矩阵，可选追加 runClient 烟测。
#>
param(
	[switch]$RunClient
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Gradle {
	param(
		[string[]]$GradleArgs
	)

	& .\gradlew.bat @GradleArgs
	if ($LASTEXITCODE -ne 0) {
		throw "Gradle failed: gradlew.bat $($GradleArgs -join ' ')"
	}
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Push-Location $repoRoot
try {
	Write-Host "[LithiumMatrix] Step 1/2: no lithium -> compileJava + remapJar"
	Invoke-Gradle -GradleArgs @("--no-daemon", "compileJava", "remapJar")

	Write-Host "[LithiumMatrix] Step 2/2: with lithium -> compileJava + remapJar"
	Invoke-Gradle -GradleArgs @("-PwithLithium=true", "--no-daemon", "compileJava", "remapJar")

	if ($RunClient) {
		Write-Host "[LithiumMatrix] Smoke: no lithium -> runClient"
		Invoke-Gradle -GradleArgs @("--no-daemon", "runClient")

		Write-Host "[LithiumMatrix] Smoke: with lithium -> runClient"
		Invoke-Gradle -GradleArgs @("-PwithLithium=true", "--no-daemon", "runClient")
	}

	Write-Host "[LithiumMatrix] All checks passed."
}
finally {
	Pop-Location
}
