package com.example

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KatPlugin: Plugin() {
    private var activity: AppCompatActivity? = null

    companion object {
        private var isLoaded = false
        private val katProviderInstance by lazy { KatProvider() }
    }

    override fun load(context: Context) {
        // Prevent duplicate loading
        if (isLoaded) return
        isLoaded = true

        activity = context as? AppCompatActivity

        // All providers should be added in this manner - use cached instance
        registerMainAPI(katProviderInstance)

        openSettings = {
            val frag = KatFragment(this)
            activity?.let {
                frag.show(it.supportFragmentManager, "KatSettings")
            }
        }
    }
}
