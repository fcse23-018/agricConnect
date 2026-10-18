"""Stand-alone plot marking, preview and CSV export.

This is the command-line companion to the web service: mark an area, look at it
on a satellite map, then pull the Sentinel-2 readings into a CSV. It deliberately
reuses ``app.geometry`` and ``app.gee`` so the numbers match the API exactly.

Examples
--------
Preview the test plot (no satellite request)::

    python scripts/ndvi_plot.py --lat -21.382573 --lon 27.446793 \
        --width 40 --length 70 --bearing 140 --preview

Fetch the readings once the shape looks right::

    python scripts/ndvi_plot.py --lat -21.382573 --lon 27.446793 \
        --width 40 --length 70 --bearing 140 --csv

Use your own four corners instead of a rectangle::

    python scripts/ndvi_plot.py --corners "-21.38250,27.44660 -21.38250,27.44700 \
        -21.38285,27.44700 -21.38285,27.44660" --csv

Live Sentinel-2 needs ``GEE_ENABLED=true`` and a logged-in ``earthengine-api``;
otherwise the tool prints the deterministic synthetic series and labels it.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import gee  # noqa: E402
from app.geometry import (  # noqa: E402
    centroid,
    inset_ring,
    polygon_area_m2,
    rectangle_corners,
)

PREVIEW_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Plot preview</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>html,body,#map{height:100%;margin:0}
#box{position:absolute;z-index:1000;top:10px;right:10px;background:#fff;padding:8px 12px;
font:13px Arial;border-radius:6px;box-shadow:0 1px 4px rgba(0,0,0,.4);max-width:280px}</style>
</head><body><div id="map"></div>
<div id="box"><b>Plot preview</b><br>
<span style="color:#f9a825">&#9632;</span> dashed = area you marked<br>
<span style="color:#7cb342">&#9632;</span> solid = area that will be measured<br>
Click the map to read any coordinate.</div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
var outer = __OUTER__;
var inner = __INNER__;
var map = L.map('map');
L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
  {maxZoom: 20, maxNativeZoom: 16, attribution: 'Imagery: Esri'}).addTo(map);
var o = L.polygon(outer, {color: '#f9a825', weight: 2, dashArray: '6 4', fill: false}).addTo(map);
L.polygon(inner, {color: '#7cb342', weight: 2, fillOpacity: 0.25}).addTo(map);
map.fitBounds(o.getBounds().pad(0.6));
map.on('click', function(e){
  L.popup().setLatLng(e.latlng)
   .setContent(e.latlng.lat.toFixed(6) + ', ' + e.latlng.lng.toFixed(6)).openOn(map);
});
</script></body></html>"""


def _parse_corners(raw: str) -> list[tuple[float, float]]:
    """Parse "lat,lon lat,lon ..." (pairs separated by spaces or semicolons)."""
    points: list[tuple[float, float]] = []
    for chunk in raw.strip().split(";"):
        for pair in chunk.strip().split():
            pair = pair.strip()
            if not pair:
                continue
            lat, _, lon = pair.partition(",")
            points.append((float(lat), float(lon)))
    return points


def _write_preview(outer, inner, path):
    data = {
        "__OUTER__": json.dumps([[lat, lon] for lat, lon in outer]),
        "__INNER__": json.dumps([[lat, lon] for lat, lon in inner]),
    }
    html = PREVIEW_HTML.replace("__OUTER__", data["__OUTER__"]).replace(
        "__INNER__", data["__INNER__"]
    )
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(html)
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lat", type=float, help="Centre latitude (with --lon/--width/--length).")
    parser.add_argument("--lon", type=float, help="Centre longitude.")
    parser.add_argument("--width", type=float, default=40.0, help="Short side in metres.")
    parser.add_argument("--length", type=float, default=70.0, help="Long side in metres.")
    parser.add_argument("--bearing", type=float, default=0.0, help="Long-side bearing, clockwise from north.")
    parser.add_argument("--corners", type=str, help="'lat,lon' pairs separated by spaces or ';'.")
    parser.add_argument("--inset", type=float, default=5.0, help="Inward inset in metres.")
    parser.add_argument("--days-back", type=int, default=365)
    parser.add_argument("--preview", action="store_true", help="Write plot_preview.html and stop.")
    parser.add_argument("--csv", action="store_true", help="Fetch readings and write plot_readings.csv.")
    parser.add_argument("--preview-file", default="plot_preview.html")
    parser.add_argument("--csv-file", default="plot_readings.csv")
    args = parser.parse_args()

    if args.corners:
        corners = _parse_corners(args.corners)
    elif args.lat is not None and args.lon is not None:
        corners = rectangle_corners(args.lat, args.lon, args.width, args.length, args.bearing)
    else:
        parser.error("Provide --lat/--lon or --corners.")

    if len(corners) < 3:
        parser.error("Need at least three corners.")

    inner = inset_ring(corners, args.inset) if args.inset > 0 else corners
    if inner is None:
        print("The measured area is under 200 m2 after the inset. Enlarge the area or lower --inset.")
        return 2

    outer_area = polygon_area_m2(corners)
    measured_area = polygon_area_m2(inner)
    min_pixels = gee.min_clear_pixels(measured_area)
    lat, lon = centroid(corners)

    print(f"Marked area : {outer_area:,.0f} m2 ({outer_area / 10_000:.2f} ha)")
    print(f"Measured    : {measured_area:,.0f} m2 after {args.inset:g} m inset "
          f"(about {measured_area / 100:.0f} pixels)")
    print(f"A reading needs at least {min_pixels} clear pixels")
    print(f"Centre      : {lat:.6f}, {lon:.6f}\n")

    path = _write_preview(corners, inner, args.preview_file)
    print(f"Preview map saved: {path}")

    if args.preview and not args.csv:
        print("\n--preview only: no satellite data requested.")
        print("Check the map. Re-run with --csv when the shape fits.")
        return 0

    if not args.csv:
        print("\nNothing else to do. Pass --csv to fetch the Sentinel-2 readings.")
        return 0

    data = gee.fetch_series(corners, inset_m=args.inset, days_back=args.days_back)
    print(f"\nsource: {data['source']}  (set GEE_ENABLED=true for live Sentinel-2)")
    print(f"{len(data['readings'])} reliable readings; {len(data['skipped'])} skipped\n")
    print("date        ndvi   evi    pixels")
    for row in data["readings"]:
        print(f"{row['date']}  {row['ndvi']:.4f}  {row['evi']:.4f}  {row['pixels']}")

    with open(args.csv_file, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["date", "ndvi", "evi", "clear_pixels"])
        for row in data["readings"]:
            writer.writerow([row["date"], row["ndvi"], row["evi"], row["pixels"]])
    print(f"\nSaved: {args.csv_file}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
