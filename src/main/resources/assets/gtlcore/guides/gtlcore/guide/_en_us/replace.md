---
navigation:
  title: Replacement and whitelists
  parent: gtlcore:index.md
  position: 5
---

# Replacement and whitelists

Open the menu, select "**Replace**", and mark A and B. Set the whitelist, shape, and destination materials, then sneak and hold right-click to start.

The tool processes positions matching both the shape and the whitelist, placing destination materials by Corners, Edges, Surface, and Interior.

## **Editing the whitelist**

* **Sample in the world**: aim at a source block and sneak + middle-click to add its actual block state. Repeat to add more states.
* **Whitelist cursor item**: use the "Replace whitelist" menu or the "Materials" screen to add the cursor block's default state. An empty cursor adds air.
* **Clear whitelist**: remove all entries. **An empty whitelist matches only air by default**.

Matching uses block states. Add different orientations, waterlogged states, and other variants separately.

## **Materials and existing blocks**

Set destination materials with "Set blocks", middle-click sampling, or the "Materials" screen, just as in Build mode.

Set a destination material to air to remove the matching blocks. To replace existing solid blocks, set the removal rule to "**Remove all**". Adding a block to the whitelist does not itself allow removing an existing block.

[Shapes, materials, and removal rules](build.md) · [Selection editing](selection.md) · [Contents](index.md)
