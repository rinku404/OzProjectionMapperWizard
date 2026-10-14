# Oz Projection export: renders each selected face of your model, seen straight on, and writes
# oz-surfaces.json so the Oz Projection PC app can import the faces as surfaces to warp.
#
# Install (Blender 3.6 or newer): Edit > Preferences > Add-ons, then "Install..." (or on 4.2 and
# newer the drop-down at the top right > "Install from Disk...") and pick this file.
# Use: in Edit Mode select the faces to project on, then in the 3D view's sidebar (N) open the
# "Oz" tab and press "Export surfaces".

bl_info = {
    "name": "Oz Projection export",
    "author": "Oz Projection",
    "version": (1, 0, 0),
    "blender": (3, 6, 0),
    "location": "3D View > Sidebar > Oz",
    "description": "Render selected faces straight on for projection mapping with Oz Projection",
    "category": "Import-Export",
}

import json
import os
import re

import bpy
from bpy.props import FloatProperty, IntProperty, PointerProperty, StringProperty
from mathutils import Matrix, Vector

FORMAT = "oz-surfaces"
FILE_NAME = "oz-surfaces.json"


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

        if settings.output_dir.startswith("//") and not bpy.data.filepath:
            self.report({"ERROR"}, "Save the .blend file first, so the export folder can go next to it")
            return {"CANCELLED"}
        folder = bpy.path.abspath(settings.output_dir)
        os.makedirs(folder, exist_ok=True)

        fps = scene.render.fps / scene.render.fps_base
        frames = scene.frame_end - scene.frame_start + 1
        animated = frames > 1
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


class OZ_PT_export_panel(bpy.types.Panel):
    bl_label = "Oz Projection"
    bl_idname = "OZ_PT_export_panel"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Oz"

    def draw(self, context):
        layout = self.layout
        settings = context.scene.oz_export
        layout.label(text="Select faces in Edit Mode, then export.")
        layout.prop(settings, "output_dir")
        layout.prop(settings, "max_size")
        layout.prop(settings, "depth")
        layout.operator(OZ_OT_export_surfaces.bl_idname, icon="RENDER_ANIMATION")
        layout.label(text="Each face renders the whole animation.")


classes = (OzExportSettings, OZ_OT_export_surfaces, OZ_PT_export_panel)


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
