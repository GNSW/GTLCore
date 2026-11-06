---
navigation:
  title: Rotation, mirroring, and arrays
  parent: gtlcore:index.md
  position: 8
---

# Rotation, mirroring, and arrays

## **Rotation and mirroring**

In Copy or Move mode, open "**Transform**" to rotate around the X, Y, or Z axis or mirror along an axis. Each click rotates **90°**: left-click **+90°**, right-click **−90°**. Clicking a mirror option toggles it.

* X and Z rotations change the vertical orientation. Y rotation turns the structure horizontally.
* Some blocks cannot represent the requested vertical orientation. The tool reports an unsupported rotation.
* Devices with block entities cannot be rotated or mirrored while moving.
* Check the destination dimensions after transforming. Mark the paste destination again if they do not match.

## **Arrays**

Under "**Set array**", enter a count and additional gap for each of the X, Y, and Z axes.

* **Array count**: the number of copies on that axis, including the first. The total is the product of all three counts.
* **Additional gap**: the gap between the bounding boxes of adjacent copies. A value of 0 places them next to each other; positive values leave that many blocks between them.
* **Direction**: copies extend along the positive world coordinate axes. Each step is the transformed structure size plus the additional gap.

Multiple copies cannot overlap through negative gaps. Arrays multiply both material requirements and task size. **Move mode requires a count of 1 on every axis**.

## **Resetting transforms**

Click "**Reset**" to clear rotations and mirrors, set all array counts to 1, and set all additional gaps to 0. Source and destination coordinates remain.

[Selection editing](selection.md) · [Copying](copy.md) · [Task size](status.md) · [Contents](index.md)
