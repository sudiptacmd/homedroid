use crate::http::{invalid, Address};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::{self, Write},
    path::PathBuf,
};
#[derive(Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default, deny_unknown_fields)]
pub struct Config {
    pub address: String,
    pub token: String,
    pub computer_id: String,
    pub phone: String,
    pub phone_id: String,
    pub allow_commands: bool,
    pub camera_presets: std::collections::BTreeMap<String, serde_json::Value>,
}
impl Config {
    pub fn path() -> io::Result<PathBuf> {
        #[cfg(windows)]
        {
            Ok(PathBuf::from(
                std::env::var_os("APPDATA").ok_or_else(|| invalid("APPDATA is unset"))?,
            )
            .join("Homedroid/desktop.json"))
        }
        #[cfg(not(windows))]
        {
            let root = std::env::var_os("XDG_CONFIG_HOME")
                .map(PathBuf::from)
                .or_else(|| std::env::var_os("HOME").map(|p| PathBuf::from(p).join(".config")))
                .ok_or_else(|| invalid("HOME is unset"))?;
            Ok(root.join("homedroid-desktop/config.json"))
        }
    }
    pub fn parse(s: &str) -> io::Result<Self> {
        let c: Self =
            serde_json::from_str(s).map_err(|e| invalid(format!("Invalid config: {e}")))?;
        c.validate()?;
        Ok(c)
    }
    pub fn validate(&self) -> io::Result<()> {
        if !self.address.is_empty() {
            Address::parse(&self.address)?;
        }
        if !self.token.is_empty()
            && (self.token.len() != 64
                || !self.token.bytes().all(|c| c.is_ascii_hexdigit())
                || self.address.is_empty())
        {
            return Err(invalid("invalid pairing credentials"));
        }
        if self.camera_presets.len() > 32 {
            return Err(invalid("at most 32 camera presets"));
        }
        for (name, settings) in &self.camera_presets {
            if name.trim().is_empty() || name.len() > 80 {
                return Err(invalid("preset name must be 1–80 bytes"));
            }
            crate::controls::validate(settings)?;
            if settings.get("x").is_some() || settings.get("reset").is_some() {
                return Err(invalid("presets cannot contain transient controls"));
            }
        }
        Ok(())
    }
    pub fn load() -> io::Result<Self> {
        match fs::read_to_string(Self::path()?) {
            Ok(s) => Self::parse(&s),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(Self::default()),
            Err(e) => Err(e),
        }
    }
    pub fn save(&self) -> io::Result<()> {
        self.validate()?;
        let p = Self::path()?;
        let parent = p.parent().ok_or_else(|| invalid("invalid config path"))?;
        fs::create_dir_all(parent)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(parent, fs::Permissions::from_mode(0o700))?;
        }
        let tmp = p.with_extension("json.tmp");
        let mut opts = fs::OpenOptions::new();
        opts.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            opts.mode(0o600);
        }
        let mut f = opts.open(&tmp)?;
        let result = (|| {
            f.write_all(serde_json::to_string_pretty(self)?.as_bytes())?;
            f.sync_all()?;
            drop(f);
            #[cfg(windows)]
            if p.exists() {
                fs::remove_file(&p)?;
            }
            fs::rename(&tmp, &p)
        })();
        if result.is_err() {
            let _ = fs::remove_file(tmp);
        }
        result
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn defaults_and_validation() {
        assert!(!Config::parse("{}").unwrap().allow_commands);
        assert!(
            Config::parse(r#"{"allowCommands":true}"#)
                .unwrap()
                .allow_commands
        );
        assert!(Config::parse(r#"{"token":"oops"}"#).is_err());
        assert!(Config::parse(r#"{"allowCommand":true}"#).is_err());
    }
}
