# Ceiling mount for the tracker phone (Oukitel WP5) on a 48.3 mm pipe.
#
# Build:  /Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd phone_mount.py
# Writes phone_mount.FCStd (assembly) and one STL per part, in print orientation, next to this file.
#
# Global frame: pipe along X, pipe axis at z=0. The phone hangs below the pipe, screen up (towards the
# pipe), camera down through the open floor of the cradle.
#
# Two adjustment axes:
#   - pitch (along the phone): turn the clamp on the pipe (loosen the two clamp bolts)
#   - roll (across the phone): toothed hinge (Hirth-style, 36 teeth = 10 deg steps), axis across the
#     pipe, on the middle of the phone's long side; the phone's long axis runs across the pipe
#
# Hardware: 2x M6x20 + 2x M6 nut (clamp), 1x M6x35 + M6 nut + M6 wing nut (hinge),
#           2x M4x16 + 2x M4 nut (retaining bar).

import math
import os

import FreeCAD as App
import Part

V = App.Vector
OUT = os.path.dirname(os.path.abspath(__file__))

# --- parameters --------------------------------------------------------------------------------
PIPE_D = 48.3
CLAMP_ID = 48.6          # bore; the halves are cut back so they squeeze before they touch
CLAMP_SPLIT_GAP = 0.75   # removed from each half at the split plane
CLAMP_WALL = 6.0
CLAMP_W = 30.0           # along the pipe
EAR_H = 10.0             # each half's ear height from the split plane
BOLT_Y = 32.0            # clamp bolt centre from pipe axis
EAR_END = 39.0

M6_HOLE = 6.4
M6_NUT_AF = 10.3         # across flats, with clearance
M6_NUT_DEPTH = 5.0
M4_HOLE = 4.4
M4_NUT_AF = 7.3
M4_NUT_T = 3.6

HINGE_Z = -62.0          # hinge axis height (global)
TAB_T = 10.0             # tab thickness (along Y)
HUB_R = 12.0             # hinge hub radius = tooth ring radius
TEETH = 36
TOOTH_H = 1.2
TOOTH_CLEAR = 0.15
CHEEK_T = 8.0

# phone (Oukitel WP5, measured from photos with a folding rule, about +-1 mm)
PHONE_L = 157.0          # measured ~157 (folding rule, 2026-11-22)
PHONE_W = 77.0           # measured ~77
PHONE_T = 17.0           # measured ~16-17
FIT = 1.5                # clearance per side in plan
FIT_T = 1.5              # clearance over the screen
CAMERA_FROM_TOP = 20.0   # lens centre from the top edge (end away from USB), measured ~20
CAMERA_FROM_LEFT = PHONE_W / 2  # lens centre across the phone, only for the marker

FLOOR_T = 3.0
WALL_T = 3.0
END_WALL_T = 5.0
LIP = 4.0                # lip / bar overlap over the screen edge
LIP_T = 3.0
RAIL_H = 10.0            # low side rails (below the side buttons)
CORNER_L = 25.0          # full-height side walls at the top end
FLOOR_RIM = 8.0          # floor frame width around the camera window
POST_L = 10.0
POST_W = 14.0            # posts at the cable end, inward from the pocket side
BAR_T = 4.0

LP = PHONE_L + 2 * FIT
WP = PHONE_W + 2 * FIT
POCKET_H = PHONE_T + FIT_T
TOP_Z = FLOOR_T + POCKET_H          # underside of lips and bar
CRADLE_H = TOP_Z + LIP_T
CAM_X = FIT + CAMERA_FROM_TOP    # camera in the cradle frame
HINGE_TO_WALL = 20.0     # hinge axis to the outside of the cradle's hinge-side wall
HINGE_WALL_L = 50.0      # full-height section of the hinge-side wall (mind the side buttons)


def to_global(shape):
    """Cradle frame (long axis X, floor on z=0, hinge-side wall at -Y) to global: the phone's long
    axis runs across the pipe (global Y), the hinge-side wall faces the clamp."""
    s = shape.copy()
    s.rotate(V(0, 0, 0), V(0, 0, 1), -90)  # (x, y) -> (y, -x)
    # the camera lands on the pipe's centre line (global y=0), under the hinge
    s.translate(V(HINGE_TO_WALL + WP / 2 + END_WALL_T, CAM_X, HINGE_Z - HUB_R))
    return s


# --- helpers -----------------------------------------------------------------------------------
def box(x0, x1, y0, y1, z0, z1):
    return Part.makeBox(x1 - x0, y1 - y0, z1 - z0, V(x0, y0, z0))


def cyl(r, h, pos, direction):
    return Part.makeCylinder(r, h, pos, direction)


def poly_xz(points, y0, y1):
    """Prism from a polygon in the XZ plane, spanning y0..y1."""
    wire = Part.makePolygon([V(x, y0, z) for x, z in points] + [V(points[0][0], y0, points[0][1])])
    return Part.Face(wire).extrude(V(0, y1 - y0, 0))


def hex_prism(af, h, pos, direction):
    r = af / math.sqrt(3)
    pts = [V(r * math.cos(math.radians(60 * i)), r * math.sin(math.radians(60 * i)), 0) for i in range(7)]
    prism = Part.Face(Part.makePolygon(pts)).extrude(V(0, 0, h))
    rot = App.Rotation(V(0, 0, 1), direction)
    prism.Placement = App.Placement(pos, rot)
    return prism


def fuse(shapes):
    out = shapes[0]
    for s in shapes[1:]:
        out = out.fuse(s)
    return out.removeSplitter()


# --- hinge teeth -------------------------------------------------------------------------------
def teeth_raw():
    """Tooth ring on the plane z=0, axis Z, teeth pointing +Z. All flanks pass through one point on
    the axis (Hirth), so the mating ring is simply the slab minus these teeth."""
    rt = HUB_R + 0.5
    zc = TOOTH_H / 2
    step = 2 * math.pi / TEETH
    base = -0.3
    k = rt / HUB_R
    solids = []
    for i in range(TEETH):
        a0, a1 = i * step, (i + 1) * step
        am = (a0 + a1) / 2
        c = V(0, 0, zc)
        g1 = V(rt * math.cos(a0), rt * math.sin(a0), zc - zc * k)
        g2 = V(rt * math.cos(a1), rt * math.sin(a1), zc - zc * k)
        t = V(rt * math.cos(am), rt * math.sin(am), zc + zc * k)
        cb, g1b, g2b, tb = (V(p.x, p.y, base) for p in (c, g1, g2, t))
        faces = [
            Part.Face(Part.makePolygon([c, g1, t, c])),
            Part.Face(Part.makePolygon([c, t, g2, c])),
            Part.Face(Part.makePolygon([cb, tb, g1b, cb])),
            Part.Face(Part.makePolygon([cb, g2b, tb, cb])),
            Part.Face(Part.makePolygon([cb, g1b, g1, c, cb])),
            Part.Face(Part.makePolygon([g1b, tb, t, g1, g1b])),
            Part.Face(Part.makePolygon([tb, g2b, g2, t, tb])),
            Part.Face(Part.makePolygon([g2b, cb, c, g2, g2b])),
        ]
        solids.append(Part.Solid(Part.Shell(faces)))
    return Part.makeCompound(solids)


RAW = teeth_raw()
RAW_FUSED = fuse(list(RAW.Solids))
RING = cyl(HUB_R, 2 * TOOTH_H, V(0, 0, 0), V(0, 0, 1))
TAB_TEETH = RAW_FUSED.common(RING)
CHEEK_TEETH = cyl(HUB_R, TOOTH_H, V(0, 0, 0), V(0, 0, 1)).cut(RAW_FUSED)
CHEEK_TEETH.translate(V(0, 0, TOOTH_CLEAR))


def place_teeth(shape, y_face, outward, hinge):
    """Put a local tooth ring (base z=0, teeth +Z) on the plane y=y_face, teeth pointing
    `outward` (+1: +Y, -1: -Y), centred on the hinge axis at `hinge` (x, z)."""
    s = shape.copy()
    s.rotate(V(0, 0, 0), V(1, 0, 0), -90 if outward > 0 else 90)
    s.translate(V(hinge[0], y_face, hinge[1]))
    return s


# --- clamp -------------------------------------------------------------------------------------
def clamp_half(upper):
    ro = CLAMP_ID / 2 + CLAMP_WALL
    ring = cyl(ro, CLAMP_W, V(-CLAMP_W / 2, 0, 0), V(1, 0, 0))
    ears = box(-CLAMP_W / 2, CLAMP_W / 2, -EAR_END, EAR_END, -EAR_H, EAR_H)
    body = ring.fuse(ears)
    if upper:
        body = body.common(box(-50, 50, -50, 50, CLAMP_SPLIT_GAP, 50))
    else:
        body = body.common(box(-50, 50, -50, 50, -50, -CLAMP_SPLIT_GAP))
        hz = HINGE_Z
        tab = poly_xz(
            [(-CLAMP_W / 2, -ro + 4), (CLAMP_W / 2, -ro + 4), (CLAMP_W / 2, hz + 8),
             (HUB_R, hz), (-HUB_R, hz), (-CLAMP_W / 2, hz + 8)],
            -TAB_T / 2, TAB_T / 2)
        hub = cyl(HUB_R, TAB_T, V(0, -TAB_T / 2, hz), V(0, 1, 0))
        body = body.fuse(tab).fuse(hub)
        body = body.fuse(place_teeth(TAB_TEETH, TAB_T / 2, +1, (0, hz)))
        body = body.fuse(place_teeth(TAB_TEETH, -TAB_T / 2, -1, (0, hz)))
        body = body.cut(cyl(M6_HOLE / 2, 40, V(0, -20, hz), V(0, 1, 0)))
    body = body.cut(cyl(CLAMP_ID / 2, CLAMP_W + 2, V(-CLAMP_W / 2 - 1, 0, 0), V(1, 0, 0)))
    for y in (-BOLT_Y, BOLT_Y):
        body = body.cut(cyl(M6_HOLE / 2, 2 * EAR_H + 2, V(0, y, -EAR_H - 1), V(0, 0, 1)))
        if not upper:
            body = body.cut(hex_prism(M6_NUT_AF, M6_NUT_DEPTH + 0.01, V(0, y, -EAR_H - 0.01), V(0, 0, 1)))
    return body.removeSplitter()


# --- cradle (built in its own frame: floor on z=0, pocket from x=0 to LP, then to_global) ---------
def cradle():
    yo = WP / 2 + WALL_T
    floor = box(-END_WALL_T, LP + POST_L, -yo, yo, 0, FLOOR_T)
    window = box(FLOOR_RIM + 4, LP - FLOOR_RIM - 4, -(WP / 2 - FLOOR_RIM), WP / 2 - FLOOR_RIM, -1, FLOOR_T + 1)
    end_wall = box(-END_WALL_T, 0, -yo, yo, 0, CRADLE_H)
    end_lip = box(0, LIP, -WP / 2, WP / 2, TOP_Z, CRADLE_H)
    parts = [floor, end_wall, end_lip]
    for s in (-1, 1):
        y0, y1 = sorted((s * WP / 2, s * yo))
        parts.append(box(0, LP, y0, y1, 0, FLOOR_T + RAIL_H))          # rail
        parts.append(box(0, CORNER_L, y0, y1, 0, CRADLE_H))            # top-end corner wall
        py0, py1 = sorted((s * (WP / 2 - POST_W), s * yo))
        parts.append(box(LP, LP + POST_L, py0, py1, 0, TOP_Z))         # post at the cable end
    # full-height section of the hinge-side wall, where the cheeks attach
    wc = min(max(CAM_X, HINGE_WALL_L / 2), LP - HINGE_WALL_L / 2)
    parts.append(box(wc - HINGE_WALL_L / 2, wc + HINGE_WALL_L / 2,
                     -WP / 2 - END_WALL_T, -WP / 2, 0, CRADLE_H))
    body = fuse(parts).cut(window)
    # bar screws: vertical hole, nut slot from the outside
    for s in (-1, 1):
        y = s * (WP / 2 - POST_W / 2 + WALL_T / 2)
        x = LP + POST_L / 2
        body = body.cut(cyl(M4_HOLE / 2, TOP_Z + 2, V(x, y, -1), V(0, 0, 1)))
        z0 = TOP_Z - 11.5
        ys = sorted((y, s * (yo + 1)))
        body = body.cut(box(x - M4_NUT_AF / 2, x + M4_NUT_AF / 2, ys[0], ys[1], z0, z0 + M4_NUT_T))
        body = body.cut(hex_prism(M4_NUT_AF, M4_NUT_T, V(x, y, z0), V(0, 0, 1)))
    body = to_global(body)
    # hinge cheeks, in the global frame (hinge axis along Y at x=0)
    hx, hz = 0.0, HINGE_Z
    gap = TAB_T / 2 + TOOTH_H + TOOTH_CLEAR
    parts = [body]
    for s in (-1, 1):
        y0, y1 = sorted((s * gap, s * (gap + CHEEK_T)))
        cheek = box(hx, HINGE_TO_WALL + 0.01, y0, y1, hz - HUB_R, hz + HUB_R)
        parts.append(cheek.fuse(cyl(HUB_R, CHEEK_T, V(hx, y0, hz), V(0, 1, 0))))
        parts.append(place_teeth(CHEEK_TEETH, s * TAB_T / 2, s, (hx, hz)))
    body = fuse(parts)
    body = body.cut(cyl(M6_HOLE / 2, 60, V(hx, -30, hz), V(0, 1, 0)))
    body = body.cut(hex_prism(M6_NUT_AF, M6_NUT_DEPTH, V(hx, gap + CHEEK_T - M6_NUT_DEPTH + 0.01, hz), V(0, 1, 0)))
    return body.removeSplitter()


def bar():
    yo = WP / 2 + WALL_T
    b = box(LP - LIP, LP + POST_L, -yo, yo, TOP_Z, TOP_Z + BAR_T)
    for s in (-1, 1):
        y = s * (WP / 2 - POST_W / 2 + WALL_T / 2)
        b = b.cut(cyl(M4_HOLE / 2, BAR_T + 2, V(LP + POST_L / 2, y, TOP_Z - 1), V(0, 0, 1)))
    return to_global(b)


def phone_dummy():
    phone = box(FIT, FIT + PHONE_L, -PHONE_W / 2, PHONE_W / 2, FLOOR_T, FLOOR_T + PHONE_T)
    lens = cyl(5, 30, V(CAM_X, -PHONE_W / 2 + CAMERA_FROM_LEFT, FLOOR_T - 30), V(0, 0, 1))
    return to_global(phone.fuse(lens))


# --- build -------------------------------------------------------------------------------------
doc = App.newDocument("phone_mount")
pipe = cyl(PIPE_D / 2, 400, V(-150, 0, 0), V(1, 0, 0))
parts = {
    "clamp_top": clamp_half(True),
    "clamp_bottom": clamp_half(False),
    "cradle": cradle(),
    "bar": bar(),
}
for name, shape in parts.items():
    if not shape.isValid():
        raise SystemExit(f"{name}: invalid shape")
    print(f"{name}: volume {shape.Volume / 1000:.1f} cm3, bbox {shape.BoundBox}")

dummy = phone_dummy()

for name, shape in parts.items():
    doc.addObject("Part::Feature", name).Shape = shape
doc.addObject("Part::Feature", "pipe_ref").Shape = pipe
doc.addObject("Part::Feature", "phone_ref").Shape = dummy

# interference checks in the assembled position
for a, b in (("clamp_bottom", "cradle"), ("clamp_bottom", "clamp_top"), ("cradle", "bar")):
    v = parts[a].common(parts[b]).Volume
    print(f"overlap {a}/{b}: {v:.3f} mm3")
print(f"overlap phone/cradle: {dummy.common(parts['cradle']).Volume:.3f} mm3")
print(f"overlap phone/bar: {dummy.common(parts['bar']).Volume:.3f} mm3")
print(f"overlap pipe/clamp: {pipe.common(parts['clamp_top'].fuse(parts['clamp_bottom'])).Volume:.3f} mm3")

doc.recompute()
doc.saveAs(os.path.join(OUT, "phone_mount.FCStd"))

# STLs in print orientation
import Mesh


def export(name, shape, rot_axis=None, angle=0, subdir=""):
    s = shape.copy()
    if rot_axis:
        s.rotate(V(0, 0, 0), rot_axis, angle)
    bb = s.BoundBox
    s.translate(V(-bb.XMin, -bb.YMin, -bb.ZMin))
    d = os.path.join(OUT, subdir)
    os.makedirs(d, exist_ok=True)
    Mesh.Mesh(s.tessellate(0.02)).write(os.path.join(d, f"{name}.stl"))
    print(f"  {os.path.join(subdir, name)}.stl: {shape.Volume / 1000:.1f} cm3")


export("clamp_top", parts["clamp_top"], V(0, 1, 0), 90)       # pipe axis vertical
export("clamp_bottom", parts["clamp_bottom"], V(0, 1, 0), 90)
export("cradle", parts["cradle"])                               # floor down
export("bar", parts["bar"])

# --- test prints: slices of the real parts, to check the fits before the full prints -------------
BIG = 400
FLOOR_Z = HINGE_Z - HUB_R
cradle_g = parts["cradle"]
no_cheeks = box(HINGE_TO_WALL, BIG, -BIG, BIG, -BIG, BIG)
cable_end_y = CAM_X - (LP - 15)          # global y of local x = LP - 15
SLICE = 5.0                              # clamp ring band width
EAR_KEEP = BOLT_Y - M6_NUT_AF / 2 - 3   # |y| from here outwards: ears at full width
T1_KEEP = fuse([box(CLAMP_W / 2 - SLICE, CLAMP_W / 2, -BIG, BIG, -BIG, BIG),
                box(-BIG, BIG, EAR_KEEP, BIG, -BIG, BIG), box(-BIG, BIG, -BIG, -EAR_KEEP, -BIG, BIG)])
tests = {
    # bore on the pipe, clamping gap, M6 holes, M6 nut traps: the ears at full width (a thin slice
    # would cut through the bolt holes), the ring as a thin band on the print bed
    "t1_clamp_top_slice": (parts["clamp_top"].common(T1_KEEP), V(0, 1, 0), 90),
    "t1_clamp_bottom_slice": (parts["clamp_bottom"].common(T1_KEEP).common(
        box(-BIG, BIG, -BIG, BIG, -CLAMP_ID / 2 - CLAMP_WALL - 1, BIG)), V(0, 1, 0), 90),
    # hinge, one side only: tab hub with its +Y teeth, the +Y cheek with teeth and nut trap
    "t2_hinge_tab": (parts["clamp_bottom"].common(box(-BIG, BIG, -TAB_T / 2, BIG, -BIG, HINGE_Z + 15)), V(0, 1, 0), 90),
    "t2_hinge_cheek": (cradle_g.common(box(-BIG, HINGE_TO_WALL + 2, TAB_T / 2, 25, -BIG, BIG)), None, 0),
    # phone length and width: a 0.6 mm floor frame with 2.4 mm of wall
    "t3_cradle_footprint": (cradle_g.common(no_cheeks).common(
        box(-BIG, BIG, -BIG, BIG, FLOOR_Z + FLOOR_T - 0.6, FLOOR_Z + FLOOR_T + 2.4)), None, 0),
    # phone thickness under the end lip: the corner away from the hinge, 6 mm along the phone
    "t4_cradle_top_corner": (cradle_g.common(
        box(HINGE_TO_WALL + END_WALL_T + WP / 2 + 10, BIG, CAM_X - 6, BIG, -BIG, BIG)), None, 0),
    # bar, M4 holes and nut slots, room for the USB plug (print together with the bar)
    "t5_cradle_cable_end": (cradle_g.common(box(-BIG, BIG, -BIG, CAM_X - (LP - 5), -BIG, BIG)), None, 0),
}
for name, (shape, axis, angle) in tests.items():
    if not shape.isValid() or shape.Volume < 1:
        raise SystemExit(f"{name}: bad test shape")
    export(name, shape, axis, angle, subdir="test-prints")
print("done")
