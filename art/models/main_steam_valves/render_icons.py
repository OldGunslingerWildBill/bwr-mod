"""Inventory icons of the MSIV and the safety relief valve from their exported OBJ (headless Blender)."""
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('icons', ROOT / 'art/models/inventory/render_icons.py')
icons = importlib.util.module_from_spec(spec)
spec.loader.exec_module(icons)
for item, obj in (('msiv', 'models/block/pumps/msiv/compact.obj'), ('ads_relief_valve', 'models/block/alpha/ads_relief_valve/body.obj')):
    icons.render(item, icons.ASSETS / obj)
