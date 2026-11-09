package com.bladeblack88.matrixplayer

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.*
import android.content.res.ColorStateList
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class TvChannel(val title:String, val url:String, val group:String = "Все")

class MainActivity : Activity() {
    private val api = "https://iptv.matrix.online/server/load.php"
    private val navy = Color.rgb(25,36,57)
    private val blue = Color.rgb(83,151,235)
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private val channels = mutableListOf<TvChannel>()
    private var selected: TvChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        buildUi()
        loadChannels()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(navy); setPadding(24,18,24,18) }
        val header = TextView(this).apply { text="MATRIX  •  TV"; textSize=25f; setTextColor(Color.WHITE); setPadding(8,8,8,18) }
        root.addView(header)
        status = TextView(this).apply { text="Подключение к Matrix…"; textSize=16f; setTextColor(0xffb9c9df.toInt()); setPadding(8,4,8,12) }
        root.addView(status)
        playerView = PlayerView(this).apply { useController=true; setBackgroundColor(Color.BLACK); visibility=View.GONE }
        root.addView(playerView, LinearLayout.LayoutParams(-1,0,1f))
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        val reload = Button(this).apply { text="Обновить каналы"; setTextColor(Color.WHITE); backgroundTintList=ColorStateList.valueOf(blue); setOnClickListener { loadChannels() } }
        root.addView(reload)
        setContentView(root)
    }

    private fun loadChannels() {
        status.text = "Получаем список каналов Matrix…"
        thread {
            try {
                val conn = (URL("$api?type=itv&action=get_all_channels&JsHttpRequest=1-xml").openConnection() as HttpURLConnection).apply {
                    connectTimeout=12000; readTimeout=20000; requestMethod="GET"
                    setRequestProperty("Accept","application/json")
                    setRequestProperty("User-Agent","MatrixPlayer/1.0 (Android TV)")
                }
                val code=conn.responseCode
                val body=(if(code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
                conn.disconnect()
                if(code !in 200..299) throw Exception("HTTP $code")
                val parsed=parseChannels(body)
                runOnUiThread {
                    channels.clear(); channels.addAll(parsed)
                    status.text=if(parsed.isEmpty()) "Сервер ответил, но формат списка каналов пока не распознан." else "Каналов: ${parsed.size}"
                    renderChannels()
                }
            } catch(e:Exception) {
                runOnUiThread { status.text="Не удалось получить каналы Matrix: ${e.message}. Проверь доступ к сети/API." }
            }
        }
    }

    private fun parseChannels(body:String): List<TvChannel> {
        val out = mutableListOf<TvChannel>()
        val seen = mutableSetOf<String>()
        fun field(o: JSONObject, vararg keys: String): String {
            for (key in keys) {
                val v=o.opt(key)
                if (v is String && v.isNotBlank() && v!="null") return v
                if (v is Number) return v.toString()
            }
            return ""
        }
        fun add(o: JSONObject) {
            val title=field(o,"title","name","channel_name","tv_name","channelTitle")
            var stream=field(o,"streamUrl","stream_url","url","cmd","direct_source")
            val p=stream.indexOf("http")
            if(p>0) stream=stream.substring(p).substringBefore(" ")
            val group=field(o,"genre_name","category","group","category_name").ifBlank { "Все" }
            if(title.isNotBlank() && stream.startsWith("http") && seen.add(stream)) out.add(TvChannel(title,stream,group))
        }
        fun walk(value:Any?) {
            when(value) {
                is JSONObject -> {
                    add(value)
                    val keys=value.keys()
                    while(keys.hasNext()) {
                        val child=value.opt(keys.next())
                        if(child is JSONObject || child is JSONArray) walk(child)
                    }
                }
                is JSONArray -> for(i in 0 until value.length()) walk(value.opt(i))
            }
        }
        val raw=body.trim().removePrefix("\uFEFF")
        when {
            raw.startsWith("[") -> walk(JSONArray(raw))
            raw.startsWith("{") -> walk(JSONObject(raw))
            else -> {
                val first=raw.indexOf("{")
                val last=raw.lastIndexOf("}")
                if(first>=0 && last>first) walk(JSONObject(raw.substring(first,last+1)))
            }
        }
        return out
    }

    private fun renderChannels() {
        list.removeAllViews()
        channels.forEachIndexed { index,ch ->
            val b=Button(this).apply {
                text="${index+1}.  ${ch.title}"
                textSize=18f; isAllCaps=false; setTextColor(Color.WHITE)
                backgroundTintList=ColorStateList.valueOf(if(selected==ch) 0xff315d91.toInt() else 0xff26364f.toInt())
                setOnClickListener { play(ch) }
                setOnFocusChangeListener { v,has -> if(has) v.setBackgroundColor(0xff315d91.toInt()) else v.backgroundTintList=ColorStateList.valueOf(0xff26364f.toInt()) }
            }
            list.addView(b, LinearLayout.LayoutParams(-1,64).apply { bottomMargin=7 })
        }
    }

    private fun play(ch:TvChannel) {
        selected=ch
        if(player==null) player=ExoPlayer.Builder(this).build().also { playerView.player=it }
        playerView.visibility=View.VISIBLE
        player?.setMediaItem(MediaItem.fromUri(ch.url))
        player?.prepare()
        player?.play()
        status.text="Сейчас: ${ch.title}"
    }

    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean {
        if(keyCode==KeyEvent.KEYCODE_BACK && playerView.visibility==View.VISIBLE) {
            player?.pause(); playerView.visibility=View.GONE; return true
        }
        return super.onKeyDown(keyCode,event)
    }

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() { player?.release(); player=null; super.onDestroy() }
}