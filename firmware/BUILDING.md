# Building the firmware

## Option A: Arduino IDE (easiest)
1. In **File → Preferences → Additional boards manager URLs**, add:
   `https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json`
2. In **Boards Manager**, install **Seeed nRF52 Boards** (1.1.13 or newer).
3. Install the **Seeed_GFX2 v1.0.0** library: download the ZIP from
   https://github.com/Seeed-Studio/Seeed_GFX2/releases, then use **Sketch → Include Library → Add .ZIP Library…**
4. Install **Python 3** with **"Add python.exe to PATH"** ticked. The board package needs it during upload.
   On Windows, also switch off the Microsoft Store "python.exe" alias in **App execution aliases**.
5. Open `firmware/MediaRemote/MediaRemote.ino`, select **Tools → Board → Seeed nRF52 Boards → Seeed XIAO nRF52840 Plus** and the board's COM port, then click **Upload**.

Tips:
- Keep the project in a folder path **without spaces**. The Seeed toolchain splits paths at spaces.
- Arduino IDE 2 auto-saves open sketches. If you edit the files outside the IDE, close the tab first, or turn off **File → Auto Save**.
- If the upload can't find the board, double-tap reset to enter the bootloader, then re-select the port; its number can change.

## Option B: arduino-cli and a .uf2 file
`build-uf2.sh` compiles the sketch with arduino-cli and converts it to `prebuilt/MediaRemote.uf2`:
- **Board:** FQBN `Seeeduino:nrf52:xiaonRF52840Plus`.
- **Converter:** the core's `tools/uf2conv/uf2conv.py`, with family `0xADA52840`.
- **Address check:** the output must start at address **0x27000** (the application area after the S140 v7 SoftDevice). Seeed's own UF2 step skips XIAO boards, which is why the script converts it explicitly.

A .uf2 only replaces the application. The bootloader, the SoftDevice and the stored pairings are kept.

## Debug output
`DEBUG_LOG` near the top of the sketch prints every button press, mode change, phone connect and power-off reason to the serial monitor at 115200 baud.
