# Two-Client Test Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a repeatable two-client local Starsector test harness for coop smoke testing.

**Architecture:** Keep development in the main coop mod folder. Copy the base Starsector install into host and guest test installs, deploy the built coop mod into both, and launch clients with JVM properties that auto-start Phase 3 host/guest networking once a campaign is loaded.

**Tech Stack:** PowerShell scripts, Starsector `vmparams`, Java system properties, JUnit 5, existing Netty Phase 3 pump.

---

## Task 1: JVM Property Startup Config

**Files:**

- Create `src/main/java/coop/net/CoopNetStartupConfig.java`
- Create `src/test/java/coop/net/CoopNetStartupConfigTest.java`
- Modify `src/main/java/coop/net/CoopNetPump.java`

**Steps:**

- [ ] Write tests for host, guest, empty, and invalid property parsing.
- [ ] Run `rtk powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat test --tests coop.net.CoopNetStartupConfigTest"` and confirm tests fail because the class is missing.
- [ ] Implement `CoopNetStartupConfig` as a small immutable parser over `Properties`.
- [ ] Update `CoopNetPump` to apply JVM startup config before checking memory flags.
- [ ] Re-run the focused tests and confirm they pass.

## Task 2: Two-Client Scripts

**Files:**

- Create `scripts/setup-two-client-test.ps1`
- Create `scripts/deploy-to-test-clients.ps1`
- Create `scripts/launch-host.ps1`
- Create `scripts/launch-guest.ps1`
- Create `scripts/tail-two-client-logs.ps1`
- Modify `README_DEV.md`

**Steps:**

- [ ] Add setup script with `-WhatIfOnly`, base install root, and test root parameters.
- [ ] Add deploy script with `-WhatIfOnly` and `-SkipBuild`.
- [ ] Add host and guest launch scripts that patch temporary `vmparams` launch copies with coop JVM properties.
- [ ] Add log tail script for host and guest logs.
- [ ] Document the workflow in `README_DEV.md`.
- [ ] Run script preflight commands with `-WhatIfOnly`.

## Task 3: Verification

**Steps:**

- [ ] Run `rtk powershell -NoProfile -Command "Set-Location 'K:\Starsector\mods\coop'; .\gradlew.bat clean test build"`.
- [ ] Run `rtk powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\setup-two-client-test.ps1' -WhatIfOnly`.
- [ ] Run `rtk powershell -NoProfile -ExecutionPolicy Bypass -File 'K:\Starsector\mods\coop\scripts\deploy-to-test-clients.ps1' -SkipBuild -WhatIfOnly`.
- [ ] If preflight passes, run setup for real if the test root is outside the development install.
- [ ] Commit with `git add . && git commit -m "test: add two-client coop harness"`.
