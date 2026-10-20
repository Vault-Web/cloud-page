---
description: Biweekly repository audit that files at most two high-confidence issues, or none.
intent: Find the small number of genuinely actionable problems in the repository each week — security gaps, correctness bugs, untested critical paths — and file them as issues only when they are strong enough to be worth a maintainer's attention.

on:
  schedule: every 14 days
  workflow_dispatch:
  skip-if-match:
    query: "is:issue is:open label:agent-audit"
    max: 4

permissions:
  contents: read
  issues: read
  pull-requests: read

timeout-minutes: 25
max-turns: 80
max-ai-credits: 200
max-daily-ai-credits: 400

concurrency:
  group: "agent-weekly-audit"

tools:
  github:
    mode: gh-proxy
    toolsets: [repos, issues, pull_requests]
    allowed-repos: ["vault-web/cloud-page"]
    min-integrity: approved

safe-outputs:
  report-failure-as-issue: false
  github-app:
    app-id: ${{ vars.VAULTWEB_AGENT_APP_ID }}
    private-key: ${{ secrets.VAULTWEB_AGENT_APP_KEY }}
  create-issue:
    max: 2
    title-prefix: "[audit] "
    labels: [agent-audit]

network:
  allowed: [defaults]
---

# Repository Audit

Audit `Vault-Web/cloud-page` — the Spring Boot backend service for file and
folder management — for work that is genuinely worth doing. There is no frontend
here; the Cloud user interface lives in `Vault-Web/vault-web`.

## Before proposing anything

Search the existing issues first. Look at:

- open issues, including those labelled `agent-audit` from your previous runs
- issues closed in the last few months, so you do not re-file something that was
  rejected or already fixed
- recent commits and merged pull requests, to see whether a problem you spotted
  is already being addressed

A finding that duplicates existing work is worse than no finding at all.

## What to look for

This service manages files and folders on behalf of users. The failure modes that
actually bite here are about boundaries, state and concurrency, not about
business logic:

- **Ownership and path boundaries** — a user reaching a file that is not theirs:
  path traversal, missing ownership checks, paths that escape into `.trash` or
  outside the user's root.
- **Sharing** — permissions on a shared resource, what happens when the owner
  moves, renames or deletes it, links that outlive what they should, endpoints
  reachable without the authorization they assume.
- **Filesystem and database consistency** — an operation that writes one and not
  the other, leaving orphaned files or rows behind.
- **Atomicity** — a replace, move or upload that can leave a partial or corrupted
  file when it fails halfway.
- **Concurrency** — two operations on the same resource racing each other:
  simultaneous uploads, an edit lost because two writers overlapped, a quota check
  that passes twice.
- **Quota and trash lifecycle** — storage accounting that can be bypassed or
  double-counted, and what a file in trash costs, restores to, or silently
  overwrites.
- **Missing tests** on any of the above.

Repository hygiene and configuration problems count when they have a concrete
consequence.

## The hard rule

Create **at most two** issues, and create **none** when nothing meets the bar.
An empty run is a successful run. Do not pad the output to reach two. Do not file
a finding you cannot back with a specific file and a specific consequence.

If you have nothing strong enough, emit `noop`.

## Issue format

Each issue states the problem, the file and line, why it matters, and what a fix
would involve. Keep it short enough that a maintainer can judge it in a minute.
