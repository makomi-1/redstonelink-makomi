![title.png](/docs_dev/background_icon.png)

[![CurseForge](https://img.shields.io/badge/CurseForge-Not%20Published-f16436?logo=curseforge&logoColor=white)](https://www.curseforge.com/minecraft/mc-mods) [![Modrinth](https://img.shields.io/badge/Modrinth-Published-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/mod/redstonelink-makomi) [![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg?logo=gnu)](../LICENSE) [![GitHub](https://img.shields.io/badge/GitHub-release%2Fno--mixin-181717?logo=github&logoColor=white)](https://github.com/makomi-1/redstonelink-makomi/tree/release/no-mixin)

[中文](../README.md)  |  English

# Disclaimer
- This project uses `AI` to accelerate implementation, and quality is guarded by review and testing.
- Currently supports `fabric-1.21.1` only.
- [Bug Report](https://www.wjx.top/vm/PpvzYjl.aspx)
- The `no-mixin` branch means the link/core slice feature is not implemented through mixins, not that the branch contains no mixins at all.

# Overview

A wireless redstone mod built around a `time-priority + fixed priority within the same tick` arbitration model and `serial-number pairing`. It supports `cross-chunk` links, `relay delay`, and `permission management`, and also provides transparent variants of `core` nodes plus a `quick link tool`.

## Features
- Theoretically almost `no distance limit`
- Supports structured batch serial input such as `1:1000`
- Can be configured for `zero delay`
- The status panel tool can `track node states`
- Sync-style `triggerSource` nodes can `transfer exact redstone strength`

## Gameplay
Items are split into two families: `triggerSource` and `core`.
- `triggerSource` items: `toggle/pulse/sync` blocks and remotes, `pulse/toggle` buttons, and the `sync` lever
- `core` items: core blocks and core dust

Interaction flow:
- Sneak right-click to open the pairing UI with an empty main hand, or use the quick link tool to `collect` node serial numbers and `apply` links directly or through manual input.
- Activate a `triggerSource` by `redstone signal input` or `commands`, then `transmit redstone signals` to the linked `core` nodes.

## Docs
- [Full Item Guide](https://makomi-1.github.io/redstonelink-makomi/item_guide.html)
- [Recipe Sheet](https://makomi-1.github.io/redstonelink-makomi/recipe_sheet.html)
- [Mechanism Design](mechanism_design_en.md)
- [Usage Guide](usage_guide_en.md)

## Conflicts
Mods that modify vanilla `chunk loading` or `item interaction` may introduce conflict risk.
- [Conflict Mod List](../docs_dev/冲突模组名单.md) (TBD)
- [Conflict Feedback](https://www.wjx.top/vm/wTsJKow.aspx)

# Future Plans
- [ ] Add Send/receive filters
- [ ] Add recording and playback support to the status panel
- [ ] Support custom node aliases
- [ ] Expand channel matching
- [ ] Improve the UI
- [ ] Migrate to version 26.1+
- [ ] Add a visual network analyzer
