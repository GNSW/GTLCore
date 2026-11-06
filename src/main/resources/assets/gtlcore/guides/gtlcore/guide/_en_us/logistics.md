---
navigation:
  title: Network linking, supplies, and returns
  parent: gtlcore:index.md
  position: 9
---

# Network linking, supplies, and returns

## **Linking and unlinking**

Open the menu and choose "**Network link → Bind**". After the menu closes, right-click an <ItemLink id="ae2:wireless_access_point" /> connected to the network with the tool to bind it.

* A linked network supplies logistics across unlimited distances and dimensions. No additional wireless terminal is required.
* Keep the linked access point's chunk loaded and the network available and powered.
* "Unbind" removes the link. An existing task retains its startup binding, so cancel the old task before switching networks.

Cross-dimensional logistics only affects materials and energy. World edits must still take place in the source selection's dimension.

## **Supply and return order**

* **Item extraction**: ME network first, then your inventory for the remainder.
* **Fluid extraction**: ME network first, then usable fluid containers in your inventory.
* **Item returns**: ME network → inventory → drops at the work site.
* **Fluid returns**: ME network → suitable fluid containers in your inventory. Any remainder stays in the task's material escrow.

## **Materials screen**

The "**Materials**" screen shows remaining task requirements. Use "Previous" and "Next" to change pages. Tool charge and network link status appear below. This screen also provides the four building material categories and replacement whitelist settings.

## **Energy**

The tool uses EU, charges at the <Color color="#FF0000">**ZPM**</Color> tier, and has a default capacity of **10 billion EU**. Work uses the tool's charge first; actual energy from the linked ME network can cover a shortfall.

Block hardness, device type, and distance from the player affect energy costs. Staying near the work area helps reduce consumption.

## **Automatic resupply**

During construction, the tool checks remaining requirements and can automatically request missing materials when the network has the required crafting patterns, base ingredients, and an available CPU.

Releasing right-click pauses world edits. AE requests already calculating or submitted may continue. Choose "**Plans and orders → Cancel auto orders**" to stop those requests. Holding right-click to resume a regular task enables automatic resupply again.

[Material plans](plans.md) · [Material settings](build.md) · [Task controls](tasks.md) · [Contents](index.md)
