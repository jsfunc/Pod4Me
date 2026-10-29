# Working on Pod4Me

Before changing the app, read [docs/handover.md](docs/handover.md), then [README.md](README.md). The handover records the user's accepted requirements, current UI, architecture, release/signing workflow, regression traps, and pending work. Do not depend on chat history or ask the user to reconstruct decisions already recorded there.

- Inspect `git status` and current source first. Preserve existing user changes. Dates, commit IDs and artifact hashes in the notes are snapshots, not substitutes for checking the current tree.
- `goal.txt` is the original specification; later accepted decisions in the handover supersede it where they differ. `audit.txt` is a historical audit, not a current list of unfixed bugs.
- Keep the latest compact UI and storage/playback rules unless the user requests a change. In particular, the recent artwork changes intentionally exclude Library cards.
- Use explicit, data-preserving SQLite migrations. Preserve the personal signing certificate so updates do not require uninstalling the app.
- Use `./tools/build-apk.sh` for the release build. See the handover for sandbox limitations and the normal-terminal fallback. Never describe an older APK as containing new source changes.
- Run checks appropriate to the change and report separately what compiled, what passed automated tests, and what was actually tested on a phone. Do not infer device acceptance from a successful build.
- After changing behavior, build/release procedures or accepted requirements, update the relevant documentation and the handover's current-state/pending-work sections. Record actual device results in `docs/device-checks.md`.
