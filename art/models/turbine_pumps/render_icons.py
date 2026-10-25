"""Inventory icons of the three turbine-driven pumps from their exported compact OBJ (headless Blender)."""
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('icons', ROOT / 'art/models/inventory/render_icons.py')
icons = importlib.util.module_from_spec(spec)
spec.loader.exec_module(icons)
for item, folder in (('turbine_feed_pump', 'turbine_feed_pump_modern'), ('rcic_twl', 'rcic_twl_terry'), ('hpci_turbine', 'hpci_turbine_terry')):
    icons.render(item, icons.ASSETS / f'models/block/pumps/{folder}/compact.obj')
