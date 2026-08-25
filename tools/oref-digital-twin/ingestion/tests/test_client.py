import pytest

from ingestion.client import NightscoutAuthError, NightscoutClient, NightscoutError
from ingestion.config import NightscoutConfig
from ingestion.pull import run_pull
from ingestion.tests import fixtures as fx

DAY_MS = 86_400_000


def _cfg():
    return NightscoutConfig(base_url="https://example.test", token="tok")


def _no_sleep(_):
    return None


def test_windows_chunk_a_long_range():
    """A 21-day pull with 7-day windows must issue 3 windowed requests per collection."""
    calls = []

    def transport(method, url, params, headers, timeout):
        calls.append((url, params))
        return 200, []

    client = NightscoutClient(_cfg(), transport=transport, sleep=_no_sleep)
    start, end = 0, 21 * DAY_MS
    client.fetch_entries(start, end)
    entries_calls = [c for c in calls if "entries.json" in c[0]]
    assert len(entries_calls) == 3
    # each window carries gte/lte bounds and the auth token
    for _, params in entries_calls:
        assert "find[date][$gte]" in params and "find[date][$lte]" in params
        assert params["token"] == "tok"


def test_dedup_across_windows_by_id():
    def transport(method, url, params, headers, timeout):
        # same doc returned in every window
        return 200, [dict(fx.ENTRY)]

    client = NightscoutClient(_cfg(), transport=transport, sleep=_no_sleep)
    docs = client.fetch_entries(0, 21 * DAY_MS)
    assert len(docs) == 1  # deduplicated by _id


def test_backoff_then_success_on_502():
    seq = [502, 502, 200]

    def transport(method, url, params, headers, timeout):
        status = seq.pop(0)
        return status, ([dict(fx.ENTRY)] if status == 200 else None)

    client = NightscoutClient(_cfg(), transport=transport, sleep=_no_sleep)
    docs = client.fetch_entries(0, DAY_MS)  # single window
    assert len(docs) == 1 and seq == []  # retried through the two 502s


def test_auth_error_raises_immediately():
    def transport(method, url, params, headers, timeout):
        return 403, {"status": 403}

    client = NightscoutClient(_cfg(), transport=transport, sleep=_no_sleep)
    with pytest.raises(NightscoutAuthError):
        client.fetch_entries(0, DAY_MS)


def test_network_exception_exhausts_retries():
    def transport(method, url, params, headers, timeout):
        raise ConnectionError("boom")

    client = NightscoutClient(_cfg(), transport=transport, sleep=_no_sleep, max_retries=2)
    with pytest.raises(NightscoutError):
        client.fetch_entries(0, DAY_MS)


def _routing_transport(method, url, params, headers, timeout):
    if "entries.json" in url:
        return 200, [fx.ENTRY, fx.ENTRY_MBG]
    if "treatments.json" in url:
        return 200, [fx.TREATMENT_SMB, fx.TREATMENT_CARB, fx.TREATMENT_TT]
    if "devicestatus.json" in url:
        return 200, [fx.DEVICESTATUS, fx.DEVICESTATUS_NO_OREF]
    if "profile.json" in url:
        return 200, [fx.PROFILE_MMOL]
    return 200, []


def test_run_pull_end_to_end():
    client = NightscoutClient(_cfg(), transport=_routing_transport, sleep=_no_sleep)
    result = run_pull(_cfg(), 0, DAY_MS, client=client)

    assert len(result.entries) == 2
    assert len(result.treatments) == 3
    # the no-oref devicestatus doc is dropped as "not a loop cycle"
    assert len(result.devicestatus) == 1
    assert result.dropped["devicestatus"] == 1
    assert len(result.profiles) == 1
    assert result.devicestatus_present is True

    summary = result.summary()
    assert summary["counts"]["entries"] == 2
    assert "Temporary Target" in summary["treatment_counts"]
    # sorted ascending by time
    assert result.entries[0].ts_ms <= result.entries[-1].ts_ms


def test_run_pull_warns_when_no_devicestatus():
    def transport(method, url, params, headers, timeout):
        if "profile.json" in url:
            return 200, [fx.PROFILE_MMOL]
        if "entries.json" in url:
            return 200, [fx.ENTRY]
        return 200, []

    client = NightscoutClient(_cfg(), transport=transport, sleep=_no_sleep)
    result = run_pull(_cfg(), 0, DAY_MS, client=client)
    assert result.devicestatus_present is False
    assert any("devicestatus" in w for w in result.warnings())


def test_config_never_leaks_token_in_repr():
    cfg = NightscoutConfig(base_url="https://example.test", token="s3cr3t-value-xyz")
    assert "s3cr3t-value-xyz" not in repr(cfg)
    assert "***" in repr(cfg)
