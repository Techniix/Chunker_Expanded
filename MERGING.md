# World Merging

Chunker Expanded can merge any number of Java and Bedrock worlds, from any version Chunker can read, into one large
world. While merging it:

- **Keeps your data.** Columns, chests and their contents, signs, banners, item frames, paintings, maps and Bedrock
  portal records are carried over and moved to their new positions. Positions mobs remember are moved too (bee hives,
  villager beds and job sites, leash knots, turtle home beaches and so on).
- **Generates the area below Y=0 for pre-1.18 worlds.** Like the game's own upgrade, the old bedrock floor at Y 0–4
  is replaced and deepslate with caves, ores, tuff, lava and a new bedrock floor is added from Y −64 to −1. This works
  for both Java and Bedrock output, because Chunker writes the target format directly and the game's own upgrade step
  never runs.
- **Blends the borders.** The gaps around and between worlds are filled with generated *seam* terrain. Its height is
  solved as a smooth surface that starts exactly at each world's real edge, so there are no chunk walls. Materials,
  water and biomes come from the nearest world edge, and the borders between them are warped so they look natural.

## Usage

```
java -jar chunker-cli.jar merge -f JAVA_1_21_11 -o MergedWorld WorldA WorldB BedrockWorldC OldWorld_1_12
```

The first world is the "main" world: it keeps its co-ordinates, spawn point, game rules and player data. The other
worlds are packed around it in a grid.

| Option | Default | Description |
|---|---|---|
| `-f, --outputFormat` | (required) | Output format, e.g. `JAVA_1_21_11`, `BEDROCK_R21_130`, or `INPUT` for the first world's format. |
| `-o, --outputDirectory` | (required) | Where to write the merged world. |
| `--gap` | `32` | Minimum gap between worlds in chunks, rounded up to whole regions (32 chunks). Use `0` to place worlds as close as possible. |
| `--layout` | `GRID` | `GRID` packs worlds into a square-ish grid; `ROW` puts them in one line. |
| `--offset NAME=X,Z` | – | Place worlds manually (block offsets, multiples of 512). If any are given, unlisted worlds stay where they are. |
| `--seams` | `SYNTHESIZE` | `SYNTHESIZE` generates blended terrain around worlds; `NONE` leaves gaps for the game to generate. |
| `--seam-width` | `16` | How far, in chunks, seam terrain reaches out from each world's edge. |
| `--no-below-zero` | off | Don't generate below Y=0 for old worlds. |
| `--align-regions` | `1` | Placement alignment in regions. `8` keeps Nether portals linked exactly (the Nether is moved by 1/8th). |
| `--no-nether-scale` | off | Move the Nether by the full overworld offset instead of 1/8th. |
| `--sea-level` | `63` | Sea level used for seam water. |
| `--seed` | fixed | Seed for generated caves, ores and seam detail. |
| `-k, --keepOriginalNBT` | off | For worlds of the **same edition** as the output, copy original NBT so mobs and any other data Chunker doesn't model come across exactly. |
| `--java-blending` | off | *Experimental:* write Java `blending_data` so terrain the game generates later (beyond the seams) blends into the merged chunks. Test it on a copy first. |

## Mobs and other entities

Stock Chunker only translates a few entity types between editions (item frames and paintings). Other
mobs need their original NBT copied, which is only possible when the source and output are the same edition. Use
`-k` to keep them for those worlds. Mobs in worlds of the other edition are dropped, and Chunker prints a
`Missing entity mapping` line for each type. Translating mobs between editions is on the to-do list below.

## How it works

1. **Scan.** Each world's level data and region list is read (no chunks) to measure its size.
2. **Layout.** Worlds are packed so their region-aligned bounding boxes don't overlap. Region alignment means each
   source region file maps onto exactly one output region file.
3. **Passes.** Each world is converted in turn through the normal Chunker pipeline into one shared writer
   (`MergeLevelWriter`). That writer writes `level.dat` once, renumbers maps so their IDs don't collide, keeps files and
   databases open between passes, and only flushes at the end. `MergeTransform` runs first in the pipeline. It moves
   columns, entities, block entities, maps and portals, generates the below-zero layer and records a compact surface
   profile near each world's edge.
4. **Seams.** `SeamGenerator` finds every empty chunk within `--seam-width` of a world and solves a harmonic (Laplace)
   height field on a 4×4 block grid. Real edge heights are fixed, and the open outer edge carries the nearest world's
   height outwards. Detail noise is added, scaled by how rough the neighbouring terrain is and faded out at the edges.
   A flood fill gives each cell the surface materials, water and biome of the nearest edge, sampled through a
   domain-warped lookup. The seams are written by a final pass through the same pipeline (`SeamLevelReader`).

Code lives in `cli/src/main/java/com/hivemc/chunker/conversion/merge`.

## Ideas / to-do

- Translate common mobs between Java and Bedrock (type and basic fields such as health, name, age, tamed, variants).
- Vegetation (grass, flowers, trees) on seam terrain.
- Carry block/fluid tick lists and Java POI (villager beds, bells) through merges.
- A GUI tab in the Electron app.
