# Oz Projection export: renders each selected face of your model, seen straight on, and writes
# oz-surfaces.json so the Oz Projection PC app can import the faces as surfaces to warp. It also
# unfolds the model into papercraft pieces (glue tabs, fold lines, numbered edges) and saves them
# as PDFs at true size: big sheets for a print shop, and the same sheets split across home
# printer pages that tape together.
#
# Install (Blender 3.6 or newer): Edit > Preferences > Add-ons, then "Install..." (or on 4.2 and
# newer the drop-down at the top right > "Install from Disk...") and pick this file.
# Use: in Edit Mode select the faces to project on, then in the 3D view's sidebar (N) open the
# "Oz" tab and press "Make it" (papercraft and surfaces), or export either one on its own.
# Scripts (and Claude through Blender MCP) can run the same with bpy.ops.oz.make_it(),
# bpy.ops.oz.export_papercraft() and bpy.ops.oz.export_surfaces().

bl_info = {
    "name": "Oz Projection export",
    "author": "Oz Projection",
    "version": (1, 1, 0),
    "blender": (3, 6, 0),
    "location": "3D View > Sidebar > Oz",
    "description": "Render faces straight on for projection mapping with Oz Projection, and print the model as papercraft",
    "category": "Import-Export",
}

import json
import os
import re

import bpy
import heapq
import math

from bpy.props import BoolProperty, EnumProperty, FloatProperty, IntProperty, PointerProperty, StringProperty
from mathutils import Matrix, Vector

FORMAT = "oz-surfaces"
FILE_NAME = "oz-surfaces.json"

# Paper sizes in millimetres, portrait.
PAPER_SIZES = {
    "A0": (841.0, 1189.0),
    "A1": (594.0, 841.0),
    "A2": (420.0, 594.0),
    "A3": (297.0, 420.0),
    "A4": (210.0, 297.0),
    "LETTER": (215.9, 279.4),
    "TABLOID": (279.4, 431.8),
}
SHEET_CHOICES = [("A0", "A0"), ("A1", "A1"), ("A2", "A2"), ("A3", "A3"), ("A4", "A4"), ("TABLOID", "Tabloid"), ("LETTER", "Letter")]


def folder_property():
    options = dict(
        name="Folder",
        description="Where the renders and oz-surfaces.json are saved (// means next to the .blend file)",
        subtype="DIR_PATH",
        default="//oz_export/",
    )
    # Newer Blender asks paths to declare that "//" (relative to the .blend) is allowed.
    try:
        return StringProperty(options={"PATH_SUPPORTS_BLEND_RELATIVE"}, **options)
    except TypeError:
        return StringProperty(**options)


class OzExportSettings(bpy.types.PropertyGroup):
    output_dir: folder_property()
    max_size: IntProperty(
        name="Size",
        description="Longest side of each face's render, in pixels",
        default=1280,
        min=64,
        max=4096,
    )
    depth: FloatProperty(
        name="Depth",
        description="How far in front of each face to capture, for things that float just above it",
        default=0.05,
        min=0.0,
        unit="LENGTH",
    )
    paper_scale: FloatProperty(
        name="Scale",
        description="Size on paper compared with the model: 1 prints it at its real size (1 m in Blender is 1 m of paper)",
        default=1.0,
        min=0.001,
        max=1000.0,
    )
    sheet: EnumProperty(
        name="Sheet",
        description="The big paper the pieces are laid out on, for a print shop or a large-format printer",
        items=[(key, label, "") for key, label in SHEET_CHOICES],
        default="A1",
    )
    home_page: EnumProperty(
        name="Home printer",
        description="Each sheet is also split across pages of this size, with overlaps to tape together",
        items=[("A4", "A4", ""), ("LETTER", "Letter", "")],
        default="A4",
    )
    tab_size: FloatProperty(
        name="Glue tabs (mm)",
        description="How wide the glue tabs are, in millimetres on the paper",
        default=8.0,
        min=2.0,
        max=40.0,
    )
    print_names: BoolProperty(
        name="Surface names",
        description="Print each projection surface's name on its face, as the Oz app shows it",
        default=True,
    )


def selected_faces(context):
    """(object, polygon) for every selected face of the selected or edited mesh objects."""
    objects = {o for o in context.selected_objects if o.type == "MESH"}
    if context.edit_object and context.edit_object.type == "MESH":
        objects.add(context.edit_object)
    faces = []
    for obj in sorted(objects, key=lambda o: o.name):
        for poly in obj.data.polygons:
            if poly.select:
                faces.append((obj, poly))
    return faces


def face_names(faces):
    """A readable, unique name per face: the object's name, plus the face's material when the
    object has several faces (so naming a material "Door" names the surface "Box Door")."""
    per_object = {}
    for obj, _ in faces:
        per_object[obj.name] = per_object.get(obj.name, 0) + 1
    names = []
    for obj, poly in faces:
        if per_object[obj.name] == 1:
            names.append(obj.name)
            continue
        slots = obj.material_slots
        material = slots[poly.material_index].material if poly.material_index < len(slots) else None
        names.append(f"{obj.name} {material.name}" if material else f"{obj.name} {poly.index}")
    # Faces sharing a material get numbered.
    seen = {}
    for i, name in enumerate(names):
        if names.count(name) > 1:
            seen[name] = seen.get(name, 0) + 1
            names[i] = f"{name} {seen[name]}"
    return names


def scene_is_animated(scene):
    """Whether anything in the scene has keyframes or drivers, so its frames differ."""
    def moves(owner):
        data = getattr(owner, "animation_data", None)
        return bool(data and (data.action or len(data.drivers)))

    def tree_moves(owner):
        return bool(getattr(owner, "node_tree", None) and moves(owner.node_tree))

    if moves(scene) or (scene.world and (moves(scene.world) or tree_moves(scene.world))):
        return True
    for obj in scene.objects:
        if moves(obj) or (obj.data and moves(obj.data)):
            return True
        if getattr(obj.data, "shape_keys", None) and moves(obj.data.shape_keys):
            return True
        for slot in obj.material_slots:
            if slot.material and (moves(slot.material) or tree_moves(slot.material)):
                return True
    return False


def set_media_type(image_settings, media_type):
    # Blender 5 picks image or video output first; older versions list FFMPEG among image formats.
    if hasattr(image_settings, "media_type"):
        image_settings.media_type = media_type


def safe_file_name(name):
    return re.sub(r"[^A-Za-z0-9._ -]+", "_", name).strip() or "surface"


def face_frame(obj, poly):
    """A camera basis looking straight at the face, and the face's corners in that basis."""
    world = obj.matrix_world
    points = [world @ obj.data.vertices[i].co for i in poly.vertices]
    normal = (world.to_3x3().inverted().transposed() @ poly.normal).normalized()
    centre = sum(points, Vector()) / len(points)
    up_hint = Vector((0.0, 0.0, 1.0))
    if abs(normal.dot(up_hint)) > 0.99:
        up_hint = Vector((0.0, 1.0, 0.0))
    y_axis = (up_hint - normal * normal.dot(up_hint)).normalized()
    x_axis = y_axis.cross(normal).normalized()
    flat = [((p - centre).dot(x_axis), (p - centre).dot(y_axis)) for p in points]
    return centre, x_axis, y_axis, normal, flat


class OZ_OT_export_surfaces(bpy.types.Operator):
    bl_idname = "oz.export_surfaces"
    bl_label = "Export surfaces"
    bl_description = "Render each selected face straight on and write oz-surfaces.json"

    def execute(self, context):
        scene = context.scene
        settings = scene.oz_export
        was_editing = context.mode == "EDIT_MESH"
        if was_editing:
            # Face selection is only written back to the mesh when leaving Edit Mode.
            bpy.ops.object.mode_set(mode="OBJECT")
        try:
            return self.export(context, scene, settings)
        finally:
            if was_editing:
                bpy.ops.object.mode_set(mode="EDIT")

    def export(self, context, scene, settings):
        faces = selected_faces(context)
        if not faces:
            self.report({"ERROR"}, "Select the faces to project on (in Edit Mode) first")
            return {"CANCELLED"}

        folder = export_folder(self, settings)
        if folder is None:
            return {"CANCELLED"}

        fps = scene.render.fps / scene.render.fps_base
        frames = scene.frame_end - scene.frame_start + 1
        # A new scene spans 250 frames even with nothing moving; rendering all of them for every
        # face kept Blender busy for ages, so only a scene that really moves becomes videos.
        animated = frames > 1 and scene_is_animated(scene)
        duration = frames / fps if animated else 10.0

        names = face_names(faces)

        saved = self.save_render_settings(scene)
        camera_data = bpy.data.cameras.new("Oz export camera")
        camera = bpy.data.objects.new("Oz export camera", camera_data)
        scene.collection.objects.link(camera)
        surfaces = []
        used_names = set()
        wm = context.window_manager
        wm.progress_begin(0, len(faces))
        try:
            for index, (obj, poly) in enumerate(faces):
                wm.progress_update(index)
                name = names[index]
                file_base = safe_file_name(name)
                while file_base in used_names:
                    file_base += "_"
                used_names.add(file_base)
                surface = self.render_face(scene, settings, camera, obj, poly, name, folder, file_base, animated)
                if surface:
                    surfaces.append(surface)
        finally:
            wm.progress_end()
            bpy.data.objects.remove(camera)
            bpy.data.cameras.remove(camera_data)
            self.restore_render_settings(scene, saved)

        if not surfaces:
            self.report({"ERROR"}, "The selected faces are too small to render")
            return {"CANCELLED"}

        export = {
            "format": FORMAT,
            "version": 1,
            "fps": fps,
            "durationSeconds": duration,
            "surfaces": surfaces,
        }
        with open(os.path.join(folder, FILE_NAME), "w", encoding="utf-8") as f:
            json.dump(export, f, indent=2)
        self.report({"INFO"}, f"Exported {len(surfaces)} surfaces to {folder}")
        return {"FINISHED"}

    def render_face(self, scene, settings, camera, obj, poly, name, folder, file_base, animated):
        centre, x_axis, y_axis, normal, flat = face_frame(obj, poly)
        xs = [p[0] for p in flat]
        ys = [p[1] for p in flat]
        width = max(xs) - min(xs)
        height = max(ys) - min(ys)
        if width <= 1e-6 or height <= 1e-6:
            return None

        # Render size at the face's proportions, even numbers for the video encoder.
        scale = settings.max_size / max(width, height)
        res_x = max(2, int(round(width * scale / 2)) * 2)
        res_y = max(2, int(round(height * scale / 2)) * 2)

        # Orthographic camera centred on the face, looking straight at its front.
        mid_x = (max(xs) + min(xs)) / 2
        mid_y = (max(ys) + min(ys)) / 2
        target = centre + x_axis * mid_x + y_axis * mid_y
        distance = settings.depth + 0.001
        rotation = Matrix((x_axis, y_axis, normal)).transposed().to_4x4()
        camera.matrix_world = Matrix.Translation(target + normal * distance) @ rotation
        camera.data.type = "ORTHO"
        camera.data.sensor_fit = "AUTO"
        camera.data.ortho_scale = max(width, height)
        camera.data.clip_start = 0.0005
        # Just past the face: what is behind it never shows.
        camera.data.clip_end = distance + 0.001 + 0.01 * max(width, height)
        scene.camera = camera

        render = scene.render
        render.resolution_x = res_x
        render.resolution_y = res_y
        render.resolution_percentage = 100
        render.pixel_aspect_x = 1.0
        render.pixel_aspect_y = 1.0

        # Corners in the rendered image, 0..1 from the top left. The image spans ortho_scale on its
        # longer side, so measure against the image's real extent rather than the face's.
        longest = max(res_x, res_y)
        image_w = camera.data.ortho_scale * res_x / longest
        image_h = camera.data.ortho_scale * res_y / longest
        points = [[round((x - mid_x) / image_w + 0.5, 6), round(0.5 - (y - mid_y) / image_h, 6)] for x, y in flat]

        if animated:
            set_media_type(render.image_settings, "VIDEO")
            render.image_settings.file_format = "FFMPEG"
            render.ffmpeg.format = "MPEG4"
            render.ffmpeg.codec = "H264"
            render.ffmpeg.constant_rate_factor = "HIGH"
            render.ffmpeg.ffmpeg_preset = "GOOD"
            render.ffmpeg.audio_codec = "NONE"
            media = file_base + ".mp4"
            render.filepath = os.path.join(folder, file_base)
            render.use_file_extension = True
            # Blender adds the frame range to movie names; find out what it will write and rename it.
            written = scene.render.frame_path(frame=scene.frame_start)
            bpy.ops.render.render(animation=True)
            target_path = os.path.join(folder, media)
            if os.path.abspath(written) != os.path.abspath(target_path):
                if os.path.exists(target_path):
                    os.remove(target_path)
                os.replace(written, target_path)
        else:
            set_media_type(render.image_settings, "IMAGE")
            render.image_settings.file_format = "PNG"
            render.image_settings.color_mode = "RGB"
            media = file_base + ".png"
            render.filepath = os.path.join(folder, media)
            bpy.ops.render.render(write_still=True)

        return {"name": name, "media": media, "width": res_x, "height": res_y, "points": points}

    @staticmethod
    def save_render_settings(scene):
        r = scene.render
        return {
            "camera": scene.camera,
            "resolution": (r.resolution_x, r.resolution_y, r.resolution_percentage),
            "pixel_aspect": (r.pixel_aspect_x, r.pixel_aspect_y),
            "filepath": r.filepath,
            "use_file_extension": r.use_file_extension,
            "media_type": getattr(r.image_settings, "media_type", None),
            "file_format": r.image_settings.file_format,
            "color_mode": r.image_settings.color_mode,
            "ffmpeg": (r.ffmpeg.format, r.ffmpeg.codec, r.ffmpeg.constant_rate_factor, r.ffmpeg.ffmpeg_preset, r.ffmpeg.audio_codec),
        }

    @staticmethod
    def restore_render_settings(scene, saved):
        r = scene.render
        scene.camera = saved["camera"]
        r.resolution_x, r.resolution_y, r.resolution_percentage = saved["resolution"]
        r.pixel_aspect_x, r.pixel_aspect_y = saved["pixel_aspect"]
        r.filepath = saved["filepath"]
        r.use_file_extension = saved["use_file_extension"]
        if saved["media_type"]:
            set_media_type(r.image_settings, saved["media_type"])
        r.image_settings.file_format = saved["file_format"]
        if saved["file_format"] == "FFMPEG":
            (r.ffmpeg.format, r.ffmpeg.codec, r.ffmpeg.constant_rate_factor, r.ffmpeg.ffmpeg_preset, r.ffmpeg.audio_codec) = saved["ffmpeg"]
        try:
            r.image_settings.color_mode = saved["color_mode"]
        except TypeError:
            pass


# ---------------------------------------------------------------------------------------------
# Papercraft: unfolds the model into flat pieces with glue tabs and fold lines, then writes them at
# true size as a PDF of big sheets and as the same sheets split across home printer pages.

MM_TO_PT = 72.0 / 25.4
EPS_MM = 0.01
FLAT_DEGREES = 1.0
SHEET_MARGIN_MM = 12.0
PIECE_GAP_MM = 4.0
PAGE_MARGIN_MM = 8.0
PAGE_OVERLAP_MM = 12.0
NUMBER_MM = 3.0
NAME_MM = 4.0
# The app maps up to this many surfaces, so "Make it" only exports every face of small models.
MAX_APP_SURFACES = 8


def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1])


def _cross(a, b):
    return a[0] * b[1] - a[1] * b[0]


def _bbox(points):
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    return (min(xs), min(ys), max(xs), max(ys))


def _near(a, b):
    return abs(a[0] - b[0]) < 0.05 and abs(a[1] - b[1]) < 0.05


def _segments_cross(a, b, c, d):
    d1 = _cross(_sub(b, a), _sub(c, a))
    d2 = _cross(_sub(b, a), _sub(d, a))
    d3 = _cross(_sub(d, c), _sub(a, c))
    d4 = _cross(_sub(d, c), _sub(b, c))
    return d1 * d2 < 0 and d3 * d4 < 0


def _inside(p, poly):
    inside = False
    j = len(poly) - 1
    for i in range(len(poly)):
        (xi, yi), (xj, yj) = poly[i], poly[j]
        if (yi > p[1]) != (yj > p[1]) and p[0] < (xj - xi) * (p[1] - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def _shrink(poly, amount):
    """The polygon pulled a little towards its middle, so shapes that only touch don't overlap."""
    cx = sum(p[0] for p in poly) / len(poly)
    cy = sum(p[1] for p in poly) / len(poly)
    out = []
    for x, y in poly:
        dx, dy = cx - x, cy - y
        d = math.hypot(dx, dy)
        out.append((x + dx / d * amount, y + dy / d * amount) if d > amount else (cx, cy))
    return out


def _overlap(p, q):
    a, b = _bbox(p), _bbox(q)
    if a[2] <= b[0] + EPS_MM or b[2] <= a[0] + EPS_MM or a[3] <= b[1] + EPS_MM or b[3] <= a[1] + EPS_MM:
        return False
    ps, qs = _shrink(p, EPS_MM * 2), _shrink(q, EPS_MM * 2)
    for i in range(len(ps)):
        for j in range(len(qs)):
            if _segments_cross(ps[i - 1], ps[i], qs[j - 1], qs[j]):
                return True
    return _inside(ps[0], qs) or _inside(qs[0], ps)


class _Grid:
    """Polygons laid out so far, bucketed by area, to find overlaps without testing every pair."""

    def __init__(self, cell):
        self.cell = max(cell, 1.0)
        self.cells = {}

    def _keys(self, poly):
        x0, y0, x1, y1 = _bbox(poly)
        c = self.cell
        for i in range(int(math.floor(x0 / c)), int(math.floor(x1 / c)) + 1):
            for j in range(int(math.floor(y0 / c)), int(math.floor(y1 / c)) + 1):
                yield (i, j)

    def add(self, poly):
        for key in self._keys(poly):
            self.cells.setdefault(key, []).append(poly)

    def hits(self, poly):
        seen = set()
        for key in self._keys(poly):
            for other in self.cells.get(key, ()):
                if id(other) not in seen:
                    seen.add(id(other))
                    if _overlap(poly, other):
                        return True
        return False


class _Piece:
    """One flat piece: its faces, the lines to print (cut, mountain or valley), glue tabs and text."""

    def __init__(self, cell):
        self.faces = []
        self.lines = []
        self.tabs = []
        self.texts = []
        self.grid = _Grid(cell)

    def points(self):
        return [p for poly in self.faces + self.tabs for p in poly]

    def transform(self, fn):
        self.faces = [[fn(p) for p in poly] for poly in self.faces]
        self.lines = [(fn(a), fn(b), style) for a, b, style in self.lines]
        self.tabs = [[fn(p) for p in poly] for poly in self.tabs]
        self.texts = [(fn(p), text, size) for p, text, size in self.texts]


def _mesh_faces(obj, depsgraph, mm_per_unit):
    """The object's faces (with modifiers) as corner lists in millimetres on paper, the original
    polygon each one is (None when modifiers changed the faces), and the seam edges."""
    evaluated = obj.evaluated_get(depsgraph)
    mesh = evaluated.to_mesh()
    try:
        world = obj.matrix_world
        verts = [(world @ v.co) * mm_per_unit for v in mesh.vertices]
        seams = {tuple(sorted(e.vertices)) for e in mesh.edges if e.use_seam}
        same = len(mesh.polygons) == len(obj.data.polygons)
        faces = [(list(p.vertices), p.index if same else None) for p in mesh.polygons]
    finally:
        evaluated.to_mesh_clear()
    return verts, faces, seams


def _face_frame_2d(points):
    """The face's normal, centre and its corners flat in its own plane, counter-clockwise seen from outside."""
    normal = Vector()
    for i, a in enumerate(points):
        b = points[(i + 1) % len(points)]
        normal.x += (a.y - b.y) * (a.z + b.z)
        normal.y += (a.z - b.z) * (a.x + b.x)
        normal.z += (a.x - b.x) * (a.y + b.y)
    if normal.length < 1e-9:
        return None
    normal.normalize()
    centre = sum(points, Vector()) / len(points)
    x_axis = None
    for i, a in enumerate(points):
        edge = points[(i + 1) % len(points)] - a
        if edge.length > 1e-6:
            x_axis = edge.normalized()
            break
    if x_axis is None:
        return None
    y_axis = normal.cross(x_axis)
    flat = [((p - centre).dot(x_axis), (p - centre).dot(y_axis)) for p in points]
    return normal, centre, flat


def _place(local, iu, iv, at_u, at_v):
    """[local] turned and moved so its corners iu and iv land on at_u and at_v."""
    lu, lv = local[iu], local[iv]
    angle = math.atan2(at_v[1] - at_u[1], at_v[0] - at_u[0]) - math.atan2(lv[1] - lu[1], lv[0] - lu[0])
    c, s = math.cos(angle), math.sin(angle)
    out = []
    for x, y in local:
        dx, dy = x - lu[0], y - lu[1]
        out.append((at_u[0] + c * dx - s * dy, at_u[1] + s * dx + c * dy))
    return out


def _tab(a, b, height):
    """A glue tab outside the edge a->b of a counter-clockwise face."""
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    dx, dy = (b[0] - a[0]) / length, (b[1] - a[1]) / length
    ox, oy = dy, -dx
    h = min(height, length * 0.45)
    inset = min(h, length / 3)
    return [a, b, (b[0] + ox * h - dx * inset, b[1] + oy * h - dy * inset), (a[0] + ox * h + dx * inset, a[1] + oy * h + dy * inset)]


def _inward_label(a, b, gap):
    """A spot just inside a counter-clockwise face, next to the middle of its edge a->b."""
    length = math.hypot(b[0] - a[0], b[1] - a[1]) or 1.0
    nx, ny = -(b[1] - a[1]) / length, (b[0] - a[0]) / length
    return ((a[0] + b[0]) / 2 + nx * gap, (a[1] + b[1]) / 2 + ny * gap)


def unfold(verts, faces, seams, labels, tab_mm, numbers, sheet):
    """Unfolds one mesh into pieces. Joins faces along their longest (and flat) edges first and
    cuts an edge instead when the face would land on the piece or make it bigger than [sheet]
    (width, height in mm, with room for tabs). [numbers] counts glue edges across objects so
    every pair of matching edges has its own number."""
    frames = [None] * len(faces)
    for f, (loop, _) in enumerate(faces):
        if len(loop) >= 3:
            frames[f] = _face_frame_2d([verts[i] for i in loop])
    edges = {}
    for f, (loop, _) in enumerate(faces):
        if frames[f] is None:
            continue
        for k in range(len(loop)):
            key = tuple(sorted((loop[k], loop[(k + 1) % len(loop)])))
            edges.setdefault(key, []).append(f)
    lengths = sorted((verts[a] - verts[b]).length for a, b in edges)
    cell = lengths[len(lengths) // 2] * 3 if lengths else 10.0

    def is_flat(f, g):
        return frames[f][0].angle(frames[g][0], 0.0) < math.radians(FLAT_DEGREES)

    placed = {}
    piece_of = {}
    pieces = []
    counter = 0
    areas = {f: abs(sum(_cross(p, fr[2][i - 1]) for i, p in enumerate(fr[2]))) for f, fr in enumerate(frames) if fr}
    for seed in sorted(areas, key=lambda f: -areas[f]):
        if seed in placed:
            continue
        piece = _Piece(cell)
        pieces.append(piece)
        heap = []
        bounds = [_bbox(frames[seed][2])]

        def fits(poly):
            a, b = bounds[0], _bbox(poly)
            w = max(a[2], b[2]) - min(a[0], b[0]) + 2 * tab_mm
            h = max(a[3], b[3]) - min(a[1], b[1]) + 2 * tab_mm
            return (w <= sheet[0] and h <= sheet[1]) or (w <= sheet[1] and h <= sheet[0])

        def add(f, poly):
            nonlocal counter
            placed[f] = poly
            piece_of[f] = piece
            piece.grid.add(poly)
            loop = faces[f][0]
            for k in range(len(loop)):
                u, v = loop[k], loop[(k + 1) % len(loop)]
                key = tuple(sorted((u, v)))
                sharing = edges[key]
                if len(sharing) != 2 or key in seams:
                    continue
                g = sharing[1] if sharing[0] == f else sharing[0]
                if g in placed or g == f:
                    continue
                counter += 1
                heapq.heappush(heap, (0 if is_flat(f, g) else 1, -(verts[u] - verts[v]).length, counter, f, g, u, v))

        add(seed, frames[seed][2])
        while heap:
            _, _, _, f, g, u, v = heapq.heappop(heap)
            if g in placed:
                continue
            loop_f, loop_g = faces[f][0], faces[g][0]
            poly = _place(frames[g][2], loop_g.index(u), loop_g.index(v), placed[f][loop_f.index(u)], placed[f][loop_f.index(v)])
            if not fits(poly) or piece.grid.hits(poly):
                continue
            a, b = bounds[0], _bbox(poly)
            bounds[0] = (min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
            add(g, poly)

    # Lines, numbers and tabs, edge by edge.
    for (u, v), sharing in edges.items():
        ends = []
        for f in sharing:
            loop = faces[f][0]
            iu, iv = loop.index(u), loop.index(v)
            # Keep each face's own counter-clockwise direction along the edge.
            a, b = (placed[f][iu], placed[f][iv]) if (iu + 1) % len(loop) == iv else (placed[f][iv], placed[f][iu])
            ends.append((f, a, b))
        if len(sharing) == 2:
            (f, a, b), (g, c, d) = ends
            if piece_of[f] is piece_of[g] and _near(a, d) and _near(b, c):
                if not is_flat(f, g):
                    convex = (frames[g][1] - frames[f][1]).dot(frames[f][0]) < 0
                    piece_of[f].lines.append((a, b, "mountain" if convex else "valley"))
                continue
            numbers[0] += 1
            text = str(numbers[0])
            gap = NUMBER_MM * 0.9
            for h, x, y in ends:
                piece_of[h].texts.append((_inward_label(x, y, gap), text, NUMBER_MM))
            # One tab per glued pair, on whichever side has room for it.
            options = [(f, a, b, tab_mm), (g, c, d, tab_mm), (f, a, b, tab_mm / 2), (g, c, d, tab_mm / 2)]
            chosen = None
            for h, x, y, size in options:
                tab = _tab(x, y, size)
                if not piece_of[h].grid.hits(tab):
                    chosen = (h, x, y, tab)
                    break
            if chosen is None:
                chosen = (f, a, b, _tab(a, b, tab_mm / 2))
            h, x, y, tab = chosen
            piece_of[h].tabs.append(tab)
            piece_of[h].grid.add(tab)
            piece_of[h].lines.append((x, y, "mountain"))
            other = (g, c, d) if h == f else (f, a, b)
            piece_of[other[0]].lines.append((other[1], other[2], "cut"))
        else:
            for h, x, y in ends:
                piece_of[h].lines.append((x, y, "cut"))

    for f, poly in placed.items():
        piece_of[f].faces.append(poly)
        name = labels.get(faces[f][1])
        if name:
            cx = sum(p[0] for p in poly) / len(poly)
            cy = sum(p[1] for p in poly) / len(poly)
            x0, _, x1, _ = _bbox(poly)
            size = min(NAME_MM, (x1 - x0) * 0.8 / max(1, len(name)) / 0.55)
            # Faces too small for their name are too small to project on anyway.
            if size >= 1.5:
                piece_of[f].texts.append(((cx, cy), name, size))
    return pieces


def _hull(points):
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and _cross(_sub(lower[-1], lower[-2]), _sub(p, lower[-2])) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and _cross(_sub(upper[-1], upper[-2]), _sub(p, upper[-2])) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def _square_up(piece):
    """Turns the piece to its smallest bounding box and moves it to start at (0, 0); returns its size."""
    hull = _hull(piece.points())
    best = (float("inf"), 0.0)
    for i in range(len(hull)):
        a, b = hull[i - 1], hull[i]
        angle = -math.atan2(b[1] - a[1], b[0] - a[0])
        c, s = math.cos(angle), math.sin(angle)
        x0, y0, x1, y1 = _bbox([(c * x - s * y, s * x + c * y) for x, y in hull])
        if (x1 - x0) * (y1 - y0) < best[0]:
            best = ((x1 - x0) * (y1 - y0), angle)
    c, s = math.cos(best[1]), math.sin(best[1])
    piece.transform(lambda p: (c * p[0] - s * p[1], s * p[0] + c * p[1]))
    x0, y0, x1, y1 = _bbox(piece.points())
    piece.transform(lambda p: (p[0] - x0, p[1] - y0))
    return x1 - x0, y1 - y0


def _skyline_spot(skyline, w, h, width, height):
    """The lowest, then leftmost, place a w x h box fits on top of the skyline, or None."""
    best = None
    for start, _, _ in skyline:
        if start + w > width + 1e-6:
            continue
        y = max(top for x, top, length in skyline if x < start + w - 1e-6 and x + length > start + 1e-6)
        if y + h <= height + 1e-6 and (best is None or (y, start) < (best[1], best[0])):
            best = (start, y)
    return best


def _skyline_add(skyline, x, y, w):
    """The skyline after a box w wide is placed at x with its top at y."""
    out = []
    for sx, top, length in skyline:
        if sx < x:
            out.append((sx, top, min(length, x - sx)))
        if sx + length > x + w:
            start = max(sx, x + w)
            out.append((start, top, sx + length - start))
    out.append((x, y, w))
    out.sort()
    merged = []
    for seg in out:
        if merged and abs(merged[-1][1] - seg[1]) < 1e-6:
            merged[-1] = (merged[-1][0], seg[1], merged[-1][2] + seg[2])
        else:
            merged.append(seg)
    return merged


def _pack(sizes, width, height):
    """Lays pieces out on as few sheets as it can, biggest first, each in the lowest spot it fits:
    (sheet, x, y, turned) per piece, and the sheet count. A piece too big for the sheet gets a
    sheet of its own."""
    gap = PIECE_GAP_MM
    spots = [None] * len(sizes)
    sheets = []
    for i in sorted(range(len(sizes)), key=lambda i: -sizes[i][0] * sizes[i][1]):
        w, h = sizes[i]
        ways = [(w, h, False), (h, w, True)]
        ways = [way for way in ways if way[0] <= width and way[1] <= height]
        if not ways:
            sheets.append([(0.0, height + gap, width + gap)])
            spots[i] = (len(sheets) - 1, 0.0, 0.0, h > w and h <= width)
            continue
        for n in range(len(sheets) + 1):
            if n == len(sheets):
                sheets.append([(0.0, 0.0, width + gap)])
            found = None
            for bw, bh, turned in ways:
                spot = _skyline_spot(sheets[n], bw + gap, bh + gap, width + gap, height + gap)
                if spot and (found is None or (spot[1], spot[0]) < (found[1], found[0])):
                    found = (spot[0], spot[1], bw, bh, turned)
            if found:
                x, y, bw, bh, turned = found
                sheets[n] = _skyline_add(sheets[n], x, y + bh + gap, bw + gap)
                spots[i] = (n, x, y, turned)
                break
    return spots, len(sheets)


def _pdf_text(text):
    return text.encode("cp1252", "replace").replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")


def _text_op(x_mm, y_mm, size_mm, text, centred=True):
    size = size_mm * MM_TO_PT
    x = x_mm * MM_TO_PT - (len(text) * size * 0.55 / 2 if centred else 0)
    y = y_mm * MM_TO_PT - (size * 0.35 if centred else 0)
    return b"BT /F1 %.2f Tf %.2f %.2f Td (" % (size, x, y) + _pdf_text(text) + b") Tj ET\n"


LINE_STYLES = {
    "cut": b"[] 0 d 0 G 0.5 w\n",
    "mountain": b"[3 2] 0 d 0.35 G 0.5 w\n",
    "valley": b"[5 1.5 1 1.5] 0 d 0.35 G 0.5 w\n",
}


def _sheet_content(pieces, width, height, footer):
    k = MM_TO_PT
    out = [b"1 J 1 j\n0.88 g\n"]
    for piece in pieces:
        for tab in piece.tabs:
            out.append(b"%.2f %.2f m " % (tab[0][0] * k, tab[0][1] * k))
            out.extend(b"%.2f %.2f l " % (x * k, y * k) for x, y in tab[1:])
            out.append(b"h f\n")
    out.append(LINE_STYLES["cut"])
    for piece in pieces:
        for tab in piece.tabs:
            out.append(b"%.2f %.2f m " % (tab[1][0] * k, tab[1][1] * k))
            out.extend(b"%.2f %.2f l " % (x * k, y * k) for x, y in tab[2:] + [tab[0]])
            out.append(b"S\n")
    for style, ops in LINE_STYLES.items():
        out.append(ops)
        for piece in pieces:
            for a, b, s in piece.lines:
                if s == style:
                    out.append(b"%.2f %.2f m %.2f %.2f l S\n" % (a[0] * k, a[1] * k, b[0] * k, b[1] * k))
    for piece in pieces:
        for (x, y), text, size in piece.texts:
            # Edge numbers dark, surface names lighter.
            out.append(b"0.2 g\n" if size == NUMBER_MM else b"0.5 g\n")
            out.append(_text_op(x, y, size, text))
    # Footer: what this is, a ruler to check the print scale, and which line means what.
    m = SHEET_MARGIN_MM
    out.append(b"0 g [] 0 d 0 G 0.6 w\n")
    out.append(b"%.2f %.2f m %.2f %.2f l S\n" % (m * k, 6 * k, (m + 50) * k, 6 * k))
    for tick in range(0, 51, 10):
        out.append(b"%.2f %.2f m %.2f %.2f l S\n" % ((m + tick) * k, 6 * k, (m + tick) * k, (8 if tick % 50 == 0 else 7) * k))
    out.append(_text_op(m + 53, 5.3, 2.5, "50 mm: measure it to check the print is at actual size (100%)", centred=False))
    out.append(_text_op(m, 2.2, 2.5, footer + "   Solid: cut.  Dashed: fold away from you.  Dash-dot: fold towards you.  Grey: glue tab, glue it under the edge with the same number.", centred=False))
    return b"".join(out)


class _PdfWriter:
    """A small PDF of vector pages; each big sheet is drawn once and reused by the pages that show it."""

    def __init__(self):
        self.objects = [None, None, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"]
        self.pages = []

    def add(self, body):
        self.objects.append(body)
        return len(self.objects)

    def stream(self, header, content):
        return self.add(b"<< " + header + b" /Length %d >>\nstream\n" % len(content) + content + b"\nendstream")

    def form(self, content, width_mm, height_mm):
        box = b"/BBox [0 0 %.2f %.2f]" % (width_mm * MM_TO_PT, height_mm * MM_TO_PT)
        return self.stream(b"/Type /XObject /Subtype /Form " + box + b" /Resources << /Font << /F1 3 0 R >> >>", content)

    def page(self, width_mm, height_mm, content, form):
        contents = self.stream(b"", content)
        self.pages.append(self.add(
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] " % (width_mm * MM_TO_PT, height_mm * MM_TO_PT)
            + b"/Resources << /Font << /F1 3 0 R >> /XObject << /S %d 0 R >> >> /Contents %d 0 R >>" % (form, contents)
        ))

    def save(self, path):
        self.objects[0] = b"<< /Type /Catalog /Pages 2 0 R >>"
        kids = b" ".join(b"%d 0 R" % p for p in self.pages)
        self.objects[1] = b"<< /Type /Pages /Kids [" + kids + b"] /Count %d >>" % len(self.pages)
        data = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = []
        for n, body in enumerate(self.objects, start=1):
            offsets.append(len(data))
            data += b"%d 0 obj\n" % n + body + b"\nendobj\n"
        xref = len(data)
        data += b"xref\n0 %d\n0000000000 65535 f \n" % (len(self.objects) + 1)
        data += b"".join(b"%010d 00000 n \n" % o for o in offsets)
        data += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(self.objects) + 1, xref)
        with open(path, "wb") as f:
            f.write(data)


def _tiles(width, height, page_w, page_h):
    """How a sheet splits into pages: columns, rows and the step between them, in mm."""
    inner_w, inner_h = page_w - 2 * PAGE_MARGIN_MM, page_h - 2 * PAGE_MARGIN_MM
    step_w, step_h = inner_w - PAGE_OVERLAP_MM, inner_h - PAGE_OVERLAP_MM
    cols = 1 if width <= inner_w else math.ceil((width - inner_w) / step_w) + 1
    rows = 1 if height <= inner_h else math.ceil((height - inner_h) / step_h) + 1
    return cols, rows, step_w, step_h


def _tiled_page(width, height, page_w, page_h, col, row, cols, rows, step_w, step_h, label):
    """One home printer page showing part of a sheet, with + marks where it overlaps its neighbours."""
    k = MM_TO_PT
    m = PAGE_MARGIN_MM
    inner_w, inner_h = page_w - 2 * m, page_h - 2 * m
    x0 = col * step_w
    top = height - row * step_h
    y0 = top - inner_h
    out = [b"q %.2f %.2f %.2f %.2f re W n\n" % (m * k, m * k, inner_w * k, inner_h * k)]
    out.append(b"1 0 0 1 %.2f %.2f cm\n/S Do\n" % ((m - x0) * k, (m - y0) * k))
    # Registration marks in the middle of every overlap strip, on the sheet, so neighbouring
    # pages carry the same marks in the same places.
    marks = []
    for c in range(cols - 1):
        x = (c + 1) * step_w + PAGE_OVERLAP_MM / 2
        marks += [(x, y) for y in _spaced(height)]
    for r in range(rows - 1):
        y = height - (r + 1) * step_h - PAGE_OVERLAP_MM / 2
        marks += [(x, y) for x in _spaced(width)]
    out.append(b"[] 0 d 0 G 0.4 w\n")
    for x, y in marks:
        if x0 - 5 <= x <= x0 + inner_w + 5 and y0 - 5 <= y <= y0 + inner_h + 5:
            out.append(b"%.2f %.2f m %.2f %.2f l %.2f %.2f m %.2f %.2f l S\n" % ((x - 3) * k, y * k, (x + 3) * k, y * k, x * k, (y - 3) * k, x * k, (y + 3) * k))
    out.append(b"Q\n")
    # Trim guides on the edges that go over the previous page.
    out.append(b"[1 2] 0 d 0.6 G 0.3 w\n")
    if col > 0:
        out.append(b"%.2f %.2f m %.2f %.2f l S\n" % (m * k, m * k, m * k, (page_h - m) * k))
    if row > 0:
        out.append(b"%.2f %.2f m %.2f %.2f l S\n" % (m * k, (page_h - m) * k, (page_w - m) * k, (page_h - m) * k))
    out.append(b"0 g\n")
    out.append(_text_op(m, page_h - m + 2.5, 2.5, label, centred=False))
    hint = "Trim the white border on the dotted edges, lay this page over its neighbours so the + marks match, and tape."
    out.append(_text_op(m, m - 4.5, 2.2, hint, centred=False))
    return b"".join(out)


def _spaced(length):
    return [length * (i + 0.5) / max(1, round(length / 60)) for i in range(max(1, round(length / 60)))]


def _row_name(n):
    name = ""
    n += 1
    while n:
        n, r = divmod(n - 1, 26)
        name = chr(65 + r) + name
    return name


def papercraft_objects(context):
    objects = {o for o in context.selected_objects if o.type == "MESH"}
    if context.edit_object and context.edit_object.type == "MESH":
        objects.add(context.edit_object)
    return sorted(objects, key=lambda o: o.name)


def export_papercraft(context, objects, settings, folder, base_name):
    """Unfolds [objects] and writes the PDFs; returns the files written, the sheet and page counts,
    and warnings for the person."""
    scene = context.scene
    depsgraph = context.evaluated_depsgraph_get()
    mm_per_unit = scene.unit_settings.scale_length * 1000.0 * settings.paper_scale
    labels = {}
    if settings.print_names:
        faces = [(o, p) for o, p in selected_faces(context) if o in objects]
        for (obj, poly), name in zip(faces, face_names(faces)):
            labels[(obj.name, poly.index)] = name
    numbers = [0]
    pieces = []
    sheet_w, sheet_h = PAPER_SIZES[settings.sheet]
    usable = (sheet_w - 2 * SHEET_MARGIN_MM, sheet_h - 2 * SHEET_MARGIN_MM)
    face_count = 0
    for obj in objects:
        verts, faces, seams = _mesh_faces(obj, depsgraph, mm_per_unit)
        face_count += len(faces)
        named = {index: labels.get((obj.name, index)) for _, index in faces if index is not None}
        pieces += unfold(verts, faces, seams, named, settings.tab_size, numbers, usable)
    pieces = [p for p in pieces if p.faces]
    if not pieces:
        return [], 0, 0, ["The selected objects have no faces to unfold"]
    warnings = []
    if face_count > 300:
        warnings.append(f"{face_count} faces is a lot to cut out; a Decimate (Planar) modifier makes fewer, bigger faces")

    sizes = [_square_up(p) for p in pieces]
    m = SHEET_MARGIN_MM
    # Portrait or landscape, whichever needs fewer sheets.
    layouts = []
    for w, h in ((sheet_w, sheet_h), (sheet_h, sheet_w)):
        spots, count = _pack(sizes, w - 2 * m, h - 2 * m)
        layouts.append((count, w, h, spots))
    count, sheet_w, sheet_h, spots = min(layouts, key=lambda layout: layout[0])
    too_big = 0
    sheets = [[] for _ in range(count)]
    for piece, (w, h), (n, x, y, turned) in zip(pieces, sizes, spots):
        if turned:
            piece.transform(lambda p, h=h: (h - p[1], p[0]))
            w, h = h, w
        if w > sheet_w - 2 * m or h > sheet_h - 2 * m:
            too_big += 1
        piece.transform(lambda p, x=x, y=y: (p[0] + x + m, sheet_h - m - y - h + p[1]))
        sheets[n].append(piece)
    if too_big:
        warnings.append(f"{too_big} piece(s) are bigger than {settings.sheet}; pick a bigger sheet or a smaller scale")

    scale_text = "actual size" if abs(settings.paper_scale - 1.0) < 1e-6 else f"scale {settings.paper_scale:g}"
    big = _PdfWriter()
    home = _PdfWriter()
    page_w, page_h = PAPER_SIZES[settings.home_page]
    page_count = 0
    for n, sheet in enumerate(sheets):
        footer = f"Oz papercraft - {base_name} - sheet {n + 1} of {count} - {settings.sheet} - {scale_text}."
        content = _sheet_content(sheet, sheet_w, sheet_h, footer)
        big.page(sheet_w, sheet_h, b"/S Do\n", big.form(content, sheet_w, sheet_h))
        # Portrait or landscape pages, whichever needs fewer.
        tilings = [(_tiles(sheet_w, sheet_h, pw, ph), pw, ph) for pw, ph in ((page_w, page_h), (page_h, page_w))]
        (cols, rows, step_w, step_h), pw, ph = min(tilings, key=lambda t: t[0][0] * t[0][1])
        form = home.form(content, sheet_w, sheet_h)
        boxes = [_bbox(piece.points()) for piece in sheet]
        inner_w, inner_h = pw - 2 * PAGE_MARGIN_MM, ph - 2 * PAGE_MARGIN_MM
        for row in range(rows):
            for col in range(cols):
                # Pages with nothing to cut out aren't printed.
                x0, top = col * step_w, sheet_h - row * step_h
                if not any(b[0] < x0 + inner_w and b[2] > x0 and b[1] < top and b[3] > top - inner_h for b in boxes):
                    continue
                label = f"Sheet {n + 1} of {count}, page {_row_name(row)}{col + 1}: row {_row_name(row)} of {_row_name(rows - 1)}, column {col + 1} of {cols}. Print at actual size (100%)."
                home.page(pw, ph, _tiled_page(sheet_w, sheet_h, pw, ph, col, row, cols, rows, step_w, step_h, label), form)
                page_count += 1

    os.makedirs(folder, exist_ok=True)
    big_path = os.path.join(folder, f"{base_name} papercraft {settings.sheet}.pdf")
    home_path = os.path.join(folder, f"{base_name} papercraft {settings.home_page} pages.pdf")
    big.save(big_path)
    home.save(home_path)
    return [big_path, home_path], count, page_count, warnings


def export_folder(operator, settings):
    """The export folder as an absolute path, or None (with an error reported) when it can't be used."""
    if settings.output_dir.startswith("//") and not bpy.data.filepath:
        operator.report({"ERROR"}, "Save the .blend file first, so the export folder can go next to it")
        return None
    folder = bpy.path.abspath(settings.output_dir)
    os.makedirs(folder, exist_ok=True)
    return folder


def base_name(objects):
    if len(objects) == 1:
        return safe_file_name(objects[0].name)
    return safe_file_name(bpy.path.display_name_from_filepath(bpy.data.filepath) or "model")


class OZ_OT_export_papercraft(bpy.types.Operator):
    bl_idname = "oz.export_papercraft"
    bl_label = "Export papercraft"
    bl_description = "Unfold the selected objects into pieces with glue tabs and save them as PDFs to print at true size"

    def execute(self, context):
        was_editing = context.mode == "EDIT_MESH"
        if was_editing:
            bpy.ops.object.mode_set(mode="OBJECT")
        try:
            objects = papercraft_objects(context)
            if not objects:
                self.report({"ERROR"}, "Select the objects to make out of paper first")
                return {"CANCELLED"}
            folder = export_folder(self, context.scene.oz_export)
            if folder is None:
                return {"CANCELLED"}
            files, sheets, pages, warnings = export_papercraft(context, objects, context.scene.oz_export, folder, base_name(objects))
            for warning in warnings:
                self.report({"WARNING"}, warning)
            if not files:
                return {"CANCELLED"}
            self.report({"INFO"}, f"Papercraft: {sheets} sheet(s), or {pages} home printer page(s), in {folder}")
            return {"FINISHED"}
        finally:
            if was_editing:
                bpy.ops.object.mode_set(mode="EDIT")


class OZ_OT_make_it(bpy.types.Operator):
    bl_idname = "oz.make_it"
    bl_label = "Make it"
    bl_description = (
        "Export the papercraft of the selected objects and their projection surfaces in one go. The selected faces "
        "become surfaces; with none selected, every face does (for models with up to 8 faces)"
    )

    def execute(self, context):
        was_editing = context.mode == "EDIT_MESH"
        if was_editing:
            bpy.ops.object.mode_set(mode="OBJECT")
        selected_now = []
        try:
            settings = context.scene.oz_export
            objects = papercraft_objects(context)
            if not objects:
                self.report({"ERROR"}, "Select the objects to make first")
                return {"CANCELLED"}
            folder = export_folder(self, settings)
            if folder is None:
                return {"CANCELLED"}
            surfaces = True
            if not selected_faces(context):
                total = sum(len(o.data.polygons) for o in objects)
                if total <= MAX_APP_SURFACES:
                    for obj in objects:
                        for poly in obj.data.polygons:
                            poly.select = True
                            selected_now.append(poly)
                else:
                    surfaces = False
            files, sheets, pages, warnings = export_papercraft(context, objects, settings, folder, base_name(objects))
            for warning in warnings:
                self.report({"WARNING"}, warning)
            if not files:
                return {"CANCELLED"}
            message = f"Papercraft: {sheets} sheet(s), or {pages} home printer page(s)"
            if surfaces:
                if "FINISHED" not in bpy.ops.oz.export_surfaces():
                    return {"CANCELLED"}
                message += f", and {len(selected_faces(context))} surfaces with oz-surfaces.json"
            else:
                self.report({"WARNING"}, "No surfaces exported: select the faces to project on in Edit Mode (up to 8)")
            self.report({"INFO"}, f"{message} in {folder}")
            return {"FINISHED"}
        finally:
            for poly in selected_now:
                poly.select = False
            if was_editing:
                bpy.ops.object.mode_set(mode="EDIT")


class OZ_PT_export_panel(bpy.types.Panel):
    bl_label = "Oz Projection"
    bl_idname = "OZ_PT_export_panel"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Oz"

    def draw(self, context):
        layout = self.layout
        settings = context.scene.oz_export
        layout.prop(settings, "output_dir")
        layout.operator(OZ_OT_make_it.bl_idname, icon="OUTLINER_OB_GROUP_INSTANCE")
        layout.label(text="Papercraft PDFs plus the surfaces for the app.")

        box = layout.box()
        box.label(text="Surfaces for the app", icon="RENDER_ANIMATION")
        box.label(text="Select faces in Edit Mode, then export.")
        box.prop(settings, "max_size")
        box.prop(settings, "depth")
        box.operator(OZ_OT_export_surfaces.bl_idname)
        box.label(text="Animated scenes render each face as a video.")

        box = layout.box()
        box.label(text="Papercraft", icon="FILE_IMAGE")
        box.prop(settings, "paper_scale")
        box.prop(settings, "sheet")
        box.prop(settings, "home_page")
        box.prop(settings, "tab_size")
        box.prop(settings, "print_names")
        box.operator(OZ_OT_export_papercraft.bl_idname)
        box.label(text="Mark seams to choose where it is cut.")


classes = (OzExportSettings, OZ_OT_export_surfaces, OZ_OT_export_papercraft, OZ_OT_make_it, OZ_PT_export_panel)


def register():
    for cls in classes:
        bpy.utils.register_class(cls)
    bpy.types.Scene.oz_export = PointerProperty(type=OzExportSettings)


def unregister():
    del bpy.types.Scene.oz_export
    for cls in reversed(classes):
        bpy.utils.unregister_class(cls)


if __name__ == "__main__":
    register()
