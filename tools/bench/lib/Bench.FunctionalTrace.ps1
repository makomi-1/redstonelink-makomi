$functionalTraceScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $functionalTraceScriptRoot "Bench.FunctionalTrace.Common.ps1")
. (Join-Path $functionalTraceScriptRoot "Bench.FunctionalTrace.Trace.ps1")
. (Join-Path $functionalTraceScriptRoot "Bench.FunctionalTrace.Expectations.ps1")
. (Join-Path $functionalTraceScriptRoot "Bench.FunctionalTrace.Phases.ps1")
