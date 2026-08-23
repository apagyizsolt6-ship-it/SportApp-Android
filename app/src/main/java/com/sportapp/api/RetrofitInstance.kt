package com.sportapp.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitInstance {
    // IDE MÁSOLD BE A REPLIT FÖLDGÖMB MÖGÖTTI LINKET (per jellel a végén!)
    private const val BASE_URL = "https://A_TE_REPLIT_LINKED.replit.app/"

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
