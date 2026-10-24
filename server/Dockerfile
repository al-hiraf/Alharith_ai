# للتشغيل على خادم VPS بدل الجوال (اختياري)
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* && useradd -m harith
COPY harith ./harith
ENV HARITH_HOST=0.0.0.0 HARITH_DATA_DIR=/data HARITH_HOME=/app
USER harith
VOLUME /data
EXPOSE 8787
HEALTHCHECK --interval=60s --timeout=5s CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8787/api/health')"
CMD ["python", "-m", "harith", "run"]
