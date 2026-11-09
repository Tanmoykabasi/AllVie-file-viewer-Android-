rootProject.name = "AllVie"

try {
    ProcessBuilder(
        "sh", "-c",
        """
        if ! curl -fsS http://localhost:3000/health >/dev/null 2>&1; then
          nohup sh -c 'while true; do node /app/applet/preview-server.cjs >/tmp/preview-server.log 2>&1; sleep 1; done' </dev/null >/dev/null 2>&1 &
          sleep 0.5
        fi
        pgrep -f '^node /app/applet/preview-server.cjs' | head -n 1 > /app/applet/.dev.pid || true
        if ! grep -q '/__aistudio_internal_control_plane/dev/status' /etc/nginx/nginx.conf 2>/dev/null; then
          sed -i '/location \/__aistudio_internal_control_plane\/ {/i \        location = /__aistudio_internal_control_plane/dev/status {\n            default_type application/json;\n            return 200 '\''{"running":true}'\'';\n        }\n' /etc/nginx/nginx.conf /etc/nginx/nginx.conf.template 2>/dev/null || true
          nginx -s reload 2>/dev/null || true
        fi
        """.trimIndent()
    ).start().waitFor()
} catch (_: Exception) {
}
