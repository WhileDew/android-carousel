package com.example.carousel

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private val images = mutableListOf<String>()
    private var autoSlideJob: Job? = null
    private lateinit var adapter: ImageSliderAdapter
    private val slideDuration = 300L

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("MainActivity", "onCreate called")
        
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)

        super.onCreate(savedInstanceState)
        
        checkOverlayPermission()

        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)

        setContentView(R.layout.activity_main)
        supportActionBar?.hide()
        hideSystemUI()

        viewPager = findViewById(R.id.viewPager)

        try {
            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, null, null)
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        adapter = ImageSliderAdapter(mutableListOf())
        viewPager.adapter = adapter

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val lastIndex = adapter.itemCount - 1
                if (adapter.itemCount > 0) {
                    when (position) {
                        0 -> postDelayedSwitch(lastIndex - 1)
                        lastIndex -> postDelayedSwitch(1)
                    }
                }
            }
        })

        lifecycleScope.launch {
            loadData()
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "广告机模式：请开启‘显示在其他应用上层’权限", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart called")
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume called")
        hideSystemUI()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "onNewIntent called - Activity already running")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    private fun postDelayedSwitch(targetPosition: Int) {
        viewPager.post {
            viewPager.setCurrentItem(targetPosition, false)
        }
    }

    private fun startAutoSlide() {
        autoSlideJob?.cancel()
        autoSlideJob = lifecycleScope.launch {
            while (isActive) {
                delay(5000)
                smoothScrollToNext()
            }
        }
    }

    private fun smoothScrollToNext() {
        val recyclerView = viewPager.getChildAt(0) as RecyclerView
        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateTimeForScrolling(dx: Int): Int {
                return slideDuration.toInt()
            }
        }
        scroller.targetPosition = viewPager.currentItem + 1
        recyclerView.layoutManager?.startSmoothScroll(scroller)
    }

    private suspend fun loadData() = withContext(Dispatchers.Main) {
        var loadedImages = fetchImages()
        if (loadedImages.isEmpty()) {
            Toast.makeText(this@MainActivity, "正在读取本地缓存…", Toast.LENGTH_SHORT).show()
            loadedImages = loadCachedImages()
        }

        if (loadedImages.isNotEmpty()) {
            val loopedList = mutableListOf<String>()
            loopedList.add(loadedImages.last())
            loopedList.addAll(loadedImages)
            loopedList.add(loadedImages.first())

            images.clear()
            images.addAll(loopedList)
            adapter.updateImages(images)
            viewPager.setCurrentItem(1, false)
            startAutoSlide()
        } else {
            Toast.makeText(this@MainActivity, "未找到任何可用图片", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun fetchImages(): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()
        val apiUrl = "https://li.gdtv.cn/art/api/gallery_info"
        try {
            val url = URL(apiUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 8000
            val out = conn.outputStream
            out.write("{}".toByteArray())
            out.flush()
            out.close()

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                val urlsArray = jsonObject.optJSONObject("data")?.optJSONArray("urls")
                
                if (urlsArray != null && urlsArray.length() > 0) {
                    // ✅ 只有成功获取到新数据后，才清理旧的缓存图片
                    cacheDir.listFiles()?.filter { it.name.startsWith("img_") }?.forEach { it.delete() }
                    
                    for (i in 0 until urlsArray.length()) {
                        val imgUrl = urlsArray.getString(i)
                        val localPath = downloadAndSave(imgUrl, "img_$i.jpg")
                        if (localPath != null) list.add(localPath)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching images", e)
        }
        list
    }

    private suspend fun downloadAndSave(urlStr: String, fileName: String): String? = withContext(Dispatchers.IO) {
        try {
            val finalUrlStr = urlStr.replace("artlocal.lzz.asia", "10.0.2.2").replace("127.0.0.1", "10.0.2.2")
            val url = URL(finalUrlStr)
            val conn = url.openConnection() as HttpURLConnection
            if (conn.responseCode == 200) {
                val file = File(cacheDir, fileName)
                conn.inputStream.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
                return@withContext file.absolutePath
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Download failed: $urlStr", e)
        }
        null
    }

    private suspend fun loadCachedImages(): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()
        cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("img_") }
            ?.sortedBy { it.name }
            ?.forEach { list.add(it.absolutePath) }
        list
    }

    override fun onDestroy() {
        super.onDestroy()
        autoSlideJob?.cancel()
    }
}
