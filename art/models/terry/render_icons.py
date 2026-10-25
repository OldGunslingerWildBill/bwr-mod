"""Superseded: run art/models/turbine_pumps/render_icons.py (all three turbine-driven pumps)."""
import runpy
from pathlib import Path
runpy.run_path(str(Path(__file__).resolve().parents[1] / 'turbine_pumps/render_icons.py'), run_name='__main__')
