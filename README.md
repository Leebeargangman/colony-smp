# ColonySMP

Communist colonies for Paper servers. Players claim land, raise a Town Hall and run a commune of villager
citizens who farm, log, mine, fish, herd, cook, smith, heal, teach, build and guard, all for one shared
**Central State Chest**. Feed them at sunset or they strike; keep them rested, housed and happy or they
leave. Send their children to school. Copy any building with the wand and have the Builders build it
again. Take prisoners and re-educate them, or put them to forced labour (and keep them guarded). Declare
war with a War Banner and besiege your neighbours.

No client mods or resource pack needed.

## Requirements

- **Paper 1.21.4 or newer** (built against the 1.21.4 API, the same target as TrenchWar). The plugin uses
  APIs that changed in 1.20.5 and 1.21.3 (item components, attribute and registry changes), so a single
  jar can't also cover 1.20.x.
- Java 21.
- Nothing else. The database is SQLite, which Paper already ships.

## Install

1. Put `ColonySMP-1.1.0.jar` in `plugins/`.
2. Start the server. `plugins/ColonySMP/config.yml` and `plugins/ColonySMP/colonies.db` are created.
3. Optional: tweak the config, then `/colony admin reload`.

Build it yourself with `./gradlew build` (or `gradle build`); the jar lands in `build/libs/`.

## Quick start (for players)

1. **Craft the Colony Selection Wand**: a stick and a gold nugget side by side (stick left, nugget right).
2. **Mark your land**: Left-click a block for Position 1 (green particles), Right-click a block for
   Position 2 (white particles). Claims are 16x16 up to 128x128 and can't overlap another colony.
   Holding the wand shows your selection (green = valid, red = not) and nearby claims (orange).
3. **Shift + Right-click** to open **Establish Colony**, confirm, and type a name in chat.
   You get a **Town Hall Core** and a **Colony Blueprint Book**, plus a **72-hour Peace Shield**.
4. **Place the Town Hall Core** inside your claim. A **Central State Chest** is built beside it and your
   first two comrades arrive: a Farmer and a Builder. The starter quests appear on a boss bar:
   1. *Found the State*: place the core.
   2. *Worker Shelter*: build an enclosed 5x5 room (walls included) with a door and 2 beds, then
      Shift + Right-click inside it with the Blueprint Book.
   3. *Feed the Commune*: put 32 food items in the State Chest.

   Finishing all three sends starter tools and seeds to the State Chest.
5. Register a **farm** (Blueprint Book, Farm blueprint, Shift + Right-click the farmland) and keep seeds,
   tools and food in the State Chest. The commune does the rest.

## How it works

### The Central State Chest
The chest beside the core opens the colony's shared storage (3 pages of 45 slots by default). Everything
workers harvest, chop, mine, fish and shear goes straight in. Nothing comes out by magic: whatever a
citizen uses (tools, seeds, building materials, torches, fuel, raw food to cook, ore to smelt, books,
feed for the animals, weapons, a prisoner's meal, their own supper) they **walk to the chest and carry
in their satchel** (4 stacks) to where it's used, and they bring leftovers back. The storage works even
when the chunk isn't loaded, hoppers can feed it, and only members can open it (`/colony storage` works
anywhere inside your claim).

### The Blueprint Book
- **Hold it** to see a glowing outline of the selected blueprint where you're looking. Green means it
  fits, red means it doesn't. Your registered buildings show in blue and build orders in orange.
- **Right-click**: next blueprint (Starter House, Farm, Guard Tower, Prison Cell, School, Mine Entrance,
  then your colony's copied buildings).
- **Shift + Right-click a block**: register an existing building there:
  - *House*: an enclosed room with a wooden door and at least one bed (3x3 floor or bigger). Every bed
    is a home for one citizen.
  - *Prison Cell*: an enclosed room with an iron door and a bed (one prisoner per bed).
  - *School*: an enclosed room with a wooden door and a lectern (bookshelves inside make lessons better).
  - *Farm*: at least 9 connected farmland (or soul sand) tiles.
  - *Guard Tower*: the top of a tower at least 5 blocks tall. Guards keep watch there.
  - *Mine Entrance*: natural ground. The mine runs the way you're facing.
- **Shift + Left-click**: order the Builders to construct the blueprint there. They carry materials
  from the State Chest in batches and tell you what's missing. Finished buildings register themselves.

Buildings are re-checked every night; broken ones are unregistered with a message. Farmland inside a
claim is never trampled back to dirt, and fields that do end up as dirt or grass are re-tilled by the
Farmers.

### Copying buildings with the wand
Members of a colony use the **Colony Wand** to copy structures:
1. **Left-click** one corner and **Right-click** the opposite corner of what you want, top and bottom
   (a 3D box, up to 48 blocks on each side). The box is outlined in green while you hold the wand.
2. Stand on the side you want to be the **front**, **Shift + Right-click** and choose **Copy as
   Blueprint**, then type a name.
3. The copy is selected in your **Blueprint Book**: look where it should go (face another way to turn
   it) and **Shift + Left-click** to order it. The order lists every material needed.

The Builders clear the space, lay the full blocks, then everything that sits on them (doors, beds,
torches, stairs, glass, carpets...), then water and lava (buckets). Dirt stands in for grass, cobblestone
for stone; plants and leaves are skipped if the chest has none. Beds, lecterns and farmland in a finished
copy are registered as houses, schools and farms automatically. `/colony blueprints` lists, selects and
deletes copies (10 per colony by default).

### Citizens
Citizens are villagers whose looks follow their job. Right-click one to see their stats (trait, health,
how well they ate, efficiency, tool, bed) and to change their job:

| Job | What they do |
|---|---|
| Farmer | Harvests ripe crops on registered farms and replants from the chest's seeds (wheat, carrots, potatoes, beetroot, nether wart, melons, pumpkins). Needs a hoe for full speed. |
| Builder | Builds ordered blueprints block by block; cuts wood when there are no orders. |
| Lumberjack | Fells natural trees in and around the claim, replants saplings and collects what the leaves drop. Needs an axe. |
| Miner | Digs a staircase from a Mine Entrance down to `mines.target-y`, then a branch mine; digs out ore veins, seals lava and water, places torches from the chest. Needs a pickaxe. |
| Guard | Patrols guard towers, fights monsters, invaders and rebels, escorts captives to cells and watches forced labourers. Draws the best weapons, armour, bows and arrows from the chest when danger comes. |
| Warden | Visits each prisoner daily (see Prisoners), carrying a meal from the chest. With no prisoners, gives speeches that raise stability. |
| Fisher | Fishes from the shore of any water in the claim (cod, salmon, now and then junk or treasure). Needs a fishing rod. |
| Herder | Shears sheep, gathers eggs, breeds each kind of animal up to the herd size with feed from the chest, and slaughters the surplus for meat, leather and wool. Needs shears for sheep. |
| Cook | Carries raw meat, fish and potatoes (and fuel) to a smoker, furnace or campfire in the colony, cooks them and brings the meals back. Bakes bread from wheat when there's nothing raw. |
| Smith (education 25) | Smelts raw ore at a furnace or blast furnace, repairs worn tools and armour at an anvil, and makes spare tools for the workers and iron armour for the guards. |
| Doctor (education 40) | Runs to downed citizens and gets them back up, and treats the injured. |
| Teacher (education 30) | Teaches at a School (see Education). |
| Student | Studies at a School all day to raise their education. |

**Daily routine.** Citizens work by day. In the evening they walk to the State Chest for supper, then
gather at the Town Hall, and they sleep in their own bed at night. Non-combatants flee from monsters.

**Walking.** Citizens walk everywhere: through doors and gates, up ladders to the tops of guard towers,
in and out of prison cells (the warden opens the iron door). Only a citizen who has been stuck with no
way through for a long while is moved, and only when nobody is watching.

**Tools.** When a tool breaks, the worker walks to the State Chest, takes the best replacement and goes
back to work. With no tool left they work bare-handed and slowly (miners can't dig without a pickaxe).

**Traits.** Each citizen has a trait such as Hardworking, Green Thumb, Frugal, Glutton or Brave.

### Needs and happiness
Right-click a citizen to see their bars:
- **Rest** drops while they're awake and refills in bed. Below 30 they're tired and slow; exhausted
  citizens stop for a nap. The homeless only doze, so they never get fully rested.
- **Health** mends slowly when they're fed, faster in bed, and fast with a Doctor around.
- **Happiness** follows everything else: food, rest, a home, health, the colony's stability, a job that
  suits their trait (and their schooling), a school for the children, strikes and mobilization. Happy
  citizens work faster (up to +20%) and lift stability each night; miserable ones slow down, and a
  citizen who stays miserable for 3 nights may **leave the colony**.

### Education and schools
Build a **School** (or order the School blueprint), register it, and make someone a **Teacher**. Every
30 seconds the teacher gives a lesson to the **children** and **Students** in the room (10 per school).
Children learn twice as fast. A **book** in the State Chest (the teacher carries one over each day) and
**bookshelves** in the room make lessons better. Education (0-100) makes citizens work up to 30% faster,
and skilled jobs need it: Smith 25, Teacher 30, Doctor 40. New colonists arrive with some schooling;
children start with none. `/colony school` shows the schools and the colony's average education.

### Rations, stability and strikes
- **At sunset** every citizen gets an equal meal from the State Chest (6 hunger points by default).
  Children eat half and forced labourers eat half. Three wheat count as a loaf.
- **Full rations** raise stability; short rations lower it, and hunger lowers it a lot. Homeless
  citizens and forced labour cost a little; idle Wardens' speeches add a little.
- **Efficiency** (work speed) depends on stability and on how well the citizen ate.
- **Strikes.** Stability below 35% for **3 days in a row** starts a **general strike**: workers protest
  at the Town Hall until stability is back to 50%.
- **Children.** With free beds and at least **3 days of food** in the chest, a **child** is born (one a
  day). Children grow into adult workers after **3 days** and pick a useful job.

The colony lives while its Town Hall is loaded. Nobody has to be online, but someone must be nearby.

### Prisoners and forced labour
- **Going down.** People (citizens, villagers, wandering traders, illagers and witches) who would die in
  combat have a **20% chance** to go **down** for 60 seconds instead. Travelers always surrender.
- **Binding.** Right-click a downed person with **Rope** (3 string in a column) to bind them; they follow
  you on a lead. Walk them into a **Prison Cell**, or **Shift + Right-click** them to hand them to your
  Guards, who escort them to a free cell. Their weapons and armour go to your State Chest. You can help
  your own downed comrades up by right-clicking them.
- **Indoctrination** (default): a Warden visits every day, brings a meal from the chest and wears down
  the prisoner's **Resistance** (0-100). At 0 they join as an **Equal Citizen**. Unvisited prisoners
  harden; prisoners left without food for 5 days starve.
- **Forced Labour**: sentence a prisoner from their menu with **Iron Shackles** (iron ingot, chain,
  iron ingot) in your inventory or the chest. They mine or log by day (half rations) and are locked up
  at night. **A Guard must stay within 20 blocks of every working labourer.** If one is left unguarded
  (30 s grace), or stability drops below 40%, the labourers rise up: they **break their shackles, steal
  weapons from the State Chest and fight back**. Down them and rebind them, or they escape after 5 minutes.
- Set the policy per prisoner, set a default for new prisoners in the Town Hall menu, or use
  `/colony policy`.

### Wandering travelers
A traveler sometimes walks up to your Town Hall (more often with a good reputation). Members get a
clickable **[Recruit] / [Turn Away]**, or can right-click them. Recruiting needs a free bed and a day of
food. You can also knock them down and capture them, but that costs your colony **reputation** with
travelers (−5 for attacking, −15 for capturing or killing). Below 15 reputation they stop coming.

### War
1. **Declare**: with a **War Banner** (red banner + iron sword + gold ingot) in your inventory, right-click
   the banner in the air (or `/colony declare <colony>`). Colonies with a Peace Shield can't be targeted,
   and declaring ends your own shield. Both colonies get a boss bar and a **20-minute warm-up**.
2. **War Camp**: during the warm-up, place the War Banner **outside every claim, 8-64 blocks from the
   enemy's border**. No camp by the time the siege begins means the war is forfeit. Attackers respawn at
   the camp during the siege.
3. **Siege** (up to 45 minutes): attackers can break and place blocks in the enemy claim (but never open
   their chests). Defending **Guards draw the best gear from the State Chest** and fight; use the **State
   Mobilization Order** (`/colony mobilize` or the Town Hall) to arm every worker as militia for 10 minutes.
   - **Attackers win** by destroying the **Town Hall Core**, or by **cutting citizens off from the State
     Chest** (block every path to it, or block its lid) until stability hits **0%**: every 30 seconds
     with no access costs 5 stability.
   - **Defenders win** by holding out, or by **breaking the War Camp** banner.
4. **Spoils**: the winner takes **50% of the loser's raw resources** (ores, ingots, logs, crops, food:
   plain stackable goods, never tools, armour or named items). The loser gets a 24-hour Peace Shield.
   Blocks changed during the siege are put back two minutes after it ends.

## Items and recipes

| Item | Recipe |
|---|---|
| Colony Selection Wand | stick + gold nugget, side by side |
| Rope (x2) | 3 string in a column |
| Iron Shackles | iron ingot, chain, iron ingot (in a row) |
| War Banner | red banner + iron sword + gold ingot (shapeless) |
| Colony Blueprint Book | book + gold nugget + paper (shapeless), or `/colony book` |
| Town Hall Core | given when a colony is founded (`/colony core` if lost) |

## Commands

| Command | |
|---|---|
| `/colony info [colony]` | Colony overview |
| `/colony quests [hide\|show]` | Starter quests |
| `/colony mobilize [stop]` | State Mobilization Order |
| `/colony declare <colony>` | Declare war (War Banner needed) |
| `/colony war`, `/colony surrender` | War status / give up |
| `/colony citizens`, `prison`, `buildings` | Management menus |
| `/colony blueprints [list\|info\|select\|delete <name>]` | Buildings copied with the wand |
| `/colony school` | Schools and education |
| `/colony storage [page]` | Open the State Chest (inside your claim) |
| `/colony invite <player>`, `join <colony>`, `leave`, `kick`, `promote`, `demote` | Members (Chairman, Commissars, Comrades) |
| `/colony rename <name>`, `policy <indoctrinate\|enslave>` | Settings |
| `/colony traveler <recruit\|dismiss>` | Answer a visiting traveler |
| `/colony book`, `core`, `spoils`, `relocate`, `abandon` | Items and the Town Hall |
| `/colony list` | Every colony |
| `/colony admin reload\|save\|bypass\|give <item> [player] [n]` | Admin |
| `/colony admin delete\|stability\|shield\|traveler\|endwar\|tp\|ration\|food <colony> [value]` | Admin |
| `/colony admin stock <colony> <item> [n]`, `who <colony>`, `addcitizen <colony> [job\|CHILD]`, `job <colony> <name> <job>` | Admin (testing and support) |

Aliases: `/col`, `/commune`. Right-clicking the Town Hall Core opens the **Town Hall** menu with
everything in one place.

**Permissions:** `colonysmp.play` (default: everyone), `colonysmp.admin` (default: op).

## Configuration

Every number lives in `config.yml`: claim sizes and gap, farmland protection, shield hours, citizen
speeds and work intervals (including fishing, cooking and smithing), herd size, when a stuck citizen may
be moved, the daily routine times, rations and stability, needs (rest loss, tiredness, emigration),
education (lesson strength, school size, starting education), copy limits, strike rules, reproduction,
downed chance and prisoner rules, traveler odds and reputation, war timings and camp distances, spoils
share, block restoration, mobilization, State Chest pages, quest rewards, mine depth and size.
`/colony admin reload` applies changes.

## Persistence

Everything is saved to `plugins/ColonySMP/colonies.db` (SQLite): colonies, claims, Town Hall and chest
locations, every State Chest item, citizens (jobs, traits, stats, needs, education, satchels, equipment, beds), prisoners (cells,
resistance, policy), buildings and build orders, copied blueprints, mines' progress, shields, cooldowns and wars in progress
(their timers pause while the server is down). Saves happen within 30 seconds of a change, every few
minutes, and on shutdown, as one transaction on a background thread.

Citizen bodies aren't saved with the world: the plugin spawns them when a colony's Town Hall loads and
removes them when it unloads, so they never duplicate.

## Credits

The NPC approach (mob bodies driven by Paper's pathfinder, with their own brains switched off) follows
TrenchWar's AI soldiers.
