"""Generate an original, deterministic 8-bit image with known numerical properties."""

from pathlib import Path

from PIL import Image, ImageDraw

image = Image.new("L", (64, 64), 0)
draw = ImageDraw.Draw(image)
draw.rectangle((8, 8, 23, 23), fill=100)
draw.rectangle((40, 40, 55, 55), fill=200)
image.save(Path(__file__).resolve().parents[1] / "fixtures/two-squares.png")
