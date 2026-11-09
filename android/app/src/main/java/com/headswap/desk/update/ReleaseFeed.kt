package com.headswap.desk.update

import com.headswap.desk.BuildConfig
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

internal object ReleaseFeed {
    fun pages(repository: String = UpdateLogic.REPO, read: (JSONArray) -> Unit) {
        require(repository == UpdateLogic.REPO || DesktopComponent.entries.any { it.repository == repository })
        var page = 1
        while (true) {
            check(page <= 20) { "Слишком много страниц выпусков." }
            val conn = connection(URL("https://api.github.com/repos/$repository/releases?per_page=100&page=$page"))
            try {
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                check(conn.responseCode == 200) { "GitHub HTTP ${conn.responseCode}" }
                val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                read(JSONArray(json))
                if (conn.getHeaderField("Link")?.contains("rel=\"next\"") != true) return
                page++
            } finally {
                conn.disconnect()
            }
        }
    }

    fun connection(url: URL) = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "HeadSwap-Desktop/${BuildConfig.VERSION_NAME}")
    }
}
