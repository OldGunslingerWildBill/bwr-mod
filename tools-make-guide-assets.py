#!/usr/bin/env python3
"""Draws the 16x16 texture of the operator's guide book (original pixel art, no outside source).

Run from anywhere with Pillow installed:  python tools-make-guide-assets.py
Writes mod/src/main/resources/assets/bwr/textures/item/guide_book.png.
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(ROOT, "mod/src/main/resources/assets/bwr/textures")


def guide_book():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([2, 1, 13, 14], fill=(32, 78, 132, 255), outline=(14, 36, 66, 255))      # the cover
    d.rectangle([2, 1, 3, 14], fill=(22, 56, 98, 255))                                   # the spine
    d.rectangle([13, 3, 13, 12], fill=(236, 230, 210, 255))                              # page edges
    d.rectangle([5, 3, 11, 5], fill=(226, 232, 238, 255))                                # a title plate
    yellow = (250, 205, 30, 255)
    d.pieslice([5, 6, 11, 12], 60, 120, fill=yellow)                                     # a trefoil: three sectors
    d.pieslice([5, 6, 11, 12], 180, 240, fill=yellow)
    d.pieslice([5, 6, 11, 12], 300, 360, fill=yellow)
    d.rectangle([7, 8, 9, 10], fill=(32, 78, 132, 255))
    img.putpixel((8, 9), yellow)
    return img


if __name__ == "__main__":
    path = os.path.join(TEX, "item/guide_book.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    guide_book().save(path)
    print("wrote item/guide_book.png")
