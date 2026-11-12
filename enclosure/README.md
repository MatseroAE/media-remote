# Media Remote case v3 (screws + button caps, 402030 battery)

For the **Seeed XIAO 1.14" IPS Display (nRF52840 Plus)** with a **402030 LiPo**
(200 mAh, 4 × 20 × 30 mm). Same as v2, but sized for this smaller battery: 0.8 mm thinner, with stops on all four sides of the battery bay so it can't slide around.

Outside size: **31.4 × 56.2 × 18.2 mm**.

## Files
| File | Print | Notes |
|---|---|---|
| `MediaRemote_v3_front.stl` | 1 | Front shell, flat face down |
| `MediaRemote_v3_back.stl` | 1 | Back shell, flat face down |
| `MediaRemote_v3_cap_front.stl` | **2** | Front button caps, top face down |
| `MediaRemote_v3_cap_side.stl` | 1 | Side (play/pause) button cap, top face down |
| `MediaRemote_v3_slider.stl` | 1 | Power switch extender, flat side down. Tiny part: 0.12 mm layers, 100 % infill. Print 2 or 3 in case one comes off the bed. |
| `MediaRemote_case_v3.scad` | – | Editable source (OpenSCAD) |

Print in PLA or PETG, 0.2 mm layers, no supports. For the caps, use 0.12 to 0.16 mm layers and 100 % infill, so they come out crisp and solid. Printing the caps in a contrasting colour looks good.

## Hardware
- **4 × M2.5 × 12 mm socket head cap screws** (DIN 912 / ISO 4762). These give 2.65 mm of thread in the insert. 10 mm screws are too short.
- **4 × M2.5 heat-set inserts, 3 mm long**. The holes are **3.6 mm** across and 4 mm deep.
  ⚠️ Use 3 mm inserts only. There's just 0.85 mm of plastic between the bottom of the hole and the front face. If your insert packet recommends a different hole size, change `INS_D` at the top of the `.scad` file and re-export.

## Assembly
1. **Inserts:** press all four into the front shell with a soldering iron at about 220 °C (PLA) or 245 °C (PETG). They go into the four round posts on the inside: two at the bottom end, two either side of the USB pocket. Push them until they sit flush with the top of the post.
2. **Button caps and power slider:** from the inside, drop the two round caps into the front holes, stem facing inward. Fit the small rectangular cap into the round-cornered window on the left edge, flange inside. Then fit the **power slider** into the square window further along that edge. The ridged knob goes out through the window, and the fork faces inward, open side up.
3. **Board:** set the board's power switch and the slider to the **same end**, then lower the board in screen-down so the switch's tiny lever drops into the slider's fork. The two bottom posts line up with the board's mounting holes, and the caps are now trapped between the case and the switches. Check that the slider moves the switch both ways before closing the case.
4. **Battery:** plug it in (check polarity: red to **+**), tuck the wires into the gap along the right-hand side, and lay the battery flat on top with its **wire end toward the button end**, inside the four stops. Put a strip of tape over the XIAO's metal shield first. A small square of foam tape on the battery stops it rattling.
5. **Back shell:** fit it on and drive the four M2.5 × 12 screws. The two bottom screws pass through the circuit board and clamp it in place. **Tighten them just until snug.** Over-tightening can pull the inserts out of the front skin.

## Controls through the case
- **Front caps:** previous / next.
- **Left-edge cap:** play / pause (hold for volume mode / power).
- **Left-edge ridged slider:** power on/off. It moves the board's slide switch through about 1.3 mm of travel. A tab on the back shell holds it in place.
- **Top end:** USB-C in a 13 × 7.5 mm pocket. Most cable plugs fit; very chunky ones may not.
- **Two pin holes in the back:** the XIAO's reset button (double-tap for the bootloader) and the charge LED.

## Button fit
- The caps leave a gap above each switch: **0.45 mm** for the front caps, **0.35 mm** for the side cap. This allows for printing tolerance, so a cap never rests on its switch.
- **A button reads as permanently pressed:** sand a few tenths of a millimetre off the stem tip, or raise `CAP_GAP` / `SB_GAP` in the `.scad` file and reprint just the caps.
- **You have to push past flush to get a click:** lower the gap by 0.1 mm and reprint the caps.

## Notes
- The thumbwheel encoder isn't included yet. Its cut-out will go in the right-hand wall once a part is chosen.
- Board positions come from Seeed's KiCad files. Heights come from their 3D model, which is of the ESP32-S3 version of the same board.
