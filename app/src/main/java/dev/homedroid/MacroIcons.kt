package dev.homedroid

/** Stable JSON names for the shared Path-based icon system (no duplicate drawables), with search keywords. */
object MacroIcons {
    class Entry(val name: String, val icon: Icon, val keywords: String)
    private fun e(name: String, icon: Icon, keywords: String = "") = Entry(name, icon, keywords)
    val list = listOf(
        e("mic", Icon.Mic, "microphone audio record voice"), e("mic-off", Icon.MicOff, "mute microphone silent"),
        e("camera", Icon.Camera, "photo picture webcam"), e("video", Icon.Video, "film movie record stream"),
        e("image", Icon.Image, "picture photo gallery"), e("headphones", Icon.Headphones, "audio listen music"),
        e("volume-up", Icon.VolumeUp, "louder sound speaker"), e("volume-down", Icon.VolumeDown, "quieter sound speaker"),
        e("volume-mute", Icon.VolumeMute, "silent sound off"), e("music", Icon.Music, "song note audio"),
        e("play", Icon.Play, "start media"), e("pause", Icon.Pause, "media hold"), e("stop", Icon.Stop, "media end square"),
        e("next", Icon.Next, "skip forward track"), e("previous", Icon.Previous, "back rewind track"),
        e("lock", Icon.Lock, "secure private"), e("key", Icon.Key, "password secret"), e("fingerprint", Icon.Fingerprint, "biometric identity"),
        e("shield", Icon.Shield, "security protect"), e("screenshot", Icon.Screenshot, "capture screen snip"),
        e("browser", Icon.Globe, "web internet chrome firefox"), e("terminal", Icon.Terminal, "console shell command cli"),
        e("code", Icon.Code, "develop programming"), e("folder", Icon.Folder, "directory files open"),
        e("copy", Icon.Copy, "duplicate clipboard"), e("paste", Icon.Paste, "clipboard insert"),
        e("undo", Icon.Undo, "back revert"), e("redo", Icon.Redo, "forward repeat"), e("edit", Icon.Edit, "pencil write change"),
        e("home", Icon.Home, "house start"), e("light", Icon.Light, "lamp bulb idea"), e("sun", Icon.Sun, "bright day weather"),
        e("moon", Icon.Moon, "night dark sleep"), e("power", Icon.Power, "shutdown off on"), e("bolt", Icon.Bolt, "lightning flash energy fast"),
        e("plus", Icon.Plus, "add new"), e("minus", Icon.Minus, "remove subtract"), e("check", Icon.Check, "done ok tick yes"),
        e("close", Icon.Close, "cancel x no"), e("keyboard", Icon.Keyboard, "type keys"), e("mouse", Icon.Mouse, "click pointer"),
        e("gamepad", Icon.Gamepad, "game controller play"), e("heart", Icon.Heart, "love like favorite"), e("star", Icon.Sparkle, "sparkle favorite magic"),
        e("favorite", Icon.Star, "star rating bookmark"), e("search", Icon.Search, "find magnify zoom"), e("settings", Icon.Settings, "gear options preferences"),
        e("tune", Icon.Tune, "sliders adjust mixer equalizer"), e("refresh", Icon.Refresh, "reload sync update"),
        e("download", Icon.Download, "save get"), e("share", Icon.Share, "send export"), e("link", Icon.Link, "url chain"),
        e("clock", Icon.Clock, "time timer watch"), e("calendar", Icon.Calendar, "date schedule event"), e("bell", Icon.Bell, "notification alarm alert"),
        e("mail", Icon.Mail, "email letter message"), e("chat", Icon.Chat, "message talk discord slack comment"),
        e("phone", Icon.Phone, "mobile call"), e("computer", Icon.Dock, "pc desktop monitor screen"), e("server", Icon.Server, "host nas rack"),
        e("cloud", Icon.Cloud, "sync online weather"), e("wifi", Icon.Wifi, "wireless network signal"), e("network", Icon.Network, "lan traffic"),
        e("plug", Icon.Plug, "power socket"), e("battery", Icon.Battery, "charge energy"), e("cpu", Icon.Cpu, "processor chip"),
        e("memory", Icon.Memory, "ram"), e("storage", Icon.Storage, "disk drive database"), e("thermometer", Icon.Thermometer, "temperature heat"),
        e("eye", Icon.Eye, "view show visible"), e("info", Icon.Info, "help about"), e("warning", Icon.Warning, "caution danger triangle"),
        e("alert", Icon.Alert, "error problem exclamation"), e("trash", Icon.Trash, "delete bin remove"), e("bookmark", Icon.Bookmark, "save tag"),
        e("flag", Icon.Flag, "mark report"), e("pin", Icon.Pin, "location map place"), e("cart", Icon.Cart, "shop buy store"),
        e("gift", Icon.Gift, "present reward"), e("swatch", Icon.Swatch, "palette color paint theme"), e("contrast", Icon.Contrast, "theme dark light"),
        e("apps", Icon.Apps, "grid launcher windows"), e("grid", Icon.Keypad, "keypad deck macro"), e("page", Icon.Layers, "layers stack"),
        e("nodes", Icon.Nodes, "graph connect share"), e("logs", Icon.Logs, "list text lines"), e("open", Icon.OpenExternal, "launch external"),
        e("back", Icon.Undo, "return previous"),
    )
    val all = linkedMapOf<String, Icon>().apply { list.forEach { put(it.name, it.icon) } }
    fun get(name: String): Icon = all[name] ?: Icon.Keypad
    /** Case-insensitive match of every word of [query] against the name or keywords; blank lists everything. */
    fun search(query: String): List<Entry> {
        val words = query.lowercase().split(' ', '-').filter { it.isNotBlank() }
        return list.filter { en -> words.all { w -> en.name.contains(w) || en.keywords.contains(w) } }
    }
}
