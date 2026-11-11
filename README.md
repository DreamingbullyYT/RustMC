# rustMC — Android integration for Horizon

This repository contains Android-specific integration code. The Horizon engine is
kept as an unmodified Git submodule at `upstream/horizon`, pinned to the exact
upstream commit recorded in [UPSTREAM.lock](UPSTREAM.lock).

- Upstream: https://github.com/horizon-rust/horizon
- Pinned commit: `687f3f6d3f2935f3198256de8f3820c5a6c94454`
- Submodule: `upstream/horizon`

## Checkout

```sh
git clone https://github.com/DreamingbullyYT/rustMC.git
cd rustMC
git submodule update --init --recursive
./scripts/verify-upstream.sh
```

## Repository boundary

Do not copy the engine source into this repository and do not patch files under
`upstream/horizon`. Android code belongs in separate modules under `android/`.
If an upstream extension point cannot support a required Android feature, document
the limitation and propose the smallest upstream change separately before touching
engine code.

## Current status

This commit establishes the repository boundary and pins the requested upstream
revision. It is an architecture/bootstrap stage, **not a working Android build**:
the Android native adapter, lifecycle integration, packaging and APK pipeline
still need to be implemented and tested.
