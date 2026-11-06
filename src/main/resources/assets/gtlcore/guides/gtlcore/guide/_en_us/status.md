---
navigation:
  title: Task status and limits
  parent: gtlcore:index.md
  position: 13
---

# Task status and limits

The item tooltip and the area below the menu show the current status, completed count, and relevant coordinates.

## **Waiting states**

* **Scanning and planning**: reading the selection, device data, and material requirements.
* **Waiting for materials / crafting**: check remaining requirements and supply matching items or fluids. Automatic resupply needs patterns, base ingredients, and an available CPU.
* **Waiting for energy**: charge the tool or provide enough energy in the linked network.
* **Waiting for bound network**: check that the linked access point is loaded and its network is available and powered.
* **Waiting for loaded chunks**: load the source, destination, and related device chunks. The tool does not load distant chunks itself.

## **Conflicts and errors**

* **Destination blocked by the removal rule**: adjust the destination or rule, cancel the old task, and plan again.
* **Paste selection size mismatch**: check the source dimensions and transform, then mark the paste destination again.
* **Destination or move source changed**: inspect the reported coordinates, cancel the old task, and plan again.
* **Network binding changed**: cancel the old task before creating one with the current network.
* **Unsupported block entities**: some devices may be skipped while other positions have already been processed. Inspect the area and handle remaining devices separately.
* **Undo conflict**: related blocks, device data, or network connections have changed. Check the reported position before continuing the remaining undo steps.

## **Task size**

* The default limit is **65,536 positions per task**. Copy arrays count the source cuboid volume multiplied by the total number of copies. Empty positions also count toward the scan size.
* Device snapshots and undo records share a default **64 MiB** snapshot budget. Split large tasks if they exceed it.
* The default target speed is **1,024 operations per second**. Actual speed depends on the server time budget, chunks, device complexity, materials, and energy.

Capacity, task size, snapshot budget, speed, previews, and other parameters can be changed in the mod configuration. Follow your server's settings. World edits must stay within build height and the world border, use loaded chunks, and pass the relevant permission checks.

[Materials and network](logistics.md) · [Task controls](tasks.md) · [Contents](index.md)
