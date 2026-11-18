package com.sunli.tvlibrary.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale

data class UploadState(
    val running: Boolean = false,
    val url: String = "",
    val target: String = "",
    val count: Int = 0,
    val error: String? = null
)

/**
 * 极简局域网文件上传服务：电脑浏览器打开 html 后，逐文件 PUT 原始字节到 /upload。
 * 只依赖 ServerSocket，不引入额外依赖。
 */
class UploadServer(
    private val context: Context,
    private val onUploaded: () -> Unit
) {

    private val _state = MutableStateFlow(UploadState())
    val state: StateFlow<UploadState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (serverSocket != null) return
        val dir = uploadDir()
        if (!dir.isDirectory && !dir.mkdirs()) {
            _state.value = UploadState(error = "无法创建上传目录，请检查存储权限")
            return
        }
        val socket = (PORT..PORT + 10).firstNotNullOfOrNull { port ->
            runCatching { ServerSocket(port) }.getOrNull()
        }
        if (socket == null) {
            _state.value = UploadState(error = "端口被占用，无法启动上传服务")
            return
        }
        serverSocket = socket
        val ip = localIpv4()
        if (ip == null) {
            socket.close()
            serverSocket = null
            _state.value = UploadState(error = "没有连接到局域网，请先连接 Wi-Fi")
            return
        }
        _state.value = UploadState(
            running = true,
            url = "http://$ip:${socket.localPort}",
            target = dir.absolutePath
        )
        acceptThread = Thread({
            while (serverSocket === socket && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread { runCatching { handle(client, dir) } }.apply {
                    isDaemon = true
                    start()
                }
            }
        }, "upload-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        runCatching { socket.close() }
        acceptThread = null
        _state.update { it.copy(running = false, url = "") }
    }

    private fun uploadDir(): File {
        val root = MediaScanner(context).storageRoots()
            .firstOrNull { !it.absolutePath.startsWith("/storage/emulated") }
            ?: Environment.getExternalStorageDirectory()
        val dir = File(root, "私影上传")
        if (!dir.exists()) dir.mkdirs()
        return if (dir.isDirectory) dir else File(context.filesDir, "uploads").apply { mkdirs() }
    }

    private fun handle(client: Socket, dir: File) {
        client.use { socket ->
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            var contentLength = 0L
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase(Locale.ROOT)
                if (lower.startsWith("content-length:")) {
                    contentLength = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
            }
            val output = socket.getOutputStream()
            when {
                method == "GET" && (target == "/" || target.startsWith("/?")) ->
                    respond(output, "text/html; charset=utf-8", page().toByteArray())

                method == "PUT" && target.startsWith("/upload") -> {
                    val name = queryParam(target, "name")
                    if (name.isNullOrBlank() || contentLength <= 0L) {
                        respond(output, "text/plain; charset=utf-8", "缺少文件名".toByteArray(), 400)
                        return
                    }
                    val saved = receiveFile(input, dir, name, contentLength)
                    if (saved != null) {
                        _state.update { it.copy(count = it.count + 1, error = null) }
                        onUploaded()
                        respond(
                            output,
                            "text/plain; charset=utf-8",
                            saved.name.toByteArray()
                        )
                    } else {
                        respond(output, "text/plain; charset=utf-8", "写入失败".toByteArray(), 500)
                    }
                }

                else -> respond(output, "text/plain; charset=utf-8", "not found".toByteArray(), 404)
            }
        }
    }

    private fun receiveFile(
        input: BufferedInputStream,
        dir: File,
        rawName: String,
        length: Long
    ): File? {
        val safe = sanitize(rawName)
        if (safe.isEmpty()) return null
        var file = File(dir, safe)
        var index = 1
        while (file.exists()) {
            val base = safe.substringBeforeLast('.', safe)
            val ext = safe.substringAfterLast('.', "")
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            file = File(dir, "$base($index)$suffix")
            index++
        }
        return runCatching {
            FileOutputStream(file).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
            file.takeIf { it.exists() && it.length() == length }
        }.getOrNull()
    }

    private fun sanitize(raw: String): String {
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[\u0000-\u001f<>:"|?*]"""), "")
            .trim()
            .take(120)
    }

    private fun queryParam(target: String, key: String): String? {
        val query = target.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&').firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
    }

    private fun readLine(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return builder.takeIf { it.isNotEmpty() }?.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) builder.append(b.toChar())
        }
        return builder.toString()
    }

    private fun respond(
        output: OutputStream,
        contentType: String,
        body: ByteArray,
        code: Int = 200
    ) {
        val header = buildString {
            append("HTTP/1.1 $code ").append(if (code == 200) "OK" else "Error").append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun page(): String = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>私影 · 上传文件</title>
<style>
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100vh; background: #0b0d11; color: #f2f3f5;
         font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; }
  .wrap { max-width: 760px; margin: 0 auto; padding: 48px 24px 64px; }
  h1 { font-size: 26px; margin: 0 0 6px; }
  .sub { color: #9aa3af; font-size: 13px; margin-bottom: 28px; }
  #drop { border: 2px dashed #3a434f; border-radius: 16px; padding: 56px 24px;
          text-align: center; transition: .15s; cursor: pointer; }
  #drop.hover { border-color: #ffffff; background: #171c23; }
  #drop b { font-size: 17px; display: block; margin-bottom: 8px; }
  #drop span { color: #9aa3af; font-size: 13px; }
  .list { margin-top: 26px; display: flex; flex-direction: column; gap: 10px; }
  .item { background: #151a21; border: 1px solid #232a33; border-radius: 12px;
          padding: 12px 16px; }
  .row { display: flex; justify-content: space-between; gap: 16px; font-size: 13px; }
  .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .stat { color: #9aa3af; white-space: nowrap; }
  .stat.ok { color: #7bd88f; }
  .stat.err { color: #ff6b6b; }
  .bar { height: 4px; background: #232a33; border-radius: 4px; margin-top: 9px; overflow: hidden; }
  .bar > i { display: block; height: 100%; width: 0; background: #dde3ea; transition: width .2s; }
  .hint { color: #9aa3af; font-size: 12px; margin-top: 18px; }
  #count { color: #f2f3f5; font-weight: 600; }
</style>
</head>
<body>
<div class="wrap">
  <h1>传文件到私影影库</h1>
  <div class="sub">视频和照片会直接写入硬盘的「私影上传」文件夹，传完后电视端会自动刷新资源库。</div>
  <div id="drop">
    <b>拖拽文件到这里</b>
    <span>或点击选择文件（支持多选，可多次上传）</span>
  </div>
  <input id="picker" type="file" multiple hidden>
  <div class="list" id="list"></div>
  <div class="hint">本次已上传 <span id="count">0</span> 个文件</div>
</div>
<script>
var drop = document.getElementById('drop');
var picker = document.getElementById('picker');
var list = document.getElementById('list');
var countEl = document.getElementById('count');
var done = 0;

drop.addEventListener('click', function () { picker.click(); });
drop.addEventListener('dragover', function (e) { e.preventDefault(); drop.classList.add('hover'); });
drop.addEventListener('dragleave', function () { drop.classList.remove('hover'); });
drop.addEventListener('drop', function (e) {
  e.preventDefault();
  drop.classList.remove('hover');
  upload(e.dataTransfer.files);
});
picker.addEventListener('change', function () {
  upload(picker.files);
  picker.value = '';
});

function upload(files) {
  for (var i = 0; i < files.length; i++) send(files[i]);
}

function formatSize(bytes) {
  if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(2) + ' GB';
  if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + ' MB';
  return Math.max(1, Math.round(bytes / 1024)) + ' KB';
}

function send(file) {
  var item = document.createElement('div');
  item.className = 'item';
  var row = document.createElement('div');
  row.className = 'row';
  var name = document.createElement('div');
  name.className = 'name';
  name.textContent = file.name;
  var stat = document.createElement('div');
  stat.className = 'stat';
  stat.textContent = formatSize(file.size) + ' · 等待中';
  row.appendChild(name);
  row.appendChild(stat);
  var bar = document.createElement('div');
  bar.className = 'bar';
  var fill = document.createElement('i');
  bar.appendChild(fill);
  item.appendChild(row);
  item.appendChild(bar);
  list.appendChild(item);

  var xhr = new XMLHttpRequest();
  xhr.open('PUT', '/upload?name=' + encodeURIComponent(file.name));
  xhr.upload.onprogress = function (e) {
    if (!e.lengthComputable) return;
    var pct = Math.round(e.loaded / e.total * 100);
    fill.style.width = pct + '%';
    stat.textContent = formatSize(file.size) + ' · ' + pct + '%';
  };
  xhr.onload = function () {
    if (xhr.status === 200) {
      fill.style.width = '100%';
      stat.className = 'stat ok';
      stat.textContent = formatSize(file.size) + ' · 已上传';
      done++;
      countEl.textContent = done;
    } else {
      stat.className = 'stat err';
      stat.textContent = '上传失败：' + xhr.responseText;
    }
  };
  xhr.onerror = function () {
    stat.className = 'stat err';
    stat.textContent = '网络错误，请确认电视和电脑在同一 Wi-Fi';
  };
  xhr.send(file);
}
</script>
</body>
</html>
""".trimIndent()

    companion object {
        private const val PORT = 8899
    }
}
