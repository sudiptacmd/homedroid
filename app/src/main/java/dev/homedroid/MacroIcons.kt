package dev.homedroid

/** Stable JSON names for the shared Path-based icon system (no duplicate drawables). */
object MacroIcons {
    val all = linkedMapOf(
        "mic" to Icon.Mic, "mic-off" to Icon.MicOff, "camera" to Icon.Camera,
        "volume-up" to Icon.VolumeUp, "volume-down" to Icon.VolumeDown, "volume-mute" to Icon.VolumeMute,
        "play" to Icon.Play, "pause" to Icon.Pause, "next" to Icon.Next, "previous" to Icon.Previous,
        "stop" to Icon.Stop, "lock" to Icon.Lock, "screenshot" to Icon.Screenshot, "browser" to Icon.Globe,
        "terminal" to Icon.Terminal, "folder" to Icon.Folder, "copy" to Icon.Copy, "paste" to Icon.Paste,
        "undo" to Icon.Undo, "redo" to Icon.Redo, "home" to Icon.Home, "light" to Icon.Light,
        "power" to Icon.Power, "plus" to Icon.Plus, "minus" to Icon.Minus, "keyboard" to Icon.Keyboard,
        "mouse" to Icon.Mouse, "music" to Icon.Music, "heart" to Icon.Heart, "search" to Icon.Search,
        "settings" to Icon.Settings, "refresh" to Icon.Refresh, "download" to Icon.Download, "share" to Icon.Share,
        "clock" to Icon.Clock, "bell" to Icon.Bell, "phone" to Icon.Phone, "computer" to Icon.Dock,
        "back" to Icon.Undo, "page" to Icon.Layers, "check" to Icon.Check, "star" to Icon.Sparkle,
        "bolt" to Icon.Bolt, "wifi" to Icon.Wifi, "grid" to Icon.Keypad, "close" to Icon.Close,
    )
    fun get(name: String): Icon = all[name] ?: Icon.Keypad
}
