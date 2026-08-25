from settings.extract import extract_settings


def test_extract_with_confidences():
    def vision(_image):
        return {
            "Max IOB": {"value": "6.0", "confidence": 0.99},
            "ISF": {"value": "50", "confidence": 0.95},
        }

    v = extract_settings([b"img1"], vision)
    assert v.values["max_iob"] == 6.0 and v.values["sens"] == 50.0
    assert v.needs_confirm == []


def test_extract_plain_values_without_confidence():
    v = extract_settings([b"img"], lambda _i: {"enableSMB_always": "true"})
    assert v.values["enable_smb"] is True


def test_low_confidence_read_flags_confirm():
    v = extract_settings([b"img"], lambda _i: {"Max IOB": {"value": "6", "confidence": 0.4}})
    assert "max_iob" in v.needs_confirm


def test_later_image_overrides_earlier():
    seen = {"n": 0}

    def vision(_image):
        seen["n"] += 1
        return {"Max IOB": "6.0"} if seen["n"] == 1 else {"Max IOB": "4.0"}

    v = extract_settings([b"a", b"b"], vision)
    assert v.values["max_iob"] == 4.0   # second screenshot corrects the first
