package com.thomas.markplace

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MarkPlace — mark your current location, name it, save coordinates,
 * and reopen any saved place in Google Maps later.
 */
class MainActivity : Activity() {

    private lateinit var spotsFile: File
    private lateinit var nameInput: EditText
    private lateinit var coordsInput: EditText
    private lateinit var listAdapter: ArrayAdapter<String>
    private val spots = mutableListOf<Triple<String, String, String>>() // name, coords, timestamp

    private val PERMISSION_REQUEST = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        spotsFile = File(getExternalFilesDir(null), "saved_spots.txt")
        if (!spotsFile.exists()) spotsFile.writeText("")

        // Simple vertical layout built programmatically — no XML needed
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        nameInput = EditText(this).apply {
            hint = "Name of this place (e.g. Home, Dive boat)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        layout.addView(nameInput)

        val markButton = Button(this).apply { text = "📍 Mark current location" }
        layout.addView(markButton)

        coordsInput = EditText(this).apply {
            hint = "Coordinates to save by hand (e.g. 27.915817,34.329946)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        layout.addView(coordsInput)

        val saveManualButton = Button(this).apply { text = "💾 Save entered coordinates" }
        layout.addView(saveManualButton)

        val exportButton = Button(this).apply { text = "📤 Export list to Documents" }
        layout.addView(exportButton)

        val openMapsButton = Button(this).apply { text = "🗺 Open plain coordinates in Maps" }
        layout.addView(openMapsButton)

        val listView = ListView(this)
        layout.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(layout)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listView.adapter = listAdapter
        loadSpots()

        markButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    PERMISSION_REQUEST
                )
            } else {
                markCurrentLocation()
            }
        }

        // Long-press a saved place to open it in Google Maps
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val (name, coords, _) = spots[position]
            openInMaps(coords, name)
            true
        }

        // Tap a saved place to copy the coordinates to clipboard
        listView.setOnItemClickListener { _, _, position, _ ->
            val (_, coords, _) = spots[position]
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("coords", coords))
            Toast.makeText(this, "Copied: $coords", Toast.LENGTH_SHORT).show()
        }

        saveManualButton.setOnClickListener {
            val name = nameInput.text.toString().trim().ifEmpty { "Unnamed spot" }
            val coords = extractCoords(coordsInput.text.toString()) ?: run {
                Toast.makeText(this, "Enter coordinates like 27.915817,34.329946", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val (la, lo) = coords.split(",").map { it.toDouble() }
            saveSpot(name, la, lo)
            coordsInput.setText("")
        }

        exportButton.setOnClickListener { exportList() }

        openMapsButton.setOnClickListener {
            val text = nameInput.text.toString().trim()
            val coords = extractCoords(text) ?: run {
                Toast.makeText(this, "Enter coordinates like 27.915817,34.329946", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            openInMaps(coords, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun markCurrentLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val name = nameInput.text.toString().trim().ifEmpty { "Unnamed spot" }

        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER
        )
        var lastFix: android.location.Location? = null
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p)
                if (loc != null && (lastFix == null || loc.time > lastFix!!.time)) lastFix = loc
            } catch (_: SecurityException) { }
        }

        if (lastFix != null) {
            saveSpot(name, lastFix.latitude, lastFix.longitude)
        } else {
            // No cached fix — request a fresh one
            lm.getCurrentLocation(
                android.location.LocationManager.GPS_PROVIDER,
                null,
                mainExecutor
            ) { loc ->
                if (loc != null) {
                    runOnUiThread { saveSpot(name, loc.latitude, loc.longitude) }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "No GPS fix — go outside and retry", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun saveSpot(name: String, lat: Double, lon: Double) {
        val coords = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        spotsFile.appendText("$name | $coords | $ts\n")
        loadSpots()
        nameInput.setText("")
        Toast.makeText(this, "Saved $name at $coords", Toast.LENGTH_LONG).show()
    }

    private fun exportList() {
        try {
            val docs = File("/storage/emulated/0/Documents")
            if (!docs.exists()) docs.mkdirs()
            val out = File(docs, "markplace_spots.txt")
            out.writeText(spotsFile.readText())
            Toast.makeText(this, "Exported to Documents/markplace_spots.txt", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSpots() {
        spots.clear()
        spotsFile.readLines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split(" | ")
            if (parts.size >= 2) spots.add(Triple(parts[0], parts[1], parts.getOrElse(2) { "" }))
        }
        listAdapter.clear()
        for ((name, coords, ts) in spots.reversed()) {
            listAdapter.add("$name\n$coords  ·  $ts")
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun extractCoords(text: String): String? {
        val regex = Regex("""(-?\d{1,3}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)""")
        val m = regex.find(text) ?: return null
        val (lat, lon) = m.destructured
        val la = lat.toDouble(); val lo = lon.toDouble()
        if (la < -90 || la > 90 || lo < -180 || lo > 180) return null
        return String.format(Locale.US, "%.6f,%.6f", la, lo)
    }

    private fun openInMaps(coords: String, name: String?) {
        // api=1 query works in both Google Maps app and any browser
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$coords")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            markCurrentLocation()
        } else {
            Toast.makeText(this, "Location permission needed to mark a place", Toast.LENGTH_LONG).show()
        }
    }
}
