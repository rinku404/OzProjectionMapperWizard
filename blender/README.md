# Blender: models, papercraft and surfaces

`oz_projection_export.py` is a Blender add-on that turns a model into two things:

- **Papercraft PDFs** to print, cut, fold and glue: the model unfolded into flat pieces with grey
  glue tabs, dashed fold lines and numbered edges (glue each tab under the edge with the same
  number). You get one PDF of big sheets (A0 to A3, for a print shop or plotter) and one with the
  same sheets split across A4 or Letter pages that tape together, with + marks to line them up.
  Both print at the model's real size; a 50 mm ruler on every sheet lets you check the scale.
- **Surfaces for the Oz app**: each selected face rendered straight on, plus `oz-surfaces.json`.
  In the controller, "Import surfaces from Blender…" adds them, named like the faces printed on
  the paper, so you know which surface goes on which side of the paper model.

## Install the add-on

Blender 3.6 or newer: Edit > Preferences > Add-ons, then "Install…" (on 4.2 and newer, the
drop-down at the top right > "Install from Disk…") and pick `oz_projection_export.py`. The tools
are in the 3D view's sidebar (press N), on the "Oz" tab.

## Use it

1. Model in metres at the size you want it on paper (a 0.3 m tall house prints 30 cm tall), or
   change **Scale** (0.5 prints at half size). Save the .blend file.
2. In Edit Mode, select the faces you want to project on (up to 8). Give a face a material named
   like "Door" to call its surface "House Door".
3. Press **Make it**. The PDFs, the face renders and `oz-surfaces.json` go in the export folder
   (`oz_export` next to the .blend file by default).

Tips:

- Simple, low-poly models make the nicest papercraft. If a model has hundreds of faces, add a
  Decimate modifier set to "Planar" first.
- Mark seams (Edge > Mark Seam) to choose where the paper is cut; the add-on cuts more edges
  only where pieces would overlap or not fit on the sheet.
- If pieces come out mirrored or overlapping, recalculate normals (Mesh > Normals > Recalculate
  Outside).

## Let Claude make the models (Blender MCP)

[Blender MCP](https://github.com/ahujasid/blender-mcp) lets the Claude desktop app build models
in your Blender. It runs on your PC, next to Blender, so set it up there once:

1. Install `uv`: open PowerShell and run
   `powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"`.
2. Download `addon.py` from the Blender MCP repository and install it in Blender like the Oz
   add-on above. In the sidebar's "BlenderMCP" tab, press "Connect to Claude".
3. In the Claude desktop app, open Settings > Developer > Edit Config and add:

   ```json
   {
     "mcpServers": {
       "blender": { "command": "uvx", "args": ["blender-mcp"] }
     }
   }
   ```

   Restart the Claude app; a hammer icon shows the Blender tools are there.

Then ask for what you want, for example:

> Make a small low-poly castle in Blender, about 30 cm tall, with flat walls that are good for
> papercraft and projection. Name the walls' materials Front, Left, Right and Tower. Save it,
> select the faces of those walls, and run `bpy.ops.oz.make_it()`.

Claude builds the model and runs the Oz add-on, which writes the PDFs and the surfaces. Print the
PDF, build the model, then import the surfaces in the Oz controller and use "Fill every surface
from a group…" to try your texture groups on it.

Scripts can call the same steps one at a time: `bpy.ops.oz.export_papercraft()` and
`bpy.ops.oz.export_surfaces()`. The settings are on `bpy.context.scene.oz_export` (`paper_scale`,
`sheet`, `home_page`, `tab_size`, `print_names`, `output_dir`).
