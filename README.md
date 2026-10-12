# Qwerte
A Meteor Addon For 2b2t Dedicated to Regrowing &amp; Creating Farmland

Qwerte adds two modules to [Meteor Client](https://meteorclient.com):

- **Auto Till** turns nearby dirt and grass into farmland, digs and fills water holes so the farmland stays hydrated,
  plants and bone meals crops, and restocks its own seeds. With [Baritone](https://github.com/dekrom/baritone)
  installed it can also walk to new land and to water by itself, so a whole field can be built hands-free.
- **Auto Dirt** covers the ground around you with dirt, so stone, sand or netherrack can be turned into farmland. It
  never places dirt on top of dirt, and restocks its own dirt.

| | |
|---|---|
| **Minecraft** | 1.21.11 |
| **Meteor Client** | 1.21.11 |
| **Fabric Loader** | 0.18.2 or newer |
| **Java** | 21 or newer |
| **Optional** | [dekrom's Baritone fork](https://github.com/dekrom/baritone/releases) for walking, refilling and storage trips |

## Features

Both modules are in Meteor's **Farming** category. Every feature can be switched on and off on its own.

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

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft **1.21.11**.
2. Put [Meteor Client](https://meteorclient.com) for 1.21.11 in your `mods` folder.
3. Download the `qwerte-addon` jar from the [latest release](https://github.com/DustinFoes/Qwerte/releases/latest) and
   put it in your `mods` folder.
4. *(Optional, for auto-walk and refilling)* Download `baritone-api-fabric-1.3.0-1.21.11.jar` from
   [dekrom's Baritone releases](https://github.com/dekrom/baritone/releases/tag/v1.3.0-1.21.11) and put it in your
   `mods` folder. Only have one Baritone jar installed at a time.
5. Start the game. Qwerte shows up under Meteor in Mod Menu, and the **auto-till** and **auto-dirt** modules are in
   Meteor's **Farming** category.

> **Updating:** close Minecraft before replacing the jar, and delete the old one. Swapping a mod's jar while the game is
> running can crash it later.

## Setup guide

Open Meteor's click GUI (Right Shift by default), go to **Farming** and open **auto-till**.

### Just tilling
Turn the module on with a hoe in your hotbar and walk around. The brown boxes are the blocks it's about to till.

### Farming with water
1. Turn on **auto-water** in the **Water** group.
2. Carry a water bucket, plus a shovel if you want the holes dug faster.
3. Stand in a dry, flat patch. A blue box marks where it digs the hole. Once it's filled, the land around it gets tilled.

### Planting
1. Turn on **auto-plant** in the **Planting** group and pick your crops in **seeds**.
2. Leave **bone-meal** on to grow them instantly while you have bone meal.

### Seed restocking
1. Turn on **seed-restock** and set **restock-at** (0 means restock when you run out).
2. Pick a **restock-mode**:
   - **Storage:** turn on **pick-storage** and right-click your seed chest, or type its coordinates into
     **seed-storage**. If it's out of reach you'll need auto-walk on.
   - **Ender Chest:** keep an ender chest and a silk touch pickaxe on you, with a shulker of seeds stored in your ender
     chest. Keep at least 2 inventory slots free. If Meteor has already seen inside your ender chest, Qwerte checks for
     a seed shulker before placing anything; otherwise it checks once the chest is open.

### Full automation with Baritone
1. Install dekrom's Baritone (see [Installation](#installation)).
2. Turn on **auto-walk** in the **Automation** group and set **search-radius** to the size of the area to farm. It's
   measured from where you turn the module on.
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

## Settings reference

### auto-till

| Group | Setting | Default | Description |
|---|---|---|---|
| General | `reach` | 4.5 | Max distance from your eyes to a block's center. |
| General | `delay` | 2 | Ticks to wait between batches. |
| General | `blocks-per-tick` | 1 | How many blocks to work on per batch. |
| General | `blocks` | Grass Block, Dirt, Dirt Path | Blocks to turn into farmland. |
| General | `only-hydrated` | off | Only till blocks that would be hydrated. |
| General | `auto-switch` | on | Switch to the right item from your hotbar. |
| General | `swap-back` | on | Switch back to your previous slot afterwards. |
| General | `rotate` | on | Face blocks while working on them. |
| Planting | `auto-plant` | off | Plant seeds on empty farmland. |
| Planting | `seeds` | Wheat Seeds | Which seeds to plant. |
| Planting | `bone-meal` | on | Bone meal crops until fully grown. |
| Planting | `seed-restock` | off | Restock seeds when low. |
| Planting | `restock-mode` | Storage | Storage or Ender Chest. |
| Planting | `restock-at` | 0 | Restock at this many seeds or fewer. |
| Planting | `seed-storage` | 0, 0, 0 | The container to restock from. |
| Planting | `pick-storage` | off | Right-click a container to set it as the storage. |
| Water | `auto-water` | off | Dig and fill water holes for dry land. |
| Water | `water-side-color` / `water-line-color` | blue | Colors of the planned water hole. |
| Automation | `auto-walk` | off | Walk to more land with Baritone. |
| Automation | `refill-water` | on | Walk to water and refill empty buckets. |
| Automation | `refill-source` | Nearest Pool | Nearest Pool or Station. |
| Automation | `station` | 0, 0, 0 | The water block to refill at. |
| Automation | `pick-station` | off | Right-click a water block to set it as the station. |
| Automation | `water-search-radius` | 96 | How far to look for a 4x4 pool. |
| Automation | `search-radius` | 32 | How far from your start point to look for land. |
| Automation | `search-height` | 4 | How far up and down to look for land. |
| Automation | `combine-trips` | on | Restock seeds on water trips and refill water on seed trips. |
| Automation | `seed-top-up-at` | 128 | On a water trip, restock seeds if you have fewer than this. |
| Safety | `anti-break` | on | Don't use hoes that are about to break. |
| Safety | `min-durability` | 10 | Uses left at which a hoe counts as about to break. |
| Safety | `disable-when-out` | on | Turn off when you have no usable hoe left. |
| Render | `render` | on | Highlight blocks about to be tilled. |
| Render | `shape-mode` | Both | How the highlight is drawn. |
| Render | `side-color` / `line-color` | brown | Colors of the highlight. |

### auto-dirt

| Group | Setting | Default | Description |
|---|---|---|---|
| General | `reach` | 4.5 | Max distance from your eyes to the spot to fill. |
| General | `delay` | 2 | Ticks to wait between batches. |
| General | `blocks-per-tick` | 1 | How many blocks to place per batch. |
| General | `dirt-item` | Dirt | Dirt, Coarse Dirt, Rooted Dirt or Grass Block. |
| General | `below-feet-only` | on | Only cover ground below your feet. |
| General | `depth` | 2 | How far below your feet to look for ground. |
| General | `skip-own-column` | off | Leave the block you're standing on uncovered. |
| General | `ignore` | chests, barrel, crafting table, furnace | Blocks never covered. |
| General | `rotate` | on | Face the block being placed against. |
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

## Building from source

You need **JDK 21** or newer. Everything else, including Minecraft, Meteor and the Baritone API, is downloaded by Gradle.

```sh
git clone https://github.com/DustinFoes/Qwerte.git
cd Qwerte
./gradlew build
```

On Windows use `gradlew.bat build`. The jar ends up in `build/libs/qwerte-addon-<version>.jar`.

To test in a development client, run `./gradlew runClient`. Baritone isn't included there; add its jar to `run/mods`
if you want to try the automation.

### Project layout

```
src/main/java/com/porter/qwerte/
├── QwerteAddon.java          Addon entrypoint, registers the Farming category and modules
├── modules/
│   ├── AutoTill.java         The auto-till module: tilling, watering, planting, automation
│   ├── AutoDirt.java         The auto-dirt module: covering ground with dirt
│   └── Restocker.java        Storage and ender chest restocking, shared by both modules
└── utils/
    └── BaritoneHelper.java   The only class that touches Baritone, loaded only when it's installed
```

Versions live in `gradle/libs.versions.toml`.

## License

[GPL-3.0](LICENSE)
