---
navigation:
  title: Removing blocks
  parent: gtlcore:index.md
  position: 4
---

# Removing blocks

Open the menu, select "**Remove**", mark A and B around the area, then sneak and hold right-click to start. Sneak and tap right-click to select the block itself.

## **Removal area**

Remove mode processes the **entire cuboid** between A and B. It does not clip to the building shape or follow destination removal rules such as "No removal". Permission and unbreakable-block checks still apply.

## **Material returns**

* Items go to the linked ME network first, then your inventory. Any remainder drops at the work site.
* Fluids that cannot be returned to the network or a container remain in the task's material escrow.
* Removing ores suppresses normal ore drops and experience. Use regular mining tools to obtain ores.

## **Devices**

Unsupported devices may be skipped. If a warning appears, check the area for devices left behind.

Remove existing ME Pattern Buffers using their own removal method. The tool cannot reliably journal refunds into external networks for undo.

[Supplies and returns](logistics.md) · [Cancelling and undoing](tasks.md) · [Contents](index.md)
