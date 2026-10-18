"""AgriConnect crop-monitoring service.

A thin FastAPI layer that turns a field's shape into Sentinel-2 vegetation
readings via Google Earth Engine. It is the "messenger" tier of the crop
monitoring architecture::

    Android app  ->  this service  ->  Google Earth Engine + Sentinel-2

The service holds no field data of its own — each request carries the shape of
the area to measure — so it stays stateless apart from a short-lived cache.

See ``app.gee`` for the Earth Engine work, ``app.geometry`` for the pure
geometry maths, and ``app.main`` for the HTTP surface.
"""

__version__ = "0.2.0"
