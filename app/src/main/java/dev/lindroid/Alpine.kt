package dev.lindroid

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Alpine Linux userland run through proot. Nothing is downloaded until something needs it.
 *
 * proot's loader runs from nativeLibraryDir; guest binaries in app storage are then mapped
 * with mmap(PROT_EXEC), which SDK 29+ still allows (only execve() of app data is blocked).
 */
class Alpine(private val ctx: Context, private val paths: Paths) {
    val root = File(paths.root, "alpine")
    private val meta = File(root, ".lindroid")

    val installed get() = File(root, "etc/alpine-release").exists()

    /**
     * Arguments to run [argv] as (fake) root inside Alpine, or inside another [rootfs] such as
     * a pulled container image. Pair with [processEnv]. [binds] maps host folders onto guest
     * paths; [sysvipc] emulates System V IPC, which Android kernels lack (PostgreSQL needs it).
     */
    fun command(
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        binds: List<Pair<String, String>> = emptyList(),
        rootfs: File = root,
        workdir: String = "/root",
        baseEnv: Map<String, String> = BASE_ENV,
        sysvipc: Boolean = false,
    ): List<String> = buildList {
        add(paths.exe("proot"))
        addAll(listOf("-0", "--link2symlink", "--kill-on-exit", "-r", rootfs.path, "-w", workdir))
        if (sysvipc) add("--sysvipc")
        for (bind in BINDS) addAll(listOf("-b", bind))
        // Android hides these from apps; programs that read them get plausible stand-ins.
        for (name in FAKE_PROC.keys) addAll(listOf("-b", "${File(meta, "proc/$name").path}:/proc/$name"))
        addAll(listOf("-b", "${paths.home.path}:/mnt/lindroid"))
        // Android's own userland, so bionic programs (the MediaCodec FFmpeg) run in Alpine
        // too. The lib dir keeps its real path: the linker picks its namespace from it. Not in
        // images: they may mount their own data at /data, where the lib dir would appear.
        if (rootfs == root) {
            for (dir in ANDROID_DIRS) if (File(dir).exists()) addAll(listOf("-b", dir))
            if (File(LINKER_CONFIG).canRead()) addAll(listOf("-b", LINKER_CONFIG))
            addAll(listOf("-b", paths.libDir.path))
        }
        // With storage access, every volume shows up at its usual /storage/... path.
        if (Storage.hasAccess(ctx)) for (v in Storage.volumes(ctx)) addAll(listOf("-b", v.root.path))
        for ((host, guest) in binds) addAll(listOf("-b", "$host:$guest"))
        add("/usr/bin/env")
        add("-i")
        for ((k, v) in baseEnv + env) add("$k=$v")
        addAll(argv)
    }

    /** Environment for the proot process itself. */
    fun processEnv(): Map<String, String> = buildMap {
        put("PROOT_LOADER", paths.exe("proot-loader"))
        File(paths.exe("proot-loader32")).takeIf { it.exists() }?.let { put("PROOT_LOADER_32", it.path) }
        put("PROOT_TMP_DIR", paths.tmp.path)
        if (!seccompWorks()) put("PROOT_NO_SECCOMP", "1")
    }

    /** One-line shell command that opens a login shell in Alpine, for the `alpine` alias. */
    fun shellCommand(): String = shellPrefix(listOf("/bin/sh", "-l"))

    /**
     * Shell command prefix that runs a program inside Alpine; append the program and its
     * arguments. Uses shell assignment prefixes, not env(1): the APK path contains "==", which
     * env would mistake for another NAME=VALUE pair.
     */
    fun shellPrefix(argv: List<String> = emptyList()): String {
        val env = processEnv().entries.joinToString(" ") { "${it.key}=${it.value}" }
        return "$env " + command(argv).joinToString(" ")
    }

    /** Downloads and unpacks the minirootfs if needed, then refreshes per-boot config. */
    @Synchronized
    fun ensure(log: (String) -> Unit) {
        if (!installed) {
            val arch = arch()
            val base = "https://dl-cdn.alpinelinux.org/alpine/v$BRANCH/releases/$arch/"
            val name = "alpine-minirootfs-$VERSION-$arch.tar.gz"
            val tgz = File(paths.tmp, name)
            log("Downloading Alpine $VERSION ($arch)…")
            download(base + name, tgz)
            val want = String(fetch(base + name + ".sha256")).trim().substringBefore(' ')
            if (sha256(tgz) != want) {
                tgz.delete()
                throw IOException("Alpine download is corrupt (checksum mismatch)")
            }
            log("Unpacking…")
            val partial = File(paths.root, "alpine.partial")
            removeTree(partial)
            partial.mkdirs()
            // Our own extractor: Android 10's tar fails on the (impossible) chown.
            java.util.zip.GZIPInputStream(tgz.inputStream().buffered(), 64 * 1024).use { Tar(partial).extract(it) }
            tgz.delete()
            removeTree(root)
            if (!partial.renameTo(root)) throw IOException("could not move Alpine into place")
            log("Alpine $VERSION installed")
        }
        refresh()
    }

    /** Runs a shell script inside Alpine, streaming output to [log]. Returns the exit code. */
    fun run(script: String, log: (String) -> Unit, env: Map<String, String> = emptyMap()): Int {
        val pb = ProcessBuilder(command(listOf("/bin/sh", "-euc", script), env))
            .directory(paths.home)
            .redirectErrorStream(true)
        pb.environment().putAll(processEnv())
        val p = pb.start()
        p.outputStream.close()
        p.inputStream.bufferedReader().forEachLine(log)
        return p.waitFor()
    }

    /**
     * Deletes paths inside Alpine from outside proot. proot fakes hard links as chains of
     * symlinks, and removing those from inside proot fails.
     */
    fun removeGuest(vararg guestPaths: String) {
        for (p in guestPaths) {
            val rel = p.trim('/')
            require(rel.isNotEmpty() && rel.split('/').none { it == ".." || it == "." }) { "bad path $p" }
            removeTree(File(root, rel))
        }
    }

    /** Rewrites config that depends on the current network and app paths. */
    fun refresh() {
        prepareRootfs(root)
        File(meta, "proc").mkdirs()
        for ((name, content) in FAKE_PROC) File(meta, "proc/$name").writeText(content)
        writeFfmpegShim()
    }

    /** Deletes a host directory tree (such as an unpacked image) without following symlinks. */
    fun removeGuestHost(dir: File) = removeTree(dir)

    /** Network config for a rootfs (Alpine or an image); DNS servers change with the network. */
    fun prepareRootfs(dir: File) {
        val etc = File(dir, "etc").also { it.mkdirs() }
        File(etc, "resolv.conf").let {
            it.delete() // images often ship it as a symlink into /run
            it.writeText(dnsServers().joinToString("") { s -> "nameserver $s\n" })
        }
        File(etc, "hosts").let {
            if (!it.exists() || it.length() == 0L) it.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }
    }

    /**
     * Jellyfin can't drive MediaCodec, but it can drive V4L2 encoders. The shim takes their
     * place: commands naming a *_v4l2m2m encoder run Android's FFmpeg with the matching
     * *_mediacodec encoder, and everything else runs Alpine's jellyfin-ffmpeg.
     */
    private fun writeFfmpegShim() {
        val shim = File(root, FFMPEG_SHIM.removePrefix("/"))
        shim.parentFile!!.mkdirs()
        // env(1) would read the "==" in the APK path as an assignment, so go through a link.
        val link = File(shim.parentFile, "android-ffmpeg")
        link.delete()
        Os.symlink(paths.exe("ffmpeg"), link.path)
        // Jellyfin looks for ffprobe beside ffmpeg; probing needs no hardware.
        File(shim.parentFile, "ffprobe").let {
            it.delete()
            Os.symlink("/usr/lib/jellyfin-ffmpeg/ffprobe", it.path)
        }
        shim.writeText(
            """
            |#!/bin/sh
            |# Written by Lindroid on every start; edits are overwritten.
            |case " ${'$'}* " in
            |*_v4l2m2m*) ;;
            |*) exec /usr/lib/jellyfin-ffmpeg/ffmpeg "${'$'}@" ;;
            |esac
            |for arg do
            |	shift
            |	case ${'$'}arg in
            |	h264_v4l2m2m) arg=h264_mediacodec ;;
            |	hevc_v4l2m2m) arg=hevc_mediacodec ;;
            |	libfdk_aac) arg=aac ;;
            |	esac
            |	set -- "${'$'}@" "${'$'}arg"
            |done
            |exec env -i ANDROID_ROOT=/system ANDROID_DATA=/data \
            |	ANDROID_ART_ROOT=/apex/com.android.art ANDROID_I18N_ROOT=/apex/com.android.i18n \
            |	ANDROID_TZDATA_ROOT=/apex/com.android.tzdata PATH=/system/bin \
            |	${link.path.removePrefix(root.path)} "${'$'}@"
            |""".trimMargin()
        )
        shim.setExecutable(true, false)
    }

    private fun dnsServers(): List<String> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val found = cm.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress?.substringBefore('%') }
        return found.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
    }

    /**
     * Android's app seccomp policy rejects fork(2) on ABIs that have it (x86_64, armv7), and
     * musl uses it. proot rewrites it into clone(2), but only in plain ptrace mode, so probe
     * whether the faster seccomp mode can fork here.
     */
    private fun seccompWorks(): Boolean {
        cachedSeccomp?.let { return it }
        val ok = try {
            val pb = ProcessBuilder(command(listOf("/bin/sh", "-c", "/bin/true; /bin/true")))
                .directory(paths.home).redirectErrorStream(true)
            pb.environment()["PROOT_LOADER"] = paths.exe("proot-loader")
            pb.environment()["PROOT_TMP_DIR"] = paths.tmp.path
            val p = pb.start()
            p.inputStream.readBytes()
            p.waitFor() == 0
        } catch (_: IOException) {
            false
        }
        cachedSeccomp = ok
        return ok
    }

    private fun arch(): String = when (File(ctx.applicationInfo.nativeLibraryDir).name) {
        "arm64" -> "aarch64"
        "arm" -> "armv7"
        "x86_64" -> "x86_64"
        else -> throw IOException("unsupported CPU: ${ctx.applicationInfo.nativeLibraryDir}")
    }

    private fun exec(argv: List<String>) {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw IOException("${argv.first()} failed: $out")
    }

    // rm(1) rather than File.deleteRecursively(), which would follow the rootfs's absolute
    // symlinks out into the host filesystem.
    private fun removeTree(dir: File) {
        if (dir.exists()) exec(listOf("/system/bin/rm", "-rf", dir.path))
    }

    companion object {
        private const val BRANCH = "3.24"
        private const val VERSION = "3.24.2"

        @Volatile
        private var cachedSeccomp: Boolean? = null

        private val BINDS = listOf("/dev", "/proc", "/sys")
        private val ANDROID_DIRS = listOf("/system", "/apex", "/vendor", "/product", "/system_ext")

        // Apps can read this file but not list its directory, so bind just the file.
        private const val LINKER_CONFIG = "/linkerconfig/ld.config.txt"

        /** Path of the FFmpeg shim inside Alpine; see [writeFfmpegShim]. */
        const val FFMPEG_SHIM = "/usr/local/lib/lindroid/ffmpeg"

        val BASE_ENV = mapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "TMPDIR" to "/tmp",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )

        private val FAKE_PROC = mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "stat" to "cpu  1957 0 2877 93280 262 342 254 87 0 0\n" +
                "cpu0 1957 0 2877 93280 262 342 254 87 0 0\n" +
                "intr 0\nctxt 0\nbtime 0\nprocesses 0\nprocs_running 1\nprocs_blocked 0\nsoftirq 0\n",
            "uptime" to "124.08 932.80\n",
            "version" to "Linux version 6.1.0-lindroid (lindroid@android) #1 SMP PREEMPT\n",
            "vmstat" to "nr_free_pages 0\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n",
        )

        private fun fetch(url: String): ByteArray = open(url).inputStream.use { it.readBytes() }

        private fun download(url: String, dest: File) {
            open(url).inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        }

        private fun open(url: String): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                if (responseCode != 200) throw IOException("$url: HTTP $responseCode")
            }

        private fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
