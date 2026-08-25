"""Replay oracle: decision-level counterfactuals via the real oref0 determine-basal.

Public surface:
    OrefOracle, run_counterfactual, CounterfactualResult, apply_delta, from_cycle
"""

from .counterfactual import CounterfactualResult, run_counterfactual
from .autoisf_source import (
    AutoIsfOracleUnavailable,
    AutoIsfProcessOracle,
    AutoIsfSourceError,
    AutoIsfSourceManifest,
    build_source_manifest,
    verify_embedded_source_identity,
)
from .inputs import from_cycle
from .oracle_bridge import OracleError, OracleUnavailable, OrefOracle
from .settings_delta import apply_delta, known_keys

__all__ = [
    "OrefOracle",
    "OracleError",
    "OracleUnavailable",
    "run_counterfactual",
    "CounterfactualResult",
    "apply_delta",
    "known_keys",
    "from_cycle",
    "AutoIsfProcessOracle",
    "AutoIsfOracleUnavailable",
    "AutoIsfSourceError",
    "AutoIsfSourceManifest",
    "build_source_manifest",
    "verify_embedded_source_identity",
]
