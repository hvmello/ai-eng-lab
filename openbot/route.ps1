# Dispatches an issue or pull request to the agent that matches its complexity label.
# The agent receives only a number and reads the content itself, under the rules in AGENTS.md.
param(
    [Parameter(Mandatory)][string]$Repo,
    [Parameter(Mandatory)][int]$Issue,
    [Parameter(Mandatory)][ValidateSet('dev', 'review')][string]$Stage,
    [int]$Pr,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'

$sonnet = 'claude-sonnet-5-5'
$opus = 'claude-opus-5-5'
$routes = @{
    'complexity:low'    = @{ Dev = 'Dev-Local';  DevModel = $null;   Reviewer = $sonnet; Advisory = $false }
    'complexity:medium' = @{ Dev = 'Dev-Claude'; DevModel = $sonnet; Reviewer = $opus;   Advisory = $true }
    'complexity:high'   = @{ Dev = 'Dev-Claude'; DevModel = $opus;   Reviewer = $opus;   Advisory = $true }
}

if ($Stage -eq 'review' -and -not $Pr) { throw 'Stage review requires -Pr.' }

$labels = @(gh issue view $Issue --repo $Repo --json labels --jq '.labels[].name')
if ($LASTEXITCODE -ne 0) { throw "Could not read issue $Issue." }
$matched = @($labels | Where-Object { $routes.ContainsKey($_) })
if ($matched.Count -ne 1) { throw "Issue $Issue needs exactly one complexity label; found $($matched.Count)." }
$route = $routes[$matched[0]]

$cli = Join-Path $env:LOCALAPPDATA 'Programs\OpenBot\resources\cli\openbot-cli.mjs'

function Invoke-Agent([string[]]$Arguments) {
    if ($DryRun) { Write-Output "openbot-cli $($Arguments -join ' ')"; return }
    & node $cli @Arguments
    if ($LASTEXITCODE -ne 0) { throw "openbot-cli failed with exit code $LASTEXITCODE." }
}

if ($Stage -eq 'dev') {
    if ($route.DevModel) { Invoke-Agent @('agent', 'set-model', $route.Dev, $route.DevModel) }
    Invoke-Agent @('run', $route.Dev, "Implement issue #$Issue of $Repo.")
    return
}

if ($route.Advisory) {
    Invoke-Agent @('agent', 'new-chat', 'Reviewer-Local')
    Invoke-Agent @('run', 'Reviewer-Local', "Advisory review of pull request #$Pr of $Repo.", '--wait')
}
Invoke-Agent @('agent', 'set-model', 'Reviewer-Claude', $route.Reviewer)
Invoke-Agent @('agent', 'new-chat', 'Reviewer-Claude')
Invoke-Agent @('run', 'Reviewer-Claude', "Review pull request #$Pr of $Repo and give the deciding verdict.")
