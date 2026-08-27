/*
 * Droidspaces - anland display daemon integration
 *
 * Embeds the anland broker daemon (libdisplay_daemon, vendored alongside this
 * file) into the container lifecycle. For a container with anland enabled we:
 *   - generate a per-container host socket path under <workspace>/anland,
 *   - fork a persistent process that runs the daemon's epoll loop on it,
 *   - record the pid and the socket path in the Pids dir so the app can find
 *     and stop it, and
 *   - bind-mount the socket onto the container's /run/display.sock
 * (post-pivot).
 *
 * The Android consumer app connects to the same socket (via its root fd-helper)
 * and a patched KWin ("producer") inside the container connects to
 * /run/display.sock; the daemon just brokers their handshake.
 *
 * Modeled on src/android/x11.c. Unlike X11 (a single global server) each
 * container gets its own daemon, so the pid / socket-path files are keyed by
 * container name.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#define _GNU_SOURCE
#include "droidspace.h"

#include <ctype.h>
#include <dirent.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "display_daemon.h"

#include <sys/mount.h>
#include <sys/stat.h>

/* Host directory for the per-container display sockets: /data/local/tmp, which
 * always exists and is the same directory the consumer app already uses for its
 * default socket, so the consumer can reach it without the permission problems
 * of a workspace-private path. The socket files are named anland-<uuid>.sock
 * directly in this dir (no subdirectory to create). */
#define ANLAND_SOCK_DIR "/data/local/tmp"
#define ANLAND_RUNTIME_DIR "/run/droidspaces-anland"

/* Compatibility path used by older Anland consumers.  New Droidspaces
 * instances use a per-container socket, so this must be kept as a symlink to
 * the currently active daemon instead of being left as a stale socket path. */
#define ANLAND_DEFAULT_SOCK ANLAND_SOCK_DIR "/display_daemon.sock"

/* Per-container Pids-dir filenames (keyed by container name). */
static void anland_pid_file(const struct ds_config *cfg, char *buf, size_t n) {
  snprintf(buf, n, "%s.anland.pid", cfg->container_name);
}
static void anland_sock_file(const struct ds_config *cfg, char *buf, size_t n) {
  snprintf(buf, n, "%s.anland", cfg->container_name);
}

/* A stable, per-container directory is bind-mounted into the container once.
 * New app-mode broker sockets created later become visible there immediately,
 * which lets one running container host many independent Android windows. */
static void anland_session_dir(const struct ds_config *cfg, char *buf, size_t n) {
  char safe[256];
  sanitize_container_name(cfg->container_name, safe, sizeof(safe));
  snprintf(buf, n, ANLAND_SOCK_DIR "/droidspaces-anland-%.240s", safe);
}

static int anland_valid_session_id(const char *id) {
  if (!id || !*id)
    return 0;
  size_t len = strlen(id);
  if (len > 64 || strcmp(id, ".") == 0 || strcmp(id, "..") == 0)
    return 0;
  for (size_t i = 0; i < len; i++) {
    unsigned char ch = (unsigned char)id[i];
    if (!(isalnum(ch) || ch == '-' || ch == '_' || ch == '.'))
      return 0;
  }
  return 1;
}

static void anland_app_pid_file(const struct ds_config *cfg, const char *id,
                                char *buf, size_t n) {
  snprintf(buf, n, "%.200s.anland-app.%.64s.pid", cfg->container_name, id);
}

static void anland_app_socket(const struct ds_config *cfg, const char *id,
                              char *buf, size_t n) {
  char dir[PATH_MAX];
  anland_session_dir(cfg, dir, sizeof(dir));
  snprintf(buf, n, "%s/app-%.64s.sock", dir, id);
}

static int anland_default_alias_target(char *buf, size_t n) {
  ssize_t r = readlink(ANLAND_DEFAULT_SOCK, buf, n - 1);
  if (r < 0)
    return -1;
  buf[r] = '\0';
  return 0;
}

/* Atomically point the legacy consumer path at this container's current
 * per-container socket.  The consumer may reconnect while a container is
 * restarting, so publishing a dangling/old path here creates a reconnect loop
 * that looks like an Anland hang. */
static void anland_update_default_alias(const struct ds_config *cfg) {
  if (!cfg || cfg->anland_sock[0] == '\0')
    return;

  char current[PATH_MAX];
  if (anland_default_alias_target(current, sizeof(current)) == 0 &&
      strcmp(current, cfg->anland_sock) == 0)
    return;

  char tmp[PATH_MAX];
  snprintf(tmp, sizeof(tmp), "%s.tmp.%ld", ANLAND_DEFAULT_SOCK,
           (long)getpid());
  unlink(tmp);
  if (symlink(cfg->anland_sock, tmp) < 0 ||
      rename(tmp, ANLAND_DEFAULT_SOCK) < 0) {
    ds_warn("[Anland] failed to publish compatibility socket %s -> %s: %s",
            ANLAND_DEFAULT_SOCK, cfg->anland_sock, strerror(errno));
    unlink(tmp);
  }
}

static void anland_remove_default_alias(const struct ds_config *cfg) {
  if (!cfg || cfg->anland_sock[0] == '\0')
    return;
  char current[PATH_MAX];
  if (anland_default_alias_target(current, sizeof(current)) == 0 &&
      strcmp(current, cfg->anland_sock) == 0)
    unlink(ANLAND_DEFAULT_SOCK);
}

/* anland is per-container, so a daemon is never shared between containers:
 * ds_global_daemon_stop's "keep alive for others" check is always false. */
static int anland_never_needed(void) { return 0; }

static void ds_anland_sessions_stop_all(struct ds_config *cfg);

/* daemon child */

/*
 * The forked daemon process. Runs the vendored libdisplay_daemon epoll loop
 * in-process (the library is linked into this binary, so no execv is needed).
 * Never returns: always _exit()s.
 */
static void anland_daemon_child(int out_fd, const char *sock_path) {
  /* Detach from the launcher's session and make the daemon robust/persistent
   * (SIGTERM is left default so ds_global_daemon_stop can kill it). */
  setsid();
  signal(SIGHUP, SIG_IGN);
  signal(SIGINT, SIG_IGN);
  signal(SIGQUIT, SIG_IGN);
  signal(SIGPIPE, SIG_IGN);
  ds_oom_protect();

  /* stdout/stderr -> log pipe, stdin -> /dev/null */
  int devnull = open("/dev/null", O_RDONLY);
  if (devnull >= 0) {
    dup2(devnull, STDIN_FILENO);
    close(devnull);
  }
  dup2(out_fd, STDOUT_FILENO);
  dup2(out_fd, STDERR_FILENO);
  close(out_fd);

  daemon_ctx *ctx = NULL;
  if (daemon_create(&ctx, sock_path) < 0) {
    /* stderr is the log relay pipe here, and ds_error bypasses the [Anland]
     * terminal filter, so this still reaches the log. _exit, not ds_die: the
     * forked child must not flush the parent's stdio or run its atexit list. */
    ds_error("[Anland] failed to bind %s", sock_path);
    _exit(1);
  }
  daemon_run(ctx); /* blocks until SIGTERM-triggered exit via the loop flag */
  daemon_destroy(ctx); /* closes clients, unlinks the socket */
  _exit(0);
}

/* Fork the daemon child and a log-relay grandchild. log_path is resolved by
 * ds_spawn_log_relay() relative to get_logs_dir(), so it must be relative
 * (e.g. "<name>/anland") and its parent dir must already exist.
 * Returns the daemon PID, or -1 on error. */
static pid_t spawn_anland_daemon(const char *sock_path, const char *log_path) {
  int pipefd[2];
  if (pipe(pipefd) < 0) {
    ds_warn("[Anland] pipe: %s", strerror(errno));
    return -1;
  }

  pid_t child = fork();
  if (child < 0) {
    ds_warn("[Anland] fork: %s", strerror(errno));
    close(pipefd[0]);
    close(pipefd[1]);
    return -1;
  }
  if (child == 0) {
    close(pipefd[0]);
    anland_daemon_child(pipefd[1], sock_path); /* never returns */
    _exit(1);
  }

  /* Parent: hand the read end to a log relay; the daemon child holds the only
   * write end, so the relay sees EOF when the daemon exits. */
  close(pipefd[1]);
  ds_spawn_log_relay(pipefd[0], log_path, "anland");
  return child;
}

/* Load the recorded per-container socket path (Pids/<name>.anland) into
 * cfg->anland_sock. No-op when the file is missing/empty. Needed because the
 * socket path is runtime-only (not persisted to container.config), so a cfg
 * loaded from disk at stop time has an empty anland_sock. */
static void anland_load_sock(struct ds_config *cfg) {
  char sockfile[NAME_MAX + 16], rec[PATH_MAX];
  anland_sock_file(cfg, sockfile, sizeof(sockfile));
  snprintf(rec, sizeof(rec), "%s/%s", get_pids_dir(), sockfile);
  int fd = open(rec, O_RDONLY | O_CLOEXEC);
  if (fd < 0)
    return;
  ssize_t r = read(fd, cfg->anland_sock, sizeof(cfg->anland_sock) - 1);
  close(fd);
  if (r > 0) {
    cfg->anland_sock[r] = '\0';
    cfg->anland_sock[strcspn(cfg->anland_sock, "\r\n")] = '\0';
  }
}

/* public API */

int ds_anland_daemon_start(struct ds_config *cfg) {
  if (!cfg || !cfg->anland || !is_android())
    return -1;
  if (getuid() != 0) {
    ds_error("[Anland] not running as root");
    return -1;
  }

  char pidfile[NAME_MAX + 16], sockfile[NAME_MAX + 16];
  anland_pid_file(cfg, pidfile, sizeof(pidfile));
  anland_sock_file(cfg, sockfile, sizeof(sockfile));

  /* Reuse an existing live daemon (ds_daemon_read_pid verifies liveness). */
  pid_t existing = ds_daemon_read_pid(pidfile);
  if (existing > 0) {
    cfg->anland_pid = existing;
    anland_load_sock(cfg);
    anland_update_default_alias(cfg);
    ds_log("[Anland] daemon already running (PID %d)", (int)existing);
    return 1;
  }

  /* Keep every broker socket for this container in one stable directory.
   * The directory itself is mounted into the container, so app-mode sessions
   * can be added dynamically without another mount-namespace operation. */
  char session_dir[PATH_MAX];
  anland_session_dir(cfg, session_dir, sizeof(session_dir));
  if (mkdir_p(session_dir, 0755) < 0) {
    ds_error("[Anland] failed to create session directory %s: %s",
             session_dir, strerror(errno));
    return -1;
  }
  chmod(session_dir, 0755);
  snprintf(cfg->anland_sock, sizeof(cfg->anland_sock),
           "%s/desktop.sock", session_dir);

  ds_log("[Anland] launching display daemon on %s", cfg->anland_sock);

  /* ds_spawn_log_relay() resolves its log-file argument relative to
   * get_logs_dir() (== <workspace>/Logs) and only O_CREATs the final path
   * component, so we must (a) pre-create the per-container subdir and (b) pass
   * a RELATIVE "<name>/anland" - passing an absolute path would double-prefix
   * to <Logs>/<Logs>/... whose parents don't exist, and the relay would fail
   * its open() and silently exit without writing anything. */
  char safe_log_name[256];
  sanitize_container_name(cfg->container_name, safe_log_name,
                          sizeof(safe_log_name));

  char log_rel[288];
  snprintf(log_rel, sizeof(log_rel), "%.256s/anland", safe_log_name);

  pid_t child = spawn_anland_daemon(cfg->anland_sock, log_rel);
  if (child <= 0)
    return -1;

  cfg->anland_pid = child;
  ds_daemon_write_pid(pidfile, child);

  /* Record the socket path so the app (and a later restart) can find it. */
  char rec[PATH_MAX];
  snprintf(rec, sizeof(rec), "%s/%s", get_pids_dir(), sockfile);
  write_file_atomic(rec, cfg->anland_sock);

  /* Give the daemon a moment to bind, then loosen the socket perms so the
   * consumer app can connect to it. */
  wait_for_socket_or_death(child, cfg->anland_sock, 2000, 20000);
  if (access(cfg->anland_sock, F_OK) == 0) {
    chmod(cfg->anland_sock, 0666);
    anland_update_default_alias(cfg);
  } else {
    ds_warn("[Anland] daemon socket did not appear at %s", cfg->anland_sock);
  }
  return 0;
}

void ds_anland_daemon_stop(struct ds_config *cfg) {
  if (!cfg)
    return;

  /* App-mode brokers are children of the container lifecycle too. */
  ds_anland_sessions_stop_all(cfg);
  char pidfile[NAME_MAX + 16], sockfile[NAME_MAX + 16];
  anland_pid_file(cfg, pidfile, sizeof(pidfile));
  anland_sock_file(cfg, sockfile, sizeof(sockfile));

  /* Recover the socket path from the Pids file if this cfg was loaded from disk
   * (anland_sock is runtime-only), so ds_global_daemon_stop can unlink it. */
  if (cfg->anland_sock[0] == '\0')
    anland_load_sock(cfg);

  anland_remove_default_alias(cfg);
  ds_global_daemon_stop(anland_never_needed, cfg->anland_pid, &cfg->anland_pid,
                        pidfile, cfg->anland_sock[0] ? cfg->anland_sock : NULL,
                        "[Anland]");
  ds_daemon_remove_pid(sockfile);

  char session_dir[PATH_MAX];
  anland_session_dir(cfg, session_dir, sizeof(session_dir));
  rmdir(session_dir);
}

/* WSLg-style application sessions */

int ds_anland_session_start(struct ds_config *cfg, const char *session_id,
                            char *sock_out, size_t sock_out_size) {
  if (!cfg || !cfg->anland || !is_android() ||
      !anland_valid_session_id(session_id)) {
    errno = EINVAL;
    return -1;
  }
  if (getuid() != 0) {
    errno = EPERM;
    return -1;
  }

  char session_dir[PATH_MAX];
  anland_session_dir(cfg, session_dir, sizeof(session_dir));
  if (mkdir_p(session_dir, 0755) < 0)
    return -1;
  chmod(session_dir, 0755);

  char sock[PATH_MAX];
  anland_app_socket(cfg, session_id, sock, sizeof(sock));

  char pidfile[NAME_MAX + 96];
  anland_app_pid_file(cfg, session_id, pidfile, sizeof(pidfile));

  pid_t existing = ds_daemon_read_pid(pidfile);
  if (existing > 0) {
    if (sock_out && sock_out_size)
      safe_strncpy(sock_out, sock, sock_out_size);
    return 0;
  }

  char safe_name[256];
  sanitize_container_name(cfg->container_name, safe_name, sizeof(safe_name));
  char log_rel[384];
  snprintf(log_rel, sizeof(log_rel), "%.240s/anland-app-%.64s",
           safe_name, session_id);

  unlink(sock);
  pid_t child = spawn_anland_daemon(sock, log_rel);
  if (child <= 0)
    return -1;

  ds_daemon_write_pid(pidfile, child);
  if (wait_for_socket_or_death(child, sock, 2000, 20000) < 0 ||
      access(sock, F_OK) != 0) {
    kill(child, SIGTERM);
    ds_daemon_remove_pid(pidfile);
    unlink(sock);
    errno = EIO;
    return -1;
  }

  chmod(sock, 0666);
  if (sock_out && sock_out_size)
    safe_strncpy(sock_out, sock, sock_out_size);
  ds_log("[Anland-App] session %s ready on %s", session_id, sock);
  return 0;
}

int ds_anland_session_stop(struct ds_config *cfg, const char *session_id) {
  if (!cfg || !anland_valid_session_id(session_id)) {
    errno = EINVAL;
    return -1;
  }

  char pidfile[NAME_MAX + 96];
  anland_app_pid_file(cfg, session_id, pidfile, sizeof(pidfile));
  char sock[PATH_MAX];
  anland_app_socket(cfg, session_id, sock, sizeof(sock));

  pid_t pid = ds_daemon_read_pid(pidfile);
  if (pid > 0) {
    pid_t tracked = pid;
    ds_global_daemon_stop(anland_never_needed, pid, &tracked, pidfile, sock,
                          "[Anland-App]");
  } else {
    ds_daemon_remove_pid(pidfile);
    unlink(sock);
  }
  return 0;
}

static void ds_anland_sessions_stop_all(struct ds_config *cfg) {
  if (!cfg)
    return;

  DIR *dir = opendir(get_pids_dir());
  if (!dir)
    return;

  char prefix[320];
  snprintf(prefix, sizeof(prefix), "%.200s.anland-app.", cfg->container_name);
  size_t prefix_len = strlen(prefix);

  struct dirent *ent;
  while ((ent = readdir(dir)) != NULL) {
    size_t name_len = strlen(ent->d_name);
    if (name_len <= prefix_len + 4 ||
        strncmp(ent->d_name, prefix, prefix_len) != 0 ||
        strcmp(ent->d_name + name_len - 4, ".pid") != 0)
      continue;

    size_t id_len = name_len - prefix_len - 4;
    if (id_len == 0 || id_len > 64)
      continue;

    char id[65];
    memcpy(id, ent->d_name + prefix_len, id_len);
    id[id_len] = '\0';
    if (anland_valid_session_id(id))
      ds_anland_session_stop(cfg, id);
  }
  closedir(dir);
}

/* socket bridge */

int ds_setup_anland_socket(struct ds_config *cfg) {
  if (!cfg || !cfg->anland || !is_android())
    return 0;

  char session_dir[PATH_MAX];
  anland_session_dir(cfg, session_dir, sizeof(session_dir));

  /* Post-pivot: expose the whole per-container session directory. Future
   * app-*.sock entries automatically appear through this one bind mount. */
  char src[PATH_MAX + 32];
  snprintf(src, sizeof(src), "/.old_root%s", session_dir);
  if (access(src, F_OK) != 0) {
    ds_warn("[Anland] host session directory not found at %s", src);
    return 0;
  }

  mkdir_p(ANLAND_RUNTIME_DIR, 0755);
  if (mount(src, ANLAND_RUNTIME_DIR, NULL, MS_BIND | MS_REC, NULL) < 0) {
    ds_warn("[Anland] failed to bind session directory %s -> %s: %s",
            src, ANLAND_RUNTIME_DIR, strerror(errno));
    return 0;
  }

  /* Preserve the producer ABI used by existing KWin/Weston startup scripts. */
  unlink("/run/display.sock");
  if (symlink(ANLAND_RUNTIME_DIR "/desktop.sock", "/run/display.sock") < 0) {
    ds_warn("[Anland] failed to create /run/display.sock compatibility link: %s",
            strerror(errno));
  }

  ds_log("[Anland] session directory mounted at %s", ANLAND_RUNTIME_DIR);
  return 0;
}
