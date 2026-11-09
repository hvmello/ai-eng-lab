# Runs one issue through Planner, Dev, Reviewer and CI, then has the Merger merge it into develop.
# Control flow lives here, in code, so no agent decides whether its own work ships.
# It stops and labels the issue `needs-heitor` whenever a person must decide.
param(
    [string[]]$Repos = @('hvmello/ai-eng-lab', 'hvmello/evidence-gate'),
    [string]$Workspace = 'F:\Borderless\agents-workspace',
    [int]$MaxRounds = 2,
    [int]$CiMinutes = 30,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'

$owner = 'hvmello'
$route = Join-Path $PSScriptRoot 'route.ps1'
$cli = Join-Path $env:LOCALAPPDATA 'Programs\OpenBot\resources\cli\openbot-cli.mjs'
$agents = 'Planner', 'Dev', 'Reviewer', 'Merger'
$logFile = Join-Path $Workspace 'pipeline.log'

function Write-Log([string]$Text) {
    $line = "$((Get-Date).ToString('s')) $Text"
    Write-Output $line
    if (-not $DryRun) { Add-Content -Path $logFile -Value $line }
}

function Invoke-Gh {
    $output = & gh @args
    if ($LASTEXITCODE -ne 0) { throw "gh $($args[0]) $($args[1]) failed." }
    return $output
}

# gh reports "no checks" and 404 on stderr, which Stop would turn into an exception.
function Invoke-GhQuiet {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & gh @args 2>&1 | ForEach-Object { "$_" }
        return [pscustomobject]@{ Code = $LASTEXITCODE; Text = ($output -join "`n") }
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Stop-ForHeitor([string]$Repo, [int]$Issue, [string]$Reason) {
    Write-Log "$Repo #${Issue}: needs Heitor. $Reason"
    if ($DryRun) { return }
    Invoke-Gh issue edit $Issue --repo $Repo --add-label needs-heitor | Out-Null
    Invoke-Gh issue comment $Issue --repo $Repo --body "Pipeline stopped: $Reason" | Out-Null
}

function Assert-Ready {
    try {
        Invoke-RestMethod 'http://localhost:11434/api/version' -TimeoutSec 5 | Out-Null
    } catch {
        Write-Log 'Ollama is down; starting it.'
        Start-Process (Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe') -ArgumentList 'serve' -WindowStyle Hidden
        Start-Sleep -Seconds 8
        Invoke-RestMethod 'http://localhost:11434/api/version' -TimeoutSec 5 | Out-Null
    }
    $listed = (& node $cli list) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'OpenBot is closed, or no agent has Local scripts on.' }
    foreach ($agent in $agents) {
        if ($listed -notmatch [regex]::Escape($agent)) { throw "Agent $agent is missing or has Local scripts off." }
    }
}

function Sync-Clone([string]$Repo) {
    $path = Join-Path $Workspace ($Repo.Split('/')[1])
    if (-not (Test-Path (Join-Path $path '.git'))) {
        Invoke-Gh repo clone $Repo $path | Out-Null
    }
    foreach ($step in @(@('fetch', '--quiet', '--prune', 'origin'), @('switch', '--quiet', 'develop'), @('pull', '--quiet', '--ff-only'))) {
        & git -C $path @step
        if ($LASTEXITCODE -ne 0) { throw "Could not update the clone of $Repo." }
    }
}

function Get-Labels($Item) { return @($Item.labels | ForEach-Object { $_.name }) }

function Get-OpenIssues([string]$Repo) {
    $open = Invoke-Gh issue list --repo $Repo --state open --limit 300 --json 'number,labels,body,author' | ConvertFrom-Json
    return @($open | Where-Object { $_ } | Sort-Object number)
}

# The single eligibility rule, used for new work and for resuming a pull request.
function Test-Eligible($Issue, [object[]]$Open) {
    $labels = Get-Labels $Issue
    if ($Issue.author.login -ne $owner) { return $false }
    if ($labels -contains 'needs-heitor' -or $labels -contains 'hotfix') { return $false }
    $openNumbers = @($Open | ForEach-Object { $_.number })
    $section = [regex]::Match([string]$Issue.body, '(?ms)^## Depends on\s*(.*?)(^## |\z)').Groups[1].Value
    $blocked = [regex]::Matches($section, '#(\d+)') | Where-Object { $openNumbers -contains [int]$_.Groups[1].Value }
    return (-not $blocked)
}

function Get-NextIssue([string]$Repo) {
    $open = Get-OpenIssues $Repo
    foreach ($issue in $open) {
        if (Test-Eligible $issue $open) { return $issue }
    }
    return $null
}

function Get-OpenPullRequests([string]$Repo) {
    $all = Invoke-Gh pr list --repo $Repo --state open --json 'number,headRefName,baseRefName,isCrossRepository' | ConvertFrom-Json
    return @($all | Where-Object { $_ -and -not $_.isCrossRepository -and $_.headRefName -match '^(feature|bugfix|chore)/\d+-' })
}

function Get-PullRequest([string]$Repo, [int]$Issue) {
    return Get-OpenPullRequests $Repo | Where-Object { $_.headRefName -match "/$Issue-" } | Select-Object -First 1
}

function Get-Head([string]$Repo, [int]$Pr) {
    return (Invoke-Gh pr view $Pr --repo $Repo --json headRefOid | ConvertFrom-Json).headRefOid
}

function Invoke-Stage([string]$Repo, [int]$Issue, [string]$Stage, [int]$Pr, [string]$Nonce, [string]$Sha) {
    $parameters = @{ Repo = $Repo; Issue = $Issue; Stage = $Stage; Workspace = $Workspace; Wait = $true }
    if ($Pr) { $parameters.Pr = $Pr }
    if ($Nonce) { $parameters.Nonce = $Nonce }
    if ($Sha) { $parameters.Sha = $Sha }
    & $route @parameters
}

# Only the last line of a comment by the owner counts, and it must carry this round's secret and the reviewed commit.
function Get-Verdict([string]$Repo, [int]$Pr, [string]$Nonce, [string]$Sha, [DateTimeOffset]$Since) {
    $pattern = "^VERDICT $Nonce $Sha (approve|changes-requested)$"
    $view = Invoke-Gh pr view $Pr --repo $Repo --json comments | ConvertFrom-Json
    $result = 'none'
    foreach ($comment in @($view.comments)) {
        if ($comment.author.login -ne $owner) { continue }
        if ([DateTimeOffset]::Parse([string]$comment.createdAt) -lt $Since) { continue }
        $lastLine = (([string]$comment.body).Trim() -split '\r?\n')[-1].Trim()
        $match = [regex]::Match($lastLine, $pattern)
        if ($match.Success) { $result = $match.Groups[1].Value }
    }
    return $result
}

function Test-CiGreen([string]$Repo, [int]$Pr) {
    $workflows = Invoke-GhQuiet api "repos/$Repo/contents/.github/workflows?ref=develop" --silent
    if ($workflows.Code -ne 0 -and $workflows.Text -notmatch 'HTTP 404') { return $false }
    $hasWorkflows = ($workflows.Code -eq 0)

    # A repository with no workflow yet may have one added by this very pull request, so its checks are still read.
    $deadline = if ($hasWorkflows) { (Get-Date).AddMinutes($CiMinutes) } else { (Get-Date).AddMinutes(2) }
    while ((Get-Date) -lt $deadline) {
        $checks = Invoke-GhQuiet pr checks $Pr --repo $Repo --json bucket --jq '.[].bucket'
        $buckets = @($checks.Text -split '\s+' | Where-Object { $_ -in 'pass', 'fail', 'pending', 'skipping', 'cancel' })
        if ($buckets.Count -gt 0 -and $buckets -notcontains 'pending') {
            return (($buckets -notcontains 'fail') -and ($buckets -notcontains 'cancel'))
        }
        if (-not $hasWorkflows -and $buckets -contains 'pending') {
            $hasWorkflows = $true
            $deadline = (Get-Date).AddMinutes($CiMinutes)
        }
        Start-Sleep -Seconds 20
    }
    if (-not $hasWorkflows -and $checks.Text -match 'no checks reported') {
        Write-Log "$Repo has no CI workflow yet."
        return $true
    }
    return $false
}

function Complete-Issue([string]$Repo, [int]$Issue, [int]$Pr, [string]$Head) {
    $view = Invoke-Gh pr view $Pr --repo $Repo --json 'state,baseRefName,isCrossRepository,headRefOid,labels' | ConvertFrom-Json
    $issueView = Invoke-Gh issue view $Issue --repo $Repo --json 'state,labels' | ConvertFrom-Json
    if ($view.state -ne 'OPEN' -or $view.isCrossRepository -or $view.baseRefName -ne 'develop') {
        Stop-ForHeitor $Repo $Issue "PR #$Pr is no longer an open pull request from this repository into develop."
        return
    }
    if ($view.headRefOid -ne $Head) { Stop-ForHeitor $Repo $Issue "PR #$Pr changed after it was reviewed."; return }
    if ($issueView.state -ne 'OPEN' -or (Get-Labels $issueView) -contains 'needs-heitor') {
        Write-Log "$Repo #${Issue}: held, so PR #$Pr was not merged."
        return
    }
    $files = Invoke-Gh pr diff $Pr --repo $Repo --name-only
    if (($files -match 'LEARNING\.md') -and ((Get-Labels $view) -notcontains 'heitor-answered')) {
        Stop-ForHeitor $Repo $Issue "PR #$Pr is approved. Answer the questions in LEARNING.md, add the label heitor-answered to the pull request and remove needs-heitor from this issue."
        return
    }
    # Every check above is code. The Merger only runs the one command it is given, and the result is verified here.
    $mainBefore = Invoke-Gh api "repos/$Repo/commits/main" --jq '.sha'
    Invoke-Stage $Repo $Issue 'merge' $Pr '' $Head
    $mainAfter = Invoke-Gh api "repos/$Repo/commits/main" --jq '.sha'
    if ($mainBefore -ne $mainAfter) {
        Stop-ForHeitor $Repo $Issue "main moved from $mainBefore to $mainAfter during the merge of PR #$Pr. Check it by hand."
        return
    }
    $merged = Invoke-Gh pr view $Pr --repo $Repo --json 'state,baseRefName,headRefOid' | ConvertFrom-Json
    if ($merged.state -ne 'MERGED' -or $merged.baseRefName -ne 'develop' -or $merged.headRefOid -ne $Head) {
        Stop-ForHeitor $Repo $Issue "the Merger did not merge PR #$Pr as instructed."
        return
    }
    Invoke-Gh issue close $Issue --repo $Repo --comment "Merged into develop in #$Pr." | Out-Null
    Write-Log "$Repo #${Issue}: merged in PR #$Pr at $Head."
}

# A plan already paid for is reused when a run is repeated.
function Test-HasPlan([string]$Repo, [int]$Issue) {
    $view = Invoke-Gh issue view $Issue --repo $Repo --json comments | ConvertFrom-Json
    return [bool](@($view.comments) | Where-Object { $_.author.login -eq $owner -and $_.body -match '^\s*## Plan' })
}

function Invoke-Issue([string]$Repo, [int]$Issue, [switch]$Resume) {
    try {
        if (-not $Resume) {
            if (-not (Test-HasPlan $Repo $Issue)) { Invoke-Stage $Repo $Issue 'plan' }
            Invoke-Stage $Repo $Issue 'dev'
        }
        $pr = Get-PullRequest $Repo $Issue
        if (-not $pr) { Stop-ForHeitor $Repo $Issue 'the Dev finished without a pull request.'; return }
        if ($pr.baseRefName -ne 'develop') { Stop-ForHeitor $Repo $Issue "PR #$($pr.number) does not target develop."; return }

        foreach ($round in 0..$MaxRounds) {
            $head = Get-Head $Repo $pr.number
            $nonce = [guid]::NewGuid().ToString('N')
            $since = [DateTimeOffset]::UtcNow
            Invoke-Stage $Repo $Issue 'review' $pr.number $nonce $head
            $verdict = Get-Verdict $Repo $pr.number $nonce $head $since
            $green = Test-CiGreen $Repo $pr.number
            Write-Log "$Repo PR #$($pr.number) round ${round}: verdict '$verdict', CI green: $green"
            if ($verdict -eq 'approve' -and $green) { Complete-Issue $Repo $Issue $pr.number $head; return }
            if ($verdict -eq 'none') { Stop-ForHeitor $Repo $Issue 'the Reviewer left no valid verdict.'; return }
            if ($round -lt $MaxRounds) { Invoke-Stage $Repo $Issue 'fix' $pr.number }
        }
        Stop-ForHeitor $Repo $Issue "still not approved after $MaxRounds fix rounds."
    } catch {
        Stop-ForHeitor $Repo $Issue "$($_.Exception.Message)"
    }
}

$lock = New-Object System.Threading.Mutex($false, 'Local\ai-eng-lab-pipeline')
if (-not $lock.WaitOne(0)) { Write-Output 'Another run is active.'; exit 0 }
try {
    if (-not $DryRun) {
        New-Item -ItemType Directory -Force $Workspace | Out-Null
        Assert-Ready
    }
    foreach ($repo in $Repos) {
        if (-not $DryRun) { Sync-Clone $repo }

        # A pull request left open is resumed with a fresh review once its issue is released.
        $paused = $false
        foreach ($pr in (Get-OpenPullRequests $repo)) {
            $number = [int][regex]::Match($pr.headRefName, '/(\d+)-').Groups[1].Value
            $open = Get-OpenIssues $repo
            $linked = $open | Where-Object { $_.number -eq $number } | Select-Object -First 1
            if (-not $linked -or -not (Test-Eligible $linked $open)) {
                Write-Log "$repo PR #$($pr.number) waits for Heitor."
                $paused = $true
                continue
            }
            Write-Log "$repo resuming PR #$($pr.number)."
            if (-not $DryRun) { Invoke-Issue $repo $number -Resume }
            exit 0
        }
        if ($paused) { continue }

        $issue = Get-NextIssue $repo
        if (-not $issue) { Write-Log "$repo has no issue ready."; continue }
        Write-Log "$repo next issue: #$($issue.number)"
        if (-not $DryRun) { Invoke-Issue $repo $issue.number }
        exit 0
    }
    Write-Log 'No issue ready in any repository.'
    # An idle run costs no tokens: the Planner is asked at most once a day.
    $stamp = Join-Path $Workspace 'planner-last-run.txt'
    $recent = (Test-Path $stamp) -and ((Get-Item $stamp).LastWriteTime -gt (Get-Date).AddHours(-24))
    if (-not $DryRun -and -not $recent) {
        Set-Content -Path $stamp -Value (Get-Date).ToString('s')
        & node $cli run 'Planner' "Open the next issue from ROADMAP.md of $($Repos[0]), or reply nothing to report. Work in $Workspace." --wait --timeout 30m
    }
} catch {
    Write-Log "Run failed: $($_.Exception.Message)"
    exit 1
} finally {
    $lock.ReleaseMutex()
}
