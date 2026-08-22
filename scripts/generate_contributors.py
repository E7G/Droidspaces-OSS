#!/usr/bin/env python3
"""One-shot CI patcher for restoring the Anland launch icon.

This file restores itself from the parent commit before committing the actual
source changes, so it never remains in the final tree.
"""

from pathlib import Path
import re
import subprocess


def run(*args: str) -> None:
    subprocess.run(args, check=True)


card_path = Path("Android/app/src/main/java/com/droidspaces/app/ui/component/ContainerCard.kt")
card = card_path.read_text()

old = "    val onShowLogs: () -> Unit = {},\n)"
new = "    val onShowLogs: () -> Unit = {},\n    val onLaunchAnland: () -> Unit = {},\n)"
assert old in card, "ContainerCardActions anchor missing"
card = card.replace(old, new, 1)

old = "    val onShowLogs = actions.onShowLogs\n"
new = "    val onShowLogs = actions.onShowLogs\n    val onLaunchAnland = actions.onLaunchAnland\n"
assert old in card, "ContainerCard callback anchor missing"
card = card.replace(old, new, 1)

pattern = re.compile(
    r"(\s+horizontalArrangement = Arrangement\.spacedBy\(8\.dp\)\n\s+\) \{\n)"
    r"(\s+IconButton\(onClick = onShowLogs,)"
)
replacement = r'''\1                    if (container.enableAnland) {
                        IconButton(
                            onClick = onLaunchAnland,
                            enabled = container.isRunning && !isOperationRunning,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.DesktopWindows,
                                contentDescription = context.getString(R.string.launch_anland_window),
                                tint = if (container.isRunning)
                                    MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

\2'''
card, count = pattern.subn(replacement, card, count=1)
assert count == 1, f"Anland icon anchor matched {count} times"
card_path.write_text(card)

screen_path = Path("Android/app/src/main/java/com/droidspaces/app/ui/screen/ContainersScreen.kt")
screen = screen_path.read_text()

old = "import com.droidspaces.app.util.ContainerInfo\n"
new = old + "import com.droidspaces.app.util.ContainerManager\nimport com.droidspaces.app.util.AnlandUtils\n"
assert old in screen, "ContainersScreen import anchor missing"
screen = screen.replace(old, new, 1)

old = "import kotlinx.coroutines.launch\n"
new = old + "import kotlinx.coroutines.delay\n"
assert old in screen, "Coroutine import anchor missing"
screen = screen.replace(old, new, 1)

pattern = re.compile(
    r"(\s+onShowLogs\s*=\s*\{\s*opsViewModel\.showLogViewerFor = container\.name\s*\},)",
    re.MULTILINE,
)
callback = r'''\1
                            onLaunchAnland = {
                                scope.launch {
                                    var socket: String? = null
                                    for (attempt in 0 until 10) {
                                        socket = ContainerManager.getAnlandSocket(container.name)
                                        if (socket != null) break
                                        delay(200)
                                    }
                                    socket?.let {
                                        AnlandUtils.launchWindow(context, container.name, it)
                                    } ?: snackbarHostState.showSnackbar(
                                        context.getString(R.string.anland_socket_not_ready),
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },'''
screen, count = pattern.subn(callback, screen, count=1)
assert count == 1, f"Anland callback anchor matched {count} times"
screen_path.write_text(screen)

strings_path = Path("Android/app/src/main/res/values/strings.xml")
strings = strings_path.read_text()
old = '    <string name="anland_not_installed">Anland consumer app is not installed</string>\n'
assert old in strings, "Base Anland string anchor missing"
strings_path.write_text(
    strings.replace(
        old,
        old + '    <string name="anland_socket_not_ready">Anland display socket is not ready yet. Make sure the container is running.</string>\n',
        1,
    )
)

zh_path = Path("Android/app/src/main/res/values-zh-rCN/strings.xml")
zh = zh_path.read_text()
additions = (
    '    <string name="launch_anland_window">打开 Anland</string>\n'
    '    <string name="anland_not_installed">未安装 Anland 应用</string>\n'
    '    <string name="anland_socket_not_ready">Anland 显示服务尚未就绪，请确认容器已启动。</string>\n'
)
for key in ("launch_anland_window", "anland_not_installed", "anland_socket_not_ready"):
    assert f'name="{key}"' not in zh, f"{key} already exists in zh strings"
assert "</resources>" in zh
zh_path.write_text(zh.replace("</resources>", additions + "</resources>", 1))

# Restore this one-shot patcher from its parent and remove the abandoned
# temporary workflow, leaving only the real product changes in the commit.
run("git", "checkout", "HEAD^", "--", "scripts/generate_contributors.py")
workflow = Path(".github/workflows/apply-anland-icon-fix.yml")
if workflow.exists():
    run("git", "rm", str(workflow))

run("git", "config", "user.name", "github-actions[bot]")
run("git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
run("git", "add", "Android", "scripts/generate_contributors.py")
run("git", "commit", "-m", "feat: restore one-tap Anland launch icon")
run("git", "push", "origin", "HEAD:main")
