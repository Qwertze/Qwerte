# Qwerte
A Meteor Addon For 2b2t Dedicated to Regrowing &amp; Creating Farmland

Qwerte adds three modules to [Meteor Client](https://meteorclient.com):

- **Auto Till** turns nearby dirt and grass into farmland, digs and fills water holes so the farmland stays hydrated,
  plants and bone meals crops, and restocks its own seeds. With [Baritone](https://github.com/dekrom/baritone)
  installed it can also walk to new land and to water by itself, so a whole field can be built hands-free.
- **Auto Dirt** covers the ground around you with dirt, so stone, sand or netherrack can be turned into farmland. It
  never places dirt on top of dirt, and restocks its own dirt.
- **Stash Sorter** *(dev builds only, experimental)* sorts the chests and barrels around you, either into tiers or by
  the signs on them.

| | |
|---|---|
| **Minecraft** | 1.21.11 or 1.21.4, each with its own jar |
| **Meteor Client** | The build for your Minecraft version |
| **Fabric Loader** | 0.18.2 or newer |
| **Java** | 21 or newer |
| **Optional** | [dekrom's Baritone fork](https://github.com/dekrom/baritone/releases) for walking, refilling and storage trips |

## Features

Auto Till and Auto Dirt are in Meteor's **Farming** category, Stash Sorter is in **Storage**. Every feature can be
switched on and off on its own.

### Auto Till

#### Tilling
- Tills every dirt, grass block or dirt path in reach that has air above it, nearest first.
- Pick which blocks it tills, how far it reaches, how many blocks per tick and how long to wait between batches.
- Switches to a hoe from your hotbar (or pulls one out of your main inventory) and switches back afterwards.
- Skips hoes that are about to break, and only turns itself off if you truly have no usable hoe left.
- Optionally only tills blocks that would end up hydrated.
- Highlights the blocks it's about to till.

#### Watering
- When tillable land has no water within 4 blocks, it finds the spot that would water the most of it, digs a hole there
  with your best tool and fills it from a water bucket.
- Only digs where the water can't spill: solid on all four sides and underneath, and never the block you stand on.
- Checks where the bucket will land right before using it, so water never ends up on the wrong block.
- Holds dry land back from tilling until it's watered, so new farmland doesn't dry out.
- **Fills holes in passing:** pours water into any empty 1x1 hole in reach that would water dry farmland or dirt, even
  while Baritone is walking, so holes missed earlier get filled on the way past.

#### Planting and bone meal
- Plants your chosen seeds on empty farmland: wheat, beetroot, melon, pumpkin and torchflower seeds, carrots, potatoes
  and pitcher pods.
- Uses bone meal on crops growing on farmland until they're fully grown.
- Works in order: till first, then plant what's tilled, then grow what's planted.

#### Seed restocking
- **Storage mode:** takes seeds from a chest, barrel or any other container you pick, walking there and back with
  Baritone if it's out of reach.
- **Ender Chest mode:** places your ender chest, takes the shulker holding the most of your seeds, places and empties
  the shulker into your inventory, breaks it, puts it back in the exact ender chest slot it came from, then breaks the
  ender chest with a silk touch pickaxe and picks it back up. It checks Meteor's memory of your ender chest first, keeps
  a slot free for the shulker, and always puts everything back even if something goes wrong.
- Restocks before doing anything else when you're low, so it never tills land it has nothing to plant on.
- Notices when the storage, or every shulker in your ender chest, has run out of seeds, and turns seed restocking off
  with a message instead of trying again and again.

#### Automation (needs Baritone)
- **Auto walk:** when nothing is left in reach, walks to the nearest untilled land within a radius of where you turned
  the module on.
- **Water refills:** when your buckets run dry, walks to the nearest 4x4 infinite water source (or a refill station you
  choose), fills every empty bucket, then walks back to where it was working.
- Fills only from water that grows back and waits for it to refill between buckets, so pools never drain.
- Stands on the bank to fill, and turns off Baritone's block placing in water while it drives, so pools aren't filled
  in on the way out.
- Keeps Baritone from breaking your farmland or using your water buckets to break falls. Your own Baritone settings
  are restored when the module turns off.
- Remembers the last pool it used, recovers when Baritone stalls, and puts your held item back after every trip.
- **Combined trips:** after filling buckets it restocks seeds before walking back if you're running low, and after a
  seed trip it refills any empty buckets, so one trip covers both.

### Auto Dirt

#### Covering
- Places dirt on every empty spot in reach that sits on a solid block that isn't dirt, nearest first.
- Never places dirt on dirt, grass, coarse or rooted dirt, podzol, mycelium, mud, dirt paths or farmland, so it never
  stacks.
- Never places into water or lava, or where you or another entity is standing.
- Only covers ground below your feet by default, so walls and ledges are left alone.
- Pick which dirt block to place, how far it reaches, how fast it places, and blocks it should never cover.
- Pulls dirt from your main inventory into the hotbar, and switches back to your previous slot afterwards.
- Works alongside auto-till, which tills the dirt as it's laid.

#### Dirt restocking
- **Storage** and **Ender Chest** modes, the same as seed restocking.
- Can walk to a storage that's out of reach with Baritone, and back again.
- Restocks before placing when you're low, and turns restocking off when the storage has run out of dirt.

### Stash Sorter (experimental)

#### A saved stash
- The first time, it reads every chest, trapped chest and barrel within `radius` (a double chest counts as one) and
  saves the stash under `stash-name`. After that it only reads containers it hasn't seen before.
- The saved stash stays up to date: whenever you open one of its containers yourself, even with the module off, what's
  inside is saved.
- Each stash name on each server is its own stash, so you can keep several. `Rescan` and `Forget stash` buttons in
  the module's settings start over.

#### Sorting
- **Tiers mode** fills the containers nearest to where you turned it on first, in this order: filled shulkers, empty
  shulkers, items that stack to 64, items that stack to 16, then singles and everything else.
- **Signs mode** reads the signs on the side of each container or standing on top of it. Each item goes to the
  container whose signs describe it best. Items no sign describes stay where they are.

#### What signs understand
Write signs the way you'd talk. Each line is read on its own, a sign's lines are also read together, and lists like
`Logs, Planks & Wood` are split up. Filler like "stuff", "items" or "supplies" is ignored, so `Redstone Stuff` works.

- **Exact names** win over everything: `Diamond Block`, `Totem of Undying`.
- **Words** in an item's name: `diamonds`, `ores`, `gold` (also finds golden items), `oak`, `copper`.
- **Hundreds of categories and slang**, for example:

| Kind | Sign words |
|---|---|
| Shulkers | `shulkers`, `full shulkers`, `kits`, `empty shulkers` |
| PvP | `pvp`, `cpvp`, `crystals`, `obby`, `totems`, `gapples`, `egaps`, `pearls`, `anchors`, `xp bottles` |
| Gear | `armor`, `helmets`, `boots`, `elytra`, `weapons`, `swords`, `bows`, `arrows`, `tools`, `pickaxes`, `enchanted`, `godset`, `damaged` |
| Valuables | `valuables`, `loot`, `treasure`, `rare`, `diamond`, `netherite`, `gems`, `ingots`, `ores`, `storage blocks` |
| Building | `building blocks`, `stone`, `bricks`, `wood`, `logs`, `planks`, `glass`, `concrete`, `terracotta`, `wool`, `dirt`, `sand`, `stairs`, `slabs`, `walls`, `fences`, `doors` |
| Farming | `food`, `meat`, `fish`, `crops`, `farming`, `seeds`, `saplings`, `flowers`, `plants`, `bone meal` |
| Redstone | `redstone`, `tech`, `circuits`, `contraptions`, `rails`, `pistons` |
| Mob drops | `mob drops`, `bones`, `string`, `gunpowder`, `slime`, `blaze` |
| Brewing | `potions`, `brewing`, `alchemy`, `enchanted books`, `books` |
| Places | `nether`, `end`, `ocean` |
| Decor | `decoration`, `lighting`, `banners`, `carpets`, `beds`, `candles`, `heads`, `dyes`, `colored` |
| Other | `transport`, `fireworks`, `music discs`, `spawn eggs`, `maps`, `workstations`, `templates`, `named`, `junk` |
| By stack size | `x64` / `stackables`, `x16`, `singles` |
| Catch-all | `misc`, `other`, `overflow`, `unsorted`, `*` |

#### Excluded and drop-off containers
- Set `pick` to **Exclude** and right-click containers it should never sort into or out of. Right-click again to undo.
- Set `pick` to **Drop-off** and right-click containers to empty into the stash, for example a chest where friends
  leave items.
- Marks are saved with the stash and shown in red and blue while picking or sorting.

#### Bot mode
- With `stay-on`, it doesn't turn off when the stash is sorted. It sorts again whenever a container in the stash
  changes, and checks drop-off containers every `recheck` seconds.

#### Carry slots
- By default it carries items in every inventory slot that's empty when it starts, so your own items are never touched.
- `carry-slots` picks exactly which slots to use, like `28-36`. `keep-slots` marks slots never to touch. Slots 1-9 are
  the hotbar and 10-36 the inventory from the top left.
- When the container an item belongs in is full, it swaps the item with something in there that belongs elsewhere, so
  a stash where every container is full can still be sorted.

#### Search
- Type in the search box in the module's settings to find anything in the saved stash, including inside shulkers.
  Each result shows the item, how many you have, and where. **View** opens it in a container screen: hover it for its
  name, enchantments and everything else (press F3+H for full item data).
- With `whisper-search`, players on your `whitelist` can whisper you, for example `find diamonds`, and you whisper back
  `Diamond Can be Found At 120, 64, -300 @ stash`. Works with the module off. Replies are spaced out to avoid spam
  kicks.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft **1.21.11** or **1.21.4**.
2. Put [Meteor Client](https://meteorclient.com) for the same Minecraft version in your `mods` folder.
3. Download the `qwerte-addon` jar for your Minecraft version from the
   [latest release](https://github.com/Qwertze/Qwerte/releases/latest): `qwerte-addon-<version>+1.21.11.jar` or
   `qwerte-addon-<version>+1.21.4.jar`. Put it in your `mods` folder.
4. *(Optional, for auto-walk and refilling)* Download `baritone-api-fabric-1.3.0-<minecraft>.jar` for your version from
   [dekrom's Baritone releases](https://github.com/dekrom/baritone/releases) (for example
   [1.21.11](https://github.com/dekrom/baritone/releases/tag/v1.3.0-1.21.11) or
   [1.21.4](https://github.com/dekrom/baritone/releases/tag/v1.3.0-1.21.4)) and put it in your `mods` folder. Only have
   one Baritone jar installed at a time.
5. Start the game. Qwerte shows up under Meteor in Mod Menu, and the **auto-till** and **auto-dirt** modules are in
   Meteor's **Farming** category.

> **On 1.21.4:** Baritone for 1.21.4 has no setting to stop it placing blocks in water, so Qwerte can't switch that off
> during trips. Filling buckets from the bank still keeps pools intact; just keep an eye on your pool the first time.

> **Updating:** close Minecraft before replacing the jar, and delete the old one. Swapping a mod's jar while the game is
> running can crash it later.

## Dev builds

New features are tested on the [`dev`](https://github.com/Qwertze/Qwerte/tree/dev) branch before they reach `main`.
Each round of testing gets a **pre-release** on the [releases page](https://github.com/Qwertze/Qwerte/releases), named
like `v0.2.6-dev.1`. These are unofficial: they may be buggy and settings may change. Use the latest regular release
unless you're helping test.

## Setup guide

Open Meteor's click GUI (Right Shift by default), go to **Farming** and open **auto-till**.

Out of the box, auto-till waters, plants and walks with Baritone. Each step below says which setting controls that part.

### Just tilling
Turn off **auto-walk**, **auto-water** and **auto-plant**, then turn the module on with a hoe in your hotbar and walk
around. The purple boxes are the blocks it's about to till.

### Farming with water
1. Leave **auto-water** on in the **Water** group.
2. Carry a water bucket, plus a shovel if you want the holes dug faster.
3. Stand in a dry, flat patch. A magenta box marks where it digs the hole. Once it's filled, the land around it gets
   tilled.

### Planting
1. Leave **auto-plant** on in the **Planting** group and pick your crops in **seeds**.
2. Leave **bone-meal** on to grow them instantly while you have bone meal.

### Seed restocking
1. Turn on **seed-restock** and set **restock-at** (default 1; 0 means restock only once you've run out).
2. Pick a **restock-mode**:
   - **Storage:** turn on **pick-storage** and right-click your seed chest, or type its coordinates into
     **seed-storage**. If it's out of reach you'll need auto-walk on.
   - **Ender Chest:** keep an ender chest and a silk touch pickaxe on you, with a shulker of seeds stored in your ender
     chest. Keep at least 2 inventory slots free. If Meteor has already seen inside your ender chest, Qwerte checks for
     a seed shulker before placing anything; otherwise it checks once the chest is open.

### Full automation with Baritone
1. Install dekrom's Baritone (see [Installation](#installation)).
2. Leave **auto-walk** on in the **Automation** group and set **search-radius** (default 64) to the size of the area
   to farm. It's measured from where you turn the module on.
3. Turn on **refill-water** and choose a **refill-source**:
   - **Nearest Pool:** finds the closest 4x4 water source within **water-search-radius**.
   - **Station:** turn on **pick-station** and right-click a water block, or type its coordinates into **station**.
4. Leave **combine-trips** on to restock seeds and refill water on the same trip. Raise **seed-top-up-at** if you
   want it to top up seeds more eagerly while it's out.
5. Carry a hoe, a few empty buckets, a water bucket, seeds and bone meal, then turn the module on and let it work.

### Covering ground with dirt
1. Open **auto-dirt** in the **Farming** category.
2. Carry dirt (or the block picked in **dirt-item**) and turn the module on. The brown boxes are the spots it's about
   to fill.
3. To restock, turn on **restock** in the **Restock** group and pick a **restock-mode**, set up the same way as seed
   restocking. Turn on **walk-to-storage** if your dirt storage is out of reach.
4. Turn on **auto-till** too to till the new dirt as it goes down.

### Sorting a stash
1. Empty some inventory slots, or set `carry-slots`. The more slots, the fewer trips.
2. For **Signs** mode, put signs on your containers saying what goes in each, in your own words.
3. *(Optional)* Set `pick` to **Exclude** or **Drop-off** and right-click containers to mark them, then set it back to
   **Off**.
4. Stand among the containers, open **stash-sorter** in the **Storage** category, name the stash in `stash-name`, pick
   a `mode` and turn it on. The first run reads every container, later runs go straight to sorting.
5. Turn on `stay-on` to keep it running as a sorting bot.
6. Search the stash anytime from the module's settings.

## Settings reference

The defaults are a tuned setup that works well on 2b2t, so most people only need to set their seed storage or refill
station. If you'd rather work hands-on, turn off `auto-walk`, `auto-water` and `auto-plant`.

### auto-till

| Group | Setting | Default | Description |
|---|---|---|---|
| General | `reach` | 4.5 | Max distance from your eyes to a block's center. |
| General | `delay` | 2 | Ticks to wait between batches. |
| General | `blocks-per-tick` | 7 | How many blocks to work on per batch. |
| General | `blocks` | Grass Block, Dirt, Dirt Path, Coarse Dirt | Blocks to turn into farmland. Coarse dirt is hoed into dirt first. |
| General | `only-hydrated` | off | Only till blocks that would be hydrated. |
| General | `auto-switch` | on | Switch to the right item from your hotbar. |
| General | `swap-back` | on | Switch back to your previous slot afterwards. |
| General | `rotate` | off | Face blocks while working on them. |
| Planting | `auto-plant` | on | Plant seeds on empty farmland. |
| Planting | `seeds` | Wheat Seeds | Which seeds to plant. |
| Planting | `bone-meal` | on | Bone meal crops until fully grown. |
| Planting | `seed-restock` | off | Restock seeds when low. |
| Planting | `restock-mode` | Storage | Storage or Ender Chest. |
| Planting | `restock-at` | 1 | Restock at this many seeds or fewer. |
| Planting | `seed-storage` | 0, 0, 0 | The container to restock from. |
| Planting | `pick-storage` | off | Right-click a container to set it as the storage. |
| Water | `auto-water` | on | Dig and fill water holes for dry land. |
| Water | `fill-holes` | on | Fill empty 1x1 holes that would water dry land, even while walking. |
| Water | `water-side-color` / `water-line-color` | magenta | Colors of the planned water hole. |
| Automation | `auto-walk` | on | Walk to more land with Baritone. Turns itself off if Baritone isn't installed. |
| Automation | `refill-water` | on | Walk to water and refill empty buckets. |
| Automation | `refill-source` | Nearest Pool | Nearest Pool or Station. |
| Automation | `station` | 0, 0, 0 | The water block to refill at. |
| Automation | `pick-station` | off | Right-click a water block to set it as the station. |
| Automation | `water-search-radius` | 96 | How far to look for a 4x4 pool. |
| Automation | `search-radius` | 64 | How far from your start point to look for land. |
| Automation | `search-height` | 1 | How far up and down to look for land. |
| Automation | `combine-trips` | on | Restock seeds on water trips and refill water on seed trips. |
| Automation | `seed-top-up-at` | 128 | On a water trip, restock seeds if you have fewer than this. |
| Safety | `anti-break` | on | Don't use hoes that are about to break. |
| Safety | `min-durability` | 10 | Uses left at which a hoe counts as about to break. |
| Safety | `disable-when-out` | on | Turn off when you have no usable hoe left. |
| Render | `render` | on | Highlight blocks about to be tilled. |
| Render | `shape-mode` | Both | How the highlight is drawn. |
| Render | `side-color` / `line-color` | purple / pink | Colors of the highlight. |

### auto-dirt

| Group | Setting | Default | Description |
|---|---|---|---|
| General | `reach` | 4.5 | Max distance from your eyes to the spot to fill. |
| General | `delay` | 1 | Ticks to wait between batches. |
| General | `blocks-per-tick` | 6 | How many blocks to place per batch. |
| General | `dirt-item` | Dirt | Dirt, Coarse Dirt, Rooted Dirt or Grass Block. |
| General | `below-feet-only` | on | Only cover ground below your feet. |
| General | `depth` | 1 | How far below your feet to look for ground. |
| General | `skip-own-column` | on | Leave the block you're standing on uncovered. |
| General | `ignore` | chests, barrel, crafting table, furnace | Blocks never covered. |
| General | `rotate` | off | Face the block being placed against. |
| General | `swap-back` | on | Switch back to your previous slot afterwards. |
| General | `disable-when-out` | on | Turn off when out of dirt and unable to restock. |
| Restock | `restock` | off | Restock dirt when low. |
| Restock | `restock-mode` | Storage | Storage or Ender Chest. |
| Restock | `restock-at` | 0 | Restock at this much dirt or less. |
| Restock | `dirt-storage` | 0, 0, 0 | The container to restock from. |
| Restock | `pick-storage` | off | Right-click a container to set it as the storage. |
| Restock | `walk-to-storage` | off | Walk to an out-of-reach storage with Baritone. |
| Render | `render` | on | Highlight the spots about to be filled. |
| Render | `shape-mode` | Both | How the highlight is drawn. |
| Render | `side-color` / `line-color` | brown | Colors of the highlight. |

### stash-sorter

| Group | Setting | Default | Description |
|---|---|---|---|
| General | `stash-name` | stash | The name the stash is saved under. |
| General | `mode` | Tiers | Tiers or Signs. |
| General | `pick` | Off | Right-click to mark containers: Off, Exclude or Drop-off. |
| General | `radius` | 8 | How far around you to look for new containers. |
| General | `height` | 3 | How far above and below you to look. |
| General | `barrels` | on | Include barrels as well as chests. |
| General | `reach` | 4.5 | How close a container has to be to open it. |
| General | `delay` | 3 | Ticks between inventory clicks. Raise it if the server rejects clicks. |
| General | `walk` | off | Walk to containers out of reach with Baritone. |
| General | `rotate` | on | Face containers when opening them. |
| Bot | `stay-on` | off | Keep running after sorting, as a sorting bot. |
| Bot | `recheck` | 30 | Seconds between drop-off checks. |
| Slots | `carry-slots` | blank | Slots to carry items in. Blank uses every empty slot. |
| Slots | `keep-slots` | blank | Slots never to touch. |
| Search | `whisper-search` | off | Let whitelisted players search by whisper. |
| Search | `whitelist` | empty | Who can search by whisper. |
| Search | `prefix` | find | Whispers starting with this are searches. |
| Render | `render` | on | Highlight the container being worked on, and marked containers. |
| Render | `shape-mode` | Both | How the highlight is drawn. |
| Render | `work-color` / `excluded-color` / `drop-off-color` | orange / red / blue | Highlight colors. |

## Building from source

You need **JDK 21** or newer. Everything else, including Minecraft, Meteor and the Baritone API, is downloaded by Gradle.

```sh
git clone https://github.com/Qwertze/Qwerte.git
cd Qwerte
./gradlew build              # Minecraft 1.21.11
./gradlew build -Pmc=1.21.4  # Minecraft 1.21.4
```

On Windows use `gradlew.bat` instead of `./gradlew`. The jar ends up in
`build/libs/qwerte-addon-<version>+<minecraft>.jar`.

To test in a development client, run `./gradlew runClient`, adding `-Pmc=1.21.4` for 1.21.4. Baritone isn't included
there; add its jar to `run/mods` if you want to try the automation.

### Project layout

```
src/main/java/com/porter/qwerte/
├── QwerteAddon.java          Addon entrypoint, registers the Farming and Storage categories and modules
├── modules/
│   ├── AutoTill.java         The auto-till module: tilling, watering, planting, automation
│   ├── AutoDirt.java         The auto-dirt module: covering ground with dirt
│   ├── StashSorter.java      The stash-sorter module: sorting, bot mode, picking, search
│   └── Restocker.java        Storage and ender chest restocking, shared by auto-till and auto-dirt
├── stash/
│   ├── Stash.java            A saved stash: its containers and contents, saving, loading and search
│   ├── Categories.java       What sign words and searches mean: categories, slang and synonyms
│   └── ItemPreviewScreen.java  Shows a found item in a look-only container screen
└── utils/
    └── BaritoneHelper.java   The only class that touches Baritone, loaded only when it's installed

src/<minecraft>/java/com/porter/qwerte/compat/
├── Players.java              The selected hotbar slot
├── RightClick.java           Right clicks, for picking storages and stations
└── BaritoneFluids.java       Keeping Baritone from placing blocks in water, where the version allows it

versions/<minecraft>.properties   Minecraft, Meteor and Baritone versions for each supported Minecraft version
```

Shared code is in `src/main`. The little that differs between Minecraft versions is in `src/<minecraft>`, and only the
folder for the version being built is compiled. The addon version, Fabric Loader and Loom versions live in
`gradle/libs.versions.toml`.

To add another Minecraft version, add a `versions/<minecraft>.properties` and a `src/<minecraft>/java` folder with the
three compat classes, then build with `-Pmc=<minecraft>`.

## License

[GPL-3.0](LICENSE)
