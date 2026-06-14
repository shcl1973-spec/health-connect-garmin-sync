package com.example.healthgarminexporter

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusView: TextView
    private var healthConnectClient: HealthConnectClient? = null

    private val permissions = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val missing = permissions - granted
        statusView.text = if (missing.isEmpty()) {
            "All Health Connect permissions granted."
        } else {
            "Missing permissions: ${missing.joinToString()}"
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            statusView.text = "Export cancelled."
            return@registerForActivityResult
        }

        val file = File(cacheDir, EXPORT_FILE_NAME)
        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        }
        statusView.text = "Export saved."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val availability = HealthConnectClient.getSdkStatus(this)
        if (availability == HealthConnectClient.SDK_AVAILABLE) {
            healthConnectClient = HealthConnectClient.getOrCreate(this)
        }

        statusView = TextView(this).apply {
            textSize = 16f
            text = if (healthConnectClient == null) {
                "Health Connect is not available on this device."
            } else {
                "Ready."
            }
        }

        val grantButton = Button(this).apply {
            text = "Grant Health Connect Permissions"
            isEnabled = healthConnectClient != null
            setOnClickListener { permissionLauncher.launch(permissions) }
        }

        val exportButton = Button(this).apply {
            text = "Export Last 30 Days"
            isEnabled = healthConnectClient != null
            setOnClickListener { exportLast30Days() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 48, 32, 48)
            addView(grantButton, buttonLayoutParams())
            addView(exportButton, buttonLayoutParams())
            addView(statusView, textLayoutParams())
        }

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun exportLast30Days() {
        val client = healthConnectClient ?: return
        scope.launch {
            try {
                statusView.text = "Reading Health Connect..."
                val end = Instant.now()
                val start = end.minus(Duration.ofDays(30))
                val json = withContext(Dispatchers.IO) {
                    buildExportJson(client, start, end)
                }
                val file = File(cacheDir, EXPORT_FILE_NAME)
                file.writeText(json.toString(2), Charsets.UTF_8)
                statusView.text = "Export ready. Choose where to save it."
                createDocumentLauncher.launch(EXPORT_FILE_NAME)
            } catch (error: Exception) {
                statusView.text = "Export failed: ${error.message}"
            }
        }
    }

    private suspend fun buildExportJson(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): JSONObject {
        val timeRange = TimeRangeFilter.between(start, end)

        val weights = client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = timeRange,
            )
        ).records

        val bloodPressures = client.readRecords(
            ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = timeRange,
            )
        ).records

        val heartRates = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = timeRange,
            )
        ).records

        return JSONObject()
            .put("exported_at", Instant.now().toString())
            .put("weight", JSONArray().also { array ->
                weights
                    .sortedBy { it.time }
                    .forEach { record ->
                        array.put(
                            JSONObject()
                                .put("time", formatInstant(record.time))
                                .put("kg", record.weight.inKilograms)
                        )
                    }
            })
            .put("blood_pressure", JSONArray().also { array ->
                bloodPressures
                    .sortedBy { it.time }
                    .forEach { record ->
                        array.put(
                            JSONObject()
                                .put("time", formatInstant(record.time))
                                .put("systolic", record.systolic.inMillimetersOfMercury.toInt())
                                .put("diastolic", record.diastolic.inMillimetersOfMercury.toInt())
                                .put("pulse", nearestPulse(record.time, heartRates))
                        )
                    }
            })
    }

    private fun nearestPulse(
        measurementTime: Instant,
        heartRates: List<HeartRateRecord>,
    ): Int? {
        val nearest = heartRates
            .flatMap { it.samples }
            .minByOrNull { sample ->
                abs(Duration.between(sample.time, measurementTime).toMillis())
            }
            ?: return null

        val age = abs(Duration.between(nearest.time, measurementTime).toMinutes())
        return if (age <= MAX_PULSE_DISTANCE_MINUTES) {
            nearest.beatsPerMinute.toInt()
        } else {
            null
        }
    }

    private fun formatInstant(instant: Instant): String {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            instant.atZone(ZoneId.systemDefault())
        )
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = 24
        }
    }

    private fun textLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private companion object {
        const val EXPORT_FILE_NAME = "health-connect-garmin-export.json"
        const val MAX_PULSE_DISTANCE_MINUTES = 5
    }
}
