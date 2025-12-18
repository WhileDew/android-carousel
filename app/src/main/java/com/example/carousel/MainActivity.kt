package com.example.carousel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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
    private val slideDuration = 300L // 滑动动画时间，单位 ms

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置全屏，隐藏状态栏
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)

        setContentView(R.layout.activity_main)

        // 隐藏 ActionBar
        supportActionBar?.hide()

        // 隐藏导航栏并实现沉浸式效果
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        viewPager = findViewById(R.id.viewPager)

        // Android 5 启用 TLS 1.2
        try {
            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, null, null)
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        adapter = ImageSliderAdapter(mutableListOf())
        viewPager.adapter = adapter

        // 无限循环逻辑
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val lastIndex = adapter.itemCount - 1
                when (position) {
                    0 -> postDelayedSwitch(lastIndex - 1)
                    lastIndex -> postDelayedSwitch(1)
                }
            }
        })

        lifecycleScope.launch {
            loadData()
        }
    }

    /** 延迟切换页面，避免闪烁 */
    private fun postDelayedSwitch(targetPosition: Int) {
        viewPager.post {
            viewPager.setCurrentItem(targetPosition, false)
        }
    }

    /** 自动轮播 */
    private fun startAutoSlide() {
        autoSlideJob?.cancel()
        autoSlideJob = lifecycleScope.launch {
            while (isActive) {
                delay(5000) // 每 5 秒轮播
                smoothScrollToNext()
            }
        }
    }

    /** 使用自定义滑动时间滑动到下一页 */
    private fun smoothScrollToNext() {
        val recyclerView = viewPager.getChildAt(0) as RecyclerView
        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateTimeForScrolling(dx: Int): Int {
                return slideDuration.toInt() // 设置动画时间
            }
        }
        scroller.targetPosition = viewPager.currentItem + 1
        recyclerView.layoutManager?.startSmoothScroll(scroller)
    }

    /** 加载网络或缓存图片 */
    private suspend fun loadData() = withContext(Dispatchers.Main) {
        var loadedImages = fetchImages()
        if (loadedImages.isEmpty()) {
            Toast.makeText(this@MainActivity, "网络加载失败，尝试加载缓存图片…", Toast.LENGTH_SHORT).show()
            loadedImages = loadCachedImages()
        }

        if (loadedImages.isNotEmpty()) {
            // 无限循环 Adapter 需要前后各加一张
            val loopedList = mutableListOf<String>()
            loopedList.add(loadedImages.last())
            loopedList.addAll(loadedImages)
            loopedList.add(loadedImages.first())

            images.clear()
            images.addAll(loopedList)
            adapter.updateImages(images)

            // 设置初始位置为真实第一张
            viewPager.setCurrentItem(1, false)

            // 启动自动轮播
            startAutoSlide()
        } else {
            Toast.makeText(this@MainActivity, "未找到任何图片", Toast.LENGTH_LONG).show()
        }
    }

    /** 通过接口获取图片路径 */
    private suspend fun fetchImages(): List<String> = withContext(Dispatchers.IO) {
        // 清除旧的缓存
        cacheDir.listFiles()?.forEach { file ->
            file.delete()
        }

        val list = mutableListOf<String>()
        val apiUrl = "http://artlocal.lzz.asia/art/api/gallery_info"
        
        try {
            val url = URL(apiUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            // 写入请求体 {}
            val out = conn.outputStream
            out.write("{}".toByteArray())
            out.flush()
            out.close()

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                val data = jsonObject.optJSONObject("data")
                val urlsArray = data?.optJSONArray("urls")
                
                if (urlsArray != null) {
                    for (i in 0 until urlsArray.length()) {
                        val imgUrl = urlsArray.getString(i)
                        list.add(imgUrl)
                        // 打印获取到的url
                        Log.d("MainActivity", "Fetched URL [$i]: $imgUrl")
                        // 保存到缓存
                        saveImageToCache(imgUrl, "img_$i.jpg")
                    }
                }
            } else {
                Log.e("MainActivity", "API Request failed with code: ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching images", e)
        }
        list
    }

    /** 保存图片到缓存 */
    private suspend fun saveImageToCache(urlStr: String, fileName: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val bmp = BitmapFactory.decodeStream(conn.inputStream)
            if (bmp != null) {
                val file = File(cacheDir, fileName)
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 读取缓存图片 */
    private fun loadCachedImages(): List<String> {
        val list = mutableListOf<String>()
        cacheDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            list.add(file.absolutePath)
        }
        return list
    }

    override fun onDestroy() {
        super.onDestroy()
        autoSlideJob?.cancel()
    }
}
