---
name: resolve-issue
description: Takes an assigned issue from reading it to an open pull request — plan, branch, implement, test, pull request. Use when an issue is ready to be implemented. Do not use to triage or to assess an issue, or on an issue nobody is assigned to.
---

# Resolve an issue

## Prerequisites

- `gh` installed and authenticated.
- The issue number, and the issue assigned to whoever runs this.

## Steps

1. **Read the issue** with its whole comment thread: `gh issue view <n> --comments`. Read the linked issues too.
2. **Read the repository's `CLAUDE.md`.** It holds the build, test and release commands and the conventions that are not obvious from the code.
3. **Understand the code** the issue touches before proposing anything. Follow the existing patterns; the repository's way beats a better way invented here.
4. **Plan, then wait.** Post or state one concise plan: approach, files, tests, out of scope, risks. List every unresolved decision with a recommended answer and a one-line why. Do not start implementing before the plan is approved.
5. **Branch.** `<name>/issue-<n>` off the default branch.
6. **Implement the plan and nothing else.** A blocker, or a flaw that only shows up in the code, stops the work with a comment — never a half-finished push.
7. **Test.** Add or update the tests the plan named, and run the repository's lint, typecheck and test commands until they pass.
8. **Update the docs** wherever behavior changed: the README, the docs site, the CLI help.
9. **Open the pull request** with the `create-pr` skill.

## Rules

- Going back up a layer is allowed and expected: when the issue turns out to ask the wrong question, say so instead of implementing it.
- Never widen the scope. A second problem found on the way is a second issue.
- Never commit a secret, a token, a customer name or a customer identifier.
