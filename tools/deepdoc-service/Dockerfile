FROM docquery/deepdoc-gpu:ragflow-v0.26.4-torch-2.13.0-cu126

COPY deepdoc_http_service.py /opt/docquery-deepdoc/deepdoc_http_service.py
COPY deepdoc_wsgi.py /opt/docquery-deepdoc/deepdoc_wsgi.py

WORKDIR /ragflow
ENTRYPOINT ["uvicorn"]
CMD ["--app-dir", "/opt/docquery-deepdoc", "--interface", "wsgi", "--workers", "1", "--host", "0.0.0.0", "--port", "8080", "--no-access-log", "deepdoc_wsgi:application"]
