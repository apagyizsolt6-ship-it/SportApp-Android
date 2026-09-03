package com.sportapp.api

import android.content.Context
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object RetrofitInstance {
    private const val BASE_URL = "https://sportapp-android.onrender.com/"

    @Volatile
    private var appContext: Context? = null

    /** Hívd MainActivity.onCreate-ben – disk cache + gyorsabb ismételt betöltés. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val cacheInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        // GET lista: rövid kliens-oldali cache (szerver Cache-Control nélkül is)
        if (request.method == "GET" && request.url.encodedPath.contains("/api/matches")
            && !request.url.encodedPath.contains("/api/matches/")
        ) {
            response.newBuilder()
                .header("Cache-Control", "public, max-age=20")
                .build()
        } else {
            response
        }
    }

    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addNetworkInterceptor(cacheInterceptor)

        appContext?.let { ctx ->
            val dir = File(ctx.cacheDir, "http_cache")
            builder.cache(Cache(dir, 15L * 1024L * 1024L)) // 15 MB
        }
        builder.build()
    }

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
