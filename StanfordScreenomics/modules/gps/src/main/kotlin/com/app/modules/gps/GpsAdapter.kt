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
import edu.stanford.screenomics.core.unified.DataEntity
import edu.stanford.screenomics.core.unified.EntityAttributeSpec
import edu.stanford.screenomics.core.unified.EntityRelationship
import edu.stanford.screenomics.core.unified.EntityTypeSpec
import edu.stanford.screenomics.core.unified.ModalityKind
import edu.stanford.screenomics.core.unified.NumericEntityValueRange
import edu.stanford.screenomics.core.unified.ProvenanceRecord
import edu.stanford.screenomics.core.unified.UnboundedEntityValueRange
import edu.stanford.screenomics.core.unified.UfsEnvelopeVersions
import edu.stanford.screenomics.core.unified.UfsReservedDataKeys
import edu.stanford.screenomics.core.unified.UfsSchemaComposition
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

private const val GPS_SCHEMA_ID: String = "com.app.modules.gps/ufs-schema"
private const val GPS_SCHEMA_REVISION: String = "3"

private const val KEY_LAT: String = "gps.fix.latitudeDegrees"
private const val KEY_LON: String = "gps.fix.longitudeDegrees"
private const val KEY_ACC: String = "gps.fix.horizontalAccuracyMeters"
private const val KEY_PROVIDER: String = "gps.fix.providerName"
private const val KEY_TEMP: String = "gps.weather.temperatureCelsius"
private const val KEY_HUM: String = "gps.weather.relativeHumidityPercent"
private const val KEY_WIND: String = "gps.weather.windSpeedMs"
private const val KEY_WMO: String = "gps.weather.wmoWeatherCode"
private const val KEY_WEATHER_PLACEHOLDER: String = "gps.weather.enrichmentPlaceholder"
private const val KEY_SUN: String = "gps.weather.sunScore0To10"

private fun buildGpsPayloadDataEntity(): DataEntity = UfsSchemaComposition.compose(
    schemaId = GPS_SCHEMA_ID,
    schemaRevision = GPS_SCHEMA_REVISION,
    payloadAttributes = mapOf(
        KEY_LAT to EntityAttributeSpec(
            required = true,
            semanticDescription = "Fix latitude in decimal degrees (WGS-84)",
            structureTag = "scalar.double",
        ),
        KEY_LON to EntityAttributeSpec(
            required = true,
            semanticDescription = "Fix longitude in decimal degrees (WGS-84)",
            structureTag = "scalar.double",
        ),
        KEY_ACC to EntityAttributeSpec(
            required = true,
            semanticDescription = "Horizontal accuracy in meters; -1 when unknown",
            structureTag = "scalar.double",
        ),
        KEY_PROVIDER to EntityAttributeSpec(
            required = true,
            semanticDescription = "Android location provider or fused source label",
            structureTag = "scalar.string",
        ),
        KEY_TEMP to EntityAttributeSpec(
            required = true,
            semanticDescription = "Open-Meteo current air temperature at fix (°C), or neutral when placeholder",
            structureTag = "scalar.double",
        ),
        KEY_HUM to EntityAttributeSpec(
            required = true,
            semanticDescription = "Open-Meteo relative humidity at fix (0–100%), or 0 when placeholder",
            structureTag = "scalar.double",
        ),
        KEY_WIND to EntityAttributeSpec(
            required = true,
            semanticDescription = "Open-Meteo wind speed at 10 m (m/s), or 0 when placeholder",
            structureTag = "scalar.double",
        ),
        KEY_WMO to EntityAttributeSpec(
            required = true,
            semanticDescription = "WMO weather interpretation code from Open-Meteo current block",
            structureTag = "scalar.long",
        ),
        KEY_WEATHER_PLACEHOLDER to EntityAttributeSpec(
            required = true,
            semanticDescription = "1 if weather enrichment used fallback values (network/schema guard); 0 when API values applied",
            structureTag = "scalar.long",
        ),
        KEY_SUN to EntityAttributeSpec(
            required = true,
            semanticDescription = "Heuristic sunniness 0 (storm/dark) .. 10 (clear): OpenWeatherMap condition id when an API key is set, else Open-Meteo WMO weather_code; neutral ~5 when enrichment placeholder",
            structureTag = "scalar.double",
        ),
    ),
    payloadTypes = mapOf(
        KEY_LAT to EntityTypeSpec("gps.LatitudeDegrees", "kotlin.Double", "1"),
        KEY_LON to EntityTypeSpec("gps.LongitudeDegrees", "kotlin.Double", "1"),
        KEY_ACC to EntityTypeSpec("gps.HorizontalAccuracyMeters", "kotlin.Double", "1"),
        KEY_PROVIDER to EntityTypeSpec("gps.ProviderName", "kotlin.String", "1"),
        KEY_TEMP to EntityTypeSpec("gps.WeatherTemperatureC", "kotlin.Double", "1"),
        KEY_HUM to EntityTypeSpec("gps.WeatherRelativeHumidity", "kotlin.Double", "1"),
        KEY_WIND to EntityTypeSpec("gps.WeatherWindSpeedMs", "kotlin.Double", "1"),
        KEY_WMO to EntityTypeSpec("gps.WeatherWmoCode", "kotlin.Long", "1"),
        KEY_WEATHER_PLACEHOLDER to EntityTypeSpec("gps.WeatherEnrichmentPlaceholder", "kotlin.Long", "1"),
        KEY_SUN to EntityTypeSpec("gps.WeatherSunScore", "kotlin.Double", "1"),
    ),
    payloadValueRanges = mapOf(
        KEY_LAT to NumericEntityValueRange(-90.0, 90.0, null),
        KEY_LON to NumericEntityValueRange(-180.0, 180.0, null),
        KEY_ACC to NumericEntityValueRange(-1.0, 1_000_000.0, null),
        KEY_PROVIDER to UnboundedEntityValueRange("gps-provider-label"),
        KEY_TEMP to NumericEntityValueRange(-100.0, 70.0, null),
        KEY_HUM to NumericEntityValueRange(0.0, 100.0, null),
        KEY_WIND to NumericEntityValueRange(0.0, 150.0, null),
        KEY_WMO to NumericEntityValueRange(0.0, 200.0, null),
        KEY_WEATHER_PLACEHOLDER to NumericEntityValueRange(0.0, 1.0, 1.0),
        KEY_SUN to NumericEntityValueRange(0.0, 10.0, null),
    ),
    relationships = listOf(
        EntityRelationship(
            subjectAttributeKey = KEY_LAT,
            predicateToken = "anchors",
            objectAttributeKey = KEY_TEMP,
            bidirectional = false,
            cardinalityHint = "1:1",
        ),
        EntityRelationship(
            subjectAttributeKey = KEY_WMO,
            predicateToken = "informs",
            objectAttributeKey = KEY_SUN,
            bidirectional = false,
            cardinalityHint = "1:1",
        ),
    ),
)

/** Maps Open-Meteo [OpenMeteoCurrent.weatherCode] (WMO) to a 0–10 “sun” score when OpenWeatherMap is not used. */
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

/** Maps [OpenWeatherMap condition codes](https://openweathermap.org/weather-conditions) to 0–10. */
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
        @Query("current") currentParams: String = "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m",
    ): OpenMeteoForecastResponse
}

private data class OpenMeteoForecastResponse(
    @SerializedName("current") val current: OpenMeteoCurrent?,
)

private data class OpenMeteoCurrent(
    @SerializedName("temperature_2m") val temperature2m: Double?,
    @SerializedName("relative_humidity_2m") val relativeHumidity2m: Double?,
    @SerializedName("weather_code") val weatherCode: Double?,
    @SerializedName("wind_speed_10m") val windSpeed10m: Double?,
)

/**
 * Gradle module `:modules:gps` — [BaseAdapter] enriching each fix with **Open-Meteo** current weather (no key) and,
 * when [openWeatherMapApiKey] is set, **OpenWeatherMap** current conditions for [KEY_SUN] (weather score).
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
    private val dataEntity: DataEntity = buildGpsPayloadDataEntity(),
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
        val current = fetchOpenMeteoCurrent(raw.latitudeDegrees, raw.longitudeDegrees)
        val placeholder = if (current == null || current.temperature2m == null) 1L else 0L
        val temp = (current?.temperature2m ?: 0.0)
        val hum = (current?.relativeHumidity2m ?: 0.0)
        val wind = (current?.windSpeed10m ?: 0.0)
        val wmo = (current?.weatherCode ?: 0.0).roundToLong().coerceIn(0L, 200L)
        val accMeters = raw.horizontalAccuracyMeters?.toDouble() ?: -1.0
        val provider = raw.providerName.ifBlank { "unknown" }
        val owmConditionId = fetchOpenWeatherConditionId(raw.latitudeDegrees, raw.longitudeDegrees)
        val sunFromOwm = owmConditionId?.let { sunScoreFromOpenWeatherConditionId(it) }
        val sun = sunFromOwm ?: sunScoreFromOpenMeteoWmo(wmo, placeholder)
        val sunSource = if (sunFromOwm != null) "openweather(id=$owmConditionId)" else "openMeteoWmo"
        PipelineDiagnosticsRegistry.emit(
            logTag = PipelineLogTags.ADAPTER,
            module = ModalityKind.GPS,
            stage = "adapter_weather_transform",
            dataType = "gps_fix_weather",
            detail = "[ADAPTER][GPS_MODULE] cleaning+weather lat=${raw.latitudeDegrees} lon=${raw.longitudeDegrees} " +
                "accMeters=$accMeters provider=$provider tempC=$temp humPct=$hum windMs=$wind wmo=$wmo sunScore=$sun sunSource=$sunSource " +
                "weatherPlaceholder=$placeholder timestamp=${raw.capturedAtEpochMillis}",
        )
        return buildPoint(
            raw = raw,
            lat = raw.latitudeDegrees,
            lon = raw.longitudeDegrees,
            acc = accMeters,
            provider = provider,
            temp = temp,
            hum = hum,
            wind = wind,
            wmo = wmo,
            sun = sun,
            weatherPlaceholder = placeholder,
            provenanceHop = "gps.adapt.location",
            provenanceNote = "openMeteo=current;sunScore=$sunSource;weatherPlaceholder=$placeholder",
        )
    }

    private fun adaptLegacyPlaceholder(raw: GpsRawCaptureFrame): UnifiedDataPoint =
        buildPoint(
            raw = raw,
            lat = 0.0,
            lon = 0.0,
            acc = -1.0,
            provider = raw.providerNamePlaceholder.ifBlank { "legacy-placeholder" },
            temp = 0.0,
            hum = 0.0,
            wind = 0.0,
            wmo = 0L,
            sun = sunScoreFromOpenMeteoWmo(0L, 1L),
            weatherPlaceholder = 1L,
            provenanceHop = "gps.adapt.legacyPlaceholder",
            provenanceNote = "providerNamePlaceholder=${raw.providerNamePlaceholder}",
        )

    private suspend fun fetchOpenMeteoCurrent(lat: Double, lon: Double): OpenMeteoCurrent? =
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
        acc: Double,
        provider: String,
        temp: Double,
        hum: Double,
        wind: Double,
        wmo: Long,
        sun: Double,
        weatherPlaceholder: Long,
        provenanceHop: String,
        provenanceNote: String,
    ): UnifiedDataPoint {
        val monoNanos = System.nanoTime()
        val correlationString = raw.correlationId.value
        val latC = lat.coerceIn(-90.0, 90.0)
        val lonC = lon.coerceIn(-180.0, 180.0)
        val accC = acc.coerceIn(-1.0, 1_000_000.0)
        val tempC = temp.coerceIn(-100.0, 70.0)
        val humC = hum.coerceIn(0.0, 100.0)
        val windC = wind.coerceIn(0.0, 150.0)
        val wmoC = wmo.coerceIn(0L, 200L)
        val sunC = sun.coerceIn(0.0, 10.0)
        val phC = weatherPlaceholder.coerceIn(0L, 1L)
        val data = linkedMapOf<String, Any>(
            UfsReservedDataKeys.CORRELATION_ID to correlationString,
            UfsReservedDataKeys.MONOTONIC_TIMESTAMP_NANOS to monoNanos,
            UfsReservedDataKeys.PROVENANCE_RECORDS to listOf(
                ProvenanceRecord(
                    hopName = provenanceHop,
                    componentId = adapterId,
                    recordedAtEpochMillis = System.currentTimeMillis(),
                    note = provenanceNote,
                ),
            ),
            KEY_LAT to latC,
            KEY_LON to lonC,
            KEY_ACC to accC,
            KEY_PROVIDER to provider,
            KEY_TEMP to tempC,
            KEY_HUM to humC,
            KEY_WIND to windC,
            KEY_WMO to wmoC,
            KEY_SUN to sunC,
            KEY_WEATHER_PLACEHOLDER to phC,
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
        return UnifiedDataPoint(
            data = data,
            metadata = metadata,
            schema = dataEntity,
        )
    }
}
