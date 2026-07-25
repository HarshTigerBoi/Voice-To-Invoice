package com.voicetoinvoice.app.network

object SupabaseConfig {
    const val SUPABASE_URL = "https://lyowklxsbfznnqridtgr.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx5b3drbHhzYmZ6bm5xcmlkdGdyIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ2NTk3MTAsImV4cCI6MjEwMDIzNTcxMH0.NDcTTRT-1K8Rb3mkelzvZPSvYY8m0mTUDdtf4kJWQRQ"
    const val STT_PROXY_ENDPOINT = "$SUPABASE_URL/functions/v1/process-voice-job"

    fun getNullSafeShopId(shopId: String?): String? {
        if (shopId == null || shopId.isBlank() || shopId == "default_shop" || shopId.length != 36) {
            return null
        }
        return shopId
    }
}
