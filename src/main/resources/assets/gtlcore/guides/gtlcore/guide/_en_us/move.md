---
navigation:
  title: Moving
  parent: gtlcore:index.md
  position: 7
---

# Moving

Select "**Move**" or press **Ctrl+X**, then mark source A and B. Choose "Mark paste target" or press **Ctrl+V**, select the destination, then sneak and hold right-click to start.

"Mark source" or Ctrl+X returns to source marking, clears the paste destination, and preserves the source coordinates. Other selection edits work the same way as in Copy mode.

## **How moving works**

The tool transfers the original blocks and supported device data to the destination, clearing source positions as the task progresses. Existing destination blocks follow the removal rule.

Moving transfers the original materials rather than producing another copy of the structure. It still consumes energy.

## **Restrictions**

* Source and destination must not overlap.
* The array count on every axis must be **1**.
* Devices with block entities cannot be rotated or mirrored while moving. Reset the transform under "Transform" before moving devices.
* Source and destination must be in the same dimension, with the relevant chunks loaded.
* Changes to the source state stop the task. Check the reported position, cancel the task, and plan again.

Moving an ME Pattern Buffer updates references from its registered mirrors. Handle unsupported devices separately.

[Selection editing](selection.md) · [Device connections](devices.md) · [Task controls](tasks.md) · [Contents](index.md)
