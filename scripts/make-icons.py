"""
Generates the Ledger favicon set from the brand mark.

The mark is three stacked rules of decreasing length -- a ledger entry reduced to
a glyph -- in brass on ink, matching the sidebar in the application itself.

An SVG is the primary icon because it stays crisp at every size a browser might
ask for. The rasters exist for the places that still cannot use one: Safari
before 16.4, iOS home screens, and Windows shortcuts.

Rendered here rather than pulled from a library so the repository stays free of a
build-time image dependency for four small files. Drawn at 4x and box-filtered
down, which is what gives the rounded corners their anti-aliasing.
"""
import pathlib
import struct
import sys
import zlib

INK = (0x0C, 0x0E, 0x0F)
BRASS = (0xE0, 0xA9, 0x59)

SUPERSAMPLE = 4

# Geometry on a 32-unit grid, chosen so every edge lands on a whole pixel when
# the grid is halved to 16 -- the size a browser tab actually uses. Odd offsets
# put the bars on half-pixel rows, and the anti-aliasing then makes the first and
# last bar look thinner than the middle one.
#
# Three bars of height 4 with 4-unit gaps: 20 units of mark, 6 clear above and
# below. Widths are even for the same reason.
TILE_RADIUS = 7.0
BAR_X = 6.0
BAR_HEIGHT = 4.0
BAR_RADIUS = 2.0
BARS = [(6.0, 20.0), (14.0, 14.0), (22.0, 8.0)]  # (y, width)


def in_rounded_rect(px, py, x, y, w, h, r):
    """Whether a point falls inside a rounded rectangle."""
    if px < x or px > x + w or py < y or py > y + h:
        return False
    r = min(r, w / 2, h / 2)
    if r <= 0:
        return True

    # Inside the cross formed by the two inner rectangles: definitely in.
    if x + r <= px <= x + w - r or y + r <= py <= y + h - r:
        return True

    # Otherwise it is in a corner region, so measure against that corner centre.
    cx = x + r if px < x + r else x + w - r
    cy = y + r if py < y + r else y + h - r
    return (px - cx) ** 2 + (py - cy) ** 2 <= r * r


def render(size, rounded=True):
    """Renders RGB pixel rows at the given size, supersampled then averaged."""
    hi = size * SUPERSAMPLE
    step = 32.0 / hi

    tile_r = TILE_RADIUS if rounded else 0.0

    # Accumulate at high resolution, one row of the final image at a time.
    rows = []
    for out_y in range(size):
        row = bytearray()
        for out_x in range(size):
            r_sum = g_sum = b_sum = 0
            for sy in range(SUPERSAMPLE):
                py = (out_y * SUPERSAMPLE + sy + 0.5) * step
                for sx in range(SUPERSAMPLE):
                    px = (out_x * SUPERSAMPLE + sx + 0.5) * step

                    colour = None
                    if in_rounded_rect(px, py, 0, 0, 32, 32, tile_r):
                        colour = INK
                        for bar_y, bar_w in BARS:
                            if in_rounded_rect(px, py, BAR_X, bar_y, bar_w,
                                               BAR_HEIGHT, BAR_RADIUS):
                                colour = BRASS
                                break

                    # Outside the tile the icon is white, so a rounded corner
                    # blends toward the page rather than toward black.
                    if colour is None:
                        colour = (0xFF, 0xFF, 0xFF)

                    r_sum += colour[0]
                    g_sum += colour[1]
                    b_sum += colour[2]

            n = SUPERSAMPLE * SUPERSAMPLE
            row += bytes((r_sum // n, g_sum // n, b_sum // n))
        rows.append(bytes(row))
    return rows


def png(rows, size):
    """Minimal 8-bit RGB PNG. Filter type 0 on every scanline."""
    raw = b''.join(b'\x00' + row for row in rows)

    def chunk(tag, data):
        body = tag + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body))

    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


def ico(entries):
    """
    An ICO containing PNG payloads.

    Storing PNG rather than BMP inside an ICO is valid and universally supported
    now, and it avoids hand-rolling the bottom-up BMP with its AND mask.
    """
    header = struct.pack('<HHH', 0, 1, len(entries))
    offset = len(header) + 16 * len(entries)

    directory = b''
    for size, data in entries:
        directory += struct.pack('<BBBBHHII',
                                 size if size < 256 else 0,
                                 size if size < 256 else 0,
                                 0, 0, 1, 32, len(data), offset)
        offset += len(data)

    return header + directory + b''.join(data for _, data in entries)


SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" role="img"
     aria-label="Ledger">
  <!--
    Three stacked rules of decreasing length: a ledger entry, reduced to a mark.
    The same glyph as the sidebar brand, on a filled tile so it reads against a
    light or a dark browser tab strip rather than only one of them. Offsets and
    widths are even so that halving the grid to 16 leaves every edge on a whole
    pixel.
  -->
  <rect width="32" height="32" rx="7" fill="#0c0e0f"/>
  <g fill="#e0a959">
    <rect x="6" y="6" width="20" height="4" rx="2"/>
    <rect x="6" y="14" width="14" height="4" rx="2"/>
    <rect x="6" y="22" width="8" height="4" rx="2"/>
  </g>
</svg>
"""


def main(out_dir):
    out = pathlib.Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    (out / 'icon.svg').write_text(SVG, encoding='utf-8')
    print('icon.svg')

    # ICO carries the small sizes a browser tab and a Windows shortcut ask for.
    ico_sizes = [16, 32, 48]
    payloads = []
    for size in ico_sizes:
        payloads.append((size, png(render(size), size)))
        print(f'  rendered {size}x{size}')
    (out / 'favicon.ico').write_bytes(ico(payloads))
    print('favicon.ico', (out / 'favicon.ico').stat().st_size, 'bytes')

    # iOS applies its own mask and does not honour transparency, so this one is
    # full-bleed with square corners.
    apple = png(render(180, rounded=False), 180)
    (out / 'apple-touch-icon.png').write_bytes(apple)
    print('apple-touch-icon.png', len(apple), 'bytes')


if __name__ == '__main__':
    main(sys.argv[1])
