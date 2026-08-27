package com.droidspaces.app.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class LinuxDesktopApp(
    val name: String,
    val exec: String,
    val desktopFile: String = "",
)

data class AnlandAppSession(
    val id: String,
    val hostSocket: String,
)

/**
 * WSLg-like app launcher for Anland.
 *
 * Each Linux application receives its own Anland broker socket and its own
 * lightweight KWin Wayland compositor. The Anland Android consumer maps that
 * socket to a separate Android task/window.
 */
object AnlandAppManager {
    private const val MAX_APPS = 160

    suspend fun listApps(containerName: String): List<LinuxDesktopApp> =
        withContext(Dispatchers.IO) {
            val script = """
                for f in /usr/share/applications/*.desktop \
                         /usr/local/share/applications/*.desktop \
                         /home/*/.local/share/applications/*.desktop \
                         /root/.local/share/applications/*.desktop; do
                    [ -f "${'$'}f" ] || continue
                    grep -qi '^NoDisplay=true' "${'$'}f" && continue
                    grep -qi '^Hidden=true' "${'$'}f" && continue
                    name="${'$'}(sed -n '/^\[Desktop Entry\]/,/^\[/s/^Name=//p' "${'$'}f" | head -n 1)"
                    exec="${'$'}(sed -n '/^\[Desktop Entry\]/,/^\[/s/^Exec=//p' "${'$'}f" | head -n 1)"
                    [ -n "${'$'}name" ] && [ -n "${'$'}exec" ] || continue
                    printf '%s\t%s\t%s\n' "${'$'}name" "${'$'}exec" "${'$'}f"
                done | sort -f -u | head -n $MAX_APPS
            """.trimIndent()

            val result = runInContainer(containerName, script)
            if (!result.isSuccess) return@withContext emptyList()

            result.out.mapNotNull { line ->
                val fields = line.split('\t', limit = 3)
                if (fields.size < 2) null
                else LinuxDesktopApp(
                    name = fields[0].trim(),
                    exec = sanitizeDesktopExec(fields[1].trim()),
                    desktopFile = fields.getOrElse(2) { "" }.trim(),
                ).takeIf { it.name.isNotBlank() && it.exec.isNotBlank() }
            }.distinctBy { it.name.lowercase() to it.exec }
        }

    suspend fun launchApp(
        containerName: String,
        app: LinuxDesktopApp,
    ): Result<AnlandAppSession> = withContext(Dispatchers.IO) {
        try {
            require(app.exec.isNotBlank()) { "Empty application command" }

            val id = "app-" + UUID.randomUUID().toString().substring(0, 8)
            val binary = Constants.DROIDSPACES_BINARY_PATH
            val qName = ContainerCommandBuilder.quote(containerName)
            val qId = ContainerCommandBuilder.quote(id)

            val start = Shell.cmd(
                "$binary --name=$qName anland-session start $qId 2>&1"
            ).exec()
            if (!start.isSuccess) {
                val detail = (start.out + start.err).joinToString("\n").trim()
                return@withContext Result.failure(
                    IllegalStateException(detail.ifBlank { "Failed to create Anland app session" })
                )
            }

            val hostSocket = (start.out + start.err)
                .asReversed()
                .map { it.trim() }
                .firstOrNull { it.startsWith("/") && it.endsWith(".sock") }
                ?: return@withContext Result.failure(
                    IllegalStateException("Anland session started but no socket path was returned")
                )

            val user = detectDefaultUser(containerName) ?: "root"
            val containerSocket = "/run/droidspaces-anland/app-$id.sock"
            val appCommand = sanitizeDesktopExec(app.exec)
            val qAppCommand = ContainerCommandBuilder.quote(appCommand)

            val sessionScript = """
                export XDG_RUNTIME_DIR="/run/user/${'$'}(id -u)"
                mkdir -p "${'$'}XDG_RUNTIME_DIR"
                chmod 0700 "${'$'}XDG_RUNTIME_DIR"
                unset DISPLAY
                export ANLAND_SOCKET="$containerSocket"
                export ANLAND=1
                export ANLAND_DRM_DEVICE=/dev/dri/renderD128
                export MESA_LOADER_DRIVER_OVERRIDE=kgsl
                export GALLIUM_DRIVER=kgsl
                export FD_FORCE_KGSL=1
                export QT_QPA_PLATFORM=wayland
                export GDK_BACKEND=wayland,x11
                export MOZ_ENABLE_WAYLAND=1
                exec dbus-run-session kwin_wayland --anland --xwayland --exit-with-session sh -lc $qAppCommand
            """.trimIndent()

            val runCommand = buildString {
                append(binary)
                append(" --name=").append(qName)
                append(" --user=").append(ContainerCommandBuilder.quote(user))
                append(" run sh -lc ")
                append(ContainerCommandBuilder.quote(sessionScript))
            }
            val stopCommand =
                "$binary --name=$qName anland-session stop $qId"
            val logPath = "/data/local/tmp/droidspaces-anland-$id.log"

            // Detach all file descriptors so libsu does not wait for the GUI.
            val wrapper = "( $runCommand; $stopCommand ) >" +
                ContainerCommandBuilder.quote(logPath) +
                " 2>&1 </dev/null &"
            val launched = Shell.cmd(wrapper).exec()
            if (!launched.isSuccess) {
                Shell.cmd(stopCommand).exec()
                val detail = (launched.out + launched.err).joinToString("\n").trim()
                return@withContext Result.failure(
                    IllegalStateException(detail.ifBlank { "Failed to launch Linux application" })
                )
            }

            Result.success(AnlandAppSession(id, hostSocket))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun stopSession(containerName: String, sessionId: String) =
        withContext(Dispatchers.IO) {
            val binary = Constants.DROIDSPACES_BINARY_PATH
            Shell.cmd(
                "$binary --name=${ContainerCommandBuilder.quote(containerName)} " +
                    "anland-session stop ${ContainerCommandBuilder.quote(sessionId)}"
            ).exec()
        }

    private suspend fun detectDefaultUser(containerName: String): String? {
        val script =
            "getent passwd 2>/dev/null | awk -F: '\\$3 >= 1000 && \\$3 < 65534 && \\$7 !~ /(nologin|false)/ { print \\$1; exit }'"
        val result = runInContainer(containerName, script)
        return if (result.isSuccess) result.out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        else null
    }

    private fun runInContainer(containerName: String, script: String): Shell.Result {
        val binary = Constants.DROIDSPACES_BINARY_PATH
        val command = "$binary --name=${ContainerCommandBuilder.quote(containerName)} " +
            "run sh -lc ${ContainerCommandBuilder.quote(script)}"
        return Shell.cmd(command).exec()
    }

    private fun sanitizeDesktopExec(raw: String): String {
        return raw
            .replace("%%", "__ANLAND_PERCENT__")
            .replace(Regex("""\s*%[fFuUdDnNickvm]"""), "")
            .replace("__ANLAND_PERCENT__", "%")
            .trim()
    }
}
