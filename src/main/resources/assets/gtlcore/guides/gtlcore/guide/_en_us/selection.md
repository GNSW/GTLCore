---
navigation:
  title: Selection editing and previews
  parent: gtlcore:index.md
  position: 2
---

# Selection editing and previews

## **Source selection**

Select points A and B in order. The selection is the cuboid between them, **including both endpoints**. Its size on each axis is the absolute coordinate difference plus one. Equal coordinates give a thickness of one block on that axis.

* Right-click normally to select the position outside the clicked face.
* Sneak and tap right-click to select the block itself.
* Once both A and B are set, marking another source point starts a new pair.
* Use "Clear" or Ctrl+Z to clear the source and destination coordinates.

## **Paste selection**

For copying or moving, set source A and B first. Choose "Mark paste target" or press **Ctrl+V**, then click the destination once.

The chosen position is the paste cuboid's **minimum X, Y, and Z corner**. The tool fills in paste A and B using the source dimensions after rotation. Clicking again moves the entire destination while preserving its size.

Source A can be any corner, so the paste point does not necessarily correspond to source A. Source and destination must be in the same dimension, and the destination size must match the transformed source size.

## **Editing coordinates**

Open "**Edit source/paste selection**" in the current mode and choose "Edit source selection", "Edit paste selection", or "Edit both". The latter two are available in Copy and Move modes.

Use "Edit selection" to enter source point A and B coordinates directly. "Here" sets that point to the player's current block position. Paste points A and B are read-only and cannot be edited individually.

## **Nudging and moving selections**

* **Nudge point A / B**: move the corresponding source endpoint.
* **Nudge selection**: move the entire selection being edited.
* **Move selection here**: align the selection's minimum corner with the player's block position. When editing both selections, the offset is calculated from the source and applied to both.

Each nudge moves one block along the coordinate axis closest to your viewing direction. Look up or down to adjust height. The paste box only moves as a whole; its size always matches the transformed source size.

## **Expanding and contracting**

Under "Edit source selection → Expand/contract source", you can:

* Expand upward or downward by 1 or 16 blocks.
* Expand horizontally toward or away from your facing direction by 1 block.
* Expand outward by 1 or 16 blocks, or contract inward by 1 block, along the axis closest to your viewing direction.

An existing paste selection is adjusted at the same time. If the sizes no longer match after editing, press Ctrl+V and choose the destination again.

## **Previews**

* A blue outline marks the source selection; an orange outline marks the paste destination.
* Small selections show individual block previews. Positions blocked by the removal rule are tinted red.
* Large previews are simplified. Construction still uses the actual selection.

[Rotation, mirroring, and arrays](transform.md) · [Task controls](tasks.md) · [Contents](index.md)
