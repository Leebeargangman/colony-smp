# ColonySMP

Colony simulation for Paper servers. Players found a settlement, claim land around its Town Hall, and run
a population of villager citizens who live, work, eat, sleep, learn, fall ill, get married, grow old,
trade, and go to war. Buildings are built by hand (or by your builders from a plan) and only count once
they pass inspection. Every good in the colony is real: harvested, made, carried and stored by its people.
Nothing appears from nothing.

There is always something going on. Citizens ask you for things, researchers work through a research tree,
merchants' caravans and refugees turn up at the gates, festivals light the sky, and once the colony is worth
robbing, barbarians and the walking dead come for it at nightfall.

Colonies keep going when nobody is around. Citizens near players walk around as villagers. The rest of
the colony runs as a background simulation at the same pace, so a colony on the far side of the map
still farms, cooks, pays wages and grows.

No client mods or resource pack needed.

New to it? Read [TUTORIAL.txt](TUTORIAL.txt) for a walk-through of a player's first hour.

## Requirements

- **Paper 1.21.4 or newer** and Java 21.
- Optional: **Vault** with an economy plugin. With Vault, players pay into treasuries and shop at colony
  markets with server money. Without it they use emeralds (`economy.deposit-item`).
- The database is SQLite, which Paper already ships.

## Install

1. Put `ColonySMP-2.2.0.jar` in `plugins/`.
2. Start the server. `plugins/ColonySMP/config.yml` and `plugins/ColonySMP/colonies.db` are created.
3. Optional: change the config, then run `/colony admin reload`.

Build it yourself with `./gradlew build`. The jar lands in `build/libs/`.

### Upgrading from 2.0 or 2.1

Drop the new jar in. Colonies, citizens and buildings load as they are; the new settings (`raids`, `requests`,
`events`, `milestones`, `christmas`, `citizens.chatter-seconds`) use their defaults until you add them to `config.yml` (delete the file to have a fresh
one written with every comment). Established colonies are awarded the milestones they have already reached on
their first day, and their masons start keeping a little gravel, sand and flint.

### Upgrading from 1.x

The 2.0 database is imported automatically on first start. A backup of the old file goes to
`plugins/ColonySMP/backups/` first. Communes become colonies (founder → owner, commissars →
co-owners, comrades → residents), rectangular claims become the chunks they covered, and the State Chest
becomes colony storage. Citizens keep their names, traits, jobs, schooling and gear. Wars in progress are
not carried over. The old tables stay in the database (`v1_*`) as a record. The 1.x config isn't read:
2.0 writes a new `config.yml`.

## The colony

### Founding and land
`/colony create <name>` gives you a **Town Hall Core** (a lodestone). Place it to found the colony:

- The chunks around it (3×3 by default) become yours, and a storage chest appears beside it.
- Your first settlers arrive, with provisions to last until the first harvest.
- You get a 72-hour peace shield.

Claim more chunks next to your land with `/colony claim`. Each claim costs more than the last, and the
settlement level sets the limit. Colonies must keep a gap of free chunks between them. `/colony map` shows
the land around you.

Inside the territory, outsiders can't build, open containers or use doors unless your roles allow it.
Explosions, fire spread and farmland trampling are blocked, and players can't fight each other unless
their colonies are at war.

### Roles
| Role | May, by default |
|---|---|
| Owner | Everything, including disbanding and handing the colony over (`/colony promote <player> owner`). |
| Co-owner | Everything except editing role permissions and disbanding. |
| Manager | Build, use doors and containers, use storage, manage buildings, jobs and citizens, trade, command the military. |
| Resident | Build, use doors and containers, put goods into storage. |
| Ally | Use doors and gates (members of allied colonies). |
| Visitor | Nothing but walking around. |
| Enemy | A member of a colony at war with yours. |

`/colony settings` lists who may do what, and `/colony settings perm <role> <permission> on|off` changes
the Manager, Resident, Ally and Visitor roles. The permissions are build, interact, containers,
storage_deposit, storage_withdraw, manage_buildings, manage_jobs, manage_citizens, policies, treasury,
trade, military, diplomacy, members, roles, territory and settings.

### The Town Hall
Right-click the core (or `/colony menu`) for the **Town Hall menu**: citizens, jobs, buildings,
storage, economy, policies, military, diplomacy, construction, history, statistics, members,
recruitment, the Town Hall upgrade, and research, requests, raids, today's happenings and milestones. The Town Hall level is the **settlement level** (1 to 5). No
building can be a higher level than the Town Hall.

### Storage
Colony storage is counted in slots, like chests: each slot holds one stack of one good. The Town Hall
gives 27 slots per level, every chest or barrel in a **Warehouse** adds 27 more, and a **Granary** holds
food and crops at 1.5× the slots. The armoury holds tools, weapons and armour as items.

The chest beside the Town Hall opens storage: click goods in your own inventory to put them in, click
storage to take them out. Goods are reserved while they're in use: a builder fetching 20 planks or a
trade offer holding 64 bread takes them out of what is available, so two jobs never count on the same
stack. `/colony storage target <good> <min> [max]` sets stock targets. Workshops make goods below the
minimum first, and merchants sell above the maximum.

## Buildings

Build a building by hand, then place the **Building Marker** (a light blue banner, `/colony marker`)
inside it. The marker works out what the building is from what's inside - a bed makes a House, a lectern a
School, an anvil and a furnace a Blacksmith - and registers it. Place it on farmland for a Farm, inside the
fence for a pen or yard, or on top of a tower for a Watchtower. If the building could be more than one type
(a furnace suits a Kitchen and a Bakery), a menu asks which; if it's nothing yet, it says what the closest
type is missing. Right-click the marker to inspect again, **Shift + Right-Click** it to choose another type,
and break it once the building is registered: the building stays and the marker drops for the next one.
Craft more from any banner, paper and a gold nugget (makes 2).

You can also register by hand: hold the **Blueprint Book**, choose the type (right-click to turn pages,
or `/colony book <type>`) and **Shift + Right-Click** inside it (or `/colony building register <type>`).

A building is inspected straight away and every day after that. A building
that fails inspection stops working and tells you why (`/colony validate`). If it keeps failing for 2 days
it is unregistered.

Six types have **standard plans** your builders can build for you: House, Farm, Warehouse, School, Prison
and Watchtower. Choose one in the book, look at the ground and **Shift + Left-Click**, or use
`/colony construction order <type>`. The builders carry every block from storage. The **Copy Wand** copies
any building you've made into a plan of your own, which the builders can then build again.

Upgrade with `/colony upgrade <id>` once the building has what the next level needs (and the Town Hall is
high enough). Upgrades cost money and goods.

| Building | Shape | Needs (level 1) | Workers |
|---|---|---|---|
| Town Hall | core block | | Administrator, Treasurer, the first Builder |
| House | room | 9 floor, bed, door | |
| Inn | room | 16 floor, 2 beds, door, barrel | Innkeeper |
| Warehouse | room | 9 floor, door, 2 chests/barrels | Warehouse Worker |
| Granary | room | 9 floor, door, 2 chests/barrels | Food Distributor |
| Farm | farmland | 9 farmland (small/medium/large: 1/2/4 farmers) | Farmer |
| Animal Pen | fenced area with a gate | 9 floor | Animal Keeper |
| Stables | fenced area with a gate | 16 floor, hay bale | Animal Keeper (level 2) |
| Fishery | room by 20+ water | 4 floor, door, barrel | Fisher |
| Forestry Hut | room | 4 floor, door, chest/barrel | Forester |
| Mine | entrance in natural ground | | Miner |
| Mill | room | 9 floor, door, grindstone | Miller |
| Bakery | room | 9 floor, door, furnace/smoker/campfire | Baker |
| Butcher Shop | room | 9 floor, door, smoker | Butcher |
| Kitchen | room | 9 floor, door, furnace/smoker/campfire | Cook |
| Restaurant | room | 12 floor, door, 2 stairs (seats), barrel | Restaurant Worker |
| Blacksmith | room | 9 floor, door, anvil, furnace | Blacksmith |
| Carpentry | room | 9 floor, door, crafting table | Carpenter |
| Masonry | room | 9 floor, door, stonecutter, furnace | Mason |
| Workshop | room | 9 floor, door, fletching table | Craftsworker |
| Market | room | 12 floor, door, 2 barrels | Shopkeeper |
| Trading Post | room | 9 floor, door, cartography table, chest/barrel | Merchant |
| School | room | 9 floor, door, lectern (bookshelves at level 2) | Teacher |
| Clinic | room | 9 floor, door, bed, brewing stand | Doctor |
| Builder's Hut | room | 9 floor, door, crafting table | Builder |
| Barracks | room | 12 floor, door, 2 beds | (soldiers sleep here) |
| Training Ground | fenced area | 25 floor, 2 hay bales | |
| Archery Range | fenced area | 25 floor, 3 target blocks | |
| Armoury | room | 9 floor, door, 2 chests/barrels/smithing tables | |
| Watchtower | tower 5+ blocks tall | | Guard |
| Prison | room | 4 floor, iron door, bed | Warden |
| Engineering Workshop | room | 9 floor, door, smithing table, crafting table | Engineer |
| University | room | 16 floor, door, lectern, 8 bookshelves | Researcher |
| Library | room | 12 floor, door, 6 bookshelves, 2 stairs (seats) | |
| Tavern | room | 16 floor, door, 4 stairs (seats), barrel, note block or jukebox | Bard |
| Enchanter's Tower | room | 9 floor, door, enchanting table, 4 bookshelves | Enchanter |
| Druid's Grove | fenced area with a gate | 9 floor, 2 mossy stones (mossy cobblestone or mossy stone bricks) | Druid |
| Apiary | fenced area with a gate | 9 floor, 2 beehives or bee nests | Beekeeper |

A room of bookshelves with a lectern could be a School or a University, so the marker asks which. A
University's level decides which research it can do; a bigger Enchanter's Tower lays stronger enchantments.

Higher levels need more floor and more of the same things. The book's hover text and
`/colony building <id>` show exactly what the next level needs.

## Citizens

Every citizen has a name, age, life stage (child, adult, elder), trait, skills, education, needs, mood,
job, workplace, home and bed, family, money, and a history. Right-click one (or use `/colony citizen`) to
see it all.

**Needs:** hunger, energy, health, social, housing, safety and employment. Hungry citizens fetch a meal
from storage or a restaurant. Tired ones go to bed, the exhausted nap wherever they are, and the starving
lose health. Mood follows the needs, plus food variety, wages and taxes, the work they do, illness,
crowding, grief and the colony's policies. Unhappy citizens work slower, and very unhappy ones riot or
leave.

**A day:** work hours (set by policy), leisure, and sleep from dusk. When there's danger, civilians
shelter, flee, or fight (the emergency policy decides which). A citizen's brain checks its priorities in
order: danger, starving, injured, exhausted, then the time of day.

**They use their heads.** A citizen chased by a monster shouts for the watch and runs somewhere safer, not
just away: behind the nearest guard or soldier, home, or to the Town Hall, whichever doesn't mean running past
the danger. Guards answer the shout at a run, go first for whatever is attacking a citizen, then for a raid's
leader, then for the nearly beaten, and fall back when badly hurt instead of dying where they stand. Archers
with no blade keep their distance and go on shooting. Guards and doctors run to a downed citizen and get them
on their feet. Off the alarm, guards eat, see the doctor and sleep when they're tired, at any hour, and wake
if the alarm goes. A soldier left on a standing order still falls out to eat before starving. The unemployed
carry goods instead of standing about, and a worker with two stacks waiting takes them to storage without
waiting for the shelves to fill.

**They talk.** Citizens speak to members who walk past, about whatever is most on their mind: what is stopping
their work ("I'm stuck: waiting for 6 flour"), that they are starving, unpaid, homeless or ill, the raid
expected tonight, the thing they asked you for, or just the time of day. One remark every 45 seconds or so
(`citizens.chatter-seconds`, 0 for off); `/colony chatter off` silences them for you alone.

**Families:** adults who get on become partners and, with a home, enough food and a good mood, have
children. Children grow up in 5 days (by default), go to school if there is one, and get a job when they
come of age. Elders retire from heavy work, and everyone dies of old age in the end.

**Skills and education:** citizens get better at what they do (and at what they've done before).
Education comes from schools and books. Some jobs need some education: an Administrator needs 35, a
Doctor 40.

**Health:** injuries heal faster in a bed or a staffed clinic. Illnesses come from hunger, crowding and
bad food, spread through shared homes and workplaces, and are cured by doctors with medicine. Quarantine
slows an outbreak but keeps the ill off work.

**Jobs** (35 of them): Farmer, Animal Keeper, Miller, Forester, Fisher, Butcher, Cook, Baker, Restaurant
Worker, Food Distributor, Miner, Blacksmith, Carpenter, Mason, Craftsworker, Engineer, Builder, Merchant,
Shopkeeper, Warehouse Worker, Administrator, Treasurer, Teacher, Doctor, Innkeeper, Warden, Researcher,
Bard, Druid, Enchanter, Beekeeper, Guard, Soldier, Student, Unemployed. Assign them with `/colony assign <citizen> <job> [building]`, the citizen
menu, or let an **Administrator** fill empty places with the unemployed every day.

**The rarer trades:**

- A **Researcher** (education 50) works on the colony's research at a University.
- A **Bard** plays at a Tavern through the working day and the evening. Everyone is happier for the music
  (+4 mood), and the tavern is where the colony gathers after work.
- A **Druid** (education 20) walks the fields from their grove and coaxes the crops on. Fields far from any
  player grow a quarter faster with one druid, a little more with each one after.
- An **Enchanter** (education 45) enchants the colony's gear: the weapons, tools and armour its people carry
  first, then the spares in the armoury. Each enchantment takes about four hours' work and two lapis lazuli
  a level. Tools get Efficiency, swords and good axes Sharpness, bows Power, armour Protection, the rest
  Unbreaking. A level 2 tower lays level 2 enchantments; the Arcane Studies research adds one more.
- A **Beekeeper** collects from the hives of an Apiary when they are full: three honeycomb, or a bottle of
  honey if the apiary has glass bottles. The bees are yours to bring (a merchant sometimes sells them).
- A **Library** needs no staff. Adults read there in the evening and slowly educate themselves (up to 70).

**New citizens:** recruit settlers from the Town Hall (`/colony settler`), take in **travellers** who stop
by (`/colony traveller recruit`), shelter **refugees** when they come to the gates, have children, or win
prisoners over. Every newcomer needs a free bed
and a day's food.

## Production and food

Farms grow wheat, carrots, potatoes or beetroots (`/colony building crop <id> <crop|auto>`). Animal pens
raise the animals inside them for eggs, wool, milk and meat. Fisheries fish, foresters fell and replant,
miners dig a staircase down and branch out for ore. Workshops turn inputs into outputs by **recipe**:
flour, bread, cooked meat, meals, planks, tools, weapons, armour, glass, bricks, arrows, books, medicine,
siege charges. Masons also crush cobblestone to gravel, gravel to sand, and sift gravel for flint, keeping a
small stock of each. `/colony building recipe <id> <recipe> on|off` turns a recipe off.

Workers carry what they need from storage and bring what they make back. Warehouse workers and food
distributors do the hauling for everyone else once the colony has them. Crops only grow in the world
where vanilla grows them (near a player). A colony far from players runs its farms, pens, fisheries and
workshops in the background from the same inputs. Jobs that need the world (forestry, mining,
construction) wait until someone is near.

`/colony production` shows what was made and what's waiting, and `/colony resources` shows the stores.

## Research

Build a **University**, give it a **Researcher**, and choose a project with `/colony research` (or the Town
Hall). Each project costs money and goods to begin, takes researcher hours to finish, and then changes the
colony for good. One project at a time; some need an earlier one, and the later ones need a bigger University.
Research carries on while nobody is near.

| Branch | Project | Needs | What it does |
|---|---|---|---|
| Farming | Crop Rotation | | Farmers, animal keepers, fishers, beekeepers and druids work 15% faster |
| Farming | Irrigation | Crop Rotation, University 2 | Fields yield 20% more and grow 20% faster while nobody is near |
| Industry | Improved Tools | | Miners, foresters and builders work 15% faster |
| Industry | Guilds | Improved Tools, University 2 | Everyone who works a recipe works 15% faster |
| Industry | Storehouses | Improved Tools, University 2 | Storage and granaries hold 25% more |
| Industry | Bookkeeping | | Building upkeep costs 15% less |
| Society | Herbal Medicine | | Wounds heal faster; illness is 40% rarer |
| Society | Hardy Folk | Herbal Medicine, University 2 | Every citizen has 4 more health |
| Society | Pedagogy | | Schools and libraries teach 30% faster |
| Society | Arts and Festivals | Pedagogy, University 2 | +3 mood for everyone; festivals come more often |
| Society | Hospitality | | Travellers and merchant caravans visit half again as often |
| Knowledge | Scholarship | Pedagogy, University 2 | Researchers work 25% faster |
| Knowledge | Arcane Studies | Scholarship, University 3 | Enchanters lay enchantments one level stronger |
| Defence | Drill | | Soldiers train 50% faster |
| Defence | Weaponcraft | Drill, University 2 | Guards and soldiers hit and shoot 20% harder |
| Defence | Watch System | Drill, University 2 | Raids are a quarter smaller |

`/colony research list` shows what each costs. Most want paper or books, which a Workshop makes from sugar
cane and leather. `/colony research abandon` drops the project under way (what was paid is lost).

## Requests

Now and then a citizen asks the players for something the stores can't give them: a pie, a few torches, feed
for the animals, strings for the bard. `/colony requests` lists who wants what and why. Bring it and
right-click the citizen with it in your hand (or use the link in the list). They pay you from their own
purse if they have money, cheer up, and work 15% faster for a couple of days; the colony's stability and
reputation rise a little too. Food is eaten on the spot and anything else goes to the stores. A request
nobody answers lapses after three days, and the citizen is a little disappointed.

## Happenings

Every few days, while a member is online, something happens. `/colony today` shows what is going on.

- **Festival**: a contented, well-fed colony declares a feast day. Mood rises for two days, and there are
  fireworks over the Town Hall at dusk.
- **Good growing weather**: fields give a third more for three days.
- **Merchant caravan**: a merchant with a llama walks to the Town Hall and trades for a few minutes.
  Right-click them. They sell livestock (handed over on a lead, to be walked home), bees, and things the
  colony can't make: sugar cane, saplings, books, lapis, bookshelves, an enchanting table, beehives, mossy
  stone, saddles. Anyone can buy, with Vault money or emeralds.
- **Refugees**: two or three people ask for shelter. Take them in (`/colony refugees accept`) if there are
  beds and food, or turn them away at a cost in reputation.
- **Lucky strike**: the miners break into a pocket of diamonds, emeralds, gold, lapis, amethyst or redstone.
- **Stray animals** wander into a staffed animal pen.
- **Travelling scholar**: a few hours of free research, or a lesson for the school's pupils.
- **Storm damage**: during a storm, a building may be damaged. Engineers mend it.

## Raids from the wilds

Once a colony has six citizens and is five days old, it is worth robbing. On the morning of a raid, scouts
report who is coming and roughly how many. The raid arrives at nightfall and lasts until the raiders are
dead or the sun comes up. Nobody in the colony can sleep that night.

- **Barbarians** make for the stores. Those who reach the storage chest or a warehouse carry goods off.
  Kill every one of them and the goods come back. They can be bought off beforehand with
  `/colony raid tribute` (it costs the treasury, and a little stability).
- **The undead** come to kill, led by a **Necromancer** who keeps raising more. Kill the Necromancer and
  the dead still standing fall with them.

Raiders are ordinary hostile mobs, so guards, soldiers and (under the militia policy) armed civilians fight
them, and other civilians flee. Bigger, richer, more successful colonies draw bigger raids; the Watch System
research thins them. Beating a raid pays a bounty into the treasury and lifts stability, reputation and
mood. Losing goods or citizens to one does the opposite. The chief and the necromancer drop loot of their
own. Downed barbarians can be bound with rope and imprisoned like any other enemy.

With their chief dead and three quarters of the band down, the last barbarians break and run, dropping
what they carried.

Raids only happen while a member is on or near the colony's land and the colony is loaded, never while a
war is on, and never in a peaceful world. `/colony raid` shows what the scouts know.

## Christmas

From 1 December to 6 January (by the server's calendar; `christmas.mode` can force it on or off) it is the
Christmas season.

- **The tree.** Craft a **Christmas Tree Sapling** (spruce sapling + gold nugget + red dye), or take the
  colony's free one with `/colony christmas`, and plant it on dirt or grass under open sky in your land. It
  grows at once into a spruce with lanterns hung in its branches and a star on top. One tree per colony per
  season; if the star is removed, the tree no longer counts.
- **While it stands:** everyone gets +3 mood, snow drifts round it, half the colony spends the evening
  there, the children play by it and throw snowballs (at you too; they hurt nobody), and the bard at the
  tavern plays carols.
- **Father Christmas** walks in with his reindeer every few in-game days while a member is online. He leaves
  treats in the stores and presents for the children (a mood boost for three days), and every player who
  right-clicks him gets a **Christmas Present**. Right-click the present to unwrap it: cookies, emeralds,
  iron, gold, diamonds, a golden apple, a saddle, a rare enchanted book... or a lump of coal.
- **Also in season:** citizens ask for festive things (cookies, a cake, wool for stockings, snowballs for the
  children), feast days become Christmas feasts with red, green and white fireworks, and people wish you a
  merry Christmas as you pass.

## Milestones

`/colony milestones` lists seventeen marks along a colony's way: ten citizens, a first raid beaten off, a first
discovery, a bard in the tavern, 25 requests answered. Each is rewarded once with a grant to the treasury and
a little reputation.

## Economy

Each colony has a **treasury** with a full ledger (`/colony economy`). Under the default **wages**
economy, workers are paid daily (tax is withheld) and spend their money at the market and on meals, so it
flows back. A **communal** economy pays no wages and everyone eats from the stores. Buildings and soldiers
cost **upkeep**. If wages or upkeep go unpaid, workers lose heart and then strike, and buildings fall into
disrepair.

- **Trading Post** (with a merchant at work): sell surplus and buy shortages on the server market
  (`/colony trade`). Prices move with supply and demand and recover over time. Auto-sell and auto-buy
  follow your stock targets. Food is never sold or traded below the **food reserve** policy.
- **Market** (with a shopkeeper): citizens spend wages here, and any player can buy from or sell to the
  colony (`/colony shop [colony]`).
- **Players** deposit and withdraw money with `/colony deposit|withdraw <amount>` (withdrawing needs the
  treasury permission).
- **Other colonies**: trade offers, gifts and recurring supply contracts carried by caravans (see
  Diplomacy).

## Policies

`/colony policies` (or the Town Hall): economy model, wage level, tax rate, working hours, recruitment,
population limit, births, military service, defence posture, emergency response, production priority,
food reserve, rationing, quarantine, auto-sell, auto-buy, and who may trade with you. Every policy says
what it costs and what it does to mood.

## Order

Stability is the colony's mood over time. It falls with hunger, homelessness, unemployment, unpaid wages,
illness, crime, high taxes, war, defeat and harsh policies, and rises with full bellies, good homes,
guards and a working economy. Low stability brings theft (fewer thefts where guards watch), disputes,
protests, strikes and riots, and very unhappy citizens leave. Criminals go to prison if there is a staffed
one, or are fined.

## Military

**Guards** watch towers and walls and keep order. **Soldiers** are recruited by class
(`/colony recruit <citizen> <class>`). There are 28 classes: militia, swordsmen, shieldmen, spearmen,
pikemen, axemen, berserkers, macemen, duelists, bodyguards, archers, longbowmen, crossbowmen, javelinmen,
slingers, light and heavy cavalry, lancers, horse archers, cavalry raiders, mounted swordsmen, medics,
standard bearers, commanders, scouts, sappers and siege engineers. Most need a training building and gear
from the armoury, and cavalry need a war horse broken in at the stables. Soldiers train every day, get
paid, and need barracks beds (4 can serve without barracks).

Combat follows class strengths and counters. Spears and pikes break charges, shields stop arrows from the
front, axes split shields, maces go through armour, cavalry crush the flanks, and crossbows pierce.
Training, morale and formation matter as much as gear. A squad whose morale breaks routs.

**Squads** take orders: defend, hold, follow, patrol, charge, shield wall, spear wall, volley, focus,
flank, retreat, protect, hold fire. Formations: line, column, wedge, square, loose. Give orders from the
Military menu, with `/colony orders <squad> <order> [here]`, or with the **Command Baton**: right-click a
block to rally, Shift + Right-Click for the next order, left-click an enemy to focus it, Shift +
Left-Click for the next squad. `/colony mobilize` stands everyone to arms for a while, at a cost in
stability.

## Diplomacy

Colonies are neutral, friendly, allied, hostile, at war, or in a truce. Their relation score moves with
gifts, trade, treaties, broken promises and wars. From the Diplomacy menu or `/colony diplomacy`:

- **Alliance**: allies defend each other and use each other's doors.
- **Treaties**: non-aggression pact, trade agreement, defensive pact, open borders.
- **Trade offers**: goods and money each way. What you offer is set aside until they answer.
- **Supply contracts**: a delivery every day for an agreed price.
- **Gifts**, a **call to arms** for allies, and **peace** with a truce.

Offers wait 30 minutes. Accept with the [Accept] link or `/colony diplomacy accept <colony>`.

## War

`/colony declarewar <colony> raid|siege` (costs money, needs a defender online by default, and ends your own
peace shield). After a warm-up:

- **Raid**: a short attack. Raiders standing at the defenders' storage loot it, and both sides score
  for kills and loot. Whoever has the higher score when time runs out wins.
- **Siege**: the attackers raise a **War Camp** (place the War Banner near the defenders' land). They
  win by destroying the defending Town Hall or by cutting the defenders off from their stores until order
  collapses. The defenders win by holding out or by tearing down the camp.

A lost siege costs the losers a share of their goods and money as spoils. The loser of any war gets a
peace shield, and blocks broken during the war are put back afterwards.
Downed fighters can be taken prisoner. `/colony peace <colony>` offers a truce, and `/colony surrender`
gives up.

## Prisoners

In a fight, citizens and travellers may go **down** instead of dying. A downed enemy can be bound with
**Rope** and led to one of your **Prisons**, or left for your wardens. Wardens feed prisoners and talk to
them every day. Each prisoner has a resistance that wears down until they agree to join the colony.
Prisoners from another colony can be ransomed or exchanged (`/colony prison ransom|exchange`), or
released.

## Background simulation

Every colony advances on its world's clock. A colony with players around has bodies for its citizens
(up to `max-bodies-per-colony`, nearest the players first). Everyone else, and every colony out of
sight, is simulated in steps: needs, meals and sleep, work and production, hauling, wages, illness,
births and deaths, crime. It's all within a small time budget per tick. Nothing happens while the server
is down, because the world doesn't move either. Long gaps are caught up to `max-catch-up-hours`.

## Items and recipes

| Item | How to get it |
|---|---|
| Town Hall Core | `/colony create`, or `/colony core` if lost |
| Blueprint Book | book + gold nugget + paper (shapeless), or `/colony book` |
| Building Marker (2) | any banner + paper + gold nugget (shapeless), or `/colony marker` |
| Copy Wand | stick + gold nugget side by side, or `/colony wand` |
| Rope (×2) | 3 string in a column |
| Command Baton | stick + gold ingot + red dye (shapeless), or `/colony baton` |
| Christmas Tree Sapling | spruce sapling + gold nugget + red dye (shapeless), or `/colony christmas` in season |
| Christmas Present | right-click Father Christmas when he visits |
| War Banner | red banner + iron sword + gold ingot (shapeless), or `/colony banner` |

## Commands

`/colony` (aliases `/col`, `/colonies`). `/colony help <topic>` lists them in game.

| Topic | Commands |
|---|---|
| Colony | `create <name>` · `info [colony]` · `list` · `map` · `claim` · `unclaim` · `menu` · `core` · `book [type]` · `marker` · `wand` · `baton` · `banner` · `disband` · `quests` · `events [type] [page]` · `stats` |
| Members | `invite <player>` · `join <colony>` · `leave` · `kick <player>` · `members` · `promote\|demote <player> [role]` · `rename <name>` · `settings` |
| Citizens | `citizens` · `citizen <name>` · `jobs` · `assign <citizen> <job> [building]` · `home <citizen> [house]` · `needs [citizen]` · `settler` · `traveller recruit\|dismiss` · `exile <citizen>` · `release <citizen>` |
| Buildings | `buildings` · `building <id>` · `building register <type>` · `building crop\|herd\|recipe\|name\|remove ...` · `validate [id\|all]` · `upgrade <id>` · `construction [order <type> \| pause\|resume\|cancel\|first <id>]` |
| Economy | `resources` · `storage [target <good> <min> [max] \| clear <good>]` · `economy` · `trade [sell\|buy <good> <qty>]` · `production` · `deposit\|withdraw <amount>` · `shop [colony]` · `policies [set <policy> <value>]` |
| Military | `military` · `recruit <citizen> <class>` · `army` · `orders <squad> <order> [here]` · `squad create\|delete\|rename\|add\|remove\|formation\|patrol\|select ...` · `mobilize` |
| Diplomacy | `diplomacy [list\|offers\|accept\|decline\|ally\|treaty\|cancel\|gift\|trade\|contract\|calltoarms]` · `declarewar <colony> raid\|siege` · `peace <colony>` · `surrender` |
| Prisoners | `prison` · `prison release <name>` · `prison ransom <name> [price]` · `prison exchange <ours> <theirs>` |
| Events | `today` · `chatter [on\|off]` · `christmas` · `requests [deliver <n>]` · `research [list \| start <project> \| abandon]` · `raid [tribute]` · `refugees accept\|refuse` · `milestones` |

Citizen names can have spaces: `/colony assign Rhea Pike farmer` works.

**Admin** (`colonysmp.admin`): `/colony admin reload | save | bypass | give <item> [player] | delete <colony>
| tp <colony> | inspect <colony> | citizen <colony> <name> | building <colony> <id> | reservations <colony>
| production <colony> | sim | perf | db | stuck <colony> | events <colony> | simulate <colony> <hours> |
setstock <colony> <good> <qty> | treasury <colony> set|add <n> | addcitizen <colony> [job] | endwar <colony>
| stability <colony> <n> | shield <colony> <hours> | traveller <colony> | repair <colony> | bodies |
demo <name> [x z] | raid <colony> [barbarians|undead] [size] | event <colony> <kind> |
research <colony> <project|all> | request <colony>`. `raid` starts a raid at once, `event` makes a happening happen (festival, harvest, caravan,
refugees, lucky_find, strays, scholar, storm, santa), `give` also hands out `christmas_sapling` and `present`, `research` grants a project, and `request` has a citizen ask
for something. `inspect` lists every citizen with their position, activity and needs. `simulate`
fast-forwards a colony's background simulation, and `demo` founds a stocked test colony from the console.

**Permissions:** `colonysmp.play` (default: everyone), `colonysmp.admin` (default: op).

## Configuration

Every number lives in `config.yml`, with a comment on each: claims and land, protection and shields,
citizens and bodies (speeds, work intervals, body limits), life stages and births, needs, education, the
economy and prices, storage, the background simulation budget, health, order, the military, prisoners,
travellers, war, diplomacy, plans, mines, the starter quest reward, raids, requests, happenings,
milestones and Christmas. Raids, requests, happenings and milestones each have an `enabled` switch. `/colony admin reload` applies
changes.

## Persistence

Everything is saved to `plugins/ColonySMP/colonies.db` (SQLite): colonies, members and roles, claims,
storage and reservations, the armoury, citizens, buildings, build orders, plans, squads, relations,
offers, contracts, shipments, wars, the market, research, open requests, milestones, and every transaction
and event. Changes are saved within
30 seconds, every few minutes, and on shutdown, as one transaction on a background thread. If a save
fails it is retried. The database is backed up at every start (`general.backups-to-keep`), and its schema
is versioned so later releases can migrate it.

Citizen bodies, raiders and visiting merchants aren't saved with the world. The plugin spawns them near players and removes them when
they're no longer needed, so they never duplicate.
