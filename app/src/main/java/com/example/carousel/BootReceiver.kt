package com.example.carousel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "===== Broadcast Received: $action =====")
        
        if (action == Intent.ACTION_BOOT_COMPLETED 
            || action == "android.intent.action.QUICKBOOT_POWERON"
            || action == "com.example.carousel.TEST_BOOT") {
            
            try {
                // 💡 获取启动 Intent
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    // 💡 组合最强力的标志位：
                    // NEW_TASK: 在 Receiver 中启动必加
                    // CLEAR_TASK: 清除该任务栈中所有的 Activity，确保从头开始
                    // CLEAR_TOP: 如果已运行，则将其上的所有 Activity 关掉
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or 
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    
                    Log.d("BootReceiver", "Starting MainActivity with CLEAR_TASK flag...")
                    context.startActivity(launchIntent)
                    Log.d("BootReceiver", "startActivity call finished.")
                } else {
                    Log.e("BootReceiver", "Launch intent is null!")
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error during startActivity", e)
            }
        }
    }
}
