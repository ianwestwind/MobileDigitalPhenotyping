package com.app.modules.gps

import com.google.gson.annotations.SerializedName
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsFormat
import edu.stanford.screenomics.core.debug.PipelineDiagnosticsRegistry
import edu.stanford.screenomics.core.debug.PipelineLogTags
import edu.stanford.screenomics.core.collection.GpsLocationRawFrame
import edu.stanford.screenomics.core.collection.GpsRawCaptureFrame
import edu.stanford.screenomics.core.collection.RawModalityFrame
import edu.stanford.screenomics.core.module.template.BaseAdapter
import edu.stanford.screenomics.core.unified.DataDescription
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.UfsEnvelopeVersions
import edu.stanford.screenomics.core.unified.UfsReservedDataKeys
import edu.stanford.screenomics.core.unified.UnifiedDataPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

private const val KEY_LAT: String = "gps.fix.latitudeDegrees"
private const val KEY_LON: String = "gps.fix.longitudeDegrees"
private const val KEY_SUN: String = "gps.weather.sunScore0To10"

private fun sunScoreFromOpenMeteoWmo(wmoCode: Long, weatherPlaceholder: Long): Double {
    if (weatherPlaceholder == 1L) {
        return 5.0
    }
    val code = wmoCode.toInt().coerceIn(0, 199)
    return when (code) {
        0 -> 10.0
        in 1..3 -> 8.0
        45, 48 -> 2.0
        in 51..57 -> 4.0
        in 61..67 -> 3.0
        in 71..77 -> 1.0
        in 80..82 -> 4.0
        in 85..86 -> 1.0
        in 95..99 -> 0.0
        else -> 6.0
    }.coerceIn(0.0, 10.0)
}

private fun sunScoreFromOpenWeatherConditionId(conditionId: Int): Double =
    when (conditionId) {
        800 -> 10.0
        801 -> 9.0
        802 -> 7.0
        803 -> 4.0
        804 -> 2.0
        in 200..232 -> 1.0
        in 300..321 -> 4.0
        in 500..531 -> 3.0
        in 600..622 -> 2.0
        in 701..781 -> 2.0
        else -> 5.0
    }.coerceIn(0.0, 10.0)

private interface OpenWeatherCurrentApi {
    @GET("weather")
    suspend fun current(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("appid") appId: String,
        @Query("units") units: String = "metric",
    ): OpenWeatherCurrentResponse
}

private data class OpenWeatherCurrentResponse(
    @SerializedName("weather") val weather: List<OpenWeatherCondition>?,
)

private data class OpenWeatherCondition(
    @SerializedName("id") val id: Int?,
)

private interface OpenMeteoForecastApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") currentParams: String = "weather_code",
    ): OpenMeteoForecastResponse
}

private data class OpenMeteoForecastResponse(
    @SerializedName("current") val current: OpenMeteoCurrent?,
)

private data class OpenMeteoCurrent(
    @SerializedName("weather_code") val weatherCode: Double?,
)

/**
 * Gradle module `:modules:gps` — [BaseAdapter] emitting only lat/lon + sun score (Open-Meteo and optional OpenWeatherMap).
 */
class GpsAdapter(
    adapterId: String,
    private val captureSessionId: String,
    private val producerNodeId: String,
    private val openMeteoBaseUrl: String = "https://api.open-meteo.com/",
    private val openWeatherMapApiKey: String? = null,
    private val openWeatherMapBaseUrl: String = "https://api.openweathermap.org/data/2.5/",
    private val sourceLabel: String = "android:LocationManager",
    private val acquisitionMethodLabel: String = "Retrofit.openMeteo+openWeather+UFS",
    private val ufsEnvelopeVersion: String = UfsEnvelopeVersions.V1,
) : BaseAdapter(adapterId = adapterId, modality = ModalityKind.GPS) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val openMeteoApi: OpenMeteoForecastApi by lazy {
        Retrofit.Builder()
            .baseUrl(openMeteoBaseUrl)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenMeteoForecastApi::class.java)
    }

    private val openWeatherApi: OpenWeatherCurrentApi by lazy {
        Retrofit.Builder()
            .baseUrl(openWeatherMapBaseUrl)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenWeatherCurrentApi::class.java)
    }

    override suspend fun adaptRaw(raw: RawModalityFrame): UnifiedDataPoint =
        when (raw) {
            is GpsLocationRawFrame -> adaptLocation(raw)
            is GpsRawCaptureFrame -> adaptLegacyPlaceholder(raw)
            else -> error("unsupported RawModalityFrame for gps: ${raw::class.simpleName}")
        }

    private suspend fun adaptLocation(raw: GpsLocationRawFrame): UnifiedDataPoint {
        val owmConditionId = fetchOpenWeatherConditionId(raw.latitudeDegrees, raw.longitudeDegrees)
        val sun = if (owmConditionId != null) {
            sunScoreFromOpenWeatherConditionId(owmConditionId)
        } else {
            val current = fetchOpenMeteoWeatherCode(raw.latitudeDegrees, raw.longitudeDegrees)
            val wmo = (current?.weatherCode ?: 0.0).roundToLong().coerceIn(0L, 200L)
            val placeholder = if (current?.weatherCode == null) 1L else 0L
            sunScoreFromOpenMeteoWmo(wmo, placeholder)
        }
        val sunSource = if (owmConditionId != null) "openweather(id=$owmConditionId)" else "openMeteoWmo"
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.GPS,
            stage = "adapter_weather_transform",
            dataType = "gps_fix_weather",
            detail = "[ADAPTER][GPS_MODULE] lat=${raw.latitudeDegrees} lon=${raw.longitudeDegrees} sunScore=$sun sunSource=$sunSource " +
                "timestamp=${raw.capturedAtEpochMillis}",
        )
        return buildPoint(
            raw = raw,
            lat = raw.latitudeDegrees,
            lon = raw.longitudeDegrees,
            sun = sun,
        )
    }

    private fun adaptLegacyPlaceholder(raw: GpsRawCaptureFrame): UnifiedDataPoint =
        buildPoint(
            raw = raw,
            lat = 0.0,
            lon = 0.0,
            sun = sunScoreFromOpenMeteoWmo(0L, 1L),
        )

    private suspend fun fetchOpenMeteoWeatherCode(lat: Double, lon: Double): OpenMeteoCurrent? =
        withContext(Dispatchers.IO) {
            runCatching {
                openMeteoApi.forecast(latitude = lat, longitude = lon).current
            }.getOrNull()
        }

    private suspend fun fetchOpenWeatherConditionId(lat: Double, lon: Double): Int? {
        val key = openWeatherMapApiKey?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                openWeatherApi.current(latitude = lat, longitude = lon, appId = key)
                    .weather
                    ?.firstOrNull()
                    ?.id
            }.getOrNull()
        }
    }

    private fun buildPoint(
        raw: RawModalityFrame,
        lat: Double,
        lon: Double,
        sun: Double,
    ): UnifiedDataPoint {
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val latC = lat.coerceIn(-90.0, 90.0)
        val lonC = lon.coerceIn(-180.0, 180.0)
        val sunC = sun.coerceIn(0.0, 10.0)
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            KEY_LAT to latC,
            KEY_LON to lonC,
            KEY_SUN to sunC,
        )
        val metadata = DataDescription(
            source = sourceLabel,
            timestamp = Instant.ofEpochMilli(raw.capturedAtEpochMillis),
            acquisitionMethod = acquisitionMethodLabel,
            modality = ModalityKind.GPS,
            captureSessionId = captureSessionId,
            producerNodeId = producerNodeId,
            producerAdapterId = adapterId,
            ufsEnvelopeVersion = ufsEnvelopeVersion,
        )
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.GPS,
            stage = "adapter_metadata_attachment",
            dataType = "gps_ufs",
            detail = "[ADAPTER][GPS_MODULE] metadata={ ${PipelineDiagnosticsFormat.dataDescription(metadata)} }",
        )
        return UnifiedDataPoint(data = data, metadata = metadata)
    }
}
