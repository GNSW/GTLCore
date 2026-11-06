---
navigation:
  title: Material plans and orders
  parent: gtlcore:index.md
  position: 10
---

# Material plans and orders

After setting the mode, selection, paste destination, and array, publish a material plan through "**Plans and orders**". Plans prepare materials through the linked ME network. Publishing a plan does not start world edits; sneak and hold right-click to begin construction.

## **Plan types**

* **Missing: manual**: subtract available stock and task escrow from the requirements and publish a plan for the shortfall. Manually request one corresponding task receipt in a terminal on the linked network.
* **Missing: auto**: publish the shortfall plan and automatically request one.
* **All: manual**: publish the full task requirements without subtracting existing stock. Request the plan manually.
* **All: auto**: publish the full task plan and automatically request one.

Automatic orders require the network to support the corresponding crafting jobs. If patterns, base ingredients, or available CPUs are missing, follow the status message and retry.

## **Clearing and cancelling**

* **Clear manual plan**: remove the manual plan published by this tool.
* **Cancel auto orders**: cancel this tool's automatic requests and stop automatic resupply for the current task. Holding right-click to resume a regular task enables automatic resupply again.

## **Task receipts**

Real materials received by a plan enter the task's material escrow. The output is a receipt shaped like the Matter Manipulator and named after the task. It **cannot be used as a tool or construction material**.

Publishing a new plan replaces this tool's previous task record. If you need to undo the old task, finish undoing it before publishing another plan.

[Network supplies](logistics.md) · [Task controls](tasks.md) · [Contents](index.md)
