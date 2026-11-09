# Automation

`pipeline.ps1` is the routine. Windows Task Scheduler runs it on a schedule, and each run takes one issue.

It stops and labels the issue `needs-heitor` when:

- an agent waits for a person's approval or answer;
- the Dev finishes without a pull request;
- the Reviewer leaves no verdict, or has not approved after two fix rounds;
- a pull request changes `LEARNING.md` and lacks the `heitor-answered` label.

It never starts a new issue while a pull request is open, and never touches `main`.

Remove `needs-heitor` from an issue to let it run again. If its pull request is still open, the next run reviews
it again at its current commit and then merges it. The log is `pipeline.log` in the workspace folder.

If no issue is ready, the Planner opens the next one or replies "nothing to report". It does not invent work.
