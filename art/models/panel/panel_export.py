"""Shared Blender-side exporter for control-panel component models.

Every panel component model (switches, meters, pushbuttons, lights) is built by
a generator script under art/models/panel/<family>/ and exported through this
module, so they all reach the game in one format. Run generators headless:

    blender --background --factory-startup --python art/models/panel/<family>/build.py

AUTHORING CONVENTIONS (Blender scene)
  * Units are INCHES, at real hardware size from the cited drawings.
  * The panel face is the plane Z = 0. +Z points out of the panel toward the
    operator. +X is right, +Y is UP as seen by the operator.
  * Each model sits inside a nominal footprint rectangle on the face: origin of
    the rectangle's TOP-LEFT corner at (0, 0) in X/Y, extending to
    (footprint_w, -footprint_h). Nothing may extend outside it (moving parts at
    any position included) or behind Z = -0.05.
  * Colour comes from each material's base colour (no textures: the game
    renders flat-coloured quads). Keep the control-room palette.
  * One OBJECT per part. Custom properties on the object control export:
        bwr_part   : part name ("body" if absent). Objects with the same part
                     name are merged.
        bwr_role   : "paint" (default), "lamp" (emissive when lit, dimmed when
                     off), "flag" (recoloured by state, e.g. target flag),
                     "needle" (pointer), "glass" (window, drawn last).
        bwr_pivot  : (x, y, z) pivot in inches, for parts that rotate.
        bwr_axis   : (x, y, z) rotation axis, default (0, 0, 1).
        bwr_tint   : optional state name the renderer maps to a colour
                     ("red", "green", "white", "amber", "blue").
        bwr_travel : for parts that SLIDE instead of rotate (pushbutton caps):
                     travel in inches along bwr_axis when fully operated.
  * Engraved/printed legends are NOT modelled as geometry: the game draws them
    with its font so players can edit them. Mark where they go with EMPTY
    objects carrying:
        bwr_label  : label id ("caption", "left", "right", "top", "pos1".."pos6",
                     "scale", "units", "legend", ...)
        bwr_text_h : text cap height in inches
        bwr_align  : "left" | "center" | "right" (default center)
     The empty's location is the text anchor; its Z rotation is the text angle.
  * Budget: at most ~800 faces per model (all parts). Prefer quads. Bevel
    edges that face the operator; skip anything hidden behind the panel.

EXPORT FORMAT (JSON, written to mod/src/main/resources/assets/bwr/models/panel/)
  {
    "format": 1, "name": "<model id>", "units": "inch",
    "footprint": {"w": W, "h": H},
    "source": "<citation of the drawing used>",
    "parts": [
      {"name": "body", "role": "paint", "tint": null,
       "pivot": [x, y, z] | null, "axis": [x, y, z] | null, "travel": t | null,
       "faces": [{"v": [[x, y, z], ...3 or 4], "n": [nx, ny, nz], "c": "RRGGBB"}]}
    ],
    "labels": [{"id": "left", "pos": [x, y, z], "h": cap_height, "align": "center", "rot": degrees}]
  }
  Exported coordinates are converted to the game's component space convention:
  x right, y DOWN from the footprint's top-left corner, z out of the face,
  still in inches. The game loader scales inches to grid cells uniformly so
  the footprint fills the component's cell rectangle.
"""
import json
import math
import os

import bpy
import bmesh

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT_DIR = os.path.join(REPO, "mod", "src", "main", "resources", "assets", "bwr", "models", "panel")
VALID_ROLES = {"paint", "lamp", "flag", "needle", "glass"}


def _hex(rgb):
    return "%02X%02X%02X" % tuple(max(0, min(255, round(c * 255))) for c in rgb[:3])


def _linear_to_srgb(c):
    return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055


def _material_colour(mat):
    if mat is None:
        return (0.5, 0.5, 0.5)
    rgb = None
    if mat.use_nodes and mat.node_tree:
        for node in mat.node_tree.nodes:
            if node.type == "BSDF_PRINCIPLED":
                rgb = tuple(node.inputs["Base Color"].default_value[:3])
                break
    if rgb is None:
        rgb = tuple(mat.diffuse_color[:3])
    return tuple(_linear_to_srgb(c) for c in rgb)


def material(name, srgb_hex, roughness=0.5, metallic=0.0, emission=0.0):
    """Creates (or reuses) a material from an sRGB hex colour like 'A7BCAE'."""
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    r, g, b = (int(srgb_hex[i:i + 2], 16) / 255 for i in (0, 2, 4))
    lin = tuple(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in (r, g, b))
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = (*lin, 1.0)
    bsdf.inputs["Roughness"].default_value = roughness
    bsdf.inputs["Metallic"].default_value = metallic
    if emission > 0:
        bsdf.inputs["Emission Color"].default_value = (*lin, 1.0)
        bsdf.inputs["Emission Strength"].default_value = emission
    mat.diffuse_color = (*lin, 1.0)
    return mat


def label(label_id, x, y, z, text_h, align="center", rot_deg=0.0):
    """Adds a text anchor empty (legends are drawn by the game, not modelled)."""
    e = bpy.data.objects.new(f"label_{label_id}", None)
    e.location = (x, y, z)
    e.rotation_euler = (0, 0, math.radians(rot_deg))
    e["bwr_label"] = label_id
    e["bwr_text_h"] = text_h
    e["bwr_align"] = align
    bpy.context.scene.collection.objects.link(e)
    return e


def tag(obj, part="body", role="paint", pivot=None, axis=None, tint=None, travel=None):
    """Marks an object for export."""
    assert role in VALID_ROLES, role
    if travel is not None:
        obj["bwr_travel"] = travel
    obj["bwr_part"] = part
    obj["bwr_role"] = role
    if pivot is not None:
        obj["bwr_pivot"] = list(pivot)
    if axis is not None:
        obj["bwr_axis"] = list(axis)
    if tint is not None:
        obj["bwr_tint"] = tint
    return obj


def export(name, footprint_w, footprint_h, source, objects=None, max_faces=800):
    """Exports tagged mesh objects (default: all visible meshes) to <name>.json.

    Returns the output path. Raises if the budget or footprint is exceeded.
    """
    objects = objects or [o for o in bpy.context.scene.objects if o.type == "MESH" and o.visible_get()]
    deps = bpy.context.evaluated_depsgraph_get()
    parts = {}
    total = 0
    eps = 1e-4
    for obj in objects:
        ev = obj.evaluated_get(deps)
        mesh = ev.to_mesh()
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bm.transform(obj.matrix_world)
        bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 4])
        bm.normal_update()
        pname = obj.get("bwr_part", "body")
        role = obj.get("bwr_role", "paint")
        part = parts.setdefault(pname, {
            "name": pname, "role": role, "tint": obj.get("bwr_tint"),
            "pivot": None, "axis": None, "travel": None, "faces": []})
        if "bwr_travel" in obj:
            part["travel"] = round(float(obj["bwr_travel"]), 5)
            ax = obj.get("bwr_axis", [0, 0, -1])
            part["axis"] = [round(ax[0], 5), round(-ax[1], 5), round(ax[2], 5)]
        if "bwr_pivot" in obj:
            px, py, pz = obj["bwr_pivot"]
            part["pivot"] = [round(px, 5), round(-py, 5), round(pz, 5)]
            ax = obj.get("bwr_axis", [0, 0, 1])
            part["axis"] = [round(ax[0], 5), round(-ax[1], 5), round(ax[2], 5)]
        for f in bm.faces:
            mat = obj.material_slots[f.material_index].material if obj.material_slots else None
            col = _hex(_material_colour(mat))
            verts = []
            for v in f.verts:
                x, y, z = v.co
                if x < -eps or x > footprint_w + eps or y > eps or y < -footprint_h - eps or z < -0.05 - eps:
                    raise ValueError(f"{name}: {obj.name} vertex {tuple(round(c, 3) for c in v.co)} "
                                     f"outside footprint {footprint_w} x {footprint_h}")
                verts.append([round(x, 5), round(-y, 5), round(z, 5)])
            n = f.normal
            part["faces"].append({"v": verts, "n": [round(n.x, 4), round(-n.y, 4), round(n.z, 4)], "c": col})
            total += 1
        bm.free()
        ev.to_mesh_clear()
    if total > max_faces:
        raise ValueError(f"{name}: {total} faces exceeds budget {max_faces}")
    labels = []
    for e in bpy.context.scene.objects:
        if e.type == "EMPTY" and "bwr_label" in e:
            x, y, z = e.location
            labels.append({"id": e["bwr_label"], "pos": [round(x, 5), round(-y, 5), round(z, 5)],
                           "h": round(float(e["bwr_text_h"]), 5), "align": e.get("bwr_align", "center"),
                           "rot": round(-math.degrees(e.rotation_euler[2]), 3)})
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, f"{name}.json")
    doc = {"format": 1, "name": name, "units": "inch",
           "footprint": {"w": footprint_w, "h": footprint_h},
           "source": source, "parts": list(parts.values()), "labels": labels}
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, separators=(",", ":"))
    print(f"exported {name}: {total} faces, {len(parts)} parts -> {path}")
    return path


def clear_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.context.scene.unit_settings.system = "IMPERIAL"
    bpy.context.scene.unit_settings.scale_length = 0.0254


def preview(path, footprint_w, footprint_h, views=("front", "three_quarter", "side"), res=640):
    """Renders orthographic/perspective previews of the current scene to <path>_<view>.png."""
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE_NEXT" if "BLENDER_EEVEE_NEXT" in [e.identifier for e in
        bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items] else "BLENDER_EEVEE"
    scene.render.resolution_x = scene.render.resolution_y = res
    if scene.world is None:
        scene.world = bpy.data.worlds.new("w")
    scene.world.color = (0.18, 0.2, 0.19)
    # Blender 4.2+/5.x worlds render through their node tree, which ignores world.color
    # (its default 0.05 grey Background leaves previews nearly unlit): set the node too.
    bg = scene.world.node_tree.nodes.get("Background") if scene.world.node_tree else None
    if bg is not None:
        bg.inputs["Color"].default_value = (0.18, 0.2, 0.19, 1.0)
    # Show palette colours as authored (the default AgX view transform desaturates them).
    scene.view_settings.view_transform = "Standard"
    cx, cy = footprint_w / 2, -footprint_h / 2
    size = max(footprint_w, footprint_h) * 1.25
    # A panel-face backdrop in benchboard paint so the model is seen in context.
    bpy.ops.mesh.primitive_plane_add(size=size * 2.5, location=(cx, cy, -0.06))
    bd = bpy.context.active_object
    bd.data.materials.append(material("backdrop", "A7BCAE", 0.8))
    sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
    sun.data.energy = 3.5
    # Light from the upper left, as from control-room ceiling lights. (+Y is UP on the
    # face here, so a positive X tilt would light the model from below.)
    sun.rotation_euler = (math.radians(-40), math.radians(-25), math.radians(20))
    scene.collection.objects.link(sun)
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    scene.collection.objects.link(cam)
    scene.camera = cam
    outs = []
    for view in views:
        if view == "front":
            cam.data.type = "ORTHO"
            cam.data.ortho_scale = size
            cam.location = (cx, cy, 20)
            cam.rotation_euler = (0, 0, 0)
        elif view == "side":
            cam.data.type = "ORTHO"
            cam.data.ortho_scale = size
            cam.location = (cx + 20, cy, size * 0.3)
            cam.rotation_euler = (0, math.radians(90), 0)
        else:
            cam.data.type = "PERSP"
            cam.data.lens = 50
            dist = size * 2.2
            cam.location = (cx + dist * 0.55, cy - dist * 0.45, dist * 0.75)
            direction = (cx - cam.location[0], cy - cam.location[1], 0 - cam.location[2])
            import mathutils
            cam.rotation_euler = mathutils.Vector(direction).to_track_quat("-Z", "Y").to_euler()
        out = f"{path}_{view}.png"
        scene.render.filepath = out
        bpy.ops.render.render(write_still=True)
        outs.append(out)
    # Remove everything this call added, so repeated previews of one scene (e.g. a
    # needle at min / mid / max) do not stack suns and over-expose later renders.
    bpy.data.objects.remove(bd)
    light, camera = sun.data, cam.data
    bpy.data.objects.remove(sun)
    bpy.data.objects.remove(cam)
    bpy.data.lights.remove(light)
    bpy.data.cameras.remove(camera)
    return outs
