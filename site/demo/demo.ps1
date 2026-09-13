$ErrorActionPreference = "Stop"

Write-Output "Hello from run.zip."
foreach ($argument in $args) {
    Write-Output "argument: $argument"
}
