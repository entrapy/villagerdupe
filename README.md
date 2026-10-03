# VillagerDupe

A standalone Fabric client mod targeting Minecraft 1.21.6 and newer. It adds one client-side command, `/villagerdupe`, with an optional repeating mode. Fabric API is required.

**Current download:** The v1.0.0 jar in [Releases](https://github.com/entrapy/villagerdupe/releases) is built for Minecraft **26.2 only**. Fabric will reject that jar on 1.21.6. A jar for 1.21.6 has not been released yet.

## Use

Keep an emerald in your hotbar and shears in your inventory or offhand. Stand within normal interaction range of a villager. The command selects the emerald and, when needed, swaps shears into the offhand. It sends an offhand interaction when the villager holds a saddle, horse armor, or carpet.

| Command | Action |
| --- | --- |
| `/villagerdupe` | Arm one interaction. Run it again to cancel. |
| `/villagerdupe auto` | Toggle repeating interactions for all supported items. |
| `/villagerdupe auto saddle` | Toggle repeating interactions for saddles. |
| `/villagerdupe auto horse_armor` | Toggle repeating interactions for horse armor. |
| `/villagerdupe auto carpet` | Toggle repeating interactions for carpets. |

Repeating mode checks every client tick and sends at most one interaction every 500 ms. Its enabled state and filter are saved in `config/villagerdupe.properties`. Running `auto` with a different filter changes the filter; running it with the current filter turns it off.

The mod will not automatically replace an offhand totem. Equip shears yourself if you want to use the command while carrying one. The command does not open an inventory screen or manage other equipment.

## Build

The current source and build configuration target Minecraft 26.2. Use Java 25 and run `./gradlew build` (or `gradlew.bat build` on Windows). The mod jar is created in `build/libs/`. Install it with Fabric Loader and Fabric API for Minecraft 26.2.
