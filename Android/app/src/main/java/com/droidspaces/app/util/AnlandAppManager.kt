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
                export XDG_RUNTIME_DIR="${'
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

    /**
     * V2 shared-compositor mode. One KWin instance hosts many Linux apps while
     * ANLAND_MULTIWINDOW publishes KWin's top-level tree to the Android consumer.
     */
    suspend fun launchSharedApp(
        containerName: String,
        app: LinuxDesktopApp,
    ): Result<AnlandAppSession> = withContext(Dispatchers.IO) {
        try {
            require(app.exec.isNotBlank()) { "Empty application command" }
            val session = ensureSharedSession(containerName).getOrThrow()
            val user = detectDefaultUser(containerName) ?: "root"
            val binary = Constants.DROIDSPACES_BINARY_PATH
            val qName = ContainerCommandBuilder.quote(containerName)
            val qUser = ContainerCommandBuilder.quote(user)
            val appCommand = sanitizeDesktopExec(app.exec)

            val launchScript = """
                export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-${'$'}HOME/.local/run/anland-${'$'}(id -u)}"
                env_file="${'$'}XDG_RUNTIME_DIR/droidspaces-wslg-v2.env"
                for i in ${'$'}(seq 1 100); do
                    [ -s "${'$'}env_file" ] && break
                    sleep 0.1
                done
                [ -s "${'$'}env_file" ] || {
                    echo "WSLg V2 compositor did not publish its Wayland environment" >&2
                    exit 1
                }
                . "${'$'}env_file"
                exec sh -lc ${ContainerCommandBuilder.quote(appCommand)}
            """.trimIndent()

            val runCommand =
                "$binary --name=$qName --user=$qUser run sh -lc " +
                    ContainerCommandBuilder.quote(launchScript)
            val logPath = "/data/local/tmp/droidspaces-wslg-v2-app.log"
            val launched = Shell.cmd(
                "$runCommand >>${ContainerCommandBuilder.quote(logPath)} 2>&1 </dev/null &"
            ).exec()
            if (!launched.isSuccess) {
                val detail = (launched.out + launched.err).joinToString("\n").trim()
                return@withContext Result.failure(
                    IllegalStateException(detail.ifBlank { "Failed to launch app in shared WSLg session" })
                )
            }
            Result.success(session)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun ensureSharedSession(
        containerName: String,
    ): Result<AnlandAppSession> = withContext(Dispatchers.IO) {
        try {
            val id = "wslg-v2"
            val binary = Constants.DROIDSPACES_BINARY_PATH
            val qName = ContainerCommandBuilder.quote(containerName)
            val qId = ContainerCommandBuilder.quote(id)

            val broker = Shell.cmd(
                "$binary --name=$qName anland-session start $qId 2>&1"
            ).exec()
            if (!broker.isSuccess) {
                val detail = (broker.out + broker.err).joinToString("\n").trim()
                return@withContext Result.failure(
                    IllegalStateException(detail.ifBlank { "Failed to create shared Anland broker" })
                )
            }
            val hostSocket = (broker.out + broker.err)
                .asReversed()
                .map { it.trim() }
                .firstOrNull { it.startsWith("/") && it.endsWith(".sock") }
                ?: return@withContext Result.failure(
                    IllegalStateException("Shared broker started but returned no socket path")
                )

            val user = detectDefaultUser(containerName) ?: "root"
            val qUser = ContainerCommandBuilder.quote(user)
            val containerSocket = "/run/droidspaces-anland/app-$id.sock"

            val checkScript = """
                export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-${'$'}HOME/.local/run/anland-${'$'}(id -u)}"
                pid_file="${'$'}XDG_RUNTIME_DIR/droidspaces-wslg-v2.pid"
                [ -s "${'$'}pid_file" ] || exit 1
                pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null)" || exit 1
                kill -0 "${'$'}pid" 2>/dev/null
            """.trimIndent()
            val check = Shell.cmd(
                "$binary --name=$qName --user=$qUser run sh -lc " +
                    ContainerCommandBuilder.quote(checkScript)
            ).exec()
            if (check.isSuccess) {
                return@withContext Result.success(AnlandAppSession(id, hostSocket))
            }

            val innerSession = """
                kwin_wayland --anland --xwayland &
                kwin_pid=${'$'}!
                printf '%s\n' "${'$'}kwin_pid" > "${'$'}XDG_RUNTIME_DIR/droidspaces-wslg-v2.pid"
                ready=0
                for i in ${'$'}(seq 1 100); do
                    for wl in "${'$'}XDG_RUNTIME_DIR"/wayland-*; do
                        [ -S "${'$'}wl" ] || continue
                        case "${'$'}wl" in *.lock) continue ;; esac
                        wayland_display="${'$'}(basename "${'$'}wl")"
                        {
                            printf "export XDG_RUNTIME_DIR='%s'\n" "${'$'}XDG_RUNTIME_DIR"
                            printf "export WAYLAND_DISPLAY='%s'\n" "${'$'}wayland_display"
                            printf "export DBUS_SESSION_BUS_ADDRESS='%s'\n" "${'$'}DBUS_SESSION_BUS_ADDRESS"
                            printf "export QT_QPA_PLATFORM='wayland'\n"
                            printf "export GDK_BACKEND='wayland,x11'\n"
                            printf "export MOZ_ENABLE_WAYLAND='1'\n"
                        } > "${'$'}XDG_RUNTIME_DIR/droidspaces-wslg-v2.env"
                        chmod 0600 "${'$'}XDG_RUNTIME_DIR/droidspaces-wslg-v2.env"
                        ready=1
                        break 2
                    done
                    kill -0 "${'$'}kwin_pid" 2>/dev/null || break
                    sleep 0.1
                done
                if [ "${'$'}ready" -ne 1 ]; then
                    kill "${'$'}kwin_pid" 2>/dev/null || true
                    wait "${'$'}kwin_pid" 2>/dev/null || true
                    exit 1
                fi
                wait "${'$'}kwin_pid"
            """.trimIndent()

            val compositorScript = """
                set -eu
                export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-${'$'}HOME/.local/run/anland-${'$'}(id -u)}"
                mkdir -p "${'$'}XDG_RUNTIME_DIR"
                chmod 0700 "${'$'}XDG_RUNTIME_DIR"
                rm -f "${'$'}XDG_RUNTIME_DIR/droidspaces-wslg-v2.env" \
                      "${'$'}XDG_RUNTIME_DIR/droidspaces-wslg-v2.pid"
                unset DISPLAY
                export ANLAND_SOCKET="$containerSocket"
                export ANLAND=1
                export ANLAND_MULTIWINDOW=1
                export ANLAND_DRM_DEVICE=/dev/dri/renderD128
                export MESA_LOADER_DRIVER_OVERRIDE=kgsl
                export GALLIUM_DRIVER=kgsl
                export FD_FORCE_KGSL=1
                export QT_QPA_PLATFORM=wayland
                export GDK_BACKEND=wayland,x11
                export MOZ_ENABLE_WAYLAND=1
                exec dbus-run-session sh -lc ${ContainerCommandBuilder.quote(innerSession)}
            """.trimIndent()

            val runCommand =
                "$binary --name=$qName --user=$qUser run sh -lc " +
                    ContainerCommandBuilder.quote(compositorScript)
            val stopCommand = "$binary --name=$qName anland-session stop $qId"
            val logPath = "/data/local/tmp/droidspaces-wslg-v2.log"
            val wrapper =
                "( $runCommand; $stopCommand ) >" +
                    ContainerCommandBuilder.quote(logPath) +
                    " 2>&1 </dev/null &"
            val started = Shell.cmd(wrapper).exec()
            if (!started.isSuccess) {
                Shell.cmd(stopCommand).exec()
                val detail = (started.out + started.err).joinToString("\n").trim()
                return@withContext Result.failure(
                    IllegalStateException(detail.ifBlank { "Failed to start shared KWin compositor" })
                )
            }

            Result.success(AnlandAppSession(id, hostSocket))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun stopSharedSession(containerName: String) = withContext(Dispatchers.IO) {
        val user = detectDefaultUser(containerName) ?: "root"
        val binary = Constants.DROIDSPACES_BINARY_PATH
        val qName = ContainerCommandBuilder.quote(containerName)
        val qUser = ContainerCommandBuilder.quote(user)
        val stopScript = """
            export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-${'$'}HOME/.local/run/anland-${'$'}(id -u)}"
            pid_file="${'$'}XDG_RUNTIME_DIR/droidspaces-wslg-v2.pid"
            if [ -s "${'$'}pid_file" ]; then
                pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null || true)"
                [ -n "${'$'}pid" ] && kill "${'$'}pid" 2>/dev/null || true
            fi
            rm -f "${'$'}XDG_RUNTIME_DIR/droidspaces-wslg-v2.env" "${'$'}pid_file"
        """.trimIndent()
        Shell.cmd(
            "$binary --name=$qName --user=$qUser run sh -lc " +
                ContainerCommandBuilder.quote(stopScript)
        ).exec()
        stopSession(containerName, "wslg-v2")
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
            "getent passwd 2>/dev/null | awk -F: '\$3 >= 1000 && \$3 < 65534 && \$7 !~ /(nologin|false)/ { print \$1; exit }'"
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
}{XDG_RUNTIME_DIR:-${'
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
            "getent passwd 2>/dev/null | awk -F: '\$3 >= 1000 && \$3 < 65534 && \$7 !~ /(nologin|false)/ { print \$1; exit }'"
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
}HOME/.local/run/anland-${'
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
            "getent passwd 2>/dev/null | awk -F: '\$3 >= 1000 && \$3 < 65534 && \$7 !~ /(nologin|false)/ { print \$1; exit }'"
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
}(id -u)}"
                mkdir -p "${'
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
            "getent passwd 2>/dev/null | awk -F: '\$3 >= 1000 && \$3 < 65534 && \$7 !~ /(nologin|false)/ { print \$1; exit }'"
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
}XDG_RUNTIME_DIR"
                chmod 0700 "${'
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
            "getent passwd 2>/dev/null | awk -F: '\$3 >= 1000 && \$3 < 65534 && \$7 !~ /(nologin|false)/ { print \$1; exit }'"
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
}XDG_RUNTIME_DIR"
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
            "getent passwd 2>/dev/null | awk -F: '\$3 >= 1000 && \$3 < 65534 && \$7 !~ /(nologin|false)/ { print \$1; exit }'"
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
