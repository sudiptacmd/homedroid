//! Bounded wire controls. The phone performs the final per-camera capability check.
use crate::http::invalid;
use serde_json::{json, Value};
use std::io;

pub fn validate(v: &Value) -> io::Result<()> {
    let o = v
        .as_object()
        .ok_or_else(|| invalid("camera controls must be an object"))?;
    for (k, v) in o {
        let valid = match k.as_str() {
            "reset" | "torch" | "aeLock" | "awbLock" | "manualSensor" | "stabilization"
            | "mirror" | "flip" => v.is_boolean(),
            "camera" => v.as_str().is_some_and(|s| {
                !s.is_empty()
                    && s.len() <= 64
                    && s.bytes()
                        .all(|c| c.is_ascii_alphanumeric() || b"_-".contains(&c))
            }),
            "focus" => v
                .as_str()
                .is_some_and(|s| ["continuous", "auto", "manual"].contains(&s)),
            "wb" => v.as_str().is_some_and(|s| {
                ["auto", "incandescent", "fluorescent", "daylight", "cloudy"].contains(&s)
            }),
            "antiBanding" => v
                .as_str()
                .is_some_and(|s| ["auto", "50hz", "60hz"].contains(&s)),
            "rotate" => v.as_u64().is_some_and(|n| [0, 90, 180, 270].contains(&n)),
            "width" | "height" => integer(v, 2, 4096),
            "fps" => integer(v, 1, 120),
            "quality" => integer(v, 1, 100),
            "iso" => integer(v, 1, 10_000_000),
            "shutterNs" => integer(v, 1, 1_000_000_000_000),
            "zoom" => number(v, 0.01, 1000.0),
            "focusDistance" => number(v, 0.0, 1000.0),
            "ev" => number(v, -100.0, 100.0),
            "x" | "y" => number(v, 0.0, 1.0),
            _ => return Err(invalid(format!("unknown camera control: {k}"))),
        };
        if !valid {
            return Err(invalid(format!("invalid camera control: {k}")));
        }
    }
    if o.contains_key("x") != o.contains_key("y") {
        return Err(invalid("tap focus needs x and y"));
    }
    Ok(())
}
fn number(v: &Value, min: f64, max: f64) -> bool {
    v.as_f64()
        .is_some_and(|n| n.is_finite() && (min..=max).contains(&n))
}
fn integer(v: &Value, min: u64, max: u64) -> bool {
    v.as_u64().is_some_and(|n| (min..=max).contains(&n))
}

pub fn flag(arg: &str, value: &str) -> io::Result<(String, Value)> {
    let key = match arg {
        "--zoom" => "zoom",
        "--ev" => "ev",
        "--wb" => "wb",
        "--rotate" => "rotate",
        "--focus" => "focus",
        "--focus-distance" => "focusDistance",
        "--iso" => "iso",
        "--shutter-ns" => "shutterNs",
        "--anti-banding" => "antiBanding",
        _ => return Err(invalid("unknown control flag")),
    };
    let v = if ["wb", "focus", "antiBanding"].contains(&key) {
        json!(value)
    } else {
        serde_json::from_str(value).map_err(|_| invalid(format!("invalid {arg}")))?
    };
    validate(&json!({key: v}))?;
    Ok((key.into(), v))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounds_and_types() {
        for v in [
            json!({"torch":"true"}),
            json!({"zoom":0}),
            json!({"rotate":45}),
            json!({"quality":101}),
            json!({"iso":1.5}),
            json!({"wb":"sun"}),
            json!({"x":0.2}),
            json!({"x":2,"y":0}),
            json!({"unknown":true}),
            json!([]),
        ] {
            assert!(validate(&v).is_err(), "{v}");
        }
        validate(&json!({"zoom":1.5,"ev":-1.0,"rotate":270,"x":0.2,"y":0.8,"shutterNs":10000000}))
            .unwrap();
    }
    #[test]
    fn cli_flags() {
        assert_eq!(flag("--zoom", "1.5").unwrap(), ("zoom".into(), json!(1.5)));
        assert_eq!(flag("--wb", "daylight").unwrap().1, json!("daylight"));
        for (k, v) in [
            ("--zoom", "NaN"),
            ("--ev", "true"),
            ("--rotate", "91"),
            ("--wb", "bad"),
        ] {
            assert!(flag(k, v).is_err());
        }
    }
}
