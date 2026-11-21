# Media Remote: treadmill mode

**Status: compiles, not yet run on the remote or paired with the watch.**
Written 2026-11-21 on top of the GitHub snapshot `cc80861` (2026-11-13). Only `MediaRemote.ino` changed; the Android app is untouched.

## What it does
The remote also poses as a Bluetooth foot pod. You set the treadmill's belt speed on the remote and the watch uses it for pace and distance. The phone and the watch can both stay connected.

## Controls
| Where | Button | Action |
|---|---|---|
| Anywhere | **Side**, hold 1.5 s | Switch between the music screen and the treadmill screen |
| Treadmill screen | **Left / Right** tap | Speed down / up 0.1 km/h (hold to keep changing) |
| Treadmill screen | **Side** tap | Belt on / belt stopped (stopped sends the watch 0) |
| Treadmill screen | **Side**, hold 0.6 s | Volume mode, as before |

- With the belt on you can go back to the music screen. The speed keeps being sent and shows in the status bar.
- If the screen is asleep on the treadmill screen, the first press only wakes it.
- Leaving the treadmill screen with the belt stopped clears the on-screen time and distance.
- Side hold 3 s is still power off. The hold order is now 0.6 s volume, 1.5 s switch screen, 3 s power off.

## First use
1. Build and flash the firmware as in `firmware/BUILDING.md`. The GitHub workflow only builds the Android app; `firmware/prebuilt/MediaRemote.uf2` is the released firmware without treadmill mode.
2. Check the phone still works. If album art or track info stops, forget the remote in the phone's Bluetooth settings and pair again.
3. Open the treadmill screen on the remote.
4. On the watch: **Sensors & Accessories > Add New > Foot Pod**, and pick **Media Remote**.
5. Start a Treadmill activity, set the speed on the remote, tap Side for belt on.

## Things to check on the first run
- **Does the watch list the remote as a foot pod?** It advertises the foot pod service next to the media-remote one. If the watch doesn't find it, say so and the fix is to advertise only the foot pod service while the treadmill screen is open.
- **Pace and distance on the watch** should follow the set speed. If the watch ignores it, look at the foot pod's Speed setting on the watch (Indoor or Always).
- **Cadence.** The foot pod data must carry a cadence value and the remote sends 0, hoping the watch keeps its wrist cadence. If the watch shows 0 cadence, set `TM_CADENCE_MODE` to `1` near the top of the sketch (it then sends an estimate, not a measurement).
- **Treadmill screen missing?** Holding Side for 1.5 s shows "No treadmill mode" if the Bluetooth stack could not get enough memory for two connections. The remote then works exactly as before. The serial log (115200 baud) prints which "Bluetooth memory plan" it ended up on: 0 and 1 have treadmill mode, 2 does not.

## Going back
Flash the released `MediaRemote.uf2` from GitHub. Nothing else on the remote changes.
