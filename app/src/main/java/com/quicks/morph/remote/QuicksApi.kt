package com.quicks.morph.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.GET

data class IceServersWrapper(
    @SerializedName("iceServers") val servers: IceServers
)

data class IceServers(
    @SerializedName("urls") val urls: List<String>,
    @SerializedName("username") val username: String,
    @SerializedName("credential") val credential: String
)

data class IceResponse(
    @SerializedName("s")
    val status: String,
    @SerializedName("v")
    val data: IceServersWrapper
)

interface QuicksAPI {
    @GET("/ice")
    fun getIceServers(): Call<IceResponse>
}