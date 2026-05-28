# Two-Client Test Harness Design

## Goal

Create a repeatable local QA setup that runs two independent Starsector clients for coop testing without sharing saves, logs, screenshots, or mutable install-local state.

## Approach

Use two copied Starsector installs under `K:\Starsector-coop-test\host` and `K:\Starsector-coop-test\guest`. Each copy has its own `saves`, `screenshots`, `mods`, and `starsector-core\starsector.log`, which keeps host and guest evidence separate.

The coop mod remains developed in `K:\Starsector\mods\coop`. A deploy script builds the mod and copies it into both test installs. Launch scripts start each copied client with JVM system properties:

- Host: `-Dcoop.hostPort=7777`
- Guest: `-Dcoop.connectHost=127.0.0.1 -Dcoop.connectPort=7777`

`CoopNetPump` will continue to support campaign memory flags, and it will also read these JVM properties while idle. This keeps manual console testing available while enabling scripted two-client starts.

## Files

- `src/main/java/coop/net/CoopNetStartupConfig.java`: Parses JVM properties for host/guest startup.
- `src/main/java/coop/net/CoopNetPump.java`: Uses `CoopNetStartupConfig` before memory flags.
- `src/test/java/coop/net/CoopNetStartupConfigTest.java`: Verifies host, guest, invalid, and empty property cases.
- `scripts/setup-two-client-test.ps1`: Creates host/guest install copies under `K:\Starsector-coop-test`.
- `scripts/deploy-to-test-clients.ps1`: Builds coop and deploys it into both test installs.
- `scripts/launch-host.ps1`: Launches the host test client.
- `scripts/launch-guest.ps1`: Launches the guest test client.
- `scripts/tail-two-client-logs.ps1`: Tails recent coop lines from both logs.
- `README_DEV.md`: Documents the two-client workflow.

## Verification

Automated verification:

- `.\gradlew.bat test --tests coop.net.CoopNetStartupConfigTest`
- `.\gradlew.bat clean test build`
- `scripts/setup-two-client-test.ps1 -WhatIfOnly`
- `scripts/deploy-to-test-clients.ps1 -SkipBuild -WhatIfOnly`

Manual verification:

1. Run setup once.
2. Deploy the mod to both test clients.
3. Launch host.
4. Launch guest.
5. Start/load a campaign in each.
6. Confirm host log shows inbound `PING` and outbound `PONG`.
7. Confirm guest log shows outbound `PING` and inbound `PONG`.
