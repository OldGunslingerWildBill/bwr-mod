"""Inventory icons of the two Terry skids from their exported compact OBJ (headless Blender)."""
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('icons', ROOT / 'art/models/inventory/render_icons.py')
icons = importlib.util.module_from_spec(spec)
spec.loader.exec_module(icons)
# The feed pump turbine icon comes from art/models/modern/build_turbine_feed_pump.py -- --icon (its compact.obj is frozen).
for item, folder in (('rcic_twl', 'rcic_twl_terry'), ('hpci_turbine', 'hpci_turbine_terry')):
    icons.render(item, icons.ASSETS / f'models/block/pumps/{folder}/compact.obj')
