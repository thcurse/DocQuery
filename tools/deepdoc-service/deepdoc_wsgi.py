"""Uvicorn WSGI entrypoint that initializes exactly one DeepDoc model."""

from deepdoc_http_service import _positive_int, build_runtime, create_app


application = create_app(
    build_runtime(),
    _positive_int("PARSER_MAX_REQUEST_BYTES", 51 * 1024 * 1024),
)
