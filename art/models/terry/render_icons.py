"""Blender inventory icons use the actual clipped-model compact OBJ export."""
import importlib.util
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('icons',ROOT/'art/models/inventory/render_icons.py')
icons=importlib.util.module_from_spec(spec);spec.loader.exec_module(icons)
for id in ('rcic_twl','hpci_turbine'):
    icons.render(id,icons.ASSETS/f'models/block/pumps/{id}_terry/compact.obj')
