---
navigation:
  title: Copying
  parent: gtlcore:index.md
  position: 6
---

# Copying

Select "**Copy**" or press **Ctrl+C**, then mark source A and B. Choose "Mark paste target" or press **Ctrl+V**, select the destination, then sneak and hold right-click to start.

## **Source and destination**

* **Mark source**: choose "Copy → Mark source" or press Ctrl+C to clear the paste destination and return to source marking. Existing source coordinates remain; use "Clear" to remove them.
* **Mark paste target**: choose "Copy → Mark paste target" or press Ctrl+V to choose a new destination. The tool generates its size from the transformed source dimensions.
* **Transforms and arrays**: use "Transform" and "Set array" to change orientation and copy counts.
* **Existing destination blocks**: handled according to the current removal rule.

Copying leaves the source intact. Source data is read during the scan when the task starts. Source and destination chunks must be loaded, and world edits take place in the source selection's dimension.

## **Materials and devices**

Copying consumes real materials for the destination blocks, additional components, and supported inventory contents. Prepare them through "Plans and orders".

Supported device settings and connections are restored. **Copying an ME Pattern Buffer creates a mirror linked to the original buffer**. See "Device settings and connections" for pattern providers, P2P tunnels, and wireless hubs.

[Selection editing](selection.md) · [Rotation and arrays](transform.md) · [Material plans](plans.md) · [Device connections](devices.md) · [Contents](index.md)
