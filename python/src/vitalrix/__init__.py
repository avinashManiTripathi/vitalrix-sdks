"""VitalRix for Python: traces, metrics and logs in one call.

    import vitalrix
    vitalrix.init()          # reads VITALRIX_URL, VITALRIX_API_KEY, OTEL_SERVICE_NAME, ...
"""
from .core import check, init, instrument_installed, shutdown

__all__ = ["init", "check", "shutdown", "instrument_installed"]
__version__ = "0.1.0"
