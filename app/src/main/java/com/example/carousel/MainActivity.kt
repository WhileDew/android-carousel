package com.example.carousel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*
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
        setContentView(R.layout.activity_main)

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
        val baseUrl =
            "https://lishipin-file.oss-cn-guangzhou.aliyuncs.com/li/fyjk_2505/asset/temp/"

        var loadedImages = fetchImages(baseUrl)
        if (loadedImages.isEmpty()) {
            Toast.makeText(this@MainActivity, "网络加载失败，尝试加载缓存图片…", Toast.LENGTH_SHORT).show()
            loadedImages = loadCachedImages()
        }

        // 去掉右侧多余图片
        loadedImages = loadedImages.filter { !it.contains("banner") && !it.contains("android") }

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

    /** 网络加载图片 */
    private suspend fun fetchImages(baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()
        var index = 1
        while (true) {
            val urlStr = "$baseUrl$index.jpg"
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.connect()
                if (conn.responseCode != 200) break

                list.add(urlStr)
                saveImageToCache(urlStr, "img_$index.jpg")
                index++
            } catch (e: Exception) {
                break
            }
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
            val file = File(cacheDir, fileName)
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
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
