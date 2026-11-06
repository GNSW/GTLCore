---
navigation:
  title: Pausing, cancelling, undoing, and resetting
  parent: gtlcore:index.md
  position: 11
---

# Pausing, cancelling, undoing, and resetting

## **Starting and resuming**

Sneak and hold right-click for **1.5 seconds** to start or resume a task. If the startup gesture is interrupted by releasing sneak, opening a screen, or another action, release right-click fully before holding it again.

If an unfinished task exists, holding right-click resumes that task. Changing the mode, coordinates, or materials in the menu does not rewrite the saved plan. Cancel the old task before building with new settings.

## **Pausing**

Release right-click or choose "**Task controls → Pause**" to stop world edits while preserving progress. Sneak and hold right-click to continue. Tasks restored after a normal restart also require manual resuming.

Pausing does not cancel AE requests already calculating or submitted. Use "Plans and orders → Cancel auto orders" to stop automatic orders.

## **Cancelling a task**

Choose "**Task controls → Cancel**" to stop further construction and associated requests and return materials held in escrow. **Completed world edits remain**.

Fluids that cannot be returned stay in escrow. Provide network space or a suitable container. The tool blocks new tasks until the old task's escrow has been fully returned.

## **Undoing a task**

Choose "**Task controls → Undo task**". After the ready message appears, sneak and hold right-click to undo. Release right-click to pause.

Undo restores original blocks, device data, and recorded connections in reverse construction order. Materials are settled for each step:

* Restoring the original structure requires the materials recovered when it was removed. The tool uses task escrow first, then the linked ME network and your inventory. It waits if materials are missing.
* Each successful undo step returns the materials paid for that construction step. They can supply later undo steps; surplus follows the normal return order.
* Undo does not refund spent energy, and undo operations also consume energy.
* Undo does not automatically order missing materials.

Only the latest task stored by this tool can be undone, including its completed portion. Starting a new task or publishing another plan replaces the old record.

If a related block, device data, or network connection has changed, undo stops at the conflict and reports its coordinates. Older tasks without before-state records cannot be undone. After cancelling further undo, choose "Undo task" again to prepare the remaining steps.

## **Clearing coordinates**

Choose "**Clear**" or press **Ctrl+Z** to clear source A, source B, and the paste destination. This does not cancel a saved task or undo construction.

## **Resetting transforms**

Choose "**Reset**" to clear rotations and mirrors, set array counts to 1, and set additional gaps to 0. Source and destination coordinates remain.

[Network and returns](logistics.md) · [Task status](status.md) · [Contents](index.md)
