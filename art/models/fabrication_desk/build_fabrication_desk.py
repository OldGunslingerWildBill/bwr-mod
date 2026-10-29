"""Build the fabrication desk in Blender (run headless) and export its OBJ model.

    blender --background --factory-startup --python art/models/fabrication_desk/build_fabrication_desk.py -- [--previews DIR]

What it does:
  1. makes the geometry as a face soup with furniture_geo.py's Builder (plain Python; the same box-by-box way the control-room
     furniture is made, so the two sets of models line up),
  2. turns the soup into one Blender mesh with materials and UVs and saves art/models/fabrication_desk/fabrication_desk.blend,
  3. exports fabrication_desk.obj into mod/src/main/resources/assets/bwr/models/block/fabrication_desk/ (axes: Blender -Z
     forward, Y up, the scene laid out as (x, -z, y) of Minecraft's x east, y up, z south) and writes materials.mtl (plain colours
     are minecraft:block/white_concrete tinted by Kd, like the panel bodies and the furniture),
  4. with --previews DIR renders Workbench PNGs of the desk from the front and the back into DIR.

The desk is a generic workshop bench: a steel drawer cabinet at the left, a knee space with a shelf, a work top with a parts
tray and a tilted drawing board, and a pegboard at the back with a few hanging tools and an amber lamp. It is authored for
facing=north (the operator stands at -z); the blockstate turns it. It has no name, mark or logo of any maker.
"""
import os
import sys

sys.dont_write_bytecode = True

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "art", "models", "room_furniture"))
import furniture_geo as g  # noqa: E402

RES = os.path.join(REPO, "mod", "src", "main", "resources", "assets", "bwr")
OUT = os.path.join(RES, "models", "block", "fabrication_desk")
BLEND = os.path.join(HERE, "fabrication_desk.blend")

# name -> Kd (0..255). Every material is white_concrete tinted by its Kd.
MATERIALS = {
    "steel": (0x6C, 0x75, 0x7A),      # brushed steel carcass and trays
    "drawer": (0x9A, 0xA3, 0xA7),     # drawer fronts, a lighter steel
    "top": (0x36, 0x42, 0x4A),        # dark slate work surface
    "dark": (0x1F, 0x23, 0x26),       # plinth, knee space and recesses
    "handle": (0xC3, 0xC8, 0xC9),     # pulls, shafts and jaws
    "peg": (0x8F, 0x6E, 0x44),        # pegboard hardboard
    "paper": (0xD6, 0xE2, 0xEE),      # drawing sheet
    "board": (0x4F, 0x5B, 0x63),      # drawing board
    "amber": (0xE6, 0x9A, 0x1E),      # indicator lamp
    "red": (0xB5, 0x33, 0x2E),        # screwdriver handle, toolbox
    "blue": (0x2F, 0x5E, 0xA6),       # pliers grips
    "copper": (0xB8, 0x73, 0x33),     # coil of wire
}

TILT = -9.0                           # degrees: the back edge of the drawing board is raised


def fabrication_desk():
    b = g.Builder("fabrication_desk")
    # base plinth, set back so a technician can stand close
    b.box((0.03, 0.0, 0.06), (0.97, 0.07, 0.94), "dark", skip="d")
    # drawer cabinet on the left: carcass, three drawer fronts, pulls
    b.box((0.0, 0.07, 0.04), (0.36, 0.80, 0.98), "steel", skip="d")
    for k in range(3):
        y0 = 0.10 + k * 0.23
        b.box((0.025, y0, 0.02), (0.335, y0 + 0.20, 0.04), {"n": "drawer", "e": "steel", "w": "steel", "u": "steel", "d": "steel"})
        b.box((0.11, y0 + 0.13, 0.0), (0.25, y0 + 0.15, 0.02), "handle", skip="s")
    # right side panel and back panel, leaving the knee space between
    b.box((0.93, 0.07, 0.06), (1.0, 0.80, 0.96), "steel", skip="d")
    b.box((0.36, 0.07, 0.90), (0.93, 0.80, 0.96), "dark")
    # a shelf in the knee space with a small toolbox on it
    b.box((0.36, 0.34, 0.34), (0.93, 0.37, 0.90), "steel")
    b.box((0.52, 0.37, 0.52), (0.82, 0.50, 0.80), {"n": "red", "s": "red", "e": "red", "w": "red", "u": "dark"})
    b.box((0.60, 0.44, 0.50), (0.74, 0.47, 0.52), "handle", skip="s")
    # work top: slate surface, steel edge
    b.box((0.0, 0.80, 0.0), (1.0, 0.875, 1.0),
          {"u": "top", "n": "steel", "s": "steel", "e": "steel", "w": "steel", "d": "dark"})
    # pegboard at the back with a steel top rail
    b.box((0.0, 0.875, 0.90), (1.0, 1.0, 0.94), {"n": "peg", "s": "steel", "e": "steel", "w": "steel", "u": "steel", "d": "steel"})
    b.box((0.0, 0.975, 0.89), (1.0, 1.0, 0.95), "steel", skip="d")
    # tools on the pegboard: a screwdriver, pliers, a coil of wire, a small parts organiser, the lamp
    b.box((0.10, 0.93, 0.865), (0.14, 0.99, 0.90), "red", skip="s")
    b.box((0.115, 0.885, 0.875), (0.125, 0.93, 0.885), "handle", skip="s")
    b.box((0.22, 0.89, 0.875), (0.235, 0.935, 0.89), "blue", skip="s")
    b.box((0.245, 0.89, 0.875), (0.26, 0.935, 0.89), "blue", skip="s")
    b.box((0.2275, 0.935, 0.875), (0.2525, 0.99, 0.89), "handle", skip="s")
    b.box((0.40, 0.90, 0.86), (0.50, 0.98, 0.90), "copper", skip="s")
    b.box((0.62, 0.91, 0.85), (0.78, 0.99, 0.90), "steel", skip="s")
    for k in range(3):
        b.box((0.635 + k * 0.05, 0.92, 0.84), (0.675 + k * 0.05, 0.98, 0.85), "drawer", skip="s")
    b.box((0.86, 0.925, 0.86), (0.92, 0.985, 0.90), "amber", skip="s")
    # parts tray on the work top (left, toward the back): floor, four walls, two dividers
    b.box((0.04, 0.875, 0.50), (0.32, 0.885, 0.84), "dark")
    b.box((0.04, 0.885, 0.50), (0.05, 0.915, 0.84), "steel")
    b.box((0.31, 0.885, 0.50), (0.32, 0.915, 0.84), "steel")
    b.box((0.05, 0.885, 0.50), (0.31, 0.915, 0.51), "steel")
    b.box((0.05, 0.885, 0.83), (0.31, 0.915, 0.84), "steel")
    b.box((0.135, 0.885, 0.51), (0.145, 0.905, 0.83), "steel")
    b.box((0.225, 0.885, 0.51), (0.235, 0.905, 0.83), "steel")
    # the tilted drawing board: board, sheet, ruler, two back struts
    tilt = g.rot_x(TILT, (0.875, 0.08))
    b.box((0.40, 0.875, 0.08), (0.90, 0.895, 0.54), "board", xform=tilt)
    b.box((0.42, 0.895, 0.10), (0.88, 0.90, 0.52), {"u": "paper", "n": "paper", "s": "paper", "e": "paper", "w": "paper", "d": "board"},
          xform=tilt)
    b.box((0.42, 0.90, 0.10), (0.88, 0.905, 0.12), "handle", xform=tilt)
    for x0 in (0.42, 0.86):
        b.box((x0, 0.875, 0.44), (x0 + 0.04, 0.935, 0.48), "steel", skip="d")
    return b.finish(single_block=True)


# ---- Blender -------------------------------------------------------------------------------------------------------

def set_material(mat, rgb):
    mat.diffuse_color = (rgb[0] / 255.0, rgb[1] / 255.0, rgb[2] / 255.0, 1.0)
    try:
        mat.use_nodes = True
    except Exception:
        pass
    tree = mat.node_tree
    if tree is None:
        return
    bsdf = tree.nodes.get("Principled BSDF")
    if bsdf is None:
        bsdf = tree.nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.inputs["Base Color"].default_value = mat.diffuse_color
    bsdf.inputs["Roughness"].default_value = 0.8


def make_object(name, faces, mats):
    verts, vindex, polys, uvs, mat_ids = [], {}, [], [], []
    order = []
    for mat, pts, uv in faces:
        if mat not in order:
            order.append(mat)
        loop = []
        for p in pts:
            v = (round(p[0], 6), round(-p[2], 6), round(p[1], 6))   # Minecraft -> Blender (x, -z, y)
            if v not in vindex:
                vindex[v] = len(verts)
                verts.append(v)
            loop.append(vindex[v])
        polys.append(loop)
        uvs.append(uv)
        mat_ids.append(order.index(mat))
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], polys)
    for mat in order:
        mesh.materials.append(mats[mat])
    uv_layer = mesh.uv_layers.new(name="UVMap")
    for pi, poly in enumerate(mesh.polygons):
        poly.material_index = mat_ids[pi]
        poly.use_smooth = False
        for k in range(len(poly.loop_indices)):
            uv_layer.data[poly.loop_start + k].uv = uvs[pi][k]
    mesh.update()
    return bpy.data.objects.new(name, mesh)


def export_obj(obj, path):
    for o in bpy.data.objects:
        o.select_set(False)
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.wm.obj_export(filepath=path, check_existing=False, export_selected_objects=True, apply_modifiers=False,
                          export_uv=True, export_normals=True, export_materials=True, export_triangulated_mesh=False,
                          forward_axis="NEGATIVE_Z", up_axis="Y", global_scale=1.0, path_mode="STRIP")
    mtl = os.path.splitext(path)[0] + ".mtl"
    if os.path.exists(mtl):
        os.remove(mtl)
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    kept = []
    for line in lines:
        if line.startswith("mtllib"):
            kept.append("mtllib materials.mtl")
        elif line.startswith("s ") or line.startswith("# Blender"):
            continue
        else:
            kept.append(line)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("# Generated by art/models/fabrication_desk/build_fabrication_desk.py (Blender)\n" + "\n".join(kept) + "\n")


def write_mtl(path):
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        for name, rgb in MATERIALS.items():
            fh.write("newmtl %s\nKd %.6f %.6f %.6f\nmap_Kd minecraft:block/white_concrete\n\n" % (
                name, rgb[0] / 255.0, rgb[1] / 255.0, rgb[2] / 255.0))


def render_preview(obj, directory, name, azimuth, elevation, size=640):
    import math
    import mathutils
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.render.resolution_x = size
    scene.render.resolution_y = size
    scene.render.image_settings.file_format = "PNG"
    scene.display.shading.light = "STUDIO"
    scene.display.shading.color_type = "MATERIAL"
    scene.display.shading.show_shadows = False
    scene.world = scene.world or bpy.data.worlds.new("w")
    scene.world.color = (0.62, 0.66, 0.70)
    pts = [obj.matrix_world @ v.co for v in obj.data.vertices]
    lo = [min(p[i] for p in pts) for i in range(3)]
    hi = [max(p[i] for p in pts) for i in range(3)]
    centre = [(lo[i] + hi[i]) / 2.0 for i in range(3)]
    distance = max(hi[i] - lo[i] for i in range(3)) * 2.3 + 0.8
    az, el = math.radians(azimuth), math.radians(elevation)
    # azimuth 0 looks at the front (Blender -Y direction, the camera at +Y)
    cam_loc = (centre[0] + distance * math.sin(az) * math.cos(el), centre[1] + distance * math.cos(az) * math.cos(el),
               centre[2] + distance * math.sin(el))
    cam = bpy.data.objects.get("_cam")
    if cam is None:
        cam = bpy.data.objects.new("_cam", bpy.data.cameras.new("_cam"))
        scene.collection.objects.link(cam)
    cam.data.type = "PERSP"
    cam.data.lens = 50
    cam.location = cam_loc
    direction = (centre[0] - cam_loc[0], centre[1] - cam_loc[1], centre[2] - cam_loc[2])
    cam.rotation_euler = mathutils.Vector(direction).to_track_quat("-Z", "Y").to_euler()
    scene.camera = cam
    os.makedirs(directory, exist_ok=True)
    scene.render.filepath = os.path.join(directory, name + ".png")
    bpy.ops.render.render(write_still=True)


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    bpy.ops.wm.read_factory_settings(use_empty=True)
    mats = {}
    for name, rgb in MATERIALS.items():
        mat = bpy.data.materials.new(name)
        set_material(mat, rgb)
        mats[name] = mat
    faces = fabrication_desk()
    obj = make_object("fabrication_desk", faces, mats)
    collection = bpy.data.collections.new("fabrication_desk")
    bpy.context.scene.collection.children.link(collection)
    collection.objects.link(obj)
    bpy.context.preferences.filepaths.save_version = 0   # no .blend1 backup next to the file
    bpy.ops.wm.save_as_mainfile(filepath=BLEND)
    os.makedirs(OUT, exist_ok=True)
    export_obj(obj, os.path.join(OUT, "fabrication_desk.obj"))
    write_mtl(os.path.join(OUT, "materials.mtl"))
    print("FABRICATION DESK FACES", len(faces))
    if "--previews" in argv:
        directory = argv[argv.index("--previews") + 1]
        render_preview(obj, directory, "desk_front", -30, 24)
        render_preview(obj, directory, "desk_back", 150, 24)
        print("PREVIEWS", directory)


main()
