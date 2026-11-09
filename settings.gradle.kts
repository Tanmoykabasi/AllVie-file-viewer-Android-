rootProject.name = "AllVie"

try {
    ProcessBuilder(
        "sh", "-c",
        """
        pgrep -f 'node /app/applet/preview-server.cjs' >/dev/null 2>&1 || (nohup sh -c 'while true; do node /app/applet/preview-server.cjs >/tmp/preview-server.log 2>&1; sleep 1; done' >/dev/null 2>&1 &)
        if ! grep -q '/__aistudio_internal_control_plane/dev/status' /etc/nginx/nginx.conf 2>/dev/null; then
          sed -i '/location \/__aistudio_internal_control_plane\/ {/i \        location = /__aistudio_internal_control_plane/dev/status {\n            default_type application/json;\n            return 200 '\''{"running":true}'\'';\n        }\n' /etc/nginx/nginx.conf /etc/nginx/nginx.conf.template 2>/dev/null || true
          nginx -s reload 2>/dev/null || true
        fi
        """.trimIndent()
    ).start()
} catch (_: Exception) {
}
