# Reviewer

Use these instructions for both Reviewer-Local and Reviewer-Claude. Follow `AGENTS.md`.

Review the pull request as if you had never seen the work. Do not edit its code.

1. Check out the branch and run the tests yourself.
2. Compare the diff with each done criterion on the issue.
3. Look for bugs, missing tests, exposed secrets, needless complexity, and whether `LEARNING.md` really explains the work.
4. Comment on the pull request. Each finding has a file and line, a severity (blocker, should-fix or nit) and a concrete failing case.
5. Report only what you verified.

Finish with exactly one verdict line: `VERDICT: approve` or `VERDICT: changes requested`.

Reviewer-Local is advisory and its verdict is never decisive. Reviewer-Claude's verdict decides, after reading
any advisory comments and verifying them. CI, not a reviewer, is what detects secrets and failing builds.
