# VaultMapper-Enhanced — Single Source of Truth & Change Requirement Document

**Repo:** https://github.com/IamHaque/VaultMapper-Enhanced (fork of `VaultMapper/VaultMapper`)
**Target:** Vault Hunters 3rd Edition modpack, Minecraft 1.18.2, Forge
**Document owner:** Human reviewer (Vault Hunters player) via Claude / AI Agent
**Purpose:** This file is the authoritative context + backlog for any AI agent (or human contributor) picking up work on this fork. It records what the project is, what the fork currently does, what is wrong with it from a UX and engineering standpoint, and exactly what must change. It also defines a mandatory changelog/decision-log process so future work stays auditable.

**Rule for any agent using this document:**

1. Read this whole file before touching code.
2. **Branching:** You must create and work on a separate branch (e.g., `feature/enhancements`). **Do not commit directly to the main branch.**
3. **Workflow:** Work must be broken down into manageable, verifiable BDD (Behavior-Driven Development) stories (see §8). Commit after every manageable chunk is implemented.
4. **Logging:** When you make a change, append an entry to **§10. Change Log** (below) before ending your session — do not skip this even for small edits. If you must deviate from what's specified here (different approach, different file layout, a requirement that turned out to be infeasible, etc.), record it under **§11. Agent Decision Log** with your reasoning. Never silently diverge.

---

## 1. What this project is

**VaultMapper** is a client-side Minecraft mod that renders an on-screen minimap of the "Vault" dungeon a player is currently inside, in the Vault Hunters 3E modpack. As the player explores procedurally-generated rooms, the mod tracks which rooms have been visited/discovered and draws them as a grid/graph on screen (color-coded by room type: start room, marked room, omega room, challenge room, ore room, resource room, etc.), along with an arrow for the player's current position and heading.

**VaultMapper-Enhanced** (this fork) is a single additional commit on top of upstream `VaultMapper/VaultMapper` (commit `0a221bc`, "Refactor code structure and remove redundant sections for improved readability and maintainability" — the message is misleading; the diff is almost entirely new feature code, not a refactor). That one commit adds **room/feature scanning**:

- **Loaded-room scanning (`SCAN_LOADED_ROOMS`)**: identifies room _types_ (Omega, Challenge, Ore, Resource, etc.) for rooms that are merely loaded/nearby in the chunk, even if the player hasn't walked into them yet, so the map can show "what's around me" ahead of exploration.
- **Current-room special-feature scanning**: scans the room the player is physically standing in for specific interactable objects and overlays text about them on screen:
  - **Brazier / Monolith modifiers** (`SCAN_CURRENT_ROOM_BRAZIER`) — reads the brazier's NBT and matches it against a configurable list of "target" modifier combinations (`brazier_targets.default.json`), highlighting a match.
  - **God Altars / God Challenges** (`SCAN_CURRENT_ROOM_GOD_ALTARS`) — detects god-challenge altars (Idona, Tenos, Velara, Wendarr) and displays which challenge is present, color-coded per god.
  - **Pylons** — detects vault pylons (including "time" pylons, flagged with a ⏱ icon/color).
  - **Cake** — detects the vault cake block and shows its location relative to the player.
- All of the above is driven by JSON definition files (`room_signatures.default.json`, `room_special_detection.default.json`, `brazier_targets.default.json`, `vault_rooms.json`) so new room types / features can be added without recompiling, plus a new section in the mod's config screen (`VaultMapperConfigScreen`) and `ClientConfig` to toggle these scans on/off.

**Net effect for the player:** at a glance, without opening a big map, you can tell whether there's an Omega/Challenge room nearby, and if you're standing in a room with a brazier/altar/pylon/cake, what it is and roughly where it is.

## 2. As-built state of the fork (facts, verified against the actual repo)

This section is derived from cloning the repo and inspecting the commit diff and current file contents directly (not guessed).

### 2.1 Commit shape — this is a monolith, not a series of features

The entire enhancement is **one commit**: 38 files changed, **6,217 insertions**, 205 deletions. There is no incremental history for these features — no separate commits for "add brazier scanning," "add cake scanning," "add config UI," etc. This is itself a process problem (see §7).

### 2.2 File sizes (current, after the fork's commit)

| File                                      |       Lines | Note                                                                                                                                                           |
| ----------------------------------------- | ----------: | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `map/VaultMap.java`                       |   **2,327** | Grew by +1,939/-… lines in the one commit. This is the god-object: room state, scanning, caching, detection orchestration, and geometry logic are all in here. |
| `map/VaultMapOverlayRenderer.java`        |     **986** | Grew by +546 lines. Handles both the minimap rendering _and_ now the special-feature text overlay wiring.                                                      |
| `commands/VaultMapperCommand.java`        |         272 | +123 lines added for debug/admin commands related to the new scanning features.                                                                                |
| `gui/screen/VaultMapperConfigScreen.java` |         547 | +131 lines; all new toggles bolted onto the existing single config screen (no sub-screens).                                                                    |
| `map/special/*.java` (8 files)            | 37–228 each | The one genuinely well-factored part of this commit — see §2.3.                                                                                                |
| `config/*.java` (new files)               |  9–188 each | Config plumbing for the JSON-driven detection system.                                                                                                          |

There is no per-file size ceiling anywhere in the codebase; nothing stops `VaultMap.java` from becoming 3,000+ lines next time.

### 2.3 What's already well-designed (keep this pattern)

The `map/special/` package is the one part of this commit that follows decent OOP:

- `RoomSpecialFeatureDetector` — interface each detector implements.
- `BrazierFeatureDetector`, `GodAltarFeatureDetector`, `CakeFeatureDetector`, `PylonFeatureDetector` — one class per feature type, small (~52–56 lines each), single responsibility.
- `FeatureDetectorRegistry` — simple registry/lookup keyed by feature ID, detectors self-register.
- `DetectedSpecialFeature` — a small immutable-ish DTO returned by detectors.
- `RoomSpecialNbtParser` — shared NBT-parsing helpers (vertical relation calc, name prettifying, etc.).
- `RoomSpecialTextRenderer` — draws one line of overlay text (arrow glyph + colored label).

**This is the model to extend, not abandon.** The problem is that _orchestration_ (calling these detectors, caching results, deciding what to draw and where) still lives inside the 2,327-line `VaultMap.java` instead of being pulled into its own coordinator class(es).

### 2.4 Confirmed architectural facts relevant to the requested changes

**a) Only vertical (Y-axis) relation exists today — X/Z is silently discarded.**
`RoomSpecialNbtParser.getVerticalRelationKey(int positionY, int playerY)` only compares Y and returns one of `"UP"`, `"DOWN"`, `"SAME"`. This string is packed into a `"KIND|RELATION|PAYLOAD"` line and handed to `RoomSpecialTextRenderer`, which just picks a static glyph (▲ / ▼ / ◆) — never a rotating/directional arrow. **The full `BlockPos` of the brazier/altar/pylon/cake is already available** in `VaultMap.java` (`currentRoomBrazierPos`, `currentRoomCakePos`, `altar.position`, `pylon.position` all exist as real `BlockPos` fields) — it's just thrown away at the point the display string is built. This means a 2D/3D directional indicator is a **rendering + string-format change**, not a new detection pipeline. Low-to-medium risk.

**b) `DetectedSpecialFeature` (the DTO returned by individual detectors) has no position field at all.** Position is tracked separately, only inside `VaultMap.java`'s private state (`RoomSpecialPoint`, `currentRoomBrazierPos`, etc.), not on the DTO itself. Any refactor that moves orchestration out of `VaultMap.java` needs to add `BlockPos` (or x/y/z) onto `DetectedSpecialFeature` so position travels with the feature through the whole pipeline instead of being tracked in parallel, disconnected fields.

**c) There is exactly one visual style for the map, and no visual style at all for "just the text."**
`ClientConfig` only exposes positioning/sizing for the minimap as a whole: `MAP_ENABLED`, `MAP_X_OFFSET/Y_OFFSET`, `MAP_X_ANCHOR/Y_ANCHOR`, `MAP_SCALE` (3–30), `ARROW_SCALE`. The special-feature text lines are drawn relative to the map's own position/scale inside `VaultMapOverlayRenderer` / `RoomSpecialTextRenderer` — there is no independent X/Y offset, anchor, or font-scale config for the text overlay, and no way to show the text without also showing the map, or vice versa. This directly confirms the user's complaint.

**d) No "current room only, large tile" rendering mode exists.** The renderer only knows how to draw the full multi-room map (scaled 3–30) plus optionally the special-feature text glued to it. There is no alternate "zoomed to the single room the player is standing in" view.

### 2.5 Dead / risky code observed

- A commented-out, fully dead config option: `IGNORE_RESEARCH_REQUIREMENT` in `ClientConfig.java` (left in as a `//`-commented block).
- The top commit's message ("Refactor code structure and remove redundant sections...") does not match its actual diff (pure feature addition) — this is a documentation/process smell to fix going forward (see §7), not a code smell per se.
- No automated tests were found anywhere in the repository for the new detection/scanning logic.

---

## 3. Problem statement (why this needs to change)

Reviewed from the perspective of an active Vault Hunters player using this mod live, mid-dungeon:

1. **No independent control over what's shown.** The player cannot choose "map only," "text only," or "both" — today it's all-or-nothing and tied together. Some players want to keep screen real estate for combat and only want the text callouts (e.g., "there's a cake up-left of you"), not a full grid map.
2. **No control over text position or size.** The brazier/god/pylon/cake overlay text has a fixed position and fixed font scale, unlike the map itself (which at least has offset/anchor/scale). This is inconsistent and not usable for different screen resolutions, aspect ratios, or personal HUD layouts.
3. **The full map is hard to read at a glance for "what's in the room I'm in right now."** The multi-room map is useful for overall navigation, but when you specifically want to know "where in _this_ room is the brazier/cake/altar," a small icon on a zoomed-out multi-room grid is hard to parse quickly. A dedicated, larger, single-room tile view solves a different, narrower need than the full map and should be a separate, independently configurable overlay.
4. **Cake/feature "direction" is Y-only and not actionable for search.** Telling the player "the cake is above you" (▲) is much less useful while searching a room than telling them _which horizontal direction to walk_ (X/Z) as well as up/down. A player actively hunting for the cake needs something closer to a compass/3D-pointer: rotate toward the target's horizontal bearing and indicate vertical offset, not just a static glyph.

## 4. Goals

**G1.** Make the "what's around/in this room" overlays (map, single-room tile, and text callouts) three independently toggleable display elements: Map, Room Tile, Text — any combination, including none.
**G2.** Make position, anchor, and scale of the Room Tile and of the Text overlay independently configurable, the same way the Map already is (offset X/Y, anchor X/Y, scale), each with its own config keys — not shared with the map's.
**G3.** Add a new "Room Tile" rendering mode: a larger, single-room-only view (just the cell the player currently occupies, not the whole explored map) showing the special features (brazier/altar/pylon/cake icons) positioned within that room, independently sized/positioned from the full map.
**G4.** Upgrade feature-direction indication from Y-only (▲▼◆) to full 3D: a horizontal bearing indicator (rotating arrow/compass pointing toward the target's X/Z direction relative to player facing or to world north — decide and document, see §8 open question) _combined with_ a vertical indicator (above/level/below), starting with the Cake feature, then generalized to God Altar/Brazier/Pylon.
**G5.** Reduce `VaultMap.java` and `VaultMapOverlayRenderer.java` to a maintainable size by extracting cohesive responsibilities into their own classes, following the pattern already established by `map/special/*`.
**G6.** Remove identified dead code (see §2.5) and prevent recurrence via a stated file-size/SRP convention (§7).
**G7.** Every future change against this backlog must be logged in §9/§10 of this document (or a linked CHANGELOG) so a subsequent agent/human has full traceability of what was done and why, including any deviation from this spec.

## 5. Non-goals / out of scope (for now)

- Server-side or multiplayer-sync behavior changes (the mod is explicitly client-side; `SYNC_*` config is unrelated to this work).
- Adding scanning for new feature types beyond Brazier/God Altar/Pylon/Cake (the detector framework already supports this generically — extending it to new feature types is a natural follow-up but not requested here).
- Rewriting the room-type scanning (`SCAN_LOADED_ROOMS`) system — only the _current-room special feature_ overlays (Brazier/Altar/Pylon/Cake) and their display are in scope for G1–G4.
- Full automated test suite for the whole mod (though new code introduced under this plan should be written to be testable, and simple unit tests for pure-logic classes like a new bearing calculator are encouraged).

## 6. Functional requirements (detailed)

### FR-1 — Independent display-element toggles

- New config booleans, distinct from `MAP_ENABLED`:
  - `SHOW_MINIMAP` (renames/aliases current `MAP_ENABLED` semantics — full multi-room map)
  - `SHOW_ROOM_TILE` (new — the single current-room large tile)
  - `SHOW_SPECIAL_TEXT` (new — the brazier/god/pylon/cake text callouts)
- All three must be independently toggleable in any combination, including all-off.
- Config screen must expose all three as separate checkboxes/buttons, not nested inside one master switch (other than a mod-wide "enabled" kill switch, which is fine to keep).

### FR-2 — Independent position/size config per element

For **Room Tile** and **Text overlay**, mirror the existing map config shape:

- `ROOM_TILE_X_OFFSET`, `ROOM_TILE_Y_OFFSET`, `ROOM_TILE_X_ANCHOR`, `ROOM_TILE_Y_ANCHOR`, `ROOM_TILE_SCALE`
- `SPECIAL_TEXT_X_OFFSET`, `SPECIAL_TEXT_Y_OFFSET`, `SPECIAL_TEXT_X_ANCHOR`, `SPECIAL_TEXT_Y_ANCHOR`, `SPECIAL_TEXT_SCALE` (font scale — today's `scale` param on `drawSpecialInfoLine` is hardcoded/passed in from the map's own scale; it must become independently configurable)
- Reuse the existing anchor convention (0/2/4 = left|top, center, right|bottom on each axis) for consistency with `MAP_X_ANCHOR`/`MAP_Y_ANCHOR` rather than inventing a new one.
- All new keys must be exposed on the in-game config screen (drag/drop or numeric entry, consistent with how map offset/scale is currently edited) — not JSON-file-only.

### FR-3 — Room Tile rendering mode (new)

- New renderer (or new method on a renamed/extracted renderer, see §7) that draws **only the room the player currently occupies** — not the whole discovered map — at a larger, independently-configured scale.
- Must show the special features detected in that room (brazier, altar, pylon, cake) as icons/markers positioned within the tile at their approximate in-room location (this requires reusing the already-available `BlockPos` data — see §2.4a — projected into room-local coordinates).
- Must respect `SHOW_ROOM_TILE` and the FR-2 sizing/position config independently of the minimap.
- Out of scope: showing neighboring rooms in this view — that's what the minimap is for. Keep the two views' responsibilities distinct (SRP at the feature level, not just the class level).

### FR-4 — 3D directional indicator, starting with Cake

- Replace/extend the current `"UP"/"DOWN"/"SAME"` vertical-only relation with a bearing calculation that also produces a horizontal direction (angle or 8/16-point compass bucket) from player position to target position, using the X/Z delta already available via the existing `BlockPos` fields.
- Renderer: a rotating arrow (rotate the existing glyph, or a small compass-style icon) that visually points toward the horizontal bearing of the target, plus a distinct, still-visible vertical cue (e.g., color, up/down chevron, or vertical offset text like today's ▲/▼) so both axes are conveyed at once.
- **Decide and document as part of implementation (see open question in §8):** whether horizontal bearing should be relative to true world direction (north-up, like a map) or relative to the player's current look/facing direction (ego-centric, like a HUD compass). Record the decision in §10.
- Implement for **Cake first** (the user's explicit priority), landing the shared bearing-calculation utility in a reusable place (e.g., a new small class in `map/special/`, not duplicated per feature) so it can be applied to God Altar / Brazier / Pylon afterward with minimal extra work.
- `DetectedSpecialFeature` should be extended to carry the target `BlockPos` (see §2.4b) so the renderer/bearing-calculator doesn't need to reach back into `VaultMap.java` private state.

### FR-5 — Config screen updates

- Add the new toggles and position/scale controls from FR-1/FR-2 to `VaultMapperConfigScreen`.
- Given the screen is already 547 lines and growing, split it rather than keep bolting on sections — see §7.2.

## 7. Engineering standards for this and future work

These are binding constraints for any agent/contributor working from this document, not suggestions.

### 7.1 File size / single-responsibility limits

- **Soft limit: ~300 lines per class. Hard limit: ~500 lines.** If a class is approaching either limit, that's a signal to extract a collaborator, not to keep appending methods.
- `VaultMap.java` (2,327 lines) and `VaultMapOverlayRenderer.java` (986 lines) are both already over the hard limit and must be decomposed as part of this work, not left as-is with new code added on top. Suggested extraction targets (agent should confirm exact boundaries against current code before extracting):
  - A `RoomSpecialFeatureScanner`/`RoomSpecialFeatureCoordinator` class to own calling the `FeatureDetectorRegistry`, caching (`RoomSpecialDetectionCacheEntry` and friends), and producing `DetectedSpecialFeature` results — pulled out of `VaultMap.java`.
  - A `VaultRoomTileRenderer` (new, for FR-3) kept separate from `VaultMapOverlayRenderer` (existing minimap renderer) — two renderers, two files, shared helpers factored into a small utility class rather than inherited/duplicated.
  - A `SpecialFeatureBearingCalculator` (or similarly named) pure-logic class for FR-4's 3D bearing math, kept independent of any rendering code so it's unit-testable without a Minecraft client context.
- Config classes should stay one concern each, as the fork already mostly does (`BrazierTargetConfigManager`, `RoomSpecialDetectionConfigManager`, etc.) — continue that pattern for any new config surface rather than growing `ClientConfig.java` indefinitely.

### 7.2 Config screen

- `VaultMapperConfigScreen.java` should be split into logical sub-panels/tabs (e.g., Map, Room Tile, Text/Overlay, Scanning toggles, Sync) rather than one flat 500+ line screen with everything appended in sequence. Exact UI mechanism (tabs vs. sub-screens vs. scrollable sections) is an implementation decision for whoever builds FR-5 — record the choice in §10.

### 7.3 DRY / SOLID

- Any logic duplicated between the Map renderer and the new Room Tile renderer (icon drawing, color lookups, etc.) must be factored into a shared helper rather than copy-pasted — both should depend on the same small rendering-utility class(es).
- Detectors must remain closed for modification / open for extension: new feature types are added by writing a new `RoomSpecialFeatureDetector` implementation and registering it, never by adding `if (kind.equals("newthing"))` branches inside shared renderer/coordinator code. Preserve this discipline when doing FR-3/FR-4.
- Config value objects (`RoomSpecialFeatureDefinition`, `DetectedSpecialFeature`, etc.) should stay small, immutable-shaped DTOs — do not turn them into god-objects as new fields (e.g., position for FR-4) are added.

### 7.4 Dead code

- Remove the commented-out `IGNORE_RESEARCH_REQUIREMENT` block in `ClientConfig.java` (§2.5) unless there's a concrete near-term plan to ship it — if kept, it must be tracked as a real, uncommented, disabled-by-default config with a comment explaining why it's off, not commented-out code.
- As part of the `VaultMap.java`/`VaultMapOverlayRenderer.java` decomposition, any method/field that becomes unused after extraction must be deleted in the same change, not left behind "just in case."
- Sweep for other commented-out code blocks across the touched files during this work and remove or justify each one explicitly in the change log.

### 7.5 Commit hygiene (process fix, not code fix)

- Going forward, this backlog (FR-1 through FR-5, plus the §7.1 decomposition) should land as **separate, reviewable commits/PRs**, not one 6,000-line commit like the one this document is reviewing. Suggested breakdown: (1) decomposition/refactor of existing files with no behavior change, (2) FR-1/FR-2 config plumbing, (3) FR-3 room tile renderer, (4) FR-4 bearing indicator for cake, (5) FR-4 extended to other features, (6) FR-5 config screen split.
- Commit messages must accurately describe the diff (the top commit's "Refactor... remove redundant sections" message describing a 6,217-line feature-adding diff is the anti-pattern to avoid).

## 8. AI Agent Workflow & BDD Requirements (MANDATORY)

To ensure the project remains stable, verifiable, and trackable, the AI agent must adhere strictly to the following workflow conventions:

### 8.1 Branching & Committing

- **No Main Branch Commits:** All modifications must be made on a dedicated, separate Git branch (e.g., `feature/ux-rendering-overhaul` or `feature/bdd-task-name`).
- **Manageable Chunks:** Code must be implemented and committed in small, manageable increments corresponding to individual BDD stories. Do not create massive, monolithic commits.
- **Commit Documentation:** Update this document's Change Log (§10) and commit your changes _immediately_ after completing any functional chunk.

### 8.2 BDD Task Breakdown (Given-When-Then)

Before writing code for any Functional Requirement (FR-1 to FR-5) or structural refactoring, the agent must define the work as manageable **BDD Stories**. Every piece of work must be documented in the agent's internal plan or PR following this structure:

- **Story ID & Title:** (e.g., `STORY-1: Extract Text Rendering Toggles`)
- **Dependencies:** List the exact Story IDs this task depends on (e.g., `Depends on STORY-0`, or `None / Concurrent`).
- **Given:** [The initial state of the system or context]
- **When:** [The action taken by the system or user]
- **Then:** [The expected observable result or state change]
- **Verifiability:** Provide a concrete, actionable instruction on how to test this specific story (e.g., "Open the config screen, uncheck `SHOW_SPECIAL_TEXT`, walk into a room with a Cake, and verify the UI overlay is hidden but the minimap remains visible").

The agent must ensure that it addresses prerequisites and dependencies in the correct order, leaving the codebase in a compileable and testable state at the end of each story.

## 9. Open questions (must be resolved before or during implementation, and recorded in §10 once decided)

1. **Bearing reference frame (FR-4):** world-north-relative compass, or player-facing-relative HUD arrow? Each has different math and different player expectations (map-style vs. FPS-HUD-style). No decision has been made yet — pick one, document it, and note the tradeoff.
2. **Room Tile content scope (FR-3):** should the Room Tile show _only_ special features (brazier/altar/pylon/cake), or also room boundaries/exits/other players? Default assumption per the user's brief: features + room shape only, no neighboring-room data. Confirm before building.
3. **Anchor/offset units:** should Room Tile and Text overlay reuse literal pixel offsets like the map, or should Text specifically support percentage-based positioning for better cross-resolution behavior? Default assumption: keep pixel-based to match existing map config for consistency, unless this proves awkward in testing.
4. **Backward compatibility of config keys:** renaming `MAP_ENABLED` semantics (FR-1) may need a migration note for existing users' config files. Decide whether to keep `MAP_ENABLED` as-is and simply add the two new booleans alongside it (lower risk) rather than renaming anything.

---

## 10. Change Log

_(Append one entry per work session/PR. Do not edit past entries — add new ones.)_

| Date       | Agent/Author    | Summary of change                                                                          | Files touched | Requirement(s) addressed |
| :--------- | :-------------- | :----------------------------------------------------------------------------------------- | :------------ | :----------------------- |
| 2026-07-27 | Claude (Sonnet) | Initial creation of this SSOT/requirements document from repo analysis.                    | (docs only)   | N/A — baseline           |
| 2026-07-27 | Gemini (Agent)  | Injected strict AI workflow constraints, branch rules, and BDD task tracking requirements. | (docs only)   | Agent Workflow Update    |

## 11. Agent Decision Log

_(Record any place you deviated from this document, made an assumption not specified here, or resolved one of the open questions. Include your reasoning.)_

| Date | Agent/Author | Decision / deviation | Reasoning |
| :--- | :----------- | :------------------- | :-------- |
| —    | —            | —                    | —         |

---

## Appendix A — Key file inventory (as of commit `0a221bc`)

```text
src/main/java/com/nodiumhosting/vaultmapper/
├── VaultMapper.java                                  (+8 lines this commit)
├── commands/VaultMapperCommand.java                  272 lines
├── config/
│   ├── BrazierTargetConfig.java                      9
│   ├── BrazierTargetConfigManager.java                188
│   ├── ClientConfig.java                              107  (contains dead commented-out IGNORE_RESEARCH_REQUIREMENT)
│   ├── RoomSignatureConfig.java                       39
│   ├── RoomSignatureConfigManager.java                 73
│   ├── RoomSpecialDetectionConfig.java                 29
│   ├── RoomSpecialDetectionConfigManager.java          129
│   ├── RoomSpecialFeatureDefinition.java               52
│   ├── RoomSpecialScanToggleConfig.java                9
│   └── RoomSpecialScanToggleConfigManager.java         150
├── events/ (DimensionChangeEvent, KeybindEvents, NetworkEvent — small edits)
├── gui/screen/VaultMapperConfigScreen.java             547  (was ~416 before this commit)
├── map/
│   ├── VaultMap.java                                   2,327  ⚠ over hard limit — decompose
│   ├── VaultMapOverlayRenderer.java                    986    ⚠ over hard limit — decompose
│   ├── snapshots/MapCache.java                         (minor edit)
│   └── special/
│       ├── BrazierFeatureDetector.java                 56
│       ├── CakeFeatureDetector.java                     52
│       ├── DetectedSpecialFeature.java                  34   (needs BlockPos field — see §2.4b)
│       ├── FeatureDetectorRegistry.java                 47
│       ├── GodAltarFeatureDetector.java                 52
│       ├── PylonFeatureDetector.java                    55
│       ├── RoomSpecialFeatureDetector.java              37
│       ├── RoomSpecialNbtParser.java                    228  (getVerticalRelationKey lives here — §2.4a)
│       └── RoomSpecialTextRenderer.java                 106
├── mixin/PlayerTabOverlayMixin.java                    (minor edit)
└── util/
    ├── ColorUtil.java                                   23 (new)
    └── VaultDimensionUtil.java                          28 (new)

Data files (repo root / resources):
├── brazier_modifiers.json                              31 (new)
├── vault_rooms.json                                     1,730 (new — data, not code)
└── src/main/resources/
    ├── brazier_targets.default.json                     30 (new)
    ├── room_signatures.default.json                     367 (new)
    └── room_special_detection.default.json               69 (new)
```

## Appendix B — Current `ClientConfig` keys relevant to this work (verbatim, for reference)

- `MAP_ENABLED`, `MAP_X_OFFSET`, `MAP_Y_OFFSET`, `MAP_X_ANCHOR`, `MAP_Y_ANCHOR`, `MAP_SCALE` (3–30), `ARROW_SCALE` (3–30)
- `SCAN_LOADED_ROOMS`, `IDENTIFICATION_EXTRA_CELL_RADIUS`
- `SCAN_CURRENT_ROOM_GOD_ALTARS`, `SCAN_CURRENT_ROOM_BRAZIER`
- (Cake and Pylon scanning currently have no dedicated top-level enable flag distinct from the general special-feature system — confirm current gating logic in `RoomSpecialScanToggleConfigManager`/`RoomSpecialDetectionConfigManager` before adding FR-1's new toggles, to avoid creating redundant/overlapping switches.)

**No `TEXT_*` or `ROOM_TILE_*` keys exist today.** These are net-new per FR-2.
