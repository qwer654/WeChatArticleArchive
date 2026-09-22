package com.qwer654.wechatarchive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LoginSession(val uuid: String, val scanUrl: String)
data class WeReadCredential(val vid: String, val token: String, val username: String)
data class MpInfo(val id: String, val name: String, val intro: String)
data class HistoryArticle(val id: String, val title: String, val picUrl: String, val publishTime: Long) {
    val url: String get() = "https://mp.weixin.qq.com/s/" + id
}

class WeReadHistoryClient(context: Context) {
    private val prefs = context.getSharedPreferences("weread_history", Context.MODE_PRIVATE)
    private val baseUrl = "https://weread.111965.xyz"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val loginClient = client.newBuilder()
        .readTimeout(125, TimeUnit.SECONDS)
        .callTimeout(130, TimeUnit.SECONDS)
        .build()

    fun savedCredential(): WeReadCredential? {
        val vid = prefs.getString("vid", null).orEmpty()
        val token = prefs.getString("token", null).orEmpty()
        if (vid.isBlank() || token.isBlank()) return null
        return WeReadCredential(vid, token, prefs.getString("username", "").orEmpty())
    }

    fun clearCredential() {
        prefs.edit().clear().apply()
    }

    fun createLoginSession(): LoginSession {
        val json = JSONObject(get(baseUrl + "/api/v2/login/platform", null, client))
        val uuid = json.optString("uuid")
        val scanUrl = json.optString("scanUrl")
        if (uuid.isBlank() || scanUrl.isBlank()) error("登录服务没有返回二维码")
        return LoginSession(uuid, scanUrl)
    }

    fun completeLogin(uuid: String): WeReadCredential {
        val json = JSONObject(get(baseUrl + "/api/v2/login/platform/" + uuid, null, loginClient))
        val vid = json.opt("vid")?.toString().orEmpty()
        val token = json.optString("token")
        val username = json.optString("username")
        if (vid.isBlank() || token.isBlank()) {
            error(json.optString("message").ifBlank { "尚未确认扫码登录" })
        }
        val credential = WeReadCredential(vid, token, username)
        prefs.edit()
            .putString("vid", credential.vid)
            .putString("token", credential.token)
            .putString("username", credential.username)
            .apply()
        return credential
    }

    fun resolveAccount(sampleArticleUrl: String, credential: WeReadCredential): MpInfo {
        val body = JSONObject().put("url", sampleArticleUrl).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(baseUrl + "/api/v2/platform/wxs2mp")
            .header("xid", credential.vid)
            .header("Authorization", "Bearer " + credential.token)
            .post(body)
            .build()
        val text = execute(request, client)
        val array = JSONArray(text)
        if (array.length() == 0) error("没有识别到公众号")
        val item = array.getJSONObject(0)
        return MpInfo(
            id = item.optString("id"),
            name = item.optString("name"),
            intro = item.optString("intro")
        )
    }

    fun historyPage(mpId: String, page: Int, credential: WeReadCredential): List<HistoryArticle> {
        val request = Request.Builder()
            .url(baseUrl + "/api/v2/platform/mps/" + mpId + "/articles?page=" + page)
            .header("xid", credential.vid)
            .header("Authorization", "Bearer " + credential.token)
            .get()
            .build()
        val array = JSONArray(execute(request, client))
        val out = ArrayList<HistoryArticle>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val id = item.optString("id")
            if (id.isBlank()) continue
            out += HistoryArticle(
                id = id,
                title = item.optString("title"),
                picUrl = item.optString("picUrl"),
                publishTime = item.optLong("publishTime")
            )
        }
        return out
    }

    private fun get(url: String, credential: WeReadCredential?, http: OkHttpClient): String {
        val builder = Request.Builder().url(url).get()
        if (credential != null) {
            builder.header("xid", credential.vid)
            builder.header("Authorization", "Bearer " + credential.token)
        }
        return execute(builder.build(), http)
    }

    private fun execute(request: Request, http: OkHttpClient): String {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 429) error("请求过于频繁，历史同步已停止")
                if (response.code == 401) error("微信读书登录已失效，请重新扫码")
                error("历史服务 HTTP " + response.code)
            }
            return text
        }
    }
}

fun makeQrBitmap(text: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
