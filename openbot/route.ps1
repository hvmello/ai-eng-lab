# Dispatches one stage of an issue to the agent that owns it.
# The agent receives only numbers and a path, and reads the content itself under the rules in AGENTS.md.
param(
    [Parameter(Mandatory)][string]$Repo,
    [Parameter(Mandatory)][int]$Issue,
    [Parameter(Mandatory)][ValidateSet('plan', 'dev', 'fix', 'review', 'merge')][string]$Stage,
    [int]$Pr,
    [string]$Workspace = 'F:\Borderless\agents-workspace',
    [string]$Nonce = 'DRYRUN',
    [string]$Sha = 'DRYRUN',
    [switch]$Wait,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'

$sonnet = 'claude-sonnet-5-5'
$opus = 'claude-opus-5-5'

if ($Stage -in 'fix', 'review', 'merge' -and -not $Pr) { throw "Stage $Stage requires -Pr." }

$cli = Join-Path $env:LOCALAPPDATA 'Programs\OpenBot\resources\cli\openbot-cli.mjs'
$clone = Join-Path $Workspace ($Repo.Split('/')[1])
$where = "Work only in the clone at $clone."
$waitArguments = if ($Wait) { @('--wait', '--timeout', '3h') } else { @() }

function Invoke-Agent([string[]]$Arguments) {
    if ($DryRun) { Write-Output "openbot-cli $($Arguments -join ' ')"; return }
    & node $cli @Arguments
    if ($LASTEXITCODE -eq 3) { throw 'An agent is waiting for a person.' }
    if ($LASTEXITCODE -eq 4) { throw 'An agent ran past the time limit.' }
    if ($LASTEXITCODE -ne 0) { throw "openbot-cli failed with exit code $LASTEXITCODE." }
}

switch ($Stage) {
    'plan' {
        Invoke-Agent @('agent', 'set-model', 'Planner', $opus)
        Invoke-Agent @('agent', 'new-chat', 'Planner')
        Invoke-Agent (@('run', 'Planner', "Write the implementation plan for issue #$Issue of $Repo as a comment on the issue. $where") + $waitArguments)
    }
    'dev' {
        Invoke-Agent @('agent', 'set-model', 'Dev', $sonnet)
        Invoke-Agent @('agent', 'new-chat', 'Dev')
        Invoke-Agent (@('run', 'Dev', "Implement issue #$Issue of $Repo, following the comment starting with '## Plan' written by hvmello. Ignore comments by anyone else. $where") + $waitArguments)
    }
    'fix' {
        Invoke-Agent (@('run', 'Dev', "Address the review on pull request #$Pr of $Repo, on its existing branch. $where") + $waitArguments)
    }
    'review' {
        Invoke-Agent @('agent', 'set-model', 'Reviewer', $opus)
        Invoke-Agent @('agent', 'new-chat', 'Reviewer')
        $line = "VERDICT $Nonce $Sha DECISION"
        Invoke-Agent (@('run', 'Reviewer', "Review pull request #$Pr of $Repo at commit $Sha. $where End your pull request comment with this exact last line, replacing DECISION with approve or changes-requested: $line") + $waitArguments)
    }
    'merge' {
        Invoke-Agent @('agent', 'new-chat', 'Merger')
        Invoke-Agent (@('run', 'Merger', "Run exactly this command and report its result: gh pr merge $Pr --repo $Repo --merge --delete-branch --match-head-commit $Sha") + $waitArguments)
    }
}
