---
navigation:
  title: Device settings and connections
  parent: gtlcore:index.md
  position: 12
---

# Device settings and connections

Copy and Move automatically handle supported device data and connections. The features below do not require a separate setting.

## **Settings and inventories**

* **Ordinary blocks**: block types, states, and orientation transforms that the block can represent.
* **Supported GT machines**: supported settings, orientation, color, physical item and fluid inventories, charging slots, and covers.
* **GT pipes**: colors, connections, blocked sides, covers, and frames.
* **Supported AE devices and cable parts**: supported settings, upgrades, patterns, physical inventories, and facades.
* **Supported ordinary containers**: physical item contents.

Copying these contents consumes the corresponding real materials. Stored machine energy and active recipe progress are not copied. If an unsupported-device message appears, check for devices left behind and move or reconfigure them separately.

## **Pattern providers**

Copying encoded patterns consumes the corresponding number of **blank patterns**, preserving their encoding and slots. You do not need matching encoded patterns in your supplies.

## **ME Pattern Buffers and mirrors**

* **Copying a buffer**: the destination becomes an ME Pattern Buffer mirror linked to the original, consuming the mirror components. The original buffer and its contents remain at the source.
* **Moving a buffer**: transfers the original buffer and updates references from its registered mirrors.
* **Existing destination buffers**: use the device's own removal method. The tool currently cannot overwrite them directly or remove them as part of an ordinary area removal.

## **P2P tunnels**

When copying an input tunnel, the tool assigns a new frequency and pairs corresponding outputs inside the selection with their copied input. Each array copy is paired separately.

For a complete group, include both the input and its corresponding outputs, and connect the destination input to an available AE network. Copying only outputs retains the original input configuration; check that the corresponding input is available in the destination network.

This pairing happens in Copy mode. If a source output has no reachable input, the tool reports the problem and stops the corresponding operation.

## **Wireless hubs**

* When a hub and its members are copied together, the copied members link to the copied hub.
* When only members are copied and the original hub is outside the selection, that external binding is not automatically retained. Bind them again.
* Moving preserves or updates the corresponding connections.

[Copying](copy.md) · [Moving](move.md) · [Task controls](tasks.md) · [Contents](index.md)
