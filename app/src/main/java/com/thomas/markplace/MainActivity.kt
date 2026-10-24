package com.thomas.markplace

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MarkPlace — mark your current location, name it, save coordinates,
 * reopen any saved place in MAPS.ME or Google Maps, delete saved spots,
 * or export/import spots with Documents/Obsidian Vault.
 */
class MainActivity : Activity() {

    private lateinit var spotsFile: File
    private lateinit var nameInput: EditText
    private lateinit var coordsInput: EditText
    private lateinit var listAdapter: ArrayAdapter<String>
    private val spots = mutableListOf<Triple<String, String, String>>() // name, coords, timestamp (Newest first)

    private val PERMISSION_REQUEST = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        spotsFile = File(getExternalFilesDir(null), "saved_spots.txt")
        if (!spotsFile.exists()) spotsFile.writeText("")

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

        val importButton = Button(this).apply { text = "📥 Import list from Documents" }
        layout.addView(importButton)

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

        // Tap a spot to show action dialog (Open, Copy, Delete)
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in spots.indices) {
                val (name, coords, ts) = spots[position]
                val options = arrayOf("🗺 Open in MAPS.ME", "📋 Copy coordinates", "🗑 Delete spot")
                AlertDialog.Builder(this)
                    .setTitle(name)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> openInMaps(coords, name)
                            1 -> {
                                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("coords", coords))
                                Toast.makeText(this, "Copied: $coords", Toast.LENGTH_SHORT).show()
                            }
                            2 -> {
                                AlertDialog.Builder(this)
                                    .setTitle("Delete Spot?")
                                    .setMessage("Delete '$name'?")
                                    .setPositiveButton("Delete") { _, _ -> deleteSpot(position) }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    }
                    .show()
            }
        }

        // Long-press opens delete confirmation directly
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position in spots.indices) {
                val (name, _, _) = spots[position]
                AlertDialog.Builder(this)
                    .setTitle("Delete Spot?")
                    .setMessage("Delete '$name'?")
                    .setPositiveButton("Delete") { _, _ -> deleteSpot(position) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }

        saveManualButton.setOnClickListener {
            val name = nameInput.text.toString().trim().ifEmpty { "Unnamed spot" }
            val coords = extractCoords(coordsInput.text.toString().trim()) ?: run {
                Toast.makeText(this, "Rejected: \"${coordsInput.text}\" — use 27.915817,34.329946", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val (la, lo) = coords.split(",").map { it.toDouble() }
            saveSpot(name, la, lo)
            coordsInput.setText("")
        }

        exportButton.setOnClickListener { exportList() }
        importButton.setOnClickListener { importList() }

        openMapsButton.setOnClickListener {
            val text = nameInput.text.toString().trim()
            val coords = extractCoords(text) ?: run {
                Toast.makeText(this, "Rejected: \"${coordsInput.text}\" — use 27.915817,34.329946", Toast.LENGTH_LONG).show()
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

    private fun deleteSpot(position: Int) {
        if (position in spots.indices) {
            val (name, _, _) = spots[position]
            spots.removeAt(position)
            val lines = spots.reversed().map { (n, c, t) -> "$n | $c | $t" }
            val content = if (lines.isNotEmpty()) lines.joinToString("\n") + "\n" else ""
            spotsFile.writeText(content)
            loadSpots()
            Toast.makeText(this, "Deleted $name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportList() {
        try {
            val content = spotsFile.readText()
            val resolver = contentResolver
            // Delete any previous export we own, then write a fresh copy via MediaStore
            resolver.delete(
                MediaStore.Files.getContentUri("external"),
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " = ?",
                arrayOf("Documents/", "markplace_spots.txt")
            )
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "markplace_spots.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents")
            }
            var uri = try {
                resolver.insert(MediaStore.Files.getContentUri("external"), values)
            } catch (e: Exception) {
                null // SQLiteConstraintException: stale row from an older, foreign-owned file
            }
            if (uri == null) {
                // Fall back to a timestamped name so the export still succeeds
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, "markplace_spots_$ts.txt")
                uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: throw IllegalStateException("MediaStore insert returned null")
            }
            resolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
            Toast.makeText(this, "Exported to Documents/markplace_spots.txt", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importList() {
        try {
            // Scoped storage: read Documents file via MediaStore; Obsidian Vault falls back to direct read (usually readable)
            var importedText: String? = null
            var sourceName = ""
            try {
                val resolver = contentResolver
                val collection = MediaStore.Files.getContentUri("external")
                val sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " = ?"
                resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), sel, arrayOf("Documents/", "markplace_spots.txt"), null)?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(0)
                        val uri = android.content.ContentUris.withAppendedId(collection, id)
                        resolver.openInputStream(uri)!!.bufferedReader().use { importedText = it.readText() }
                        sourceName = "Documents/markplace_spots.txt"
                    }
                }
            } catch (e: Exception) {
                // fall through to direct path
            }
            if (importedText == null) {
                val alt = File("/storage/emulated/0/Obsidian Vault/markplace_spots.txt")
                if (alt.exists()) {
                    importedText = alt.readText()
                    sourceName = "Obsidian Vault/markplace_spots.txt"
                }
            }
            if (importedText == null) {
                Toast.makeText(this, "No markplace_spots.txt found to import", Toast.LENGTH_LONG).show()
                return
            }

            val existingLines = if (spotsFile.exists()) spotsFile.readLines().map { it.trim() }.toSet() else emptySet()
            val importedLines = (importedText ?: "").lines().filter { it.isNotBlank() }

            var addedCount = 0
            for (line in importedLines) {
                val trimmed = line.trim()
                if (trimmed !in existingLines) {
                    spotsFile.appendText("$trimmed\n")
                    addedCount++
                }
            }

            loadSpots()
            Toast.makeText(this, "Imported $addedCount new spot(s)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSpots() {
        spots.clear()
        if (spotsFile.exists()) {
            spotsFile.readLines().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split(" | ")
                if (parts.size >= 2) spots.add(Triple(parts[0], parts[1], parts.getOrElse(2) { "" }))
            }
        }
        spots.reverse()
        listAdapter.clear()
        for ((name, coords, ts) in spots) {
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
        val label = Uri.encode(name ?: "MarkPlace spot")
        val geoUri = Uri.parse("geo:$coords?q=$coords($label)")
        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
        try {
            startActivity(mapIntent)
        } catch (_: Exception) {
            val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$coords")
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
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
