# AgriConnect crop-monitoring service — container image.
#
#   docker build -t agriconnect/crop-api .
#   docker run -p 8000:8000 -e GEE_ENABLED=false agriconnect/crop-api
#
# For LIVE Sentinel-2, add `earthengine-api` to requirements.txt (or install it
# here) and mount the Earth Engine credentials at runtime.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /srv

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

EXPOSE 8000

# A single worker matches the in-process cache; scale out behind a shared cache.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
