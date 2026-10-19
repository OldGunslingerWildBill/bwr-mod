"""Build the control-room furniture in Blender (run headless) and export the OBJ models.

    blender --background --factory-startup --python art/models/room_furniture/build_furniture.py -- [--previews]

What it does:
  1. makes every model's face soup with furniture_geo.py (plain Python, the geometry lives there),
  2. turns each soup into a Blender mesh with materials and UVs and saves art/models/room_furniture/room_furniture.blend,
  3. exports one OBJ per model into mod/src/main/resources/assets/bwr/models/block/furniture/ (axes: Blender -Z forward,
     Y up, which maps the scene's (x, -z, y) layout back to Minecraft's x east, y up, z south), and writes the shared
     materials.mtl (plain colours are minecraft:block/white_concrete tinted by Kd, like the panel bodies),
  4. with --previews renders Workbench PNGs under tmp/ for checking by eye.

The scene is laid out as Blender (x, y, z) = (mx, -mz, my): Minecraft north (the front, where the operator stands) is
Blender +Y.
"""
import math
import os
import sys

sys.dont_write_bytecode = True

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, HERE)
import furniture_geo as g  # noqa: E402

RES = os.path.join(REPO, "mod", "src", "main", "resources", "assets", "bwr")
OUT = os.path.join(RES, "models", "block", "furniture")
TEX = os.path.join(RES, "textures", "block", "furniture")
BLEND = os.path.join(HERE, "room_furniture.blend")
PREVIEW = os.path.join(REPO, "tmp", "furniture_preview")


def build_models():
    """name -> face list, every model of the pack."""
    m = {}
    unit = (0, 0, 0, 1, 1, 1)
    for plain, prefix in ((False, "cabinet"), (True, "cabinet_plain")):
        m[prefix + "_lower"] = g.cabinet((0, 0, 0, 1, 1, 1), back_door=not plain).finish(single_block=True)
        m[prefix + "_upper"] = g.cabinet((0, 1, 0, 1, 2, 1), back_door=not plain).finish(single_block=True)
        m[prefix + "_item"] = g.cabinet(back_door=not plain).finish(recentre=True)
    m["desk"] = g.desk_straight().finish(single_block=True)
    m["desk_flat"] = g.monitor_flat()
    m["desk_crt"] = g.monitor_crt()
    m["desk_round"] = g.desk_round()
    m["desk_round_mirrored"] = g.desk_round(mirrored=True)
    m["desk_round_flat"] = g.monitor_flat(cx=0.34)
    m["desk_round_crt"] = g.monitor_crt(cx=0.34)
    m["desk_round_flat_mirrored"] = g.mirror_x(g.monitor_flat(cx=0.34))
    m["desk_round_crt_mirrored"] = g.mirror_x(g.monitor_crt(cx=0.34))
    m["desk_corner"] = g.desk_corner().finish(single_block=True)
    m["desk_corner_flat"] = g.monitor_flat(cx=0.60, cz=0.60, turn=45.0)
    m["desk_corner_crt"] = g.monitor_crt(cx=0.60, cz=0.60, turn=45.0)
    m["supervisor_drawers"] = g.supervisor((0, 0, 0, 1, 1, 1)).finish(single_block=True)
    m["supervisor_knee"] = g.supervisor((1, 0, 0, 2, 1, 1)).finish(single_block=True)
    m["supervisor_item"] = g.supervisor().finish(recentre=True)
    m["mimic_band_cream"] = g.mimic_band("cream")
    m["mimic_band_black"] = g.mimic_band("black")
    for width in (2, 3):
        for col in range(width):
            for row in range(2):
                clip = (col, row, 0, col + 1, row + 1, 1)
                m["wall_display_%dx2_%d%d" % (width, col, row)] = g.wall_display(width, 2, clip).finish(single_block=True)
        m["wall_display_%dx2_item" % width] = g.wall_display(width, 2).finish(recentre=True)
    return m


def set_material(mat, rgb, texture):
    """Node material for Workbench and for the exporter; the OBJ's mtl is rewritten afterwards."""
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
    if texture:
        node = tree.nodes.new("ShaderNodeTexImage")
        node.image = bpy.data.images.load(os.path.join(TEX, texture + ".png"), check_existing=True)
        node.interpolation = "Closest"
        tree.links.new(node.outputs["Color"], bsdf.inputs["Base Color"])


def make_object(name, faces, mats):
    verts, vindex, polys, uvs, mat_ids = [], {}, [], [], []
    order = []
    for mat, pts, uv in faces:
        if mat not in order:
            order.append(mat)
        loop = []
        for p in pts:
            b = (round(p[0], 6), round(-p[2], 6), round(p[1], 6))   # Minecraft -> Blender (x, -z, y)
            if b not in vindex:
                vindex[b] = len(verts)
                verts.append(b)
            loop.append(vindex[b])
        polys.append(loop)
        uvs.append(uv)
        mat_ids.append(order.index(mat))
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], polys)
    for mat in order:
        mesh.materials.append(mats[mat])
    uv_layer = mesh.uv_layers.new(name="UVMap")
    li = 0
    for pi, poly in enumerate(mesh.polygons):
        poly.material_index = mat_ids[pi]
        poly.use_smooth = False
        for k in range(len(poly.loop_indices)):
            uv_layer.data[poly.loop_start + k].uv = uvs[pi][k]
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    return obj


def export_obj(obj, path):
    for o in bpy.data.objects:
        o.select_set(False)
    saved = obj.location.copy()
    obj.location = (0, 0, 0)
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.wm.obj_export(filepath=path, check_existing=False, export_selected_objects=True, apply_modifiers=False,
                          export_uv=True, export_normals=True, export_materials=True, export_triangulated_mesh=False,
                          forward_axis="NEGATIVE_Z", up_axis="Y", global_scale=1.0, path_mode="STRIP")
    obj.location = saved
    obj.select_set(False)
    # Blender's own mtl is replaced by the shared one; smoothing lines are dropped (everything is flat shaded).
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
        fh.write("# Generated by art/models/room_furniture/build_furniture.py (Blender)\n" + "\n".join(kept) + "\n")


def write_mtl(path):
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        for name, (rgb, tex) in g.MATERIALS.items():
            fh.write("newmtl %s\nKd %.6f %.6f %.6f\nmap_Kd %s\n\n" % (
                name, rgb[0] / 255.0, rgb[1] / 255.0, rgb[2] / 255.0,
                ("bwr:block/furniture/" + tex) if tex else "minecraft:block/white_concrete"))


# ---- previews ------------------------------------------------------------------------------------------------------

def render_preview(obj, name, azimuth, elevation=24.0, distance=None, size=640):
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.render.resolution_x = size
    scene.render.resolution_y = size
    scene.render.image_settings.file_format = "PNG"
    scene.display.shading.light = "STUDIO"
    scene.display.shading.color_type = "TEXTURE"
    scene.display.shading.show_shadows = False
    scene.display.shading.show_cavity = False
    scene.world = scene.world or bpy.data.worlds.new("w")
    scene.world.color = (0.62, 0.66, 0.70)
    # bounding box in scene space
    pts = [obj.matrix_world @ v.co for v in obj.data.vertices]
    lo = [min(p[i] for p in pts) for i in range(3)]
    hi = [max(p[i] for p in pts) for i in range(3)]
    centre = [(lo[i] + hi[i]) / 2.0 for i in range(3)]
    radius = max(hi[i] - lo[i] for i in range(3))
    distance = distance or radius * 2.3 + 0.8
    az, el = math.radians(azimuth), math.radians(elevation)
    # azimuth 0 = looking at the front (Blender -Y direction, camera at +Y), positive moves toward +X (Minecraft east)
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
    import mathutils
    cam.rotation_euler = mathutils.Vector(direction).to_track_quat("-Z", "Y").to_euler()
    scene.camera = cam
    os.makedirs(PREVIEW, exist_ok=True)
    scene.render.filepath = os.path.join(PREVIEW, name + ".png")
    for o in bpy.data.objects:
        if o.name != "_cam":
            o.hide_render = (o is not obj)
    bpy.ops.render.render(write_still=True)


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    bpy.ops.wm.read_factory_settings(use_empty=True)
    mats = {}
    for name, (rgb, tex) in g.MATERIALS.items():
        mat = bpy.data.materials.new(name)
        set_material(mat, rgb, tex)
        mats[name] = mat
    models = build_models()
    os.makedirs(OUT, exist_ok=True)
    collection = bpy.data.collections.new("furniture")
    bpy.context.scene.collection.children.link(collection)
    objects = {}
    column = 0.0
    counts = {}
    for name, faces in models.items():
        obj = make_object(name, faces, mats)
        obj.location = (column, 0, 0)
        column += 2.6
        collection.objects.link(obj)
        objects[name] = obj
        counts[name] = len(faces)
    bpy.ops.wm.save_as_mainfile(filepath=BLEND)
    for name, obj in objects.items():
        export_obj(obj, os.path.join(OUT, name + ".obj"))
    write_mtl(os.path.join(OUT, "materials.mtl"))
    print("FURNITURE MODELS", len(objects))
    for name in sorted(counts):
        print("FACES %-32s %d" % (name, counts[name]))
    if "--previews" in argv:
        combos = {
            "prev_desk_flat": ["desk", "desk_flat"], "prev_desk_crt": ["desk", "desk_crt"],
            "prev_round_crt": ["desk_round", "desk_round_crt"], "prev_round_flat_m": ["desk_round_mirrored", "desk_round_flat_mirrored"],
            "prev_corner_crt": ["desk_corner", "desk_corner_crt"], "prev_corner_flat": ["desk_corner", "desk_corner_flat"],
        }
        for cname, parts in combos.items():
            obj = make_object(cname, [f for part in parts for f in models[part]], mats)
            obj.location = (column, 0, 0)
            column += 2.6
            collection.objects.link(obj)
            objects[cname] = obj
        views = {
            "cabinet_item": (-32, 20), "cabinet_plain_item": (-32, 20), "supervisor_item": (28, 28),
            "mimic_band_cream": (15, 15), "mimic_band_black": (15, 15),
            "wall_display_2x2_item": (20, 14), "wall_display_3x2_item": (20, 14),
            "prev_desk_flat": (25, 28), "prev_desk_crt": (-25, 28), "prev_round_crt": (25, 28),
            "prev_round_flat_m": (-25, 28), "prev_corner_crt": (25, 28), "prev_corner_flat": (-25, 28),
        }
        for name, (az, el) in views.items():
            render_preview(objects[name], name, az, el)
        # the rear of the standard cabinet
        render_preview(objects["cabinet_item"], "cabinet_item_back", 180 - 32, 20)
        print("PREVIEWS", PREVIEW)


main()
