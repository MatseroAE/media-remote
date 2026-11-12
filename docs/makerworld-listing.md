# MakerWorld listing: ready to paste

Repository: https://github.com/MatseroAE/media-remote

---

## Title
**Media Remote: Bluetooth Music Remote Case for Seeed XIAO nRF52840 Plus + 1.14″ Display**

Shorter alternative: *Pocket Bluetooth Media Remote (XIAO nRF52840 Plus, 1.14″ screen)*

## Category and tags
- **Category:** Gadgets → Electronics (or Tech & Gadgets, depending on what's offered)
- **Tags:** bluetooth, remote, media remote, music, XIAO, nRF52840, Seeed, enclosure, heat-set inserts, Android, spotify controller, electronics case

## Summary (one line)
A screw-together case for a pocket Bluetooth music remote with a colour screen that shows the song, artist and album art.

---

## Description

**What it is**
A small Bluetooth remote for your phone's music, built on the Seeed Studio XIAO nRF52840 Plus with Seeed's XIAO 1.14″ IPS display board. It pairs with your phone as a media remote, so play/pause, next/previous, seek and volume work with any music app. With the free Android companion app, the screen also shows the title, artist, album art and a progress bar.

This listing is the **case**. The firmware (with a ready-to-flash .uf2 file), the Android app and full instructions are on GitHub: **https://github.com/MatseroAE/media-remote**

**Features**
- Two front buttons for previous/next. Hold them to seek.
- A side button for play/pause. Hold it for volume mode or power off.
- A ridged slider for the board's battery switch.
- Separate printed button caps that sit clear of their switches.
- Held together by 4 × M2.5 screws in heat-set inserts. The bottom two clamp the circuit board through its own mounting holes.
- A recessed USB-C pocket for charging and flashing.
- Two pin holes on the back for the XIAO's reset button, so you can flash new firmware without opening the case.
- Size: 31.4 × 56.2 × 18.2 mm.

**What you need**
- Seeed Studio XIAO 1.14″ IPS Display board **with the XIAO nRF52840 Plus**. Get the nRF52840 version, not the ESP32-S3 one.
- A 402030 LiPo (3.7 V, 200 mAh, 4 × 20 × 30 mm) with a **JST PH 2.0 2-pin** plug. ⚠️ Check that the red wire lines up with **+** on the board before plugging it in.
- 4 × **M2.5 × 12 mm** socket head cap screws. 10 mm screws are too short.
- 4 × **M2.5 heat-set inserts, 3 mm long** (fit into 3.6 mm holes). Don't use 4 mm inserts: there's only 0.85 mm of plastic behind them.
- A strip of Kapton or electrical tape, and a small square of foam tape for the battery.

**Printing**
| Part | Qty | Settings |
|---|---|---|
| Front shell | 1 | Front face down, 0.16 to 0.2 mm layers, no supports |
| Back shell | 1 | Back face down, no supports |
| Front button cap | 2 | 0.12 mm (or 0.08 mm) layers, 100 % infill, knob down with build-plate supports under the flange. Sand the underside of the flange flat afterwards |
| Side button cap | 1 | Flange down, 0.12 mm layers, 100 % infill, no supports |
| Power slider | 1 + spares | Flat side down, 0.12 mm (or 0.08 mm) layers, 100 % infill. It's tiny, so print a couple |

PLA or PETG both work. PETG holds the inserts better if you open the case often. Printing the caps in a contrasting colour looks good.
Check the caps with calipers: front caps should be **3.97 mm** tall and the side cap **2.25 mm**, ±0.1 mm.

**Assembly**
1. Press the 4 inserts into the front shell's round posts with a soldering iron, about 220 °C for PLA or 245 °C for PETG. Push them in until they sit flush with the top of the post.
2. Fit the button caps from the inside:
   - Drop the two round caps into the front holes.
   - Fit the small rectangular cap into the window on the left edge, flange inside.
   - Fit the power slider into the square window further along the same edge, ridged knob out and fork open side up.
3. Set the board's slide switch and the slider to the same end. Then lower the board in screen down, so the switch lever drops into the slider's fork.
4. Put tape over the XIAO's metal shield. Plug in the battery, tuck the wires down the right-hand side, and lay the battery flat between its stops, wire end toward the buttons.
5. Fit the back shell and tighten the 4 M2.5 × 12 screws until just snug.

**Flashing the firmware**
Plug the remote in with a USB-C cable that carries data. Double-tap reset through the back pin hole with a paper clip, and a USB drive appears. Drag `MediaRemote.uf2` from GitHub onto it, and the remote restarts with the new firmware.

**Customising**
The OpenSCAD source (`MediaRemote_case_v3.scad`) is included. You can change the battery size, the button gaps (`CAP_GAP` / `SB_GAP`), the insert hole size and the clearances, then re-export.

**Notes**
- This project isn't affiliated with Seeed Studio. The board dimensions come from Seeed's published design files, and none of those files are included.
- AI disclosure: the case, firmware and app were designed with the help of an AI assistant (Claude). I directed the design, then printed, assembled and tested it on real hardware, including several rounds of fit fixes.

**Licence:** CC BY-NC-SA 4.0 (case files). The firmware and app code are MIT, on GitHub.

---

## Upload checklist
- [ ] **Photos (required: real photos of your print).** Take at least: the front with the screen on and album art showing, the back with screws, the left edge with the slider and side button, the parts laid out before assembly, and the inside with the board fitted.
- [ ] **Files:**
  - the 5 STLs from `enclosure/stl/`;
  - `MediaRemote_case_v3.scad`;
  - optionally `exploded.png` as a description image. It's a render, so use it only in the description, not as a model photo.
- [ ] **Print profile (3MF, made in Bambu Studio), optional but recommended.** Put all the parts in **one profile**:
  - **Plate 1 "Shells":** front and back shell at 0.2 mm (or 0.16 mm).
  - **Plate 2 "Caps & slider":** 2 front caps, 1 side cap and 2–3 sliders at 0.12 mm and 100 % infill, with build-plate supports for the front caps only.
  - Name the plates. The profile needs its own real photo of a print made with it.
- [ ] **Licence:** select CC BY-NC-SA 4.0 (or your choice).
- [ ] **AI-generated content:** MakerWorld asks you to tag models that are "primarily or entirely AI-generated". Choose the option that you feel fits. The disclosure line in the description keeps it transparent either way.
- [ ] **Remix:** no, this is an original model.
- [ ] Avoid words like "best", "perfect" or "fast" in the title and description, and don't use Seeed's logo.
