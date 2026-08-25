"""Synthetic Nightscout documents for offline tests.

All data here is fabricated — no real site, token, name or location. Values are chosen to
exercise unit conversion, SMB detection, prediction arrays and the enacted/suggested
merge, not to be clinically realistic.
"""

from __future__ import annotations

# A raw entries doc (numeric `date`, sgv always mg/dL).
ENTRY = {
    "_id": "e1",
    "date": 1_700_000_000_000,
    "dateString": "2023-11-14T22:13:20.000Z",
    "sgv": 128,
    "direction": "Flat",
    "device": "xDrip",
    "type": "sgv",
}

# A manual BG (mbg) entry, no sgv.
ENTRY_MBG = {"_id": "e2", "date": 1_700_000_300_000, "mbg": 140, "type": "mbg"}

# An SMB bolus treatment (flagged via enteredBy, no explicit isSMB).
TREATMENT_SMB = {
    "_id": "t1",
    "created_at": "2023-11-14T22:15:00.000Z",
    "eventType": "Correction Bolus",
    "insulin": 0.6,
    "enteredBy": "openaps://AAPS SMB",
}

# A carb + bolus treatment in mmol-agnostic form.
TREATMENT_CARB = {
    "_id": "t2",
    "created_at": "2023-11-14T18:00:00.000Z",
    "eventType": "Meal Bolus",
    "insulin": 4.0,
    "carbs": 45,
    "enteredBy": "user",
}

# A temp target treatment.
TREATMENT_TT = {
    "_id": "t3",
    "created_at": "2023-11-14T19:37:00.000Z",
    "eventType": "Temporary Target",
    "duration": 225,
    "targetTop": 160,
    "targetBottom": 160,
    "reason": "Activity",
}

# A devicestatus doc with both suggested and enacted (enacted must win).
DEVICESTATUS = {
    "_id": "d1",
    "created_at": "2023-11-14T22:14:00.000Z",
    "device": "openaps://phone",
    "openaps": {
        "iob": [{"iob": 1.85}],
        "suggested": {
            "bg": 128,
            "COB": 0,
            "eventualBG": 110,
            "insulinReq": 0.2,
            "sensitivityRatio": 0.93,
            "reason": "suggested reason",
            "predBGs": {"IOB": [128, 124, 120], "ZT": [128, 130, 131]},
        },
        "enacted": {
            "bg": 128,
            "IOB": 1.85,
            "COB": 0,
            "eventualBG": 108,
            "insulinReq": 0.25,
            "sensitivityRatio": 0.93,
            "units": 0.6,          # enacted SMB bolus in U
            "rate": 0.0,
            "duration": 30,
            "reason": "enacted: SMB 0.6U; maxIOB 11.2",
            "predBGs": {"IOB": [128, 123, 118], "UAM": [128, 120, 112]},
        },
    },
}

# A devicestatus doc with no openaps payload (pump/uploader only) — must be dropped.
DEVICESTATUS_NO_OREF = {
    "_id": "d2",
    "created_at": "2023-11-14T22:19:00.000Z",
    "device": "openaps://phone",
    "pump": {"battery": {"percent": 80}},
}

# A profile document in mmol/L — glucose blocks must convert to mg/dL.
PROFILE_MMOL = {
    "_id": "p1",
    "defaultProfile": "Active",
    "startDate": "2023-01-01T00:00:00.000Z",
    "store": {
        "Active": {
            "dia": 6,
            "units": "mmol",
            "timezone": "Europe/London",
            "basal": [{"time": "00:00", "timeAsSeconds": 0, "value": 0.85}],
            "sens": [{"time": "00:00", "timeAsSeconds": 0, "value": 3.1}],       # mmol -> ~55.9 mg/dL
            "carbratio": [{"time": "00:00", "timeAsSeconds": 0, "value": 10}],
            "target_low": [{"time": "00:00", "timeAsSeconds": 0, "value": 5.0}],  # -> ~90 mg/dL
            "target_high": [{"time": "00:00", "timeAsSeconds": 0, "value": 7.5}], # -> ~135 mg/dL
        }
    },
}
