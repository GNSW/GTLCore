---
navigation:
  title: Building, shapes, and materials
  parent: gtlcore:index.md
  position: 3
---

# Building, shapes, and materials

Open the menu, select "**Build**", and mark A and B. Choose the shape and blocks under "Set shape" and "Set blocks", then sneak and hold right-click to start.

The tool works within the source selection, filters positions by shape, and places the material assigned to each category. Materials come from the linked ME network or your inventory.

## **Set shape**

Choose a shape under "Build → Set shape". Replace mode offers the same settings.

* **Line**: connects A and B, including diagonal lines, using the Edges material.
* **Cuboid**: places materials by Corners, Edges, Surface, and Interior.
* **Cylinder**: runs along the Y axis. X and Z dimensions define its cross-section; Y defines its height.
* **Sphere**: fits inside the selection. Unequal dimensions produce an ellipsoid.

## **Set blocks**

Corners, Edges, Surface, and Interior each have their own material. Set them separately or sample one block for all four.

* **Middle-click sampling**: aim at a block in the world and middle-click to apply its actual block state to all four categories.
* **Set blocks menu**: click a category to use the block on your cursor, or the block in your offhand if the cursor is empty. Choosing an item uses its block's default state.
* **Materials screen**: pick up a block from the inventory below and click a category button to use its default state. An empty cursor selects air.

Thin structures are also classified into Corners, Edges, and Surface. To use one material everywhere, sample it with middle-click.

**Air materials set the corresponding positions to air**. Setting Interior to air makes a hollow structure. Existing blocks must still satisfy the removal rule.

## **Removal rule**

Choose "Removal rule" to control existing blocks at the destination:

* **Replaceable only**: the default. Replaceable blocks such as grass can be cleared; ordinary solid blocks stop the task.
* **No removal**: leaves existing blocks in place, for construction in empty positions.
* **Remove all**: allows overwriting existing blocks, subject to permissions, unbreakable blocks, and device support.

The rule also applies to destinations in Replace, Copy, and Move modes. Ordinary blocks that already match can be skipped. Remove mode uses its own full selection instead.

[Selection editing](selection.md) · [Replacement](replace.md) · [Supplies and returns](logistics.md) · [Contents](index.md)
