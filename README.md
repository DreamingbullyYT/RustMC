# rustMC — Android integration for Horizon

This repository contains Android-specific integration code. Horizon remains an
upstream Git submodule at `upstream/horizon`; we do not vendor or permanently
fork its source tree.

- Upstream: https://github.com/horizon-rust/horizon
- Tracked upstream branch: `main`
- Exact checked-out upstream revision: [`UPSTREAM.lock`](UPSTREAM.lock)

## Checkout

```sh
git clone https://github.com/DreamingbullyYT/rustMC.git
cd rustMC
git submodule update --init --recursive
./scripts/verify-upstream.sh
```

## Upstream synchronization

GitHub Actions checks upstream every 6 hours (and can also be run manually).
When upstream `main` advances, the job updates the submodule gitlink and
`UPSTREAM.lock` to the exact new commit, applies the Android patch series from
`patches/*.patch`, and runs the repository checks.

No commit is created when upstream has not changed. If a patch no longer applies,
the job fails instead of silently dropping or rewriting the patch. The upstream
submodule itself is never pushed to or modified on the upstream server.

To update locally:

```sh
./scripts/update-upstream.sh
python3 -m unittest discover -s tests -v
./scripts/verify-upstream.sh
```

## Android patch policy

- Android integration belongs in separate modules under `android/`.
- Keep upstream source changes as small, reviewable patch files in `patches/`.
- Never edit files under `upstream/horizon` manually and commit them as if they
  were upstream changes.
- Every patch must apply cleanly to the tracked upstream revision.
- If upstream changes break a patch, stop synchronization and fix the patch
  deliberately; do not skip it automatically.

## Current status

This is still the repository/bootstrap stage, not a working Android build.
The six-hour upstream tracking and patch-application pipeline is being established;
the Android adapter, lifecycle integration, packaging, and APK pipeline remain
to be implemented and tested.
