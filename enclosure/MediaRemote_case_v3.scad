// ============================================================================
//  Media Remote case v3 (402030 battery)  -  Seeed XIAO 1.14" IPS Display (nRF52840 Plus)
//  - Two shells held by 4x M2 x 14 socket head screws into M2 heat-set inserts
//  - Separate printed button caps (2 front, 1 side)
//
//  Coordinates match Seeed's board model:
//    PCB      x -12.5..12.5, y 0..46.7 (USB-C end = +y), z -0.05..1.56
//    display  on the -z side ("front"), XIAO + battery plug on +z ("back")
//
//  part = "front" | "back" | "cap_front" | "cap_side" | "caps" | "both" | "assembled"
// ============================================================================
part = "both";

/* [Battery] */
// 402030 cell (4 x 20 x 30 mm, 200 mAh) plus margin for tape / swelling / the protection board
BAT_T = 4.2;  BAT_W = 20.5;  BAT_L = 31.0;

/* [Fit] */
CLR  = 0.25;     // board-to-case clearance
FIT  = 0.15;     // clearance around button caps (per side)

/* [Hardware] */
// M2.5 socket head screws + M2.5 x 3 mm heat-set inserts
INS_D     = 3.6;   // insert hole diameter: check your insert packet (M2.5 x 3 mm: usually 3.5-3.6)
INS_DEPTH = 4.0;   // hole depth (insert length + 1 mm). Max ~4.0: the front face is behind it
SCREW_D   = 2.9;   // M2.5 clearance hole
HEAD_D    = 5.0;   // counterbore for M2.5 socket head (4.5 head, 2.5 tall)
CB_DEPTH  = 4.0;   // counterbore depth -> head sits 1.5 mm below the surface
BOSS_D    = 6.0;
SCREW_L   = 12;    // screw length this geometry is checked for (see the echo below)
// THT test pad next to the bottom-right screw (solder on both sides): keep posts off it
TP_CLEAR  = [[11.0, 5.8]];

$fn = 48;
eps = 0.01;

// ---------------------------------------------------------------- shell
WALL   = 1.5;      // right wall
WALL_L = 2.0;      // left wall (houses the side cap + power switch)
FRONT  = 1.2;
BACK   = 1.4;
WALL_T = 1.5;      // top / bottom walls

IX0 = -12.5 - CLR;
IX1 =  12.5 + 2.6;            // room for the battery wires on the right
IY0 = -CLR;
IY1 =  52.9;                  // top screw posts sit beyond the board's top edge
IZ0 = -3.69 - 0.11;           // display glass at z -3.69
BAT_Z0 = 7.45;
IZ1 = BAT_Z0 + BAT_T + 0.15;
SPLIT = 7.2;

OX0 = IX0 - WALL_L; OX1 = IX1 + WALL;
OY0 = IY0 - WALL_T; OY1 = IY1 + WALL_T;
OZ0 = IZ0 - FRONT;  OZ1 = IZ1 + BACK;
R_OUT = 3.0;  EDGE = 0.8;

BAT_X0 = -BAT_W/2;  BAT_Y0 = 7.2;   // centred; wire end toward the bottom (plug side)

// ---------------------------------------------------------------- screws
SCREWS_BOT = [[-10, 2.5], [10, 2.5]];          // through the PCB mounting holes
SCREWS_TOP = [[-10.5, 49.9], [10.5, 49.9]];    // corner posts above the board
INS_TOP_Z  = -0.15;                            // all inserts sit with their top here

// ---------------------------------------------------------------- features
WIN = [[-8.0, 15.9], [8.0, 45.25]];
// front buttons (TS-1080S, top at z -1.58)
BTN = [[-7.0, 9.0], [7.0, 9.0]];
BTN_TOP_Z = -1.58;
CAP_D = 6.0;  CAP_FLANGE_D = 7.6;  CAP_FLANGE_T = 0.8;  CAP_PROUD = 1.0;
CAP_GAP = 0.45;      // gap between cap stem and switch (printing tolerance; switch clicks after ~0.2 mm)
// side button (TS-1084, actuator tip x -13.05, centre y 8.3 z 3.0)
SB_Y = 8.3;  SB_Z = 3.0;  SB_TIP = -13.05;
SB_KNOB = [3.6, 2.4];         // y, z size of the part you press
SB_FLANGE = [5.0, 3.6];       // y, z size of the inside flange
SB_FLANGE_T = 0.6;
SB_PROUD = 0.9;
SB_GAP   = 0.35;     // gap between side cap and its switch actuator
SB_POCKET_X = -13.85;         // inner pocket (flange) reaches out to here
// power slide switch (MSK12C02). Its lever: x -13.65 (tip) .. -12.15 (body face),
// 1.6 wide in y, z 1.89..2.89, centre y 15.35 in Seeed's model, slides along y.
SLIDE = [11.6, 20.4];                 // inner pocket the extender slides in
SL_LEVER_Y = 15.35;  SL_LEVER_W = 1.6;  SL_LEVER_Z = [1.89, 2.89];
SL_TRAVEL  = 1.6;                     // allowed travel each way is generous; the switch sets the real stroke
SL_POCKET_X = -13.85;                 // outer face of the inner pocket (skin outside it is 0.9 mm)
// extender
SL_CAR_X  = [-13.8, -12.6];           // carrier plate (stays outside the PCB edge at -12.5)
SL_CAR_L  = 7.0;  SL_CAR_Z = [1.25, 3.6];
SL_KNOB_L = 3.0;  SL_KNOB_Z = [1.25, 3.35];  SL_KNOB_PROUD = 0.8;
SL_WIN_Z  = [SL_KNOB_Z[0] - 0.2, SL_KNOB_Z[1] + 0.2];
SL_MID    = 16.0;                        // window centre = switch body centre (lever sits at 16.0 -/+ 0.65)
// mic
MIC = [0, 9.0];
// USB-C
USB_W = 8.94 + 2*0.35;  USB_Z = [1.81 - 0.35, 5.97 + 0.08];  USB_ZC = 3.89;
USB_MOUTH = 48.09;
POCKET_W = 13.0;  POCKET_H = 7.5;            // cable plug pocket
POCKET_FLOOR = 48.6;                          // plug overmold stops here
TUNNEL_X = 7.5;                               // half-width of the solid USB block
// XIAO reset/boot + LED pin holes
PIN_HOLES = [[-5.85, 44.9], [5.85, 44.9]];

// ============================================================ helpers
module rbox(x0, x1, y0, y1, z0, z1, r) {
  translate([0, 0, z0]) linear_extrude(z1 - z0)
    offset(r) offset(-r) translate([x0, y0]) square([x1 - x0, y1 - y0]);
}
module outer_solid() {
  hull() {
    rbox(OX0, OX1, OY0, OY1, OZ0 + EDGE, OZ1 - EDGE, R_OUT);
    rbox(OX0 + EDGE, OX1 - EDGE, OY0 + EDGE, OY1 - EDGE, OZ0, OZ1, R_OUT - EDGE);
  }
}
module interior() { rbox(IX0, IX1, IY0, IY1, IZ0, IZ1, 2.0); }
module slab(z0, z1) { translate([-60, -60, z0]) cube([120, 140, z1 - z0]); }
module rrect(sx, sy, r) { offset(r) offset(-r) square([sx, sy], center = true); }

// USB pocket + port opening (cut from both shells)
module usb_cuts() {
  // plug pocket from outside
  translate([-POCKET_W/2, POCKET_FLOOR, USB_ZC - POCKET_H/2])
    rotate([-90, 0, 0]) translate([0, -POCKET_H, 0])
      linear_extrude(10) translate([POCKET_W/2, POCKET_H/2]) rrect(POCKET_W, POCKET_H, 1.2);
  // port window through the block
  translate([-USB_W/2, USB_MOUTH - 1.5, USB_Z[0]]) cube([USB_W, POCKET_FLOOR - USB_MOUTH + 1.6, USB_Z[1] - USB_Z[0]]);
}
module usb_block() { translate([-TUNNEL_X, 48.3, IZ0]) cube([2*TUNNEL_X, IY1 - 48.3 + 0.5, IZ1 - IZ0]); }

// ============================================================ FRONT SHELL
module front_shell() {
  difference() {
    intersection() {
      union() {
        difference() { outer_solid(); interior(); }
        // board edge rails (clear of the test pads)
        for (sx = [-1, 1]) translate([sx < 0 ? IX0 : 11.95, 13.0, IZ0]) cube([0.8, 33.5, -0.15 - IZ0]);
        // insert posts under the PCB mounting holes
        for (p = SCREWS_BOT) translate([p[0], p[1], IZ0 - eps]) cylinder(d = BOSS_D, h = INS_TOP_Z - IZ0 + eps);
        // insert posts for the top screws
        for (p = SCREWS_TOP) translate([p[0], p[1], IZ0 - eps]) cylinder(d = BOSS_D, h = INS_TOP_Z - IZ0 + eps);
        // solid block around the USB pocket
        usb_block();
      }
      slab(OZ0 - 1, SPLIT);
    }
    // clearance around the test pad's solder joint
    for (t = TP_CLEAR) translate([t[0], t[1], -1.2]) cylinder(d = 2.6, h = 1.3);
    // insert holes
    for (p = concat(SCREWS_BOT, SCREWS_TOP)) translate([p[0], p[1], INS_TOP_Z - INS_DEPTH]) cylinder(d = INS_D, h = INS_DEPTH + 1);
    // display window, chamfered outward
    hull() {
      translate([WIN[0][0], WIN[0][1], IZ0 - 0.4]) cube([WIN[1][0]-WIN[0][0], WIN[1][1]-WIN[0][1], 0.5]);
      translate([WIN[0][0]-0.9, WIN[0][1]-0.9, OZ0 - 0.1]) cube([WIN[1][0]-WIN[0][0]+1.8, WIN[1][1]-WIN[0][1]+1.8, 0.1]);
    }
    translate([WIN[0][0], WIN[0][1], OZ0 - 1]) cube([WIN[1][0]-WIN[0][0], WIN[1][1]-WIN[0][1], 3]);
    // front button holes, with a small outer chamfer
    for (b = BTN) translate([b[0], b[1], 0]) {
      translate([0, 0, OZ0 - 1]) cylinder(d = CAP_D + 2*FIT, h = FRONT + 2);
      translate([0, 0, OZ0 - eps]) cylinder(d1 = CAP_D + 2*FIT + 1.0, d2 = CAP_D + 2*FIT, h = 0.5);
    }
    // mic port
    translate([MIC[0], MIC[1], OZ0 - 1]) cylinder(d = 1.0, h = 5, $fn = 16);
    // USB
    usb_cuts();
    // side button: inner pocket (open to the split, for the actuator + cap flange) ...
    translate([SB_POCKET_X - FIT, SB_Y - SB_FLANGE[0]/2 - FIT, SB_Z - SB_FLANGE[1]/2 - FIT])
      cube([IX0 - SB_POCKET_X + FIT + 0.5, SB_FLANGE[0] + 2*FIT, 20]);
    // ... and the window the cap pokes through
    translate([OX0 - 1, SB_Y, SB_Z]) rotate([0, 90, 0]) linear_extrude(SB_POCKET_X - OX0 + 1.1)
      rotate(90) rrect(SB_KNOB[0] + 2*FIT, SB_KNOB[1] + 2*FIT, 0.6);
    // power slide switch: inner pocket for the lever + extender (open to the split) ...
    translate([SL_POCKET_X, SLIDE[0], 1.0]) cube([IX0 - SL_POCKET_X + 0.5, SLIDE[1] - SLIDE[0], 20]);
    // ... and the window the extender knob slides in
    translate([OX0 - 1, SL_MID - (SL_KNOB_L + SL_TRAVEL + 0.4)/2, SL_WIN_Z[0]])
      cube([SL_POCKET_X - OX0 + 1.1, SL_KNOB_L + SL_TRAVEL + 0.4, SL_WIN_Z[1] - SL_WIN_Z[0]]);
  }
}

// ============================================================ BACK SHELL
module back_shell() {
  difference() {
    union() {
      intersection() {
        union() {
          difference() { outer_solid(); interior(); }
          usb_block();
        }
        slab(SPLIT, OZ1 + 1);
      }
      // screw columns: bottom ones press the PCB, top ones meet the insert posts
      for (p = SCREWS_BOT) translate([p[0], p[1], 1.58]) cylinder(d = 5.0, h = IZ1 - 1.58 + eps);
      // (below the split they're trimmed to the inside so they slide past the front shell's walls)
      intersection() {
        for (p = SCREWS_TOP) translate([p[0], p[1], INS_TOP_Z + 0.15]) cylinder(d = BOSS_D, h = IZ1 - INS_TOP_Z + eps);
        union() { rbox(IX0 + 0.1, IX1 - 0.1, IY0 + 0.1, IY1 - 0.1, IZ0, IZ1 + 1, 2.0); slab(SPLIT, OZ1 + 1); }
      }
      // collars around the screw-head recesses: the recess (CB_DEPTH) is deeper than the back
      // plate, so without these the narrow posts would be cut off from the plate
      for (p = concat(SCREWS_BOT, SCREWS_TOP))
        translate([p[0], p[1], OZ1 - CB_DEPTH - 1.2]) cylinder(d = HEAD_D + 2.4, h = CB_DEPTH + 1.2 - BACK + eps);
      // hold-down on top of the USB-C receptacle
      translate([-3.5, 42.6, 6.1]) cube([7, 5.4, IZ1 - 6.1 + eps]);
      // battery end stops
      for (yy = [BAT_Y0 - 1.2, BAT_Y0 + BAT_L + 0.3]) for (xx = [BAT_X0 + 1, BAT_X0 + BAT_W - 8])
        translate([xx, yy, IZ1 - 3.0]) cube([7, 0.9, 3.0 + eps]);
      // battery side stops
      for (xx = [BAT_X0 - 1.2, BAT_X0 + BAT_W + 0.3]) for (yy = [BAT_Y0 + 4, BAT_Y0 + BAT_L - 10])
        translate([xx, yy, IZ1 - 3.0]) cube([0.9, 6, 3.0 + eps]);
      // pin-hole guide tubes
      for (p = PIN_HOLES) translate([p[0], p[1], 4.4]) cylinder(d = 2.5, h = IZ1 - 4.4 + eps);
      // (no retainers over the side cap / power slider: their knobs sit in windows, so they can't lift out)
    }
    for (t = TP_CLEAR) translate([t[0], t[1], 1.4]) cylinder(d = 2.6, h = 1.3);
    // screw holes + counterbores
    for (p = concat(SCREWS_BOT, SCREWS_TOP)) translate([p[0], p[1], 0]) {
      translate([0, 0, -5]) cylinder(d = SCREW_D, h = 30, $fn = 24);
      translate([0, 0, OZ1 - CB_DEPTH]) cylinder(d = HEAD_D, h = CB_DEPTH + 1, $fn = 32);
    }
    for (p = PIN_HOLES) translate([p[0], p[1], 0]) cylinder(d = 1.6, h = 30, $fn = 20);
    usb_cuts();
  }
}

// ============================================================ CAPS
// front cap: knob (outside) + flange (inside) + stem (presses the switch)
module cap_front() {
  knob_h  = FRONT + CAP_PROUD;
  stem_h  = (BTN_TOP_Z - CAP_GAP) - (IZ0 + CAP_FLANGE_T);
  // modelled in place: z = 0 at the inner face of the front wall, +z toward the board
  translate([0, 0, -knob_h]) {
    hull() {
      translate([0, 0, 0.5]) cylinder(d = CAP_D, h = knob_h - 0.5 + eps);
      cylinder(d = CAP_D - 1.0, h = eps);       // chamfered top edge
    }
  }
  cylinder(d = CAP_FLANGE_D, h = CAP_FLANGE_T);
  translate([0, 0, CAP_FLANGE_T - eps]) cylinder(d = 2.6, h = stem_h + eps);
}
module cap_side() {
  // z along +x (toward the board): flange at the inside, knob through the wall
  knob_l = (SB_POCKET_X - OX0) + SB_PROUD;
  flange_t = SB_TIP - SB_GAP - SB_POCKET_X;        // stops SB_GAP short of the actuator tip
  translate([0, 0, -knob_l]) hull() {
    translate([0, 0, 0.4]) linear_extrude(knob_l - 0.4 + eps) rrect(SB_KNOB[0], SB_KNOB[1], 0.6);
    linear_extrude(eps) rrect(SB_KNOB[0] - 0.8, SB_KNOB[1] - 0.8, 0.3);
  }
  linear_extrude(flange_t) rrect(SB_FLANGE[0], SB_FLANGE[1], 0.5);
}
module caps_in_place() {
  for (b = BTN) translate([b[0], b[1], IZ0]) cap_front();
  translate([SB_POCKET_X, SB_Y, SB_Z]) rotate([0, 90, 0]) rotate([0, 0, 90]) cap_side();
}

// ============================================================ SLIDE EXTENDER
// Modelled in place, centred on the switch lever at y = 0 (add SL_LEVER_Y to place it).
module slide_ext() {
  difference() {
    union() {
      // carrier plate inside the wall (wider than the window, so it can't fall out)
      translate([SL_CAR_X[0], -SL_CAR_L/2, SL_CAR_Z[0]]) cube([SL_CAR_X[1] - SL_CAR_X[0], SL_CAR_L, SL_CAR_Z[1] - SL_CAR_Z[0]]);
      // knob through the window, with grip ridges
      kx0 = OX0 - SL_KNOB_PROUD;
      translate([kx0, -SL_KNOB_L/2, SL_KNOB_Z[0]]) cube([SL_CAR_X[0] - kx0 + eps, SL_KNOB_L, SL_KNOB_Z[1] - SL_KNOB_Z[0]]);
    }
    // fork slot for the lever: open toward the board and on top (so the board drops in)
    translate([SL_CAR_X[0] + 0.05, -(SL_LEVER_W + 0.2)/2, SL_LEVER_Z[0] - 0.1]) cube([5, SL_LEVER_W + 0.2, 10]);
    // grip grooves across the knob face
    for (dy = [-1.0, 0, 1.0]) translate([OX0 - SL_KNOB_PROUD - 1, dy - 0.2, SL_KNOB_Z[0] - 1]) cube([1.3, 0.4, 5]);
  }
}

// ============================================================ board proxy (preview only)
module board_proxy() {
  color("green") rbox(-12.5, 12.5, 0, 46.7, -0.05, 1.56, 2.5);
  color("#222") translate([-8.8, 15.06, -3.69]) cube([17.6, 31, 3.61]);
  color("silver") translate([-4.47, 40.34, 1.81]) cube([8.94, 7.75, 4.16]);
  color("white") translate([1.21, 11.95, 1.5]) cube([8.6, 7.9, 5.6]);
  color("white") translate([-6, 0.3, 1.59]) cube([12, 10.5, 5.2]);
  color("gray") translate([-6.5, 28.97, 1.58]) cube([13, 10.9, 3.49]);
  color("blue", 0.4) translate([BAT_X0, BAT_Y0, BAT_Z0]) cube([BAT_W, BAT_L, BAT_T]);
}

// screw check: tip must land inside the insert, above the bottom of its hole
SEAT_Z = OZ1 - CB_DEPTH;
TIP_Z  = SEAT_Z - SCREW_L;
echo(str("Screw M2.5 x ", SCREW_L, ": thread in insert = ", INS_TOP_Z - TIP_Z, " mm (insert 3 mm), clearance to hole bottom = ", TIP_Z - (INS_TOP_Z - INS_DEPTH), " mm"));
assert(TIP_Z > INS_TOP_Z - INS_DEPTH + 0.3, "screw too long: it would bottom out in the insert hole");
assert(INS_TOP_Z - TIP_Z >= 2.0, "screw too short: less than 2 mm of thread in the insert");
assert(OZ1 - CB_DEPTH - 1.2 > SPLIT + 0.3, "screw collars would reach below the split");

// ============================================================ output
if (part == "front") translate([0, 0, -OZ0]) front_shell();
else if (part == "back") translate([0, 0, OZ1]) rotate([180, 0, 0]) back_shell();
else if (part == "cap_front") translate([0, 0, FRONT + CAP_PROUD]) cap_front();
else if (part == "cap_side") rotate([0, 0, 0]) translate([0, 0, (SB_POCKET_X - OX0) + SB_PROUD]) cap_side();
else if (part == "slider") translate([0, 0, -SL_CAR_Z[0]]) slide_ext();
else if (part == "caps") {
  for (i = [0, 1]) translate([i * 10, 0, FRONT + CAP_PROUD]) cap_front();
  translate([20, 0, (SB_POCKET_X - OX0) + SB_PROUD]) cap_side();
}
else if (part == "assembled") { front_shell(); back_shell(); color("orange") { caps_in_place(); translate([0, SL_LEVER_Y, 0]) slide_ext(); } %board_proxy(); }
else { front_shell(); translate([0, 0, 16]) back_shell(); color("orange") { caps_in_place(); translate([0, SL_LEVER_Y, 0]) slide_ext(); } %board_proxy(); }
