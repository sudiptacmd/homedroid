package dev.homedroid
import android.content.Context
import java.io.File

class Spec(val name: String, val command: List<String>, val env: Map<String, String> = emptyMap())
// Unrelated Android service and Alpine execution dependencies are deliberately inert.
class Alpine(ctx: Context, paths: Paths) {
    val root = File(paths.root, "alpine").apply { mkdirs() }
    fun command(args: List<String>, env: Map<String, String>) = args
    fun processEnv() = emptyMap<String, String>()
    fun removeGuest(path: String) { error("not supported by host test double") }
}
object ServerService {
    var supervisor: Supervisor? = null
    val running = false
    fun restart(ctx: Context) { error("not supported by host test double") }
    fun install(ctx: Context, app: AppDef) { error("not supported by host test double") }
    fun uninstall(ctx: Context, app: AppDef) { error("not supported by host test double") }
    fun deploy(ctx: Context, id: String) { error("not supported by host test double") }
    fun undeploy(ctx: Context, id: String) { error("not supported by host test double") }
}
object Jobs {
    val title: String? = null
    val running = false
    val error: String? = null
    val log = LogRing(200)
}
class Device(ctx: Context) {
    val model = "host test"
    val android = "stub"
    val batteryPercent = 100
    val batteryTempC = 0f
    val charging = false
    val hot = false
    val ramFreeMb = 0
    val ramTotalMb = 0
    val storageFreeMb = 0
    val storageTotalMb = 0
    val ips = emptyList<String>()
    val uptimeS = 0
}
