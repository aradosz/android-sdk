package com.pushpushgo.sdk.network

import com.pushpushgo.sdk.BuildConfig
import com.pushpushgo.sdk.network.data.TokenRequest
import com.pushpushgo.sdk.network.data.TokenResponse
import com.pushpushgo.sdk.network.interceptor.RequestInterceptor
import com.pushpushgo.sdk.network.interceptor.ResponseInterceptor
import com.pushpushgo.sdk.utils.PlatformType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.create
import retrofit2.http.*

internal interface ApiService {

    @POST("{projectId}/subscriber")
    suspend fun registerSubscriber(
        @Path("projectId") projectId: String,
        @Body body: TokenRequest
    ): TokenResponse

    @DELETE("{projectId}/subscriber/{subscriberId}")
    suspend fun unregisterSubscriber(
        @Path("projectId") projectId: String,
        @Path("subscriberId") subscriberId: String
    ): Response<Void>

    @POST("{projectId}/subscriber/{subscriberId}/beacon")
    suspend fun sendBeacon(
        @Path("projectId") projectId: String,
        @Path("subscriberId") subscriberId: String,
        @Body beacon: RequestBody
    ): Response<Void>

    @POST("{projectId}/event/")
    suspend fun sendEvent(
        @Path("projectId") projectId: String,
        @Body event: RequestBody
    ): Response<Void>

    @GET
    suspend fun getRawResponse(@Url url: String): ResponseBody

    companion object {
        operator fun invoke(
            requestInterceptor: RequestInterceptor,
            responseInterceptor: ResponseInterceptor,
            platformType: PlatformType
        ): ApiService {
            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(requestInterceptor)
                .addInterceptor(responseInterceptor)
                .addNetworkInterceptor(
                    HttpLoggingInterceptor().setLevel(
                        if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                        else HttpLoggingInterceptor.Level.NONE
                    )
                )
                .build()

            return Retrofit.Builder()
                .client(okHttpClient)
                .baseUrl("${BuildConfig.BASE_URL}/v1/${platformType.apiName}/")
                .addConverterFactory(MoshiConverterFactory.create())
                .build().create()
        }
    }
}
